package uk.ewancroft.chronicler.news

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import uk.ewancroft.chronicler.config.NewspaperConfig
import uk.ewancroft.chronicler.config.WebConfig
import java.nio.file.Path
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
}
