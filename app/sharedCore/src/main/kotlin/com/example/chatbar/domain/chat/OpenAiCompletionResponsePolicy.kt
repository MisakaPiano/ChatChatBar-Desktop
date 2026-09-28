package com.example.chatbar.domain.chat

import com.example.chatbar.domain.prompt.*
import kotlinx.serialization.json.*

object OpenAiCompletionResponsePolicy {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    fun content(body: String): String {
        val obj = json.decodeFromString<JsonObject>(body)
        val message = obj["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")
            ?.jsonObject
        val content = message?.get("content")
        val primitiveContent = (content as? JsonPrimitive)?.contentOrNull
        if (primitiveContent != null) return primitiveContent

        val arrayContent = runCatching {
            content?.jsonArray?.joinToString("") { part ->
                val partObj = part.jsonObject
                partObj["text"]?.jsonPrimitive?.contentOrNull
                    ?: partObj["content"]?.jsonPrimitive?.contentOrNull
                    ?: ""
            }
        }.getOrNull()
        if (arrayContent != null) return arrayContent

        val reasoningContent = message?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
            ?: message?.get("reasoning")?.jsonPrimitive?.contentOrNull
        if (reasoningContent != null) throw AiTaskEmptyResponseException(true)

        val legacyText = obj["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")
            ?.jsonPrimitive?.contentOrNull
        if (legacyText != null) return legacyText

        val outputText = obj["output_text"]?.jsonPrimitive?.contentOrNull
        if (outputText != null) return outputText

        throw RuntimeException("无法解析响应内容。Raw body: ${body.take(2000)}")
    }

    fun finishReason(body: String): String? = runCatching {
        json.decodeFromString<JsonObject>(body)["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("finish_reason")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    fun requireCompletion(body: String, onContent: (String) -> Unit = {}): String {
        val reason = finishReason(body)
        val refused = AiTaskRefusalPolicy.responseRefused(body)
        AiTaskRefusalPolicy.failure("", reason, refused)?.let { throw it }
        val content = content(body)
        onContent(content)
        AiTaskRefusalPolicy.failure(content, reason, refused)?.let { throw it }
        if (reason == "length") throw ModelResponseTruncatedException()
        if (content.isBlank()) throw AiTaskEmptyResponseException(false)
        return content
    }
}
