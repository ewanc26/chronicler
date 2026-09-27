package uk.ewancroft.chronicler.integration

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import uk.ewancroft.chronicler.config.NewspaperConfig
import uk.ewancroft.chronicler.config.PrivacyConfig
import uk.ewancroft.chronicler.news.ChronicleEvent
import uk.ewancroft.chronicler.news.EventStore
import uk.ewancroft.chronicler.news.EventType
import uk.ewancroft.chronicler.news.NewspaperGenerator
import java.nio.file.Path
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapMarkersTest {
    @TempDir lateinit var dir: Path
    private val config = NewspaperConfig(title = "T", author = "A", storiesPerSection = 5, showStatistics = false)

    private fun issue(includeCoordinates: Boolean) = EventStore(dir.resolve("e.json")).let { store ->
        store.record(ChronicleEvent(EventType.DEATH, System.currentTimeMillis(), "Steve", "u", "world",
            mapOf("message" to "Steve fell <script>", "x" to "120", "y" to "70", "z" to "-40")))
        NewspaperGenerator(store, config, null, false, Logger.getAnonymousLogger(),
            privacyConfig = PrivacyConfig(false, false, includeCoordinates, emptySet())).generate(1, 0, System.currentTimeMillis())
    }

    @Test
    fun `stories are located only when coordinates may be published`() {
        assertTrue(MapMarkers.located(issue(includeCoordinates = false), null).isEmpty())
        val stories = MapMarkers.located(issue(includeCoordinates = true), "https://news.example.com/issue/1")
        val s = stories.first()
        assertEquals(listOf(120, 70, -40), listOf(s.x, s.y, s.z))
        assertTrue("&lt;script&gt;" in s.detailHtml && "<script>" !in s.detailHtml)
        assertTrue("https://news.example.com/issue/1" in s.detailHtml)
    }
}
