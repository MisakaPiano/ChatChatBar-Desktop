package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.data.operation.NoOpAppDataOperationGate
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.DocumentRagStatus
import com.example.chatbar.data.local.entity.PresetEntry
import com.example.chatbar.data.local.entity.PresetImportState
import com.example.chatbar.data.local.entity.PresetType
import com.example.chatbar.data.local.entity.RagIndexStatus
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.data.repository.WorldBookRepository
import com.example.chatbar.domain.card.CharacterCardTransferCore
import com.example.chatbar.domain.card.FormatCardTransferService
import com.example.chatbar.domain.card.WorldBookTransferService
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/** Manifest-order Desktop preset lifecycle. An entity commit is authoritative even if the ledger was lost. */
internal class DesktopPresetBootstrap(
    private val source: DesktopPresetSource,
    private val storage: JsonFileStorage,
    private val characters: CharacterRepository,
    private val formats: FormatCardRepository,
    private val worlds: WorldBookRepository,
    private val characterTransfers: CharacterCardTransferCore,
    private val formatTransfers: FormatCardTransferService,
    private val worldTransfers: WorldBookTransferService,
    private val resources: DesktopCharacterResourceStore,
    private val beforeCharacterWorldBookBinding: suspend (CharacterCard) -> Unit = {},
    private val operationGate: AppDataOperationGate = NoOpAppDataOperationGate,
    private val saveRepairedCharacter: suspend (CharacterCard, () -> Unit) -> Unit = characters::saveObserved,
    private val readRepairedCharacter: suspend (String) -> JsonFileStorage.EntityReadResult<CharacterCard> = characters::readDurable,
    private val probeDocument: (String) -> DesktopDocumentProbe = resources::probeDocument,
) {
    private val mutex = Mutex()
    private var initialized = false
    private val failureMessages = mutableListOf<String>()
    val failures: List<String> get() = failureMessages.toList()

    suspend fun initialize() = mutex.withLock {
        if (initialized) return@withLock
        val envelope = storage.loadSingleton("preset_import_state", JsonObject.serializer())
        val original = envelope?.let { storage.json.decodeFromJsonElement(PresetImportState.serializer(), it) }
            ?: PresetImportState()
        val seen = original.seenVersions.toMutableMap()
        val failedWorldBooks = mutableSetOf<String>()
        for (entry in source.entries) {
            val prior = seen[entry.presetKey]
            if (prior != null) {
                if (entry.version > prior) seen[entry.presetKey] = entry.version
                continue
            }
            try {
                importOrReconcile(entry, failedWorldBooks)
                seen[entry.presetKey] = entry.version
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Leave unseen. The next startup retries while later entries can still import.
                val diagnostic = "Bundled preset ${entry.presetKey} failed: ${error.message ?: error::class.simpleName}"
                failureMessages += diagnostic
                System.err.println(diagnostic)
                if (entry.type == PresetType.WORLD_BOOK) failedWorldBooks += entry.presetKey
            }
        }
        for (card in characters.getAll().filter { it.sourcePresetKey != null }) {
            try {
                repairCharacterDocuments(card)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A damaged/unavailable document must not block other bundled entries.
                val diagnostic = "Bundled Character document repair ${card.id} failed: ${error.message ?: error::class.simpleName}"
                failureMessages += diagnostic
                System.err.println(diagnostic)
            }
        }
        if (seen != original.seenVersions) {
            val seenJson = storage.json.encodeToJsonElement(PresetImportState.serializer(), PresetImportState(seen))
                .let { it as JsonObject }["seenVersions"] ?: error("Missing preset ledger versions")
            storage.saveSingleton("preset_import_state",
                JsonObject((envelope ?: JsonObject(emptyMap())) + ("seenVersions" to seenJson)),
                JsonObject.serializer())
        }
        initialized = true
    }

    private suspend fun importOrReconcile(entry: PresetEntry, failedWorldBooks: Set<String>) {
        when (entry.type) {
            PresetType.WORLD_BOOK -> if (worlds.getAll().none { it.sourcePresetKey == entry.presetKey }) {
                val packaged = source.worldBookPackage(entry)
                worldTransfers.importNew(packaged.copy(book = packaged.book.copy(
                    sourcePresetKey = entry.presetKey, sourcePresetVersion = entry.version,
                )), entry.displayName)
            }
            PresetType.CHARACTER -> {
                var card = characters.getAll().firstOrNull { it.sourcePresetKey == entry.presetKey }
                if (card == null) {
                    try {
                        card = characterTransfers.importNew(source.characterPackage(entry),
                            presetKey = entry.presetKey, presetVersion = entry.version)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // Shared transfer may have committed before a post-commit step failed.
                        characters.refreshFromStorage()
                        card = characters.getAll().firstOrNull { it.sourcePresetKey == entry.presetKey }
                        if (card == null) throw error
                    }
                }
                bindPresetWorldBooks(card, entry, failedWorldBooks)
            }
            PresetType.FORMAT -> if (formats.getAll().none { it.sourcePresetKey == entry.presetKey }) {
                formatTransfers.importNew(source.formatPackage(entry), entry.presetKey, entry.version)
            }
            PresetType.MODEL_CATALOG -> Unit // Ledger only; model restoration has its own authority.
        }
    }

    private suspend fun bindPresetWorldBooks(card: CharacterCard, entry: PresetEntry, failedWorldBooks: Set<String>) {
        if (entry.worldBookPresetKeys.isEmpty()) return
        beforeCharacterWorldBookBinding(card)
        val keys = entry.worldBookPresetKeys.toSet()
        val matched = worlds.getAll().filter { it.sourcePresetKey in keys }
        val ids = matched.map { it.id }
        val bound = (card.worldBookIds + ids).distinct()
        if (bound != card.worldBookIds) characters.save(card.copy(worldBookIds = bound))
        check(keys.any { it in failedWorldBooks }.not()) {
            "Bundled WorldBook import failed this startup for ${entry.presetKey}"
        }
    }

    internal suspend fun repairCharacterDocuments(card: CharacterCard): CharacterCard = operationGate.withNormalOperation {
        val entry = source.entries.firstOrNull {
            it.type == PresetType.CHARACTER && it.presetKey == card.sourcePresetKey
        } ?: return@withNormalOperation card
        val missing = card.customDocuments.filter { document ->
            when (val result = probeDocument(document.filePath)) {
                DesktopDocumentProbe.Missing -> true
                DesktopDocumentProbe.Present -> false
                is DesktopDocumentProbe.ReadError -> throw IOException("Bundled document unreadable: ${document.filePath}", result.cause)
                is DesktopDocumentProbe.Unsafe -> throw IOException("Bundled document reference unsafe: ${document.filePath}", result.cause)
            }
        }
        if (missing.isEmpty()) return@withNormalOperation card
        val packaged = source.characterPackage(entry).documents.associateBy { it.fileName }
        val created = mutableListOf<String>()
        var committed = false
        var saveStarted = false
        try {
            val repaired = card.customDocuments.map { document ->
                if (document !in missing) return@map document
                val sourceDocument = packaged[document.fileName] ?: return@map document
                val reference = resources.materializeDocument(sourceDocument, System.currentTimeMillis(), document.id)
                created += reference
                document.copy(
                    filePath = reference,
                    fileType = sourceDocument.fileType,
                    contentHash = null,
                    indexedHash = null,
                    ragStatus = DocumentRagStatus.PENDING.name,
                    ragChunkCount = 0,
                    ragIndexedAt = null,
                    ragError = null,
                )
            }
            if (created.isEmpty()) return@withNormalOperation card
            val updated = card.copy(
                customDocuments = repaired,
                ragIndexStatus = RagIndexStatus.NOT_INDEXED.name,
                ragIndexDone = 0,
                ragIndexTotal = repaired.size,
                ragIndexMessage = "参考文档已修复，待建立索引",
                ragIndexedAt = null,
            )
            saveStarted = true
            saveRepairedCharacter(updated) { committed = true }
            return@withNormalOperation updated
        } catch (error: Throwable) {
            val cleanupFailure = withContext(NonCancellable) {
                runCatching {
                    val durable = if (committed || !saveStarted) null else try {
                        readRepairedCharacter(card.id)
                    } catch (probeError: Exception) {
                        JsonFileStorage.EntityReadResult.ReadError(probeError)
                    }
                    val outcome = when {
                        committed -> RepairCommitOutcome.COMMITTED
                        !saveStarted -> RepairCommitOutcome.PRECOMMIT
                        durable is JsonFileStorage.EntityReadResult.Valid &&
                            durable.value.customDocuments.any { it.filePath in created } -> RepairCommitOutcome.COMMITTED
                        durable is JsonFileStorage.EntityReadResult.Valid ||
                            durable is JsonFileStorage.EntityReadResult.Missing -> RepairCommitOutcome.PRECOMMIT
                        else -> RepairCommitOutcome.INDETERMINATE
                    }
                    if (outcome == RepairCommitOutcome.PRECOMMIT) {
                        created.forEach { resources.deleteOwned(it) }
                    } else if (outcome == RepairCommitOutcome.COMMITTED) {
                        System.err.println("Bundled Character document repair committed for ${card.id}; resources retained")
                        characters.refreshFromStorage()
                    }
                    if (outcome == RepairCommitOutcome.INDETERMINATE) {
                        System.err.println("Bundled Character document repair indeterminate for ${card.id}; created resources retained")
                    }
                }.exceptionOrNull()
            }
            cleanupFailure?.let(error::addSuppressed)
            if (error is CancellationException) throw error
            throw IOException("Bundled Character document repair failed", error)
        }
    }

    private enum class RepairCommitOutcome { PRECOMMIT, COMMITTED, INDETERMINATE }
}
