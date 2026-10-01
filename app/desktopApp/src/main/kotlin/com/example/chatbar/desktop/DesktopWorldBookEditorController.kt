package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.EditorDraft
import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.EditorDraftRepository
import com.example.chatbar.data.repository.WorldBookRepository
import com.example.chatbar.domain.card.CharacterSectionImportPolicy
import com.example.chatbar.domain.card.NamePolicy
import com.example.chatbar.domain.card.WorldBookCharacterImportResult
import com.example.chatbar.domain.draft.WorldBookEntryModalState
import com.example.chatbar.domain.draft.WorldBookEditorDraftUiState
import com.example.chatbar.domain.draft.WorldBookEditorDraftUiStateCodec
import com.example.chatbar.domain.draft.WorldBookEditorPostCommitState
import com.example.chatbar.domain.draft.EditorPostCommitFingerprint
import com.example.chatbar.domain.draft.hasMeaningfulEntryData
import com.example.chatbar.domain.draft.materialize
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

internal enum class WorldBookEditorProblem {
    NAME_REQUIRED, DUPLICATE_NAME, SCAN_DEPTH_INVALID, TOKEN_BUDGET_INVALID,
    ENTRY_REQUIRED, ENTRY_CHANGED, ENTRY_MODAL_OPEN, SOURCE_CHANGED, SOURCE_DELETED, NEW_DRAFT_EXISTS,
    DRAFT_FAILED, SAVE_FAILED, SAVE_COMMITTED_WARNING, CLEAN_DRAFT_WARNING,
    CHARACTER_IMPORT_FAILED, CHARACTER_CLEAR_FAILED,
}

internal data class DesktopWorldBookEditorState(
    val books: List<WorldBook> = emptyList(),
    val query: String = "",
    val book: WorldBook? = null,
    val base: WorldBook? = null,
    val targetId: String? = null,
    val draftSessionId: String? = null,
    val draftBasis: EditorDraft? = null,
    val recoveryTargetId: String? = null,
    val scanDepthInput: String = "10",
    val tokenBudgetInput: String = "",
    val modal: WorldBookEntryModalState? = null,
    val eligibleCharacters: List<CharacterCard> = emptyList(),
    val pendingClearCardId: String? = null,
    val pendingClearCharacterIds: Set<String> = emptySet(),
    val dirty: Boolean = false,
    val draftPersisted: Boolean = false,
    val leavePrompt: Boolean = false,
    val problem: WorldBookEditorProblem? = null,
    val detail: String? = null,
) {
    val visibleBooks: List<WorldBook> get() = books.filter { it.name.contains(query, ignoreCase = true) }
}

