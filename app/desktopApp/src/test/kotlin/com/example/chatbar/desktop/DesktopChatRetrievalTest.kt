package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.prompt.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopChatRetrievalTest {
    private val config = EmbeddingConfig("default", "test", "https://invalid.example/v1", "synthetic", "test", 2)
    private val card = CharacterCard.create("Synthetic").copy(customDocuments = listOf(
        DocumentInfo("doc", "doc.txt", "documents/doc.txt", "txt", 1, ragStatus = "INDEXED")))
    private val session = ChatSession.create(card.id, "Synthetic")
    private val user = ChatMessage.create(session.id, MessageRole.USER, "Where is the museum?")
    private val model = ModelConfig(id = "synthetic", displayName = "Synthetic", baseUrl = "https://invalid.example/v1",
        apiKey = "synthetic", modelName = "synthetic", createdAt = 1)
    private val inputs = DesktopFakeChatInputs(effectiveContextWindowSize = 2, ragInjectionMode = "STANDARD", retrievalModel = model)
    private var requests = 0
    private var vectors = 0
    private var answer = """{"t":["museum"],"q":["museum"],"e":[]}"""
    private val text = object : MemoryRagTextClient {
        override suspend fun completeTextStreaming(taskContext: MemoryRagTask, messages: List<ChatApiMessage>,
            modelConfig: ModelConfig, disableThinking: Boolean, isolatedTaskParameters: Boolean,
            responseFormatJson: Boolean, onDelta: (String) -> Unit): String {
            requests++; assertEquals(MemoryRagTaskKind.RETRIEVAL_PLAN, taskContext.kind)
            assertEquals(model, modelConfig); return answer
        }
    }
    private fun chunk(id: String, type: ChunkSourceType) = VectorChunk(id, type, "source", content = "The museum is near the park.",
        embedding = listOf(1f, 0f), metadata = mapOf("sourceLabel" to id), createdAt = 1)
    private fun service(documents: suspend (CharacterCard, DesktopRagEmbeddingIdentity) -> List<VectorChunk> = { _, _ -> listOf(chunk("doc", ChunkSourceType.DOCUMENT)) },
        memory: suspend (String, Set<String>, DesktopRagEmbeddingIdentity) -> List<VectorChunk> = { _, _, _ -> listOf(chunk("memory", ChunkSourceType.CHAT_MEMORY)) }) = DesktopChatRetrieval(
        { config }, { AppSettings(docRagTopK = 1, memoryRagTopK = 1) }, documents, memory,
        { selected, _ -> assertEquals(config, selected); vectors++; listOf(1f, 0f) }, text)

    @Test fun `OFF readonly and fake never resolve credentials or send requests`() = runBlocking {
        val service = DesktopChatRetrieval({ error("credential read") }, { error("settings read") },
            { _, _ -> error("document read") }, { _, _, _ -> error("memory read") }, { _, _ -> error("embedding") }, text)
        assertTrue(service.retrieve(card, session, user, listOf(user), inputs.copy(ragInjectionMode = "OFF"), false).cards.isEmpty())
        assertTrue(service.retrieve(card, session, user, listOf(user), inputs, true).cards.isEmpty())
        assertTrue(service.retrieve(card, session, user, listOf(user), inputs.copy(retrievalModel = null), false).cards.isEmpty())
        assertEquals(0, requests)
    }
    @Test fun `split sources each receive their own recall quota and active message exclusion`() = runBlocking {
        val result = service(memory = { id, active, _ ->
            assertEquals(session.id, id); assertTrue(user.id in active); listOf(chunk("memory", ChunkSourceType.CHAT_MEMORY))
        }).retrieve(card, session, user, listOf(user), inputs, false)
        assertEquals(listOf(ChunkSourceType.DOCUMENT, ChunkSourceType.CHAT_MEMORY), result.cards.map { it.type })
        assertEquals(1, requests); assertEquals(1, vectors)
    }
    @Test fun `empty inventory and intentionally empty plan do not send an embedding request`() = runBlocking {
        assertTrue(service({ _, _ -> emptyList() }, { _, _, _ -> emptyList() }).retrieve(card, session, user, listOf(user), inputs, false).cards.isEmpty())
        assertEquals(0, requests)
        answer = """{"t":[],"q":[],"e":[]}"""
        assertTrue(service().retrieve(card, session, user, listOf(user), inputs, false).cards.isEmpty())
        assertEquals(1, requests); assertEquals(0, vectors)
    }
    @Test fun `planning failure stays visible and source change invalidates completed retrieval`() = runBlocking {
        answer = "invalid synthetic output"
        assertNotNull(service().retrieve(card, session, user, listOf(user), inputs, false).notice)
        var reads = 0
        assertFailsWith<IllegalStateException> { service(documents = { _, _ ->
            reads++; listOf(chunk("doc$reads", ChunkSourceType.DOCUMENT))
        }).retrieve(card, session, user, listOf(user), inputs, false) }
        Unit
    }
}
