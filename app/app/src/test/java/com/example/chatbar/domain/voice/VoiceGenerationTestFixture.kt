package com.example.chatbar.domain.voice

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.VoiceMessageRepository
import java.io.File
import java.util.UUID

internal class VoiceGenerationTestFixture(val files: File) {
    private val context = object : ContextWrapper(null) { override fun getFilesDir(): File = files }
    val storage = JsonFileStorage(context)
    val chats = ChatRepository(storage)
    val voices = VoiceMessageRepository(storage)
    val removed = mutableListOf<String>()
    val committer = VoiceGenerationCommitter(chats, voices, deleteUncommittedAudio = ::deleteAudio)
    lateinit var message: ChatMessage
    val snapshot get() = VoiceGenerationSourceSnapshot.capture(message)

    suspend fun initialize() {
        chats.createSession(ChatSession(id = "session", characterCardId = "card", title = "synthetic", createdAt = 1, updatedAt = 1))
        message = chats.addMessage(ChatMessage(id = "old", sessionId = "session", role = MessageRole.ASSISTANT,
            content = "<n=\"Synthetic\"/>[Completed old dialogue]()", currentAlternativeVersionId = "version1", createdAt = 1, updatedAt = 1))
        voices.initialize()
    }

    fun audio(): File = File(files, "${UUID.randomUUID()}.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    fun deleteAudio(path: String) { removed += path; File(path).delete() }
    fun voice(path: String = audio().absolutePath, anchor: String? = null) = GeneratedVoiceMessage(
        id = UUID.randomUUID().toString(), sessionId = message.sessionId, messageId = message.id,
        messageVersionId = snapshot.versionId, anchorId = anchor, sourceOrder = 0,
        sourceSegmentKind = "whole_message", sourceSpeakerName = "Synthetic", sourceText = "Completed old dialogue",
        taggedText = "Completed old dialogue", characterId = "person", characterName = "Synthetic",
        voice = FishAudioVoiceBinding("synthetic-reference", "Synthetic"), fishModelId = "s1",
        audioPath = path, durationMs = 100, byteLength = 3, createdAt = 1, updatedAt = 1)
}
