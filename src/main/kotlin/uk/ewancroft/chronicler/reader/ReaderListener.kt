package uk.ewancroft.chronicler.reader

import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import uk.ewancroft.chronicler.config.NewspaperConfig
import uk.ewancroft.chronicler.news.BookRenderer

/** Opens the reader for newspaper items and keeps the pack in step with players. */
class ReaderListener(
    private val reader: NewspaperReader,
    private val packs: ResourcePackService,
    private val newspaperConfig: NewspaperConfig,
) : Listener {

    @EventHandler(priority = EventPriority.HIGH)
    fun onUse(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val issue = BookRenderer.issueNumberOf(event.item, newspaperConfig) ?: return
        // Only take over when the issue can be found; otherwise the book opens as normal.
        if (reader.open(event.player, issue)) {
            event.setUseItemInHand(Event.Result.DENY)
            event.setUseInteractedBlock(Event.Result.DENY)
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (packs.enabled) packs.offer(event.player)
    }

    @EventHandler
    fun onPackStatus(event: PlayerResourcePackStatusEvent) = packs.onStatus(event)

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) = packs.forget(event.player)
}
