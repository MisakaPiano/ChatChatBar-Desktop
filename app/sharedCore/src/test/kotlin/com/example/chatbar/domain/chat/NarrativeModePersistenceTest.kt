package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class NarrativeModePersistenceTest {
    private val json = Json { encodeDefaults = true }
    private fun message() = ChatMessage("m", "s", MessageRole.ASSISTANT, "old", createdAt = 1, updatedAt = 1)
    private fun session() = ChatSession("s", "card", "title", createdAt = 1, updatedAt = 1)

    @Test fun `legacy missing modes default to focus while explicit advance survives strict serialization`() {
        val legacy = json.decodeFromString(ChatMessage.serializer(),
            """{"id":"m","sessionId":"s","role":"ASSISTANT","content":"old","createdAt":1,"updatedAt":1}""")
        assertEquals(ChatNarrativeMode.FOCUS, legacy.narrativeMode)
        assertTrue(legacy.alternativeNarrativeModes.isEmpty())
        val explicit = legacy.copy(narrativeMode = ChatNarrativeMode.ADVANCE)
        assertEquals(explicit, json.decodeFromString(ChatMessage.serializer(), json.encodeToString(ChatMessage.serializer(), explicit)))
        assertFalse(json.encodeToString(ChatMessage.serializer(), legacy).contains("narrativeMode"))
        assertFails { json.decodeFromString(ChatMessage.serializer(),
            json.encodeToString(ChatMessage.serializer(), explicit).replace("ADVANCE", "FUTURE_MODE")) }
    }

    @Test fun `candidate selection append prune and edit keep the corresponding mode and stable identity`() {
        val old = message()
        val appended = MessageAlternativeVersionPolicy.append(old, "new", "v2", narrativeMode = ChatNarrativeMode.ADVANCE)
        assertEquals(listOf(ChatNarrativeMode.FOCUS, ChatNarrativeMode.ADVANCE), appended.alternativeNarrativeModes)
        val first = MessageAlternativeVersionPolicy.select(appended, 0)
        assertEquals(ChatNarrativeMode.FOCUS, first.narrativeMode)
        assertEquals(old.id, first.currentAlternativeVersionId)
        val edited = MessageAlternativeVersionPolicy.editCurrentContent(first, "edited")
        assertEquals(appended.alternativeNarrativeModes, edited.alternativeNarrativeModes)
        assertEquals(listOf("edited", "new"), edited.alternatives)
        val pruned = MessageAlternativeVersionPolicy.append(edited, "third", "v3", maxVersions = 1,
            narrativeMode = ChatNarrativeMode.ADVANCE)
        assertEquals(listOf("v3"), pruned.alternativeVersionIds)
        assertEquals(listOf(ChatNarrativeMode.ADVANCE), pruned.alternativeNarrativeModes)
    }

    @Test fun `legacy candidate gaps mean focus and copying retains each saved version mode`() {
        val legacy = message().copy(alternatives = listOf("a", "b"), alternativeVersionIds = listOf("v1", "v2"),
            currentAlternativeIndex = 1, narrativeMode = ChatNarrativeMode.ADVANCE,
            alternativeNarrativeModes = listOf(ChatNarrativeMode.ADVANCE))
        val normalized = MessageAlternativeVersionPolicy.normalize(legacy)
        assertEquals(listOf(ChatNarrativeMode.ADVANCE, ChatNarrativeMode.FOCUS), normalized.alternativeNarrativeModes)
        assertEquals(ChatNarrativeMode.FOCUS, normalized.narrativeMode)
        val policy = SessionCopyPolicy("copy", setOf("m"))
        val copy = policy.message(normalized, "copy")
        assertEquals(normalized.alternativeVersionIds, copy.alternativeVersionIds)
        assertEquals(normalized.alternativeNarrativeModes, copy.alternativeNarrativeModes)
        assertEquals(ChatNarrativeMode.ADVANCE,
            policy.session(session().copy(narrativeMode = ChatNarrativeMode.ADVANCE), "copy", "copy", 2).narrativeMode)
    }

    @Test fun `slot capture and restoration retain narrative mode without replacing unrelated session identity`() {
        val source = session().copy(narrativeMode = ChatNarrativeMode.ADVANCE)
        val slot = SaveSlotSessionPolicy.create(source, "saved", null, null, SaveSlotImagePolicy.NONE, false)
        assertEquals(ChatNarrativeMode.ADVANCE, slot.narrativeMode)
        val restored = SaveSlotSessionPolicy.restore(session().copy(title = "keep title"), slot, null)
        assertEquals(ChatNarrativeMode.ADVANCE, restored.narrativeMode)
        assertEquals("keep title", restored.title)
        assertEquals(slot, json.decodeFromString(SaveSlot.serializer(), json.encodeToString(SaveSlot.serializer(), slot)))
    }

    @Test fun `old whole session snapshots cannot undo next reply mode but explicit restore can`() = runBlocking {
        val root = Files.createTempDirectory("narrative-repository-")
        try {
            val repository = ChatRepository(JsonFileStorage(root))
            val old = repository.createSession(session())
            repository.setNarrativeMode(old.id, ChatNarrativeMode.ADVANCE)
            repository.updateSession(old.copy(title = "changed by old snapshot"))
            val current = assertNotNull(repository.getSession(old.id))
            assertEquals(ChatNarrativeMode.ADVANCE, current.narrativeMode)
            assertEquals("changed by old snapshot", current.title)
            repository.updateSession(old, restoreNarrativeMode = true)
            assertEquals(ChatNarrativeMode.FOCUS, repository.getSession(old.id)!!.narrativeMode)
            assertEquals(ChatNarrativeMode.FOCUS, ChatRepository(JsonFileStorage(root)).getSession(old.id)!!.narrativeMode)
        } finally {
            check(root.fileName.toString().startsWith("narrative-repository-"))
            check(root.toFile().deleteRecursively())
        }
    }
}
