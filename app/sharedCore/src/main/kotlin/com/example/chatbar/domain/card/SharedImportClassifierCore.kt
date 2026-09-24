package com.example.chatbar.domain.card

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

enum class SharedImportKind {
    CHARACTER,
    FORMAT,
    WORLD_BOOK,
    MODEL_TEMPLATE,
    IMAGE,
    UNKNOWN,
}

data class SharedImportImageInfo(
    val mimeType: String,
    val width: Int,
    val height: Int,
    val animatedGif: Boolean,
)

fun interface SharedImportModelTemplateDecoder<M> {
    fun decode(rawJson: String): M
}

sealed interface SharedImportCoreInspection<out M> {
    val kind: SharedImportKind

    data class Character(val request: CharacterCardImportRequest) : SharedImportCoreInspection<Nothing> {
        override val kind = SharedImportKind.CHARACTER
    }

    data class Format(val packageData: FormatCardPackage) : SharedImportCoreInspection<Nothing> {
        override val kind = SharedImportKind.FORMAT
    }

    data class WorldBook(val packageData: WorldBookPackage) : SharedImportCoreInspection<Nothing> {
        override val kind = SharedImportKind.WORLD_BOOK
    }

    data class ModelTemplate<M>(val packageData: M) : SharedImportCoreInspection<M> {
        override val kind = SharedImportKind.MODEL_TEMPLATE
    }

    data class Image(val info: SharedImportImageInfo) : SharedImportCoreInspection<Nothing> {
        override val kind = SharedImportKind.IMAGE
    }

    data class Unknown(val textLike: Boolean) : SharedImportCoreInspection<Nothing> {
        override val kind = SharedImportKind.UNKNOWN
    }
}

/**
 * Shared content-first classifier authority.
 *
 * Android's ModelTemplate contract remains platform-owned and enters only through the typed decoder seam;
 * candidate order and all shared Package/ST decoding remain defined here once.
 */
