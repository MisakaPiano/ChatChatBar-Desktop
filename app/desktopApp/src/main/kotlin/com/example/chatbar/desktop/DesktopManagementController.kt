package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.EditorDraft
import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.data.local.entity.PresetEntry
import com.example.chatbar.data.local.entity.PresetType
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.card.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.example.chatbar.domain.card.CharacterTransferPostCommitOperation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

internal val DesktopTransferKind.draftType: EditorDraftType get() = when (this) {
    DesktopTransferKind.CHARACTER -> EditorDraftType.CHARACTER_CARD
    DesktopTransferKind.FORMAT -> EditorDraftType.FORMAT_CARD
    DesktopTransferKind.WORLD_BOOK -> EditorDraftType.WORLD_BOOK
}

internal data class DesktopManagementDraftRows(val badges: Map<String, EditorDraft>, val recoverable: List<EditorDraft>)
internal enum class DesktopPresetAvailability { RECOVERABLE, UPDATE_AVAILABLE, PRESENT }
internal data class DesktopManagementPresetRow(val entry: PresetEntry, val availability: DesktopPresetAvailability)
internal fun desktopManagementPresetRows(entries: List<PresetEntry>, entities: List<Pair<String?, Int?>>): List<DesktopManagementPresetRow> =
    entries.map { entry ->
        val matching = entities.filter { it.first == entry.presetKey }
        DesktopManagementPresetRow(entry, when {
            matching.isEmpty() -> DesktopPresetAvailability.RECOVERABLE
            matching.any { (it.second ?: 0) < entry.version } -> DesktopPresetAvailability.UPDATE_AVAILABLE
            else -> DesktopPresetAvailability.PRESENT
        })
    }
internal fun desktopManagementDraftRows(drafts: List<EditorDraft>, kind: DesktopTransferKind, entityIds: Set<String>): DesktopManagementDraftRows {
    val matching = drafts.filter { it.entityType == kind.draftType }
    return DesktopManagementDraftRows(
        matching.filter { it.targetId in entityIds }.associateBy { it.targetId!! },
        matching.filter { it.targetId == null || it.targetId !in entityIds },
    )
}

internal sealed interface DesktopManagementDeletion {
    val name: String
    data class Entity(val kind: DesktopTransferKind, val id: String, override val name: String) : DesktopManagementDeletion
    data class Draft(val draft: EditorDraft) : DesktopManagementDeletion { override val name get() = draft.title }
}

internal data class DesktopManagementState(
    val busy: Boolean = false,
    val pendingDeletion: DesktopManagementDeletion? = null,
    val warning: DesktopUiText? = null,
    val error: String? = null,
    val characterReferences: List<String> = emptyList(),
    val sessionReferences: List<String> = emptyList(),
)

