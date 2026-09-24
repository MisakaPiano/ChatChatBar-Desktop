package com.example.chatbar.domain.card

import java.util.Base64

/**
 * Character transfer 对 owned resources 的最小平台边界。
 *
 * Package resource payload、Entity persisted reference 与实际 filesystem path 是三个不同层次。
 * 实现负责产生本平台允许持久化的 reference，并且只删除自己能够证明属于 Character owned
 * resource area 的 reference。
 */
interface CharacterResourceStore {
    fun readText(reference: String): String

    fun readBytes(reference: String): ByteArray

    fun fileName(reference: String): String

    fun materializeDocument(
        document: PackagedDocument,
        timestamp: Long,
        resourceId: String,
    ): String

    fun materializeImage(
        image: PackagedImage,
        timestamp: Long,
        resourceId: String,
    ): String

    /** 删除 owned reference；实现不得把任意外部路径当成 owned resource。 */
    fun deleteOwned(reference: String)
}

interface CharacterTransferPromptPolicy {
    fun defaultCharacterNaiNegativePrompt(): String

    fun effectiveCharacterNaiNegativePrompt(value: String): String
}

fun interface CharacterDocumentRagCleanup {
    suspend fun deleteDocumentChunks(characterId: String)
}

sealed interface CharacterPackagedImageContent {
    data class Bytes(val value: ByteArray) : CharacterPackagedImageContent

    data class Asset(val logicalPath: String) : CharacterPackagedImageContent
}

/** Android Base64.DEFAULT 接受换行/空白；JVM shared 解码保持该兼容性。 */
fun decodeCharacterPackagedImage(data: String): CharacterPackagedImageContent {
    if (data.startsWith(CHARACTER_ASSET_PREFIX)) {
        val logicalPath = data.removePrefix(CHARACTER_ASSET_PREFIX)
        require(logicalPath.isNotBlank()) { "角色图片 asset 引用不能为空" }
        return CharacterPackagedImageContent.Asset(logicalPath)
    }
    return CharacterPackagedImageContent.Bytes(Base64.getMimeDecoder().decode(data))
}

internal const val CHARACTER_ASSET_PREFIX = "asset:"

object CharacterResourceNaming {
    fun safeDocumentFileName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "document.txt" }

    fun imageExtension(fileName: String): String =
        fileName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
            ?: "jpg"
}