/** Desktop presentation workflow; shared entities, modal materialization and repositories own semantics. */
internal class DesktopWorldBookEditorController(
    private val worlds: WorldBookRepository,
    private val drafts: EditorDraftRepository,
    private val characters: CharacterRepository,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val persistBook: suspend (WorldBook) -> Unit = worlds::save,
    private val deleteDraft: suspend (EditorDraftType, String?) -> Unit = drafts::deleteForTarget,
    private val refreshRepository: suspend () -> Unit = worlds::refreshFromStorage,
    private val persistDraftMarker: suspend (EditorDraft) -> EditorDraft = drafts::save,
) {
    private val mutableState = MutableStateFlow(DesktopWorldBookEditorState())
    val state = mutableState.asStateFlow()
    private val draftMutex = Mutex()
    private var draftJob: Job? = null
    private var leaveAction: (() -> Unit)? = null
    private var committedBook: WorldBook? = null
    private var committedDraftTargets: List<String?> = emptyList()

    suspend fun load() {
        mutableState.value = mutableState.value.copy(books = worlds.getAll())
    }

    fun search(query: String) { mutableState.value = mutableState.value.copy(query = query) }

    suspend fun openNew() {
        if (mutableState.value.problem == WorldBookEditorProblem.SAVE_COMMITTED_WARNING) return
        if (deferLeave { scope.launch { openNew() } }) return
        flushDraft()
        val saved = drafts.getForTarget(EditorDraftType.WORLD_BOOK, null)
        val ui = try { WorldBookEditorDraftUiStateCodec.decode(json, saved?.openModalState) }
        catch (error: Exception) { report(WorldBookEditorProblem.DRAFT_FAILED, error); return }
        if (saved != null && restoreCommittedDraft(saved, ui?.postCommit)) return
        if (ui?.postCommit == null) {
            val alreadyCommitted = saved?.worldBookPayload?.id?.let { worlds.getById(it) }
            if (saved != null && alreadyCommitted != null) {
                restoreLegacyCommittedNew(saved, alreadyCommitted)
                retryCleanup()
                return
            }
        }
        showBook(saved?.worldBookPayload ?: WorldBook.create("").copy(scanDepth = 10), null, null, saved)
        val markedConflict = try { ui?.postCommit?.worldBookId?.let { worlds.getById(it) } != null }
        catch (error: Exception) { report(WorldBookEditorProblem.DRAFT_FAILED, error); return }
        if (markedConflict)
            mutableState.value = mutableState.value.copy(problem = WorldBookEditorProblem.SOURCE_CHANGED)
    }

    suspend fun openExisting(id: String) {
        if (mutableState.value.problem == WorldBookEditorProblem.SAVE_COMMITTED_WARNING) return
        if (deferLeave { scope.launch { openExisting(id) } }) return
        flushDraft()
        val source = worlds.getById(id)
        val saved = drafts.getForTarget(EditorDraftType.WORLD_BOOK, id)
        val ui = try { WorldBookEditorDraftUiStateCodec.decode(json, saved?.openModalState) }
        catch (error: Exception) { report(WorldBookEditorProblem.DRAFT_FAILED, error); return }
        if (saved != null && restoreCommittedDraft(saved, ui?.postCommit, source)) return
        if (source == null && saved?.worldBookPayload == null) {
            mutableState.value = mutableState.value.copy(problem = WorldBookEditorProblem.SOURCE_DELETED)
            return
        }
        showBook(saved?.worldBookPayload ?: source!!, source, id, saved)
    }

    private fun restoreLegacyCommittedNew(draft: EditorDraft, durable: WorldBook) {
        mutableState.value = mutableState.value.copy(book = durable, base = durable,
            targetId = durable.id, draftSessionId = draft.draftSessionId, draftBasis = draft,
            scanDepthInput = durable.scanDepth.toString(),
            tokenBudgetInput = durable.tokenBudget?.toString().orEmpty(), modal = null,
            dirty = false, draftPersisted = true,
            problem = WorldBookEditorProblem.SAVE_COMMITTED_WARNING)
        committedBook = durable
        committedDraftTargets = listOf(null)
    }

    private suspend fun restoreCommittedDraft(draft: EditorDraft,
        marker: WorldBookEditorPostCommitState?, knownSource: WorldBook? = null): Boolean {
        if (marker == null || draft.worldBookPayload?.id != marker.worldBookId ||
            (draft.targetId != null && draft.targetId != marker.worldBookId)) return false
        val durable = knownSource?.takeIf { it.id == marker.worldBookId }
            ?: worlds.getById(marker.worldBookId) ?: return false
        if (EditorPostCommitFingerprint.worldBook(json, durable) != marker.expectedSemanticFingerprint) return false
        restoreLegacyCommittedNew(draft, durable)
        mutableState.value = mutableState.value.copy(recoveryTargetId = marker.recoveryTargetId)
        committedDraftTargets = buildList { marker.recoveryTargetId?.let(::add); add(draft.targetId) }
        retryCleanup()
        return true
    }

    private suspend fun showBook(book: WorldBook, source: WorldBook?, targetId: String?, saved: EditorDraft?) {
        val uiState = try { WorldBookEditorDraftUiStateCodec.decode(json, saved?.openModalState) }
        catch (error: Exception) {
            mutableState.value = mutableState.value.copy(problem = WorldBookEditorProblem.DRAFT_FAILED, detail = error.message)
            return
        }
        val modal = uiState?.entryModalState
        val scanInput = uiState?.scanDepthInput ?: book.scanDepth.toString()
        val budgetInput = uiState?.tokenBudgetInput ?: book.tokenBudget?.toString().orEmpty()
        val restoredDirty = book != source || modal != null ||
            scanInput != book.scanDepth.toString() || budgetInput != book.tokenBudget?.toString().orEmpty()
        mutableState.value = mutableState.value.copy(book = book, base = source, targetId = targetId,
            draftSessionId = saved?.draftSessionId ?: UUID.randomUUID().toString(), draftBasis = saved,
            recoveryTargetId = null, scanDepthInput = scanInput,
            tokenBudgetInput = budgetInput, modal = modal,
            eligibleCharacters = characters.getAll().filter { card ->
                card.editMode == CharacterEditMode.STRUCTURED && card.characters.any { it.name.isNotBlank() }
            }, pendingClearCardId = null, pendingClearCharacterIds = emptySet(),
            dirty = saved != null && restoredDirty, draftPersisted = saved != null,
            problem = when {
                targetId != null && source == null -> WorldBookEditorProblem.SOURCE_DELETED
                saved != null && restoredDirty && source != null && drafts.isChanged(source, saved) ->
                    WorldBookEditorProblem.SOURCE_CHANGED
                else -> null
            }, detail = null)
    }

    private fun isDirty(current: DesktopWorldBookEditorState): Boolean =
        current.modal != null || current.book != current.base ||
            current.scanDepthInput != current.book?.scanDepth?.toString().orEmpty() ||
            current.tokenBudgetInput != current.book?.tokenBudget?.toString().orEmpty()

    fun edit(change: (WorldBook) -> WorldBook) {
        val before = mutableState.value
        val book = before.book ?: return
        if (!before.dirty && before.problem == WorldBookEditorProblem.SAVE_COMMITTED_WARNING) return
        val next = change(book)
        if (next == book) return
        val updated = before.copy(book = next, problem = null, detail = null, draftPersisted = false)
        mutableState.value = updated.copy(dirty = isDirty(updated))
        scheduleDraft()
    }

    fun editScanDepth(raw: String) {
        val before = mutableState.value
        if (before.problem == WorldBookEditorProblem.SAVE_COMMITTED_WARNING) return
        val parsed = raw.toIntOrNull()?.takeIf { it >= 0 }
        val updated = before.copy(scanDepthInput = raw,
            book = before.book?.let { if (parsed == null) it else it.copy(scanDepth = parsed) },
            problem = null, draftPersisted = false)
        mutableState.value = updated.copy(dirty = isDirty(updated))
        scheduleDraft()
    }

    fun editTokenBudget(raw: String) {
        val before = mutableState.value
        if (before.problem == WorldBookEditorProblem.SAVE_COMMITTED_WARNING) return
        val parsed = if (raw.isBlank()) null else raw.toIntOrNull()?.takeIf { it >= 0 }
        val updated = before.copy(tokenBudgetInput = raw,
            book = before.book?.let { if (raw.isBlank() || parsed != null) it.copy(tokenBudget = parsed) else it },
            problem = null, draftPersisted = false)
        mutableState.value = updated.copy(dirty = isDirty(updated))
        scheduleDraft()
    }

    fun openEntry(index: Int?) {
        val before = mutableState.value
        if (before.problem == WorldBookEditorProblem.SAVE_COMMITTED_WARNING) return
        val book = before.book ?: return
        val modal = WorldBookEntryModalState.from(index, index?.let(book.entries::getOrNull))
        mutableState.value = before.copy(modal = modal, dirty = true, draftPersisted = false, problem = null)
        scheduleDraft()
    }

    fun updateEntryModal(change: (WorldBookEntryModalState) -> WorldBookEntryModalState) {
        val before = mutableState.value
        val modal = before.modal ?: return
        mutableState.value = before.copy(modal = change(modal), dirty = true, draftPersisted = false, problem = null)
        scheduleDraft()
    }

    fun dismissEntry() {
        val before = mutableState.value
        if (before.modal == null) return
        val updated = before.copy(modal = null, draftPersisted = false, problem = null)
        mutableState.value = updated.copy(dirty = isDirty(updated))
        scheduleDraft()
    }

    fun saveEntry(): Boolean {
        val before = mutableState.value
        val book = before.book ?: return false
        val modal = before.modal ?: return false
        if (!modal.hasMeaningfulEntryData()) {
            mutableState.value = before.copy(problem = WorldBookEditorProblem.ENTRY_REQUIRED)
            return false
        }
        val index = modal.editingIndex
        val existing = index?.let(book.entries::getOrNull)
        if (index != null && existing?.id != modal.originalEntryId) {
            mutableState.value = before.copy(problem = WorldBookEditorProblem.ENTRY_CHANGED)
            return false
        }
        val entry = modal.materialize(existing)
        val entries = if (index == null) book.entries + entry else book.entries.toMutableList().also { it[index] = entry }
        val updated = before.copy(book = book.copy(entries = entries), modal = null,
            dirty = true, draftPersisted = false, problem = null)
        mutableState.value = updated.copy(dirty = isDirty(updated))
        scheduleDraft()
        return true
    }

    fun deleteEntry(index: Int) = edit { book ->
        if (index !in book.entries.indices) book else book.copy(entries = book.entries.toMutableList().also { it.removeAt(index) })
    }

    fun toggleEntry(index: Int) = edit { book ->
        if (index !in book.entries.indices) book else book.copy(entries = book.entries.toMutableList().also {
            it[index] = it[index].copy(enabled = !it[index].enabled)
        })
    }

    fun importCharacters(cardId: String, characterIds: Set<String>): WorldBookCharacterImportResult? {
        val before = mutableState.value
        if (before.problem == WorldBookEditorProblem.SAVE_COMMITTED_WARNING) return null
        val book = before.book ?: return null
        val card = before.eligibleCharacters.firstOrNull { it.id == cardId } ?: return null
        val selected = card.characters.filter { it.id in characterIds }
        if (selected.isEmpty()) return null
        val result = CharacterSectionImportPolicy.importIntoWorldBook(book.entries, selected)
        edit { it.copy(entries = result.entries) }
        mutableState.value = mutableState.value.copy(pendingClearCardId = cardId,
            pendingClearCharacterIds = selected.mapTo(mutableSetOf()) { it.id })
        return result
    }

    fun keepSourceCharacters() {
        mutableState.value = mutableState.value.copy(pendingClearCardId = null,
            pendingClearCharacterIds = emptySet())
    }

    /** Destructive Character write is separate from the WorldBook draft and only follows explicit UI confirmation. */
    suspend fun confirmClearSourceCharacters(): Boolean {
        val before = mutableState.value
        val id = before.pendingClearCardId ?: return false
        if (before.pendingClearCharacterIds.isEmpty()) return false
        return try {
            val source = characters.getById(id) ?: error("Source Character missing")
            val cleared = CharacterSectionImportPolicy.clearWorldBookImportedSections(source,
                before.pendingClearCharacterIds)
            characters.update(cleared)
            mutableState.value = before.copy(pendingClearCardId = null, pendingClearCharacterIds = emptySet(),
                eligibleCharacters = characters.getAll().filter { card ->
                    card.editMode == CharacterEditMode.STRUCTURED && card.characters.any { it.name.isNotBlank() }
                }, problem = null)
            true
        } catch (error: Exception) {
            mutableState.value = before.copy(problem = WorldBookEditorProblem.CHARACTER_CLEAR_FAILED,
                detail = error.message)
            false
        }
    }

    private fun scheduleDraft() {
        draftJob?.cancel()
        draftJob = scope.launch { delay(700); persistDraft() }
    }

    suspend fun flushDraft() {
        draftJob?.cancelAndJoin()
        draftJob = null
        if (mutableState.value.dirty && !mutableState.value.draftPersisted) persistDraft()
    }

    private suspend fun persistDraft() = draftMutex.withLock {
        val before = mutableState.value
        val book = before.book ?: return@withLock
        val session = before.draftSessionId ?: return@withLock
        if (!before.dirty || before.draftPersisted) return@withLock
        val modalRaw = WorldBookEditorDraftUiStateCodec.encode(json,
            WorldBookEditorDraftUiState(entryModalState = before.modal,
                scanDepthInput = before.scanDepthInput, tokenBudgetInput = before.tokenBudgetInput))
        val draft = before.draftBasis?.copy(worldBookPayload = book, openModalState = modalRaw)
            ?: drafts.worldBookDraft(before.targetId, session, book, before.base, modalRaw)
        try {
            val saved = drafts.save(draft)
            if (mutableState.value.book == book && mutableState.value.modal == before.modal &&
                mutableState.value.scanDepthInput == before.scanDepthInput &&
                mutableState.value.tokenBudgetInput == before.tokenBudgetInput &&
                mutableState.value.draftSessionId == session) {
                mutableState.value = mutableState.value.copy(draftBasis = saved, draftPersisted = true)
            }
        } catch (error: Exception) { report(WorldBookEditorProblem.DRAFT_FAILED, error) }
    }

    private suspend fun validate(before: DesktopWorldBookEditorState): Boolean {
        val book = before.book ?: return false
        val issue = when {
            NamePolicy.normalize(book.name).isBlank() -> WorldBookEditorProblem.NAME_REQUIRED
            before.scanDepthInput.toIntOrNull()?.let { it >= 0 } != true -> WorldBookEditorProblem.SCAN_DEPTH_INVALID
            before.tokenBudgetInput.isNotBlank() &&
                before.tokenBudgetInput.toIntOrNull()?.let { it >= 0 } != true ->
                WorldBookEditorProblem.TOKEN_BUDGET_INVALID
            NamePolicy.conflict(book.name, worlds.getAll().map { it.id to it.name }, before.targetId) != null ->
                WorldBookEditorProblem.DUPLICATE_NAME
            else -> null
        }
        if (issue != null) mutableState.value = before.copy(problem = issue)
        return issue == null
    }

    suspend fun save(forceOverwrite: Boolean = false): Boolean {
        if (mutableState.value.problem == WorldBookEditorProblem.SAVE_COMMITTED_WARNING) return false
        if (!mutableState.value.dirty) return cleanObsoleteDraft()
        flushDraft()
        val before = mutableState.value
        val book = before.book ?: return false
        if (!before.draftPersisted) return false
        if (before.modal != null) {
            mutableState.value = before.copy(problem = WorldBookEditorProblem.ENTRY_MODAL_OPEN)
            return false
        }
        if (!validate(before)) return false
        val source = try { before.targetId?.let { worlds.getById(it) } }
        catch (error: Exception) { report(WorldBookEditorProblem.SAVE_FAILED, error); return false }
        val newIdConflict = try { before.targetId == null && worlds.getById(book.id) != null }
        catch (error: Exception) { report(WorldBookEditorProblem.SAVE_FAILED, error); return false }
        if (newIdConflict) {
            mutableState.value = before.copy(problem = WorldBookEditorProblem.SOURCE_CHANGED)
            return false
        }
        if (before.targetId != null && source == null) {
            mutableState.value = before.copy(problem = WorldBookEditorProblem.SOURCE_DELETED)
            return false
        }
        if (!forceOverwrite && source != null && before.draftBasis != null &&
            drafts.isChanged(source, before.draftBasis)) {
            mutableState.value = before.copy(problem = WorldBookEditorProblem.SOURCE_CHANGED)
            return false
        }
        val retained = (source ?: book).copy(name = NamePolicy.normalize(book.name),
            description = book.description, entries = book.entries,
            scanDepth = before.scanDepthInput.toInt(),
            tokenBudget = before.tokenBudgetInput.takeIf(String::isNotBlank)?.toInt(),
            recursiveScanning = book.recursiveScanning,
            caseSensitive = book.caseSensitive, matchWholeWords = book.matchWholeWords)
        val activeDraft = try { drafts.getForTarget(EditorDraftType.WORLD_BOOK, before.targetId)
            ?: error("WorldBook draft missing before durable save") }
        catch (error: Exception) { report(WorldBookEditorProblem.SAVE_FAILED, error); return false }
        val markedDraft = try {
            val previousUi = WorldBookEditorDraftUiStateCodec.decode(json, activeDraft.openModalState)
                ?: WorldBookEditorDraftUiState()
            val marker = WorldBookEditorPostCommitState(retained.id,
                EditorPostCommitFingerprint.worldBook(json, retained), before.recoveryTargetId)
            val markerRaw = WorldBookEditorDraftUiStateCodec.encode(json, previousUi.copy(postCommit = marker))
            persistDraftMarker(activeDraft.copy(openModalState = markerRaw))
            val confirmed = drafts.getForTarget(EditorDraftType.WORLD_BOOK, before.targetId)
            require(confirmed != null && confirmed.draftSessionId == before.draftSessionId &&
                confirmed.openModalState == markerRaw) {
                "WorldBook post-commit recovery marker was not durable"
            }
            confirmed
        } catch (error: Exception) { report(WorldBookEditorProblem.SAVE_FAILED, error); return false }
        try { persistBook(retained) }
        catch (error: Exception) {
            // Repository stamps updatedAt before the write. Compare content without the pre-save timestamp.
            val durable = runCatching { worlds.getById(retained.id) }.getOrNull()
            if (durable == null || durable.copy(updatedAt = retained.updatedAt) != retained) {
                report(WorldBookEditorProblem.SAVE_FAILED, error)
                return false
            }
            // The durable write committed; retry will reconcile the cache.
        }
        val durable = runCatching { worlds.getById(retained.id) }.getOrNull()
        val committed = durable ?: retained
        committedBook = committed
        committedDraftTargets = buildList {
            before.recoveryTargetId?.let(::add)
            add(before.targetId)
        }
        mutableState.value = before.copy(book = committed, base = committed, targetId = committed.id,
            draftBasis = markedDraft, dirty = false,
            scanDepthInput = committed.scanDepth.toString(), tokenBudgetInput = committed.tokenBudget?.toString().orEmpty(),
            problem = WorldBookEditorProblem.SAVE_COMMITTED_WARNING)
        retryCleanup()
        return true
    }

    suspend fun saveAsNew(): Boolean {
        if (mutableState.value.problem == WorldBookEditorProblem.SAVE_COMMITTED_WARNING) return false
        flushDraft()
        val before = mutableState.value
        val book = before.book ?: return false
        val existing = try { drafts.getForTarget(EditorDraftType.WORLD_BOOK, null) }
        catch (error: Exception) { report(WorldBookEditorProblem.DRAFT_FAILED, error); return false }
        if (existing != null && existing.draftSessionId != before.draftSessionId) {
            mutableState.value = before.copy(problem = WorldBookEditorProblem.NEW_DRAFT_EXISTS)
            return false
        }
        val now = System.currentTimeMillis()
        val copy = book.copy(id = UUID.randomUUID().toString(),
            name = NamePolicy.nextCopyName(book.name, worlds.getAll().map { it.name }),
            sourcePresetKey = null, sourcePresetVersion = null, createdAt = now, updatedAt = now)
        val session = UUID.randomUUID().toString()
        val draft = drafts.worldBookDraft(null, session, copy, null,
            WorldBookEditorDraftUiStateCodec.encode(json,
                WorldBookEditorDraftUiState(entryModalState = before.modal,
                    scanDepthInput = before.scanDepthInput, tokenBudgetInput = before.tokenBudgetInput)))
        try { drafts.save(draft) }
        catch (error: Exception) { report(WorldBookEditorProblem.DRAFT_FAILED, error); return false }
        mutableState.value = before.copy(book = copy, base = null, targetId = null,
            draftSessionId = session, draftBasis = draft, recoveryTargetId = before.targetId,
            dirty = true, draftPersisted = true, problem = null)
        return true
    }

    private suspend fun cleanupDrafts(targets: List<String?>,
        stopOnBlocking: Boolean = false): Map<String?, DesktopEditorDraftCleanup> {
        val results = linkedMapOf<String?, DesktopEditorDraftCleanup>()
        for (target in targets.distinct()) {
            val result = deleteEditorDraftConfirmed(drafts, EditorDraftType.WORLD_BOOK, target, deleteDraft)
            results[target] = result
            if (stopOnBlocking && result.blocking) break
        }
        return results
    }

    private fun needsCleanDraftCleanup(current: DesktopWorldBookEditorState): Boolean =
        !current.dirty && current.book != null && current.base != null &&
            current.book == current.base && current.modal == null &&
            current.draftBasis?.targetId == current.targetId && current.draftBasis != null &&
            current.problem != WorldBookEditorProblem.SAVE_COMMITTED_WARNING

    private suspend fun cleanObsoleteDraft(): Boolean = draftMutex.withLock {
        val before = mutableState.value
        if (!needsCleanDraftCleanup(before)) return@withLock true
        val pending = cleanupDrafts(listOf(before.targetId))[before.targetId]?.blocking != false
        val current = mutableState.value
        if (current.book != before.book || current.modal != null || current.dirty ||
            current.draftSessionId != before.draftSessionId) return@withLock false
        if (pending) {
            mutableState.value = current.copy(problem = WorldBookEditorProblem.CLEAN_DRAFT_WARNING)
            return@withLock false
        }
        mutableState.value = current.copy(draftBasis = null, draftPersisted = false, problem = null)
        true
    }

    suspend fun retryCleanup() {
        val before = mutableState.value
        if (before.dirty || before.book == null) return
        if (committedBook == null && needsCleanDraftCleanup(before)) { cleanObsoleteDraft(); return }
        val committed = committedBook ?: return
        val durable = runCatching { worlds.getById(committed.id) }.getOrNull()
        val refreshed = runCatching { refreshRepository(); worlds.getAll() }
        val cacheReady = durable != null && durable.copy(updatedAt = committed.updatedAt) == committed &&
            refreshed.getOrNull()?.any { it == durable } == true
        val cleanup = if (cacheReady) cleanupDrafts(committedDraftTargets, stopOnBlocking = true) else emptyMap()
        val pending = !cacheReady || cleanup.size != committedDraftTargets.distinct().size ||
            cleanup.values.any(DesktopEditorDraftCleanup::blocking)
        val activeRemoved = before.draftBasis == null || cleanup[before.draftBasis.targetId]?.removed == true
        val recoveryRemoved = before.recoveryTargetId?.let { cleanup[it]?.removed == true } ?: true
        mutableState.value = before.copy(book = durable ?: before.book, base = durable ?: before.base,
            books = refreshed.getOrNull() ?: before.books,
            draftBasis = if (activeRemoved) null else before.draftBasis,
            recoveryTargetId = if (recoveryRemoved) null else before.recoveryTargetId,
            draftPersisted = !activeRemoved,
            problem = if (pending) WorldBookEditorProblem.SAVE_COMMITTED_WARNING else null)
        if (!pending) { committedBook = null; committedDraftTargets = emptyList() }
    }

    suspend fun discard() {
        draftJob?.cancelAndJoin()
        draftJob = null
        val before = mutableState.value
        if (!before.dirty && before.problem == WorldBookEditorProblem.SAVE_COMMITTED_WARNING) return
        val target = if (before.draftBasis != null) before.draftBasis.targetId else before.targetId
        if (cleanupDrafts(listOf(target))[target]?.removed != true) {
            mutableState.value = before.copy(problem = WorldBookEditorProblem.DRAFT_FAILED)
            return
        }
        mutableState.value = before.copy(book = null, base = null, modal = null, targetId = null,
            draftBasis = null, draftSessionId = null, dirty = false, draftPersisted = false,
            leavePrompt = false, problem = null)
        leaveAction?.also { leaveAction = null; it() }
    }

    fun requestLeave(action: () -> Unit) {
        if (mutableState.value.problem == WorldBookEditorProblem.SAVE_COMMITTED_WARNING) return
        if (deferLeave(action)) return
        if (needsCleanDraftCleanup(mutableState.value)) {
            scope.launch { if (cleanObsoleteDraft() && !mutableState.value.dirty) action() }
        } else action()
    }
    private fun deferLeave(action: () -> Unit): Boolean {
        if (mutableState.value.book != null && mutableState.value.dirty) {
            leaveAction = action
            mutableState.value = mutableState.value.copy(leavePrompt = true)
            return true
        }
        return false
    }
    fun continueEditing() { leaveAction = null; mutableState.value = mutableState.value.copy(leavePrompt = false) }
    suspend fun keepDraftAndLeave() {
        if (mutableState.value.problem == WorldBookEditorProblem.SAVE_COMMITTED_WARNING) return
        flushDraft()
        val before = mutableState.value
        if (before.problem == WorldBookEditorProblem.SAVE_COMMITTED_WARNING) return
        if (before.dirty && !before.draftPersisted) {
            mutableState.value = before.copy(problem = WorldBookEditorProblem.DRAFT_FAILED)
            return
        }
        mutableState.value = before.copy(book = null, base = null, modal = null, leavePrompt = false)
        leaveAction?.also { leaveAction = null; it() }
    }
    suspend fun saveAndLeave() {
        if (mutableState.value.problem == WorldBookEditorProblem.SAVE_COMMITTED_WARNING) return
        if (save() && mutableState.value.problem != WorldBookEditorProblem.SAVE_COMMITTED_WARNING) {
            closeClean()
            leaveAction?.also { leaveAction = null; it() }
        } else if (mutableState.value.problem == WorldBookEditorProblem.SAVE_COMMITTED_WARNING) {
            leaveAction = null
            mutableState.value = mutableState.value.copy(leavePrompt = false)
        }
    }
    fun closeClean() {
        if (mutableState.value.dirty) { requestLeave { closeClean() }; return }
        if (needsCleanDraftCleanup(mutableState.value)) { requestLeave { closeClean() }; return }
        if (mutableState.value.problem == WorldBookEditorProblem.SAVE_COMMITTED_WARNING) return
        mutableState.value = mutableState.value.copy(book = null, base = null, modal = null, leavePrompt = false)
    }
    suspend fun closeAndDrain() {
        flushDraft()
        when {
            mutableState.value.problem == WorldBookEditorProblem.SAVE_COMMITTED_WARNING -> retryCleanup()
            needsCleanDraftCleanup(mutableState.value) -> cleanObsoleteDraft()
        }
        scope.coroutineContext[Job]?.cancel()
    }
    private fun report(problem: WorldBookEditorProblem, error: Throwable) {
        mutableState.value = mutableState.value.copy(problem = problem, detail = error.message)
    }
}
