package uk.ewancroft.chronicler.tracker

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import uk.ewancroft.chronicler.config.TrackingConfig
import uk.ewancroft.chronicler.news.ChronicleEvent
import uk.ewancroft.chronicler.news.EventStore
import uk.ewancroft.chronicler.news.EventType
import java.time.LocalDate
import java.time.ZoneId

class SessionTracker(
    private val store: EventStore,
    private val sessionStore: SessionStore,
    private val tracking: TrackingConfig,
) : Listener {

    private val activeSessions = mutableMapOf<String, Long>()

    /**
     * Starts timing sessions for players who are already online, e.g. when the
     * plugin is enabled or reloaded mid-game, so their playtime is not lost.
     */
    fun resumeSessions(players: Collection<Player>, now: Long = System.currentTimeMillis()) {
        players.forEach { activeSessions.putIfAbsent(it.uniqueId.toString(), now) }
    }

    /**
     * Credits playtime accrued so far by online players without ending their
     * sessions. Called on disable/reload: Paper disables plugins before it
     * disconnects players on shutdown, so their quit events never arrive here.
     */
    fun checkpointSessions(players: Collection<Player>, now: Long = System.currentTimeMillis()) {
        players.forEach { player ->
            val uuid = player.uniqueId.toString()
            val joinTime = activeSessions[uuid] ?: return@forEach
            sessionStore.getOrCreate(uuid, player.name).totalPlaytimeTicks += (now - joinTime) / 50
            activeSessions[uuid] = now
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val uuid = player.uniqueId.toString()
        val now = System.currentTimeMillis()

        val data = sessionStore.getOrCreate(uuid, player.name)
        data.lastSeen = now
        data.sessionCount++
        activeSessions[uuid] = now

        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        if (data.lastLoginDate != today) {
            val yesterday = LocalDate.now(ZoneId.systemDefault()).minusDays(1).toString()
            if (data.lastLoginDate == yesterday) {
                data.currentStreak++
            } else {
                data.currentStreak = 1
            }
            if (data.currentStreak > data.longestStreak) {
                data.longestStreak = data.currentStreak
            }
            data.lastLoginDate = today

            if (data.currentStreak > 1 && data.currentStreak % 5 == 0) {
                store.record(
                    ChronicleEvent(
                        type = EventType.MILESTONE_LOGIN_STREAK,
                        timestamp = now,
                        playerName = player.name,
                        playerUuid = uuid,
                        world = player.world.name,
                        details = mapOf("streak" to data.currentStreak.toString()),
                    )
                )
            }
        }

        if (tracking.social) {
            store.record(
                ChronicleEvent(
                    type = EventType.SESSION_START,
                    timestamp = now,
                    playerName = player.name,
                    playerUuid = uuid,
                    world = player.world.name,
                    details = mapOf("sessionCount" to data.sessionCount.toString()),
                )
            )
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        val uuid = player.uniqueId.toString()
        val now = System.currentTimeMillis()

        val joinTime = activeSessions.remove(uuid)
        if (joinTime != null) {
            val sessionTicks = (now - joinTime) / 50
            val data = sessionStore.getOrCreate(uuid, player.name)
            val minutesBefore = data.totalPlaytimeTicks / TICKS_PER_MINUTE
            data.totalPlaytimeTicks += sessionTicks

            val totalMinutes = data.totalPlaytimeTicks / TICKS_PER_MINUTE
            // Report each whole hour of playtime crossed during this session.
            if (totalMinutes / 60 > minutesBefore / 60) {
                store.record(
                    ChronicleEvent(
                        type = EventType.MILESTONE_PLAYTIME,
                        timestamp = now,
                        playerName = player.name,
                        playerUuid = uuid,
                        world = player.world.name,
                        details = mapOf("totalMinutes" to totalMinutes.toString()),
                    )
                )
            }
        }

        if (tracking.social) {
            store.record(
                ChronicleEvent(
                    type = EventType.SESSION_END,
                    timestamp = now,
                    playerName = player.name,
                    playerUuid = uuid,
                    world = player.world.name,
                )
            )
        }
    }

    private companion object {
        const val TICKS_PER_MINUTE = 20L * 60
    }
}
