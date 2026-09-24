package com.example.chatbar.domain.card

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** JVM-neutral authority for SillyTavern Character JSON and PNG metadata. */
object SillyTavernCardParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseJson(raw: String, pngBytes: ByteArray? = null): SillyTavernCard {
        val document = json.parseToJsonElement(raw).jsonObject
        val parsed = if (document.containsKey("spec")) {
            parseV2(document)
        } else {
            parseV1(document)
        }
        return parsed.copy(pngBytes = pngBytes)
    }

    fun extractCharaChunk(pngBytes: ByteArray): String? {
        val encoded = PngTextChunks.extractTextChunk(
            pngBytes,
            keyword = "Chara",
            ignoreCase = true,
        ) ?: return null
        return String(Base64.getMimeDecoder().decode(encoded), Charsets.UTF_8)
    }

    private fun parseV1(document: JsonObject): SillyTavernCard = SillyTavernCard(
        name = document.string("name"),
        description = document.string("description"),
        personality = document.string("personality"),
        scenario = document.string("scenario"),
        firstMes = document.string("first_mes"),
        mesExample = document.string("mes_example"),
    )

    private fun parseV2(document: JsonObject): SillyTavernCard {
        val data = document["data"]?.jsonObject
            ?: throw IllegalArgumentException("V2 角色卡缺少 data 字段")
        return SillyTavernCard(
            name = data.string("name"),
            description = data.string("description"),
            personality = data.string("personality"),
            scenario = data.string("scenario"),
            firstMes = data.string("first_mes"),
            mesExample = data.string("mes_example"),
            systemPrompt = data.string("system_prompt"),
            postHistoryInstructions = data.string("post_history_instructions"),
            alternateGreetings = data.stringArray("alternate_greetings"),
            creatorNotes = data.string("creator_notes"),
            tags = data.stringArray("tags"),
            creator = data.string("creator"),
            characterVersion = data.string("character_version"),
            extensions = data["extensions"]?.toString() ?: "",
            characterBook = data["character_book"]?.toString(),
        )
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.content ?: ""

    private fun JsonObject.stringArray(key: String): List<String> {
        val element = this[key] ?: return emptyList()
        return try {
            when (element) {
                is JsonArray -> element.map { it.jsonPrimitive.content }
                else -> {
                    val raw = element.toString().trim()
                    if (raw.startsWith("[") && raw.endsWith("]")) {
                        val inner = raw.substring(1, raw.length - 1).trim()
                        if (inner.isEmpty()) emptyList()
                        else inner.split(",").map { it.trim().removeSurrounding("\"") }
                    } else {
                        emptyList()
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

data class SillyTavernCard(
    val name: String,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMes: String = "",
    val mesExample: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val creatorNotes: String = "",
    val tags: List<String> = emptyList(),
    val creator: String = "",
    val characterVersion: String = "",
    val extensions: String = "",
    val characterBook: String? = null,
    val pngBytes: ByteArray? = null,
)
