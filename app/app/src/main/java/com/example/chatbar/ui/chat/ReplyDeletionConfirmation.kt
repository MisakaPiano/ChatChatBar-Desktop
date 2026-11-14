package com.example.chatbar.ui.chat

import androidx.compose.runtime.*
import com.example.chatbar.ui.kit.*

/** The menu exposes one action; deleting the whole message requires a separate confirmation. */
@Composable
internal fun ReplyDeletionConfirmation(
    messageId: String,
    versionId: String,
    canDeleteAlternative: Boolean,
    onDismiss: () -> Unit,
    onDeleteAlternative: () -> Unit,
    onDeleteWholeMessage: () -> Unit
) {
    var wholeMessage by remember(messageId, versionId) { mutableStateOf(false) }
    if (wholeMessage) {
        WholeMessageDeletionConfirmation(
            onDismiss = { wholeMessage = false },
            onDelete = onDeleteWholeMessage
        )
    } else {
        CbDialog(
            onDismissRequest = onDismiss,
            title = "删除当前备选",
            titleActions = {
                CbButton("删除整条…", { wholeMessage = true }, variant = ButtonVariant.Ghost, size = ButtonSize.Sm)
            },
            dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) },
            confirm = {
                CbButton("删除此备选", onDeleteAlternative,
                    enabled = canDeleteAlternative, variant = ButtonVariant.Destructive)
            }
        ) {
            CbText(if (canDeleteAlternative)
                "只删除当前备选，其他备选、图片和录音保留。此操作不可撤销。"
            else "仅剩一个备选。如需移除这条消息，请从右上角进入“删除整条”。")
        }
    }
}

@Composable
internal fun WholeMessageDeletionConfirmation(onDismiss: () -> Unit, onDelete: () -> Unit) {
    CbDialog(
        onDismissRequest = onDismiss,
        title = "删除整条消息",
        dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) },
        confirm = { CbButton("删除整条", onDelete, variant = ButtonVariant.Destructive) }
    ) {
        CbText("确定删除整条消息及全部备选回复？其中所有片段、图片和录音都会删除，此操作不可撤销。",
            color = ChatBarTheme.colors.mutedForeground)
    }
}
