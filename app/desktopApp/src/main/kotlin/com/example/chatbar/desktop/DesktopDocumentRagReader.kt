package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.rag.ChunkingEngine
import com.example.chatbar.domain.rag.VectorSearchEngine
import java.nio.file.Path
import java.security.MessageDigest
import java.util.PriorityQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Identity of the externally supplied query vector. No credentials or embedding requests. */
internal data class DesktopRagEmbeddingIdentity(val configId: String, val key: String, val dimensions: Int) {
    companion object {
        // Official 1.4.1 RagManager.embeddingKey: dimensions are validated separately.
        fun from(config: EmbeddingConfig) = DesktopRagEmbeddingIdentity(config.id,
            ragDigest("${config.baseUrl.trimEnd('/')}|${config.modelName}"), config.dimensions)
    }
}

internal data class DesktopDocumentRagMatch(
    val chunkId: String, val characterCardId: String, val documentId: String,
    val sourceLabel: String, val content: String, val similarity: Float, val embeddingKey: String,
)

internal sealed interface DesktopDocumentRagResult {
    data class Available(val matches: List<DesktopDocumentRagMatch>, val scannedFiles: Long,
        val peakRetainedCandidates: Int) : DesktopDocumentRagResult
    /** Deliberately contains no raw exception, path, document content or credential. */
    data class Unavailable(val reason: Reason) : DesktopDocumentRagResult
    enum class Reason { INVALID_QUERY, OWNERSHIP, CHARACTER_CHANGED, INVENTORY, DOCUMENT, EMBEDDING }
}

/** Verified document inventory shared by read-only diagnostics and production retrieval.
 * Uses the container's one storage and coordinator. Exclusive is a read snapshot barrier against
 * cooperative root writers, not indexing/repair. No normal-operation-to-exclusive upgrade.
 * One bounded document plan and one decoded vector file at a time; at most topK output bodies.
 */
