package uk.ewancroft.chronicler.news

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import uk.ewancroft.chronicler.config.NewspaperConfig
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewspaperPackTest {

    private val config = NewspaperConfig(title = "The Weekly Chronicle", author = "Chronicler", storiesPerSection = 5, showStatistics = true)
    private val pages by lazy {
        val json = NewspaperPackTest::class.java.getResource("/sample-issue.json")!!.readText()
        NewspaperTypesetter(config).typeset(Json { ignoreUnknownKeys = true }.decodeFromString(json))
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) { val e = zis.nextEntry ?: break; put(e.name, zis.readBytes()) }
        }
    }

    @Test
    fun `pack contains metadata, item model override and one font per page`() {
        val built = NewspaperPack().build(12, pages)
        val files = unzip(built.zip)
        File("build/newspaper-preview").mkdirs()
        File("build/newspaper-preview/chronicler-pack.zip").writeBytes(built.zip)

        val meta = Json.parseToJsonElement(files.getValue("pack.mcmeta").decodeToString()).jsonObject["pack"]!!.jsonObject
        assertEquals(NewspaperPack.PACK_FORMAT, meta["max_format"]!!.jsonPrimitive.int)
        assertTrue("assets/minecraft/items/written_book.json" in files)
        assertTrue("assets/chronicler/textures/item/newspaper.png" in files)
        assertEquals(pages.size, built.pages.size)
        built.pages.forEachIndexed { i, page ->
            assertEquals("chronicler:page_${i + 1}", page.font)
            assertTrue("assets/chronicler/font/page_${i + 1}.json" in files)
        }
    }

    @Test
    fun `every glyph texture fits the client's font atlas`() {
        val files = unzip(NewspaperPack().build(1, pages).zip)
        val tiles = files.filterKeys { it.startsWith("assets/chronicler/textures/font/") }
        assertTrue(tiles.isNotEmpty())
        tiles.values.forEach { bytes ->
            val image = ImageIO.read(ByteArrayInputStream(bytes))
            assertTrue(image.width < 256 && image.height < 256)
        }
    }

    @Test
    fun `identical content produces an identical pack hash`() {
        assertEquals(NewspaperPack().build(3, pages).sha1, NewspaperPack().build(3, pages).sha1)
    }

    /** Lays the glyph string out with the client's rules and checks it rebuilds the page. */
    @Test
    fun `glyph layout reassembles the original page`() {
        val tileGui = 42
        val built = NewspaperPack(tileGui).build(1, pages)
        val files = unzip(built.zip)
        val font = Json.parseToJsonElement(files.getValue("assets/chronicler/font/page_1.json").decodeToString()).jsonObject
        val glyphs = mutableMapOf<Char, Pair<String, Int>>()
        val spaces = mutableMapOf<Char, Int>()
        font["providers"]!!.jsonArray.map { it.jsonObject }.forEach { p: JsonObject ->
            when (p["type"]!!.jsonPrimitive.content) {
                "bitmap" -> {
                    assertTrue(p["ascent"]!!.jsonPrimitive.int <= p["height"]!!.jsonPrimitive.int)
                    val ch = p["chars"]!!.jsonArray[0].jsonPrimitive.content.single()
                    glyphs[ch] = p["file"]!!.jsonPrimitive.content.removePrefix("chronicler:") to p["ascent"]!!.jsonPrimitive.int
                }
                "space" -> p["advances"]!!.jsonObject.forEach { (k, v) -> spaces[k.single()] = v.jsonPrimitive.int }
            }
        }
        // Client: bitmap advance = round(width * height / textureHeight) + 1; draw top = baseline - ascent.
        var x = 0
        val placed = mutableListOf<Triple<String, Int, Int>>()
        for (ch in built.pages[0].text) {
            val glyph = glyphs[ch]
            if (glyph != null) {
                placed += Triple(glyph.first, x, 7 - glyph.second)
                x += tileGui + 1
            } else x += spaces.getValue(ch)
        }
        val original = pages[0]
        val cols = original.width / NewspaperPack.TILE
        assertEquals(original.width / NewspaperPack.TILE * (original.height / NewspaperPack.TILE), placed.size)
        placed.forEach { (file, gx, gy) ->
            val col = gx / tileGui; val row = gy / tileGui
            assertEquals(0, gx % tileGui); assertEquals(0, gy % tileGui)
            assertTrue(col < cols)
            val tile = ImageIO.read(ByteArrayInputStream(files.getValue("assets/chronicler/textures/$file")))
            val source = original.getSubimage(col * 128, row * 128, 128, 128)
            for (p in 0 until 128 * 128 step 97) {
                val a = tile.getRGB(p % 128, p / 128); val b = source.getRGB(p % 128, p / 128)
                val diff = listOf(16, 8, 0).maxOf { abs((a shr it and 0xFF) - (b shr it and 0xFF)) }
                assertTrue(diff <= 12, "tile r${row}c$col differs by $diff")
            }
        }
    }
}
