package uk.ewancroft.chronicler.tracker

import org.bukkit.NamespacedKey
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.persistence.PersistentDataType
import uk.ewancroft.chronicler.Chronicler

class IssueRemovalListener(private val plugin: Chronicler) : Listener {
    private val key = NamespacedKey(plugin, "chronicler_issue")

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val item = event.item ?: return
        if (!item.type.name.endsWith("_BOOK")) return
        val meta = item.itemMeta ?: return
        if (!meta.persistentDataContainer.has(key, PersistentDataType.INTEGER)) return
        // Schedule removal after a short delay to allow reading
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            val player = event.player
            // Remove one matching book from inventory
            val inventory = player.inventory
            for (i in 0 until inventory.size) {
                val stack = inventory.getItem(i) ?: continue
                val stackMeta = stack.itemMeta ?: continue
                if (stackMeta.persistentDataContainer.has(key, PersistentDataType.INTEGER)) {
                    stack.amount = stack.amount - 1
                    if (stack.amount <= 0) inventory.setItem(i, null)
                    break
                }
            }
        }, 20L) // 1 second later
    }
}
