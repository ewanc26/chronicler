package uk.ewancroft.chronicler.integration

import org.bukkit.Bukkit
import xyz.jpenilla.squaremap.api.BukkitAdapter
import xyz.jpenilla.squaremap.api.Key
import xyz.jpenilla.squaremap.api.Point
import xyz.jpenilla.squaremap.api.SimpleLayerProvider
import xyz.jpenilla.squaremap.api.SquaremapProvider
import xyz.jpenilla.squaremap.api.marker.Marker
import xyz.jpenilla.squaremap.api.marker.MarkerOptions
import java.awt.Color
import java.util.logging.Logger

/** Only loaded when squaremap is enabled. One "Chronicler" layer per enabled world. */
class SquaremapLayer(private val label: String, private val logger: Logger) : MapLayer {
    private val layerKey = Key.of("chronicler_stories")
    private val providers = mutableMapOf<String, SimpleLayerProvider>()

    override fun show(stories: List<MapStory>) {
        val api = SquaremapProvider.get()
        for (world in Bukkit.getWorlds()) {
            val mapWorld = api.getWorldIfEnabled(BukkitAdapter.worldIdentifier(world)).orElse(null) ?: continue
            val provider = providers.getOrPut(world.name) {
                SimpleLayerProvider.builder(label).showControls(true).defaultHidden(false).build().also {
                    if (!mapWorld.layerRegistry().hasEntry(layerKey)) mapWorld.layerRegistry().register(layerKey, it)
                }
            }
            provider.clearMarkers()
            stories.filter { it.world == world.name }.forEachIndexed { i, s ->
                val marker = Marker.circle(Point.of(s.x.toDouble(), s.z.toDouble()), 6.0)
                marker.markerOptions(MarkerOptions.builder()
                    .strokeColor(Color(0x6B, 0x3E, 0x00)).fillColor(Color(0xF1, 0xEA, 0xDA)).fillOpacity(0.9)
                    .hoverTooltip(s.headline).clickTooltip(s.detailHtml).build())
                provider.addMarker(Key.of("story_$i"), marker)
            }
        }
    }

    override fun clear() = show(emptyList())
}
