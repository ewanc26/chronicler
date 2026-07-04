package uk.ewancroft.chronicler.news

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private val now = System.currentTimeMillis()

    private fun event(type: EventType, ts: Long = now, player: String = "ewanc26"): ChronicleEvent =
        ChronicleEvent(type, ts, player, "uuid-$player", "world")

    @Test
    fun `record adds event to store`() {
        val store = EventStore(tempDir.resolve("events.json"))
        store.record(event(EventType.DEATH))
        assertEquals(1, store.allEvents().size)
    }

    @Test
    fun `eventsSince filters by timestamp`() {
        val store = EventStore(tempDir.resolve("events.json"))
        store.record(event(EventType.DEATH, ts = 100L))
        store.record(event(EventType.KILL, ts = 200L))
        store.record(event(EventType.DEATH, ts = 300L))

        assertEquals(3, store.eventsSince(0L).size)
        assertEquals(2, store.eventsSince(150L).size)
        assertEquals(1, store.eventsSince(250L).size)
        assertEquals(0, store.eventsSince(400L).size)
    }

    @Test
    fun `eventsSince returns events with timestamp greater than given`() {
        val store = EventStore(tempDir.resolve("events.json"))
        store.record(event(EventType.DEATH, ts = 100L))

        assertTrue(store.eventsSince(100L).isEmpty())
        assertEquals(1, store.eventsSince(99L).size)
    }

    @Test
    fun `eventsOfTypeSince filters by type`() {
        val store = EventStore(tempDir.resolve("events.json"))
        store.record(event(EventType.DEATH, ts = 100L))
        store.record(event(EventType.KILL, ts = 200L))
        store.record(event(EventType.ADVANCEMENT, ts = 300L))

        val deaths = store.eventsOfTypeSince(EventType.DEATH, 0L)
        assertEquals(1, deaths.size)
        assertEquals(EventType.DEATH, deaths[0].type)
    }

    @Test
    fun `countByType groups correctly`() {
        val store = EventStore(tempDir.resolve("events.json"))
        store.record(event(EventType.DEATH, ts = 100L))
        store.record(event(EventType.DEATH, ts = 200L))
        store.record(event(EventType.KILL, ts = 300L))

        val counts = store.countByType(0L)
        assertEquals(2, counts[EventType.DEATH])
        assertEquals(1, counts[EventType.KILL])
        assertEquals(2, counts.size)
    }

    @Test
    fun `clear removes all events`() {
        val store = EventStore(tempDir.resolve("events.json"))
        store.record(event(EventType.DEATH))
        store.record(event(EventType.KILL))
        assertEquals(2, store.allEvents().size)

        store.clear()
        assertEquals(0, store.allEvents().size)
    }

    @Test
    fun `removeThrough preserves events after cutoff`() {
        val store = EventStore(tempDir.resolve("events.json"))
        store.record(event(EventType.DEATH, ts = 100L))
        store.record(event(EventType.KILL, ts = 200L))
        store.record(event(EventType.ADVANCEMENT, ts = 300L))

        store.removeThrough(200L)

        assertEquals(listOf(EventType.ADVANCEMENT), store.allEvents().map { it.type })
    }

    @Test
    fun `setMaxEvents prunes oldest events`() {
        val store = EventStore(tempDir.resolve("events.json"))
        store.setMaxEvents(3)
        store.record(event(EventType.DEATH, ts = 100L, player = "a"))
        store.record(event(EventType.DEATH, ts = 200L, player = "b"))
        store.record(event(EventType.DEATH, ts = 300L, player = "c"))
        store.record(event(EventType.DEATH, ts = 400L, player = "d"))

        assertEquals(3, store.allEvents().size)
        assertEquals("b", store.allEvents()[0].playerName)
    }

    @Test
    fun `save and load persists events`() {
        val path = tempDir.resolve("events.json")
        val store1 = EventStore(path)
        store1.record(event(EventType.DEATH, ts = 100L))
        store1.record(event(EventType.KILL, ts = 200L))
        store1.save()

        val store2 = EventStore(path)
        store2.load()
        assertEquals(2, store2.allEvents().size)
        assertEquals(EventType.DEATH, store2.allEvents()[0].type)
    }

    @Test
    fun `load with empty or missing file does nothing`() {
        val store = EventStore(tempDir.resolve("nonexistent.json"))
        store.load()
        assertTrue(store.allEvents().isEmpty())
    }

    @Test
    fun `load from corrupt file does nothing`() {
        val path = tempDir.resolve("corrupt.json")
        path.toFile().writeText("not valid json")
        val store = EventStore(path)
        store.load()
        assertTrue(store.allEvents().isEmpty())
    }

    @Test
    fun `load respects maxEvents limit`() {
        val path = tempDir.resolve("events.json")
        val store1 = EventStore(path)
        repeat(10) { i ->
            store1.record(event(EventType.DEATH, ts = i * 100L, player = "p$i"))
        }
        store1.save()

        val store2 = EventStore(path)
        store2.setMaxEvents(3)
        store2.load()
        assertEquals(3, store2.allEvents().size)
        assertEquals("p7", store2.allEvents()[0].playerName)
    }

    @Test
    fun `recordAll adds events without pruning`() {
        val store = EventStore(tempDir.resolve("events.json"))
        store.setMaxEvents(3)
        val events = (1..10).map { i ->
            event(EventType.DEATH, ts = i * 100L, player = "p$i")
        }
        store.recordAll(events)
        assertEquals(10, store.allEvents().size)
    }

    @Test
    fun `recordAll followed by record prunes to maxEvents`() {
        val store = EventStore(tempDir.resolve("events.json"))
        store.setMaxEvents(5)
        val events = (1..3).map { i ->
            event(EventType.DEATH, ts = i * 100L, player = "p$i")
        }
        store.recordAll(events)
        store.record(event(EventType.KILL, ts = 400L, player = "new"))
        assertEquals(4, store.allEvents().size)
    }
}
