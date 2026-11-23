package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MemoryCompressionEvent
import com.example.chatbar.data.local.entity.MemoryCompressionKind
import com.example.chatbar.data.local.entity.MemorySessionState
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.MemoryRepository
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.chat.ContextWindowManager
import com.example.chatbar.domain.lanSync.LanSyncOperationGate
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MemoryCompressionEventDrainTest {
    @Test fun openingAnOlderChatWithoutEventsDoesNotCreateMemoryOrRewriteOriginalSourceFields() = fixture { f ->
        val session = f.createSession()
        val message = ChatMessage(
            id = "legacy-message",
            sessionId = session.id,
            role = MessageRole.USER,
            content = "合成历史消息",
            createdAt = 2,
            updatedAt = 2,
        )
        // A legacy record predates persisted source turns. Ordinary addMessage would assign one.
        val storageId = "${session.id}_${message.id}"
        f.storage.saveEntity("chat_messages", storageId, message, ChatMessage.serializer())
        val messagePath = f.root.resolve("entities/chat_messages/$storageId.json")
        val originalMessage = Files.readAllBytes(messagePath)
        val originalSession = Files.readAllBytes(f.sessionPath)
        assertNull(f.memory.getState(session.id))

        assertTrue(f.service.consumeCompressionEvents(session.id).isEmpty())

        assertNull(f.memory.getState(session.id))
        assertTrue(Files.notExists(f.statePath))
        assertContentEquals(originalSession, Files.readAllBytes(f.sessionPath))
        assertContentEquals(originalMessage, Files.readAllBytes(messagePath))
        assertEquals(message, f.chats.getMessagesReadOnly(session.id).single())
    }

    @Test fun existingEventsDrainExactlyOnceWithoutChangingTheRestOfTheSavedState() = fixture { f ->
        val session = f.createSession()
        val original = f.pendingState(session.id)
        f.memory.saveState(original)

        assertEquals(original.pendingCompressionEvents, f.service.consumeCompressionEvents(session.id))
        val drained = assertNotNull(f.memory.getState(session.id))
        assertTrue(drained.updatedAt > original.updatedAt)
        assertEquals(original.copy(
            pendingCompressionEvents = emptyList(),
            revision = original.revision + 1,
            updatedAt = drained.updatedAt,
        ), drained)

        val consumedBytes = Files.readAllBytes(f.statePath)
        assertTrue(f.service.consumeCompressionEvents(session.id).isEmpty())
        assertContentEquals(consumedBytes, Files.readAllBytes(f.statePath))
    }

    @Test fun anOldPageCannotConsumeStaleMemoryAfterTheDurableSessionWasRemoved() = fixture { f ->
        val session = f.createSession()
        val stale = f.pendingState(session.id)
        f.memory.saveState(stale)
        val original = Files.readAllBytes(f.statePath)
        // External native removal can win before the ordinary page's cached session is refreshed.
        f.gate.exclusive { Files.delete(f.sessionPath) }
        assertEquals(JsonFileStorage.EntityReadResult.Missing, f.chats.readSessionDurable(session.id))

        assertTrue(f.service.consumeCompressionEvents(session.id).isEmpty())
        assertContentEquals(original, Files.readAllBytes(f.statePath))
        assertEquals(stale, f.memory.getState(session.id))
        assertEquals(JsonFileStorage.EntityReadResult.Missing, f.chats.readSessionDurable(session.id))
    }

    private fun fixture(run: suspend (Fixture) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-memory-event-drain-").toRealPath()
        try { run(Fixture(root)) } finally { check(root.toFile().deleteRecursively()) }
    }

    private class Fixture(val root: Path) {
        private val sessionId = "synthetic-chat"
        val gate = LanSyncOperationGate()
        val storage = JsonFileStorage(root, gate)
        val chats = ChatRepository(storage)
        val memory = MemoryRepository(storage, strictReads = true)
        val sessionPath = root.resolve("entities/chat_sessions/$sessionId.json")
        val statePath = root.resolve("entities/memory_states/$sessionId.json")
        private val noAi = object : MemoryAiClient {
            override suspend fun episode(model: ModelConfig, renderedTurns: String, summaryPromptMaxChars: Int,
                onStreamingSummary: ((String) -> Unit)?, validate: (EpisodeResponse) -> Unit): EpisodeResponse =
                error("Compression notifications must not generate an episode")

            override suspend fun compression(model: ModelConfig, kind: MemoryCompressionKind, forcedConsumedChildIds: List<String>,
                renderedChildren: String, onStreamingSummary: ((String) -> Unit)?,
                validate: (CompressionResponse) -> Unit): CompressionResponse =
                error("Compression notifications must not call a model")

            override suspend fun head(model: ModelConfig, mode: MemoryHeadUpdateMode, throughT: Long,
                currentHead: String, archive: String, sourceTurns: String, validate: (HeadResponse) -> Unit): HeadResponse =
                error("Compression notifications must not regenerate HEAD")
        }
        val service = LongTermMemoryService(chats, memory, SettingsRepository(storage), noAi, ContextWindowManager())

        suspend fun createSession(): ChatSession =
            chats.createSession(ChatSession(sessionId, "synthetic-card", "合成旧会话", longTermMemoryEnabled = false,
                createdAt = 1, updatedAt = 1))

        fun pendingState(sessionId: String): MemorySessionState = MemorySessionState(
            sessionId = sessionId,
            pendingCompressionEvents = listOf(MemoryCompressionEvent("synthetic-event", "synthetic-transaction",
                MemoryCompressionKind.EPISODE_TO_ARC, consumedCount = 3, createdAt = 3)),
            episodeCompressionPromptDeclined = true,
            eraCompressionsSincePrompt = 2,
            memoryWasEnabled = false,
            revision = 7,
            createdAt = 2,
            updatedAt = 3,
        )
    }
}
