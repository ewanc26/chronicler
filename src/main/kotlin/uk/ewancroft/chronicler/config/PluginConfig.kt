package uk.ewancroft.chronicler.config

import org.bukkit.configuration.file.FileConfiguration

data class TrackingConfig(
    val deaths: Boolean,
    val kills: Boolean,
    val pvp: Boolean,
    val advancements: Boolean,
    val blocks: Boolean,
    val exploration: Boolean,
    val social: Boolean,
    val economy: Boolean,
    val chat: Boolean,
    val crafting: Boolean,
    val fishing: Boolean,
    val sleep: Boolean,
    val portals: Boolean,
    val entities: Boolean,
    val explosions: Boolean,
    val weather: Boolean,
    val raids: Boolean,
    val teleport: Boolean,
    val consumption: Boolean,
    val projectiles: Boolean,
    val vehicles: Boolean,
    val misc: Boolean,
)

data class LlmConfig(
    val enabled: Boolean,
    val provider: String,
    val model: String,
    val apiKey: String,
    val baseUrl: String,
    val ollamaUrl: String,
    val lmStudioUrl: String,
    val coCoreBaseUrl: String,
    val timeoutSeconds: Int,
    val systemPrompt: String,
)

data class NewspaperConfig(
    val title: String,
    val author: String,
    val storiesPerSection: Int,
    val showStatistics: Boolean,
    val byline: String = "Chronicler Staff",
    val tone: String = "dry, factual, and slightly humorous",
    val maxArticleCharacters: Int = 900,
    val sectionOrder: List<String> = emptyList(),
    val titlePageText: String = "A chronicle of events past.",
    val accentColor: Int = 0x6B3E00,
    val primaryTextColor: Int = 0x1F1A14,
    val secondaryTextColor: Int = 0x3D342A,
    val mutedTextColor: Int = 0x5C4F40,
)

data class PrivacyConfig(
    val includePrivateMessages: Boolean,
    val includeChatExcerpts: Boolean,
    val includeCoordinates: Boolean,
    val excludedPlayers: Set<String>,
)

data class WebConfig(
    val enabled: Boolean,
    val port: Int,
    val writeFiles: Boolean,
)

class PluginConfig(private val config: FileConfiguration) {

    var enabled: Boolean
    val schedule: String
    val scheduleBase: ScheduleBase
    val publishDay: Int
    val publishHour: Int
    val tracking: TrackingConfig
    val eventLimit: Int
    val llm: LlmConfig
    val newspaper: NewspaperConfig
    val web: WebConfig
    val configVersion: Int
    val tickerInterval: Long
    val papiEnabled: Boolean
    val bStatsEnabled: Boolean
    val autoUpdateEnabled: Boolean
    val privacy: PrivacyConfig
    val archiveRetention: Int
    val reviewRequired: Boolean
    val backfillEnabled: Boolean
    val backfillMaxLogFiles: Int

