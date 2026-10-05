package com.example.chatbar.domain.image

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Writes fresh sRGB pixels, never source chunks or compressed image data. */
internal object PrivacyPngEncoder {
    fun write(
        output: OutputStream,
        width: Int,
        height: Int,
        orientation: Int = 1,
        readRow: (Int, IntArray) -> Unit
    ) {
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS)
        require(orientation in 1..8)
        val stream = DataOutputStream(output)
        stream.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeInt(width)
                writeInt(height)
                write(byteArrayOf(8, 6, 0, 0, 0))
            }
        }.toByteArray()
        writeChunk(stream, "IHDR", header)
        writeChunk(stream, "sRGB", byteArrayOf(0))
        if (orientation != 1) {
            writeChunk(stream, "eXIf", ImageMetadataStripper.buildMinimalTiffOrientation(orientation))
        }
        // Bounded IDAT chunks avoid retaining a second full compressed image in memory.
        val chunks = object : OutputStream() {
            private val buffer = ByteArray(64 * 1024)
            private var size = 0

            override fun write(value: Int) {
                buffer[size++] = value.toByte()
                if (size == buffer.size) flush()
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                var position = offset
                var remaining = length
                while (remaining > 0) {
                    val count = minOf(remaining, buffer.size - size)
                    bytes.copyInto(buffer, size, position, position + count)
                    size += count
                    position += count
                    remaining -= count
                    if (size == buffer.size) flush()
                }
            }

            override fun flush() {
                if (size > 0) writeChunk(stream, "IDAT", buffer.copyOf(size))
                size = 0
            }

            override fun close() = flush()
        }
        val pixels = IntArray(width)
        val row = ByteArray(1 + width * 4) // Filter None; direct RGBA avoids premultiplication round-off.
        DeflaterOutputStream(chunks).use { compressed ->
            repeat(height) { y ->
                readRow(y, pixels)
                pixels.forEachIndexed { x, pixel ->
                    val clean = sanitizePixel(pixel)
                    val offset = 1 + x * 4
                    row[offset] = (clean ushr 16).toByte()
                    row[offset + 1] = (clean ushr 8).toByte()
                    row[offset + 2] = clean.toByte()
                    row[offset + 3] = (clean ushr 24).toByte()
                }
                compressed.write(row)
            }
        }
        writeChunk(stream, "IEND", byteArrayOf())
        stream.flush()
    }

    // Erase every payload bit, including data after a damaged/missing stealth signature.
    // Each even/odd pair maps to one value. Preserve fully transparent and opaque endpoints.
    internal fun sanitizePixel(pixel: Int): Int {
        val alpha = (pixel ushr 24) and 0xfe
        if (alpha == 0) return 0 // Invisible RGB must not retain private data either.
        val cleanAlpha = if (alpha == 254) 255 else alpha
        return (cleanAlpha shl 24) or (pixel and 0x00fefefe)
    }

    private fun writeChunk(output: DataOutputStream, type: String, data: ByteArray) {
        val name = type.toByteArray(Charsets.US_ASCII)
        output.writeInt(data.size)
        output.write(name)
        output.write(data)
        output.writeInt(CRC32().apply { update(name); update(data) }.value.toInt())
    }

    const val MAX_PIXELS = 12_582_912L
}
