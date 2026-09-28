package uk.ewancroft.chronicler.integration

import org.bukkit.plugin.Plugin

/**
 * Posts a plain message to DiscordSRV's main channel. DiscordSRV only
 * publishes snapshot APIs, so its static helpers are called reflectively;
 * any failure simply reports false.
 */
class DiscordSrvHook(private val discordSrv: Plugin) {

    fun send(message: String): Boolean = try {
        val loader = discordSrv.javaClass.classLoader
        val main = Class.forName("github.scarsz.discordsrv.DiscordSRV", true, loader)
        val plugin = main.getMethod("getPlugin").invoke(null)
        val channel = main.getMethod("getMainTextChannel").invoke(plugin)
        if (channel == null) false else {
            val util = Class.forName("github.scarsz.discordsrv.util.DiscordUtil", true, loader)
            val send = util.methods.first { it.name == "sendMessage" && it.parameterCount == 2 && it.parameterTypes[1] == String::class.java }
            send.invoke(null, channel, message)
            true
        }
    } catch (_: Throwable) {
        false
    }
}
