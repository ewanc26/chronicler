package uk.ewancroft.chronicler.news

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import uk.ewancroft.chronicler.config.NewspaperConfig
import java.nio.file.Path
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntegrationSectionsTest {
    @TempDir lateinit var dir: Path
    private val config = NewspaperConfig(title = "T", author = "A", storiesPerSection = 5, showStatistics = false)

    private fun generate(vararg events: ChronicleEvent): Newspaper {
        val store = EventStore(dir.resolve("e.json"))
        events.forEach(store::record)
        return NewspaperGenerator(store, config, null, false, Logger.getAnonymousLogger()).generate(1, 0, System.currentTimeMillis())
    }

    private fun e(type: EventType, player: String, vararg details: Pair<String, String>) =
        ChronicleEvent(type, System.currentTimeMillis(), player, "u-$player", "world", details.toMap())

    @Test
    fun `civic affairs covers towns, nations and wars`() {
        val paper = generate(
            e(EventType.TOWN_FOUNDED, "Alex", "town" to "Oakhaven"),
            e(EventType.WAR_DECLARED, "Steve", "attacker" to "Stonebridge", "defender" to "Oakhaven"),
            e(EventType.NATION_JOINED, "Alex", "town" to "Oakhaven", "nation" to "The North"),
        )
        val civic = paper.sections.single { it.title == "Civic Affairs" }
        val text = civic.stories.joinToString(" ") { it.headline + " " + it.body }
        assertTrue("Oakhaven" in text && "Stonebridge" in text && "The North" in text, text)
    }

    @Test
    fun `market report prices goods and names the busiest shop`() {
        val paper = generate(
            e(EventType.SHOP_SALE, "Alex", "item" to "iron_ingot", "amount" to "32", "total" to "64.00", "owner" to "Smith"),
            e(EventType.SHOP_SALE, "Steve", "item" to "iron_ingot", "amount" to "16", "total" to "32.00", "owner" to "Smith"),
            e(EventType.SHOP_SALE, "Steve", "item" to "bread", "amount" to "10", "total" to "5.00", "owner" to "Baker"),
        )
        val market = paper.sections.single { it.title == "Market Report" }
        val text = market.stories.joinToString(" ") { it.headline + " " + it.body }
        assertTrue("3 Sales" in text && "Iron ingot went for about 2.00 each" in text && "Busiest Shop: Smith" in text, text)
    }

    @Test
    fun `rising stars and votes`() {
        val paper = generate(
            e(EventType.SKILL_MILESTONE, "Alex", "skill" to "Mining", "level" to "500"),
            e(EventType.RANK_UP, "Steve", "rank" to "veteran"),
            e(EventType.VOTE, "Alex", "service" to "List A"),
            e(EventType.VOTE, "Alex", "service" to "List B"),
        )
        assertTrue(paper.sections.single { it.title == "Rising Stars" }.stories.any { "Mining" in it.headline })
        assertEquals("Thank You, Voters", paper.sections.single { it.title == "Votes" }.stories.single().headline)
    }
}
