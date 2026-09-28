package uk.ewancroft.chronicler.integration

import org.bukkit.plugin.Plugin
import uk.ewancroft.chronicler.news.Newspaper
import uk.ewancroft.chronicler.news.Story
import java.util.logging.Logger

/** A story placed on a web map: where, what, and a link to read it. */
data class MapStory(val world: String, val x: Int, val y: Int, val z: Int, val headline: String, val detailHtml: String)

interface MapLayer {
    fun show(stories: List<MapStory>)
    fun clear()
}

/** Puts the latest issue's located stories on BlueMap and/or squaremap. */
class MapMarkers(plugin: Plugin, logger: Logger, private val layerName: String) {
    private val layers: List<MapLayer> = listOfNotNull(
        hook(plugin, "BlueMap", logger) { BlueMapLayer(layerName) },
        hook(plugin, "squaremap", logger) { SquaremapLayer(layerName, logger) },
    )

    val active: Boolean get() = layers.isNotEmpty()

    fun update(issue: Newspaper, issueUrl: String?) {
        if (layers.isEmpty()) return
        val stories = located(issue, issueUrl)
        layers.forEach { runCatching { it.show(stories) } }
    }

    fun clear() = layers.forEach { runCatching { it.clear() } }

    companion object {
        fun located(issue: Newspaper, issueUrl: String?): List<MapStory> =
            issue.sections.flatMap { section -> section.stories.mapNotNull { story -> toMap(section.title, story, issue, issueUrl) } }

        private fun toMap(section: String, story: Story, issue: Newspaper, issueUrl: String?): MapStory? {
            val loc = story.location ?: return null
            val link = issueUrl?.let { "<br><a href=\"${escape(it)}\" target=\"_blank\">Read issue #${issue.issueNumber}</a>" } ?: ""
            val html = "<b>${escape(story.headline)}</b><br><i>${escape(section)}</i><br>${escape(story.body.take(280))}$link"
            return MapStory(loc.world, loc.x, loc.y ?: 64, loc.z, story.headline, html)
        }

        private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
