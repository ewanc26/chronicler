package uk.ewancroft.chronicler.news

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import uk.ewancroft.chronicler.config.NewspaperConfig
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewspaperTypesetterTest {

    private val config = NewspaperConfig(
        title = "The Weekly Chronicle",
        author = "Chronicler",
        storiesPerSection = 5,
        showStatistics = true,
        serverName = "Croft SMP",
        titlePageText = "All the news that fits, in blocks.",
    )

    private fun sample(): Newspaper {
        val json = NewspaperTypesetterTest::class.java.getResource("/sample-issue.json")!!.readText()
        return Json { ignoreUnknownKeys = true }.decodeFromString(json)
    }

    @Test
    fun `typesets full-size pages and writes previews`() {
        val pages = NewspaperTypesetter(config).typeset(sample())
        assertTrue(pages.isNotEmpty())
        val out = File("build/newspaper-preview").apply { mkdirs() }
        pages.forEachIndexed { i, page ->
            assertEquals(NewspaperTypesetter.PAGE_WIDTH, page.width)
            assertTrue(page.height <= NewspaperTypesetter.PAGE_HEIGHT && page.height % NewspaperPack.TILE == 0)
            if (i == 0) assertEquals(NewspaperTypesetter.PAGE_HEIGHT, page.height)
            ImageIO.write(page, "png", File(out, "page-${i + 1}.png"))
        }
    }

    @Test
    fun `empty issue still produces a front page`() {
        val pages = NewspaperTypesetter(config).typeset(Newspaper(1, 0, 0, emptyList()))
        assertEquals(1, pages.size)
    }

    @Test
    fun `page count is capped`() {
        val long = (1..60).map { Story("Story $it", "Lorem ipsum dolor sit amet. ".repeat(40), listOf("Steve"), null) }
        val pages = NewspaperTypesetter(config).typeset(Newspaper(9, 0, 0, listOf(NewspaperSection("Headlines", long))))
        assertEquals(NewspaperTypesetter.MAX_PAGES, pages.size)
    }
}
