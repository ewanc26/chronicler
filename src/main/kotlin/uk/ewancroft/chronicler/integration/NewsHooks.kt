package uk.ewancroft.chronicler.integration

import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import uk.ewancroft.chronicler.news.ChronicleEvent
import uk.ewancroft.chronicler.news.EventStore
import uk.ewancroft.chronicler.news.EventType
import java.util.logging.Logger

data class IntegrationConfig(
    val towny: Boolean = true,
    val lands: Boolean = true,
    val mcmmo: Boolean = true,
    /** mcMMO skill levels are reported each time a multiple of this is crossed. */
    val mcmmoMilestone: Int = 100,
    val luckperms: Boolean = true,
    val quickshop: Boolean = true,
    val votifier: Boolean = true,
    val plan: Boolean = true,
)

/** Records newsworthy events from other plugins. Each hook class is only loaded when its plugin is enabled. */
class NewsHooks(private val plugin: Plugin, private val store: EventStore, private val config: IntegrationConfig, private val logger: Logger) {
    private val cleanups = mutableListOf<() -> Unit>()

    fun register() {
        val pm = plugin.server.pluginManager
        fun listen(name: String, enabled: Boolean, create: () -> Listener) {
            if (!enabled) return
            hook(plugin, name, logger, create)?.let { pm.registerEvents(it, plugin) }
        }
        listen("Towny", config.towny) { TownyHook(record) }
        listen("Lands", config.lands) { LandsHook(record) }
        listen("mcMMO", config.mcmmo) { McMMOHook(record, config.mcmmoMilestone) }
        listen("QuickShop-Hikari", config.quickshop) { QuickShopHook(record) }
        if (config.luckperms) hook(plugin, "LuckPerms", logger) { LuckPermsHook(plugin, record) }?.let { cleanups += it::close }
        if (config.votifier) registerVotifier()
    }

    fun close() = cleanups.forEach { runCatching(it) }

    private val record: (EventType, String, String, String, Map<String, String>) -> Unit = { type, name, uuid, world, details ->
        store.record(ChronicleEvent(type, System.currentTimeMillis(), name, uuid, world, details))
    }

    /**
     * NuVotifier's event is tiny and its API is only published through JitPack,
     * so it is registered reflectively: VotifierEvent#getVote -> Vote#getUsername/getServiceName.
     */
    private fun registerVotifier() {
        val votifier = plugin.server.pluginManager.getPlugin("Votifier")?.takeIf { it.isEnabled } ?: return
        try {
            @Suppress("UNCHECKED_CAST")
            val eventClass = Class.forName("com.vexsoftware.votifier.model.VotifierEvent", true, votifier.javaClass.classLoader) as Class<out Event>
            val executor = EventExecutor { _, event ->
                if (!eventClass.isInstance(event)) return@EventExecutor
                val vote = event.javaClass.getMethod("getVote").invoke(event)
                val username = vote.javaClass.getMethod("getUsername").invoke(vote) as? String ?: return@EventExecutor
                val service = vote.javaClass.getMethod("getServiceName").invoke(vote) as? String ?: "a server list"
                if (!username.matches(Regex("[A-Za-z0-9_.]{1,20}"))) return@EventExecutor
                val player = Bukkit.getOfflinePlayerIfCached(username)
                record(EventType.VOTE, username, player?.uniqueId?.toString() ?: "", "server", mapOf("service" to service.take(60)))
            }
            plugin.server.pluginManager.registerEvent(eventClass, object : Listener {}, EventPriority.MONITOR, executor, plugin, true)
            logger.info("Hooked into Votifier.")
        } catch (e: Throwable) {
            logger.warning("Found Votifier but could not hook into it (${e.javaClass.simpleName}).")
        }
    }
}
