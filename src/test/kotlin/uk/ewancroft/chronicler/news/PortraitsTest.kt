package uk.ewancroft.chronicler.news

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Path
import java.util.logging.Logger
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PortraitsTest {
    @TempDir lateinit var dir: Path

    /** A 64x64 skin whose face is red and whose hat layer paints the top row blue. */
    private fun skin(): ByteArray {
        val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color.RED; g.fillRect(8, 8, 8, 8)
        g.color = Color.BLUE; g.fillRect(40, 8, 8, 1)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    @Test
    fun `crops the face with its hat layer and caches it`() {
        var downloads = 0
        val portraits = Portraits(dir, Logger.getAnonymousLogger(), skinUrl = { URI("https://textures.minecraft.net/texture/x").toURL() },
            download = { downloads++; skin() })
        val face = assertNotNull(portraits.face("Steve"))
        assertEquals(8, face.width)
        assertEquals(Color.BLUE.rgb, face.getRGB(3, 0))
        assertEquals(Color.RED.rgb, face.getRGB(3, 4))
        portraits.face("Steve")
        assertEquals(1, downloads, "second lookup should come from the cache")
    }

    @Test
    fun `unknown players and odd names get no portrait`() {
        val portraits = Portraits(dir, Logger.getAnonymousLogger(), skinUrl = { null })
        assertNull(portraits.face("Nobody"))
        assertNull(portraits.face("../../etc/passwd"))
    }
}
