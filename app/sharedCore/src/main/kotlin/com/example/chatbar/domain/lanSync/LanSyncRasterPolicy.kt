package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.image.NovelAiPostProcessPolicy
import com.example.chatbar.domain.image.NovelAiPostProcessTab
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.CRC32

class LanSyncLocalImageUnsupported : IllegalArgumentException("SYNC_GROUP_LOCAL_RESOURCE_BLOCKED")
class LanSyncInvalidImage(cause: Throwable? = null) : IllegalArgumentException("SYNC_IMAGE_INVALID", cause)

/** Encoded originals are streamed; raster working limits are independent of encoded length. */
object LanSyncRasterPolicy {
    fun maximumPixels(record: LanSyncRecord?): Long = record?.let(LanSyncPostProcess::record)?.let {
        it.validateMetadata()
        NovelAiPostProcessPolicy.MAX_PIXELS.toLong() * if (it.operation == NovelAiPostProcessTab.UPSCALE.name) 4 else 1
    } ?: 32_000_000L

    /** Camera JPEG can be admitted and sampled above the ordinary bitmap limit. */
    fun validateDimensions(path: Path, record: LanSyncRecord?, width: Int, height: Int, jpeg: Boolean) {
        val pixels = width.toLong() * height
        requireValidImage(width > 0 && height > 0)
        if (pixels <= maximumPixels(record)) return
        requireValidImage(record?.let(LanSyncPostProcess::record) == null)
        if (!jpeg) throw LanSyncLocalImageUnsupported()
        val frame = jpegFrame(path) ?: throw LanSyncInvalidImage()
        requireValidImage(frame.width == width && frame.height == height)
        if (!frame.baseline || width > 16_384 || height > 16_384 || pixels > 72_000_000L) throw LanSyncLocalImageUnsupported()
    }
    fun needsSampledJpegPreview(path: Path): Boolean = jpegFrame(path)?.let {
        it.width.toLong() * it.height > maximumPixels(null)
    } == true

    private data class JpegFrame(val width: Int, val height: Int, val baseline: Boolean)

    // Progressive decoding may need full-size coefficient storage despite a sampled output.
    private fun jpegFrame(path: Path): JpegFrame? {
        DataInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).buffered()).use { input ->
            if (input.read() != 0xff || input.read() != 0xd8) return null
            var budget = 16 * 1024 * 1024
            while (budget >= 4) {
                if (input.readUnsignedByte() != 0xff) return null
                var marker: Int
                do { marker = input.readUnsignedByte(); budget-- } while (marker == 0xff && budget >= 4)
                if (marker == 0xc0 || marker == 0xc1 || marker == 0xc2) {
                    if (input.readUnsignedShort() < 8 || input.readUnsignedByte() != 8) return null
                    val height = input.readUnsignedShort()
                    return JpegFrame(input.readUnsignedShort(), height, marker == 0xc0)
                }
                if (marker != 0xc4 && marker !in 0xe0..0xef && marker != 0xdb && marker != 0xdd && marker != 0xfe) return null
                val length = input.readUnsignedShort()
                if (length < 2 || length + 1 > budget || input.skipBytes(length - 2) != length - 2) return null
                budget -= length + 1
            }
            return null
        }
    }

    /** Walk PNG without retaining ancillary payloads. Native decoders still verify the raster. */
    fun checkPng(path: Path, staticPngRequired: Boolean) {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val size = Files.size(path)
        DataInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).buffered()).use { input ->
            val header = ByteArray(8)
            if (size >= 8) input.readFully(header)
            val png = size >= 8 && header.contentEquals(signature)
            requireValidImage(png || !staticPngRequired)
            if (!png) return
            var consumed = 8L
            var first = true
            var imageData = false
            var endedData = false
            var palette = false
            val buffer = ByteArray(64 * 1024)
            while (consumed < size) {
                requireValidImage(size - consumed >= 12)
                val length = input.readInt().toLong()
                requireValidImage(length >= 0 && length <= size - consumed - 12)
                val typeBytes = ByteArray(4).also(input::readFully)
                requireValidImage(typeBytes.all { it.toInt() in 65..90 || it.toInt() in 97..122 } && typeBytes[2].toInt() in 65..90)
                val type = typeBytes.toString(Charsets.US_ASCII)
                requireValidImage(!first || type == "IHDR" && length == 13L)
                requireValidImage(first || type != "IHDR")
                require(!staticPngRequired || type !in setOf("acTL", "fcTL", "fdAT")) { "SYNC_IMAGE_ANIMATED_RESULT" }
                requireValidImage(typeBytes[0].toInt() in 97..122 || type in setOf("IHDR", "PLTE", "IDAT", "IEND"))
                if (type == "PLTE") {
                    requireValidImage(!palette && !imageData && length in 3L..768L && length % 3 == 0L)
                    palette = true
                }
                if (type == "IDAT") { requireValidImage(!endedData); imageData = true }
                else if (imageData) endedData = true
                val crc = CRC32().apply { update(typeBytes) }
                var remaining = length
                while (remaining > 0) {
                    val read = minOf(remaining, buffer.size.toLong()).toInt()
                    input.readFully(buffer, 0, read); crc.update(buffer, 0, read); remaining -= read
                }
                requireValidImage((input.readInt().toLong() and 0xffffffffL) == crc.value)
                consumed += length + 12
                if (type == "IEND") {
                    requireValidImage(length == 0L && imageData && consumed == size && input.read() == -1)
                    return
                }
                first = false
            }
            throw LanSyncInvalidImage()
        }
    }

    private fun requireValidImage(condition: Boolean) {
        if (!condition) throw LanSyncInvalidImage()
    }
}
