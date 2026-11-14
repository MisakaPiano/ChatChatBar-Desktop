package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX

internal data class DesktopSessionImageOccurrence(
    val messageId: String, val imageIndex: Int, val reference: String,
    val role: MessageRole, val createdAt: Long, val alternativeCount: Int
) {
    val key: Pair<String, Int> get() = messageId to imageIndex
}

internal data class DesktopSessionImageGallerySnapshot(val sessionId: String, val title: String,
    val occurrences: List<DesktopSessionImageOccurrence>, val messageCount: Int, val omittedCount: Int)

/** Images belong to messages, shared by their text alternatives. Never scan unrelated media or restore archives. */
internal class DesktopSessionImageGallery(private val chats: ChatRepository) {
    private data class MessageProjection(val id: String, val orderKey: Long, val createdAt: Long,
        val role: MessageRole, val alternatives: Int, val images: List<String>)

    suspend fun read(sessionId: String): DesktopSessionImageGallerySnapshot {
        val session = requireNotNull(chats.getSession(sessionId)) { "会话已不存在" }
        val messages = chats.mapMessagesReadOnlyStrict(sessionId) { message ->
            MessageProjection(message.id, message.orderKey, message.createdAt, message.role,
                message.alternatives.size.coerceAtLeast(1), message.images)
        }.sortedWith(compareBy<MessageProjection> { it.orderKey }.thenBy { it.createdAt }.thenBy { it.id })
        var omitted = 0
        val occurrences = buildList {
            messages.forEach { message -> message.images.forEachIndexed { index, reference ->
                if (reference.startsWith(OMITTED_SAVE_SLOT_IMAGE_PREFIX)) omitted++
                else add(DesktopSessionImageOccurrence(message.id, index, reference, message.role, message.createdAt, message.alternatives))
            } }
        }
        return DesktopSessionImageGallerySnapshot(sessionId, session.title, occurrences, messages.size, omitted)
    }
}
