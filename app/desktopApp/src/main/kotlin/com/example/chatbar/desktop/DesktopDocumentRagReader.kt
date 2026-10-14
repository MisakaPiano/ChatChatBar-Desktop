package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.rag.ChunkingEngine
import com.example.chatbar.domain.rag.VectorSearchEngine
import com.sun.jna.platform.win32.Kernel32
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
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

/** Read-only P9-S1 boundary. Never wired into the chat planner, transport or prompt assembler.
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
) {
    suspend fun retrieve(expected: CharacterCard, query: List<Float>, identity: DesktopRagEmbeddingIdentity,
        topK: Int = 5, threshold: Float = .7f): DesktopDocumentRagResult {
        if (topK !in 0..1000 || !threshold.isFinite() || threshold !in -1f..1f ||
            identity.configId.isBlank() || !identity.key.matches(Regex("[0-9a-f]{64}")) ||
            identity.dimensions !in 1..65536 || query.size != identity.dimensions || !validVector(query))
            return DesktopDocumentRagResult.Unavailable(DesktopDocumentRagResult.Reason.INVALID_QUERY)
        return try {
            val vector = query.toList()
            coordinator.withExclusiveMaintenance {
                if (!DesktopDataRootOwnership.isHeld(root)) reject(DesktopDocumentRagResult.Reason.OWNERSHIP)
                withContext(Dispatchers.IO) { inspect(expected, vector, identity, topK, threshold) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (rejected: Rejected) { DesktopDocumentRagResult.Unavailable(rejected.reason) }
        catch (_: Exception) { DesktopDocumentRagResult.Unavailable(DesktopDocumentRagResult.Reason.INVENTORY) }
    }

    private suspend fun inspect(expected: CharacterCard, query: List<Float>, identity: DesktopRagEmbeddingIdentity,
        topK: Int, threshold: Float): DesktopDocumentRagResult.Available {
        var card: CharacterCard? = null
        storage.visitEntitiesStrict("character_cards", CharacterCard.serializer(), validatePath = ::safeWindowsPath) { id, value ->
            requireIdentity(id, value.id)
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
            validatePath = ::safeWindowsPath) { id, chunk ->
            onChunkRead()
            requireIdentity(id, chunk.id)
            if (chunk.sourceId.isBlank() || chunk.embedding.size > 65536 || !validVector(chunk.embedding))
                reject(DesktopDocumentRagResult.Reason.EMBEDDING)
            if (chunk.sourceType == ChunkSourceType.DOCUMENT && chunk.sourceId == expected.id &&
                chunk.metadata["originalDocId"] !in ids) reject(DesktopDocumentRagResult.Reason.DOCUMENT)
            scanned++
        }
        for (doc in documents) {
            currentCoroutineContext().ensureActive()
            requireIdentity(doc.id, doc.id)
            if (doc.ragStatus != DocumentRagStatus.INDEXED.name || doc.ragChunkCount !in 1..MAX_PLAN_CHUNKS ||
                doc.fileName.length > MAX_FILE_NAME_CHARS)
                reject(DesktopDocumentRagResult.Reason.DOCUMENT)
            val content = readDocument(doc.filePath)
            val hash = ragDigest(content)
            if (doc.contentHash != hash || doc.indexedHash != hash) reject(DesktopDocumentRagResult.Reason.DOCUMENT)
            checkPlanBudget(content, doc.fileName)
            // Exact official chunk text AND metadata; verifies duplicate/missing blocks as a multiset.
            onPlanGeneration()
            val plan = chunking.chunkDocument(content, doc.id, doc.fileName)
            if (plan.size != doc.ragChunkCount) reject(DesktopDocumentRagResult.Reason.DOCUMENT)
            val remaining = plan.groupingBy { it }.eachCount().toMutableMap()
            storage.visitEntitiesStrict(VectorChunkStorageContract.ENTITY_TYPE, VectorChunk.serializer(),
                validatePath = ::safeWindowsPath) { _, chunk ->
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

    private fun readDocument(reference: String): String {
        val relative = Path.of(reference)
        // Android absolute paths/assets require a separate resource migration contract.
        if (relative.isAbsolute || relative.nameCount != 2 || relative.getName(0).toString() != "documents" ||
            relative.any { it.toString() in setOf(".", "..") } ||
            reference.any { it in "<>:\"|?*" || it.code < 32 } ||
            relative.last().toString().let { it.endsWith('.') || it.endsWith(' ') })
            reject(DesktopDocumentRagResult.Reason.DOCUMENT)
        val path = root.toAbsolutePath().normalize().resolve(relative).normalize()
        var ancestor = path.root
        for (part in path) {
            ancestor = ancestor.resolve(part)
            val attr = Files.readAttributes(ancestor, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (attr.isSymbolicLink || attr.isOther || if (ancestor == path) !attr.isRegularFile else !attr.isDirectory)
                reject(DesktopDocumentRagResult.Reason.DOCUMENT)
            safeWindowsPath(ancestor)
        }
        val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_DOCUMENT_BYTES + 1) }
        if (bytes.size > MAX_DOCUMENT_BYTES) reject(DesktopDocumentRagResult.Reason.DOCUMENT)
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

    /** Admission estimate only, never an alternative chunker. Mirrors the official heading
     * recognition so every possible source path is bounded before chunkDocument allocates it.
     * For body B > 700, adjacent greedy merged chunks have combined length >= 700; hence
     * 2 * floor(B / 700) + 2 conservatively bounds their count. Splitting/trim removes characters
     * and joining consumes existing separators; overlap adds at most 70 chars per final chunk.
     * All arithmetic is Long over the existing 1 MiB input bound; no plan strings are built here.
     */
    private fun checkPlanBudget(content: String, fileName: String) {
        val headings = ArrayList<String>(6)
        val fileLabelLength = if (fileName.isBlank()) "未命名文档".length.toLong() else fileName.length.toLong()
        var labelLength = fileLabelLength
        var headingCount = 0L
        var sectionCount = 0L
        var bodyLength = 0L
        var bodyPresent = false
        var chunkBound = 0L
        var characterBound = 0L
        fun accountBody(length: Long, label: Long) {
            val count = if (length <= 700L) 1L else 2L * (length / 700L) + 2L
            sectionCount++
            chunkBound += count
            // 770 body/overlap + 5 for the official source prefix and newline.
            characterBound += count * (label + 775L)
            if (sectionCount > MAX_PLAN_SECTIONS || chunkBound > MAX_PLAN_CHUNKS ||
                characterBound > MAX_PLAN_CHARS) reject(DesktopDocumentRagResult.Reason.DOCUMENT)
        }
        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            val markdown = MARKDOWN_HEADING.find(trimmed)
            val plain = markdown == null && trimmed.isNotBlank() && trimmed.length <= 40 &&
                (trimmed.endsWith(':') || trimmed.endsWith('：') || PLAIN_HEADING.matches(trimmed))
            if (markdown != null || plain) {
                if (bodyPresent) accountBody(bodyLength, labelLength)
                bodyLength = 0L; bodyPresent = false
                if (++headingCount > MAX_PLAN_SECTIONS) reject(DesktopDocumentRagResult.Reason.DOCUMENT)
                val level = markdown?.groupValues?.get(1)?.length ?: 1
                val title = markdown?.groupValues?.get(2)?.trim() ?: trimmed.trimEnd(':', '：')
                if (title.length > MAX_HEADING_CHARS) reject(DesktopDocumentRagResult.Reason.DOCUMENT)
                while (headings.size >= level) headings.removeAt(headings.lastIndex)
                headings.add(title)
                val pathLength = headings.sumOf { it.length.toLong() } + (headings.size - 1) * 3L
                labelLength = if (pathLength == 0L) fileLabelLength else fileLabelLength + 3L + pathLength
                if (labelLength > MAX_SOURCE_LABEL_CHARS) reject(DesktopDocumentRagResult.Reason.DOCUMENT)
            } else {
                bodyLength += line.length.toLong() + 1L
                bodyPresent = bodyPresent || line.isNotBlank()
            }
        }
        if (bodyPresent) accountBody(bodyLength, labelLength)
        // Official fallback chunks the complete input if no nonblank section body was emitted.
        if (sectionCount == 0L && content.isNotBlank()) accountBody(content.length.toLong(), fileLabelLength)
    }

    private fun requireIdentity(storageId: String, entityId: String) {
        if (storageId != entityId || !entityId.matches(Regex("[A-Za-z0-9_-]{1,256}")))
            reject(DesktopDocumentRagResult.Reason.INVENTORY)
    }
    private fun safeWindowsPath(path: Path) {
        if (System.getProperty("os.name").startsWith("Windows")) {
            val attributes = Kernel32.INSTANCE.GetFileAttributes(path.toString())
            if (attributes == -1 || attributes and 0x400 != 0) reject(DesktopDocumentRagResult.Reason.INVENTORY)
        }
    }
    private fun validVector(vector: List<Float>): Boolean {
        // Shared search accumulates Float squares. Finite inputs can still overflow or underflow
        // its norm: refuse those values rather than turning an invalid score into an empty success.
        var norm = 0f
        for (value in vector) {
            if (!value.isFinite()) return false
            norm += value * value
            if (!norm.isFinite()) return false
        }
        return norm > 0f
    }
    private class Rejected(val reason: DesktopDocumentRagResult.Reason) : Exception()
    private fun reject(reason: DesktopDocumentRagResult.Reason): Nothing = throw Rejected(reason)
    companion object {
        private const val MAX_DOCUMENT_BYTES = 1024 * 1024
        // First-stage admission caps, not truncation: 256-char names, six 128-char headings,
        // 1024-char labels, <=2048 sections/chunks (including the existing 1200-chunk fixture).
        // The 2 Mi UTF-16-char tagged-output estimate bounds label replication, ~4 MiB payload;
        // official intermediate lists/maps have bounded overhead, not an exact JVM heap claim.
        private const val MAX_FILE_NAME_CHARS = 256
        private const val MAX_HEADING_CHARS = 128
        private const val MAX_SOURCE_LABEL_CHARS = 1024L
        private const val MAX_PLAN_SECTIONS = 2048L
        private const val MAX_PLAN_CHUNKS = 2048
        private const val MAX_PLAN_CHARS = 2L * 1024 * 1024
        private val MARKDOWN_HEADING = Regex("^(#{1,6})\\s+(.+)$")
        private val PLAIN_HEADING = Regex("^([一二三四五六七八九十]+[、.．]|\\d+[、.．])\\s*.+$")
        private val DOCUMENT_KEYS = setOf("documentId", "sourceLabel", "fileName", "originalDocId", "contentHash", "embeddingKey")
    }
}

private fun ragDigest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