internal class DesktopDocumentRagReader(
    private val root: Path,
    private val storage: JsonFileStorage,
    private val coordinator: DesktopDataOperationCoordinator,
    private val search: VectorSearchEngine = VectorSearchEngine(),
    private val chunking: ChunkingEngine = ChunkingEngine(),
    private val onChunkRead: suspend () -> Unit = {}, // Deterministic read/cancellation test seam; no production writer.
    private val onPlanGeneration: () -> Unit = {}, // Confirms budget refusal precedes shared plan allocation.
    private val maintenance: DesktopAudioMaintenance? = null,
) {
    private val source = DesktopDocumentRagSource(root)

    suspend fun retrieve(expected: CharacterCard, query: List<Float>, identity: DesktopRagEmbeddingIdentity,
        topK: Int = 5, threshold: Float = .7f): DesktopDocumentRagResult {
        if (topK !in 0..1000 || !threshold.isFinite() || threshold !in -1f..1f ||
            identity.configId.isBlank() || !identity.key.matches(Regex("[0-9a-f]{64}")) ||
            identity.dimensions !in 1..65536 || query.size != identity.dimensions || !source.validVector(query))
            return DesktopDocumentRagResult.Unavailable(DesktopDocumentRagResult.Reason.INVALID_QUERY)
        return try {
            val vector = query.toList()
            barrier {
                if (!DesktopDataRootOwnership.isHeld(root)) reject(DesktopDocumentRagResult.Reason.OWNERSHIP)
                withContext(Dispatchers.IO) { inspect(expected, vector, identity, topK, threshold) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (rejected: DesktopDocumentRagSource.Rejected) { DesktopDocumentRagResult.Unavailable(rejected.reason) }
        catch (_: Exception) { DesktopDocumentRagResult.Unavailable(DesktopDocumentRagResult.Reason.INVENTORY) }
    }

    /** Production reads share normal admission with playback. Index publication is exclusive;
     * the request owner revalidates these exact candidates after network work. */
    suspend fun candidates(expected: CharacterCard, identity: DesktopRagEmbeddingIdentity): List<VectorChunk> = coordinator.withNormalOperation {
        check(DesktopDataRootOwnership.isHeld(root))
        require(identity.dimensions in 1..65536)
        val result = ArrayList<VectorChunk>()
        var retainedBytes = 0L
        withContext(Dispatchers.IO) {
            inspect(expected, List(identity.dimensions) { if (it == 0) 1f else 0f }, identity, 0, 1f,
                indexedOnly = true, accept = { chunk ->
                    retainedBytes += chunk.embedding.size * 4L + chunk.content.length * 2L + 1024L +
                        chunk.metadata.entries.sumOf { (it.key.length + it.value.length) * 2L }
                    check(result.size < 20_000 && retainedBytes <= 64L * 1024 * 1024) { "检索候选超过内存预算" }
                    result.add(chunk)
                })
        }
        result.sortedBy { it.id }
    }

    private suspend fun <T> barrier(work: suspend () -> T): T =
        if (maintenance == null) coordinator.withExclusiveMaintenance { work() }
        else maintenance.withMaintenance(false, { coordinator.isRestartRequired }) {
            coordinator.withExclusiveMaintenance { work() }
        }

    private suspend fun inspect(expected: CharacterCard, query: List<Float>, identity: DesktopRagEmbeddingIdentity,
        topK: Int, threshold: Float, indexedOnly: Boolean = false,
        accept: (VectorChunk) -> Unit = {}): DesktopDocumentRagResult.Available {
        var card: CharacterCard? = null
        storage.visitEntitiesStrict("character_cards", CharacterCard.serializer(), validatePath = source::safeWindowsPath) { id, value ->
            source.requireIdentity(id, value.id)
            if (id == expected.id) card = value
        }
        if (card != expected) reject(DesktopDocumentRagResult.Reason.CHARACTER_CHANGED)
        val documents = requireNotNull(card).customDocuments
        if (documents.size > 64 || documents.map { it.id }.distinct().size != documents.size)
            reject(DesktopDocumentRagResult.Reason.DOCUMENT)
        // Tie order is canonical chunk ID order, independent of filesystem enumeration order.
        // Equivalent to shared search on a chunk-ID-sorted input (whose sort is stable).
        val ranking = compareByDescending<DesktopDocumentRagMatch> { it.similarity }.thenBy { it.chunkId }
        val best = PriorityQueue(ranking.reversed())
        var peak = 0
        var scanned = 0L
        val ids = documents.map { it.id }.toSet()
        // First validate the whole namespace, including records outside the selected source.
        storage.visitEntitiesStrict(VectorChunkStorageContract.ENTITY_TYPE, VectorChunk.serializer(),
            validatePath = source::safeWindowsPath) { id, chunk ->
            onChunkRead()
            source.requireIdentity(id, chunk.id)
            if (chunk.sourceId.isBlank() || chunk.embedding.size > 65536 || !source.validVector(chunk.embedding))
                reject(DesktopDocumentRagResult.Reason.EMBEDDING)
            if (chunk.sourceType == ChunkSourceType.DOCUMENT && chunk.sourceId == expected.id &&
                chunk.metadata["originalDocId"] !in ids) reject(DesktopDocumentRagResult.Reason.DOCUMENT)
            scanned++
        }
        for (doc in documents) {
            currentCoroutineContext().ensureActive()
            if (indexedOnly && doc.ragStatus != DocumentRagStatus.INDEXED.name) continue
            source.requireIdentity(doc.id, doc.id)
            if (doc.ragStatus != DocumentRagStatus.INDEXED.name || doc.ragChunkCount !in 1..DesktopDocumentRagSource.MAX_PLAN_CHUNKS ||
                doc.fileName.length > DesktopDocumentRagSource.MAX_FILE_NAME_CHARS)
                reject(DesktopDocumentRagResult.Reason.DOCUMENT)
            val content = source.readDocument(doc.filePath)
            val hash = ragDigest(content)
            if (doc.contentHash != hash || doc.indexedHash != hash) reject(DesktopDocumentRagResult.Reason.DOCUMENT)
            source.checkPlanBudget(content, doc.fileName)
            // Exact official chunk text AND metadata; verifies duplicate/missing blocks as a multiset.
            onPlanGeneration()
            val plan = chunking.chunkDocument(content, doc.id, doc.fileName)
            if (plan.size != doc.ragChunkCount) reject(DesktopDocumentRagResult.Reason.DOCUMENT)
            val remaining = plan.groupingBy { it }.eachCount().toMutableMap()
            storage.visitEntitiesStrict(VectorChunkStorageContract.ENTITY_TYPE, VectorChunk.serializer(),
                validatePath = source::safeWindowsPath) { _, chunk ->
                if (chunk.sourceType != ChunkSourceType.DOCUMENT || chunk.sourceId != expected.id ||
                    chunk.metadata["originalDocId"] != doc.id) return@visitEntitiesStrict
                if (chunk.messageId != null || chunk.metadata.keys != DOCUMENT_KEYS ||
                    chunk.metadata["fileName"] != doc.fileName || chunk.metadata["contentHash"] != hash)
                    reject(DesktopDocumentRagResult.Reason.DOCUMENT)
                if (chunk.metadata["embeddingKey"] != identity.key || chunk.embedding.size != identity.dimensions)
                    reject(DesktopDocumentRagResult.Reason.EMBEDDING)
                val piece = chunk.content to mapOf("documentId" to chunk.metadata.getValue("documentId"),
                    "sourceLabel" to chunk.metadata.getValue("sourceLabel"))
                val count = remaining[piece] ?: reject(DesktopDocumentRagResult.Reason.DOCUMENT)
                if (count == 1) remaining.remove(piece) else remaining[piece] = count - 1
                accept(chunk)
                val score = search.cosineSimilarity(query, chunk.embedding)
                if (!score.isFinite()) reject(DesktopDocumentRagResult.Reason.EMBEDDING)
                if (topK > 0 && score >= threshold) {
                    val match = DesktopDocumentRagMatch(chunk.id, expected.id, doc.id,
                        chunk.metadata.getValue("sourceLabel"), chunk.content, score, identity.key)
                    if (best.size < topK) best.add(match)
                    else if (ranking.compare(match, best.peek()) < 0) { best.poll(); best.add(match) }
                    peak = maxOf(peak, best.size)
                }
            }
            if (remaining.isNotEmpty()) reject(DesktopDocumentRagResult.Reason.DOCUMENT)
        }
        return DesktopDocumentRagResult.Available(best.toList().sortedWith(ranking), scanned, peak)
    }

    private fun reject(reason: DesktopDocumentRagResult.Reason): Nothing = throw DesktopDocumentRagSource.Rejected(reason)
    companion object {
        private val DOCUMENT_KEYS = setOf("documentId", "sourceLabel", "fileName", "originalDocId", "contentHash", "embeddingKey")
    }
}

internal fun ragDigest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
