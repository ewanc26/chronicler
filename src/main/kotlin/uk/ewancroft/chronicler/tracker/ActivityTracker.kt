package uk.ewancroft.chronicler.tracker

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.FurnaceExtractEvent
import org.bukkit.event.player.PlayerBedEnterEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerItemBreakEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerShearEntityEvent
import io.papermc.paper.event.player.AsyncChatEvent
import uk.ewancroft.chronicler.config.TrackingConfig
import uk.ewancroft.chronicler.news.ChronicleEvent
import uk.ewancroft.chronicler.news.EventStore
import uk.ewancroft.chronicler.news.EventType

class ActivityTracker(
    private val store: EventStore,
    private val tracking: TrackingConfig,
) : Listener {

    @EventHandler
    fun onChat(event: AsyncChatEvent) {
        if (!tracking.chat) return
        val player = event.player
        store.record(
            ChronicleEvent(
                type = EventType.CHAT,
                timestamp = System.currentTimeMillis(),
                playerName = player.name,
                playerUuid = player.uniqueId.toString(),
                world = player.world.name,
                details = mapOf("message" to event.message().toString().take(200)),
            )
        )
    }

    @EventHandler
    fun onCraft(event: CraftItemEvent) {
        if (!tracking.crafting) return
        val player = event.whoClicked
        if (player !is org.bukkit.entity.Player) return
        val result = event.recipe?.result?.type?.name?.lowercase() ?: "unknown"
        store.record(
            ChronicleEvent(
                type = EventType.CRAFT,
                timestamp = System.currentTimeMillis(),
                playerName = player.name,
                playerUuid = player.uniqueId.toString(),
                world = player.world.name,
                details = mapOf("item" to result),
            )
        )
    }

    @EventHandler
    fun onEnchant(event: EnchantItemEvent) {
        if (!tracking.crafting) return
        val player = event.enchanter
        store.record(
            ChronicleEvent(
                type = EventType.ENCHANT,
                timestamp = System.currentTimeMillis(),
                playerName = player.name,
                playerUuid = player.uniqueId.toString(),
                world = player.world.name,
                details = mapOf(
                    "item" to event.item.type.name.lowercase(),
                    "level" to event.expLevelCost.toString(),
                ),
            )
        )
    }

    @EventHandler
    fun onFurnaceExtract(event: FurnaceExtractEvent) {
        if (!tracking.crafting) return
        val player = event.player
        store.record(
            ChronicleEvent(
                type = EventType.FURNACE_EXTRACT,
                timestamp = System.currentTimeMillis(),
                playerName = player.name,
                playerUuid = player.uniqueId.toString(),
                world = player.world.name,
                details = mapOf(
                    "item" to event.itemType.name.lowercase(),
                    "amount" to event.itemAmount.toString(),
                ),
            )
        )
    }

    @EventHandler
    fun onItemConsume(event: PlayerItemConsumeEvent) {
        if (!tracking.consumption) return
        val player = event.player
        store.record(
            ChronicleEvent(
                type = EventType.ITEM_CONSUME,
                timestamp = System.currentTimeMillis(),
                playerName = player.name,
                playerUuid = player.uniqueId.toString(),
                world = player.world.name,
                details = mapOf("item" to event.item.type.name.lowercase()),
            )
        )
    }

    @EventHandler
    fun onItemBreak(event: PlayerItemBreakEvent) {
        if (!tracking.consumption) return
        val player = event.player
        store.record(
            ChronicleEvent(
                type = EventType.ITEM_BREAK,
                timestamp = System.currentTimeMillis(),
                playerName = player.name,
                playerUuid = player.uniqueId.toString(),
                world = player.world.name,
                details = mapOf("item" to event.brokenItem.type.name.lowercase()),
            )
        )
    }

    @EventHandler
    fun onShear(event: PlayerShearEntityEvent) {
        if (!tracking.misc) return
        val player = event.player
        store.record(
            ChronicleEvent(
                type = EventType.SHEAR,
                timestamp = System.currentTimeMillis(),
                playerName = player.name,
                playerUuid = player.uniqueId.toString(),
                world = player.world.name,
                details = mapOf("entity" to event.entity.type.name.lowercase()),
            )
        )
    }

    @EventHandler
    fun onFish(event: PlayerFishEvent) {
        if (!tracking.fishing) return
        if (event.state != PlayerFishEvent.State.CAUGHT_FISH) return
        val player = event.player
        val caught = event.caught?.type?.name?.lowercase() ?: "nothing"
        store.record(
            ChronicleEvent(
                type = EventType.FISH,
                timestamp = System.currentTimeMillis(),
                playerName = player.name,
                playerUuid = player.uniqueId.toString(),
                world = player.world.name,
                details = mapOf("caught" to caught),
            )
        )
    }

    @EventHandler
    fun onSleep(event: PlayerBedEnterEvent) {
        if (!tracking.sleep) return
        val player = event.player
        store.record(
            ChronicleEvent(
                type = EventType.SLEEP,
                timestamp = System.currentTimeMillis(),
                playerName = player.name,
                playerUuid = player.uniqueId.toString(),
                world = player.world.name,
                details = mapOf("bed" to event.bed.type.name.lowercase()),
            )
        )
    }
}
