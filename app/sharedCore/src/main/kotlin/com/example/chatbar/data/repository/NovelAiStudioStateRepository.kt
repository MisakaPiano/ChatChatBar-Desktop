package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry
import com.example.chatbar.domain.image.NovelAiGenerationHistoryImage
import com.example.chatbar.domain.image.NovelAiHistoryApplyMode
import com.example.chatbar.domain.image.NovelAiHistoryFoldPreference
import com.example.chatbar.domain.image.NovelAiHistoryImageDeleteResult
import com.example.chatbar.domain.image.NovelAiHistoryImageSelection
import com.example.chatbar.domain.image.NovelAiHistoryDeletionPolicy
import com.example.chatbar.domain.image.NovelAiImageUseTarget
import com.example.chatbar.domain.image.NovelAiStudioDraft
import com.example.chatbar.domain.image.NovelAiStudioUndoDraft
import com.example.chatbar.domain.image.NovelAiGuidanceEditorCheckpoint
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiGenerationDefaults
import com.example.chatbar.domain.image.NovelAiPromptPlan
import com.example.chatbar.domain.image.applyDesignedPromptPlan
import com.example.chatbar.domain.image.applyReversePromptPlan
import com.example.chatbar.domain.image.applyHistoryRecipe
import com.example.chatbar.domain.image.ownedAssetPaths
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

