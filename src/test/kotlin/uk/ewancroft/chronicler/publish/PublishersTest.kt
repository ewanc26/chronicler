package uk.ewancroft.chronicler.publish

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import uk.ewancroft.chronicler.config.NewspaperConfig
import uk.ewancroft.chronicler.news.Newspaper
import uk.ewancroft.chronicler.news.NewspaperSection
import uk.ewancroft.chronicler.news.PrintedIssue
import uk.ewancroft.chronicler.news.Story
import java.awt.image.BufferedImage
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublishersTest {
    @TempDir lateinit var dir: Path
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val calls = CopyOnWriteArrayList<Pair<String, String>>()
    private val records = CopyOnWriteArrayList<JsonObject>()
    private val json = Json

    private val newspaper = NewspaperConfig(title = "The Weekly Chronicle", author = "C", storiesPerSection = 5, showStatistics = true, serverName = "Croft SMP")
    private val issue = Newspaper(7, 0, 1_790_000_000_000, listOf(
        NewspaperSection("Headlines", listOf(Story("Bridge Mended", "Volunteers repaired the north bridge.", listOf("Alex"), null))),
        NewspaperSection("Obituaries", listOf(Story("Steve — 1 death", "Fell.", listOf("Steve"), null))),
    ))
    private val printed = PrintedIssue(issue, listOf(BufferedImage(1024, 1536, BufferedImage.TYPE_INT_RGB)))

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes()
            val path = ex.requestURI.path
            calls += path to (ex.requestHeaders.getFirst("Authorization") ?: "")
            val reply = when {
                path.endsWith("/.well-known/atproto-did") -> null
                path == "/xrpc/com.atproto.identity.resolveHandle" -> """{"did":"did:plc:abc123"}"""
                path == "/did:plc:abc123" -> """{"id":"did:plc:abc123","service":[{"id":"#atproto_pds","type":"AtprotoPersonalDataServer","serviceEndpoint":"$base"}]}"""
                path == "/xrpc/com.atproto.server.createSession" -> {
                    val req = json.parseToJsonElement(body.decodeToString()).jsonObject
                    if (req["password"]?.jsonPrimitive?.content == "app-pass") """{"did":"did:plc:abc123","accessJwt":"jwt-1"}""" else null
                }
                path == "/xrpc/com.atproto.repo.uploadBlob" -> """{"blob":{"${'$'}type":"blob","ref":{"${'$'}link":"bafkcover"},"mimeType":"image/jpeg","size":${body.size}}}"""
                path == "/xrpc/com.atproto.repo.putRecord" -> {
                    val req = json.parseToJsonElement(body.decodeToString()).jsonObject
                    records += req
                    """{"uri":"at://did:plc:abc123/${req["collection"]!!.jsonPrimitive.content}/${req["rkey"]!!.jsonPrimitive.content}","cid":"bafyrec"}"""
                }
                path == "/webhook" -> { records += JsonObject(mapOf("multipart" to kotlinx.serialization.json.JsonPrimitive(String(body, Charsets.ISO_8859_1)))); "" }
                else -> null
            }
            if (reply == null) { ex.sendResponseHeaders(if (path.contains("createSession")) 401 else 404, -1) }
            else { val b = reply.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
            ex.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun publisher(announce: Boolean = true, password: String = "app-pass") = StandardSitePublisher(
        StandardSiteConfig(enabled = true, identifier = "news.example.com", appPassword = password, announce = announce),
        newspaper, dir.resolve("standard-site.json"), Logger.getAnonymousLogger(), { "https://news.example.com" },
        AtprotoClient(publicApi = base, plcDirectory = base),
    )

    @Test
    fun `resolves the account, publishes publication, cover, post and document`() {
        val p = publisher()
        p.publish(printed)
        assertTrue(calls.any { it.first == "/xrpc/com.atproto.identity.resolveHandle" })
        assertTrue(calls.filter { it.first.contains("repo.") }.all { it.second == "Bearer jwt-1" })

        val byCollection = records.associateBy { it["collection"]!!.jsonPrimitive.content }
        val pub = byCollection.getValue("site.standard.publication")["record"]!!.jsonObject
        assertEquals("https://news.example.com", pub["url"]!!.jsonPrimitive.content)
        assertEquals("The Weekly Chronicle", pub["name"]!!.jsonPrimitive.content)

        val doc = byCollection.getValue("site.standard.document")["record"]!!.jsonObject
        assertEquals(p.state.publicationUri, doc["site"]!!.jsonPrimitive.content)
        assertEquals("/issue/7", doc["path"]!!.jsonPrimitive.content)
        assertEquals("bafkcover", doc["coverImage"]!!.jsonObject["ref"]!!.jsonObject["\$link"]!!.jsonPrimitive.content)
        assertTrue("Volunteers repaired" in doc["textContent"]!!.jsonPrimitive.content)
        assertTrue(doc["bskyPostRef"]!!.jsonObject["uri"]!!.jsonPrimitive.content.contains("app.bsky.feed.post"))

        val post = byCollection.getValue("app.bsky.feed.post")["record"]!!.jsonObject
        assertEquals("https://news.example.com/issue/7", post["embed"]!!.jsonObject["external"]!!.jsonObject["uri"]!!.jsonPrimitive.content)
        assertNotNull(p.documentUri(7))
    }

    @Test
    fun `republishing updates the same records and does not post again`() {
        publisher().publish(printed)
        val firstDocKey = records.first { it["collection"]!!.jsonPrimitive.content == "site.standard.document" }["rkey"]
        records.clear()
        publisher().publish(printed)  // a fresh instance reads the saved state
        assertEquals(firstDocKey, records.first { it["collection"]!!.jsonPrimitive.content == "site.standard.document" }["rkey"])
        assertTrue(records.none { it["collection"]!!.jsonPrimitive.content == "app.bsky.feed.post" })
    }

    @Test
    fun `bad credentials or a non-https site publish nothing`() {
        publisher(password = "wrong").publish(printed)
        assertTrue(records.isEmpty())
        StandardSitePublisher(StandardSiteConfig(enabled = true, identifier = "did:plc:abc123", appPassword = "app-pass", pdsUrl = base),
            newspaper, dir.resolve("s2.json"), Logger.getAnonymousLogger(), { "http://insecure.example" }).publish(printed)
        assertTrue(records.isEmpty())
        assertNull(publisher().documentUri(7))
    }

    @Test
    fun `a partial postUri-without-postCid state does not abort the whole publish`() {
        // Simulates a hand-edited or partially-written state file: postUri present, postCid
        // missing. This must not crash publish() via a bare !! before the document is written.
        val stateFile = dir.resolve("standard-site.json")
        stateFile.toFile().writeText("""
            {"did":"did:plc:abc123","publicationRkey":"pub1","publicationUri":"at://did:plc:abc123/site.standard.publication/pub1",
             "documents":{"7":{"rkey":"doc1","uri":"at://did:plc:abc123/site.standard.document/doc1","postUri":"at://did:plc:abc123/app.bsky.feed.post/old","postCid":null}}}
        """.trimIndent())
        val p = StandardSitePublisher(
            StandardSiteConfig(enabled = true, identifier = "news.example.com", appPassword = "app-pass", announce = true),
            newspaper, stateFile, Logger.getAnonymousLogger(), { "https://news.example.com" },
            AtprotoClient(publicApi = base, plcDirectory = base),
        )
        p.publish(printed)
        assertTrue(records.any { it["collection"]!!.jsonPrimitive.content == "site.standard.document" },
            "the document must still be (re)published instead of the whole publish silently aborting")
        assertNotNull(p.documentUri(7))
    }

    @Test
    fun `tids are 13 sortable characters`() {
        val a = Tid.next(); val b = Tid.next()
        assertEquals(13, a.length)
        assertTrue(b > a)
    }

    @Test
    fun `discord payload carries the embed, image and only the configured mention`() {
        val d = DiscordPublisher(DiscordConfig(enabled = true, webhookUrl = "https://discord.example/webhook", mention = "<@&42>"), newspaper,
            Logger.getAnonymousLogger(), { "https://news.example.com" })
        val payload = d.payload(printed, hasImage = true)
        val embed = payload["embeds"]!!.jsonArray.single().jsonObject
        assertEquals("https://news.example.com/issue/7", embed["url"]!!.jsonPrimitive.content)
        assertEquals("attachment://front-page.png", embed["image"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("Obituaries", embed["fields"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("42"), payload["allowed_mentions"]!!.jsonObject["roles"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(payload["allowed_mentions"]!!.jsonObject["parse"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `discord webhook upload is multipart with payload and png`() {
        DiscordPublisher(DiscordConfig(enabled = true, webhookUrl = "$base/webhook"), newspaper, Logger.getAnonymousLogger(), { null })
            .publish(printed)
        val body = records.single()["multipart"]!!.jsonPrimitive.content
        assertTrue("name=\"payload_json\"" in body && "The Weekly Chronicle, No. 7" in body)
        assertTrue("filename=\"front-page.png\"" in body && "\u0089PNG" in body)
    }

    @Test
    fun `discord refuses plain-http webhooks on real hosts`() {
        DiscordPublisher(DiscordConfig(enabled = true, webhookUrl = "http://discord.example/webhook"), newspaper, Logger.getAnonymousLogger(), { null })
            .publish(printed)
        assertTrue(records.isEmpty())
    }
}
