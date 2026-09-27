package uk.ewancroft.chronicler.integration

import com.palmergames.bukkit.towny.event.DeleteNationEvent
import com.palmergames.bukkit.towny.event.NationAddTownEvent
import com.palmergames.bukkit.towny.event.NewNationEvent
import com.palmergames.bukkit.towny.event.NewTownEvent
import com.palmergames.bukkit.towny.event.TownAddResidentEvent
import com.palmergames.bukkit.towny.event.town.TownRuinedEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import uk.ewancroft.chronicler.news.EventType

/** Only loaded when Towny is enabled. */
class TownyHook(private val record: (EventType, String, String, String, Map<String, String>) -> Unit) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onNewTown(event: NewTownEvent) {
        val town = event.town
        val mayor = town.mayor
        record(EventType.TOWN_FOUNDED, mayor?.name ?: "unknown", mayor?.uuid?.toString() ?: "", town.world?.name ?: "world", mapOf("town" to town.name))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRuined(event: TownRuinedEvent) =
        record(EventType.TOWN_FALLEN, "", "", event.town.world?.name ?: "world", mapOf("town" to event.town.name, "reason" to "ruined"))

    @EventHandler(priority = EventPriority.MONITOR)
    fun onResident(event: TownAddResidentEvent) {
        val resident = event.resident
        record(EventType.TOWN_JOINED, resident.name, resident.uuid?.toString() ?: "", event.town.world?.name ?: "world", mapOf("town" to event.town.name))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onNewNation(event: NewNationEvent) {
        val king = event.nation.king
        record(EventType.NATION_FOUNDED, king?.name ?: "unknown", king?.uuid?.toString() ?: "", "world", mapOf("nation" to event.nation.name))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onNationDeleted(event: DeleteNationEvent) =
        record(EventType.NATION_FALLEN, "", "", "world", mapOf("nation" to event.nationName))

    @EventHandler(priority = EventPriority.MONITOR)
    fun onNationJoin(event: NationAddTownEvent) =
        record(EventType.NATION_JOINED, event.town.mayor?.name ?: "", event.town.mayor?.uuid?.toString() ?: "", "world",
            mapOf("town" to event.town.name, "nation" to event.nation.name))
}
