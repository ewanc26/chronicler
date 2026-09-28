package uk.ewancroft.chronicler.news

import uk.ewancroft.chronicler.config.PluginConfig
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Level
import java.util.logging.Logger
import javax.imageio.ImageIO

/** One issue, typeset: page images and their PNG encodings. */
class PrintedIssue(val issue: Newspaper, val pages: List<BufferedImage>) {
    val png: List<ByteArray> = pages.map { page -> ByteArrayOutputStream().also { ImageIO.write(page, "png", it) }.toByteArray() }
}

/**
 * Typesets each new issue once (with portraits) and hands the result to
 * everything that shows printed pages: the resource pack, newsstands, the
 * web /print route and publishers such as Discord and Standard.site.
 */
class PrintShop(
    private val config: PluginConfig,
    private val logger: Logger,
    private val portraits: Portraits,
    private val onlineMode: () -> Boolean,
    private val baseUrlFallback: () -> String?,
) {
    @Volatile
    var latest: PrintedIssue? = null
        private set

    private val listeners = CopyOnWriteArrayList<(PrintedIssue) -> Unit>()

    /** Registers a consumer; it is called on the printing (async) thread. */
    fun onPrinted(listener: (PrintedIssue) -> Unit) {
        listeners += listener
    }

    /** Typesets [newspaper] and notifies consumers. Blocking; call from an async task. */
    fun print(newspaper: Newspaper): PrintedIssue? {
        return try {
            val started = System.currentTimeMillis()
            val faces = if (portraitsEnabled()) portraits.facesFor(newspaper.sections.flatMap { s -> s.stories.flatMap { it.players } }) else emptyMap()
            val printed = PrintedIssue(newspaper, NewspaperTypesetter(config.newspaper).typeset(newspaper, faces))
            latest = printed
            logger.info("Printed issue #${newspaper.issueNumber}: ${printed.pages.size} page(s) in ${System.currentTimeMillis() - started}ms.")
            for (listener in listeners) {
                try {
                    listener(printed)
                } catch (e: Throwable) {
                    logger.log(Level.WARNING, "A consumer of printed issue #${newspaper.issueNumber} failed.", e)
                }
            }
            printed
        } catch (e: Throwable) {
            logger.log(Level.WARNING, "Could not print issue #${newspaper.issueNumber}; readers will get the text edition.", e)
            null
        }
    }

    /** Serves /print/<issue>/page-<n>.png for the current issue. */
    fun serve(path: String): ByteArray? {
        val printed = latest ?: return null
        val match = PAGE_PATH.matchEntire(path) ?: return null
        if (match.groupValues[1].toInt() != printed.issue.issueNumber) return null
        return printed.png.getOrNull(match.groupValues[2].toInt() - 1)
    }

    /** Where players and other services reach the web server, if known. */
    fun baseUrl(): String? = config.web.publicUrl.ifBlank { null } ?: baseUrlFallback()

    fun pageUrl(page: Int = 1): String? {
        val printed = latest ?: return null
        return baseUrl()?.let { "$it/print/${printed.issue.issueNumber}/page-$page.png" }
    }

    private fun portraitsEnabled(): Boolean = when (config.newspaper.portraits) {
        "true", "on", "yes" -> true
        "false", "off", "no" -> false
        else -> onlineMode()
    }

    companion object {
        private val PAGE_PATH = Regex("/print/(\\d+)/page-(\\d+)\\.png")
    }
}
