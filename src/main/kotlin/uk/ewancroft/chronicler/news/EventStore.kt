package uk.ewancroft.chronicler.news

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

class EventStore(private val dataPath: Path) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val events = mutableListOf<ChronicleEvent>()
    private var maxEvents = 500

    fun setMaxEvents(limit: Int) {
        maxEvents = limit
    }

    fun record(event: ChronicleEvent) {
        synchronized(events) {
            events.add(event)
            if (events.size > maxEvents) {
                events.removeAt(0)
            }
        }
    }

    /**
     * Bulk-add events without pruning. Used for backfilling issue #0 from server logs.
     * Pruning is deferred until the next [record] call or [removeThrough].
     */
    fun recordAll(newEvents: List<ChronicleEvent>) {
        synchronized(events) {
            events.addAll(newEvents)
        }
    }

    fun eventsSince(timestamp: Long): List<ChronicleEvent> {
        synchronized(events) {
            return events.filter { it.timestamp > timestamp }.toList()
        }
    }

    fun eventsOfTypeSince(type: EventType, timestamp: Long): List<ChronicleEvent> {
        return eventsSince(timestamp).filter { it.type == type }
    }

    fun allEvents(): List<ChronicleEvent> {
        synchronized(events) {
            return events.toList()
        }
    }

    fun countByType(timestamp: Long): Map<EventType, Int> {
        return eventsSince(timestamp).groupBy { it.type }.mapValues { it.value.size }
    }

    fun clear() {
        synchronized(events) {
            events.clear()
        }
    }

    fun removeThrough(timestamp: Long) {
        synchronized(events) {
            events.removeAll { it.timestamp <= timestamp }
        }
    }

    fun save() {
        synchronized(events) {
            val jsonStr = json.encodeToString(events.toList())
            Files.createDirectories(dataPath.parent)
            dataPath.toFile().writeText(jsonStr)
        }
    }

    fun load() {
        if (Files.exists(dataPath)) {
            try {
                val jsonStr = dataPath.toFile().readText()
                val loaded: List<ChronicleEvent> = json.decodeFromString(jsonStr)
                synchronized(events) {
                    events.clear()
                    events.addAll(loaded.takeLast(maxEvents))
                }
            } catch (_: Exception) {
            }
        }
    }
}
