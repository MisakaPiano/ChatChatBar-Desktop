package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.ContextWindowManager
import com.example.chatbar.domain.rag.*
import com.example.chatbar.ui.chat.messageIds
import java.nio.file.Path
import kotlinx.coroutines.*

/** Derived, recoverable raw-turn index. No Archive/HEAD node is an embedding source. */
internal class DesktopChatMemoryIndex(
    private val root: Path,
    private val storage: JsonFileStorage,
    private val coordinator: DesktopDataOperationCoordinator,
    private val maintenance: DesktopAudioMaintenance,
    private val configuration: suspend () -> EmbeddingConfig,
    private val gateway: DesktopEmbeddingGateway,
    private val journal: DesktopIndexCommitJournal = DesktopIndexCommitJournal(root),
) {
    private val source = DesktopDocumentRagSource(root)

    suspend fun rebuild(sessionId: String, contextWindow: Int): Int {
        val config = configuration()
        val identity = DesktopRagEmbeddingIdentity.from(config)
        val snapshot = barrier { messages(sessionId) }
        val active = ContextWindowManager().getRecentMessages(snapshot, contextWindow.coerceAtLeast(0)).mapTo(hashSetOf()) { it.id }
        val turns = ChatMemoryIndexPolicy.buildIndexableTurns(snapshot, active)
        val existing = barrier { chunks(sessionId) }
        val replacement = linkedMapOf<String, ByteArray?>()
        val keep = hashSetOf<String>()
        var bytes = 0L
        var indexed = 0
        for (turn in turns) {
            currentCoroutineContext().ensureActive()
            val texts = ChatMemoryIndexPolicy.contentsForIndex(turn)
            check(texts.size <= 2048) { "单轮历史超过索引预算" }
            val expected = texts.mapIndexed { index, text -> chunk(turn, sessionId, text, index, texts.size, identity, emptyList()) }
            keep += expected.map { it.id }
            if (expected.all { next -> existing.any { old -> sameSource(old, next) &&
                    old.embedding.size == identity.dimensions && source.validVector(old.embedding) } }) continue
            val vectors = ArrayList<List<Float>>()
            val batchSize = minOf(16, 65536 / identity.dimensions)
            texts.chunked(batchSize).forEachIndexed { batch, input ->
                vectors += gateway.pinned(config).embed(DesktopIndexEmbeddingBatch(identity, batch * batchSize,
                    input.map { it to emptyMap() }))
            }
            check(vectors.size == expected.size)
            expected.forEachIndexed { i, candidate ->
                check(vectors[i].size == identity.dimensions && source.validVector(vectors[i]))
                val encoded = storage.json.encodeToString(VectorChunk.serializer(), candidate.copy(embedding = vectors[i])).toByteArray()
                bytes += encoded.size
                check(bytes <= DesktopIndexCommitJournal.MAX_BYTES && replacement.size < 8000) { "历史索引超过发布预算" }
                replacement["entities/vector_chunks/${candidate.id}.json"] = encoded
            }
            indexed += expected.size
        }
        // Only obsolete automatic raw-turn chunks are removed; manually added fragments remain intact.
        existing.filter { ChatMemoryIndexPolicy.isAutomaticChunk(it) && it.id !in keep }.forEach {
            replacement["entities/vector_chunks/${it.id}.json"] = null
        }
        if (replacement.isEmpty()) return 0
        barrier {
            gateway.verify(config)
            check(messages(sessionId) == snapshot && chunks(sessionId) == existing) { "历史消息或索引已变化，请重试" }
            try { withContext(NonCancellable + Dispatchers.IO) { journal.commit(replacement) } }
            catch (failure: Throwable) { if (journal.hasPending()) requireRestart(); throw failure }
        }
        return indexed
    }

    suspend fun candidates(sessionId: String, active: Set<String>, identity: DesktopRagEmbeddingIdentity): List<VectorChunk> = coordinator.withNormalOperation {
        check(DesktopDataRootOwnership.isHeld(root))
        val before = messages(sessionId)
        val turns = ChatMemoryIndexPolicy.buildIndexableTurns(before, active)
        val expected = turns.flatMap { turn ->
            val texts = ChatMemoryIndexPolicy.contentsForIndex(turn)
            texts.mapIndexed { index, text -> chunk(turn, sessionId, text, index, texts.size, identity, emptyList()) }
        }.associateBy { it.id }
        val result = chunks(sessionId).filter { value ->
            value.messageIds().none { it in active } && value.metadata["indexMode"] != "memory_node" &&
                !ChatMemoryIndexPolicy.needsAutomaticRebuild(value) && value.metadata["embeddingKey"] == identity.key &&
                value.embedding.size == identity.dimensions && source.validVector(value.embedding) &&
                if (ChatMemoryIndexPolicy.isAutomaticChunk(value)) expected[value.id]?.let { sameSource(value, it) } == true
                else value.messageIds().isNotEmpty() && value.messageIds().all { id -> turns.any { id in it.messageIds } }
        }
        check(messages(sessionId) == before) { "历史消息在读取期间发生变化，请重试" }
        result
    }

    private fun sameSource(a: VectorChunk, b: VectorChunk) = a.id == b.id && a.sourceType == b.sourceType &&
        a.sourceId == b.sourceId && a.messageId == b.messageId && a.content == b.content && a.metadata == b.metadata

    private fun chunk(turn: ChatMemoryTurn, session: String, text: String, index: Int, count: Int,
        identity: DesktopRagEmbeddingIdentity, vector: List<Float>) = VectorChunk(
        id = chatMemoryChunkId(session, turn.identityKey, index), sourceType = ChunkSourceType.CHAT_MEMORY,
        sourceId = session, content = text, embedding = vector, messageId = turn.anchorMessage.id,
        metadata = buildMap {
            put("sessionId", session); put("messageIds", turn.messageIds.joinToString(","))
            put("messageTime", turn.anchorMessage.createdAt.toString()); put("indexMode", ChatMemoryIndexPolicy.INDEX_MODE)
            put("contentVersion", ChatMemoryIndexPolicy.CONTENT_VERSION); put("chunkIndex", index.toString())
            put("chunkCount", count.toString()); put("embeddingKey", identity.key); put("sourceHash", ragDigest(text))
            turn.sourceTurnId?.let { put("sourceTurnId", it) }; turn.sourceTurnOrder?.let { put("sourceTurnOrder", it.toString()) }
        }, createdAt = System.currentTimeMillis())

    private suspend fun messages(session: String): List<ChatMessage> {
        source.requireIdentity(session, session)
        val result = ArrayList<ChatMessage>()
        var bytes = 0L
        storage.visitEntitiesStrict("chat_messages", ChatMessage.serializer(), validatePath = source::safeWindowsPath) { id, msg ->
            check(id == "${msg.sessionId}_${msg.id}")
            if (msg.sessionId == session) {
                bytes += storage.json.encodeToString(ChatMessage.serializer(), msg).length * 2L
                check(result.size < 20_000 && bytes <= 32L * 1024 * 1024) { "会话超过历史索引读取预算" }
                result += msg
            }
        }
        return result.sortedWith(ChatMessage.TimelineComparator)
    }

    private suspend fun chunks(session: String): List<VectorChunk> {
        val result = ArrayList<VectorChunk>()
        var bytes = 0L
        storage.visitEntitiesStrict("vector_chunks", VectorChunk.serializer(), validatePath = source::safeWindowsPath) { id, value ->
            source.requireIdentity(id, value.id)
            if (value.sourceType == ChunkSourceType.CHAT_MEMORY && value.sourceId == session) {
                bytes += value.embedding.size * 4L + value.content.length * 2L + 2048
                check(result.size < 20_000 && bytes <= 64L * 1024 * 1024) { "会话索引超过读取预算" }
                result += value
            }
        }
        return result.sortedBy { it.id }
    }

    private suspend fun <T> barrier(work: suspend DesktopExclusiveMaintenanceScope.() -> T): T =
        maintenance.withMaintenance(false, { coordinator.isRestartRequired }) {
            coordinator.withExclusiveMaintenance { check(DesktopDataRootOwnership.isHeld(root)); work() }
        }
}
