package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.data.local.entity.DocumentRagStatus
import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.domain.rag.ChunkingEngine
import java.nio.ByteBuffer
import java.nio.file.Path
import java.nio.file.InvalidPathException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class DesktopIndexEmbeddingStore(val entityType: String) {
    SINGLETON("embedding_model_config"), LEGACY("embedding_configs")
}
internal data class DesktopIndexEmbeddingReference(val store: DesktopIndexEmbeddingStore = DesktopIndexEmbeddingStore.SINGLETON,
    val id: String = "default")

/** Only an explicitly supplied offline implementation. No credentials, HTTP client or retry. */
internal fun interface DesktopIndexEmbeddingGateway {
    suspend fun embed(batch: DesktopIndexEmbeddingBatch): List<List<Float>>
}
internal class DesktopIndexEmbeddingBatch(val identity: DesktopRagEmbeddingIdentity, val firstIndex: Int,
    val pieces: List<Pair<String, Map<String, String>>>) {
    override fun toString() = "DesktopIndexEmbeddingBatch(count=${pieces.size}, firstIndex=$firstIndex)"
}
internal enum class DesktopIndexPreparationStatus { PREPARED_ONLY, STALE, REJECTED, CANCELLED }
internal enum class DesktopIndexPreparationReason { SOURCE, INVENTORY, OWNERSHIP, EMBEDDING, ADMISSION, LIFECYCLE }
internal data class DesktopIndexPreparationSummary(val characterId: String, val documentId: String,
    val sourceStatus: String, val contentHash: String, val recordedContentHashMatches: Boolean?,
    val recordedIndexedHashMatches: Boolean?, val embedding: DesktopRagEmbeddingIdentity,
    val chunkCount: Int, val validatedBatches: Int, val peakBatchVectors: Int, val peakBatchFloats: Int,
    val orderedDigest: String)
internal data class DesktopIndexPreparationResult(val status: DesktopIndexPreparationStatus,
    val reason: DesktopIndexPreparationReason? = null, val summary: DesktopIndexPreparationSummary? = null) {
    val indexed = false
    val committed = false
}

/** Read-only preparation, not a proof-to-CAS transaction. No persisted staging or publication.
 * Caller owns structured work; the same application TaskRuntime registers it for close/drain.
 * Only a bounded official text plan survives across batches, never all document embeddings. */
