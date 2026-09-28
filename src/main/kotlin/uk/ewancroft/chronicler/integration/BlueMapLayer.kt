package uk.ewancroft.chronicler.integration

import de.bluecolored.bluemap.api.BlueMapAPI
import de.bluecolored.bluemap.api.markers.MarkerSet
import de.bluecolored.bluemap.api.markers.POIMarker
import org.bukkit.Bukkit

/** Only loaded when BlueMap is enabled. Markers live in a "Chronicler" set on every map of each world. */
class BlueMapLayer(private val label: String) : MapLayer {
    @Volatile private var pending: List<MapStory> = emptyList()

    init {
        // BlueMap (re)creates its API on each load; re-apply markers whenever it does.
        BlueMapAPI.onEnable { apply(it, pending) }
    }

    override fun show(stories: List<MapStory>) {
        pending = stories
        BlueMapAPI.getInstance().ifPresent { apply(it, stories) }
    }

    override fun clear() = show(emptyList())

    private fun apply(api: BlueMapAPI, stories: List<MapStory>) {
        for (world in Bukkit.getWorlds()) {
            val set = MarkerSet.builder().label(label).toggleable(true).defaultHidden(false).build()
            stories.filter { it.world == world.name }.forEachIndexed { i, s ->
                set.put("story-$i", POIMarker.builder().label(s.headline).detail(s.detailHtml)
                    .position(s.x.toDouble(), s.y.toDouble(), s.z.toDouble()).build())
            }
            api.getWorld(world).ifPresent { bm -> bm.maps.forEach { it.markerSets[KEY] = set } }
        }
    }

    private companion object { const val KEY = "chronicler" }
}
