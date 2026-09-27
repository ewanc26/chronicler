package uk.ewancroft.chronicler.news

import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * Builds the Chronicler resource pack: the newspaper item model plus each page
 * of the current issue, sliced into bitmap-font glyphs so a text component can
 * draw the whole page inside a dialog.
 *
 * Client constraints (26.1): glyphs are packed into 256x256 atlas textures, so
 * every glyph must be smaller than that; bitmap glyphs advance by their width
 * plus one pixel; and a provider's ascent may not exceed its height, although
 * it may be negative. Each tile row is its own text line (text wrapping does
 * not honour negative advances, so a single long line gets broken up); a
 * row's ascent cancels the 9px line pitch so rows butt together.
 */
class NewspaperPack(
    /** On-screen (GUI pixel) size of each 128px tile; 42 shows a page 336 GUI px wide. */
    private val tileGuiSize: Int = 42,
    /** Every ink the typesetter draws with; tiles are quantized to ramps from paper to these. */
    inks: List<Color> = NewspaperTypesetter.inks(0x6B3E00),
) {
    private val palette = Palette(inks)

    companion object {
        const val PACK_FORMAT = 84
        const val TILE = 128
        const val NAMESPACE = "chronicler"
        const val ITEM_MODEL_STRING = "chronicler:newspaper"

        /** Private-use code points: tile glyphs count up from TILE_BASE; spaces sit at the top. */
        private const val TILE_BASE = 0xE000
        const val BACKSPACE_ONE = '\uF801'

        /** Vanilla text line pitch in GUI pixels. */
        const val LINE_HEIGHT = 9

        /** Fixed zip timestamps keep the archive, and so its SHA-1, stable for identical content. */
        private const val ZIP_TIME = 315_532_800_000L

        private val STATIC_FILES = listOf(
            "pack.png",
            "assets/chronicler/textures/item/newspaper.png",
        )

        fun packId(issueNumber: Int): UUID = UUID.nameUUIDFromBytes("chronicler-pack-issue-$issueNumber".toByteArray())
    }

    /** Everything a reader needs to draw one page: the font to use and the glyph string. */
    data class PageGlyphs(val font: String, val text: String, val guiWidth: Int, val guiHeight: Int, val lines: Int)

    class Built(val issueNumber: Int, val zip: ByteArray, val sha1: String, val pages: List<PageGlyphs>) {
        val id: UUID get() = packId(issueNumber)
    }

    fun build(issueNumber: Int, pages: List<BufferedImage>): Built {
        val files = sortedMapOf<String, ByteArray>()
        files["pack.mcmeta"] = packMeta().toByteArray()
        files["assets/minecraft/items/written_book.json"] = writtenBookItem().toByteArray()
        files["assets/chronicler/models/item/newspaper.json"] =
            """{"parent":"minecraft:item/generated","textures":{"layer0":"chronicler:item/newspaper"}}""".toByteArray()
        for (path in STATIC_FILES) {
            files[path] = NewspaperPack::class.java.getResourceAsStream("/pack/$path")?.use { it.readBytes() }
                ?: error("missing bundled pack resource $path")
        }

        val glyphs = pages.mapIndexed { index, page -> slicePage(index + 1, page, files) }

        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zos ->
                for ((path, bytes) in files) {
                    zos.putNextEntry(ZipEntry(path).apply { time = ZIP_TIME })
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }.toByteArray()
        val sha1 = MessageDigest.getInstance("SHA-1").digest(zip).joinToString("") { "%02x".format(it) }
        return Built(issueNumber, zip, sha1, glyphs)
    }

    private fun slicePage(pageNumber: Int, page: BufferedImage, files: MutableMap<String, ByteArray>): PageGlyphs {
        require(page.width % TILE == 0 && page.height % TILE == 0) { "page size must be a multiple of $TILE" }
        val cols = page.width / TILE
        val rows = page.height / TILE
        val fontName = "page_$pageNumber"
        val providers = mutableListOf<String>()
        val text = StringBuilder()
        var nextCodePoint = TILE_BASE

        for (r in 0 until rows) {
            // Identical tiles in a row (blank paper, mostly) share one glyph.
            val rowGlyphs = mutableMapOf<Int, Char>()
            for (c in 0 until cols) {
                val tile = page.getSubimage(c * TILE, r * TILE, TILE, TILE)
                val pixels = tile.getRGB(0, 0, TILE, TILE, null, 0, TILE)
                val key = pixels.contentHashCode()
                val glyph = rowGlyphs.getOrPut(key) {
                    val ch = nextCodePoint++.toChar()
                    val file = "font/$fontName/r${r}c$c.png"
                    files["assets/$NAMESPACE/textures/$file"] = indexedPng(tile)
                    // Row r is on text line r (already r * 9px down); drop it the rest of the way.
                    val ascent = 7 - r * (tileGuiSize - LINE_HEIGHT)
                    providers += """{"type":"bitmap","file":"$NAMESPACE:$file","height":$tileGuiSize,"ascent":$ascent,"chars":["${escape(ch)}"]}"""
                    ch
                }
                text.append(glyph)
                // Cancel each glyph's 1px spacing, except after the row's last tile: the
                // dialog re-splits text at the widest line's net width to size itself, and
                // a row whose running width momentarily exceeds that (43px glyph, then -1)
                // would be counted as two lines, doubling the reserved height.
                if (c < cols - 1) text.append(BACKSPACE_ONE)
            }
            if (r < rows - 1) text.append('\n')
        }
        val rowWidth = cols * tileGuiSize
        providers += """{"type":"space","advances":{"${escape(BACKSPACE_ONE)}":-1}}"""
        files["assets/$NAMESPACE/font/$fontName.json"] = """{"providers":[${providers.joinToString(",")}]}""".toByteArray()
        return PageGlyphs("$NAMESPACE:$fontName", text.toString(), rowWidth, rows * tileGuiSize, rows)
    }

    private fun packMeta(): String =
        """{"pack":{"description":"Chronicler: printed newspaper pages","min_format":[$PACK_FORMAT,0],"max_format":$PACK_FORMAT}}"""

    /** Newspapers are written books tagged via custom_model_data; everything else keeps the vanilla model. */
    private fun writtenBookItem(): String = """
        {"model":{"type":"minecraft:select","property":"minecraft:custom_model_data","index":0,
         "cases":[{"when":"$ITEM_MODEL_STRING","model":{"type":"minecraft:model","model":"chronicler:item/newspaper"}}],
         "fallback":{"type":"minecraft:model","model":"minecraft:item/written_book"}}}
    """.trimIndent()

    private fun escape(ch: Char) = "\\u%04x".format(ch.code)

    /**
     * Pages are ink on newsprint, so a palette of ramps from paper to each ink
     * colour keeps antialiasing while shrinking tiles to 8-bit indexed PNGs.
     */
    private fun indexedPng(tile: BufferedImage): ByteArray {
        val indexed = BufferedImage(TILE, TILE, BufferedImage.TYPE_BYTE_INDEXED, palette.model)
        val raster = indexed.raster
        val cache = HashMap<Int, Int>()
        for (y in 0 until TILE) for (x in 0 until TILE) {
            val rgb = tile.getRGB(x, y) and 0xFFFFFF
            val index = cache.getOrPut(rgb) { nearest(rgb, palette.colors) }
            raster.setSample(x, y, 0, index)
        }
        return ByteArrayOutputStream().also { ImageIO.write(indexed, "png", it) }.toByteArray()
    }

    private fun nearest(rgb: Int, palette: IntArray): Int {
        val r = rgb shr 16 and 0xFF; val g = rgb shr 8 and 0xFF; val b = rgb and 0xFF
        var best = 0; var bestDistance = Int.MAX_VALUE
        for (i in palette.indices) {
            val p = palette[i]
            val dr = r - (p shr 16 and 0xFF); val dg = g - (p shr 8 and 0xFF); val db = b - (p and 0xFF)
            val d = dr * dr * 3 + dg * dg * 4 + db * db * 2
            if (d < bestDistance) { bestDistance = d; best = i }
        }
        return best
    }

    private class Palette(inks: List<Color>) {
        val colors: IntArray = buildList {
            val paper = NewspaperTypesetter.PAPER
            add(paper.rgb and 0xFFFFFF)
            val steps = (255 / inks.size.coerceAtLeast(1)).coerceAtMost(63)
            for (ink in inks) for (step in 1..steps) {
                val t = step / steps.toFloat()
                val r = (paper.red + (ink.red - paper.red) * t).toInt()
                val g = (paper.green + (ink.green - paper.green) * t).toInt()
                val b = (paper.blue + (ink.blue - paper.blue) * t).toInt()
                add(r shl 16 or (g shl 8) or b)
            }
        }.distinct().take(256).toIntArray()
        val model: IndexColorModel = IndexColorModel(
            8, colors.size,
            ByteArray(colors.size) { (colors[it] shr 16).toByte() },
            ByteArray(colors.size) { (colors[it] shr 8).toByte() },
            ByteArray(colors.size) { colors[it].toByte() },
        )
    }
}
