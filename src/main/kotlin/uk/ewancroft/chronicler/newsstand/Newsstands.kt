package uk.ewancroft.chronicler.newsstand

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.MapMeta
import org.bukkit.map.MapCanvas
import org.bukkit.map.MapPalette
import org.bukkit.map.MapRenderer
import org.bukkit.map.MapView
import uk.ewancroft.chronicler.news.NewspaperTypesetter
import uk.ewancroft.chronicler.news.PrintedIssue
import uk.ewancroft.chronicler.util.quarantineCorrupt
import uk.ewancroft.chronicler.util.writeAtomically
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

@Serializable
data class Newsstand(val id: String, val world: String, val cols: Int, val rows: Int, val maps: List<Int>)

/**
 * Walls of item frames showing the current front page as map art. Map
 * renderers are not saved by the game, so they are reattached on enable from
 * newsstands.json; tiles are converted to map colours once per issue.
 */
class Newsstands(
    private val file: Path,
    private val logger: Logger,
    private val openReader: (Player) -> Unit,
) : Listener {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val stands = mutableListOf<Newsstand>()
    private val renderers = ConcurrentHashMap<Int, TileRenderer>()

    /** The current front page; tiles are cut from it for each stand's size. */
    @Volatile
    private var front: BufferedImage? = null

    fun load() {
        if (Files.exists(file)) {
            try {
                stands += json.decodeFromString<List<Newsstand>>(Files.readString(file))
            } catch (e: Exception) {
                file.quarantineCorrupt(logger, e)
            }
        }
        stands.forEach(::attach)
        if (stands.isNotEmpty()) logger.info("Restored ${stands.size} newsstand(s).")
    }

    private fun save() = file.writeAtomically(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Newsstand.serializer()), stands.toList()))

    /** Called on the printing thread when a new issue is typeset. */
    fun update(printed: PrintedIssue) {
        front = printed.pages.firstOrNull()
        stands.forEach(::paint)
    }

    fun create(player: Player, cols: Int, rows: Int): String? {
        val target = player.getTargetEntity(8) as? ItemFrame ?: return "Look at an item frame on a wall: it becomes the top-left corner."
        return create(target.location.block, target.facing, cols, rows)
    }

    /**
     * Places a newsstand whose top-left frame hangs in [origin], facing [facing]
     * (the direction readers look from). Usable from the console and command blocks.
     */
    fun create(origin: org.bukkit.block.Block, facing: BlockFace, cols: Int, rows: Int): String? {
        if (cols !in 1..8 || rows !in 1..8) return "Newsstands can be 1-8 frames wide and tall."
        val right = rightOf(facing) ?: return "Newsstands go on walls, not floors or ceilings."
        val frames = mutableListOf<ItemFrame>()
        for (r in 0 until rows) for (c in 0 until cols) {
            val block = origin.getRelative(right, c).getRelative(BlockFace.DOWN, r)
            val existing = block.world.getNearbyEntitiesByType(ItemFrame::class.java, block.location.add(0.5, 0.5, 0.5), 0.6)
                .firstOrNull { it.facing == facing }
            val frame = existing ?: run {
                val wall = block.getRelative(facing.oppositeFace)
                if (!wall.type.isSolid || !block.isPassable) return "No room for a frame at ${block.x}, ${block.y}, ${block.z}: it needs a solid wall behind open space."
                block.world.spawn(block.location, ItemFrame::class.java) { it.setFacingDirection(facing, true) }
            }
            if (frame.item.type != Material.AIR && renderers[mapId(frame.item) ?: -1] == null) {
                return "The frame at ${block.x}, ${block.y}, ${block.z} already holds ${frame.item.type.name.lowercase()}."
            }
            frames += frame
        }
        val maps = frames.map { frame ->
            val view = Bukkit.createMap(frame.world)
            val item = ItemStack(Material.FILLED_MAP)
            item.editMeta(MapMeta::class.java) { it.mapView = view }
            frame.setItem(item, false)
            frame.isFixed = true
            frame.isVisible = false
            view.id
        }
        val stand = Newsstand(UUID.randomUUID().toString().substring(0, 8), origin.world.name, cols, rows, maps)
        stands += stand
        save()
        attach(stand)
        return null
    }

    fun remove(player: Player): String? {
        val target = player.getTargetEntity(8) as? ItemFrame ?: return "Look at one of the newsstand's frames."
        val id = mapId(target.item) ?: return "That frame is not part of a newsstand."
        val stand = stands.firstOrNull { id in it.maps } ?: return "That frame is not part of a newsstand."
        stands -= stand
        save()
        stand.maps.forEach { renderers.remove(it) }
        // Take the maps down; leave the (now empty) frames for the builder to reuse or break.
        target.world.getNearbyEntitiesByType(ItemFrame::class.java, target.location, 12.0)
            .filter { mapId(it.item) in stand.maps }
            .forEach { it.setItem(null); it.isFixed = false; it.isVisible = true }
        return null
    }

    fun list(): List<Newsstand> = stands.toList()

    private fun attach(stand: Newsstand) {
        stand.maps.forEach { id ->
            @Suppress("DEPRECATION")
            val view = Bukkit.getMap(id) ?: return@forEach
            view.renderers.toList().forEach(view::removeRenderer)
            val renderer = TileRenderer()
            view.addRenderer(renderer)
            view.isTrackingPosition = false
            view.isLocked = true
            renderers[id] = renderer
        }
        paint(stand)
    }

    /** Cuts the front page into this stand's tiles, scaled to its width and letterboxed in paper. */
    private fun paint(stand: Newsstand) {
        val page = front ?: return
        val width = stand.cols * 128
        val height = stand.rows * 128
        val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = canvas.createGraphics()
        g.color = NewspaperTypesetter.PAPER
        g.fillRect(0, 0, width, height)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        val scale = minOf(width.toDouble() / page.width, height.toDouble() / page.height)
        val w = (page.width * scale).toInt()
        val h = (page.height * scale).toInt()
        g.drawImage(page, (width - w) / 2, 0, w, h, null)
        g.dispose()
        stand.maps.forEachIndexed { i, id ->
            val tile = canvas.getSubimage((i % stand.cols) * 128, (i / stand.cols) * 128, 128, 128)
            @Suppress("DEPRECATION")
            renderers[id]?.show(MapPalette.imageToBytes(tile))
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEntityEvent) {
        val frame = event.rightClicked as? ItemFrame ?: return
        if (!isNewsstandMap(frame.item)) return
        event.isCancelled = true
        openReader(event.player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val frame = event.entity as? ItemFrame ?: return
        if (isNewsstandMap(frame.item)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onBreak(event: HangingBreakEvent) {
        val frame = event.entity as? ItemFrame ?: return
        if (isNewsstandMap(frame.item) && event.cause != HangingBreakEvent.RemoveCause.ENTITY) event.isCancelled = true
    }

    private fun isNewsstandMap(item: ItemStack?): Boolean = mapId(item)?.let(renderers::containsKey) == true

    private fun mapId(item: ItemStack?): Int? =
        (item?.takeIf { it.type == Material.FILLED_MAP }?.itemMeta as? MapMeta)?.mapView?.id

    private fun rightOf(facing: BlockFace): BlockFace? = when (facing) {
        BlockFace.NORTH -> BlockFace.WEST
        BlockFace.SOUTH -> BlockFace.EAST
        BlockFace.EAST -> BlockFace.NORTH
        BlockFace.WEST -> BlockFace.SOUTH
        else -> null
    }

    /** Draws its tile once per change rather than every tick. */
    private class TileRenderer : MapRenderer(false) {
        @Volatile private var pixels: ByteArray? = null
        @Volatile private var version = 0
        private var drawn = -1

        fun show(bytes: ByteArray) {
            pixels = bytes
            version++
        }

        override fun render(map: MapView, canvas: MapCanvas, player: Player) {
            val current = version
            if (current == drawn) return
            val data = pixels ?: return
            for (i in data.indices) {
                @Suppress("DEPRECATION")
                canvas.setPixel(i % 128, i / 128, data[i])
            }
            drawn = current
        }
    }
}
