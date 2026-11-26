package com.example.chatbar.desktop

import androidx.compose.runtime.Composable

@Composable
internal fun DesktopDraftRetry(available: Boolean, onRetry: () -> Unit) {
    if (available) BootstrapButton(LocalDesktopUiStrings.current(DesktopUiText.CHAT_DRAFT_RETRY_SAVE),
        secondary = true, onClick = onRetry)
}
