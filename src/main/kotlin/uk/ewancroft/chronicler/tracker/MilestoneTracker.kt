package uk.ewancroft.chronicler.tracker

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.advancement.Advancement
import uk.ewancroft.chronicler.config.TrackingConfig
import uk.ewancroft.chronicler.news.ChronicleEvent
import uk.ewancroft.chronicler.news.EventStore
import uk.ewancroft.chronicler.news.EventType
import uk.ewancroft.chronicler.util.plain

class MilestoneTracker(
    private val store: EventStore,
    private val tracking: TrackingConfig,
) : Listener {

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val uuid = player.uniqueId

        // hasPlayedBefore() is backed by the player's saved data, so unlike an
        // in-memory set it survives restarts and reloads.
        if (!player.hasPlayedBefore()) {
            store.record(
                ChronicleEvent(
                    type = EventType.FIRST_JOIN,
                    timestamp = System.currentTimeMillis(),
                    playerName = player.name,
                    playerUuid = uuid.toString(),
                    world = player.world.name,
                    details = mapOf("firstJoin" to "true"),
                )
            )
        }

        if (tracking.social) {
            store.record(
                ChronicleEvent(
                    type = EventType.PLAYER_JOIN,
                    timestamp = System.currentTimeMillis(),
                    playerName = player.name,
                    playerUuid = uuid.toString(),
                    world = player.world.name,
                )
            )
        }
    }

    @EventHandler
    fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        if (!tracking.advancements) return
        val player = event.player
        val adv = event.advancement
        val key = adv.key
        val namespace = key.toString().substringBefore(':')
        val path = key.toString().substringAfter(':')

        if (namespace != "minecraft") return
        if (path.startsWith("recipes/")) return

        store.record(
            ChronicleEvent(
                type = EventType.ADVANCEMENT,
                timestamp = System.currentTimeMillis(),
                playerName = player.name,
                playerUuid = player.uniqueId.toString(),
                world = player.world.name,
                details = mapOf(
                    "advancement" to path,
                    "displayName" to (adv.display?.title()?.plain() ?: adv.displayName().plain().removeSurrounding("[", "]"))
                ),
            )
        )
    }
}
