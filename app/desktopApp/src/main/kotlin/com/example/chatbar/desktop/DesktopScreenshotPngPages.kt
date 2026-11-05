package com.example.chatbar.desktop

import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO

/** One PNG file, one bounded decoded frame and one RGBA scanline; never allocates the combined raster. */
internal fun writeDesktopScreenshotPngPages(pages: List<DesktopScreenshotPng>, output: OutputStream,
    checkCancelled: () -> Unit = {}) {
    require(pages.isNotEmpty())
    val width = pages.first().width
    require(pages.all { it.width == width })
    val height = pages.sumOf { it.height.toLong() }
    require(height in 1..Int.MAX_VALUE.toLong())
    pages.forEach { DesktopChatScreenshotRenderer.validatePlan(it.width, it.height) }
    if (pages.size == 1) { checkCancelled(); output.write(pages.single().bytes); return }
    val data = DataOutputStream(output)
    data.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
    fun chunk(type: String, bytes: ByteArray, count: Int = bytes.size) {
        val kind = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32(); crc.update(kind); crc.update(bytes, 0, count)
        data.writeInt(count); data.write(kind); data.write(bytes, 0, count); data.writeInt(crc.value.toInt())
    }
    val header = java.io.ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use {
        it.writeInt(width); it.writeInt(height.toInt()); it.writeByte(8); it.writeByte(6)
        it.writeByte(0); it.writeByte(0); it.writeByte(0)
    } }.toByteArray()
    chunk("IHDR", header)
    val idat = object : OutputStream() {
        val buffer = ByteArray(64 * 1024)
        var size = 0
        override fun write(value: Int) { buffer[size++] = value.toByte(); if (size == buffer.size) flush() }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            var position = offset; var remaining = length
            while (remaining > 0) {
                val take = minOf(remaining, buffer.size - size)
                bytes.copyInto(buffer, size, position, position + take)
                size += take; position += take; remaining -= take
                if (size == buffer.size) flush()
            }
        }
        override fun flush() { if (size > 0) { checkCancelled(); chunk("IDAT", buffer, size); size = 0 } }
        override fun close() = flush()
    }
    DeflaterOutputStream(idat).use { compressed ->
        val pixels = IntArray(width)
        val row = ByteArray(width * 4 + 1)
        pages.forEach { frame ->
            checkCancelled()
            ImageIO.createImageInputStream(frame.bytes.inputStream()).use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: error("截图分块无法读取")
                try {
                    reader.input = input
                    require(reader.getWidth(0) == width && reader.getHeight(0) == frame.height)
                    val bitmap = reader.read(0)
                    try {
                        for (y in 0 until frame.height) {
                            if (y % 64 == 0) checkCancelled()
                            bitmap.getRGB(0, y, width, 1, pixels, 0, width)
                            row[0] = 0
                            pixels.forEachIndexed { x, argb ->
                                val offset = 1 + x * 4
                                row[offset] = (argb ushr 16).toByte(); row[offset + 1] = (argb ushr 8).toByte()
                                row[offset + 2] = argb.toByte(); row[offset + 3] = (argb ushr 24).toByte()
                            }
                            compressed.write(row)
                        }
                    } finally { bitmap.flush() }
                } finally { reader.dispose() }
            }
        }
    }
    checkCancelled()
    chunk("IEND", ByteArray(0))
}
