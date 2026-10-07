package com.example.chatbar.desktop

import androidx.compose.runtime.Composable

/** Attachment belongs to the composer action rail; session image policy belongs to Settings. */
@Composable
internal fun DesktopChatAttachmentAction(enabled: Boolean, onPick: () -> Unit) {
    DesktopChatIconAction("添加图片附件", DesktopAppIcons.ImageAdd, enabled = enabled, targetDp = 48, onClick = onPick)
}
