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
    REPAIR_FORMAT(DesktopUiText.FORMAT_CHECK),
    RESTORE_FORMAT(DesktopUiText.FORMAT_RESTORE),
    STOP_FORMAT(DesktopUiText.FORMAT_STOP),
}

internal fun desktopMessageActions(
    messages: List<ChatMessage>,
    message: ChatMessage,
    running: DesktopTaskEntry?,
    formatRepairEnabled: Boolean = false,
    sessionRepairBusy: Boolean = false,
    targetRepairRunning: Boolean = false,
): List<DesktopMessageAction> = buildList {
    add(DesktopMessageAction.COPY)
    if (desktopMessageMutationActionsAvailable(running) && !sessionRepairBusy) {
        add(DesktopMessageAction.EDIT)
        add(DesktopMessageAction.DELETE)
    }
    when (if (sessionRepairBusy) null else desktopRegenerationAction(messages, message, running)) {
        DesktopRegenerationAction.REGENERATE -> add(DesktopMessageAction.REGENERATE)
        DesktopRegenerationAction.RETRY -> add(DesktopMessageAction.RETRY)
        null -> Unit
    }
    if (formatRepairEnabled && message.role == MessageRole.ASSISTANT) {
        if (targetRepairRunning) add(DesktopMessageAction.STOP_FORMAT)
        else if (!sessionRepairBusy && running == null) {
            add(DesktopMessageAction.REPAIR_FORMAT)
            if (desktopCanRestoreFormat(message)) add(DesktopMessageAction.RESTORE_FORMAT)
        }
    }
}

internal fun desktopFooterMessageActions(actions: List<DesktopMessageAction>): List<DesktopMessageAction> =
    actions.filter { it == DesktopMessageAction.COPY ||
        it == DesktopMessageAction.REGENERATE || it == DesktopMessageAction.RETRY }

internal fun desktopOverflowMessageActions(actions: List<DesktopMessageAction>): List<DesktopMessageAction> =
    actions.filterNot { it in desktopFooterMessageActions(actions) }

internal enum class DesktopMessageActionScope(val title: String) { REPLY("回复"), SEGMENT("本段"), MESSAGE("整条") }

internal data class DesktopScopedMessageAction(val scope: DesktopMessageActionScope, val action: DesktopMessageAction)

/** Grouping only: eligibility still comes from desktopMessageActions. */
internal fun desktopMessageContextActions(
    actions: List<DesktopMessageAction>, hasSourceSegment: Boolean,
): List<DesktopScopedMessageAction> = buildList {
    actions.filter { it == DesktopMessageAction.REGENERATE || it == DesktopMessageAction.RETRY }
        .forEach { add(DesktopScopedMessageAction(DesktopMessageActionScope.REPLY, it)) }
    if (hasSourceSegment) actions.filter {
        it == DesktopMessageAction.COPY || it == DesktopMessageAction.EDIT || it == DesktopMessageAction.DELETE
    }.forEach { add(DesktopScopedMessageAction(DesktopMessageActionScope.SEGMENT, it)) }
    actions.filter { it != DesktopMessageAction.REGENERATE && it != DesktopMessageAction.RETRY }
        .forEach { add(DesktopScopedMessageAction(DesktopMessageActionScope.MESSAGE, it)) }
}

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
