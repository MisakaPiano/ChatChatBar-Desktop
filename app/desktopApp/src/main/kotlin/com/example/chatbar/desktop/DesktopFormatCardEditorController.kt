package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.EditorDraft
import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.FormatCardUserToolConfig
import com.example.chatbar.data.local.entity.FormatCardUserToolType
import com.example.chatbar.data.repository.EditorDraftRepository
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.domain.card.FormatCardUserToolValidator
import com.example.chatbar.domain.card.NamePolicy
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

internal enum class FormatEditorProblem {
    NAME_REQUIRED, CONTENT_REQUIRED, DUPLICATE_NAME, INVALID_TOOL, SOURCE_CHANGED,
    SOURCE_DELETED, NEW_DRAFT_EXISTS, DRAFT_FAILED, SAVE_FAILED, SAVE_COMMITTED_WARNING,
}

internal data class DesktopFormatCardEditorState(
    val cards: List<FormatCard> = emptyList(),
    val query: String = "",
    val card: FormatCard? = null,
    val base: FormatCard? = null,
    val targetId: String? = null,
    val draftSessionId: String? = null,
    val draftBasis: EditorDraft? = null,
    val recoveryTargetId: String? = null,
    val dirty: Boolean = false,
    val draftPersisted: Boolean = false,
    val leavePrompt: Boolean = false,
    val problem: FormatEditorProblem? = null,
    val detail: String? = null,
) {
    val visibleCards: List<FormatCard> get() = cards.filter { it.name.contains(query, ignoreCase = true) }
}

