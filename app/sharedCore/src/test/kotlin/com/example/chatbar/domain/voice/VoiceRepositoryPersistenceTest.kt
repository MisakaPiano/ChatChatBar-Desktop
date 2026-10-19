package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class VoiceRepositoryPersistenceTest {
    @Test fun `multiple takes and alternative versions retain independent snapshots through restart and edits`() = runBlocking {
        val root = Files.createTempDirectory("s3-official-repo-")
        val repo = VoiceMessageRepository(JsonFileStorage(root), strictReads = true)
        val message = MessageAlternativeVersionPolicy.normalize(ChatMessage(id = "message", sessionId = "session",
            role = MessageRole.ASSISTANT, content = "Alice：『第一句』", alternatives = listOf("Alice：『第一句』", "Alice：『第二句』"),
            createdAt = 1, updatedAt = 1))
        val anchored = repo.ensureAnchors(message).first()
        fun take() = GeneratedVoiceMessage.create("session", message.id,
            MessageAlternativeVersionPolicy.activeVersionId(message), anchored.anchor.id, 0, "DIALOGUE", "Alice", "第一句",
            "第一句", "第一句", "alice", "Alice", FishAudioVoiceBinding(referenceId = "old", title = "old snapshot"),
            "s2.1-pro-free", "synthetic-owned.mp3", 100, 100)
        val first = take(); val second = take(); repo.save(first); repo.save(second)
        assertEquals(2, repo.listForCurrentVersion(message).size)
        val alternative = MessageAlternativeVersionPolicy.select(message, 1)
        assertEquals(0, repo.listForCurrentVersion(alternative).size)
        assertEquals(2, repo.listForCurrentVersion(message).size)
        val restarted = VoiceMessageRepository(JsonFileStorage(root), strictReads = true)
        assertEquals(listOf(first, second).sortedBy { it.id }, restarted.listForMessage(message.id).sortedBy { it.id })
        val edited = MessageAlternativeVersionPolicy.editCurrentContent(message, "Alice：『第一句补充』")
        restarted.ensureAnchors(edited)
        assertEquals(setOf(first.id, second.id), restarted.listForMessage(message.id).map { it.id }.toSet())
        assertTrue(restarted.listForMessage(message.id).all { it.voice.referenceId == "old" })
        restarted.deleteForSession("session")
        assertEquals(0, VoiceMessageRepository(JsonFileStorage(root), strictReads = true).listForSession("session").size)
    }
}
