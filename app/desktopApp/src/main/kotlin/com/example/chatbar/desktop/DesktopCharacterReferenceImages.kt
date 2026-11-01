package com.example.chatbar.desktop

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Selected images belong only to the open AI operation, never to the character's avatar. */
internal data class DesktopCharacterReferenceImage(
    val id: String, val name: String, val base64: String, val preview: ByteArray,
)

internal fun readCharacterReferenceImage(path: Path): DesktopCharacterReferenceImage {
    val bytes = Files.newInputStream(path).use { it.readNBytes(DesktopImageEditing.MAX_BYTES + 1) }
    val image = DesktopImageEditing.decode(bytes)
    val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
    rgb.createGraphics().let { g ->
        try { g.color = Color.WHITE; g.fillRect(0, 0, rgb.width, rgb.height); g.drawImage(image, 0, 0, null) }
        finally { g.dispose() }
    }
    val encoded = ByteArrayOutputStream().use { output ->
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            ImageIO.createImageOutputStream(output).use { stream ->
                writer.output = stream
                val params = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.8f
                }
                writer.write(null, javax.imageio.IIOImage(rgb, null, null), params)
            }
            output.toByteArray()
        } finally { writer.dispose() }
    }
    val preview = DesktopImageEditing.png(DesktopImageEditing.decode(encoded, longestSide = 96))
    return DesktopCharacterReferenceImage(UUID.randomUUID().toString(), path.fileName.toString(),
        Base64.getEncoder().encodeToString(encoded), preview)
}
