package uk.ewancroft.chronicler.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import uk.ewancroft.chronicler.news.ChronicleEvent
import uk.ewancroft.chronicler.news.EventStore
import uk.ewancroft.chronicler.news.EventType
import uk.ewancroft.chronicler.tracker.SessionStore
import uk.ewancroft.chronicler.tracker.SubscribeStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AtomicFilesTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `writeAtomically replaces content and leaves no temporary files`() {
        val file = tempDir.resolve("nested/state.json")
        file.writeAtomically("first")
        file.writeAtomically("second")
        assertEquals("second", file.readText())
        assertEquals(listOf("state.json"), file.parent.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `corrupt event store is moved aside instead of being overwritten`() {
        val file = tempDir.resolve("events.json")
        Files.writeString(file, "[{\"type\":\"DEATH\",\"timest") // truncated mid-write
        val store = EventStore(file)
        store.load()
        assertTrue(store.allEvents().isEmpty())

        store.record(ChronicleEvent(EventType.CHAT, 1L, "a", "u", "world"))
        store.save()

        val quarantined = tempDir.listDirectoryEntries("events.json.corrupt-*")
        assertEquals(1, quarantined.size)
        assertEquals("[{\"type\":\"DEATH\",\"timest", quarantined.single().readText())
    }

    @Test
    fun `corrupt session and subscription stores are preserved for recovery`() {
        val sessions = tempDir.resolve("sessions.json").also { Files.writeString(it, "{\"broken\":") }
        val subs = tempDir.resolve("subscriptions.json").also { Files.writeString(it, "not json") }
        SessionStore(sessions).apply { load(); save() }
        SubscribeStore(subs).apply { load(); save() }
        assertEquals(1, tempDir.listDirectoryEntries("sessions.json.corrupt-*").size)
        assertEquals(1, tempDir.listDirectoryEntries("subscriptions.json.corrupt-*").size)
    }

    @Test
    fun `valid stores round trip through atomic saves`() {
        val file = tempDir.resolve("events.json")
        EventStore(file).apply {
            record(ChronicleEvent(EventType.CHAT, 5L, "a", "u", "world"))
            save()
        }
        val reloaded = EventStore(file).apply { load() }
        assertEquals(1, reloaded.allEvents().size)
        assertTrue(tempDir.listDirectoryEntries("*.corrupt-*").isEmpty())
    }
}
