package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.domain.rag.ChunkingEngine
import java.nio.file.Path
import kotlinx.coroutines.*

internal data class DesktopDocumentIndexOutcome(val chunks: Int, val contentHash: String)

/** Network work never owns normal/exclusive data admission. Commit rechecks the complete card,
 * public embedding identity and owned source, then publishes vectors and status under one barrier.
 * Paused audio yields retryable busy before requesting the exclusive barrier.
 */
internal class DesktopDocumentIndexService(
    private val root: Path,
    private val storage: JsonFileStorage,
    private val characters: CharacterRepository,
    private val coordinator: DesktopDataOperationCoordinator,
    private val tasks: DesktopTaskRuntime,
    private val gateway: DesktopIndexEmbeddingGateway,
    private val maintenance: DesktopAudioMaintenance? = null,
    private val journal: DesktopIndexCommitJournal = DesktopIndexCommitJournal(root),
    private val verifyConfiguration: suspend () -> Unit = {},
) {
    private val source = DesktopDocumentRagSource(root)
    private data class Snapshot(val card: CharacterCard, val document: DocumentInfo,
        val config: EmbeddingConfig, val hash: String, val plan: List<Pair<String, Map<String, String>>>)

    suspend fun index(characterId: String, documentId: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): DesktopDocumentIndexOutcome = tasks.withDocumentPreparation(characterId, documentId) {
        source.requireIdentity(characterId, characterId); source.requireIdentity(documentId, documentId)
        val snapshot = barrier { readSnapshot(characterId, documentId) }
        val identity = DesktopRagEmbeddingIdentity.from(snapshot.config)
        val changes = linkedMapOf<String, ByteArray?>()
        var bytes = 0L
        val batchSize = minOf(16, 65536 / identity.dimensions)
        snapshot.plan.chunked(batchSize).forEachIndexed { batchIndex, pieces ->
            currentCoroutineContext().ensureActive()
            val metadata = pieces.map { (text, meta) -> text to (meta + mapOf(
                "originalDocId" to documentId, "fileName" to snapshot.document.fileName,
                "contentHash" to snapshot.hash, "embeddingKey" to identity.key)) }
            val vectors = gateway.embed(DesktopIndexEmbeddingBatch(identity, batchIndex * batchSize, metadata))
            check(vectors.size == metadata.size) { "向量数量与文档分段不一致；旧索引已保留" }
            vectors.forEachIndexed { index, vector ->
                check(vector.size == identity.dimensions && source.validVector(vector)) { "无效向量；旧索引已保留" }
                val chunk = VectorChunk.create(ChunkSourceType.DOCUMENT, characterId,
                    metadata[index].first, vector, metadata = metadata[index].second)
                val encoded = storage.json.encodeToString(VectorChunk.serializer(), chunk).toByteArray(Charsets.UTF_8)
                bytes += encoded.size
                check(bytes <= DesktopIndexCommitJournal.MAX_BYTES) { "索引超过本轮内存预算；请拆分文档" }
                changes["entities/vector_chunks/${chunk.id}.json"] = encoded
            }
            onProgress(changes.size, snapshot.plan.size)
        }
        currentCoroutineContext().ensureActive()
        barrier {
            verifyConfiguration()
            val current = readSnapshot(characterId, documentId)
            check(current.card == snapshot.card && current.document == snapshot.document &&
                current.config == snapshot.config && current.hash == snapshot.hash) { "文档或模型配置已变化；旧索引已保留，请重试" }
            val oldIds = mutableListOf<String>()
            storage.visitEntitiesStrict("vector_chunks", VectorChunk.serializer(), validatePath = source::safeWindowsPath) { id, chunk ->
                source.requireIdentity(id, chunk.id)
                if (chunk.sourceType == ChunkSourceType.DOCUMENT && chunk.sourceId == characterId &&
                    (chunk.metadata["originalDocId"] == documentId ||
                        chunk.metadata["originalDocId"] !in snapshot.card.customDocuments.map { it.id })) oldIds += id
            }
            check(oldIds.size <= DesktopDocumentRagSource.MAX_PLAN_CHUNKS)
            oldIds.forEach { changes["entities/vector_chunks/$it.json"] = null }
            val documents = snapshot.card.customDocuments.map { doc -> if (doc.id != documentId) doc else
                doc.copy(contentHash = snapshot.hash, indexedHash = snapshot.hash,
                    ragStatus = DocumentRagStatus.INDEXED.name, ragChunkCount = snapshot.plan.size,
                    ragIndexedAt = System.currentTimeMillis(), ragError = null) }
            val done = documents.count { it.ragStatus == DocumentRagStatus.INDEXED.name }
            val next = snapshot.card.copy(customDocuments = documents,
                ragIndexStatus = if (done == documents.size) RagIndexStatus.COMPLETE.name else RagIndexStatus.NOT_INDEXED.name,
                ragIndexDone = done, ragIndexTotal = documents.size, ragIndexMessage = null,
                ragIndexedAt = System.currentTimeMillis())
            changes["entities/character_cards/$characterId.json"] = storage.json.encodeToString(CharacterCard.serializer(), next).toByteArray(Charsets.UTF_8)
            try {
                withContext(NonCancellable + Dispatchers.IO) { journal.commit(changes) }
                // Read from disk, rebuilding both storage and repository projections after commit.
                characters.refreshFromStorage()
            } catch (failure: Throwable) {
                if (journal.hasPending()) requireRestart()
                // A cache failure after durable commit cannot authorize continued stale operation.
                val durable = characters.readDurable(characterId)
                if (durable is JsonFileStorage.EntityReadResult.Valid && durable.value == next) requireRestart()
                throw failure
            }
        }
        DesktopDocumentIndexOutcome(snapshot.plan.size, snapshot.hash)
    }

    private suspend fun readSnapshot(characterId: String, documentId: String): Snapshot = withContext(Dispatchers.IO) {
        check(DesktopDataRootOwnership.isHeld(root))
        var card: CharacterCard? = null
        storage.visitEntitiesStrict("character_cards", CharacterCard.serializer(), validatePath = source::safeWindowsPath) { id, value ->
            source.requireIdentity(id, value.id); if (id == characterId) card = value
        }
        val actual = requireNotNull(card) { "角色卡不存在" }
        check(actual.customDocuments.size <= 64 && actual.customDocuments.map { it.id }.distinct().size == actual.customDocuments.size)
        val document = actual.customDocuments.single { it.id == documentId }
        val config = when (val read = storage.readEntityStrict("embedding_model_config", "default", EmbeddingConfig.serializer())) {
            is JsonFileStorage.EntityReadResult.Valid -> read.value
            else -> error("请先配置向量模型")
        }
        // Credentials are separately hydrated only by the network adapter. Never hash/store them in the journal.
        check(config.apiKey.isBlank() && config.id == "default" && config.dimensions in 1..65536 &&
            config.baseUrl.isNotBlank() && config.modelName.isNotBlank()) { "向量模型配置不符合受保护凭据合同" }
        val content = source.readDocument(document.filePath)
        source.checkPlanBudget(content, document.fileName)
        val plan = ChunkingEngine().chunkDocument(content, document.id, document.fileName)
        check(plan.isNotEmpty() && plan.size <= DesktopDocumentRagSource.MAX_PLAN_CHUNKS)
        Snapshot(actual, document, config, ragDigest(content), plan)
    }

    private suspend fun <T> barrier(work: suspend DesktopExclusiveMaintenanceScope.() -> T): T =
        if (maintenance == null) coordinator.withExclusiveMaintenance(work)
        else maintenance.withMaintenance(false, { coordinator.isRestartRequired }) { coordinator.withExclusiveMaintenance(work) }
}
