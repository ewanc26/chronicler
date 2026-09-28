package uk.ewancroft.chronicler.integration

import me.angeschossen.lands.api.events.LandDeleteEvent
import me.angeschossen.lands.api.events.land.create.LandPostCreateEvent
import me.angeschossen.lands.api.events.nation.edit.NationCreateEvent
import me.angeschossen.lands.api.events.war.WarDeclareEvent
import me.angeschossen.lands.api.events.war.WarEndEvent
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import uk.ewancroft.chronicler.news.EventType

/** Only loaded when Lands is enabled. Lands are reported as towns, and its nations and wars likewise. */
class LandsHook(private val record: (EventType, String, String, String, Map<String, String>) -> Unit) : Listener {

    private fun owner(uuid: java.util.UUID?): Pair<String, String> =
        (uuid?.let { Bukkit.getOfflinePlayer(it).name } ?: "unknown") to (uuid?.toString() ?: "")

    @EventHandler(priority = EventPriority.MONITOR)
    fun onCreate(event: LandPostCreateEvent) {
        val (name, uuid) = owner(event.land.ownerUID)
        record(EventType.TOWN_FOUNDED, name, uuid, "world", mapOf("town" to event.land.name))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDelete(event: LandDeleteEvent) =
        record(EventType.TOWN_FALLEN, "", "", "world", mapOf("town" to event.land.name, "reason" to event.reason.name.lowercase().replace('_', ' ')))

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onNation(event: NationCreateEvent) {
        val (name, uuid) = owner(event.nation.ownerUID)
        record(EventType.NATION_FOUNDED, name, uuid, "world", mapOf("nation" to event.nation.name))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWar(event: WarDeclareEvent) {
        val player = event.landPlayer
        record(EventType.WAR_DECLARED, player?.name ?: "", player?.uid?.toString() ?: "", "world",
            mapOf("attacker" to event.attacker.name, "defender" to event.defender.name))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWarEnd(event: WarEndEvent) {
        // A war can end without a winner (a draw or surrender terms).
        val winner = event.winner ?: return
        record(EventType.WAR_ENDED, "", "", "world", mapOf("winner" to winner.name, "loser" to (event.loser?.name ?: "their rivals")))
    }
}
