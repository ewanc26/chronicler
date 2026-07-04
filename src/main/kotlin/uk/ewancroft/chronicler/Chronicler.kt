package uk.ewancroft.chronicler

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bstats.bukkit.Metrics
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.command.Command
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import uk.ewancroft.chronicler.command.ChroniclerCommand
import uk.ewancroft.chronicler.command.ChroniclerExpansion
import uk.ewancroft.chronicler.config.Messages
import uk.ewancroft.chronicler.config.PluginConfig
import uk.ewancroft.chronicler.llm.AnthropicProvider
import uk.ewancroft.chronicler.llm.LlmProvider
import uk.ewancroft.chronicler.llm.LMStudioProvider
import uk.ewancroft.chronicler.llm.OllamaProvider
import uk.ewancroft.chronicler.llm.OpenAiProvider
import uk.ewancroft.chronicler.llm.CoCoreProvider
import uk.ewancroft.chronicler.news.ArchiveStore
import uk.ewancroft.chronicler.news.BookRenderer
import uk.ewancroft.chronicler.news.EventStore
import uk.ewancroft.chronicler.news.Newspaper
import uk.ewancroft.chronicler.news.NewspaperGenerator
import uk.ewancroft.chronicler.news.ChronicleEvent
import uk.ewancroft.chronicler.news.EventType
import uk.ewancroft.chronicler.news.WebRenderer
import uk.ewancroft.chronicler.task.HeadlineTicker
import uk.ewancroft.chronicler.task.PublicationTask
import uk.ewancroft.chronicler.tracker.ActivityTracker
import uk.ewancroft.chronicler.tracker.BreakingNewsTracker
import uk.ewancroft.chronicler.tracker.BuildTracker
import uk.ewancroft.chronicler.tracker.CombatTracker
import uk.ewancroft.chronicler.tracker.DeathTracker
import uk.ewancroft.chronicler.tracker.EconomyTracker
import uk.ewancroft.chronicler.tracker.EntityTracker
import uk.ewancroft.chronicler.tracker.IssueRemovalListener
import uk.ewancroft.chronicler.tracker.MilestoneTracker
import uk.ewancroft.chronicler.tracker.PlayerActionTracker
import uk.ewancroft.chronicler.tracker.PrivateMessageTracker
import uk.ewancroft.chronicler.tracker.SessionStore
import uk.ewancroft.chronicler.tracker.SessionTracker
import uk.ewancroft.chronicler.tracker.SocialTracker
import uk.ewancroft.chronicler.tracker.SubscribeStore
import uk.ewancroft.chronicler.tracker.WorldTracker
import uk.ewancroft.chronicler.util.UpdateChecker
import java.io.File

class Chronicler : JavaPlugin() {

    private var state: PluginState? = null

    data class PluginState(
        val config: PluginConfig,
        val messages: Messages,
        val eventStore: EventStore,
        val sessionStore: SessionStore?,
        val subscribeStore: SubscribeStore,
        val archiveStore: ArchiveStore?,
        val llmProvider: LlmProvider?,
        val llmAvailable: Boolean,
        val generator: NewspaperGenerator,
        val bookRenderer: BookRenderer,
        val webRenderer: WebRenderer?,
        val publicationTask: PublicationTask,
        val headlineTicker: HeadlineTicker?,
        val papiExpansion: ChroniclerExpansion?,
        val economyTracker: EconomyTracker?,
        val command: ChroniclerCommand,
    )

