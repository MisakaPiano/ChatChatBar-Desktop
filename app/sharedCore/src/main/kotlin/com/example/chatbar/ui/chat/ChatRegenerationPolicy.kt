package com.example.chatbar.ui.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole

fun ChatMessage.isRetryableGenerationError(): Boolean =
    role == MessageRole.SYSTEM && displayContent.trimStart().startsWith("错误:")

private fun ChatMessage.participatesInRegenerationOrdering(): Boolean =
    displayContent.isNotBlank() || images.isEmpty()

fun latestRegenerableAssistantMessageId(messages: List<ChatMessage>): String? =
    messages
        .asReversed()
        .firstOrNull { message ->
            message.participatesInRegenerationOrdering() && !message.isRetryableGenerationError()
        }
        ?.takeIf { message -> message.role == MessageRole.ASSISTANT && message.displayContent.isNotBlank() }
        ?.id

fun regenerationTargetAssistantMessageId(
    messages: List<ChatMessage>,
    selectedMessageId: String?
): String? {
    val selected = messages.firstOrNull { it.id == selectedMessageId } ?: return null
    val assistantId = latestRegenerableAssistantMessageId(messages) ?: return null
    if (selected.id == assistantId) return assistantId
    if (!selected.isRetryableGenerationError()) return null

    val latestOrderingMessage = messages
        .asReversed()
        .firstOrNull(ChatMessage::participatesInRegenerationOrdering)
    return assistantId.takeIf { latestOrderingMessage?.id == selected.id }
}

/** The same direct-action target resolution used by Android ChatViewModel. */
fun resolveRegenerationTarget(selected: ChatMessage, nearby: List<ChatMessage>): ChatMessage? = when {
    selected.role == MessageRole.ASSISTANT && selected.displayContent.isNotBlank() -> selected
    selected.isRetryableGenerationError() -> regenerationTargetAssistantMessageId(nearby, selected.id)
        ?.let { id -> nearby.firstOrNull { it.id == id } }
    else -> null
}
