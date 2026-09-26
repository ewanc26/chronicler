package uk.ewancroft.chronicler.tracker

import uk.ewancroft.chronicler.news.ChronicleEvent
import uk.ewancroft.chronicler.news.EventType

// Event record types for sign placement and teleport
fun signEvent(playerName: String, playerUuid: String, world: String, details: Map<String,String> = emptyMap()): ChronicleEvent =
    ChronicleEvent(EventType.SIGN, System.currentTimeMillis(), playerName, playerUuid, world, details)

fun teleportEvent(playerName: String, playerUuid: String, world: String, details: Map<String,String> = emptyMap()): ChronicleEvent =
    ChronicleEvent(EventType.TELEPORT, System.currentTimeMillis(), playerName, playerUuid, world, details)
