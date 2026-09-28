package uk.ewancroft.chronicler.news

import java.awt.image.BufferedImage
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.logging.Logger
import javax.imageio.ImageIO

/**
 * Player faces for printed pages, cropped from skins (face plus hat layer)
 * and cached on disk. Blocking network I/O: only call from async tasks.
 */
class Portraits(
    private val cacheDir: Path,
    private val logger: Logger,
    /** Resolves a player name to their skin texture URL (blocking), or null. */
    private val skinUrl: (String) -> URL?,
    private val maxAge: Duration = Duration.ofDays(7),
    private val download: (URL) -> ByteArray? = ::httpGet,
) {

    fun facesFor(names: Collection<String>): Map<String, BufferedImage> =
        names.distinct().mapNotNull { name -> face(name)?.let { name to it } }.toMap()

    fun face(name: String): BufferedImage? {
        if (!name.matches(Regex("[A-Za-z0-9_.]{1,20}"))) return null
        val file = cacheDir.resolve("${name.lowercase()}.png")
        try {
            if (Files.exists(file) && Files.getLastModifiedTime(file).toMillis() > System.currentTimeMillis() - maxAge.toMillis()) {
                return ImageIO.read(file.toFile())
            }
            val skin = skinUrl(name)?.let(download)?.let { ImageIO.read(it.inputStream()) } ?: return cachedOrNull(file)
            val face = cropFace(skin) ?: return cachedOrNull(file)
            Files.createDirectories(cacheDir)
            ImageIO.write(face, "png", file.toFile())
            return face
        } catch (e: Exception) {
            logger.fine("No portrait for $name: ${e.message}")
            return cachedOrNull(file)
        }
    }

    private fun cachedOrNull(file: Path): BufferedImage? =
        if (Files.exists(file)) runCatching { ImageIO.read(file.toFile()) }.getOrNull() else null

    companion object {
        /** Face (8,8) with the hat overlay (40,8) composited on top; works for 64x64 and legacy 64x32 skins. */
        fun cropFace(skin: BufferedImage): BufferedImage? {
            if (skin.width < 64 || skin.height < 32) return null
            val scale = skin.width / 64
            val face = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
            val g = face.createGraphics()
            g.drawImage(skin.getSubimage(8 * scale, 8 * scale, 8 * scale, 8 * scale), 0, 0, 8, 8, null)
            g.drawImage(skin.getSubimage(40 * scale, 8 * scale, 8 * scale, 8 * scale), 0, 0, 8, 8, null)
            g.dispose()
            return face
        }

        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

        private fun httpGet(url: URL): ByteArray? {
            // Skin textures are only ever served from Mojang's texture host.
            if (url.host != "textures.minecraft.net") return null
            val request = HttpRequest.newBuilder(URI.create(url.toString().replace("http://", "https://")))
                .timeout(Duration.ofSeconds(10)).GET().build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            return response.body().takeIf { response.statusCode() == 200 }
        }
    }
}
