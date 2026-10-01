package com.example.chatbar.domain.image

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/** NovelAI alpha LSB protocol: column-major pixels, MSB-first bytes, bit length. */
internal object StealthAlphaMetadata {
    const val MAX_METADATA_BYTES = 8 * 1024 * 1024

    fun decode(width: Int, height: Int, alpha: (Int, Int) -> Int): String? {
        val capacity = width.toLong() * height
        if (width <= 0 || height <= 0 || capacity < 152) return null
        var position = 0L
        fun byte(): Int {
            require(position + 8 <= capacity) { "透明度元数据截断" }
            var result = 0
            repeat(8) {
                result = (result shl 1) or (alpha((position / height).toInt(), (position % height).toInt()) and 1)
                position++
            }
            return result
        }
        val signature = ByteArray(15) { byte().toByte() }.toString(Charsets.US_ASCII)
        if (signature != "stealth_pngcomp" && signature != "stealth_pnginfo") return null
        var bits = 0L
        repeat(4) { bits = (bits shl 8) or byte().toLong() }
        require(bits > 0 && bits % 8 == 0L && bits <= capacity - position && bits / 8 <= MAX_METADATA_BYTES) {
            "透明度元数据长度无效或超过限制"
        }
        val payload = ByteArray((bits / 8).toInt()) { byte().toByte() }
        val decoded = if (signature == "stealth_pngcomp") {
            GZIPInputStream(ByteArrayInputStream(payload)).use { input ->
                input.readBytesBounded(MAX_METADATA_BYTES)
            }
        } else payload
        return decoded.toString(Charsets.UTF_8)
    }
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size().toLong() + count <= limit) { "图片元数据解压后超过限制" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
