package uk.ewancroft.chronicler.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger
import uk.ewancroft.chronicler.util.quarantineCorrupt
import uk.ewancroft.chronicler.util.writeAtomically

@Serializable
data class SubscriptionData(
    val subscribed: Boolean = true,
)

class SubscribeStore(
    private val dataPath: Path,
    private val logger: Logger? = null,
) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val subscriptions = mutableMapOf<String, SubscriptionData>()

    fun isSubscribed(uuid: String): Boolean {
        return synchronized(subscriptions) { subscriptions[uuid]?.subscribed ?: true }
    }

    fun toggle(uuid: String): Boolean {
        val new = synchronized(subscriptions) {
            val current = subscriptions[uuid] ?: SubscriptionData(true)
            current.copy(subscribed = !current.subscribed).also { subscriptions[uuid] = it }
        }
        save()
        return new.subscribed
    }

    fun load() {
        try {
            if (Files.exists(dataPath)) {
                val text = dataPath.toFile().readText()
                val loaded = json.decodeFromString<Map<String, SubscriptionData>>(text)
                synchronized(subscriptions) {
                    subscriptions.clear()
                    subscriptions.putAll(loaded)
                }
            }
        } catch (e: Exception) {
            dataPath.quarantineCorrupt(logger, e)
        }
    }

    fun save() {
        try {
            val snapshot = synchronized(subscriptions) { subscriptions.toMap() }
            dataPath.writeAtomically(json.encodeToString(snapshot))
        } catch (e: Exception) {
            logger?.log(Level.WARNING, "Failed to save subscriptions to ${dataPath.fileName}.", e)
        }
    }
}
