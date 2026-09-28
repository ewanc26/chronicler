package uk.ewancroft.chronicler.tracker

import net.kyori.adventure.text.Component
import org.bukkit.event.player.PlayerJoinEvent
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

class MilestoneTrackerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var server: ServerMock

    private val tracking = TrackingConfig(
        deaths = true, kills = true, pvp = true, advancements = true, blocks = true,
        exploration = true, social = true, economy = true, chat = true, crafting = true,
        fishing = true, sleep = true, portals = true, entities = true, explosions = true,
        weather = true, raids = true, teleport = true, consumption = true, projectiles = true,
        vehicles = true, misc = true,
    )

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private fun EventStore.count(type: EventType) = allEvents().count { it.type == type }

    @Test
    fun `first join is recorded only for players who have never played`() {
        val store = EventStore(tempDir.resolve("events.json"))
        val tracker = MilestoneTracker(store, tracking)
        val player = server.addPlayer("Newcomer")
        check(!player.hasPlayedBefore()) { "MockBukkit should treat a newly added player as never having played" }

        tracker.onPlayerJoin(PlayerJoinEvent(player, Component.empty()))
        assertEquals(1, store.count(EventType.FIRST_JOIN))
        assertEquals(1, store.count(EventType.PLAYER_JOIN))
    }

    @Test
    fun `returning player is not a first join after a reload`() {
        val store = EventStore(tempDir.resolve("events.json"))
        val player = server.addPlayer("Veteran")
        player.disconnect()
        player.reconnect()
        check(player.hasPlayedBefore()) { "MockBukkit should mark a reconnected player as having played before" }

        // A fresh tracker has no in-memory history, as after a restart or reload.
        MilestoneTracker(store, tracking).onPlayerJoin(PlayerJoinEvent(player, Component.empty()))
        assertEquals(0, store.count(EventType.FIRST_JOIN))
        assertEquals(1, store.count(EventType.PLAYER_JOIN))
    }
}
