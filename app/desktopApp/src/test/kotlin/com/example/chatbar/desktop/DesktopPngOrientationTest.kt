package com.example.chatbar.desktop

import com.example.chatbar.domain.image.ImageMetadataStripper
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.*

class DesktopPngOrientationTest {
    private val colors = intArrayOf(0xfffc0000.toInt(), 0xff00fc00.toInt(), 0xff0000fc.toInt(),
        0xfffcfc00.toInt(), 0xff00fcfc.toInt(), 0xfffc00fc.toInt())
    private val clockwise = intArrayOf(colors[3], colors[0], colors[4], colors[1], colors[5], colors[2])

    @Test fun `PNG rendering orientation is applied before crop and source dimension reporting`() {
        val bytes = png(orientation = 6)
        val original = bytes.copyOf()
        var reported: Pair<Int, Int>? = null
        val decoded = DesktopImageEditing.decode(bytes, onSourceDimensions = { w, h -> reported = w to h })
        assertEquals(2 to 3, reported)
        assertEquals(2, decoded.width); assertEquals(3, decoded.height)
        assertContentEquals(clockwise, decoded.getRGB(0, 0, 2, 3, null, 0, 2))
        assertContentEquals(original, bytes)
    }

    @Test fun `privacy copy preserves visible PNG rotation while removing source metadata and pixel payload bits`() {
        val bytes = png(orientation = 6, hiddenBits = true)
        val original = bytes.copyOf()
        val output = DesktopImageMetadata.privacyCopy(bytes)
        val decoded = ImageIO.read(ByteArrayInputStream(output))
        assertEquals(2, decoded.width); assertEquals(3, decoded.height)
        assertContentEquals(clockwise, decoded.getRGB(0, 0, 2, 3, null, 0, 2))
        assertFalse(output.toString(Charsets.ISO_8859_1).contains("synthetic-private-description"))
        val file = Files.createTempFile("synthetic-png-orientation-", ".png")
        try {
            Files.write(file, output)
            assertEquals(1, ImageMetadataStripper.readOrientation(file.toFile()))
        } finally { Files.deleteIfExists(file) }
        assertContentEquals(original, bytes)
    }

    @Test fun `ordinary PNG without orientation retains its original dimensions and pixels`() {
        val bytes = png(orientation = null)
        val decoded = DesktopImageEditing.decode(bytes)
        assertEquals(3, decoded.width); assertEquals(2, decoded.height)
        assertContentEquals(colors, decoded.getRGB(0, 0, 3, 2, null, 0, 3))
        val copy = ImageIO.read(ByteArrayInputStream(DesktopImageMetadata.privacyCopy(bytes)))
        assertContentEquals(colors, copy.getRGB(0, 0, 3, 2, null, 0, 3))
    }

    private fun png(orientation: Int?, hiddenBits: Boolean = false): ByteArray {
        val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB)
        colors.forEachIndexed { index, color -> image.setRGB(index % 3, index / 3,
            if (hiddenBits) color or 0x00010101 else color) }
        var bytes = ByteArrayOutputStream().use { ImageIO.write(image, "png", it); it.toByteArray() }
        if (orientation != null) bytes = chunk(bytes, "eXIf", ImageMetadataStripper.buildMinimalTiffOrientation(orientation))
        return chunk(bytes, "tEXt", "Description\u0000synthetic-private-description".toByteArray(Charsets.ISO_8859_1))
    }

    private fun chunk(png: ByteArray, type: String, payload: ByteArray): ByteArray {
        val name = type.toByteArray(Charsets.US_ASCII)
        val encoded = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(payload.size); output.write(name); output.write(payload)
                val crc = CRC32().apply { update(name); update(payload) }
                output.writeInt(crc.value.toInt())
            }
            bytes.toByteArray()
        }
        return png.copyOfRange(0, png.size - 12) + encoded + png.copyOfRange(png.size - 12, png.size)
    }
}
