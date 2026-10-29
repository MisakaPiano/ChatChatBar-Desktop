package com.example.chatbar.desktop

import androidx.compose.runtime.Composable

/** Attachment belongs to the composer action rail; session image policy belongs to Settings. */
@Composable
internal fun DesktopChatAttachmentAction(enabled: Boolean, targetDp: Int = 48, onPick: () -> Unit) {
    DesktopChatIconAction("添加图片附件", DesktopAppIcons.ImageAdd, enabled = enabled, targetDp = targetDp, onClick = onPick)
}
