package uk.ewancroft.chronicler.news

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.meta.BookMeta
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import uk.ewancroft.chronicler.config.NewspaperConfig
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BookRendererTest {

    private val config = NewspaperConfig(
        title = "Test Chronicle",
        author = "Tester",
        storiesPerSection = 5,
        showStatistics = true,
    )

    @BeforeEach
    fun setUp() {
        MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `renderToBook creates a WRITTEN_BOOK`() {
        val newspaper = Newspaper(1, 0L, 1000L, emptyList())
        val renderer = BookRenderer(config)
        val book = renderer.renderToBook(newspaper)
        assertEquals(Material.WRITTEN_BOOK, book.type)
    }

    @Test
    fun `renderToBook sets title and author via components`() {
        val newspaper = Newspaper(1, 0L, 1000L, emptyList())
        val renderer = BookRenderer(config)
        val book = renderer.renderToBook(newspaper)
        val meta = book.itemMeta as BookMeta

        val title = meta.title()
        assertNotNull(title)
        assertTrue(title.toString().contains("Test Chronicle"))

        val author = meta.author()
        assertNotNull(author)
        assertTrue(author.toString().contains("Tester"))
    }

    @Test
    fun `renderToBook includes title page at minimum`() {
        val newspaper = Newspaper(1, 0L, 1000L, emptyList())
        val renderer = BookRenderer(config)
        val book = renderer.renderToBook(newspaper)
        val meta = book.itemMeta as BookMeta
        assertTrue(meta.pageCount >= 1, "Should have at least a title page")
    }

    @Test
    fun `renderToBook adds pages for sections`() {
        val newspaper = Newspaper(
            issueNumber = 1,
            fromTime = 0L,
            toTime = 1000L,
            sections = listOf(
                NewspaperSection(
                    title = "Headlines",
                    stories = listOf(Story("Big News", "Something happened.", listOf("ewanc26"), EventType.DEATH)),
                ),
            ),
        )
        val renderer = BookRenderer(config)
        val book = renderer.renderToBook(newspaper)
        val meta = book.itemMeta as BookMeta
        assertTrue(meta.pageCount >= 2, "Should have title + section pages")
    }

    @Test
    fun `renderToBook adds footer page`() {
        val newspaper = Newspaper(
            issueNumber = 1,
            fromTime = 0L,
            toTime = 1000L,
            sections = listOf(
                NewspaperSection("Test", listOf(Story("H", "B", emptyList(), null))),
            ),
        )
        val renderer = BookRenderer(config)
        val book = renderer.renderToBook(newspaper)
        val meta = book.itemMeta as BookMeta
        assertTrue(meta.pageCount >= 3, "Should have title + section + footer")
    }

    private fun sampleIssue(bodyLength: Int = 900) = Newspaper(
        issueNumber = 3,
        fromTime = 0L,
        toTime = 1000L,
        sections = (1..4).map { i ->
            NewspaperSection("Section $i", (1..3).map { j ->
                Story("Headline $i.$j for the day", (1..bodyLength / 6).joinToString(" ") { "word$it" }, listOf("Steve", "Alex"), null)
            })
        },
    )

    @Test
    fun `every page fits the book screen without clipping`() {
        val pages = BookRenderer(config).layout(sampleIssue())
        pages.forEachIndexed { index, page ->
            assertTrue(page.size <= BookRenderer.LINES_PER_PAGE, "page ${index + 1} has ${page.size} lines")
            page.forEach { line ->
                assertTrue(MinecraftFont.width(line.plain, line.bold) <= BookRenderer.PAGE_WIDTH, "line too wide: '${line.plain}'")
            }
        }
    }

    @Test
    fun `no article text is lost across page breaks`() {
        val issue = sampleIssue()
        val text = BookRenderer(config).layout(issue).flatten().joinToString(" ") { it.plain }
        val words = text.split(Regex("\\s+")).toSet()
        issue.sections.flatMap { it.stories }.forEach { story ->
            story.body.split(" ").forEach { word -> assertTrue(word in words, "missing '$word'") }
        }
    }

    @Test
    fun `contents on the cover link to each section's first page`() {
        val issue = sampleIssue(bodyLength = 60)
        val pages = BookRenderer(config).layout(issue)
        val contents = pages.first().filter { it.plain.startsWith("• ") }
        assertEquals(issue.sections.size, contents.size)
        contents.zip(issue.sections).forEach { (line, section) ->
            val page = line.plain.substringAfterLast(' ').toInt()
            assertTrue(pages[page - 1].first().plain.startsWith(section.title.uppercase()), "entry for ${section.title} points at page $page")
        }
    }

    @Test
    fun `long issues are capped at the book page limit`() {
        val pages = BookRenderer(config).layout(sampleIssue(bodyLength = 20_000))
        assertEquals(BookRenderer.MAX_PAGES, pages.size)
    }

    @Test
    fun `wrap splits words wider than a line`() {
        val lines = MinecraftFont.wrap("x".repeat(60), BookRenderer.PAGE_WIDTH)
        assertTrue(lines.size > 1)
        assertTrue(lines.all { MinecraftFont.width(it) <= BookRenderer.PAGE_WIDTH })
        assertEquals("x".repeat(60), lines.joinToString(""))
    }
}
