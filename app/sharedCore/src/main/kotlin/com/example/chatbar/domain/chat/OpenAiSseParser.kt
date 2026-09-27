package com.example.chatbar.domain.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

data class PromptCacheUsage(
    val promptTokens: Int? = null,
    val cachedTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val cacheMissTokens: Int? = null,
    val completionTokens: Int? = null,
)

data class OpenAiSseChunk(
    val content: String?,
    val reasoningContent: String?,
    val finishReason: String? = null,
    val refused: Boolean = false,
)

object OpenAiSseParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(data: String): OpenAiSseChunk {
        val root = try {
            json.decodeFromString<JsonObject>(data)
        } catch (error: Exception) {
            throw IllegalArgumentException("无效的 SSE 数据: ${error.message}", error)
        }
        root["error"]?.let { errorElement ->
            val errorText = when (errorElement) {
                is JsonObject -> buildString {
                    append(errorElement["message"]?.jsonPrimitive?.contentOrNull ?: "未知错误")
                    errorElement["code"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?.let { append(" (code=$it)") }
                }
                else -> errorElement.jsonPrimitive.contentOrNull ?: "未知错误"
            }
            throw IllegalArgumentException("服务端返回错误: $errorText")
        }

        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val delta = choice?.get("delta") as? JsonObject
        val content = delta?.get("content")?.let(::contentText)
        val reasoning = delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
            ?: delta?.get("reasoning")?.jsonPrimitive?.contentOrNull
            ?: delta?.get("thinking")?.jsonPrimitive?.contentOrNull
        val finishReason = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
        val refused = !delta?.get("refusal")?.let(::contentText).isNullOrBlank() ||
            (delta?.get("content") as? JsonArray)?.any { part ->
                (part as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "refusal"
            } == true || finishReason == "content_filter"
        return OpenAiSseChunk(content, reasoning, finishReason, refused)
    }

    fun parseUsage(data: String): PromptCacheUsage? {
        val usage = runCatching { json.decodeFromString<JsonObject>(data) }
            .getOrNull()
            ?.get("usage") as? JsonObject ?: return null
        val promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull
        val details = usage["prompt_tokens_details"] as? JsonObject
        val cachedTokens = details?.get("cached_tokens")?.jsonPrimitive?.intOrNull
            ?: usage["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
        val cacheWriteTokens = details?.get("cache_write_tokens")?.jsonPrimitive?.intOrNull
        val cacheMissTokens = usage["prompt_cache_miss_tokens"]?.jsonPrimitive?.intOrNull
        return PromptCacheUsage(
            promptTokens = promptTokens,
            cachedTokens = cachedTokens,
            cacheWriteTokens = cacheWriteTokens,
            cacheMissTokens = cacheMissTokens,
            completionTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull,
        ).takeIf {
            it.promptTokens != null || it.cachedTokens != null ||
                it.cacheWriteTokens != null || it.cacheMissTokens != null || it.completionTokens != null
        }
    }

    private fun contentText(content: JsonElement): String? = when (content) {
        is JsonPrimitive -> content.contentOrNull
        is JsonArray -> content.joinToString("") { part ->
            when (part) {
                is JsonObject -> part["text"]?.jsonPrimitive?.contentOrNull
                    ?: part["content"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                is JsonPrimitive -> part.contentOrNull ?: ""
                else -> ""
            }
        }.takeIf(String::isNotBlank)
        else -> null
    }
}
