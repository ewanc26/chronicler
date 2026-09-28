package uk.ewancroft.chronicler.integration

import com.gmail.nossr50.events.experience.McMMOPlayerLevelUpEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import uk.ewancroft.chronicler.news.EventType

/** Only loaded when mcMMO is enabled. Reports each time a skill crosses a multiple of [step] levels. */
class McMMOHook(private val record: (EventType, String, String, String, Map<String, String>) -> Unit, private val step: Int) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLevelUp(event: McMMOPlayerLevelUpEvent) {
        val level = event.skillLevel
        val before = level - event.levelsGained
        if (step <= 0 || level / step <= before / step) return
        val player = event.player
        record(EventType.SKILL_MILESTONE, player.name, player.uniqueId.toString(), player.world.name,
            mapOf("skill" to event.skill.getName(), "level" to (level / step * step).toString()))
    }
}
