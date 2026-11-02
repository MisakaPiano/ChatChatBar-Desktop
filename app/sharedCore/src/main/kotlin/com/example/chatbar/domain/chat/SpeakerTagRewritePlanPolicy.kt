package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.SpeakerTagRename
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/** Fingerprint all persisted message fields so recovery never overwrites a later source edit. */
object SpeakerTagRewritePlanPolicy {
    private val json = Json { encodeDefaults = true }
    fun fingerprint(message: ChatMessage): String = MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(ChatMessage.serializer(), message).toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    fun rewrite(message: ChatMessage, renames: List<SpeakerTagRename>, updatedAt: Long): ChatMessage {
        val content = renameRoleplaySpeakerMarkers(message.content, renames)
        val alternatives = message.alternatives.map { renameRoleplaySpeakerMarkers(it, renames) }
        return if (content == message.content && alternatives == message.alternatives) message
            else message.copy(content = content, alternatives = alternatives, updatedAt = updatedAt)
    }
}
