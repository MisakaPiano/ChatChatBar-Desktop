package com.example.chatbar.desktop

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import javax.imageio.stream.FileImageInputStream
import javax.imageio.stream.ImageInputStream
import javax.imageio.stream.ImageInputStreamImpl

/** Read-only seekable view. Validation omits ancillary metadata without copying or changing originals. */
internal class DesktopLanSyncWebPInput private constructor(private val source: ImageInputStream,
    private val parts: List<Part>, private val size: Long) : ImageInputStreamImpl() {
    private data class Part(val start: Long, val length: Long, val sourceOffset: Long, val bytes: ByteArray? = null)
    private val single = ByteArray(1)
    override fun length() = size
    override fun read(): Int = if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 255
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        checkClosed(); require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        bitOffset = 0
        if (length == 0) return 0
        if (streamPos >= size) return -1
        val requested = minOf(length.toLong(), size - streamPos).toInt()
        var completed = 0
        // ImageInputStreamImpl primitive reads expect their full buffer, including across parts.
        while (completed < requested) {
            var low = 0; var high = parts.lastIndex
            while (low < high) { val middle = (low + high + 1) ushr 1; if (parts[middle].start <= streamPos) low = middle else high = middle - 1 }
            val part = parts[low]; val position = streamPos - part.start
            val count = minOf((requested - completed).toLong(), part.length - position).toInt()
            require(count > 0) { "SYNC_IMAGE_INVALID" }
            val read = if (part.bytes != null) {
                part.bytes.copyInto(bytes, offset + completed, position.toInt(), position.toInt() + count); count
            } else { source.seek(part.sourceOffset + position); source.read(bytes, offset + completed, count).also { require(it > 0) { "SYNC_IMAGE_INVALID" } } }
            streamPos += read; completed += read
        }
        return completed
    }
    override fun close() { try { super.close() } finally { source.close() } }

    companion object {
        private const val METADATA_BYTES = 16L * 1024 * 1024
        fun open(path: Path, renderColorMetadata: Boolean, maximumPixels: Long = 32_000_000L): ImageInputStream {
            require(maximumPixels > 0)
            val input = FileImageInputStream(path.toFile())
            try {
                val originalSize = input.length()
                if (originalSize < 12) return input
                val header = ByteArray(12).also(input::readFully); input.seek(0)
                if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" || String(header, 8, 4, Charsets.US_ASCII) != "WEBP") return input
                fun unsigned(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                require(unsigned(header, 4) == originalSize - 8) { "SYNC_IMAGE_INVALID" }
                val parts = mutableListOf(Part(0, 12, 0, header)); var logicalSize = 12L; var position = 12L
                var canvas: Pair<Long, Long>? = null
                var metadataBytes = 0L
                while (position < originalSize) {
                    require(originalSize - position >= 8) { "SYNC_IMAGE_INVALID" }
                    input.seek(position); val chunk = ByteArray(8).also(input::readFully)
                    val type = String(chunk, 0, 4, Charsets.US_ASCII); val length = unsigned(chunk, 4)
                    val physicalLength = 8 + length + (length and 1)
                    require(physicalLength <= originalSize - position) { "SYNC_IMAGE_INVALID" }
                    if (type == "ICCP") {
                        require(length >= 128) { "SYNC_IMAGE_INVALID" }
                        val profile = ByteArray(128).also(input::readFully)
                        val declared = ByteBuffer.wrap(profile).int.toLong() and 0xffffffffL
                        require(declared in 128..length && String(profile, 36, 4, Charsets.US_ASCII) == "acsp") { "SYNC_IMAGE_INVALID" }
                        if (renderColorMetadata) {
                            metadataBytes += length
                            require(metadataBytes <= METADATA_BYTES && declared <= METADATA_BYTES) { "SYNC_IMAGE_METADATA_WORKING_BUDGET" }
                        }
                    }
                    var replacement: ByteArray? = null
                    if (type == "VP8X") {
                        require(position == 12L && length == 10L) { "SYNC_IMAGE_INVALID" }
                        val body = ByteArray(10).also(input::readFully)
                        fun dimension(offset: Int) = 1L + (body[offset].toInt() and 255) + ((body[offset + 1].toInt() and 255) shl 8) + ((body[offset + 2].toInt() and 255) shl 16)
                        canvas = dimension(4) to dimension(7)
                        require(canvas.first * canvas.second <= maximumPixels) { "SYNC_IMAGE_INVALID" }
                        body[0] = (body[0].toInt() and (if (renderColorMetadata) 0x0c else 0x2c).inv()).toByte()
                        replacement = chunk + body
                    }
                    if (type == "ANMF") {
                        require(length >= 16) { "SYNC_IMAGE_INVALID" }
                        val bounds = requireNotNull(canvas) { "SYNC_IMAGE_INVALID" }
                        val frame = ByteArray(16).also(input::readFully)
                        fun value(offset: Int) = (frame[offset].toLong() and 255) + ((frame[offset + 1].toLong() and 255) shl 8) + ((frame[offset + 2].toLong() and 255) shl 16)
                        require(value(0) * 2 + value(6) + 1 <= bounds.first && value(3) * 2 + value(9) + 1 <= bounds.second) { "SYNC_IMAGE_INVALID" }
                        require((value(6) + 1) * (value(9) + 1) <= maximumPixels) { "SYNC_IMAGE_INVALID" }
                    }
                    if (type !in setOf("EXIF", "XMP ") && (renderColorMetadata || type != "ICCP")) {
                        require(parts.size < 65_536) { "SYNC_IMAGE_METADATA_WORKING_BUDGET" }
                        parts += Part(logicalSize, physicalLength, position, replacement); logicalSize += physicalLength
                    }
                    position += physicalLength
                }
                require(position == originalSize) { "SYNC_IMAGE_INVALID" }
                ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(4, (logicalSize - 8).toInt())
                return DesktopLanSyncWebPInput(input, parts, logicalSize)
            } catch (failure: Throwable) { input.close(); throw failure }
        }
    }
}
