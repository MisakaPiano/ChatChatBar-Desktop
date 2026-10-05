package com.example.chatbar.domain.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

@RunWith(AndroidJUnit4::class)
class ImagePrivacyExporterTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun publicExportRemovesTextAndAlphaStealthWithoutTouchingSource() {
        val original = privacyPngFixture()
        val source = folder.newFile("source.png").apply { writeBytes(original) }
        val output = ImageMetadataStripper.stripToCopy(source, folder.newFolder("output"))
        assertArrayEquals(original, source.readBytes())
        assertEquals("png", output.extension)
        assertFalse(output.readBytes().toString(Charsets.ISO_8859_1).contains("private prompt"))
        assertCleanPixels(output.path)
    }

    @Test
    fun editedBitmapAndLosslessWebPUseSamePixelSanitizer() {
        val original = folder.newFile("source.png").apply { writeBytes(privacyPngFixture()) }
        val bitmap = BitmapFactory.decodeFile(original.path)
        try {
            bitmap.setHasAlpha(true)
            val output = ImagePrivacyExporter.writeCopy(bitmap, folder.newFolder("edited"))
            assertCleanPixels(output.path)
            val webp = folder.newFile("source.webp")
            webp.outputStream().use {
                @Suppress("DEPRECATION")
                assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP, 100, it))
            }
            val webpResult = ImageMetadataStripper.stripToCopy(webp, folder.newFolder("webp"))
            assertCleanPixels(webpResult.path)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun invalidInputDoesNotLeaveAnOutput() {
        val source = folder.newFile("broken.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val directory = folder.newFolder("output")
        assertTrue(runCatching { ImageMetadataStripper.stripToCopy(source, directory) }.isFailure)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    private fun assertCleanPixels(path: String) {
        val bitmap = checkNotNull(BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inPremultiplied = false }))
        try {
            assertEquals(64, bitmap.width)
            assertEquals(64, bitmap.height)
            val pixels = IntArray(64 * 64)
            bitmap.getPixels(pixels, 0, 64, 0, 0, 64, 64)
            assertTrue(pixels.all { it ushr 24 == 255 })
            assertTrue(pixels.all { it and 0x00010101 == 0 })
        } finally {
            bitmap.recycle()
        }
    }

}

// Raw PNG fixture avoids Android premultiplication altering the embedded source payload.
internal fun privacyPngFixture(): ByteArray {
    val data = "stealth_pnginfo".toByteArray() + byteArrayOf(0, 0, 0, 8) + byteArrayOf(65)
    val compressed = ByteArrayOutputStream().apply {
        DeflaterOutputStream(this).use { zip ->
            repeat(64) { y ->
                zip.write(0)
                repeat(64) { x ->
                    val bit = x * 64 + y
                    val value = if (bit < data.size * 8) data[bit / 8].toInt() ushr (7 - bit % 8) and 1 else 1
                    zip.write(byteArrayOf(128.toByte(), 144.toByte(), 160.toByte(), (254 + value).toByte()))
                }
            }
        }
    }.toByteArray()
    return ByteArrayOutputStream().apply {
        write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        fun chunk(type: String, payload: ByteArray) {
            val name = type.toByteArray()
            val out = DataOutputStream(this)
            out.writeInt(payload.size)
            out.write(name)
            out.write(payload)
            out.writeInt(CRC32().apply { update(name); update(payload) }.value.toInt())
        }
        chunk("IHDR", byteArrayOf(0, 0, 0, 64, 0, 0, 0, 64, 8, 6, 0, 0, 0))
        chunk("tEXt", "Comment\u0000private prompt".toByteArray())
        chunk("IDAT", compressed)
        chunk("IEND", byteArrayOf())
    }.toByteArray()
}
