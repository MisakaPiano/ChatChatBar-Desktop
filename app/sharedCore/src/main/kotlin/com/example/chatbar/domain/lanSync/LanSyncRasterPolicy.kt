package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.image.NovelAiPostProcessPolicy
import com.example.chatbar.domain.image.NovelAiPostProcessTab
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.CRC32

/** Encoded originals are streamed; raster working limits are independent of encoded length. */
object LanSyncRasterPolicy {
    fun maximumPixels(record: LanSyncRecord): Long = LanSyncPostProcess.record(record)?.let {
        it.validateMetadata()
        NovelAiPostProcessPolicy.MAX_PIXELS.toLong() * if (it.operation == NovelAiPostProcessTab.UPSCALE.name) 4 else 1
    } ?: 32_000_000L

    /** Walk PNG without retaining ancillary payloads. Native decoders still verify the raster. */
    fun checkPng(path: Path, staticPngRequired: Boolean) {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val size = Files.size(path)
        DataInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).buffered()).use { input ->
            val header = ByteArray(8)
            if (size >= 8) input.readFully(header)
            val png = size >= 8 && header.contentEquals(signature)
            require(png || !staticPngRequired) { "SYNC_IMAGE_INVALID" }
            if (!png) return
            var consumed = 8L
            var first = true
            var imageData = false
            var endedData = false
            val buffer = ByteArray(64 * 1024)
            while (consumed < size) {
                require(size - consumed >= 12) { "SYNC_IMAGE_INVALID" }
                val length = input.readInt().toLong()
                require(length >= 0 && length <= size - consumed - 12) { "SYNC_IMAGE_INVALID" }
                val typeBytes = ByteArray(4).also(input::readFully)
                require(typeBytes.all { it.toInt() in 65..90 || it.toInt() in 97..122 } && typeBytes[2].toInt() in 65..90) { "SYNC_IMAGE_INVALID" }
                val type = typeBytes.toString(Charsets.US_ASCII)
                require(!first || type == "IHDR" && length == 13L) { "SYNC_IMAGE_INVALID" }
                require(first || type != "IHDR") { "SYNC_IMAGE_INVALID" }
                require(!staticPngRequired || type !in setOf("acTL", "fcTL", "fdAT")) { "SYNC_IMAGE_ANIMATED_RESULT" }
                require(typeBytes[0].toInt() in 97..122 || type in setOf("IHDR", "PLTE", "IDAT", "IEND")) { "SYNC_IMAGE_INVALID" }
                if (type == "IDAT") { require(!endedData) { "SYNC_IMAGE_INVALID" }; imageData = true }
                else if (imageData) endedData = true
                val crc = CRC32().apply { update(typeBytes) }
                var remaining = length
                while (remaining > 0) {
                    val read = minOf(remaining, buffer.size.toLong()).toInt()
                    input.readFully(buffer, 0, read); crc.update(buffer, 0, read); remaining -= read
                }
                require((input.readInt().toLong() and 0xffffffffL) == crc.value) { "SYNC_IMAGE_INVALID" }
                consumed += length + 12
                if (type == "IEND") {
                    require(length == 0L && imageData && consumed == size && input.read() == -1) { "SYNC_IMAGE_INVALID" }
                    return
                }
                first = false
            }
            error("SYNC_IMAGE_INVALID")
        }
    }
}
