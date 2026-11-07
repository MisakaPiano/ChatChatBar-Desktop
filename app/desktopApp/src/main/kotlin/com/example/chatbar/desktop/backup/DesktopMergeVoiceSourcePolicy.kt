package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import kotlinx.serialization.json.JsonObject

/** Equal version strings alone are not evidence that a recording belongs to incoming text. */
internal object DesktopMergeVoiceSourcePolicy {
    class Source(val record: DesktopMergeRecord, val owner: DesktopMergeRecord,
        val target: DesktopMergeRecord, val targetSide: DesktopMergeDestinationPlan.Side)

    fun canRetainLive(type: String, value: JsonObject, ownerValue: JsonObject, targetValue: JsonObject): Boolean {
        val owner = backupJson.decodeFromJsonElement(ChatMessage.serializer(), ownerValue)
        val target = backupJson.decodeFromJsonElement(ChatMessage.serializer(), targetValue)
        val before = MessageAlternativeVersionPolicy.versions(owner)
        val after = MessageAlternativeVersionPolicy.versions(target)
        val version = when (type) {
            "generated_voice_messages" -> {
                val voice = backupJson.decodeFromJsonElement(GeneratedVoiceMessage.serializer(), value)
                check(voice.messageId == owner.id && voice.sessionId == owner.sessionId) { "MERGE_RETAINED_VOICE_OWNER" }
                voice.messageVersionId
            }
            "voice_anchor_states" -> {
                val state = backupJson.decodeFromJsonElement(VoiceAnchorState.serializer(), value)
                check(state.messageId == owner.id && state.sessionId == owner.sessionId) { "MERGE_RETAINED_ANCHOR_OWNER" }
                val content = state.messageVersionId?.let { id -> before.firstOrNull { it.id == id }?.content }
                    ?: MessageAlternativeVersionPolicy.activeVersion(owner).content
                if (state.displayContentSnapshot != content) return false
                state.messageVersionId
            }
            else -> error("MERGE_RETAINED_VOICE_TYPE")
        }
        if (owner.id != target.id || owner.sessionId != target.sessionId) return false
        if (version == null) return before == after &&
            MessageAlternativeVersionPolicy.activeVersion(owner) == MessageAlternativeVersionPolicy.activeVersion(target)
        val original = before.singleOrNull { it.id == version } ?: return false
        return after.singleOrNull { it.id == version } == original
    }
}
