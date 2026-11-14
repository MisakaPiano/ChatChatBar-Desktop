package com.example.chatbar.domain.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Transport-only role adaptation; original role and wording survive in quoted unsupported turns. */
internal object GeminiChatTemplatePolicy {
    fun adaptMessages(messages: List<ChatApiMessage>): List<ChatApiMessage> {
        if (messages.isEmpty()) return messages
        val leadingSystems = messages.takeWhile { it.role == "system" }
        val conversation = messages.drop(leadingSystems.size)
        val firstUser = conversation.indexOfFirst { it.role == "user" }
        val lastUser = conversation.indexOfLast { it.role == "user" }
        val result = mutableListOf<ChatApiMessage>()
        if (firstUser < 0) {
            if (conversation.isEmpty()) return listOf(ChatApiMessage("user", quote(messages)))
            addSystem(result, leadingSystems)
            result.add(ChatApiMessage("user", quote(conversation)))
            return result
        }
        addSystem(result, leadingSystems)
        var first = conversation[firstUser]
        if (firstUser > 0) first = first.copy(content = combine(quote(conversation.take(firstUser)), first.content))
        result.add(first)
        for (index in firstUser + 1..lastUser) {
            val message = conversation[index]
            val previous = result.last()
            when {
                message.role == "system" -> result[result.lastIndex] = previous.copy(
                    content = combine(previous.content, quote(listOf(message))),
                )
                message.role == previous.role -> result[result.lastIndex] = previous.copy(
                    content = combine(previous.content, message.content),
                )
                else -> result.add(message)
            }
        }
        if (lastUser < conversation.lastIndex) {
            val previous = result.last()
            result[result.lastIndex] = previous.copy(content = combine(previous.content, quote(conversation.drop(lastUser + 1))))
        }
        return result
    }

    private fun addSystem(result: MutableList<ChatApiMessage>, messages: List<ChatApiMessage>) {
        if (messages.isNotEmpty()) result.add(ChatApiMessage("system", messages.map { it.content }.reduce(::combine)))
    }

    private fun quote(messages: List<ChatApiMessage>): JsonPrimitive = JsonPrimitive(JsonArray(messages.map { message ->
        buildJsonObject {
            put("role", message.role)
            put("content", message.content)
        }
    }).toString())

    private fun combine(first: JsonElement, second: JsonElement): JsonElement {
        if (first is JsonPrimitive && first.isString && second is JsonPrimitive && second.isString) {
            return JsonPrimitive(first.content + "\n\n" + second.content)
        }
        return JsonArray(parts(first) + parts(second))
    }

    private fun parts(content: JsonElement): List<JsonElement> = when (content) {
        is JsonArray -> content
        else -> listOf(buildJsonObject {
            put("type", "text")
            put("text", content)
        })
    }
}
