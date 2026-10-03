package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole

/** One eligibility policy for the visible footer and the redundant context menu. */
internal enum class DesktopMessageAction(val label: DesktopUiText) {
    COPY(DesktopUiText.COPY_MESSAGE),
    EDIT(DesktopUiText.EDIT_WHOLE_MESSAGE),
    DELETE(DesktopUiText.DELETE_WHOLE_MESSAGE),
    REGENERATE(DesktopUiText.REGENERATE),
    RETRY(DesktopUiText.RETRY_GENERATION),
}

internal fun desktopMessageActions(
    messages: List<ChatMessage>,
    message: ChatMessage,
    running: DesktopTaskEntry?,
): List<DesktopMessageAction> = buildList {
    add(DesktopMessageAction.COPY)
    if (desktopMessageMutationActionsAvailable(running)) {
        add(DesktopMessageAction.EDIT)
        add(DesktopMessageAction.DELETE)
    }
    when (desktopRegenerationAction(messages, message, running)) {
        DesktopRegenerationAction.REGENERATE -> add(DesktopMessageAction.REGENERATE)
        DesktopRegenerationAction.RETRY -> add(DesktopMessageAction.RETRY)
        null -> Unit
    }
}

internal fun desktopFooterMessageActions(actions: List<DesktopMessageAction>): List<DesktopMessageAction> =
    actions.filter { it == DesktopMessageAction.COPY ||
        it == DesktopMessageAction.REGENERATE || it == DesktopMessageAction.RETRY }

internal fun desktopOverflowMessageActions(actions: List<DesktopMessageAction>): List<DesktopMessageAction> =
    actions.filter { it == DesktopMessageAction.EDIT || it == DesktopMessageAction.DELETE }

internal data class DesktopAlternativeNavigation(val current: Int, val total: Int) {
    val canPrevious: Boolean get() = current > 1
    val canNext: Boolean get() = current < total
}

internal fun desktopAlternativeNavigation(
    message: ChatMessage,
    eligibleIds: Set<String>,
): DesktopAlternativeNavigation? = if (
    message.role == MessageRole.ASSISTANT && message.alternatives.size > 1 && message.id in eligibleIds
) {
    DesktopAlternativeNavigation(
        message.currentAlternativeIndex.coerceIn(0, message.alternatives.lastIndex) + 1,
        message.alternatives.size,
    )
} else null
