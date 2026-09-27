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
        // Sample faces so the preview shows portraits: a skin-tone face with hair and eyes.
        val faces = listOf("Scribe", "Alex", "Steve", "Notch", "Herobrine").mapIndexed { i, name ->
            name to java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB).apply {
                val g = createGraphics()
                g.color = java.awt.Color(0xC6 - i * 12, 0x96 - i * 8, 0x6C); g.fillRect(0, 0, 8, 8)
                g.color = java.awt.Color(0x3B + i * 20, 0x2A, 0x1A); g.fillRect(0, 0, 8, 2 + i % 2)
                g.color = java.awt.Color.WHITE; g.fillRect(1, 4, 2, 1); g.fillRect(5, 4, 2, 1)
                g.color = java.awt.Color(0x30, 0x40, 0x90); g.fillRect(2, 4, 1, 1); g.fillRect(5, 4, 1, 1)
                g.dispose()
            }
        }.toMap()
        val pages = NewspaperTypesetter(config).typeset(sample(), faces)
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
