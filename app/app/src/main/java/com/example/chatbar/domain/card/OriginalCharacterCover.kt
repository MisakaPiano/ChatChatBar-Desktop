package com.example.chatbar.domain.card

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32

/** Keeps encoded image chunks intact while removing card payloads from reusable cover images. */
object OriginalCharacterCover {
    private val cardKeywords = setOf("chatbarcharacter", "chara", "ccv3")

    fun imageOnlyPng(bytes: ByteArray): ByteArray {
        require(PngTextChunks.isPng(bytes)) { "封面不是 PNG 图片" }
        val output = ByteArrayOutputStream(bytes.size)
        output.write(bytes, 0, 8)
        var position = 8
        var first = true
        var hasImageData = false
        while (position <= bytes.size - 12) {
            val length = intAt(bytes, position)
            require(length >= 0 && length.toLong() + position + 12L <= bytes.size) { "PNG 封面数据不完整" }
            val dataStart = position + 8
            val dataEnd = dataStart + length
            val next = dataEnd + 4
            val type = String(bytes, position + 4, 4, Charsets.US_ASCII)
            require(!first || type == "IHDR" && length == 13) { "PNG 封面缺少图像头" }
            val crc = CRC32().apply { update(bytes, position + 4, length + 4) }
            require(crc.value.toInt() == intAt(bytes, dataEnd)) { "PNG 封面校验失败" }
            val characterData = type in setOf("tEXt", "zTXt", "iTXt") && run {
                val zero = (dataStart until dataEnd).firstOrNull { bytes[it] == 0.toByte() }
                zero != null && String(bytes, dataStart, zero - dataStart, Charsets.ISO_8859_1)
                    .lowercase(java.util.Locale.ROOT) in cardKeywords
            }
            if (!characterData) output.write(bytes, position, next - position)
            if (type == "IDAT") hasImageData = true
            if (type == "IEND") {
                require(length == 0 && hasImageData && next == bytes.size) { "PNG 封面结尾无效" }
                return output.toByteArray()
            }
            position = next
            first = false
        }
        throw IllegalArgumentException("PNG 封面缺少完整结尾")
    }

    /** Prefer the embedded independent original over an exported composite, avoiding nested card files. */
    fun attachToImportedPackage(source: CharacterCardPackage, png: ByteArray): CharacterCardPackage {
        if (source.card.coverImageResourceId != null) return source
        val image = imageOnlyPng(png)
        var id = "cover-original"
        var suffix = 2
        while (id in source.images) id = "cover-original-${suffix++}"
        return source.copy(
            schemaVersion = CHARACTER_CARD_PACKAGE_SCHEMA_VERSION,
            card = source.card.copy(coverImageResourceId = id),
            images = source.images + (id to PackagedImage("cover-original.png", Base64.getEncoder().encodeToString(image)))
        )
    }

    fun exportPng(original: ByteArray, packageJson: String): ByteArray =
        PngTextChunks.insertTextChunk(
            imageOnlyPng(original), PngTextChunks.CHATBAR_CHARACTER_KEYWORD,
            Base64.getEncoder().encodeToString(packageJson.toByteArray(Charsets.UTF_8))
        )

    private fun intAt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 255) shl 24) or
            ((bytes[offset + 1].toInt() and 255) shl 16) or
            ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
}
