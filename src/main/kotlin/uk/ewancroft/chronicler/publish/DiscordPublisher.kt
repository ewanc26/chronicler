package uk.ewancroft.chronicler.publish

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import uk.ewancroft.chronicler.config.NewspaperConfig
import uk.ewancroft.chronicler.news.PrintedIssue
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger

data class DiscordConfig(
    val enabled: Boolean = false,
    /** A channel webhook URL (Channel settings > Integrations > Webhooks). */
    val webhookUrl: String = "",
    /** Optional role to mention, e.g. "<@&123456789>" or "@here". */
    val mention: String = "",
    /** Without a webhook, post a link through DiscordSRV's main channel if it is installed. */
    val useDiscordSrv: Boolean = true,
)

/** Posts each new issue to Discord with the front page as the embed image. */
class DiscordPublisher(
    private val config: DiscordConfig,
    private val newspaper: NewspaperConfig,
    private val logger: Logger,
    private val baseUrl: () -> String?,
    private val discordSrv: ((String) -> Boolean)? = null,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    /** Blocking; call from the printing thread. */
    fun publish(printed: PrintedIssue) {
        if (!config.enabled) return
        try {
            when {
                config.webhookUrl.isNotBlank() -> postWebhook(printed)
                config.useDiscordSrv && discordSrv != null -> {
                    if (!discordSrv.invoke(plainAnnouncement(printed))) logger.warning("DiscordSRV has no main channel to post issue #${printed.issue.issueNumber} to.")
                }
                else -> logger.warning("Discord publishing is on but no webhook-url is set (and DiscordSRV is not available).")
            }
        } catch (e: Exception) {
            logger.warning("Could not post issue #${printed.issue.issueNumber} to Discord: ${e.message}")
        }
    }

    private fun postWebhook(printed: PrintedIssue) {
        val uri = URI.create(config.webhookUrl)
        require(uri.scheme == "https" || uri.host in setOf("127.0.0.1", "localhost")) { "webhook-url must be an https:// Discord webhook" }
        val boundary = "chronicler-" + UUID.randomUUID()
        val body = ByteArrayOutputStream()
        fun part(headers: String, content: ByteArray) {
            body.write("--$boundary\r\n$headers\r\n\r\n".toByteArray())
            body.write(content)
            body.write("\r\n".toByteArray())
        }
        val image = printed.png.firstOrNull()
        part("Content-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json",
            payload(printed, hasImage = image != null).toString().toByteArray())
        if (image != null) part("Content-Disposition: form-data; name=\"files[0]\"; filename=\"front-page.png\"\r\nContent-Type: image/png", image)
        body.write("--$boundary--\r\n".toByteArray())

        val request = HttpRequest.newBuilder(URI.create(config.webhookUrl))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        // Never log the webhook URL: it is a credential.
        if (response.statusCode() !in 200..299) error("Discord answered ${response.statusCode()}")
        logger.info("Posted issue #${printed.issue.issueNumber} to Discord.")
    }

    internal fun payload(printed: PrintedIssue, hasImage: Boolean): JsonObject {
        val issue = printed.issue
        val lead = issue.sections.firstOrNull { it.stories.isNotEmpty() }?.stories?.first()
        val url = baseUrl()?.let { "$it/issue/${issue.issueNumber}" }
        return buildJsonObject {
            put("username", newspaper.title.take(80))
            if (config.mention.isNotBlank()) put("content", config.mention)
            putJsonObject("allowed_mentions") {
                // Only the configured mention may ping; story text never can.
                putJsonArray("parse") { if (config.mention == "@here" || config.mention == "@everyone") add("everyone") }
                Regex("<@&(\\d+)>").find(config.mention)?.let { m -> putJsonArray("roles") { add(m.groupValues[1]) } }
            }
            putJsonArray("embeds") {
                addJsonObject {
                    put("title", "${newspaper.title}, No. ${issue.issueNumber}".take(256))
                    url?.let { put("url", it) }
                    put("color", newspaper.accentColor)
                    put("timestamp", Instant.ofEpochMilli(issue.toTime).toString())
                    lead?.let { put("description", "**${it.headline}**\n${it.body}".take(1500)) }
                    putJsonArray("fields") {
                        issue.sections.filter { it.stories.isNotEmpty() }.drop(1).take(6).forEach { section ->
                            addJsonObject {
                                put("name", section.title.take(256))
                                put("value", section.stories.take(3).joinToString("\n") { "• ${it.headline}" }.take(1024))
                                put("inline", true)
                            }
                        }
                    }
                    if (hasImage) putJsonObject("image") { put("url", "attachment://front-page.png") }
                    putJsonObject("footer") { put("text", newspaper.titlePageText.take(2048)) }
                }
            }
        }
    }

    private fun plainAnnouncement(printed: PrintedIssue): String {
        val lead = printed.issue.sections.firstOrNull { it.stories.isNotEmpty() }?.stories?.first()
        return buildString {
            if (config.mention.isNotBlank()) append(config.mention).append(' ')
            append("**${newspaper.title}, No. ${printed.issue.issueNumber}** is out")
            lead?.let { append(": ${it.headline}") }
            baseUrl()?.let { append("\n$it/issue/${printed.issue.issueNumber}") }
        }
    }
}
