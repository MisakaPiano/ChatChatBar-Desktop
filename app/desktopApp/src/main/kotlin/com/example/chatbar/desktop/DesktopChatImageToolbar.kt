package com.example.chatbar.desktop

import androidx.compose.runtime.Composable

/** One compact row within the composer; thumbnails are independently conditional. */
@Composable
internal fun DesktopChatImageToolbar(enabled: Boolean, automatic: Boolean, onPick: () -> Unit,
    onImages: () -> Unit, onBackground: () -> Unit) {
    StudioActions {
        StudioAction("图片附件", enabled = enabled, icon = DesktopAppIcons.Add, onClick = onPick)
        StudioAction("生图 · 自动${if (automatic) "开启" else "关闭"}", icon = DesktopAppIcons.Star, onClick = onImages)
        StudioAction("背景", icon = DesktopAppIcons.Tools, onClick = onBackground)
    }
}
