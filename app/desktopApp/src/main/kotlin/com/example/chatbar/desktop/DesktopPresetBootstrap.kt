package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
) {
    private val mutex = Mutex()
    private var initialized = false
    private val failureMessages = mutableListOf<String>()
    val failures: List<String> get() = failureMessages.toList()

    suspend fun initialize() = mutex.withLock {
        if (initialized) return@withLock
        val original = storage.loadSingleton("preset_import_state", PresetImportState.serializer())
            ?: PresetImportState()
        val seen = original.seenVersions.toMutableMap()
        for (entry in source.entries) {
            val prior = seen[entry.presetKey]
            if (prior != null) {
                if (entry.version > prior) seen[entry.presetKey] = entry.version
                continue
            }
            try {
                importOrReconcile(entry)
                seen[entry.presetKey] = entry.version
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Leave unseen. The next startup retries while later entries can still import.
                val diagnostic = "Bundled preset ${entry.presetKey} failed: ${error.message ?: error::class.simpleName}"
                failureMessages += diagnostic
                System.err.println(diagnostic)
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
            storage.saveSingleton("preset_import_state", PresetImportState(seen), PresetImportState.serializer())
        }
        initialized = true
    }

    private suspend fun importOrReconcile(entry: PresetEntry) {
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
                bindPresetWorldBooks(card, entry)
            }
            PresetType.FORMAT -> if (formats.getAll().none { it.sourcePresetKey == entry.presetKey }) {
                formatTransfers.importNew(source.formatPackage(entry), entry.presetKey, entry.version)
            }
            PresetType.MODEL_CATALOG -> Unit // Ledger only; model restoration has its own authority.
        }
    }

    private suspend fun bindPresetWorldBooks(card: CharacterCard, entry: PresetEntry) {
        if (entry.worldBookPresetKeys.isEmpty()) return
        beforeCharacterWorldBookBinding(card)
        val keys = entry.worldBookPresetKeys.toSet()
        val matched = worlds.getAll().filter { it.sourcePresetKey in keys }
        check(matched.mapNotNull { it.sourcePresetKey }.toSet().containsAll(keys)) {
            "Bundled WorldBook binding unavailable for ${entry.presetKey}"
        }
        val ids = matched.map { it.id }
        val bound = (card.worldBookIds + ids).distinct()
        if (bound != card.worldBookIds) characters.save(card.copy(worldBookIds = bound))
    }

    internal suspend fun repairCharacterDocuments(card: CharacterCard): CharacterCard {
        val entry = source.entries.firstOrNull {
            it.type == PresetType.CHARACTER && it.presetKey == card.sourcePresetKey
        } ?: return card
        val missing = card.customDocuments.filter { document ->
            runCatching { resources.readText(document.filePath) }.isFailure
        }
        if (missing.isEmpty()) return card
        val packaged = source.characterPackage(entry).documents.associateBy { it.fileName }
        val created = mutableListOf<String>()
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
            if (created.isEmpty()) return card
            val updated = card.copy(
                customDocuments = repaired,
                ragIndexStatus = RagIndexStatus.NOT_INDEXED.name,
                ragIndexDone = 0,
                ragIndexTotal = repaired.size,
                ragIndexMessage = "参考文档已修复，待建立索引",
                ragIndexedAt = null,
            )
            characters.save(updated)
            return updated
        } catch (cancelled: CancellationException) {
            created.forEach { reference -> runCatching { resources.deleteOwned(reference) } }
            throw cancelled
        } catch (error: Exception) {
            created.forEach { reference -> runCatching { resources.deleteOwned(reference) } }
            throw IOException("Bundled Character document repair failed", error)
        }
    }
}
