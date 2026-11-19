package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class VoiceRepositoryPersistenceTest {
    @Test fun `uncertain legacy ownership survives reload and later source edits without authorizing pruning`() = runBlocking {
        val root = Files.createTempDirectory("uncertain-version-repository-")
        try {
            val repo = VoiceMessageRepository(JsonFileStorage(root), strictReads = true)
            val message = ChatMessage("message", "session", MessageRole.ASSISTANT, "甲：『相同』\n删版叙述",
                alternatives = listOf("甲：『相同』\n删版叙述", "甲：『相同』\n保留叙述"), alternativeVersionIds = listOf("a", "b"),
                currentAlternativeVersionId = "a", createdAt = 1, updatedAt = 1)
            val old = GeneratedVoiceMessage.create("session", message.id, null, null, 0, "DIALOGUE", "甲", "相同",
                "相同", "相同", "person", "甲", FishAudioVoiceBinding("synthetic", "Synthetic"), "synthetic", "synthetic-not-opened.mp3", 100, 100)
            val json = Json { encodeDefaults = true }
            assertFalse("desktopVersionOwnershipUncertain" in json.parseToJsonElement(json.encodeToString(GeneratedVoiceMessage.serializer(), old)).jsonObject)
            repo.save(old)
            repo.placementsForMessage(message)
            val cold = VoiceMessageRepository(JsonFileStorage(root), strictReads = true)
            assertTrue(assertNotNull(cold.get(old.id)).desktopVersionOwnershipUncertain)
            val replacement = MessageAlternativeVersionPolicy.remove(message, "a")!!
            assertTrue(cold.deleteForRemovedVersions(message, replacement).isEmpty())
            cold.placementsForMessage(replacement)
            val durable = assertNotNull(VoiceMessageRepository(JsonFileStorage(root), strictReads = true).get(old.id))
            assertTrue(durable.desktopVersionOwnershipUncertain)
            assertEquals("a", durable.messageVersionId)
            assertEquals(old.id, assertNotNull(cold.delete(old.id)).id) // Explicit recording deletion still owns it.
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `explicit removed version pruning migrates old ownership and retains sibling and unrelated history after restart`() = runBlocking {
        val root = Files.createTempDirectory("removed-version-repository-")
        try {
            val repo = VoiceMessageRepository(JsonFileStorage(root), strictReads = true)
            val message = ChatMessage("message", "session", MessageRole.ASSISTANT, "Alice：『删除』",
                alternatives = listOf("Alice：『删除』", "Alice：『保留』"), alternativeVersionIds = listOf("a", "b"),
                currentAlternativeVersionId = "a", createdAt = 1, updatedAt = 1)
            val a = repo.ensureAnchorsForVersion(message, "a", message.alternatives[0]).first()
            val b = repo.ensureAnchorsForVersion(message, "b", message.alternatives[1]).first()
            repo.ensureAnchorsForVersion(message, "history", "Alice：『历史』")
            fun voice(version: String?, text: String, anchor: String?) = GeneratedVoiceMessage.create("session", message.id,
                version, anchor, 0, "DIALOGUE", "Alice", text, text, text, "alice", "Alice",
                FishAudioVoiceBinding("synthetic", "Synthetic"), "synthetic", "synthetic-not-opened.mp3", 100, 100)
            val legacy = voice(null, "删除", a.anchor.id)
            val sibling = voice("b", "保留", b.anchor.id)
            val history = voice("history", "历史", null)
            listOf(legacy, sibling, history).forEach { repo.save(it) }
            val replacement = MessageAlternativeVersionPolicy.remove(message, "a")!!
            assertEquals(listOf(legacy.id), repo.deleteForRemovedVersions(message, replacement).map { it.id })
            val cold = VoiceMessageRepository(JsonFileStorage(root), strictReads = true)
            assertEquals(setOf(sibling.id, history.id), cold.listForMessage(message.id).map { it.id }.toSet())
            assertEquals(setOf("b", "history"), cold.snapshotAnchorsForMessages(setOf(message.id)).map { it.messageVersionId }.toSet())
            assertTrue(cold.deleteForRemovedVersions(message, replacement).isEmpty())
            val edited = MessageAlternativeVersionPolicy.editCurrentContent(replacement, "Alice：『继续保留』")
            assertTrue(cold.deleteForRemovedVersions(replacement, edited).isEmpty())
            assertEquals(setOf(sibling.id, history.id), cold.listForMessage(message.id).map { it.id }.toSet())
            assertFailsWith<IllegalArgumentException> { cold.deleteForRemovedVersions(message, replacement.copy(sessionId = "foreign")) }
            assertEquals(2, cold.listForMessage(message.id).size)
        } finally { root.toFile().deleteRecursively() }
    }

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
