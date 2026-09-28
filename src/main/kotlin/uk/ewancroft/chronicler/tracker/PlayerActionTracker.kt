package uk.ewancroft.chronicler.tracker

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerEggThrowEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerRiptideEvent
import org.bukkit.event.player.PlayerToggleFlightEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import uk.ewancroft.chronicler.config.TrackingConfig
import uk.ewancroft.chronicler.news.ChronicleEvent
import uk.ewancroft.chronicler.news.EventStore
import uk.ewancroft.chronicler.news.EventType
import uk.ewancroft.chronicler.util.plain
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class PlayerActionTracker(
    private val store: EventStore,
    private val tracking: TrackingConfig,
) : Listener {

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        if (!tracking.misc) return
        val player = event.player
        store.record(
            ChronicleEvent(
                type = EventType.RESPAWN,
                timestamp = System.currentTimeMillis(),
                playerName = player.name,
                playerUuid = player.uniqueId.toString(),
                world = player.world.name,
                details = mapOf("bedSpawn" to event.isBedSpawn.toString()),
            )
        )
    }

    @EventHandler
    fun onKick(event: PlayerKickEvent) {
        if (!tracking.misc) return
        val player = event.player
        val reason = PlainTextComponentSerializer.plainText().serialize(event.reason())
        store.record(
            ChronicleEvent(
                type = EventType.KICK,
                timestamp = System.currentTimeMillis(),
                playerName = player.name,
                playerUuid = player.uniqueId.toString(),
                world = player.world.name,
                details = mapOf("reason" to reason.take(100)),
            )
        )
    }

    @EventHandler
    fun onGameModeChange(event: PlayerGameModeChangeEvent) {
        if (!tracking.misc) return
        val player = event.player
        store.record(
            ChronicleEvent(
                type = EventType.GAMEMODE,
                timestamp = System.currentTimeMillis(),
                playerName = player.name,
                playerUuid = player.uniqueId.toString(),
                world = player.world.name,
                details = mapOf(
                    "from" to player.gameMode.name.lowercase(),
                    "to" to event.newGameMode.name.lowercase(),
                ),
            )
        )
    }

    @EventHandler
    fun onSignChange(event: SignChangeEvent) {
        if (!tracking.misc) return
        val player = event.player
        val lines = event.lines().map { it.plain().trim() }.filter { it.isNotEmpty() }.joinToString(" | ") { it.take(40) }.take(200)
        if (lines.isEmpty()) return
        store.record(
            ChronicleEvent(
                type = EventType.SIGN_EDIT,
                timestamp = System.currentTimeMillis(),
                playerName = player.name,
                playerUuid = player.uniqueId.toString(),
                world = player.world.name,
                details = mapOf("text" to lines),
            )
        )
    }

    @EventHandler
    fun onVehicleEnter(event: VehicleEnterEvent) {
        if (!tracking.vehicles) return
        val player = event.entered as? org.bukkit.entity.Player ?: return
        store.record(
            ChronicleEvent(
                type = EventType.VEHICLE_RIDE,
                timestamp = System.currentTimeMillis(),
                playerName = player.name,
                playerUuid = player.uniqueId.toString(),
                world = player.world.name,
                details = mapOf("vehicle" to event.vehicle.type.name.lowercase()),
            )
        )
    }

    @EventHandler
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (!tracking.misc) return
        val player = event.player
        store.record(
            ChronicleEvent(
                type = EventType.BUCKET,
                timestamp = System.currentTimeMillis(),
                playerName = player.name,
                playerUuid = player.uniqueId.toString(),
                world = player.world.name,
                details = mapOf(
                    "action" to "empty",
                    "bucket" to event.bucket.name.lowercase(),
                ),
            )
        )
    }

    @EventHandler
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (!tracking.misc) return
        val player = event.player
        store.record(
            ChronicleEvent(
                type = EventType.BUCKET,
                timestamp = System.currentTimeMillis(),
                playerName = player.name,
                playerUuid = player.uniqueId.toString(),
                world = player.world.name,
                details = mapOf(
                    "action" to "fill",
                    "bucket" to event.bucket.name.lowercase(),
                ),
            )
        )
    }

    @EventHandler
    fun onRiptide(event: PlayerRiptideEvent) {
        if (!tracking.projectiles) return
        val player = event.player
        store.record(
            ChronicleEvent(
                type = EventType.RIPTIDE,
                timestamp = System.currentTimeMillis(),
                playerName = player.name,
                playerUuid = player.uniqueId.toString(),
                world = player.world.name,
                details = mapOf("item" to event.item.type.name.lowercase()),
            )
        )
    }

    @EventHandler
    fun onToggleFlight(event: PlayerToggleFlightEvent) {
        if (!tracking.misc) return
        val player = event.player
        store.record(
            ChronicleEvent(
                type = EventType.FLIGHT_TOGGLE,
                timestamp = System.currentTimeMillis(),
                playerName = player.name,
                playerUuid = player.uniqueId.toString(),
                world = player.world.name,
                details = mapOf("flying" to event.isFlying.toString()),
            )
        )
    }

    @EventHandler
    fun onEggThrow(event: PlayerEggThrowEvent) {
        if (!tracking.misc) return
        val player = event.player
        store.record(
            ChronicleEvent(
                type = EventType.EGG_THROW,
                timestamp = System.currentTimeMillis(),
                playerName = player.name,
                playerUuid = player.uniqueId.toString(),
                world = player.world.name,
                details = mapOf("hatched" to (event.numHatches > 0).toString()),
            )
        )
    }

    @EventHandler
    fun onHangingBreak(event: HangingBreakByEntityEvent) {
        if (!tracking.misc) return
        val entity = event.entity
        val player = event.remover as? org.bukkit.entity.Player
        store.record(
            ChronicleEvent(
                type = EventType.HANGING_BREAK,
                timestamp = System.currentTimeMillis(),
                playerName = player?.name ?: "world",
                playerUuid = player?.uniqueId?.toString() ?: "",
                world = entity.world.name,
                details = mapOf(
                    "entity" to entity.type.name.lowercase(),
                    "cause" to event.cause.name.lowercase(),
                ),
            )
        )
    }

    @EventHandler
    fun onHangingPlace(event: HangingPlaceEvent) {
        if (!tracking.misc) return
        val player = event.player ?: return
        store.record(
            ChronicleEvent(
                type = EventType.HANGING_PLACE,
                timestamp = System.currentTimeMillis(),
                playerName = player.name,
                playerUuid = player.uniqueId.toString(),
                world = player.world.name,
                details = mapOf("entity" to event.entity.type.name.lowercase()),
            )
        )
    }
}
