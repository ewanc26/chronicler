package uk.ewancroft.chronicler.reader

import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.bukkit.plugin.Plugin
import uk.ewancroft.chronicler.config.PluginConfig
import uk.ewancroft.chronicler.news.Newspaper
import uk.ewancroft.chronicler.news.NewspaperPack
import uk.ewancroft.chronicler.news.NewspaperTypesetter
import uk.ewancroft.chronicler.util.writeAtomically
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Owns the Chronicler resource pack for the latest issue: typesets and builds
 * it off the server thread, serves it over the web server, offers it to
 * players, and records which players have it loaded so the reader knows
 * whether printed pages will render for them.
 */
class ResourcePackService(
    private val plugin: Plugin,
    private val config: PluginConfig,
    private val webDir: Path,
    private val logger: Logger,
) {
    @Volatile
    var current: NewspaperPack.Built? = null
        private set

    private val loaded = ConcurrentHashMap<UUID, UUID>()
    private val offered = ConcurrentHashMap<UUID, UUID>()
    private var warnedNoUrl = false

    val enabled: Boolean get() = config.reader.newspaperMode && config.reader.packEnabled

    /** Typesets [newspaper] and rebuilds the pack. Blocking; call from an async task. */
    fun rebuild(newspaper: Newspaper) {
        if (!enabled) return
        try {
            val started = System.currentTimeMillis()
            val pages = NewspaperTypesetter(config.newspaper).typeset(newspaper)
            val built = NewspaperPack(
                tileGuiSize = config.reader.tileGuiSize,
                inks = NewspaperTypesetter.inks(config.newspaper.accentColor),
            ).build(newspaper.issueNumber, pages)
            Files.createDirectories(webDir)
            webDir.resolve("chronicler-pack.zip").writeAtomically(built.zip)
            current = built
            logger.info("Printed issue #${newspaper.issueNumber}: ${pages.size} page(s), pack ${built.zip.size / 1024} KB in ${System.currentTimeMillis() - started}ms.")
            Bukkit.getGlobalRegionScheduler().run(plugin) { _ -> Bukkit.getOnlinePlayers().forEach(::offer) }
        } catch (e: Throwable) {
            logger.log(Level.WARNING, "Could not print issue #${newspaper.issueNumber}; readers will get the text edition.", e)
        }
    }

    /** The web server hands requests for /chronicler-pack/<sha1>.zip here. */
    fun serve(path: String): ByteArray? {
        val built = current ?: return null
        return if (path == "/chronicler-pack/${built.sha1}.zip") built.zip else null
    }

    fun offer(player: Player) {
        val built = current ?: return
        val url = packUrl(built) ?: return
        if (offered[player.uniqueId] == built.id && loaded[player.uniqueId] == built.id) return
        // Replace only our previous issue's pack, never the server's or other plugins' packs.
        offered.put(player.uniqueId, built.id)?.takeIf { it != built.id }?.let { player.removeResourcePacks(it) }
        val prompt = config.reader.packPrompt.ifBlank {
            "<gold>Chronicler</gold> <gray>prints the server newspaper as real pages. Accept to read them in-game.</gray>"
        }
        player.sendResourcePacks(
            ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(built.id, URI.create(url), built.sha1))
                .required(config.reader.packRequired)
                .replace(false)
                .prompt(MiniMessage.miniMessage().deserialize(prompt))
                .build()
        )
    }

    fun onStatus(event: PlayerResourcePackStatusEvent) {
        val built = current ?: return
        if (event.id != built.id) return
        when (event.status) {
            PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED -> loaded[event.player.uniqueId] = event.id
            PlayerResourcePackStatusEvent.Status.DECLINED,
            PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD,
            PlayerResourcePackStatusEvent.Status.FAILED_RELOAD,
            PlayerResourcePackStatusEvent.Status.INVALID_URL,
            PlayerResourcePackStatusEvent.Status.DISCARDED -> {
                loaded.remove(event.player.uniqueId)
                if (event.status != PlayerResourcePackStatusEvent.Status.DECLINED) {
                    logger.warning("${event.player.name} could not load the Chronicler pack (${event.status}).")
                }
            }
            else -> {}
        }
    }

    fun forget(player: Player) {
        loaded.remove(player.uniqueId)
        offered.remove(player.uniqueId)
    }

    /** Printed page glyphs for [issueNumber], if this player's client can draw them. */
    fun pagesFor(player: Player, issueNumber: Int): List<NewspaperPack.PageGlyphs>? {
        val built = current ?: return null
        if (built.issueNumber != issueNumber || loaded[player.uniqueId] != built.id) return null
        return built.pages
    }

    private fun packUrl(built: NewspaperPack.Built): String? {
        val base = config.reader.packPublicUrl.ifBlank {
            val ip = Bukkit.getIp()
            if (ip.isBlank() || !config.web.enabled) null else "http://$ip:${config.web.port}"
        }
        if (base == null) {
            if (!warnedNoUrl) {
                warnedNoUrl = true
                logger.warning("Set reader.resource-pack.public-url so players can download the newspaper pack; " +
                    "until then it is only written to ${webDir.resolve("chronicler-pack.zip")}.")
            }
            return null
        }
        return "$base/chronicler-pack/${built.sha1}.zip"
    }
}
