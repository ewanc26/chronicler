package uk.ewancroft.chronicler.news

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import uk.ewancroft.chronicler.config.NewspaperConfig
import uk.ewancroft.chronicler.config.PrivacyConfig
import uk.ewancroft.chronicler.llm.ArticleResult
import uk.ewancroft.chronicler.llm.FakeLlmProvider
import java.nio.file.Path
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewspaperGeneratorTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var store: EventStore
    private val config = NewspaperConfig(
        title = "Test Chronicle",
        author = "Tester",
        storiesPerSection = 5,
        showStatistics = true,
    )
    private val logger = Logger.getLogger("TestLogger")

    @BeforeEach
    fun setUp() {
        store = EventStore(tempDir.resolve("events.json"))
    }

    private fun event(type: EventType, player: String = "ewanc26", details: Map<String, String> = emptyMap()): ChronicleEvent =
        ChronicleEvent(type, System.currentTimeMillis(), player, "uuid-$player", "world", details)

    @Test
    fun `generate with no events produces quiet newspaper`() {
        val gen = NewspaperGenerator(store, config, llmProvider = null, llmEnabled = false, logger = logger)
        val newspaper = gen.generate(1, 0L, System.currentTimeMillis())

        assertEquals(1, newspaper.issueNumber)
        assertTrue(newspaper.sections.isNotEmpty())
    }

    @Test
    fun `generate includes Headlines section even with no events`() {
        val gen = NewspaperGenerator(store, config, llmProvider = null, llmEnabled = false, logger = logger)
        val newspaper = gen.generate(1, 0L, System.currentTimeMillis())

        val headlines = newspaper.sections.find { it.title == "Headlines" }
        assertNotNull(headlines)
        assertEquals(1, headlines.stories.size)
        assertEquals("Quiet Days", headlines.stories[0].headline)
    }

    @Test
    fun `generate creates Obituaries section when deaths exist`() {
        store.record(event(EventType.DEATH, details = mapOf("message" to "ewanc26 fell")))
        val gen = NewspaperGenerator(store, config, llmProvider = null, llmEnabled = false, logger = logger)
        val newspaper = gen.generate(1, 0L, System.currentTimeMillis())

        val obits = newspaper.sections.find { it.title == "Obituaries" }
        assertNotNull(obits)
        assertTrue(obits.stories.isNotEmpty())
        assert(obits.stories[0].headline.contains("ewanc26"))
    }

    @Test
    fun `generate creates Achievements section when advancements exist`() {
        store.record(event(EventType.ADVANCEMENT, details = mapOf("advancement" to "story/root", "displayName" to "Minecraft")))
        val gen = NewspaperGenerator(store, config, llmProvider = null, llmEnabled = false, logger = logger)
        val newspaper = gen.generate(1, 0L, System.currentTimeMillis())

        val ach = newspaper.sections.find { it.title == "Achievements" }
        assertNotNull(ach)
        assertTrue(ach.stories.isNotEmpty())
    }

    @Test
    fun `generate creates Hunting Grounds section when kills exist`() {
        store.record(event(EventType.KILL, details = mapOf("entity" to "zombie")))
        val gen = NewspaperGenerator(store, config, llmProvider = null, llmEnabled = false, logger = logger)
        val newspaper = gen.generate(1, 0L, System.currentTimeMillis())

        val hunting = newspaper.sections.find { it.title == "Hunting Grounds" }
        assertNotNull(hunting)
        assertTrue(hunting.stories.isNotEmpty())
    }

    @Test
    fun `generate creates Exploration section when biomes discovered`() {
        store.record(event(EventType.BIOME_DISCOVERY, details = mapOf("biome" to "desert")))
        val gen = NewspaperGenerator(store, config, llmProvider = null, llmEnabled = false, logger = logger)
        val newspaper = gen.generate(1, 0L, System.currentTimeMillis())

        val expl = newspaper.sections.find { it.title == "Exploration & Building" }
        assertNotNull(expl)
        assertTrue(expl.stories.isNotEmpty())
    }

    @Test
    fun `generate creates Statistics section when enabled`() {
        store.record(event(EventType.PLAYER_JOIN))
        val gen = NewspaperGenerator(store, config, llmProvider = null, llmEnabled = false, logger = logger)
        val newspaper = gen.generate(1, 0L, System.currentTimeMillis())

        val stats = newspaper.sections.find { it.title == "Statistics" }
        assertNotNull(stats)
        assertTrue(stats.stories.any { it.headline.contains("Active Players") })
        assertTrue(stats.stories.any { it.headline.contains("Total Events") })
    }

    @Test
    fun `generate excludes system events from Active Players count`() {
        store.record(event(EventType.PLAYER_JOIN, player = "ewanc26"))
        // System-generated events (weather, explosions, etc.) use a sentinel playerName
        // like "world" or "nature" and a blank playerUuid — they must not be counted
        // as active players.
        store.record(
            ChronicleEvent(
                EventType.EXPLOSION,
                System.currentTimeMillis(),
                "world",
                "",
                "world",
                mapOf("source" to "creeper", "blocks" to "5"),
            )
        )
        store.record(
            ChronicleEvent(
                EventType.STRUCTURE_GROW,
                System.currentTimeMillis(),
                "nature",
                "",
                "world",
                mapOf("species" to "oak", "blocks" to "20"),
            )
        )
        val gen = NewspaperGenerator(store, config, llmProvider = null, llmEnabled = false, logger = logger)
        val newspaper = gen.generate(1, 0L, System.currentTimeMillis())

        val stats = newspaper.sections.find { it.title == "Statistics" }
        assertNotNull(stats)
        val activePlayers = stats.stories.find { it.headline == "Active Players" }
        assertNotNull(activePlayers)
        assertEquals(listOf("ewanc26"), activePlayers.players)
        assertTrue("world" !in activePlayers.players)
        assertTrue("nature" !in activePlayers.players)
    }

    @Test
    fun `generate omits Statistics section when disabled`() {
        val cfg = config.copy(showStatistics = false)
        store.record(event(EventType.PLAYER_JOIN))
        val gen = NewspaperGenerator(store, cfg, llmProvider = null, llmEnabled = false, logger = logger)
        val newspaper = gen.generate(1, 0L, System.currentTimeMillis())

        val stats = newspaper.sections.find { it.title == "Statistics" }
        assertNull(stats)
    }

    @Test
    fun `generate respects storiesPerSection limit`() {
        val cfg = config.copy(storiesPerSection = 2)
        repeat(10) {
            store.record(event(EventType.DEATH, player = "p$it", details = mapOf("message" to "p$it died")))
        }
        val gen = NewspaperGenerator(store, cfg, llmProvider = null, llmEnabled = false, logger = logger)
        val newspaper = gen.generate(1, 0L, System.currentTimeMillis())

        val obits = newspaper.sections.find { it.title == "Obituaries" }
        assertNotNull(obits)
        assertTrue(obits.stories.size <= 2)
    }

    @Test
    fun `generate filters events outside time range`() {
        store.record(event(EventType.DEATH, player = "old", details = mapOf("message" to "old death")))
        val gen = NewspaperGenerator(store, config, llmProvider = null, llmEnabled = false, logger = logger)
        val newspaper = gen.generate(1, System.currentTimeMillis() + 1000, System.currentTimeMillis() + 2000)

        val obits = newspaper.sections.find { it.title == "Obituaries" }
        assertNull(obits, "Should not include events outside the time range")
    }

    @Test
    fun `generate with multiple event types produces all relevant sections`() {
        store.record(event(EventType.DEATH, player = "a", details = mapOf("message" to "a died")))
        store.record(event(EventType.KILL, player = "b", details = mapOf("entity" to "skeleton")))
        store.record(event(EventType.ADVANCEMENT, player = "c", details = mapOf("advancement" to "story/root")))
        store.record(event(EventType.BIOME_DISCOVERY, player = "d", details = mapOf("biome" to "plains")))
        store.record(event(EventType.PVP_KILL, player = "e", details = mapOf("killer" to "f")))
        store.record(event(EventType.BLOCK_PLACE, player = "g", details = mapOf("block" to "stone")))

        val gen = NewspaperGenerator(store, config, llmProvider = null, llmEnabled = false, logger = logger)
        val newspaper = gen.generate(1, 0L, System.currentTimeMillis())

        val titles = newspaper.sections.map { it.title }
        assertTrue("Headlines" in titles)
        assertTrue("Obituaries" in titles)
        assertTrue("Achievements" in titles)
        assertTrue("Hunting Grounds" in titles)
        assertTrue("Exploration & Building" in titles)
        assertTrue("Statistics" in titles)
    }

    @Test
    fun `privacy excludes opted out players and private messages`() {
        store.record(event(EventType.DEATH, player = "hidden", details = mapOf("message" to "hidden fell")))
        store.record(event(EventType.MESSAGE_SENT, player = "visible"))
        val privacy = PrivacyConfig(false, false, false, setOf("hidden"))
        val newspaper = NewspaperGenerator(store, config, null, false, logger, privacyConfig = privacy).generate(1, 0L, System.currentTimeMillis())

        assertTrue(newspaper.sections.flatMap { it.stories }.none { "hidden" in it.players })
        assertTrue(newspaper.sections.none { it.title == "Social" })
    }

    @Test
    fun `configured byline and section ordering are applied`() {
        store.record(event(EventType.DEATH, details = mapOf("message" to "fell")))
        val ordered = config.copy(byline = "Server Desk", sectionOrder = listOf("Statistics", "Headlines"))
        val newspaper = NewspaperGenerator(store, ordered, null, false, logger).generate(1, 0L, System.currentTimeMillis())

        assertEquals("Statistics", newspaper.sections.first().title)
        assertTrue(newspaper.sections.flatMap { it.stories }.all { it.byline == "Server Desk" })
    }

    @Test
    fun `llm generates headline section when provider is available`() {
        store.record(event(EventType.DEATH, details = mapOf("message" to "ewanc26 fell")))
        val fake = FakeLlmProvider(headline = "LLM Headline", body = "LLM body text.")
        val gen = NewspaperGenerator(store, config, fake, true, logger, "")
        val newspaper = gen.generate(1, 0L, System.currentTimeMillis())

        val headlines = newspaper.sections.find { it.title == "Headlines" }
        assertNotNull(headlines)
        assertTrue(headlines.stories.any { it.headline == "LLM Headline" })
    }

    @Test
    fun `llm resolves tone and maxArticleCharacters in system prompt`() {
        store.record(event(EventType.DEATH, details = mapOf("message" to "ewanc26 fell")))
        val fake = FakeLlmProvider()
        val cfg = config.copy(tone = "witty and sarcastic", maxArticleCharacters = 500)
        val gen = NewspaperGenerator(store, cfg, fake, true, logger,
            "Tone is {tone}. Max chars is {maxArticleCharacters}.")
        gen.generate(1, 0L, System.currentTimeMillis())

        val prompt = fake.capturedSystemPrompts.first()
        assertTrue(prompt.contains("witty and sarcastic"))
        assertTrue(prompt.contains("500"))
        assertTrue(prompt.contains("{tone}") == false)
    }

    @Test
    fun `llm system prompt passes through without variables when none used`() {
        store.record(event(EventType.DEATH, details = mapOf("message" to "fell")))
        val fake = FakeLlmProvider()
        val gen = NewspaperGenerator(store, config, fake, true, logger, "Static prompt text.")
        gen.generate(1, 0L, System.currentTimeMillis())

        assertEquals("Static prompt text.", fake.capturedSystemPrompts.first())
    }

    @Test
    fun `llm falls back to template when provider returns null`() {
        store.record(event(EventType.DEATH, player = "ewanc26", details = mapOf("message" to "ewanc26 fell")))
        val fake = FakeLlmProvider(headline = "FakeLLM")
        fake.failOnAll()
        val gen = NewspaperGenerator(store, config, fake, true, logger, "")
        val newspaper = gen.generate(1, 0L, System.currentTimeMillis())

        val obits = newspaper.sections.find { it.title == "Obituaries" }
        assertNotNull(obits)
        assertTrue(obits.stories.none { it.headline == "FakeLLM" })
    }

    @Test
    fun `llm disabled falls back to template`() {
        store.record(event(EventType.DEATH, details = mapOf("message" to "ewanc26 fell")))
        val fake = FakeLlmProvider()
        val gen = NewspaperGenerator(store, config, fake, false, logger, "")
        val newspaper = gen.generate(1, 0L, System.currentTimeMillis())

        val headlines = newspaper.sections.find { it.title == "Headlines" }
        assertNotNull(headlines)
        assertTrue(headlines.stories.none { it.headline == "Fake Headline" })
    }

    @Test
    fun `llm system prompt passes section title and summary`() {
        store.record(event(EventType.DEATH, details = mapOf("message" to "ewanc26 fell")))
        val fake = FakeLlmProvider()
        val gen = NewspaperGenerator(store, config, fake, true, logger, "")
        gen.generate(1, 0L, System.currentTimeMillis())

        assertTrue(fake.capturedSectionTitles.isNotEmpty())
        assertTrue(fake.capturedEventSummaries.isNotEmpty())
        assertTrue(fake.capturedEventSummaries.any { it.contains("ewanc26") })
    }

    @Test
    fun `llm sections use LLM output across multiple sections`() {
        store.record(event(EventType.DEATH, details = mapOf("message" to "fell")))
        store.record(event(EventType.ADVANCEMENT, details = mapOf("advancement" to "story/root", "displayName" to "Minecraft")))
        val fake = FakeLlmProvider(headline = "LLM Story", body = "LLM body.")
        val gen = NewspaperGenerator(store, config, fake, true, logger, "")
        val newspaper = gen.generate(1, 0L, System.currentTimeMillis())

        val llmSections = setOf("Headlines", "Obituaries", "Achievements")
        newspaper.sections.filter { it.title in llmSections }.forEach { section ->
            assertTrue(section.stories.any { it.headline == "LLM Story" },
                "Section '$section' should use LLM output but doesn't")
        }
    }
}