    init {
        enabled = config.getBoolean("enabled", true)
        schedule = config.getString("schedule", "WEEKLY") ?: "WEEKLY"
        scheduleBase = ScheduleBase.from(config.getString("schedule-base", "REAL_TIME"))
        publishDay = config.getInt("publish-day", 0)
        publishHour = config.getInt("publish-hour", 8)
        eventLimit = config.getInt("event-limit", 500).coerceAtLeast(100)
        tickerInterval = config.getLong("ticker.interval-ticks", 1200).coerceAtLeast(0)
        papiEnabled = config.getBoolean("ticker.papi-enabled", true)
        llm = LlmConfig(
            enabled = config.getBoolean("llm.enabled", true),
            provider = (config.getString("llm.provider", "ollama") ?: "ollama").lowercase(),
            model = config.getString("llm.model", "llama3.2") ?: "llama3.2",
            apiKey = config.getString("llm.api-key", "") ?: "",
            baseUrl = (config.getString("llm.base-url", "https://openrouter.ai/api/v1") ?: "https://openrouter.ai/api/v1").trimEnd('/'),
            ollamaUrl = (config.getString("llm.ollama-url", "http://localhost:11434") ?: "http://localhost:11434").trimEnd('/'),
            lmStudioUrl = (config.getString("llm.lmstudio-url", "http://localhost:1234") ?: "http://localhost:1234").trimEnd('/'),
            coCoreBaseUrl = (config.getString("llm.cocore-base-url", "http://localhost:8080") ?: "http://localhost:8080").trimEnd('/'),
            timeoutSeconds = config.getInt("llm.timeout-seconds", 30).coerceIn(5, 120),
            systemPrompt = config.getString("llm.system-prompt", "") ?: "",
        )
        tracking = TrackingConfig(
            deaths = config.getBoolean("tracking.deaths", true),
            kills = config.getBoolean("tracking.kills", true),
            pvp = config.getBoolean("tracking.pvp", true),
            advancements = config.getBoolean("tracking.advancements", true),
            blocks = config.getBoolean("tracking.blocks", true),
            exploration = config.getBoolean("tracking.exploration", true),
            social = config.getBoolean("tracking.social", true),
            economy = config.getBoolean("tracking.economy", true),
            chat = config.getBoolean("tracking.chat", true),
            crafting = config.getBoolean("tracking.crafting", true),
            fishing = config.getBoolean("tracking.fishing", true),
            sleep = config.getBoolean("tracking.sleep", true),
            portals = config.getBoolean("tracking.portals", true),
            entities = config.getBoolean("tracking.entities", true),
            explosions = config.getBoolean("tracking.explosions", true),
            weather = config.getBoolean("tracking.weather", true),
            raids = config.getBoolean("tracking.raids", true),
            teleport = config.getBoolean("tracking.teleport", true),
            consumption = config.getBoolean("tracking.consumption", true),
            projectiles = config.getBoolean("tracking.projectiles", true),
            vehicles = config.getBoolean("tracking.vehicles", true),
            misc = config.getBoolean("tracking.misc", true),
        )
        newspaper = NewspaperConfig(
            title = config.getString("newspaper.title", "The Weekly Chronicle") ?: "The Weekly Chronicle",
            author = config.getString("newspaper.author", "Chronicler") ?: "Chronicler",
            storiesPerSection = config.getInt("newspaper.stories-per-section", 5).coerceIn(1, 20),
            showStatistics = config.getBoolean("newspaper.show-statistics", true),
            byline = config.getString("newspaper.byline", "Chronicler Staff") ?: "Chronicler Staff",
            tone = config.getString("newspaper.tone", "dry, factual, and slightly humorous") ?: "dry, factual, and slightly humorous",
            maxArticleCharacters = config.getInt("newspaper.max-article-characters", 900).coerceIn(200, 4000),
            sectionOrder = config.getStringList("newspaper.section-order"),
            titlePageText = config.getString("newspaper.title-page-text", "A chronicle of events past.") ?: "A chronicle of events past.",
            accentColor = color(config.getString("newspaper.colors.accent"), 0x6B3E00),
            primaryTextColor = color(config.getString("newspaper.colors.primary"), 0x1F1A14),
            secondaryTextColor = color(config.getString("newspaper.colors.secondary"), 0x3D342A),
            mutedTextColor = color(config.getString("newspaper.colors.muted"), 0x5C4F40),
        )
        web = WebConfig(
            enabled = config.getBoolean("web.enabled", true),
            port = config.getInt("web.port", 8080).coerceIn(1024, 65535),
            writeFiles = config.getBoolean("web.write-files", true),
        )
        bStatsEnabled = config.getBoolean("bstats-enabled", true)
        autoUpdateEnabled = config.getBoolean("auto-update.enabled", true)
        archiveRetention = config.getInt("archive.retention", 50).coerceAtLeast(1)
        reviewRequired = config.getBoolean("editor.review-required", false)
        privacy = PrivacyConfig(
            includePrivateMessages = config.getBoolean("privacy.include-private-messages", false),
            includeChatExcerpts = config.getBoolean("privacy.include-chat-excerpts", false),
            includeCoordinates = config.getBoolean("privacy.include-coordinates", false),
            excludedPlayers = config.getStringList("privacy.excluded-players").map { it.lowercase() }.toSet(),
        )
        backfillEnabled = config.getBoolean("backfill.enabled", true)
        backfillMaxLogFiles = config.getInt("backfill.max-log-files", 100).coerceAtLeast(1)
        configVersion = config.getInt("config-version", 3)
    }

    private fun color(value: String?, fallback: Int): Int = value?.removePrefix("#")?.toIntOrNull(16) ?: fallback
}
