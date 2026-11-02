@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.example.chatbar.desktop

import com.example.chatbar.domain.chat.PromptCacheUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

@Serializable
internal enum class DesktopUsageSource { CHAT, CHARACTER_CARD, FORMAT_CARD, WORLD_BOOK, MOMENTS, VOICE_PREVIEW, OTHER }

/** Actual parent identity is separate from request-log UI grouping and from physical requests. */
internal data class DesktopUsageContext(
    val source: DesktopUsageSource,
    val taskId: String? = null,
    val sessionId: String? = null,
    val cardId: String? = null,
    val editorSessionId: String? = null,
    val personId: String? = null,
    val subjectId: String? = null,
)

@Serializable
internal enum class DesktopUsageOutcome { RUNNING, COMPLETED, FAILED, CANCELLED, RETRIED }

@Serializable
internal enum class DesktopUsageMediaKind { FISH_TTS, NAI_IMAGE_GENERATION }

/** Local measurements of an actual media attempt, never provider billing or LLM token usage. */
@Serializable
internal data class DesktopUsageMedia(
    val kind: DesktopUsageMediaKind,
    val inputTextCodePoints: Long? = null,
    val outputAudioDurationMs: Long? = null,
    val outputAudioBytes: Long? = null,
    val httpStatus: Int? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val requestedImages: Long? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val outputImages: Long? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val outputImageBytes: Long? = null,
)

/** Only provider-reported counters. Cached input is a subset/annotation, never added to prompt total. */
@Serializable
internal data class DesktopUsageCounters(
    val prompt: Long? = null,
    val completion: Long? = null,
    val cacheHit: Long? = null,
    val cacheWrite: Long? = null,
    val cacheMiss: Long? = null,
) {
    fun merge(usage: PromptCacheUsage): DesktopUsageCounters = copy(
        prompt = usage.promptTokens?.takeIf { it >= 0 }?.toLong() ?: prompt,
        completion = usage.completionTokens?.takeIf { it >= 0 }?.toLong() ?: completion,
        cacheHit = usage.cachedTokens?.takeIf { it >= 0 }?.toLong() ?: cacheHit,
        cacheWrite = usage.cacheWriteTokens?.takeIf { it >= 0 }?.toLong() ?: cacheWrite,
        cacheMiss = usage.cacheMissTokens?.takeIf { it >= 0 }?.toLong() ?: cacheMiss,
    )
}

/** Numeric whitelist: no URL, request/response body, message, account name, key or failure text. */
@Serializable
internal data class DesktopUsageRecord(
    val id: String,
    val requestId: String,
    val attempt: Int,
    val revision: Long,
    val source: DesktopUsageSource,
    val taskId: String? = null,
    val sessionId: String? = null,
    val cardId: String? = null,
    val modelId: String,
    val modelName: String,
    val provider: String? = null,
    val accountId: String? = null,
    val startedAt: Long,
    val updatedAt: Long,
    val outcome: DesktopUsageOutcome = DesktopUsageOutcome.RUNNING,
    val counters: DesktopUsageCounters = DesktopUsageCounters(),
    val schemaVersion: Int = 1,
    val editorSessionId: String? = null,
    val personId: String? = null,
    val subjectId: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val media: DesktopUsageMedia? = null,
)

internal data class DesktopUsageCounterTotal(val value: Long?, val reportedAttempts: Int, val unreportedAttempts: Int)
internal data class DesktopUsageTotals(
    val requests: Int, val attempts: Int,
    val prompt: DesktopUsageCounterTotal, val completion: DesktopUsageCounterTotal,
    val cacheHit: DesktopUsageCounterTotal, val cacheWrite: DesktopUsageCounterTotal, val cacheMiss: DesktopUsageCounterTotal,
    val media: List<DesktopUsageMediaTotals> = emptyList(),
)

