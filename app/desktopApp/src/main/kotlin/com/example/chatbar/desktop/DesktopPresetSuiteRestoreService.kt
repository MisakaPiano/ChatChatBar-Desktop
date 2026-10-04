package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.PresetEntry
import com.example.chatbar.data.local.entity.PresetType
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.WorldBookRepository
import com.example.chatbar.domain.card.CharacterCardTransferCore
import com.example.chatbar.domain.card.CharacterTransferObservation
import com.example.chatbar.domain.card.WorldBookTransferService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class DesktopPresetSuiteRestoreResult(
    val characterCreated: Boolean,
    val worldBooksCreated: Int,
    val bindingsAdded: Int,
) {
    val alreadyComplete: Boolean get() = !characterCreated && worldBooksCreated == 0 && bindingsAdded == 0
}

internal class DesktopPresetSuiteIndeterminateException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Explicit additive repair. Startup ledger and ordinary name-conflict recovery are separate authorities. */
internal class DesktopPresetSuiteRestoreService(
    private val source: DesktopPresetSource,
    private val characters: CharacterRepository,
    private val worlds: WorldBookRepository,
    private val characterTransfers: CharacterCardTransferCore,
    private val worldTransfers: WorldBookTransferService,
    private val coordinator: DesktopDataOperationCoordinator,
    private val beforeWorldBookImport: suspend (PresetEntry) -> Unit = {},
    private val afterWorldBookCreated: suspend (WorldBook) -> Unit = {},
    private val afterCharacterCreated: suspend (CharacterCard) -> Unit = {},
    private val beforeBindingWrite: suspend (CharacterCard) -> Unit = {},
    private val afterBindingCommit: () -> Unit = {},
    private val saveBinding: suspend (CharacterCard, () -> Unit) -> Unit = characters::saveObserved,
    private val readCharacterDurable: suspend (String) -> JsonFileStorage.EntityReadResult<CharacterCard> = characters::readDurable,
    private val readWorldDurable: suspend (String) -> JsonFileStorage.EntityReadResult<WorldBook> = worlds::readDurable,
    private val scanCharactersDurable: suspend () -> List<JsonFileStorage.EntityFileRead<CharacterCard>> = characters::scanDurable,
    private val scanWorldsDurable: suspend () -> List<JsonFileStorage.EntityFileRead<WorldBook>> = worlds::scanDurable,
) {
    private sealed interface Unresolved {
        val id: String
        data class Character(override val id: String, val key: String) : Unresolved
        data class World(override val id: String, val key: String) : Unresolved
        data class Binding(override val id: String, val key: String, val ids: Set<String>) : Unresolved
    }

    /** In-memory restraint for an exact target whose durable state could not be read strictly. */
    private var unresolved: Unresolved? = null
    private val restoreMutex = Mutex()

    suspend fun restore(entry: PresetEntry): DesktopPresetSuiteRestoreResult {
        var originalCancellation: CancellationException? = null
        try {
            return restoreMutex.withLock {
                coordinator.withExclusiveMaintenance {
                    resolveUncertainTarget()
                    val manifest = source.entries
                    require(entry.type == PresetType.CHARACTER && entry in manifest &&
                        manifest.count { it.presetKey == entry.presetKey } == 1 && entry.worldBookPresetKeys.isNotEmpty()) {
                        "Unknown complete Character preset: ${entry.presetKey}"
                    }
                    val related = entry.worldBookPresetKeys.distinct().map { key ->
                        val matches = manifest.filter { it.presetKey == key }
                        require(matches.size == 1) { "Manifest WorldBook key '$key' is missing or ambiguous" }
                        matches.single().also {
                            require(it.type == PresetType.WORLD_BOOK) {
                                "Manifest key '$key' is not a WorldBook preset"
                            }
                        }
                    }

                    // Permissive repository caches cannot prove that a preset entity is absent.
                    val durableCharacters = strictCharacters()
                    val durableWorlds = strictWorlds()
                    val initialCard = uniqueCharacter(entry.presetKey, durableCharacters)
                    val initialWorlds = related.associate { it.presetKey to uniqueWorld(it.presetKey, durableWorlds) }
                    // Transfer NamePolicy still uses repository lists; publish only after strict discovery succeeded.
                    characters.refreshFromStorage()
                    worlds.refreshFromStorage()
                    var card = initialCard
                    var createdWorlds = 0
                    val observed = mutableListOf<Unresolved>()
                    try {
                        val books = related.map { worldEntry ->
                            initialWorlds[worldEntry.presetKey] ?: run {
                                beforeWorldBookImport(worldEntry)
                                val imported = importWorld(worldEntry, observed)
                                createdWorlds++
                                afterWorldBookCreated(imported)
                                imported
                            }
                        }
                        if (card == null) {
                            card = importCharacter(entry, observed) { originalCancellation = it }
                            afterCharacterCreated(requireNotNull(card))
                        }
                        // Defense in depth against external file changes and stale preflight IDs.
                        related.zip(books).forEach { (worldEntry, book) ->
                            val current = strictWorld(book.id)
                            check(current.sourcePresetKey == worldEntry.presetKey) {
                                "Preset WorldBook provenance changed: ${worldEntry.presetKey} (${book.id})"
                            }
                        }
                        val targetIds = books.map { it.id }.toSet()
                        val latest = strictCharacter(requireNotNull(card).id)
                        check(latest.sourcePresetKey == entry.presetKey) {
                            "Preset Character provenance changed: ${entry.presetKey}"
                        }
                        var bindingsAdded = 0
                        if (targetIds.any { it !in latest.worldBookIds }) {
                            beforeBindingWrite(latest)
                            // A callback may suspend while an external process changes the files.
                            related.zip(books).forEach { (worldEntry, book) ->
                                val currentWorld = strictWorld(book.id)
                                check(currentWorld.sourcePresetKey == worldEntry.presetKey) {
                                    "Preset WorldBook provenance changed: ${worldEntry.presetKey} (${book.id})"
                                }
                            }
                            // The callback may have suspended while an editor changed the same Character.
                            val current = strictCharacter(latest.id)
                            check(current.sourcePresetKey == entry.presetKey) {
                                "Preset Character provenance changed: ${entry.presetKey}"
                            }
                            val additions = targetIds - current.worldBookIds.toSet()
                            bindingsAdded = additions.size
                            if (additions.isNotEmpty()) {
                                val updated = current.copy(worldBookIds = (current.worldBookIds + books.map { it.id }).distinct())
                                try {
                                    saveBinding(updated) {
                                        try { afterBindingCommit() }
                                        catch (cancelled: CancellationException) {
                                            originalCancellation = cancelled
                                            throw cancelled
                                        }
                                    }
                                } catch (failure: Throwable) {
                                    val durable = withContext(NonCancellable) {
                                        runCatching { readCharacterDurable(current.id) }.getOrNull()
                                    }
                                    when {
                                        durable is JsonFileStorage.EntityReadResult.Valid &&
                                            durable.value.sourcePresetKey == entry.presetKey &&
                                            targetIds.all { it in durable.value.worldBookIds } -> {
                                            // The durable rename happened; cache publication or callback failed.
                                            val refreshFailure = withContext(NonCancellable) {
                                                runCatching { characters.refreshFromStorage() }.exceptionOrNull()
                                            }
                                            refreshFailure?.let {
                                                if (failure is CancellationException) failure.addSuppressed(it) else throw it
                                            }
                                            if (failure is CancellationException) throw failure
                                        }
                                        durable is JsonFileStorage.EntityReadResult.Valid &&
                                            durable.value.sourcePresetKey == entry.presetKey -> {
                                            if (failure is CancellationException) throw failure
                                            throw IllegalStateException(
                                                "Preset bindings were not committed; retry is safe: ${failure.message}", failure)
                                        }
                                        else -> {
                                            unresolved = Unresolved.Binding(current.id, entry.presetKey, targetIds)
                                            if (failure is CancellationException) throw failure
                                            throw DesktopPresetSuiteIndeterminateException(
                                                "Preset binding commit is indeterminate for Character ${current.id}; verify before retry", failure)
                                        }
                                    }
                                }
                            }
                        }
                        DesktopPresetSuiteRestoreResult(initialCard == null, createdWorlds, bindingsAdded)
                    } catch (failure: Throwable) {
                        if (failure is CancellationException) {
                            if (originalCancellation == null) originalCancellation = failure
                            val reconcileFailure = withContext(NonCancellable) {
                                runCatching {
                                    observed.forEach { confirmObserved(it) }
                                    characters.refreshFromStorage()
                                    worlds.refreshFromStorage()
                                }.exceptionOrNull()
                            }
                            reconcileFailure?.let { if (it !== failure) failure.addSuppressed(it) }
                        }
                        throw failure
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw originalCancellation ?: cancelled
        }
    }

    private suspend fun strictCharacters(): List<CharacterCard> = scanCharactersDurable().map { file ->
        when (val result = file.result) {
            is JsonFileStorage.EntityReadResult.Valid -> result.value.also {
                check(it.id == file.storageId) { "Character storage ID mismatch: ${file.storageId} != ${it.id}" }
            }
            else -> throw DesktopPresetSuiteIndeterminateException(
                "Cannot strictly discover Character storage ID ${file.storageId}: $result")
        }
    }

    private suspend fun strictWorlds(): List<WorldBook> = scanWorldsDurable().map { file ->
        when (val result = file.result) {
            is JsonFileStorage.EntityReadResult.Valid -> result.value.also {
                check(it.id == file.storageId) { "WorldBook storage ID mismatch: ${file.storageId} != ${it.id}" }
            }
            else -> throw DesktopPresetSuiteIndeterminateException(
                "Cannot strictly discover WorldBook storage ID ${file.storageId}: $result")
        }
    }

    private fun uniqueCharacter(key: String, durable: List<CharacterCard>): CharacterCard? {
        val matching = durable.filter { it.sourcePresetKey == key }
        check(matching.size <= 1) { "Multiple Characters have preset key '$key'; resolve duplicates first" }
        return matching.singleOrNull()
    }

    private fun uniqueWorld(key: String, durable: List<WorldBook>): WorldBook? {
        val matching = durable.filter { it.sourcePresetKey == key }
        check(matching.size <= 1) { "Multiple WorldBooks have preset key '$key'; resolve duplicates first" }
        return matching.singleOrNull()
    }

    private suspend fun strictCharacter(id: String): CharacterCard = when (val result = readCharacterDurable(id)) {
        is JsonFileStorage.EntityReadResult.Valid -> result.value.also { check(it.id == id) { "Character identity mismatch: $id" } }
        else -> throw DesktopPresetSuiteIndeterminateException("Cannot strictly verify preset Character $id: $result")
    }

    private suspend fun strictWorld(id: String): WorldBook = when (val result = readWorldDurable(id)) {
        is JsonFileStorage.EntityReadResult.Valid -> result.value.also { check(it.id == id) { "WorldBook identity mismatch: $id" } }
        else -> throw DesktopPresetSuiteIndeterminateException("Cannot strictly verify preset WorldBook $id: $result")
    }

    private suspend fun importWorld(entry: PresetEntry, observed: MutableList<Unresolved>): WorldBook {
        var prepared: WorldBook? = null
        var committed = false
        val packaged = source.worldBookPackage(entry)
        val data = packaged.copy(book = packaged.book.copy(sourcePresetKey = entry.presetKey,
            sourcePresetVersion = entry.version))
        return try {
            worldTransfers.importNewObserved(data, entry.displayName,
                onPrepared = { value -> prepared = value; observed += Unresolved.World(value.id, entry.presetKey) },
                onCommitted = { committed = true })
        } catch (failure: Throwable) {
            val target = prepared ?: throw failure
            val durable = withContext(NonCancellable) { runCatching { readWorldDurable(target.id) }.getOrNull() }
            when {
                durable is JsonFileStorage.EntityReadResult.Valid && durable.value.id == target.id &&
                    durable.value.sourcePresetKey == entry.presetKey -> {
                    val refreshFailure = withContext(NonCancellable) {
                        runCatching { worlds.refreshFromStorage() }.exceptionOrNull()
                    }
                    refreshFailure?.let { failure.addSuppressed(it) }
                    if (failure is CancellationException) throw failure
                    throw IllegalStateException("WorldBook '${entry.presetKey}' committed but transfer did not finish; retry is safe", failure)
                }
                durable == JsonFileStorage.EntityReadResult.Missing && !committed -> throw failure
                else -> {
                    unresolved = Unresolved.World(target.id, entry.presetKey)
                    if (failure is CancellationException) throw failure
                    throw DesktopPresetSuiteIndeterminateException("WorldBook import is indeterminate for ${target.id}; verify before retry", failure)
                }
            }
        }
    }

    private suspend fun importCharacter(entry: PresetEntry, observed: MutableList<Unresolved>,
        rememberCancellation: (CancellationException) -> Unit): CharacterCard {
        val observation = CharacterTransferObservation()
        return try {
            characterTransfers.importNew(source.characterPackage(entry), presetKey = entry.presetKey,
                presetVersion = entry.version, observation = observation).also {
                observed += Unresolved.Character(it.id, entry.presetKey)
            }
        } catch (failure: Throwable) {
            val cancellation = characterTransferCancellationCause(failure)
            val target = observation.expected ?: run {
                cancellation?.let { rememberCancellation(it); throw it }
                throw failure
            }
            observed += Unresolved.Character(target.id, entry.presetKey)
            val durable = withContext(NonCancellable) { runCatching { readCharacterDurable(target.id) }.getOrNull() }
            when {
                durable is JsonFileStorage.EntityReadResult.Valid && durable.value.id == target.id &&
                    durable.value.sourcePresetKey == entry.presetKey -> {
                    val refreshFailure = withContext(NonCancellable) {
                        runCatching { characters.refreshFromStorage() }.exceptionOrNull()
                    }
                    refreshFailure?.let { failure.addSuppressed(it) }
                    cancellation?.let { rememberCancellation(it); throw it }
                    throw IllegalStateException("Character '${entry.presetKey}' committed but transfer did not finish; retry is safe", failure)
                }
                durable == JsonFileStorage.EntityReadResult.Missing && !observation.committed -> {
                    cancellation?.let { rememberCancellation(it); throw it }
                    throw failure
                }
                else -> {
                    unresolved = Unresolved.Character(target.id, entry.presetKey)
                    cancellation?.let { rememberCancellation(it); throw it }
                    throw DesktopPresetSuiteIndeterminateException("Character import is indeterminate for ${target.id}; verify before retry", failure)
                }
            }
        }
    }

    private suspend fun confirmObserved(target: Unresolved) {
        val result = runCatching { when (target) {
            is Unresolved.Character, is Unresolved.Binding -> readCharacterDurable(target.id)
            is Unresolved.World -> readWorldDurable(target.id)
        } }.getOrNull()
        val safe = when (target) {
            is Unresolved.Character -> result == JsonFileStorage.EntityReadResult.Missing ||
                result is JsonFileStorage.EntityReadResult.Valid<*> &&
                (result.value as? CharacterCard)?.let { it.id == target.id && it.sourcePresetKey == target.key } == true
            is Unresolved.World -> result == JsonFileStorage.EntityReadResult.Missing ||
                result is JsonFileStorage.EntityReadResult.Valid<*> &&
                (result.value as? WorldBook)?.let { it.id == target.id && it.sourcePresetKey == target.key } == true
            is Unresolved.Binding -> result is JsonFileStorage.EntityReadResult.Valid<*> &&
                (result.value as? CharacterCard)?.let { it.id == target.id && it.sourcePresetKey == target.key } == true
        }
        if (!safe) {
            unresolved = target
            throw DesktopPresetSuiteIndeterminateException("Preset operation is indeterminate for ${target.id}")
        }
    }

    private suspend fun resolveUncertainTarget() {
        val target = unresolved ?: return
        val result = withContext(NonCancellable) {
            when (target) {
                is Unresolved.Character, is Unresolved.Binding -> runCatching { readCharacterDurable(target.id) }.getOrNull()
                is Unresolved.World -> runCatching { readWorldDurable(target.id) }.getOrNull()
            }
        }
        val resolved = when (target) {
            is Unresolved.Character -> result == JsonFileStorage.EntityReadResult.Missing ||
                result is JsonFileStorage.EntityReadResult.Valid<*> &&
                (result.value as? CharacterCard)?.let { it.id == target.id && it.sourcePresetKey == target.key } == true
            is Unresolved.World -> result == JsonFileStorage.EntityReadResult.Missing ||
                result is JsonFileStorage.EntityReadResult.Valid<*> &&
                (result.value as? WorldBook)?.let { it.id == target.id && it.sourcePresetKey == target.key } == true
            is Unresolved.Binding -> result is JsonFileStorage.EntityReadResult.Valid<*> &&
                (result.value as? CharacterCard)?.let { it.id == target.id && it.sourcePresetKey == target.key } == true
        }
        if (!resolved) throw DesktopPresetSuiteIndeterminateException(
            "Preset target ${target.id} cannot be strictly verified; no new import was attempted")
        unresolved = null
    }
}
