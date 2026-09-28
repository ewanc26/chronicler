package uk.ewancroft.chronicler.integration

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.logging.Logger

/** What a player's client can show, which decides how Chronicler presents an issue. */
enum class ClientKind {
    /** Current Java client: dialogs and the Chronicler resource pack. */
    MODERN,

    /** Older Java client joined through ViaVersion: no dialogs (ViaBackwards turns them into chests) and no 26.x pack. */
    LEGACY_JAVA,

    /** Bedrock client joined through Geyser/Floodgate: forms instead of dialogs, no Java resource packs. */
    BEDROCK,
}

/**
 * Classifies players by client. The ViaVersion and Floodgate hooks are separate
 * classes that are only loaded when those plugins are enabled, so their absence
 * never causes class-loading errors.
 */
class ClientSupport(plugin: Plugin, logger: Logger) {

    private val via: ViaVersionHook? = hook(plugin, "ViaVersion", logger) { ViaVersionHook() }
    val floodgate: FloodgateHook? = hook(plugin, "floodgate", logger) { FloodgateHook() }

    fun kind(player: Player): ClientKind = when {
        floodgate?.isBedrock(player.uniqueId) == true -> ClientKind.BEDROCK
        via?.isOlderThanServer(player.uniqueId) == true -> ClientKind.LEGACY_JAVA
        else -> ClientKind.MODERN
    }
}

internal fun <T> hook(plugin: Plugin, name: String, logger: Logger, create: () -> T): T? {
    if (!plugin.server.pluginManager.isPluginEnabled(name)) return null
    return try {
        create().also { logger.info("Hooked into $name.") }
    } catch (e: Throwable) {
        logger.warning("Found $name but could not hook into it (${e.javaClass.simpleName}: ${e.message}).")
        null
    }
}
