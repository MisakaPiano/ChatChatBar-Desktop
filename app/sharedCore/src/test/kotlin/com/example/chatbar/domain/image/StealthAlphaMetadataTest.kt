package com.example.chatbar.domain.image

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class StealthAlphaMetadataTest {
    @Test fun `compressed and plain alpha payload use column order and UTF8`() {
        val text = """{"Comment":"中文 prompt","Source":"NovelAI"}"""
        for (compressed in listOf(false, true)) {
            assertEquals(text, decode(message(text, compressed)))
        }
    }

    @Test fun `absent header and tiny images are not metadata`() {
        assertNull(StealthAlphaMetadata.decode(1, 1) { _, _ -> 255 })
        assertNull(decode(ByteArray(18)))
    }

    @Test fun `invalid lengths truncated gzip and decompression bomb fail bounded`() {
        for (bits in listOf(-1, 1, 0, 100_000)) {
            val data = message("{}", false)
            for (i in 0..3) data[15 + i] = (bits ushr (24 - i * 8)).toByte()
            assertTrue(runCatching { decode(data) }.isFailure)
        }
        val broken = message("{}", true).apply { this[19] = 0 }
        assertTrue(runCatching { decode(broken) }.isFailure)
        val bomb = message("a".repeat(StealthAlphaMetadata.MAX_METADATA_BYTES + 1), true)
        assertTrue(runCatching { decode(bomb, 400, 400) }.isFailure)
    }

    private fun message(text: String, compressed: Boolean): ByteArray {
        val raw = text.toByteArray()
        val payload = if (compressed) ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(raw) }
        }.toByteArray() else raw
        return ByteArrayOutputStream().apply {
            write((if (compressed) "stealth_pngcomp" else "stealth_pnginfo").toByteArray())
            DataOutputStream(this).writeInt(payload.size * 8)
            write(payload)
        }.toByteArray()
    }

    private fun decode(data: ByteArray, width: Int = 80, height: Int = 64): String? =
        StealthAlphaMetadata.decode(width, height) { x, y ->
            val bit = x * height + y
            254 or if (bit / 8 < data.size) (data[bit / 8].toInt() ushr (7 - bit % 8) and 1) else 1
        }
}
