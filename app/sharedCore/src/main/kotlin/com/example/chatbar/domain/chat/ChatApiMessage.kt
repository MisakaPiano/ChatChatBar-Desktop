package com.example.chatbar.domain.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** JVM-neutral logical chat message. Transport adaptation remains platform-owned. */
@Serializable
data class ChatApiMessage(
    val role: String,
    val content: JsonElement,
) {
    companion object {
        fun text(role: String, content: String) = ChatApiMessage(
            role = role,
            content = JsonPrimitive(content),
        )

        fun withImage(role: String, text: String, imageBase64: String) = ChatApiMessage(
            role = role,
            content = multimodalContent(text, listOf(imageBase64)),
        )

        fun withImages(role: String, text: String, imageBase64s: List<String>) = ChatApiMessage(
            role = role,
            content = multimodalContent(text, imageBase64s),
        )

        private fun multimodalContent(text: String, imageBase64s: List<String>) = buildJsonArray {
            text.takeIf(String::isNotBlank)?.let { nonBlankText ->
                add(buildJsonObject {
                    put("type", "text")
                    put("text", nonBlankText)
                })
            }
            imageBase64s.forEach { imageBase64 ->
                if (imageBase64.isNotBlank()) {
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject {
                            put("url", "data:image/jpeg;base64,$imageBase64")
                        })
                    })
                }
            }
        }
    }
}