internal data class DesktopUsageMediaTotals(val kind: DesktopUsageMediaKind, val attempts: Int,
    val inputTextCodePoints: DesktopUsageCounterTotal, val outputAudioDurationMs: DesktopUsageCounterTotal,
    val outputAudioBytes: DesktopUsageCounterTotal,
    val requestedImages: DesktopUsageCounterTotal, val outputImages: DesktopUsageCounterTotal,
    val outputImageBytes: DesktopUsageCounterTotal)

internal data class DesktopUsageQuery(
    val fromInclusive: Long? = null, val untilExclusive: Long? = null,
    val source: DesktopUsageSource? = null, val modelId: String? = null,
    val provider: String? = null, val accountId: String? = null, val sessionId: String? = null,
    val mediaKind: DesktopUsageMediaKind? = null, val textOnly: Boolean = false,
    val requestId: String? = null,
) {
    fun accepts(record: DesktopUsageRecord) = (fromInclusive == null || record.startedAt >= fromInclusive) &&
        (untilExclusive == null || record.startedAt < untilExclusive) && (source == null || record.source == source) &&
        (modelId == null || record.modelId == modelId) && (provider == null || record.provider == provider) &&
        (accountId == null || record.accountId == accountId) && (sessionId == null || record.sessionId == sessionId) &&
        (mediaKind == null || record.media?.kind == mediaKind) && (!textOnly || record.media == null) &&
        (requestId == null || record.requestId == requestId)
}

internal fun summarizeDesktopUsage(records: List<DesktopUsageRecord>): DesktopUsageTotals {
    // A newer cumulative report replaces an attempt; repeated events and retries are not extra requests.
    val latest = records.groupBy { it.id }.values.map { versions ->
        val newest = versions.maxBy { it.revision }
        check(versions.filter { it.revision == newest.revision }.all { it == newest })
        newest
    }
    fun measured(rows: List<DesktopUsageRecord>, select: (DesktopUsageRecord) -> Long?): DesktopUsageCounterTotal {
        val values = rows.mapNotNull { select(it)?.also { value -> require(value >= 0) } }
        return DesktopUsageCounterTotal(if (values.isEmpty()) null else values.fold(0L, Math::addExact),
            values.size, rows.size - values.size)
    }
    val text = latest.filter { it.media == null }
    fun total(select: (DesktopUsageCounters) -> Long?) = measured(text) { select(it.counters) }
    val media = latest.filter { it.media != null }.groupBy { requireNotNull(it.media).kind }.map { (kind, rows) ->
        require(rows.all { it.counters == DesktopUsageCounters() })
        DesktopUsageMediaTotals(kind, rows.size, measured(rows) { it.media?.inputTextCodePoints },
            measured(rows) { it.media?.outputAudioDurationMs }, measured(rows) { it.media?.outputAudioBytes },
            measured(rows) { it.media?.requestedImages }, measured(rows) { it.media?.outputImages },
            measured(rows) { it.media?.outputImageBytes })
    }
    return DesktopUsageTotals(latest.map { it.requestId }.distinct().size, latest.size,
        total { it.prompt }, total { it.completion }, total { it.cacheHit }, total { it.cacheWrite }, total { it.cacheMiss }, media)
}

