package com.example.chatbar.domain.prompt

import com.example.chatbar.domain.chat.ChatApiMessage
import kotlinx.serialization.json.*

object AuxiliaryMessageAssembler {
    fun assemble(messages: List<ChatApiMessage>, preservesInputRoles: Boolean): List<ChatApiMessage> {
        require(messages.isNotEmpty()) { "AI 任务没有输入" }
        val prefix = listOf(
            ChatApiMessage.text("assistant", AuxiliaryPromptAuthority.GENERAL_FIRST_ACK_ASSISTANT_PROMPT),
            ChatApiMessage.text("user", AuxiliaryPromptAuthority.GENERAL_CREATIVE_CONTRACT_USER_PROMPT),
            ChatApiMessage.text("assistant", AuxiliaryPromptAuthority.GENERAL_CONTRACT_CONFIRMATION_ASSISTANT_PROMPT)
        )
        val tail = listOf(
            ChatApiMessage.text("assistant", AuxiliaryPromptAuthority.GENERAL_CONTEXT_APPROVAL_ASSISTANT_PROMPT),
            ChatApiMessage.text("assistant", AuxiliaryPromptAuthority.GENERAL_POST_USER_ACK_ASSISTANT_PROMPT),
            ChatApiMessage.text("user", AuxiliaryPromptAuthority.GENERAL_POST_USER_IDENTITY_REMINDER_USER_PROMPT)
        )
        // Check the entire envelope, not phrases appearing inside ordinary input.
        if (messages.size >= 8 && messages.first().role == "system" &&
            messages.subList(4, messages.size - 3).all { it.role == "user" || it.role == "assistant" } &&
            messages.subList(1, 4) == prefix && messages.takeLast(3) == tail &&
            systemHasEnvelope(messages.first().content)
        ) return messages
        val system = mergeContents(
            listOf(JsonPrimitive(AuxiliaryPromptAuthority.GENERAL_SYSTEM_PROMPT)) +
                messages.filter { it.role == "system" }.map { it.content } +
                JsonPrimitive(AuxiliaryPromptAuthority.GENERAL_CREATOR_IDENTITY_SYSTEM_PROMPT)
        )
        val inputs = messages.filter { it.role != "system" }
        // Image design continues real planner/revision history instead of quoting it inside a user input.
        if (preservesInputRoles && inputs.isNotEmpty()) {
            return listOf(ChatApiMessage("system", system)) + prefix + inputs + tail
        }
        val input = when {
            inputs.isEmpty() -> JsonPrimitive(AuxiliaryPromptAuthority.GENERAL_TASK_EMPTY_INPUT)
            inputs.size == 1 && inputs.single().role == "user" -> inputs.single().content
            else -> mergeContents(inputs.flatMapIndexed { index, message ->
                listOf(JsonPrimitive(AuxiliaryPromptAuthority.generalTaskInputHeading(index, message.role)), message.content)
            })
        }
        return listOf(ChatApiMessage("system", system)) + prefix + ChatApiMessage("user", input) + tail
    }

    private fun systemHasEnvelope(content: JsonElement): Boolean {
        val first: String?
        val last: String?
        if (content is JsonArray) {
            first = ((content.firstOrNull() as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
            last = ((content.lastOrNull() as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
        } else {
            first = (content as? JsonPrimitive)?.contentOrNull
            last = first
        }
        return first?.startsWith(AuxiliaryPromptAuthority.GENERAL_SYSTEM_PROMPT) == true &&
            last?.endsWith(AuxiliaryPromptAuthority.GENERAL_CREATOR_IDENTITY_SYSTEM_PROMPT) == true
    }

    private fun mergeContents(contents: List<JsonElement>): JsonElement {
        if (contents.all { it is JsonPrimitive }) {
            return JsonPrimitive(contents.joinToString("\n\n") { (it as JsonPrimitive).content })
        }
        return JsonArray(contents.flatMap { content ->
            when (content) {
                is JsonArray -> content.toList()
                is JsonPrimitive -> listOf(buildJsonObject {
                    put("type", "text")
                    put("text", content.content)
                })
                else -> listOf(content)
            }
        })
    }

    fun addedText(messages: List<ChatApiMessage>, preservesInputRoles: Boolean = false): String = buildList {
        add(AuxiliaryPromptAuthority.GENERAL_SYSTEM_PROMPT)
        add(AuxiliaryPromptAuthority.GENERAL_CREATOR_IDENTITY_SYSTEM_PROMPT)
        add(AuxiliaryPromptAuthority.GENERAL_FIRST_ACK_ASSISTANT_PROMPT)
        add(AuxiliaryPromptAuthority.GENERAL_CREATIVE_CONTRACT_USER_PROMPT)
        add(AuxiliaryPromptAuthority.GENERAL_CONTRACT_CONFIRMATION_ASSISTANT_PROMPT)
        add(AuxiliaryPromptAuthority.GENERAL_CONTEXT_APPROVAL_ASSISTANT_PROMPT)
        add(AuxiliaryPromptAuthority.GENERAL_POST_USER_ACK_ASSISTANT_PROMPT)
        add(AuxiliaryPromptAuthority.GENERAL_POST_USER_IDENTITY_REMINDER_USER_PROMPT)
        val inputs = messages.filter { it.role != "system" }
        if (inputs.isEmpty()) add(AuxiliaryPromptAuthority.GENERAL_TASK_EMPTY_INPUT)
        else if (!preservesInputRoles && (inputs.size != 1 || inputs.single().role != "user")) {
            inputs.forEachIndexed { index, message -> add(AuxiliaryPromptAuthority.generalTaskInputHeading(index, message.role)) }
        }
    }.joinToString("\n")
}