open class NovelAiStudioStateRepository(
    protected val storage: JsonFileStorage,
    private val preserveForeignEditHistory: Boolean = false,
) {
    private val _draft = MutableStateFlow<NovelAiStudioDraft?>(null)
    val draft: StateFlow<NovelAiStudioDraft?> = _draft.asStateFlow()
    private val _pendingGuidanceEditorTarget = MutableStateFlow<NovelAiImageUseTarget?>(null)
    val pendingGuidanceEditorTarget: StateFlow<NovelAiImageUseTarget?> =
        _pendingGuidanceEditorTarget.asStateFlow()
    private val draftMutex = Mutex()
    private val draftStateLock = Any()
    private var draftDirty = false
    private val editorGuards = linkedMapOf<Any, () -> Boolean>()
    private val _lanSyncRevision = MutableStateFlow(0L)
    val lanSyncRevision: StateFlow<Long> = _lanSyncRevision.asStateFlow()
    private val undoMutex = Mutex()
    private val _undoAvailable = MutableStateFlow(false)
    val undoAvailable: StateFlow<Boolean> = _undoAvailable.asStateFlow()
    protected val historyMutex = Mutex()
    private val foreignHistory by lazy { ForeignStudioEditHistoryCustody(storage) }

    /** Guards cover local editors whose content has not reached stageDraft yet. */
    fun registerLanSyncEditorGuard(owner: Any, uncommitted: () -> Boolean) {
        synchronized(editorGuards) { editorGuards[owner] = uncommitted }
    }

    fun unregisterLanSyncEditorGuard(owner: Any) {
        synchronized(editorGuards) { editorGuards.remove(owner) }
    }

    fun requireLanSyncEditorsCommitted() {
        check(synchronized(editorGuards) { editorGuards.values.toList() }.none { it() }) { "SYNC_STUDIO_EDITOR_UNCOMMITTED" }
    }

    /** Called after platform writers drain and while storage admission is exclusive. */
    open suspend fun prepareForLanSync() {
        requireLanSyncEditorsCommitted()
        if (synchronized(draftStateLock) { draftDirty }) flushLatestDraft()
        if (preserveForeignEditHistory) foreignHistory.prepareForLanSync()
    }

    /** Refresh an existing owner without creating a default file or resolving an account/model. */
    open suspend fun reloadAfterLanSync() = draftMutex.withDataAccess {
        val loaded = storage.loadSingleton(DRAFT_ENTITY, NovelAiStudioDraft.serializer())
        val undo = storage.loadSingleton(UNDO_ENTITY, NovelAiStudioUndoDraft.serializer())
        synchronized(draftStateLock) {
            val previous = _draft.value
            if (previous != null) {
                val next = loaded ?: emptyDraft().copy(aiDesignModelId = previous.aiDesignModelId)
                if (previous.copy(contentRevision = 0, promptContentRevision = 0, updatedAt = 0) !=
                    next.copy(contentRevision = 0, promptContentRevision = 0, updatedAt = 0)) {
                    val revision = maxOf(previous.contentRevision, previous.promptContentRevision,
                        next.contentRevision, next.promptContentRevision) + 1L
                    _draft.value = next.copy(contentRevision = revision, promptContentRevision = revision)
                }
            }
            draftDirty = false
        }
        _undoAvailable.value = undo?.draft != null
        _pendingGuidanceEditorTarget.value = null
        _lanSyncRevision.value++
    }

    private fun emptyDraft() = NovelAiStudioDraft(
        followDefaultNovelAiImageModel = true,
        v45Settings = NovelAiGenerationDefaults.forModel(NovelAiImageModel.V4_5_FULL),
        v5Settings = NovelAiGenerationDefaults.forModel(NovelAiImageModel.V5_FULL))

    /** Persistent recipes and foreign undo steps also own native guidance images. */
    suspend fun <T> withRetainedGuidanceResources(additional: Set<String>, operation: suspend (Set<String>) -> T): T =
        withStudioDataAccess { draftMutex.withLock { historyMutex.withLock {
            val retained = additional.toMutableSet()
            synchronized(draftStateLock) { _draft.value?.imageGuidance?.ownedAssetPaths()?.let(retained::addAll) }
            storage.loadSingleton(DRAFT_ENTITY, NovelAiStudioDraft.serializer())?.imageGuidance?.ownedAssetPaths()?.let(retained::addAll)
            storage.loadSingleton(UNDO_ENTITY, NovelAiStudioUndoDraft.serializer())?.draft?.imageGuidance?.ownedAssetPaths()?.let(retained::addAll)
            storage.loadSingleton(GUIDANCE_CHECKPOINT_ENTITY, NovelAiGuidanceEditorCheckpoint.serializer())?.guidance?.ownedAssetPaths()?.let(retained::addAll)
            foreignHistory.retainedHistoryDrafts().forEach { retained += it.imageGuidance.ownedAssetPaths() }
            storage.loadAll(HISTORY_ENTITY, NovelAiGenerationHistoryEntry.serializer()).forEach { retained += it.recipe.imageGuidance.ownedAssetPaths() }
            operation(retained)
        } } }

    protected suspend fun <T> withStudioDataAccess(operation: suspend () -> T): T =
        storage.withNormalOperation(operation)

    private suspend fun <T> Mutex.withDataAccess(operation: suspend () -> T): T =
        withStudioDataAccess { withLock { operation() } }

    suspend fun initialize() {
        storage.loadAll(HISTORY_ENTITY, NovelAiGenerationHistoryEntry.serializer())
    }

    suspend fun loadHistoryFoldPreferences(): Map<Int, NovelAiHistoryFoldPreference> =
        storage.loadAll(HISTORY_FOLD_ENTITY, NovelAiHistoryFoldPreference.serializer())
            .associateBy { it.depth }

    suspend fun saveHistoryFoldPreference(preference: NovelAiHistoryFoldPreference) {
        require(preference.depth >= 0)
        storage.saveEntity(
            HISTORY_FOLD_ENTITY,
            preference.depth.toString(),
            preference,
            NovelAiHistoryFoldPreference.serializer()
        )
    }

    suspend fun loadDraft(): NovelAiStudioDraft {
        synchronized(draftStateLock) {
            _draft.value?.let { return it }
        }
        return draftMutex.withDataAccess {
            synchronized(draftStateLock) {
                _draft.value?.let { return@withDataAccess it }
            }
            val loaded = storage.loadSingleton(DRAFT_ENTITY, NovelAiStudioDraft.serializer())
                ?: emptyDraft()
            synchronized(draftStateLock) {
                _draft.value ?: loaded.also { _draft.value = it }
            }
        }
    }

    fun stageDraft(
        resetPromptEditors: Boolean = false,
        transform: (NovelAiStudioDraft) -> NovelAiStudioDraft
    ): NovelAiStudioDraft = stageDraftChange(resetPromptEditors, transform).after

    private data class DraftChange(val before: NovelAiStudioDraft, val wasDirty: Boolean, val after: NovelAiStudioDraft)

    private fun stageDraftChange(resetPromptEditors: Boolean, transform: (NovelAiStudioDraft) -> NovelAiStudioDraft): DraftChange = synchronized(draftStateLock) {
        val current = checkNotNull(_draft.value) { "NovelAI Studio draft is not loaded" }
        val wasDirty = draftDirty
        val transformed = transform(current)
        if (transformed == current) return@synchronized DraftChange(current, wasDirty, current)
        val revision = current.contentRevision + 1L
        val next = transformed.copy(
            contentRevision = revision,
            promptContentRevision = if (resetPromptEditors) {
                revision
            } else {
                current.promptContentRevision
            },
            updatedAt = maxOf(System.currentTimeMillis(), current.updatedAt + 1L)
        )
        draftDirty = true
        _draft.value = next
        DraftChange(current, wasDirty, next)
    }

    suspend fun flushLatestDraft(): NovelAiStudioDraft {
        if (_draft.value == null) loadDraft()
        return draftMutex.withDataAccess {
            while (true) {
                val snapshot = synchronized(draftStateLock) {
                    checkNotNull(_draft.value) { "NovelAI Studio draft is not loaded" }
                }
                val write: suspend () -> Unit = { storage.saveSingleton(DRAFT_ENTITY, snapshot, NovelAiStudioDraft.serializer()) }
                if (preserveForeignEditHistory) foreignHistory.persist(snapshot, write) else write()
                val latest = synchronized(draftStateLock) {
                    checkNotNull(_draft.value).also { if (it.contentRevision == snapshot.contentRevision) draftDirty = false }
                }
                if (latest.contentRevision == snapshot.contentRevision) return@withDataAccess latest
            }
            error("unreachable")
        }
    }

    open suspend fun updateDraft(
        resetPromptEditors: Boolean = false,
        transform: (NovelAiStudioDraft) -> NovelAiStudioDraft
    ): NovelAiStudioDraft = withStudioDataAccess {
        val change = stageDraftChange(resetPromptEditors, transform)
        val before = change.before
        val next = change.after
        try {
            flushLatestDraft()
            next
        } catch (error: Throwable) {
            val outcome = if (preserveForeignEditHistory) foreignHistory.outcomeAfterFailure(next) else ForeignStudioEditHistoryCustody.Outcome.Unmanaged
            synchronized(draftStateLock) {
                if (_draft.value?.contentRevision == next.contentRevision) {
                    when (outcome) {
                        ForeignStudioEditHistoryCustody.Outcome.Unmanaged -> {
                            _draft.value = before
                            draftDirty = change.wasDirty
                        }
                        is ForeignStudioEditHistoryCustody.Outcome.Proved -> {
                            if (!outcome.requestedWasPublished && change.wasDirty) {
                                _draft.value = before
                                draftDirty = true
                            } else {
                                _draft.value = outcome.draft
                                draftDirty = false
                            }
                        }
                        is ForeignStudioEditHistoryCustody.Outcome.Unproved -> Unit
                    }
                }
            }
            if (outcome is ForeignStudioEditHistoryCustody.Outcome.Unproved && outcome.failure !== error) error.addSuppressed(outcome.failure)
            throw error
        }
    }

    suspend fun applyDesignedPrompt(
        plan: NovelAiPromptPlan,
        targetImageModel: NovelAiImageModel
    ): NovelAiStudioDraft = updateDraft(resetPromptEditors = true) { current ->
        current.applyDesignedPromptPlan(plan, targetImageModel)
    }

    suspend fun applyReversePrompt(
        plan: NovelAiPromptPlan
    ): NovelAiStudioDraft = updateDraft(resetPromptEditors = true) { current ->
        current.applyReversePromptPlan(plan)
    }

    suspend fun saveUndoDraft(draft: NovelAiStudioDraft) = undoMutex.withDataAccess {
        storage.saveSingleton(UNDO_ENTITY, NovelAiStudioUndoDraft(draft), NovelAiStudioUndoDraft.serializer())
        _undoAvailable.value = true
    }

    suspend fun loadUndoDraft(): NovelAiStudioDraft? = undoMutex.withDataAccess {
        storage.loadSingleton(UNDO_ENTITY, NovelAiStudioUndoDraft.serializer())?.draft.also { _undoAvailable.value = it != null }
    }

    suspend fun clearUndoDraft() = undoMutex.withDataAccess {
        storage.saveSingleton(UNDO_ENTITY, NovelAiStudioUndoDraft(), NovelAiStudioUndoDraft.serializer())
        _undoAvailable.value = false
    }

    suspend fun saveGuidanceCheckpoint(guidance: com.example.chatbar.domain.image.NovelAiImageGuidanceDraft) {
        storage.saveSingleton(
            GUIDANCE_CHECKPOINT_ENTITY,
            NovelAiGuidanceEditorCheckpoint(guidance),
            NovelAiGuidanceEditorCheckpoint.serializer()
        )
    }

    suspend fun loadGuidanceCheckpoint(): com.example.chatbar.domain.image.NovelAiImageGuidanceDraft? =
        storage.loadSingleton(GUIDANCE_CHECKPOINT_ENTITY, NovelAiGuidanceEditorCheckpoint.serializer())?.guidance

    suspend fun clearGuidanceCheckpoint() {
        storage.saveSingleton(
            GUIDANCE_CHECKPOINT_ENTITY,
            NovelAiGuidanceEditorCheckpoint(),
            NovelAiGuidanceEditorCheckpoint.serializer()
        )
    }

    fun requestGuidanceEditor(target: NovelAiImageUseTarget) {
        _pendingGuidanceEditorTarget.value = target
    }

    fun consumeGuidanceEditorRequest() {
        _pendingGuidanceEditorTarget.value = null
    }

    suspend fun applyHistory(
        entry: NovelAiGenerationHistoryEntry,
        image: NovelAiGenerationHistoryImage,
        mode: NovelAiHistoryApplyMode
    ): NovelAiStudioDraft {
        val current = loadDraft()
        saveUndoDraft(current)
        return try {
            updateDraft(resetPromptEditors = true) { latest ->
                latest.applyHistoryRecipe(entry.recipe, image.seed, mode)
            }.also {
                clearGuidanceCheckpoint()
            }
        } catch (error: Throwable) {
            clearUndoDraft()
            throw error
        }
    }

    val history: Flow<List<NovelAiGenerationHistoryEntry>> =
        storage.observeAll(HISTORY_ENTITY, NovelAiGenerationHistoryEntry.serializer())
            .map { entries -> entries.sortedByDescending(NovelAiGenerationHistoryEntry::createdAt) }

    suspend fun saveHistory(entry: NovelAiGenerationHistoryEntry) = historyMutex.withDataAccess {
        storage.saveEntity(HISTORY_ENTITY, entry.id, entry, NovelAiGenerationHistoryEntry.serializer())
    }

    suspend fun deleteHistory(id: String) = historyMutex.withDataAccess {
        storage.deleteEntity<NovelAiGenerationHistoryEntry>(HISTORY_ENTITY, id)
    }

    suspend fun clearHistory() = historyMutex.withDataAccess {
        storage.deleteAll<NovelAiGenerationHistoryEntry>(HISTORY_ENTITY)
    }

    companion object {
        private const val DRAFT_ENTITY = "novelai_studio_draft"
        private const val UNDO_ENTITY = "novelai_studio_history_undo"
        private const val GUIDANCE_CHECKPOINT_ENTITY = "novelai_studio_guidance_checkpoint"
        private const val HISTORY_ENTITY = "novelai_generation_history"
        private const val HISTORY_FOLD_ENTITY = "novelai_history_fold_preferences"
    }
}
