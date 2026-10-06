package com.example.chatbar.domain.image

import java.io.ByteArrayOutputStream

object NovelAiInpaintPixelPolicy {
    /** Mirrors NovelAI web: destination-out(base, mask), then lighter(generated, mask). */
    fun composeOfficialPixel(base: Int, generated: Int, maskWeight: Int): Int {
        val baseAlpha = base ushr 24 and 0xff
        val generatedAlpha = generated ushr 24 and 0xff
        val mask = maskWeight.coerceIn(0, 255)
        if (mask <= 0) return base
        val clearedBaseAlpha = (baseAlpha * (255 - mask) + 127) / 255
        val appliedGeneratedAlpha = (generatedAlpha * mask + 127) / 255
        val outputAlpha = (clearedBaseAlpha + appliedGeneratedAlpha).coerceAtMost(255)
        if (outputAlpha <= 0) return 0
        fun channel(pixel: Int, shift: Int): Int = pixel ushr shift and 0xff
        fun composed(shift: Int): Int {
            val premultiplied = (
                channel(base, shift) * clearedBaseAlpha +
                    channel(generated, shift) * appliedGeneratedAlpha +
                    127
                ) / 255
            return ((premultiplied * 255 + outputAlpha / 2) / outputAlpha).coerceIn(0, 255)
        }
        return (outputAlpha shl 24) or
            (composed(16) shl 16) or
            (composed(8) shl 8) or
            composed(0)
    }

    fun preserveTextChunks(encoded: ByteArray, source: ByteArray): ByteArray {
        val chunks = rawTextChunks(source)
        if (chunks.isEmpty()) return encoded
        val iend = findChunkOffset(encoded, "IEND") ?: return encoded
        return ByteArrayOutputStream(encoded.size + chunks.sumOf(ByteArray::size)).use { output ->
            output.write(encoded, 0, iend)
            chunks.forEach(output::write)
            output.write(encoded, iend, encoded.size - iend)
            output.toByteArray()
        }
    }

    private fun rawTextChunks(bytes: ByteArray): List<ByteArray> {
        if (!isPng(bytes)) return emptyList()
        val result = mutableListOf<ByteArray>()
        var offset = PNG_SIGNATURE_SIZE
        while (offset + CHUNK_OVERHEAD <= bytes.size) {
            val length = readInt(bytes, offset)
            if (length < 0 || offset + CHUNK_OVERHEAD.toLong() + length > bytes.size) break
            val end = offset + CHUNK_OVERHEAD + length
            val type = bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
            if (type in TEXT_CHUNK_TYPES) result += bytes.copyOfRange(offset, end)
            offset = end
            if (type == "IEND") break
        }
        return result
    }

    private fun findChunkOffset(bytes: ByteArray, target: String): Int? {
        if (!isPng(bytes)) return null
        var offset = PNG_SIGNATURE_SIZE
        while (offset + CHUNK_OVERHEAD <= bytes.size) {
            val length = readInt(bytes, offset)
            if (length < 0 || offset + CHUNK_OVERHEAD.toLong() + length > bytes.size) return null
            val type = bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
            if (type == target) return offset
            offset += CHUNK_OVERHEAD + length
        }
        return null
    }

    private fun isPng(bytes: ByteArray): Boolean = bytes.size >= PNG_SIGNATURE_SIZE &&
        bytes.copyOfRange(0, PNG_SIGNATURE_SIZE).contentEquals(PNG_SIGNATURE)

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private val TEXT_CHUNK_TYPES = setOf("tEXt", "zTXt", "iTXt")
    private const val PNG_SIGNATURE_SIZE = 8
    private const val CHUNK_OVERHEAD = 12
}
