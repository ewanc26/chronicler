package uk.ewancroft.chronicler.tracker

import net.kyori.adventure.text.Component
import org.bukkit.event.player.PlayerQuitEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import uk.ewancroft.chronicler.config.TrackingConfig
import uk.ewancroft.chronicler.news.EventStore
import uk.ewancroft.chronicler.news.EventType
import java.nio.file.Path
import kotlin.test.assertEquals

class SessionTrackerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var server: ServerMock
    private lateinit var events: EventStore
    private lateinit var sessions: SessionStore

    private val tracking = TrackingConfig(
        deaths = true, kills = true, pvp = true, advancements = true, blocks = true,
        exploration = true, social = true, economy = true, chat = true, crafting = true,
        fishing = true, sleep = true, portals = true, entities = true, explosions = true,
        weather = true, raids = true, teleport = true, consumption = true, projectiles = true,
        vehicles = true, misc = true,
    )

    private val minute = 60_000L
    private val ticksPerMinute = 20L * 60

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        events = EventStore(tempDir.resolve("events.json"))
        sessions = SessionStore(tempDir.resolve("sessions.json"))
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private fun quit(tracker: SessionTracker, player: org.bukkit.entity.Player) =
        tracker.onPlayerQuit(PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED))

    @Test
    fun `playtime survives a reload while the player is online`() {
        val player = server.addPlayer("Steve")
        val uuid = player.uniqueId.toString()
        val start = System.currentTimeMillis() - 30 * minute

        val before = SessionTracker(events, sessions, tracking)
        before.resumeSessions(listOf(player), now = start)
        // Reload happens 20 minutes into the session.
        before.checkpointSessions(listOf(player), now = start + 20 * minute)
        assertEquals(20 * ticksPerMinute, sessions.get(uuid)!!.totalPlaytimeTicks)

        val after = SessionTracker(events, sessions, tracking)
        after.resumeSessions(listOf(player), now = start + 20 * minute)
        quit(after, player)

        val total = sessions.get(uuid)!!.totalPlaytimeTicks
        assertEquals(30L, total / ticksPerMinute)
    }

    @Test
    fun `checkpoint does not double count when the player later quits`() {
        val player = server.addPlayer("Alex")
        val uuid = player.uniqueId.toString()
        val start = System.currentTimeMillis() - 10 * minute

        val tracker = SessionTracker(events, sessions, tracking)
        tracker.resumeSessions(listOf(player), now = start)
        tracker.checkpointSessions(listOf(player), now = start + 4 * minute)
        quit(tracker, player)

        assertEquals(10L, sessions.get(uuid)!!.totalPlaytimeTicks / ticksPerMinute)
    }

    @Test
    fun `playtime milestone fires when a session crosses an hour`() {
        val player = server.addPlayer("Herobrine")
        val uuid = player.uniqueId.toString()
        sessions.getOrCreate(uuid, player.name).totalPlaytimeTicks = 57 * ticksPerMinute

        val tracker = SessionTracker(events, sessions, tracking)
        tracker.resumeSessions(listOf(player), now = System.currentTimeMillis() - 7 * minute)
        quit(tracker, player)

        val milestones = events.allEvents().filter { it.type == EventType.MILESTONE_PLAYTIME }
        assertEquals(1, milestones.size)
        assertEquals("64", milestones.single().details["totalMinutes"])
    }

    @Test
    fun `no playtime milestone within the same hour`() {
        val player = server.addPlayer("Notch")
        sessions.getOrCreate(player.uniqueId.toString(), player.name).totalPlaytimeTicks = 5 * ticksPerMinute

        val tracker = SessionTracker(events, sessions, tracking)
        tracker.resumeSessions(listOf(player), now = System.currentTimeMillis() - 10 * minute)
        quit(tracker, player)

        assertEquals(0, events.allEvents().count { it.type == EventType.MILESTONE_PLAYTIME })
    }
}