    override fun onEnable() {
        val activationTime = System.currentTimeMillis()
        saveDefaultConfig()
        val messagesFile = File(dataFolder, "messages.yml")
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false)
        }
        val cfg = PluginConfig(config)
        if (cfg.bStatsEnabled) {
            Metrics(this, 23467)
        }
        UpdateChecker(this, "ewanc26", "Chronicler", cfg.autoUpdateEnabled).checkAsync()
        state = buildState(activationTime)
        val s = state ?: return
        logger.info("Chronicler enabled. LLM: ${if (s.llmAvailable) "${s.config.llm.provider} (${s.config.llm.model})" else "template mode"}. Web: ${if (s.config.web.enabled) "port ${s.config.web.port}" else "disabled"}.")
    }

    override fun onDisable() {
        state?.let { s ->
            s.publicationTask.stop()
            s.webRenderer?.stop()
            s.headlineTicker?.stop()
            s.eventStore.save()
            s.sessionStore?.save()
            s.subscribeStore.save()
        }
        state = null
        logger.info("Chronicler disabled.")
    }

    private fun buildState(activationTime: Long = System.currentTimeMillis()): PluginState {
        val cfg = PluginConfig(config)
        val dataPath = dataFolder.toPath()
        val storeFile = dataPath.resolve("events.json")
        val sessionFile = dataPath.resolve("sessions.json")
        val subscribeFile = dataPath.resolve("subscriptions.json")
        val archiveDir = dataPath.resolve("archive")

        val messages = Messages(File(dataFolder, "messages.yml")).also { it.load() }

        val eventStore = EventStore(storeFile).also {
            it.setMaxEvents(cfg.eventLimit)
            it.load()
        }

        val sessionStore = SessionStore(sessionFile).also { it.load() }
        val subscribeStore = SubscribeStore(subscribeFile).also { it.load() }
        val archiveStore = ArchiveStore(archiveDir, cfg.archiveRetention, logger).also { it.loadAll() }

        val (llmProvider, llmAvailable) = if (cfg.llm.enabled) {
            val provider = createProvider(cfg.llm)
            val available = provider.isAvailable()
            if (!available) logger.warning("${provider.name()} is not reachable. Falling back to template mode.")
            provider to available
        } else {
            null to false
        }

        val generator = NewspaperGenerator(
            store = eventStore,
            newspaperConfig = cfg.newspaper,
            llmProvider = llmProvider?.takeIf { llmAvailable },
            llmEnabled = cfg.llm.enabled && llmAvailable,
            logger = logger,
            llmSystemPrompt = cfg.llm.systemPrompt,
            privacyConfig = cfg.privacy,
        )

        val bookRenderer = BookRenderer(cfg.newspaper)

        val webRenderer = if (cfg.web.enabled) {
            WebRenderer(cfg.web, cfg.newspaper, dataPath.resolve("web"), archiveStore)
        } else {
            logger.info("Web view disabled.")
            null
        }

        val economyTracker = EconomyTracker(eventStore, cfg.tracking).also {
            if (it.tryHook()) logger.info("Vault economy detected.")
        }

        val trackers = mutableListOf(
            DeathTracker(eventStore, cfg.tracking),
            MilestoneTracker(eventStore, cfg.tracking),
            BuildTracker(eventStore, cfg.tracking),
            SocialTracker(eventStore, cfg.tracking),
            PrivateMessageTracker(eventStore, cfg.tracking),
            BreakingNewsTracker(eventStore, cfg),
            economyTracker,
            ActivityTracker(eventStore, cfg.tracking),
            WorldTracker(eventStore, cfg.tracking),
            EntityTracker(eventStore, cfg.tracking),
            CombatTracker(eventStore, cfg.tracking),
            PlayerActionTracker(eventStore, cfg.tracking),
        )
        trackers.add(SessionTracker(eventStore, sessionStore, cfg.tracking))
        trackers.add(IssueRemovalListener(this))
        trackers.forEach { server.pluginManager.registerEvents(it, this) }

        val publicationTask = PublicationTask(
            plugin = this,
            config = cfg,
            store = eventStore,
            subscribeStore = subscribeStore,
            messages = messages,
            generator = generator,
            bookRenderer = bookRenderer,
            webRenderer = webRenderer,
            archiveStore = archiveStore,
            logger = logger,
            activationTime = activationTime,
            logsDir = dataFolder.parentFile?.parentFile?.toPath()?.resolve("logs"),
        ).also { it.start() }

        server.pluginManager.registerEvents(object : org.bukkit.event.Listener {
            @org.bukkit.event.EventHandler
            fun onJoin(event: org.bukkit.event.player.PlayerJoinEvent) {
                publicationTask.deliverToPlayer(event.player)
            }
        }, this)

        val headlineTicker = HeadlineTicker(
            plugin = this,
            logger = logger,
            intervalTicks = cfg.tickerInterval,
            getLatestNewspaper = { publicationTask.getLatestNewspaper() },
        ).also { it.start() }

        val papiExpansion = if (cfg.papiEnabled) {
            try {
                val exp = ChroniclerExpansion(this, sessionStore)
                exp.register()
                logger.info("PlaceholderAPI expansion registered.")
                exp
            } catch (_: NoClassDefFoundError) {
                logger.info("PlaceholderAPI not found, expansion skipped.")
                null
            }
        } else null

        val command = ChroniclerCommand(this, messages, subscribeStore)
        val registered = try {
            getCommand("chronicler")?.let { cmd ->
                cmd.setExecutor(command)
                cmd.setTabCompleter(command)
                true
            } ?: false
        } catch (_: UnsupportedOperationException) {
            false
        }
        if (!registered) {
            val cmd = object : Command("chronicler") {
                override fun execute(sender: org.bukkit.command.CommandSender, label: String, args: Array<out String>): Boolean {
                    return command.onCommand(sender, this, label, args)
                }
                override fun tabComplete(sender: org.bukkit.command.CommandSender, alias: String, args: Array<out String>): MutableList<String> {
                    return (command.onTabComplete(sender, this, alias, args) ?: emptyList()).toMutableList()
                }
            }
            cmd.description = "Chronicler commands."
            cmd.setAliases(listOf("clr"))
            cmd.permission = "chronicler.use"
            val commandMap = server::class.java.getMethod("getCommandMap").invoke(server) as org.bukkit.command.CommandMap
            commandMap.register("chronicler", cmd)
        }

        return PluginState(
            config = cfg,
            messages = messages,
            eventStore = eventStore,
            sessionStore = sessionStore,
            subscribeStore = subscribeStore,
            archiveStore = archiveStore,
            llmProvider = llmProvider,
            llmAvailable = llmAvailable,
            generator = generator,
            bookRenderer = bookRenderer,
            webRenderer = webRenderer,
            publicationTask = publicationTask,
            headlineTicker = headlineTicker,
            papiExpansion = papiExpansion,
            economyTracker = economyTracker,
            command = command,
        )
    }

    fun removeIssueFromPlayer(player: Player) {
        val key = NamespacedKey(this, "chronicler_issue")
        val toRemove = player.inventory.contents.filterNotNull().filter { it.itemMeta?.persistentDataContainer?.has(key, PersistentDataType.INTEGER) == true }
        toRemove.forEach { player.inventory.remove(it) }
    }

    fun giveNewspaper(player: Player) {
        val s = state ?: return
        val book = s.publicationTask.getLatestBook()
        if (book == null) {
            player.sendMessage(s.messages.noIssue())
            return
        }
        // Tag the book so we can detect when it's opened
        val meta = book.itemMeta
        if (meta != null) {
            val key = NamespacedKey(this, "chronicler_issue")
            meta.persistentDataContainer.set(key, PersistentDataType.INTEGER, 1)
            book.itemMeta = meta
        }
        if (player.inventory.firstEmpty() == -1) {
            player.sendMessage(s.messages.inventoryFull())
            return
        }
        player.inventory.addItem(book)
        player.sendMessage(s.messages.delivering())
    }

    fun publishNow(): Boolean = state?.publicationTask?.publishNow() ?: false

    fun createDraft(onComplete: (Newspaper?) -> Unit): Boolean =
        state?.publicationTask?.createDraftAsync(onComplete) ?: false
    fun getDraft(): Newspaper? = state?.publicationTask?.getDraft()
    fun publishDraft(): Boolean = state?.publicationTask?.publishDraft() ?: false
    fun removeDraftStory(section: Int, story: Int): Boolean = state?.publicationTask?.removeDraftStory(section, story) ?: false
    fun editDraftStory(section: Int, story: Int, headline: String?, body: String?): Boolean =
        state?.publicationTask?.editDraftStory(section, story, headline, body) ?: false

    fun recordTestEvent(type: EventType, sender: org.bukkit.command.CommandSender): ChronicleEvent? {
        val store = state?.eventStore ?: return null
        val player = sender as? Player
        val playerName = player?.name ?: sender.name
        val details = buildTestDetails(type, playerName)
        val event = ChronicleEvent(
            type = type,
            timestamp = System.currentTimeMillis(),
            playerName = playerName,
            playerUuid = player?.uniqueId?.toString() ?: "",
            world = player?.world?.name ?: "world",
            details = details,
        )
        store.record(event)
        return event
    }

    private fun buildTestDetails(type: EventType, player: String): Map<String, String> {
        return when (type) {
            EventType.DEATH -> mapOf("message" to "$player was slain by Zombie")
            EventType.KILL -> mapOf("entity" to "ZOMBIE", "entityName" to "Zombie")
            EventType.PVP_KILL -> mapOf("message" to "$player was slain by ${player}2", "killer" to "${player}2", "killerUuid" to "uuid-${player}2")
            EventType.ADVANCEMENT -> mapOf("advancement" to "story/root", "displayName" to "Taking Inventory")
            EventType.BIOME_DISCOVERY -> mapOf("biome" to "plains")
            EventType.BLOCK_PLACE -> mapOf("block" to "STONE", "x" to "0", "z" to "0")
            EventType.BLOCK_BREAK -> mapOf("block" to "DIRT", "x" to "0", "z" to "0")
            EventType.ORE_DISCOVERY -> mapOf("ore" to "DIAMOND_ORE", "block" to "Diamond Ore")
            EventType.TRADE -> mapOf("amount" to "3")
            EventType.END_ENTER -> mapOf("fromWorld" to "world", "firstTime" to "true", "count" to "1")
            EventType.CHAT -> mapOf("message" to "Hello everyone!")
            EventType.CRAFT -> mapOf("item" to "DIAMOND_SWORD")
            EventType.ENCHANT -> mapOf("item" to "DIAMOND_CHESTPLATE", "level" to "30")
            EventType.FISH -> mapOf("caught" to "COD")
            EventType.SLEEP -> mapOf("bed" to "true")
            EventType.ITEM_CONSUME -> mapOf("item" to "GOLDEN_APPLE")
            EventType.ITEM_BREAK -> mapOf("item" to "DIAMOND_PICKAXE")
            EventType.SHEAR -> mapOf("entity" to "SHEEP")
            EventType.FURNACE_EXTRACT -> mapOf("item" to "IRON_INGOT", "amount" to "16")
            EventType.PORTAL -> mapOf("from" to "world", "to" to "world_nether", "cause" to "portal")
            EventType.TELEPORT -> mapOf("cause" to "command", "from" to "world", "to" to "world_nether")
            EventType.EXPLOSION -> mapOf("source" to "CREEPER", "blocks" to "5")
            EventType.LIGHTNING -> mapOf("x" to "100", "z" to "200")
            EventType.WEATHER -> mapOf("raining" to "true")
            EventType.THUNDER -> mapOf("thundering" to "true")
            EventType.RAID -> mapOf("location" to "Village at 100, 200")
            EventType.STRUCTURE_GROW -> mapOf("species" to "oak", "blocks" to "20")
            EventType.TAME -> mapOf("entity" to "WOLF")
            EventType.BREED -> mapOf("entity" to "SHEEP")
            EventType.SHEEP_DYE -> mapOf("colour" to "RED")
            EventType.PROJECTILE_LAUNCH -> mapOf("projectile" to "ARROW")
            EventType.POTION_THROW -> mapOf("affected" to "1")
            EventType.FIREWORK -> mapOf("effect" to "burst")
            EventType.RESPAWN -> mapOf("bedSpawn" to "true")
            EventType.KICK -> mapOf("reason" to "Flying is not enabled on this server")
            EventType.GAMEMODE -> mapOf("from" to "SURVIVAL", "to" to "CREATIVE")
            EventType.SIGN_EDIT -> mapOf("text" to "Welcome to the server!")
            EventType.VEHICLE_RIDE -> mapOf("vehicle" to "MINECART")
            EventType.BUCKET -> mapOf("action" to "fill", "bucket" to "WATER")
            EventType.RIPTIDE -> mapOf("item" to "TRIDENT")
            EventType.FLIGHT_TOGGLE -> mapOf("flying" to "true")
            EventType.GLIDE_TOGGLE -> mapOf("gliding" to "true")
            EventType.EGG_THROW -> mapOf("hatched" to "true")
            EventType.HANGING_BREAK -> mapOf("entity" to "ITEM_FRAME", "cause" to "entity")
            EventType.HANGING_PLACE -> mapOf("entity" to "PAINTING")
            EventType.SESSION_START -> mapOf("sessionCount" to "1")
            EventType.DISTANCE_MILESTONE -> mapOf("dist" to "10000")
            EventType.MILESTONE_LOGIN_STREAK -> mapOf("streak" to "7")
            EventType.MILESTONE_PLAYTIME -> mapOf("totalMinutes" to "600")
            EventType.FIRST_JOIN -> mapOf("firstJoin" to "true")
            EventType.PLAYER_JOIN -> emptyMap()
            EventType.PLAYER_LEAVE -> emptyMap()
            EventType.SESSION_END -> emptyMap()
            EventType.MESSAGE_SENT -> mapOf("command" to "msg")
            EventType.ENTITY_TRANSFORM -> mapOf("from" to "ZOMBIE", "to" to "DROWNED", "reason" to "drown")
            EventType.SLIME_SPLIT -> mapOf("count" to "2")
            EventType.CREEPER_POWER -> mapOf("cause" to "lightning")
            EventType.PIG_ZAP -> mapOf("pig" to "true", "zombie" to "true")
            EventType.FIRST_DEATH -> mapOf("message" to "$player discovered gravity")
            EventType.MILESTONE -> mapOf("milestone" to "100 days")
        }
    }

    fun previewNextIssue(onComplete: (Newspaper?) -> Unit): Boolean {
        val s = state ?: return false
        Bukkit.getAsyncScheduler().runNow(this) {
            val issue = runCatching {
                s.generator.generate(s.publicationTask.getIssueNumber() + 1, s.publicationTask.getLastPublishTime(), System.currentTimeMillis())
            }.getOrNull()
            Bukkit.getGlobalRegionScheduler().run(this) { onComplete(issue) }
        }
        return true
    }

    fun reloadPlugin() {
        state?.let { s ->
            s.publicationTask.stop()
            s.headlineTicker?.stop()
            s.eventStore.save()
            s.sessionStore?.save()
            s.subscribeStore.save()
        }
        reloadConfig()
        state = buildState()
    }

    fun getWebPort(): Int = state?.config?.web?.port ?: 0

    fun getLastPublishTime(): Long = state?.publicationTask?.getLastPublishTime() ?: 0L

    fun getLatestNewspaper(): Newspaper? = state?.publicationTask?.getLatestNewspaper()

    fun getArchiveStore(): ArchiveStore? = state?.archiveStore

    fun getSessionStore(): SessionStore? = state?.sessionStore

    fun getEventStore(): EventStore? = state?.eventStore

    fun getNewspaperConfig() = state?.config?.newspaper

    fun getBookRenderer(): BookRenderer? = state?.bookRenderer

    fun getSubscribeStore(): SubscribeStore? = state?.subscribeStore

    fun getEconomyTracker(): EconomyTracker? = state?.economyTracker

    fun getStatus(): PluginStatus {
        val s = state
        return PluginStatus(
            enabled = s?.config?.enabled ?: false,
            schedule = buildString {
                append(s?.config?.schedule ?: "unknown")
                if (s?.config?.scheduleBase == uk.ewancroft.chronicler.config.ScheduleBase.IN_GAME) {
                    append(" (in-game)")
                }
            },
            issueNumber = s?.publicationTask?.getIssueNumber() ?: 0,
            eventCount = s?.eventStore?.allEvents()?.size ?: 0,
            llmAvailable = s?.llmAvailable ?: false,
            webEnabled = s?.config?.web?.enabled ?: false,
            webPort = s?.config?.web?.port ?: 0,
        )
    }

    private fun createProvider(cfg: uk.ewancroft.chronicler.config.LlmConfig): LlmProvider {
        return when (cfg.provider) {
            "openai", "openai-compatible" -> OpenAiProvider(cfg)
            "anthropic" -> AnthropicProvider(cfg)
            "lmstudio" -> LMStudioProvider(cfg)
            "cocore", "co/core" -> CoCoreProvider(cfg)
            else -> OllamaProvider(cfg)
        }
    }

    data class PluginStatus(
        val enabled: Boolean,
        val schedule: String,
        val issueNumber: Int,
        val eventCount: Int,
        val llmAvailable: Boolean,
        val webEnabled: Boolean,
        val webPort: Int,
    )
}
