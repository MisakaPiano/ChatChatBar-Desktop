package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.data.repository.EditorDraftRepository

internal enum class DraftPhysicalState { EXISTS, REMOVED, UNKNOWN }

/** Physical absence is the deletion commit point; a later cache error cannot undo it. */
internal data class DesktopEditorDraftCleanup(
    val physical: DraftPhysicalState,
    val cacheReconciled: Boolean,
    val errors: List<Throwable> = emptyList(),
) {
    val blocking: Boolean get() = physical != DraftPhysicalState.REMOVED || !cacheReconciled
    val removed: Boolean get() = physical == DraftPhysicalState.REMOVED
}

internal suspend fun deleteEditorDraftConfirmed(
    drafts: EditorDraftRepository,
    type: EditorDraftType,
    targetId: String?,
    delete: suspend (EditorDraftType, String?) -> Unit,
    refresh: suspend () -> Unit = drafts::refreshFromStorage,
): DesktopEditorDraftCleanup {
    val prior = runCatching { drafts.existsForTarget(type, targetId) }
    if (prior.isFailure) return DesktopEditorDraftCleanup(DraftPhysicalState.UNKNOWN, false,
        listOf(prior.exceptionOrNull()!!))
    val deleteError = if (prior.getOrThrow()) runCatching { delete(type, targetId) }.exceptionOrNull() else null
    val after = runCatching { drafts.existsForTarget(type, targetId) }
    if (after.isFailure) return DesktopEditorDraftCleanup(DraftPhysicalState.UNKNOWN, false,
        listOfNotNull(deleteError, after.exceptionOrNull()))
    if (after.getOrThrow()) return DesktopEditorDraftCleanup(DraftPhysicalState.EXISTS, false,
        listOfNotNull(deleteError, IllegalStateException("Editor draft deletion was not durable")))
    val cacheError = runCatching { refresh() }.exceptionOrNull()
    return DesktopEditorDraftCleanup(DraftPhysicalState.REMOVED, cacheError == null,
        listOfNotNull(deleteError, cacheError))
}
