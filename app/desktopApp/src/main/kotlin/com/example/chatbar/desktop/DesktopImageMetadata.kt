package com.example.chatbar.desktop

import com.example.chatbar.domain.image.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Native pixel access only; precedence, parsing and bounds belong to shared official policy. */
internal object DesktopImageMetadata {
    fun read(path: String) = NovelAiPngMetadataReader.read(path, ::alpha)
    fun readStudio(path: String) = NovelAiPngMetadataReader.readStudio(path, ::alpha)
    fun readEnhance(path: String) = NovelAiPngMetadataReader.readEnhance(path, ::alpha)

    private fun alpha(path: String): String? {
        val file = Path.of(path)
        require(Files.size(file) in 1..100L * 1024 * 1024)
        // Read the original full, non-premultiplied raster; no orientation or thumbnail resampling.
        ImageIO.createImageInputStream(file.toFile()).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return webpAlpha(file)
            val reader = readers.next()
            try {
                reader.input = input
                if (reader.formatName.lowercase() !in setOf("png", "webp")) return null
                val width = reader.getWidth(0); val height = reader.getHeight(0)
                require(width > 0 && height > 0 && width.toLong() * height <= PrivacyPngEncoder.MAX_PIXELS)
                val raster = reader.read(0)
                return StealthAlphaMetadata.decode(width, height) { x, y -> raster.getRGB(x, y) ushr 24 }
            } finally { reader.dispose() }
        }
    }

    private fun webpAlpha(file: Path): String? {
        val bytes = Files.readAllBytes(file)
        if (bytes.size < 12 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(bytes, 8, 4, Charsets.US_ASCII) != "WEBP") return null
        // ImageIO does not bundle a WebP reader. Skia decodes original-size alpha losslessly;
        // the intermediate PNG changes neither pixel order nor alpha bits.
        return org.jetbrains.skia.Image.makeFromEncoded(bytes).use { image ->
            require(image.width > 0 && image.height > 0 && image.width.toLong() * image.height <= PrivacyPngEncoder.MAX_PIXELS)
            requireNotNull(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)).use { png ->
                val raster = requireNotNull(ImageIO.read(ByteArrayInputStream(png.bytes)))
                StealthAlphaMetadata.decode(raster.width, raster.height) { x, y -> raster.getRGB(x, y) ushr 24 }
            }
        }
    }

    fun privacyCopy(source: ByteArray): ByteArray {
        DesktopImageEditing.requireStatic(source)
        val raster = DesktopImageEditing.decode(source)
        return ByteArrayOutputStream().use { output ->
            PrivacyPngEncoder.write(output, raster.width, raster.height) { y, row ->
                raster.getRGB(0, y, raster.width, 1, row, 0, raster.width)
            }
            output.toByteArray()
        }
    }
}
