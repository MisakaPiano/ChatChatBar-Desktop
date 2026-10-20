package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.chat.roleplayBlockMessageId
import com.example.chatbar.domain.chat.roleplayScreenshotBlockIds
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale

fun ChatMessage.isSelectableForChatScreenshot(
    assistantSegmentedBubblesEnabled: Boolean = true,
    voiceIds: List<String> = emptyList()
): Boolean =
    roleplayScreenshotBlockIds(this, assistantSegmentedBubblesEnabled, voiceIds).isNotEmpty()

fun toggleChatScreenshotSelection(
    currentIds: Set<String>,
    blockId: String,
    selectableIds: Set<String>
): Set<String> {
    if (blockId !in selectableIds) return currentIds
    val next = LinkedHashSet(currentIds)
    if (blockId in next) {
        next.remove(blockId)
    } else {
        next.add(blockId)
    }
    return next
}

fun toggleChatScreenshotMessageSelection(
    currentIds: Set<String>,
    messageBlockIds: Collection<String>,
    selectableIds: Set<String>
): Set<String> {
    val selectableMessageIds = messageBlockIds.filterTo(LinkedHashSet()) { it in selectableIds }
    if (selectableMessageIds.isEmpty()) return currentIds
    val next = LinkedHashSet(currentIds)
    if (selectableMessageIds.all(next::contains)) {
        next.removeAll(selectableMessageIds)
    } else {
        next.addAll(selectableMessageIds)
    }
    return next
}

fun cleanChatScreenshotSelection(
    currentIds: Set<String>,
    messages: List<ChatMessage>,
    assistantSegmentedBubblesEnabled: Boolean = true,
    voiceIdsByMessage: Map<String, List<String>> = emptyMap()
): Set<String> {
    val selectableIds = messages
        .flatMap { message ->
            roleplayScreenshotBlockIds(
                message,
                assistantSegmentedBubblesEnabled,
                voiceIdsByMessage[message.id].orEmpty()
            )
        }
        .toSet()
    return currentIds.filterTo(LinkedHashSet()) { it in selectableIds }
}

fun orderedChatScreenshotMessages(
    messages: List<ChatMessage>,
    selectedBlockIds: Set<String>,
    assistantSegmentedBubblesEnabled: Boolean = true,
    voiceIdsByMessage: Map<String, List<String>> = emptyMap()
): List<ChatMessage> =
    messages.filter { message ->
        message.isSelectableForChatScreenshot(assistantSegmentedBubblesEnabled, voiceIdsByMessage[message.id].orEmpty()) &&
            selectedBlockIds.any { roleplayBlockMessageId(it) == message.id }
    }

fun buildChatScreenshotFileName(
    title: String,
    timestampMillis: Long = System.currentTimeMillis()
): String {
    val safeTitle = sanitizeChatScreenshotFileSegment(title).ifBlank { "聊天" }
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestampMillis))
    return "ChatBar_${safeTitle}_$stamp.png"
}

internal fun sanitizeChatScreenshotFileSegment(value: String): String =
    value
        .trim()
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .replace(Regex("\\s+"), "_")
        .take(40)
        .trim('_', '.', ' ')

/** Presentation only: preserve repository order and identities, including irregular histories. */
internal data class DesktopScreenshotGroup(val user: ChatMessage?, val messages: List<ChatMessage>)

internal fun desktopScreenshotGroups(messages: List<ChatMessage>): List<DesktopScreenshotGroup> {
    val groups = mutableListOf<MutableList<ChatMessage>>()
    messages.forEach { message ->
        if (message.role == MessageRole.USER || groups.isEmpty()) {
            groups.add(mutableListOf(message))
        } else {
            groups.last().add(message)
        }
    }
    return groups.map { DesktopScreenshotGroup(it.first().takeIf { m -> m.role == MessageRole.USER }, it.toList()) }
}

internal data class DesktopScreenshotTreeState(
    val selected: Set<String>, val expanded: Set<String> = emptySet(),
) {
    fun toggleExpanded(messageId: String) = copy(expanded = if (messageId in expanded) expanded - messageId else expanded + messageId)
    fun selectAll(all: Set<String>) = copy(selected = all.toSet())
    fun clear() = copy(selected = emptySet())
    fun invert(all: Set<String>) = copy(selected = all - selected)
}
