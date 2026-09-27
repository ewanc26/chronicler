package uk.ewancroft.chronicler.tracker

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.entity.Monster
import uk.ewancroft.chronicler.config.TrackingConfig
import uk.ewancroft.chronicler.news.ChronicleEvent
import uk.ewancroft.chronicler.news.EventStore
import uk.ewancroft.chronicler.news.EventType
import uk.ewancroft.chronicler.util.plain

class DeathTracker(
    private val store: EventStore,
    private val tracking: TrackingConfig,
) : Listener {

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        if (!tracking.deaths) return
        val player = event.player
        val killer = player.killer
        val message = event.deathMessage()?.plain() ?: "died"

        store.record(
            ChronicleEvent(
                type = if (killer != null) EventType.PVP_KILL else EventType.DEATH,
                timestamp = System.currentTimeMillis(),
                playerName = player.name,
                playerUuid = player.uniqueId.toString(),
                world = player.world.name,
                details = mapOf(
                    "message" to message,
                    "killer" to (killer?.name ?: "environment"),
                    "killerUuid" to (killer?.uniqueId?.toString() ?: ""),
                ),
            )
        )
    }

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        if (!tracking.kills) return
        val killer = event.entity.killer ?: return
        val entity = event.entity
        if (entity is Player) return

        store.record(
            ChronicleEvent(
                type = EventType.KILL,
                timestamp = System.currentTimeMillis(),
                playerName = killer.name,
                playerUuid = killer.uniqueId.toString(),
                world = killer.world.name,
                details = mapOf(
                    "entity" to entity.type.name.lowercase(),
                    "entityName" to (entity.customName()?.plain() ?: entity.type.name.lowercase()),
                ),
            )
        )
    }
}