internal class DesktopDocumentRagIndexPreparation(
    private val root: Path, private val storage: JsonFileStorage,
    private val coordinator: DesktopDataOperationCoordinator, private val tasks: DesktopTaskRuntime,
    private val gateway: DesktopIndexEmbeddingGateway,
    private val onPlanGeneration: () -> Unit = {},
    private val onSnapshotRead: suspend () -> Unit = {},
) {
    private val source = DesktopDocumentRagSource(root)
    private class Rejected(val reason: DesktopIndexPreparationReason) : Exception()
    private class Stale : Exception()
    private class Snapshot(val card: CharacterCard, val document: DocumentInfo, val config: EmbeddingConfig,
        val hash: String, val plan: List<Pair<String, Map<String, String>>>)

    suspend fun prepare(characterId: String, documentId: String,
        reference: DesktopIndexEmbeddingReference = DesktopIndexEmbeddingReference()): DesktopIndexPreparationResult = try {
        source.requireIdentity(characterId, characterId)
        source.requireIdentity(documentId, documentId)
        source.requireIdentity(reference.id, reference.id)
        if (reference.store == DesktopIndexEmbeddingStore.SINGLETON && reference.id != "default")
            reject(DesktopIndexPreparationReason.EMBEDDING)
        tasks.withDocumentPreparation(characterId, documentId) {
            val snapshot = snapshot(characterId, documentId, reference, generatePlan = true)
            val identity = DesktopRagEmbeddingIdentity.from(snapshot.config)
            val batchSize = minOf(MAX_BATCH_VECTORS, MAX_BATCH_FLOATS / identity.dimensions)
            val digest = MessageDigest.getInstance("SHA-256")
            val word = ByteBuffer.allocate(4)
            var batches = 0
            var peakVectors = 0
            var peakFloats = 0
            var first = 0
            while (first < snapshot.plan.size) {
                currentCoroutineContext().ensureActive()
                val pieces = snapshot.plan.subList(first, minOf(first + batchSize, snapshot.plan.size)).map { (text, meta) ->
                    text to (meta + mapOf("originalDocId" to documentId, "fileName" to snapshot.document.fileName,
                        "contentHash" to snapshot.hash, "embeddingKey" to identity.key))
                }
                val vectors = try { gateway.embed(DesktopIndexEmbeddingBatch(identity, first, pieces)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { reject(DesktopIndexPreparationReason.EMBEDDING) }
                currentCoroutineContext().ensureActive()
                if (vectors.size != pieces.size) reject(DesktopIndexPreparationReason.EMBEDDING)
                for ((offset, vector) in vectors.withIndex()) {
                    if (vector.size != identity.dimensions || !source.validVector(vector))
                        reject(DesktopIndexPreparationReason.EMBEDDING)
                    // Length-delimited deterministic summary; no raw text or vectors leave this service.
                    digest.update(word.putInt(0, first + offset).array())
                    updateDigest(digest, pieces[offset].first)
                    for ((key, value) in pieces[offset].second.toSortedMap()) {
                        updateDigest(digest, key); updateDigest(digest, value)
                    }
                    for (value in vector) digest.update(word.putInt(0, value.toRawBits()).array())
                }
                batches++
                peakVectors = maxOf(peakVectors, vectors.size)
                peakFloats = maxOf(peakFloats, vectors.size * identity.dimensions)
                first += pieces.size
            }
            // No normal operation is held across gateway suspension or upgraded into exclusive.
            snapshot(characterId, documentId, reference, generatePlan = false, expected = snapshot)
            currentCoroutineContext().ensureActive()
            DesktopIndexPreparationResult(DesktopIndexPreparationStatus.PREPARED_ONLY, summary = DesktopIndexPreparationSummary(
                characterId, documentId, snapshot.document.ragStatus, snapshot.hash,
                snapshot.document.contentHash?.let { it == snapshot.hash }, snapshot.document.indexedHash?.let { it == snapshot.hash },
                identity, snapshot.plan.size, batches, peakVectors, peakFloats,
                digest.digest().joinToString("") { "%02x".format(it) }))
        }
    } catch (cancelled: CancellationException) {
        // Caller cancellation remains cancellation; application-close cancellation is an explicit result
        // only if the observing caller itself is still active. No old prepared summary is returned.
        currentCoroutineContext().ensureActive()
        DesktopIndexPreparationResult(DesktopIndexPreparationStatus.CANCELLED)
    } catch (_: Stale) { DesktopIndexPreparationResult(DesktopIndexPreparationStatus.STALE) }
    catch (rejected: Rejected) { DesktopIndexPreparationResult(DesktopIndexPreparationStatus.REJECTED, rejected.reason) }
    catch (rejected: DesktopDocumentRagSource.Rejected) {
        DesktopIndexPreparationResult(DesktopIndexPreparationStatus.REJECTED,
            if (rejected.reason == DesktopDocumentRagResult.Reason.DOCUMENT) DesktopIndexPreparationReason.SOURCE else DesktopIndexPreparationReason.INVENTORY)
    } catch (_: DesktopTaskAdmissionException) {
        DesktopIndexPreparationResult(DesktopIndexPreparationStatus.REJECTED, DesktopIndexPreparationReason.ADMISSION)
    } catch (_: DesktopDataOperationRejectedException) {
        DesktopIndexPreparationResult(DesktopIndexPreparationStatus.REJECTED, DesktopIndexPreparationReason.LIFECYCLE)
    } catch (_: Exception) { DesktopIndexPreparationResult(DesktopIndexPreparationStatus.REJECTED, DesktopIndexPreparationReason.INVENTORY) }

    private suspend fun snapshot(characterId: String, documentId: String, reference: DesktopIndexEmbeddingReference,
        generatePlan: Boolean, expected: Snapshot? = null): Snapshot = coordinator.withExclusiveMaintenance {
        requireRoot()
        withContext(Dispatchers.IO) {
            var card: CharacterCard? = null
            storage.visitEntitiesStrict("character_cards", CharacterCard.serializer(), validatePath = source::safeWindowsPath) { id, value ->
                source.requireIdentity(id, value.id)
                if (id == characterId) card = value
            }
            val actual = card ?: throw Stale()
            if (actual.customDocuments.size > 64 || actual.customDocuments.map { it.id }.distinct().size != actual.customDocuments.size)
                reject(DesktopIndexPreparationReason.SOURCE)
            actual.customDocuments.forEach { source.requireIdentity(it.id, it.id) }
            val document = actual.customDocuments.singleOrNull { it.id == documentId } ?: throw Stale()
            if (document.ragStatus !in setOf(DocumentRagStatus.PENDING.name, DocumentRagStatus.INDEXED.name) ||
                document.fileName.length > DesktopDocumentRagSource.MAX_FILE_NAME_CHARS) reject(DesktopIndexPreparationReason.SOURCE)
            var config: EmbeddingConfig? = null
            storage.visitEntitiesStrict(reference.store.entityType, EmbeddingConfig.serializer(), validatePath = source::safeWindowsPath) { id, value ->
                source.requireIdentity(id, value.id)
                if (reference.store == DesktopIndexEmbeddingStore.SINGLETON && id != "default")
                    reject(DesktopIndexPreparationReason.INVENTORY)
                if (id == reference.id) config = value
            }
            val embedding = config ?: reject(DesktopIndexPreparationReason.EMBEDDING)
            if (embedding.dimensions !in 1..65536 || embedding.baseUrl.isBlank() || embedding.modelName.isBlank())
                reject(DesktopIndexPreparationReason.EMBEDDING)
            onSnapshotRead()
            val content = try { source.readDocument(document.filePath) }
                catch (_: InvalidPathException) { reject(DesktopIndexPreparationReason.SOURCE) }
            source.checkPlanBudget(content, document.fileName)
            val plan = if (generatePlan) {
                onPlanGeneration()
                ChunkingEngine().chunkDocument(content, document.id, document.fileName).also {
                    if (it.isEmpty() || it.size > DesktopDocumentRagSource.MAX_PLAN_CHUNKS) reject(DesktopIndexPreparationReason.SOURCE)
                }
            } else emptyList()
            currentCoroutineContext().ensureActive()
            requireRoot()
            val current = Snapshot(actual, document, embedding, ragDigest(content), plan)
            if (expected != null && (current.card != expected.card || current.document != expected.document ||
                    current.config != expected.config || current.hash != expected.hash)) throw Stale()
            current
        }
    }

    private fun requireRoot() {
        if (!DesktopDataRootOwnership.isHeld(root)) reject(DesktopIndexPreparationReason.OWNERSHIP)
        if (coordinator.state != DesktopDataOperationCoordinatorState.EXCLUSIVE) reject(DesktopIndexPreparationReason.LIFECYCLE)
    }
    private fun reject(reason: DesktopIndexPreparationReason): Nothing = throw Rejected(reason)
    private fun updateDigest(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
    }
    companion object {
        const val MAX_BATCH_VECTORS = 16
        const val MAX_BATCH_FLOATS = 65536
    }
}