/** UI orchestration only: repositories own lists, transfers own copies/packages, editors own recovery. */
internal class DesktopManagementController(
    private val characters: CharacterRepository,
    private val formats: FormatCardRepository,
    private val worlds: WorldBookRepository,
    private val chats: ChatRepository,
    private val draftRepository: EditorDraftRepository,
    private val characterTransfers: CharacterCardTransferCore,
    private val formatTransfers: FormatCardTransferService,
    private val worldTransfers: WorldBookTransferService,
    val transfer: DesktopTypedTransferController,
    private val characterEditor: DesktopCharacterEditorController,
    private val formatEditor: DesktopFormatCardEditorController,
    private val worldEditor: DesktopWorldBookEditorController,
    private val discardAssets: (String) -> Unit,
    private val deleteDraft: suspend (EditorDraftType, String?) -> Unit = draftRepository::deleteForTarget,
    private val afterReconcile: suspend () -> Unit = {},
    private val afterCharacterDuplicateCommit: (String) -> Unit = {},
    private val presetSource: DesktopPresetSource? = null,
) {
    private val mutableState = MutableStateFlow(DesktopManagementState())
    val state = mutableState.asStateFlow()
    val drafts = draftRepository.drafts
    private val operations = Mutex()

    suspend fun refresh() = operation(clearStatus = false) { }

    fun presetEntries(kind: DesktopTransferKind): List<PresetEntry> = presetSource?.entries(when (kind) {
        DesktopTransferKind.CHARACTER -> PresetType.CHARACTER
        DesktopTransferKind.FORMAT -> PresetType.FORMAT
        DesktopTransferKind.WORLD_BOOK -> PresetType.WORLD_BOOK
    }).orEmpty()

    fun hasPresetUpdate(kind: DesktopTransferKind, key: String?, version: Int?): Boolean =
        key != null && version != null && presetEntries(kind).any { it.presetKey == key && it.version > version }

    suspend fun recoverPreset(kind: DesktopTransferKind, entry: PresetEntry) = operation {
        check(editorClosed(kind)) { "Close the editor before importing" }
        check(transfer.state.value.pendingConflict == null) { "Resolve the pending import first" }
        check(entry in presetEntries(kind)) { "Unknown bundled preset" }
        transfer.recoverPreset(entry)
    }

    private suspend fun reconcile() {
        characters.refreshFromStorage()
        formats.refreshFromStorage()
        worlds.refreshFromStorage()
        draftRepository.refreshFromStorage()
        characterEditor.load()
        formatEditor.load()
        worldEditor.load()
        afterReconcile()
    }

    private fun editorClosed(kind: DesktopTransferKind) = when (kind) {
        DesktopTransferKind.CHARACTER -> characterEditor.state.value.card == null
        DesktopTransferKind.FORMAT -> formatEditor.state.value.card == null
        DesktopTransferKind.WORLD_BOOK -> worldEditor.state.value.book == null
    }

    fun requestDelete(kind: DesktopTransferKind, id: String, name: String) {
        if (!mutableState.value.busy && editorClosed(kind) && transfer.state.value.pendingConflict == null)
            mutableState.value = mutableState.value.copy(pendingDeletion = DesktopManagementDeletion.Entity(kind, id, name))
    }

    fun requestDiscard(draft: EditorDraft) {
        if (!mutableState.value.busy && editorClosed(DesktopTransferKind.entries.single { it.draftType == draft.entityType }))
            mutableState.value = mutableState.value.copy(pendingDeletion = DesktopManagementDeletion.Draft(draft))
    }

    fun cancelDeletion() { if (!mutableState.value.busy) mutableState.value = mutableState.value.copy(pendingDeletion = null) }

    suspend fun confirmDeletion() = operation {
        val pending = mutableState.value.pendingDeletion ?: return@operation
        val kind = when (pending) {
            is DesktopManagementDeletion.Entity -> pending.kind
            is DesktopManagementDeletion.Draft -> DesktopTransferKind.entries.single { it.draftType == pending.draft.entityType }
        }
        check(editorClosed(kind)) { "Close the editor before deleting" }
        // Consume once. A post-commit cleanup warning must never invite replaying entity deletion.
        mutableState.value = mutableState.value.copy(pendingDeletion = null)
        when (pending) {
            is DesktopManagementDeletion.Entity -> when (pending.kind) {
                DesktopTransferKind.CHARACTER -> characterTransfers.deleteCard(pending.id)
                DesktopTransferKind.FORMAT -> formats.delete(pending.id)
                DesktopTransferKind.WORLD_BOOK -> {
                    characters.refreshFromStorage()
                    val characterRefs = characters.getAll().filter { pending.id in it.worldBookIds }.map { it.name }
                    val sessionRefs = chats.getAllSessions().filter { pending.id in it.extraWorldBookIds }.map { it.title }
                    if (characterRefs.isNotEmpty() || sessionRefs.isNotEmpty()) {
                        mutableState.value = mutableState.value.copy(warning = DesktopUiText.MANAGE_WORLD_REFERENCED,
                            characterReferences = characterRefs.take(3), sessionReferences = sessionRefs.take(3))
                    } else worlds.delete(pending.id)
                }
            }
            is DesktopManagementDeletion.Draft -> {
                val expected = pending.draft
                val current = draftRepository.getForTarget(expected.entityType, expected.targetId)
                check(current == expected) { "Draft changed; refresh and confirm again" }
                val result = deleteEditorDraftConfirmed(draftRepository, expected.entityType, expected.targetId, deleteDraft)
                if (result.removed) {
                    if (expected.entityType == EditorDraftType.CHARACTER_CARD) {
                        try { discardAssets(expected.draftSessionId) }
                        catch (error: Exception) {
                            if (error is CancellationException) throw error
                            mutableState.value = mutableState.value.copy(warning = DesktopUiText.MANAGE_DRAFT_CLEANUP_WARNING)
                        }
                    }
                    if (result.errors.isNotEmpty()) mutableState.value = mutableState.value.copy(warning = DesktopUiText.MANAGE_DRAFT_CLEANUP_WARNING)
                } else error(result.errors.joinToString { it.message.orEmpty() }.ifBlank { "Draft deletion was not confirmed" })
            }
        }
    }

    suspend fun duplicate(kind: DesktopTransferKind, id: String) = operation {
        check(editorClosed(kind)) { "Close the editor before duplicating" }
        when (kind) {
            DesktopTransferKind.CHARACTER -> {
                val copy = characterTransfers.duplicate(id)
                afterCharacterDuplicateCommit(copy.id)
            }
            DesktopTransferKind.FORMAT -> formatTransfers.duplicate(id)
            DesktopTransferKind.WORLD_BOOK -> worldTransfers.duplicate(id)
        }
    }

    suspend fun openDraft(draft: EditorDraft) = operation {
        val current = draftRepository.getForTarget(draft.entityType, draft.targetId)
        check(current?.draftSessionId == draft.draftSessionId) { "Draft changed; refresh and open again" }
        val target = draft.targetId
        when (draft.entityType) {
            EditorDraftType.CHARACTER_CARD -> if (target == null) characterEditor.openNew() else characterEditor.openExisting(target)
            EditorDraftType.FORMAT_CARD -> if (target == null) formatEditor.openNew() else formatEditor.openExisting(target)
            EditorDraftType.WORLD_BOOK -> if (target == null) worldEditor.openNew() else worldEditor.openExisting(target)
        }
    }

    suspend fun import(kind: DesktopTransferKind) = operation {
        check(editorClosed(kind)) { "Close the editor before importing" }
        check(transfer.state.value.pendingConflict == null) { "Resolve the pending import first" }
        when (kind) {
            DesktopTransferKind.CHARACTER -> transfer.chooseAndImportCharacter()
            DesktopTransferKind.FORMAT -> transfer.chooseAndImportFormat()
            DesktopTransferKind.WORLD_BOOK -> transfer.chooseAndImportWorldBook()
        }
    }

    suspend fun export(kind: DesktopTransferKind, id: String, alternate: Boolean = false) = operation {
        when (kind) {
            DesktopTransferKind.CHARACTER -> if (alternate) transfer.exportCharacterPng(id) else transfer.exportCharacterJson(id)
            DesktopTransferKind.FORMAT -> transfer.exportFormatJson(id)
            DesktopTransferKind.WORLD_BOOK -> if (alternate) transfer.exportWorldBookSillyTavern(id) else transfer.exportWorldBookJson(id)
        }
    }

    suspend fun resolveConflict(action: DesktopTransferConflictAction) = operation { transfer.resolveConflict(action) }

    private suspend fun operation(clearStatus: Boolean = true, block: suspend () -> Unit) {
        if (!operations.tryLock()) return
        mutableState.value = if (clearStatus) mutableState.value.copy(busy = true, warning = null, error = null,
            characterReferences = emptyList(), sessionReferences = emptyList()) else mutableState.value.copy(busy = true)
        var cancelled: CancellationException? = null
        var committed: CharacterTransferPostCommitException? = null
        val priorTransferNotice = transfer.state.value.committedNotice
        val priorTypedNotice = transfer.state.value.typedNotice
        try {
            try { block() }
            catch (error: CharacterTransferPostCommitException) {
                committed = error
            }
            catch (error: Exception) {
                if (error is CancellationException) cancelled = error
                else mutableState.value = mutableState.value.copy(error = error.message ?: error.toString())
            }
            // Never refresh typed-transfer state here: doing so would discard a pending conflict.
            val refreshFailure = withContext(NonCancellable) { runCatching { reconcile() }.exceptionOrNull() }
            if (refreshFailure is CancellationException) cancelled = refreshFailure
            committed?.let { outcome ->
                val deleted = outcome.operation == CharacterTransferPostCommitOperation.DELETE
                mutableState.value = mutableState.value.copy(warning = when {
                    deleted && refreshFailure == null -> DesktopUiText.MANAGE_DELETE_COMMITTED
                    deleted -> DesktopUiText.MANAGE_DELETE_REFRESH_FAILED
                    refreshFailure == null -> DesktopUiText.MANAGE_DUPLICATE_COMMITTED
                    else -> DesktopUiText.MANAGE_DUPLICATE_REFRESH_FAILED
                })
            }
            if (committed == null && refreshFailure != null && refreshFailure !is CancellationException) {
                if (transfer.state.value.committedNotice?.let { it !== priorTransferNotice } == true)
                    transfer.markCommittedRefreshFailed()
                else if (transfer.state.value.typedNotice?.let { it !== priorTypedNotice } == true)
                    transfer.markTypedRefreshFailed()
                else mutableState.value = mutableState.value.copy(error = refreshFailure.message ?: refreshFailure.toString())
            }
        } finally {
            mutableState.value = mutableState.value.copy(busy = false)
            operations.unlock()
        }
        cancelled?.let { throw it }
    }
}
