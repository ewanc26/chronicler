package uk.ewancroft.chronicler.publish

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import uk.ewancroft.chronicler.config.NewspaperConfig
import uk.ewancroft.chronicler.news.Newspaper
import uk.ewancroft.chronicler.news.PrintedIssue
import uk.ewancroft.chronicler.util.quarantineCorrupt
import uk.ewancroft.chronicler.util.writeAtomically
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.logging.Level
import java.util.logging.Logger
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

data class StandardSiteConfig(
    val enabled: Boolean = false,
    /** Handle (e.g. news.example.com) or DID of the account that publishes. */
    val identifier: String = "",
    /** An app password, never the account password. */
    val appPassword: String = "",
    /** Skip PDS discovery (handle -> DID -> DID document) and use this PDS. */
    val pdsUrl: String = "",
    /** Also post each new issue to Bluesky with a link card. */
    val announce: Boolean = false,
)

@Serializable
data class PublishedIssue(val rkey: String, val uri: String, val postUri: String? = null, val postCid: String? = null)

@Serializable
data class StandardSiteState(
    val did: String? = null,
    val publicationRkey: String? = null,
    val publicationUri: String? = null,
    val documents: Map<Int, PublishedIssue> = emptyMap(),
)

/**
 * Publishes each issue as a Standard.site document under a publication for
 * the server's newspaper, so it can be read in Standard.site readers and
 * (optionally) announced on Bluesky. Record keys are persisted, so
 * republishing an issue updates its records in place.
 */
