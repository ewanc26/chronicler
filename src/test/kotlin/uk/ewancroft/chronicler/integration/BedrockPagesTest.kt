package uk.ewancroft.chronicler.integration

import org.junit.jupiter.api.Test
import uk.ewancroft.chronicler.config.NewspaperConfig
import uk.ewancroft.chronicler.news.Newspaper
import uk.ewancroft.chronicler.news.NewspaperSection
import uk.ewancroft.chronicler.news.Story
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BedrockPagesTest {
    private val config = NewspaperConfig(title = "The Weekly Chronicle", author = "C", storiesPerSection = 5, showStatistics = true)
    private val issue = Newspaper(4, 0, 0, listOf(
        NewspaperSection("Headlines", listOf(Story("Lead story", "Lead body.", emptyList(), null), Story("Second", "Two.", emptyList(), null))),
        NewspaperSection("Empty", emptyList()),
        NewspaperSection("Obituaries", listOf(Story("Steve — 1 death", "Fell.", emptyList(), null))),
    ))

    @Test
    fun `front page leads with the top story and lists non-empty sections`() {
        val page = BedrockPages.front(config, issue, "Sunday")
        assertEquals("The Weekly Chronicle — No. 4", page.title)
        assertTrue("Lead story" in page.content && "Lead body." in page.content && "Sunday" in page.content)
        assertEquals(listOf("Headlines\n§82 stories", "Obituaries\n§81 story"), page.buttons)
    }

    @Test
    fun `section pages contain every story and link onwards`() {
        val first = BedrockPages.section(issue, 0)
        assertTrue("Lead story" in first.content && "Second" in first.content)
        assertEquals(listOf("« Front page", "Obituaries »"), first.buttons)
        assertEquals(listOf("« Front page"), BedrockPages.section(issue, 1).buttons)
    }
}
