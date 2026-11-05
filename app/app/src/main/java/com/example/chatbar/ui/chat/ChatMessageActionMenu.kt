package com.example.chatbar.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.ui.kit.*
import com.example.chatbar.ui.components.ChatBubbleSegmentAction

internal data class CompletedMessageLongPressActions(
    val message: (() -> Unit)?,
    val segment: ((ChatBubbleSegmentAction) -> Unit)?
)

/** These handlers are only installed on persisted rows, not the streaming reply bubble. */
internal fun completedMessageLongPressActions(
    screenshotSelectionMode: Boolean,
    streaming: Boolean = false,
    onMessage: () -> Unit,
    onSegment: (ChatBubbleSegmentAction) -> Unit
): CompletedMessageLongPressActions = if (screenshotSelectionMode || streaming) {
    CompletedMessageLongPressActions(null, null)
} else CompletedMessageLongPressActions(onMessage, onSegment)

/** Completed-message actions: independent voice work remains available during a new reply. */
@Composable
internal fun ChatMessageActionMenu(
    responding: Boolean,
    targetExists: Boolean,
    canSelectScreenshot: Boolean,
    voiceVisible: Boolean,
    voiceLabel: String,
    voiceEnabled: Boolean,
    voiceError: String?,
    canRepair: Boolean,
    canRegenerate: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onScreenshot: () -> Unit,
    onSpeak: () -> Unit,
    onRepair: () -> Unit,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit
) {
    val currentResponding by rememberUpdatedState(responding)
    fun mutation(action: () -> Unit) { if (!currentResponding) action() }
    CbDialog(onDismissRequest = onDismiss, title = "消息操作",
        dismiss = { CbButton("关闭", onDismiss, variant = ButtonVariant.Ghost) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            CbButton("编辑", { mutation(onEdit) }, modifier = Modifier.fillMaxWidth(),
                enabled = targetExists && !responding, variant = ButtonVariant.Secondary)
            if (canSelectScreenshot) {
                Spacer(Modifier.size(8.dp))
                CbButton("多选", onScreenshot, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
            }
            if (voiceVisible) {
                Spacer(Modifier.size(8.dp))
                CbButton(voiceLabel, onSpeak, modifier = Modifier.fillMaxWidth(),
                    variant = ButtonVariant.Outline, enabled = voiceEnabled)
                voiceError?.let { CbText(it, color = ChatBarTheme.colors.destructive, style = ChatBarTheme.typography.caption) }
            }
            if (canRepair) {
                Spacer(Modifier.size(8.dp))
                CbButton("AI 修复格式", { mutation(onRepair) }, modifier = Modifier.fillMaxWidth(),
                    variant = ButtonVariant.Outline, enabled = !responding)
            }
            Spacer(Modifier.size(8.dp))
            if (targetExists) {
                CbButton("删除", { mutation(onDelete) }, modifier = Modifier.fillMaxWidth(),
                    variant = ButtonVariant.Destructive, enabled = !responding)
            }
            if (canRegenerate) {
                Spacer(Modifier.size(8.dp))
                CbButton("重新生成", { mutation(onRegenerate) }, modifier = Modifier.fillMaxWidth(),
                    variant = ButtonVariant.Outline, enabled = !responding)
            }
        }
    }
}