/** Manual editor only. Entity, draft hash, validation, and single-default writes remain shared authorities. */
internal class DesktopFormatCardEditorController(
    private val formats: FormatCardRepository,
    private val drafts: EditorDraftRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val persistCard: suspend (FormatCard) -> Unit = formats::save,
    private val deleteDraft: suspend (EditorDraftType, String?) -> Unit = drafts::deleteForTarget,
) {
    private val mutableState = MutableStateFlow(DesktopFormatCardEditorState())
    val state = mutableState.asStateFlow()
    private val draftMutex = Mutex()
    private var draftJob: Job? = null
    private var leaveAction: (() -> Unit)? = null

    suspend fun load() { mutableState.value = mutableState.value.copy(cards = formats.getAll()) }
    fun search(query: String) { mutableState.value = mutableState.value.copy(query = query) }

    suspend fun openNew() {
        if (deferLeave { scope.launch { openNew() } }) return
        flushDraft()
        val saved = drafts.getForTarget(EditorDraftType.FORMAT_CARD, null)
        val card = saved?.formatPayload ?: FormatCard.create("", "")
        mutableState.value = mutableState.value.copy(card = card, base = null, targetId = null,
            draftSessionId = saved?.draftSessionId ?: UUID.randomUUID().toString(), draftBasis = saved,
            dirty = saved != null, draftPersisted = saved != null, problem = null, detail = null,
            recoveryTargetId = null)
    }

    suspend fun openExisting(id: String) {
        if (deferLeave { scope.launch { openExisting(id) } }) return
        flushDraft()
        val source = formats.getById(id)
        val saved = drafts.getForTarget(EditorDraftType.FORMAT_CARD, id)
        if (source == null && saved?.formatPayload == null) {
            mutableState.value = mutableState.value.copy(problem = FormatEditorProblem.SOURCE_DELETED)
            return
        }
        mutableState.value = mutableState.value.copy(card = saved?.formatPayload ?: source, base = source,
            targetId = id, draftSessionId = saved?.draftSessionId ?: UUID.randomUUID().toString(),
            draftBasis = saved, dirty = saved != null, draftPersisted = saved != null,
            problem = when {
                source == null -> FormatEditorProblem.SOURCE_DELETED
                saved != null && drafts.isChanged(source, saved) -> FormatEditorProblem.SOURCE_CHANGED
                else -> null
            }, detail = null, recoveryTargetId = null)
    }

    fun edit(change: (FormatCard) -> FormatCard) {
        val before = mutableState.value
        val card = before.card ?: return
        if (!before.dirty && before.draftBasis != null) {
            mutableState.value = before.copy(problem = FormatEditorProblem.SAVE_COMMITTED_WARNING)
            return
        }
        val next = change(card)
        if (next == card) return
        mutableState.value = before.copy(card = next, dirty = next != before.base,
            draftPersisted = false, problem = null, detail = null)
        scheduleDraft()
    }

    fun addTool(type: FormatCardUserToolType) = edit { card -> card.copy(userTools = card.userTools +
        when (type) {
            FormatCardUserToolType.RANDOM_NUMBER -> FormatCardUserToolConfig.randomNumber()
            FormatCardUserToolType.STRONG_PROMPT_SUFFIX -> FormatCardUserToolConfig.strongPromptSuffix()
        }) }

    fun updateTool(index: Int, change: (FormatCardUserToolConfig) -> FormatCardUserToolConfig) = edit { card ->
        if (index !in card.userTools.indices) card else card.copy(userTools = card.userTools.toMutableList().also {
            it[index] = change(it[index])
        })
    }

    fun removeTool(index: Int) = edit { card ->
        if (index !in card.userTools.indices) card else card.copy(userTools = card.userTools.toMutableList().also { it.removeAt(index) })
    }

    fun moveTool(index: Int, offset: Int) = edit { card ->
        val destination = index + offset
        if (index !in card.userTools.indices || destination !in card.userTools.indices) card
        else card.copy(userTools = card.userTools.toMutableList().also { it.add(destination, it.removeAt(index)) })
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
        val card = before.card ?: return@withLock
        if (!before.dirty || before.draftPersisted) return@withLock
        val session = before.draftSessionId ?: return@withLock
        val payload = before.draftBasis?.copy(formatPayload = card)
            ?: drafts.formatDraft(before.targetId, session, card, before.base)
        try {
            val saved = drafts.save(payload)
            if (mutableState.value.card == card && mutableState.value.draftSessionId == session) {
                mutableState.value = mutableState.value.copy(draftBasis = saved, draftPersisted = true)
            }
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(problem = FormatEditorProblem.DRAFT_FAILED, detail = error.message)
        }
    }

    private suspend fun validate(card: FormatCard, targetId: String?): Boolean {
        val issue = when {
            NamePolicy.normalize(card.name).isBlank() -> FormatEditorProblem.NAME_REQUIRED
            card.content.isBlank() -> FormatEditorProblem.CONTENT_REQUIRED
            FormatCardUserToolValidator.firstValidationError(card.userTools) != null -> FormatEditorProblem.INVALID_TOOL
            NamePolicy.conflict(card.name, formats.getAll().map { it.id to it.name }, targetId) != null ->
                FormatEditorProblem.DUPLICATE_NAME
            else -> null
        }
        if (issue != null) {
            mutableState.value = mutableState.value.copy(problem = issue,
                detail = if (issue == FormatEditorProblem.INVALID_TOOL)
                    FormatCardUserToolValidator.firstValidationError(card.userTools) else null)
            return false
        }
        return true
    }

    suspend fun save(forceOverwrite: Boolean = false): Boolean {
        // A clean editor never writes even if its source changed since open.
        if (!mutableState.value.dirty) return true
        flushDraft()
        val before = mutableState.value
        val card = before.card ?: return false
        if (!before.draftPersisted) return false
        if (!validate(card, before.targetId)) return false
        val source = try { before.targetId?.let { formats.getById(it) } }
        catch (error: Exception) { report(FormatEditorProblem.SAVE_FAILED, error); return false }
        if (before.targetId != null && source == null) {
            mutableState.value = before.copy(problem = FormatEditorProblem.SOURCE_DELETED)
            return false
        }
        if (!forceOverwrite && source != null && before.draftBasis != null && drafts.isChanged(source, before.draftBasis)) {
            mutableState.value = before.copy(problem = FormatEditorProblem.SOURCE_CHANGED)
            return false
        }
        val retained = (source ?: card).copy(name = NamePolicy.normalize(card.name), content = card.content,
            userTools = card.userTools, isDefault = card.isDefault)
        var saveCommittedWarning = false
        try {
            persistCard(retained)
        } catch (error: Exception) {
            // A cache refresh can fail after the entity write; do not ask the user to save twice.
            if (runCatching { formats.getById(retained.id) }.getOrNull() != retained) {
                report(FormatEditorProblem.SAVE_FAILED, error)
                return false
            }
            saveCommittedWarning = true
        }
        var pending = cleanupDrafts(buildList {
            add(before.targetId)
            before.recoveryTargetId?.let(::add)
        }) || saveCommittedWarning
        val refreshed = runCatching { formats.getAll() }.getOrElse {
            pending = true
            before.cards.filterNot { it.id == retained.id } + retained
        }
        mutableState.value = before.copy(card = retained, base = retained, targetId = retained.id,
            draftBasis = if (pending) before.draftBasis else null,
            recoveryTargetId = if (pending) before.recoveryTargetId else null,
            dirty = false, draftPersisted = pending, cards = refreshed,
            problem = if (pending) FormatEditorProblem.SAVE_COMMITTED_WARNING else null)
        return true
    }

    /** Transition through format_card_new only when it cannot replace someone else's draft. */
    suspend fun saveAsNew(): Boolean {
        flushDraft()
        val before = mutableState.value
        val card = before.card ?: return false
        val existing = drafts.getForTarget(EditorDraftType.FORMAT_CARD, null)
        if (existing != null && existing.draftSessionId != before.draftSessionId) {
            mutableState.value = before.copy(problem = FormatEditorProblem.NEW_DRAFT_EXISTS)
            return false
        }
        val copy = card.copy(id = UUID.randomUUID().toString(),
            name = NamePolicy.nextCopyName(card.name, formats.getAll().map { it.name }),
            isDefault = false, sourcePresetKey = null, sourcePresetVersion = null,
            createdAt = System.currentTimeMillis())
        val session = UUID.randomUUID().toString()
        val draft = drafts.formatDraft(null, session, copy, null)
        try { drafts.save(draft) }
        catch (error: Exception) { report(FormatEditorProblem.DRAFT_FAILED, error); return false }
        mutableState.value = before.copy(card = copy, base = null, targetId = null, draftSessionId = session,
            draftBasis = draft, recoveryTargetId = before.targetId, dirty = true, draftPersisted = true,
            problem = null, detail = null)
        return true
    }

    private suspend fun cleanupDrafts(targets: List<String?>): Boolean {
        var pending = false
        for (target in targets) {
            val failure = runCatching { deleteDraft(EditorDraftType.FORMAT_CARD, target) }.exceptionOrNull()
            val exists = runCatching { drafts.existsForTarget(EditorDraftType.FORMAT_CARD, target) }
            if (exists.getOrNull() != false) pending = true
            if (failure != null || exists.isFailure) pending = true
        }
        return pending
    }

    suspend fun retryCleanup() {
        val before = mutableState.value
        if (before.dirty || before.card == null) return
        val pending = cleanupDrafts(buildList {
            before.draftBasis?.let { add(it.targetId) }
            before.recoveryTargetId?.let(::add)
        })
        mutableState.value = before.copy(draftBasis = if (pending) before.draftBasis else null,
            recoveryTargetId = if (pending) before.recoveryTargetId else null, draftPersisted = pending,
            problem = if (pending) FormatEditorProblem.SAVE_COMMITTED_WARNING else null)
    }

    suspend fun discard() {
        draftJob?.cancelAndJoin()
        draftJob = null
        val before = mutableState.value
        if (!before.dirty && before.problem == FormatEditorProblem.SAVE_COMMITTED_WARNING) return
        val draftTarget = if (before.draftBasis != null) before.draftBasis.targetId else before.targetId
        if (cleanupDrafts(listOf(draftTarget))) {
            mutableState.value = before.copy(problem = FormatEditorProblem.DRAFT_FAILED)
            return
        }
        mutableState.value = before.copy(card = null, base = null, targetId = null,
            draftBasis = null, draftSessionId = null, dirty = false, draftPersisted = false,
            leavePrompt = false, problem = null)
        leaveAction?.also { leaveAction = null; it() }
    }

    fun requestLeave(action: () -> Unit) { if (!deferLeave(action)) action() }
    private fun deferLeave(action: () -> Unit): Boolean {
        if (mutableState.value.card != null && mutableState.value.dirty) {
            leaveAction = action
            mutableState.value = mutableState.value.copy(leavePrompt = true)
            return true
        }
        return false
    }
    fun continueEditing() { leaveAction = null; mutableState.value = mutableState.value.copy(leavePrompt = false) }
    suspend fun keepDraftAndLeave() {
        flushDraft()
        val before = mutableState.value
        if (before.dirty && !before.draftPersisted) {
            mutableState.value = before.copy(problem = FormatEditorProblem.DRAFT_FAILED)
            return
        }
        mutableState.value = before.copy(card = null, base = null, leavePrompt = false)
        leaveAction?.also { leaveAction = null; it() }
    }
    suspend fun saveAndLeave() {
        if (save() && mutableState.value.problem != FormatEditorProblem.SAVE_COMMITTED_WARNING) {
            closeClean()
            leaveAction?.also { leaveAction = null; it() }
        }
    }
    fun closeClean() {
        if (mutableState.value.dirty) { requestLeave { closeClean() }; return }
        if (mutableState.value.problem == FormatEditorProblem.SAVE_COMMITTED_WARNING) return
        mutableState.value = mutableState.value.copy(card = null, base = null, leavePrompt = false)
    }
    suspend fun closeAndDrain() { flushDraft(); scope.coroutineContext[Job]?.cancel() }
    private fun report(problem: FormatEditorProblem, error: Throwable) {
        mutableState.value = mutableState.value.copy(problem = problem, detail = error.message)
    }
}