class SharedImportClassifierCore<M>(
    private val sillyTavernMapper: SillyTavernCardMapper,
    private val modelTemplateDecoder: SharedImportModelTemplateDecoder<M>? = null,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    fun inspect(
        bytes: ByteArray,
        displayName: String = "共享文件",
        imageInfo: SharedImportImageInfo? = null,
    ): SharedImportCoreInspection<M> {
        if (PngTextChunks.isPng(bytes)) {
            val chatBarPayload = PngTextChunks.extractTextChunk(
                bytes,
                PngTextChunks.CHATBAR_CHARACTER_KEYWORD,
            )
            if (chatBarPayload != null) {
                return runCatching { decodeChatBarPng(chatBarPayload) }
                    .getOrElse { SharedImportCoreInspection.Unknown(textLike = false) }
            }
            val sillyTavernPayload = SillyTavernCardParser.extractCharaChunk(bytes)
            if (sillyTavernPayload != null) {
                return runCatching { decodeSillyTavernCharacter(sillyTavernPayload, bytes) }
                    .getOrElse { SharedImportCoreInspection.Unknown(textLike = false) }
            }
        }

        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim()
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (root != null) return inspectJson(text, root, displayName)
        if (imageInfo != null) return SharedImportCoreInspection.Image(imageInfo)
        return SharedImportCoreInspection.Unknown(textLike = looksLikeText(bytes))
    }

    fun decodeAs(
        bytes: ByteArray,
        kind: SharedImportKind,
        displayName: String = "共享文件",
    ): SharedImportCoreInspection<M> {
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim()
        return when (kind) {
            SharedImportKind.CHARACTER -> {
                if (PngTextChunks.isPng(bytes)) {
                    PngTextChunks.extractTextChunk(bytes, PngTextChunks.CHATBAR_CHARACTER_KEYWORD)?.let {
                        return decodeChatBarPng(it)
                    }
                    SillyTavernCardParser.extractCharaChunk(bytes)?.let {
                        return decodeSillyTavernCharacter(it, bytes)
                    }
                    error("PNG 中未找到 ChatBar 或 SillyTavern 角色卡数据")
                }
                decodeCharacterJson(text)
            }

            SharedImportKind.FORMAT -> SharedImportCoreInspection.Format(
                json.decodeFromString(FormatCardPackage.serializer(), text).also {
                    it.validateForImport()
                },
            )

            SharedImportKind.WORLD_BOOK -> SharedImportCoreInspection.WorldBook(
                WorldBookTransferService(json).decode(text, fallbackName(displayName, "导入世界书")),
            )

            SharedImportKind.MODEL_TEMPLATE -> SharedImportCoreInspection.ModelTemplate(
                requireNotNull(modelTemplateDecoder) { "模型模板解析器不可用" }.decode(text),
            )

            SharedImportKind.IMAGE,
            SharedImportKind.UNKNOWN,
            -> error("该目标不能手动解析")
        }
    }

    private fun inspectJson(
        text: String,
        root: JsonObject,
        displayName: String,
    ): SharedImportCoreInspection<M> {
        val candidates = buildList {
            if (root.containsKey("card")) add(SharedImportKind.CHARACTER)
            if (root.containsKey("book")) add(SharedImportKind.WORLD_BOOK)
            if (
                modelTemplateDecoder != null &&
                root.containsKey("displayName") &&
                root.containsKey("modelName")
            ) {
                add(SharedImportKind.MODEL_TEMPLATE)
            }
            if (root.containsKey("name") && root.containsKey("content")) add(SharedImportKind.FORMAT)
            if (root.containsKey("entries")) add(SharedImportKind.WORLD_BOOK)
            if (looksLikeSillyTavernV2(root) || looksLikeSillyTavernV1(root)) {
                add(SharedImportKind.CHARACTER)
            }
        }.distinct()
        if (candidates.size != 1) return SharedImportCoreInspection.Unknown(textLike = true)
        return runCatching {
            decodeAs(text.toByteArray(Charsets.UTF_8), candidates.single(), displayName)
        }.getOrElse {
            SharedImportCoreInspection.Unknown(textLike = true)
        }
    }

    private fun decodeChatBarPng(payload: String): SharedImportCoreInspection.Character {
        val rawJson = if (payload.trimStart().startsWith("{")) {
            payload
        } else {
            String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
        }
        val packageData = json.decodeFromString(CharacterCardPackage.serializer(), rawJson)
            .withoutEmptyCharacterPlaceholders()
            .also(CharacterCardPackage::validateForImport)
        return SharedImportCoreInspection.Character(CharacterCardImportRequest(packageData))
    }

    private fun decodeCharacterJson(text: String): SharedImportCoreInspection.Character {
        val root = json.parseToJsonElement(text).jsonObject
        val packageData = when {
            root.containsKey("card") -> json.decodeFromString(CharacterCardPackage.serializer(), text)
                .withoutEmptyCharacterPlaceholders()
                .also(CharacterCardPackage::validateForImport)

            looksLikeSillyTavernV2(root) || looksLikeSillyTavernV1(root) ->
                sillyTavernMapper.toCharacterCardPackage(SillyTavernCardParser.parseJson(text))
                    .also(CharacterCardPackage::validateForImport)

            else -> error("文件不是受支持的 ChatBar 或 SillyTavern 角色卡")
        }
        return SharedImportCoreInspection.Character(CharacterCardImportRequest(packageData))
    }

    private fun decodeSillyTavernCharacter(
        rawJson: String,
        pngBytes: ByteArray,
    ): SharedImportCoreInspection.Character {
        val root = json.parseToJsonElement(rawJson).jsonObject
        require(looksLikeSillyTavernV2(root) || looksLikeSillyTavernV1(root)) {
            "PNG 中的 Chara 数据不是受支持的 SillyTavern 角色卡"
        }
        val packageData = sillyTavernMapper.toCharacterCardPackage(
            SillyTavernCardParser.parseJson(rawJson, pngBytes),
        ).also(CharacterCardPackage::validateForImport)
        return SharedImportCoreInspection.Character(CharacterCardImportRequest(packageData))
    }

    private fun looksLikeSillyTavernV2(root: JsonObject): Boolean =
        root.containsKey("spec") && root["data"] is JsonObject

    private fun looksLikeSillyTavernV1(root: JsonObject): Boolean =
        root.containsKey("name") && listOf(
            "description",
            "personality",
            "scenario",
            "first_mes",
            "mes_example",
        ).any(root::containsKey)

    private fun fallbackName(displayName: String, default: String): String =
        displayName.substringAfterLast('/').substringBeforeLast('.').trim().ifBlank { default }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        return bytes.take(4096).none { byte ->
            val value = byte.toInt() and 0xff
            value == 0 || value in 1..8 || value in 14..31
        }
    }
}
