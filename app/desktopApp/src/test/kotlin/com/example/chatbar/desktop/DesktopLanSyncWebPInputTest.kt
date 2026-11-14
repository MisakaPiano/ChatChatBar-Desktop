package com.example.chatbar.desktop

import com.example.chatbar.domain.image.PrivacyPngEncoder
import com.example.chatbar.domain.lanSync.LanSyncRecord
import com.example.chatbar.interop.LanSyncLargePngFixture
import kotlinx.serialization.json.buildJsonObject
import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DesktopLanSyncWebPInputTest {
    private val row = LanSyncRecord("character_cards", "synthetic", buildJsonObject {})
    private fun write(path: Path, actual: Int, declared: Int) {
        val png = ByteArrayOutputStream().apply { PrivacyPngEncoder.write(this, 16, 12) { _, pixels -> pixels.fill(0xff326698.toInt()) } }.toByteArray()
        val webp = org.jetbrains.skia.Image.makeFromEncoded(png).use { image ->
            requireNotNull(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.WEBP, 100)).use { it.bytes }
        }
        val profile = ICC_Profile.getInstance(ColorSpace.CS_sRGB).data
        ByteBuffer.wrap(profile).putInt(declared)
        val body = ByteArrayOutputStream()
        var position = 12
        while (position < webp.size) {
            val length = ByteBuffer.wrap(webp, position + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val next = position + 8 + length + (length and 1)
            val type = String(webp, position, 4, Charsets.US_ASCII)
            if (type !in setOf("VP8X", "ICCP", "EXIF", "XMP ")) body.write(webp, position, next - position)
            position = next
        }
        val encoded = body.toByteArray()
        Files.newOutputStream(path).buffered().use { output ->
            fun uint(value: Int) { output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
            output.write("RIFF".toByteArray()); uint(4 + 18 + 8 + actual + (actual and 1) + encoded.size); output.write("WEBP".toByteArray())
            output.write("VP8X".toByteArray()); uint(10); output.write(byteArrayOf(0x20, 0, 0, 0, 15, 0, 0, 11, 0, 0))
            output.write("ICCP".toByteArray()); uint(actual)
            output.write(profile, 0, minOf(actual, profile.size))
            val buffer = ByteArray(64 * 1024); var remaining = actual - minOf(actual, profile.size)
            while (remaining > 0) { val count = minOf(remaining, buffer.size); output.write(buffer, 0, count); remaining -= count }
            if (actual and 1 != 0) output.write(0)
            output.write(encoded)
        }
    }
    @Test fun `forged ICC allocation and truncated RIFF are refused before a reader sees the header`() {
        val root = Files.createTempDirectory("lan-webp-forged-").toRealPath()
        try {
            val image = root.resolve("forged.webp"); write(image, 128, 0x7ffffff0)
            assertEquals("SYNC_IMAGE_INVALID", assertFailsWith<IllegalArgumentException> { DesktopLanSyncWebPInput.open(image, false).close() }.message)
            assertFailsWith<IllegalArgumentException> { validateDesktopLanSyncResource("images", image, row) }
            write(image, 128, 128)
            java.io.RandomAccessFile(image.toFile(), "rw").use { it.setLength(it.length() - 1) }
            assertFailsWith<IllegalArgumentException> { DesktopLanSyncWebPInput.open(image, true).close() }
        } finally { check(root.toFile().deleteRecursively()) }
    }
    @Test fun `ordinary bounded ICC renders and oversized real metadata only holds preview while validation retains the original`() {
        val root = Files.createTempDirectory("lan-webp-metadata-").toRealPath()
        try {
            val image = root.resolve("profile.webp")
            val ordinarySize = ICC_Profile.getInstance(ColorSpace.CS_sRGB).data.size
            write(image, ordinarySize, ordinarySize)
            DesktopLanSyncRaster.decode(image, row, 256).let { try { assertEquals(16, it.width); assertEquals(12, it.height) } finally { it.flush() } }
            write(image, 17 * 1024 * 1024, 17 * 1024 * 1024)
            val hash = LanSyncLargePngFixture.hash(image)
            validateDesktopLanSyncResource("images", image, row)
            assertEquals("SYNC_IMAGE_METADATA_WORKING_BUDGET", assertFailsWith<IllegalArgumentException> { DesktopLanSyncRaster.preview(image, row) }.message)
            assertEquals(hash, LanSyncLargePngFixture.hash(image))
        } finally { check(root.toFile().deleteRecursively()) }
    }
}