class StandardSitePublisher(
    private val config: StandardSiteConfig,
    private val newspaper: NewspaperConfig,
    private val stateFile: Path,
    private val logger: Logger,
    private val baseUrl: () -> String?,
    private val client: AtprotoClient = AtprotoClient(),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Volatile
    var state: StandardSiteState = load()
        private set

    private fun load(): StandardSiteState {
        if (!Files.exists(stateFile)) return StandardSiteState()
        return try {
            json.decodeFromString(StandardSiteState.serializer(), Files.readString(stateFile))
        } catch (e: Exception) {
            stateFile.quarantineCorrupt(logger, e)
            StandardSiteState()
        }
    }

    private fun save(next: StandardSiteState) {
        state = next
        stateFile.writeAtomically(json.encodeToString(StandardSiteState.serializer(), next))
    }

    fun documentUri(issueNumber: Int): String? = state.documents[issueNumber]?.uri

    /** Blocking; call from the printing thread. */
    fun publish(printed: PrintedIssue) {
        if (!config.enabled) return
        val base = baseUrl()
        if (base == null || !base.startsWith("https://")) {
            logger.warning("Standard.site publishing needs web.public-url set to the public https:// address of the web edition.")
            return
        }
        if (config.identifier.isBlank() || config.appPassword.isBlank()) {
            logger.warning("Standard.site publishing needs publish.standard-site.identifier and app-password.")
            return
        }
        val issue = printed.issue
        try {
            val session = client.login(config.identifier, config.appPassword, config.pdsUrl)
            var current = state
            if (current.did != null && current.did != session.did) current = StandardSiteState() // a different account
            current = current.copy(did = session.did)

            val pubRkey = current.publicationRkey ?: Tid.next()
            val publication = client.putRecord(session, "site.standard.publication", pubRkey, publicationRecord(base, session))
            current = current.copy(publicationRkey = pubRkey, publicationUri = publication.uri)
            save(current)

            val cover = printed.pages.firstOrNull()?.let { client.uploadBlob(session, jpeg(it), "image/jpeg") }
            val existing = current.documents[issue.issueNumber]
            // postUri/postCid are independent nullable fields on a persisted record, so a
            // hand-edited or partially-written state file could carry one without the other;
            // treat that as "no post recorded" rather than crashing the whole publish on !!.
            val existingPost = existing?.postUri?.let { uri -> existing.postCid?.let { cid -> AtprotoClient.StrongRef(uri, cid) } }
            val post = if (config.announce && existingPost == null) announce(session, issue, base, cover) else existingPost
            val rkey = existing?.rkey ?: Tid.next()
            val document = client.putRecord(session, "site.standard.document", rkey,
                documentRecord(issue, publication.uri, cover, post))
            save(current.copy(documents = current.documents + (issue.issueNumber to PublishedIssue(rkey, document.uri, post?.uri, post?.cid))))
            logger.info("Published issue #${issue.issueNumber} to Standard.site: ${document.uri}")
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Could not publish issue #${issue.issueNumber} to Standard.site: ${e.message}")
        }
    }

    internal fun publicationRecord(base: String, session: AtprotoClient.Session): JsonObject = buildJsonObject {
        put("\$type", "site.standard.publication")
        put("url", base)
        put("name", newspaper.title)
        put("description", buildString {
            append(newspaper.titlePageText)
            if (newspaper.serverName.isNotBlank()) append(" The newspaper of ${newspaper.serverName}.")
        })
    }

    internal fun documentRecord(issue: Newspaper, publicationUri: String, cover: JsonElement?, post: AtprotoClient.StrongRef?): JsonObject {
        val lead = issue.sections.firstOrNull { it.stories.isNotEmpty() }?.stories?.first()
        return buildJsonObject {
            put("\$type", "site.standard.document")
            put("site", publicationUri)
            put("title", "${newspaper.title}, No. ${issue.issueNumber}")
            put("path", "/issue/${issue.issueNumber}")
            put("publishedAt", Instant.ofEpochMilli(issue.toTime).toString())
            lead?.let { put("description", "${it.headline}. ${it.body}".take(300)) }
            put("textContent", textContent(issue))
            putJsonArray("tags") {
                add("minecraft"); add("newspaper")
                if (newspaper.serverName.isNotBlank()) add(newspaper.serverName)
            }
            cover?.let { put("coverImage", it) }
            post?.let { putJsonObject("bskyPostRef") { put("uri", it.uri); put("cid", it.cid) } }
        }
    }

    private fun announce(session: AtprotoClient.Session, issue: Newspaper, base: String, thumb: JsonElement?): AtprotoClient.StrongRef {
        val lead = issue.sections.firstOrNull { it.stories.isNotEmpty() }?.stories?.first()
        val url = "$base/issue/${issue.issueNumber}"
        val record = buildJsonObject {
            put("\$type", "app.bsky.feed.post")
            put("text", "${newspaper.title}, No. ${issue.issueNumber}" + (lead?.let { ": ${it.headline}" } ?: "").take(250))
            put("createdAt", Instant.now().toString())
            putJsonArray("langs") { add(newspaper.javaLocale.language.ifBlank { "en" }) }
            putJsonObject("embed") {
                put("\$type", "app.bsky.embed.external")
                putJsonObject("external") {
                    put("uri", url)
                    put("title", "${newspaper.title}, No. ${issue.issueNumber}")
                    put("description", lead?.let { "${it.headline}. ${it.body}".take(280) } ?: newspaper.titlePageText)
                    thumb?.let { put("thumb", it) }
                }
            }
        }
        return client.putRecord(session, "app.bsky.feed.post", Tid.next(), record)
    }

    companion object {
        /** Plain text of the whole issue, for search and text-only readers. */
        fun textContent(issue: Newspaper): String = buildString {
            for (section in issue.sections.filter { it.stories.isNotEmpty() }) {
                append(section.title.uppercase()).append("\n\n")
                for (story in section.stories) {
                    append(story.headline).append('\n').append(story.body).append("\n\n")
                }
            }
        }.trim()

        /** Cover images must stay under the 1 MB blob limit: JPEG, reduced until it fits. */
        fun jpeg(page: BufferedImage, limit: Int = 950_000): ByteArray {
            var image = page
            var quality = 0.85f
            while (true) {
                val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).also { it.createGraphics().drawImage(image, 0, 0, null) }
                val out = ByteArrayOutputStream()
                val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
                ImageIO.createImageOutputStream(out).use { stream ->
                    writer.output = stream
                    val params = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality }
                    writer.write(null, IIOImage(rgb, null, null), params)
                }
                writer.dispose()
                if (out.size() <= limit || image.width < 300) return out.toByteArray()
                if (quality > 0.6f) quality -= 0.1f else {
                    val smaller = BufferedImage(image.width * 3 / 4, image.height * 3 / 4, BufferedImage.TYPE_INT_RGB)
                    smaller.createGraphics().drawImage(image, 0, 0, smaller.width, smaller.height, null)
                    image = smaller
                }
            }
        }
    }
}
