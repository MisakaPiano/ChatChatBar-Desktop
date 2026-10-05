package com.example.chatbar.domain.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream

class PrivacyPngEncoderTest {
    @Test
    fun removesFullAlphaAndRgbStealthPayloadsEvenWithDamagedHeaders() {
        for (signature in listOf("stealth_pnginfo", "stealth_pngcomp", "stealth_rgbinfo", "stealth_rgbcomp")) {
            for (damagedHeader in listOf(false, true)) {
                val secret = "{\"Comment\":\"private prompt and seed 1234\"}".toByteArray()
                val payload = if (signature.endsWith("comp")) ByteArrayOutputStream().apply {
                    GZIPOutputStream(this).use { it.write(secret) }
                }.toByteArray() else secret
                val data = ByteArrayOutputStream().apply {
                    write(signature.toByteArray())
                    java.io.DataOutputStream(this).writeInt(payload.size * 8)
                    write(payload)
                }.toByteArray()
                if (damagedHeader) data[0] = 0
                val pixels = IntArray(64 * 64) { 0xff8090a0.toInt() }
                val alpha = signature.startsWith("stealth_png")
                embed(pixels, 64, data, alpha)
                assertArrayEquals(data, extract(pixels, 64, data.size, alpha))

                val decoded = decode(encode(pixels, 64, 64))
                // Assert erasure of the entire carrier plane, not merely failure to recognize a magic string.
                assertTrue(extract(decoded.pixels, 64, data.size, alpha).all {
                    it == if (alpha) 0xff.toByte() else 0.toByte()
                })
                assertFalse(decoded.chunks.any { it in setOf("tEXt", "zTXt", "iTXt", "eXIf") })
            }
        }
    }

    @Test
    fun erasesLsbRegardlessOfValueAndKeepsTransparencyEndpoints() {
        for (alpha in 0..255) {
            val pixel = (alpha shl 24) or 0x0095b7ff
            assertEquals(PrivacyPngEncoder.sanitizePixel(pixel), PrivacyPngEncoder.sanitizePixel(pixel xor 0x01010101))
        }
        assertEquals(0, PrivacyPngEncoder.sanitizePixel(0x00123456))
        assertEquals(255, PrivacyPngEncoder.sanitizePixel(0xff123456.toInt()) ushr 24)
        assertEquals(128, PrivacyPngEncoder.sanitizePixel(0x81123456.toInt()) ushr 24)
    }

    @Test
    fun preservesDimensionsAndOnlyMinimalOrientationWhileSourceStaysUnchanged() {
        val pixels = intArrayOf(0xff123457.toInt(), 0x80030405.toInt(), 0x00abcdef)
        val original = pixels.copyOf()
        val decoded = decode(encode(pixels, 3, 1, orientation = 6))
        assertEquals(3, decoded.width)
        assertEquals(1, decoded.height)
        assertEquals(listOf("IHDR", "sRGB", "eXIf", "IDAT", "IEND"), decoded.chunks)
        assertArrayEquals(original, pixels)
        assertArrayEquals(pixels.map(PrivacyPngEncoder::sanitizePixel).toIntArray(), decoded.pixels)
    }

    @Test
    fun chunkedOutputRoundTripsAndRejectsInvalidDimensions() {
        val random = java.util.Random(42)
        val pixels = IntArray(256 * 256) { random.nextInt() }
        val decoded = decode(encode(pixels, 256, 256))
        assertTrue(decoded.chunks.count { it == "IDAT" } > 1)
        assertArrayEquals(pixels.map(PrivacyPngEncoder::sanitizePixel).toIntArray(), decoded.pixels)
        assertTrue(runCatching { encode(intArrayOf(), Int.MAX_VALUE, 2) }.isFailure)
    }

    private fun encode(pixels: IntArray, width: Int, height: Int, orientation: Int = 1): ByteArray =
        ByteArrayOutputStream().apply {
            PrivacyPngEncoder.write(this, width, height, orientation) { y, row ->
                pixels.copyInto(row, 0, y * width, (y + 1) * width)
            }
        }.toByteArray()

    // Independent column-major, MSB-first extractor matching the public stealth protocol.
    private fun embed(pixels: IntArray, width: Int, data: ByteArray, alpha: Boolean) {
        repeat(data.size * 8) { bit ->
            val channels = if (alpha) 1 else 3
            val ordinal = bit / channels
            val height = pixels.size / width
            val index = (ordinal % height) * width + ordinal / height
            val shift = if (alpha) 24 else 16 - bit % 3 * 8
            val value = data[bit / 8].toInt() ushr (7 - bit % 8) and 1
            pixels[index] = (pixels[index] and (1 shl shift).inv()) or (value shl shift)
        }
    }

    private fun extract(pixels: IntArray, width: Int, size: Int, alpha: Boolean): ByteArray =
        ByteArray(size).apply {
            repeat(size * 8) { bit ->
                val ordinal = bit / if (alpha) 1 else 3
                val height = pixels.size / width
                val index = (ordinal % height) * width + ordinal / height
                val shift = if (alpha) 24 else 16 - bit % 3 * 8
                val value = pixels[index] ushr shift and 1
                this[bit / 8] = (this[bit / 8].toInt() or (value shl (7 - bit % 8))).toByte()
            }
        }

    private data class Decoded(val width: Int, val height: Int, val pixels: IntArray, val chunks: List<String>)

    private fun decode(bytes: ByteArray): Decoded {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val signature = ByteArray(8).also(input::readFully)
        assertArrayEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10), signature)
        var width = 0
        var height = 0
        val names = mutableListOf<String>()
        val compressed = ByteArrayOutputStream()
        while (input.available() > 0) {
            val size = input.readInt()
            val name = ByteArray(4).also(input::readFully)
            val payload = ByteArray(size).also(input::readFully)
            assertEquals(CRC32().apply { update(name); update(payload) }.value.toInt(), input.readInt())
            val type = name.toString(Charsets.US_ASCII)
            names += type
            when (type) {
                "IHDR" -> DataInputStream(ByteArrayInputStream(payload)).use {
                    width = it.readInt()
                    height = it.readInt()
                    assertEquals(8, it.readUnsignedByte())
                    assertEquals(6, it.readUnsignedByte())
                }
                "eXIf" -> {
                    assertEquals(26, payload.size)
                    assertEquals(6, payload[18].toInt())
                }
                "IDAT" -> compressed.write(payload)
            }
        }
        val raw = DataInputStream(InflaterInputStream(ByteArrayInputStream(compressed.toByteArray())))
        val pixels = IntArray(width * height)
        repeat(height) { y ->
            assertEquals(0, raw.readUnsignedByte())
            repeat(width) { x ->
                val r = raw.readUnsignedByte()
                val g = raw.readUnsignedByte()
                val b = raw.readUnsignedByte()
                val a = raw.readUnsignedByte()
                pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        assertEquals(-1, raw.read())
        return Decoded(width, height, pixels, names)
    }
}
