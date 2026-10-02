package com.example.chatbar.desktop

/** Ordinary obsolete drafts are maintenance state, not evidence of an entity commit. */
internal data class DesktopCharacterEditorPresentation(
    val communityReadOnly: Boolean,
    val showOrdinaryCleanupRetry: Boolean,
    val showCommittedCleanupRetry: Boolean,
) {
    val readOnly: Boolean get() = communityReadOnly || showCommittedCleanupRetry
}

internal fun desktopCharacterEditorPresentation(state: DesktopCharacterEditorState) =
    DesktopCharacterEditorPresentation(
        communityReadOnly = state.base?.isCommunityDownload == true && state.targetId != null,
        showOrdinaryCleanupRetry = !state.dirty && state.problem == CharacterEditorProblem.CLEAN_DRAFT_WARNING,
        showCommittedCleanupRetry = state.problem == CharacterEditorProblem.SAVE_COMMITTED_WARNING,
    )