/** Local statistics custody, outside portable entities. IO and all publications use the existing root gate. */
internal class DesktopProviderUsageStore(private val root: Path, private val gate: DesktopDataOperationCoordinator) {
    private val home = root.resolve("diagnostics/provider-usage")
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true }

    suspend fun finishLocalDiagnostics(operation: suspend () -> Unit) = gate.withLocalDiagnosticsShutdownDrain(operation)

    private fun validate(record: DesktopUsageRecord) {
        require(UUID.fromString(record.id).toString() == record.id)
        require(UUID.fromString(record.requestId).toString() == record.requestId)
        require(record.schemaVersion == (if (record.media == null) 1 else 2) && record.attempt >= 1 && record.revision >= 1 && record.updatedAt >= record.startedAt)
        listOfNotNull(record.taskId, record.sessionId, record.cardId, record.modelId, record.modelName,
            record.provider, record.accountId, record.editorSessionId, record.personId, record.subjectId).forEach { require(it.length <= 512) }
        with(record.counters) { listOfNotNull(prompt, completion, cacheHit, cacheWrite, cacheMiss).forEach { require(it >= 0) } }
        record.media?.let { media ->
            require(record.counters == DesktopUsageCounters())
            require(media.httpStatus == null || media.httpStatus in 100..599)
            listOfNotNull(media.inputTextCodePoints, media.outputAudioDurationMs, media.outputAudioBytes,
                media.requestedImages, media.outputImages, media.outputImageBytes).forEach { require(it >= 0) }
            when (media.kind) {
                DesktopUsageMediaKind.FISH_TTS -> require(media.requestedImages == null && media.outputImages == null && media.outputImageBytes == null)
                DesktopUsageMediaKind.NAI_IMAGE_GENERATION -> {
                    require(media.inputTextCodePoints == null && media.outputAudioDurationMs == null && media.outputAudioBytes == null)
                    require(media.requestedImages != null && media.requestedImages > 0)
                }
            }
        }
    }
    private fun read(file: Path): DesktopUsageRecord {
        DesktopArchiveFiles.safe(file, false)
        check(Files.size(file) <= 32_768)
        return json.decodeFromString(DesktopUsageRecord.serializer(), Files.readString(file)).also {
            validate(it); check(file.fileName.toString() == "${it.id}.json")
        }
    }

    suspend fun save(record: DesktopUsageRecord) = withContext(Dispatchers.IO) { gate.withNormalOperation { mutex.withLock {
        validate(record); DesktopArchiveFiles.safe(root, true)
        val diagnostics = root.resolve("diagnostics")
        if (!Files.exists(diagnostics, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(diagnostics)
        DesktopArchiveFiles.safe(diagnostics, true)
        if (!Files.exists(home, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(home)
        DesktopArchiveFiles.safe(home, true)
        val file = home.resolve("${record.id}.json")
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            val previous = read(file)
            check(previous.requestId == record.requestId && previous.attempt == record.attempt &&
                previous.startedAt == record.startedAt && previous.source == record.source &&
                previous.taskId == record.taskId && previous.sessionId == record.sessionId && previous.cardId == record.cardId &&
                previous.editorSessionId == record.editorSessionId && previous.personId == record.personId && previous.subjectId == record.subjectId &&
                previous.modelId == record.modelId && previous.modelName == record.modelName &&
                previous.provider == record.provider && previous.accountId == record.accountId && previous.schemaVersion == record.schemaVersion &&
                previous.media?.kind == record.media?.kind && previous.media?.inputTextCodePoints == record.media?.inputTextCodePoints &&
                previous.media?.requestedImages == record.media?.requestedImages)
            if (previous.revision >= record.revision) {
                check(previous.revision != record.revision || previous == record)
                return@withLock
            }
        }
        val encoded = json.encodeToString(DesktopUsageRecord.serializer(), record).toByteArray()
        check(encoded.size <= 32_768)
        val temporary = home.resolve(".${record.id}-${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                val bytes = ByteBuffer.wrap(encoded)
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    } } }

    suspend fun records(): List<DesktopUsageRecord> = withContext(Dispatchers.IO) { gate.withNormalOperation { mutex.withLock {
        if (!Files.exists(home, LinkOption.NOFOLLOW_LINKS)) return@withLock emptyList()
        DesktopArchiveFiles.safe(root.resolve("diagnostics"), true); DesktopArchiveFiles.safe(home, true)
        Files.newDirectoryStream(home).use { files ->
            val results = mutableListOf<DesktopUsageRecord>()
            files.forEach { file ->
                currentCoroutineContext().ensureActive()
                DesktopArchiveFiles.safe(file, false)
                if (file.fileName.toString().endsWith(".tmp")) return@forEach // preserved interrupted local publication
                check(results.size < 1_000_000 && file.fileName.toString().endsWith(".json"))
                results += read(file)
            }
            results.sortedByDescending { it.startedAt }
        }
    } } }
}
