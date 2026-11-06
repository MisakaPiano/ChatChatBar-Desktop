package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatNarrativeMode
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy

/** Immutable admission target. Other saved versions may remain valid but never become this target. */
data class VoiceGenerationSourceSnapshot(
    val sessionId: String,
    val messageId: String,
    val versionId: String,
    val content: String,
    /** Mode belongs to this saved version, never the session's next-reply selection. */
    val narrativeMode: ChatNarrativeMode = ChatNarrativeMode.FOCUS
) {
    fun matches(message: ChatMessage?, requireVisible: Boolean = false): Boolean =
        message != null && message.sessionId == sessionId && message.id == messageId &&
            message.role == MessageRole.ASSISTANT &&
            MessageAlternativeVersionPolicy.versions(message).any {
                it.id == versionId && it.content == content && it.narrativeMode == narrativeMode
            } &&
            (!requireVisible || MessageAlternativeVersionPolicy.activeVersionId(message) == versionId)

    companion object {
        fun capture(message: ChatMessage): VoiceGenerationSourceSnapshot {
            require(message.role == MessageRole.ASSISTANT) { "只支持助手消息生成语音" }
            val version = MessageAlternativeVersionPolicy.activeVersion(message)
            return VoiceGenerationSourceSnapshot(message.sessionId, message.id, version.id, version.content, version.narrativeMode)
        }
    }
}
