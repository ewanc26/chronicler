package uk.ewancroft.chronicler.integration

import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.event.EventSubscription
import net.luckperms.api.event.user.track.UserPromoteEvent
import org.bukkit.plugin.Plugin
import uk.ewancroft.chronicler.news.EventType

/** Only loaded when LuckPerms is enabled. LuckPerms events fire off the main thread; the event store is synchronized. */
class LuckPermsHook(plugin: Plugin, private val record: (EventType, String, String, String, Map<String, String>) -> Unit) {
    private val subscription: EventSubscription<UserPromoteEvent> =
        LuckPermsProvider.get().eventBus.subscribe(plugin, UserPromoteEvent::class.java) { event ->
            val user = event.user
            val rank = event.groupTo.orElse(null) ?: return@subscribe
            // LuckPerms keeps usernames lower-cased; prefer the server's own spelling.
            val name = org.bukkit.Bukkit.getOfflinePlayer(user.uniqueId).name ?: user.username ?: "unknown"
            record(EventType.RANK_UP, name, user.uniqueId.toString(), "server",
                mapOf("rank" to rank, "track" to event.track.name))
        }

    fun close() = subscription.close()
}
