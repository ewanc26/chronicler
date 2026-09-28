package uk.ewancroft.chronicler.news

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import uk.ewancroft.chronicler.config.NewspaperConfig
import uk.ewancroft.chronicler.config.WebConfig
import uk.ewancroft.chronicler.publish.AtprotoClient
import uk.ewancroft.chronicler.publish.StandardSiteConfig
import uk.ewancroft.chronicler.publish.StandardSitePublisher
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.logging.Logger
import kotlin.io.path.readText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebRendererTest {

    @TempDir
    lateinit var tempDir: Path

    private val payload = "<script>alert(1)</script>"

    private fun render(): Path {
        val renderer = WebRenderer(
            WebConfig(enabled = true, port = 0, writeFiles = true),
            NewspaperConfig(title = "Test Chronicle", author = "Tester", storiesPerSection = 5, showStatistics = false),
            tempDir,
        )
        val story = Story(headline = "Headline $payload", body = "Body $payload", players = listOf(payload), eventType = EventType.CHAT)
        renderer.renderAndServe(Newspaper(1, 0L, 1L, listOf(NewspaperSection("News", listOf(story)))))
        return tempDir
    }

    @Test
    fun `html edition escapes headlines, bodies and player names`() {
        val html = render().resolve("index.html").readText()
        assertFalse(payload in html, "raw markup leaked into HTML")
        assertTrue("&lt;script&gt;alert(1)&lt;/script&gt;" in html)
    }

    @Test
    fun `rss feed escapes story text`() {
        val rss = render().resolve("rss.xml").readText()
        assertFalse(payload in rss, "raw markup leaked into RSS")
    }

    @Test
    fun `standard site document link only appears on the page it verifies`() {
        val port = ServerSocket(0).use { it.localPort }
        val config = NewspaperConfig(title = "Test Chronicle", author = "Tester", storiesPerSection = 5, showStatistics = false)
        val archive = ArchiveStore(tempDir.resolve("archive"), retention = 10, logger = Logger.getAnonymousLogger())
        val renderer = WebRenderer(WebConfig(enabled = true, port = port, writeFiles = false), config, tempDir.resolve("web"), archive)

        // Seed the publisher's state directly: only the *latest* issue (#2) has a
        // published document. This is exactly the case that distinguishes correct
        // behaviour from the bug: buggy code resolved the link for any non-"/issue/"
        // path (including /archive and /search) from latestNewspaper's issue number.
        val stateFile = tempDir.resolve("standard-site.json")
        stateFile.parent.toFile().mkdirs()
        stateFile.toFile().writeText("""
            {"did":"did:plc:abc","publicationRkey":"r1","publicationUri":"at://did:plc:abc/site.standard.publication/r1",
             "documents":{"2":{"rkey":"r2","uri":"at://did:plc:abc/site.standard.document/r2"}}}
        """.trimIndent())
        val standardSite = StandardSitePublisher(
            StandardSiteConfig(enabled = true, identifier = "news.example.com", appPassword = "x"),
            config, stateFile, Logger.getAnonymousLogger(), { null }, AtprotoClient(),
        )
        renderer.standardSite = standardSite

        val issue1 = Newspaper(1, 0L, 1L, listOf(NewspaperSection("News", listOf(Story("H", "B", emptyList(), EventType.CHAT)))))
        val issue2 = Newspaper(2, 1L, 2L, listOf(NewspaperSection("News", listOf(Story("H2", "B2", emptyList(), EventType.CHAT)))))
        archive.archive(issue1)
        archive.archive(issue2)
        renderer.renderAndServe(issue2) // issue #2 is now "latest", and is the one with a published document

        val client = HttpClient.newHttpClient()
        fun get(path: String): String =
            client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).build(), HttpResponse.BodyHandlers.ofString()).body()

        assertTrue("site.standard.document" in get("/"), "root mirrors the latest issue, which has a document")
        assertTrue("site.standard.document" in get("/issue/2"), "issue #2 has a published document and must carry its link")
        assertFalse("site.standard.document" in get("/issue/1"), "issue #1 has no document of its own")
        assertFalse("site.standard.document" in get("/archive"), "archive index must never claim to be an issue's document")
        assertFalse("site.standard.document" in get("/search"), "search page must never claim to be an issue's document")
        renderer.stop()
    }
}
