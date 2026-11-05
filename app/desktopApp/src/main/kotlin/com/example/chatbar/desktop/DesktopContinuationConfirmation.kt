package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

@Composable
internal fun DesktopContinuationConfirmation(enabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val t = LocalDesktopUiStrings.current
    Popup(alignment = Alignment.Center, onDismissRequest = onCancel,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)) {
        Column(Modifier.widthIn(max = 420.dp).background(DesktopBootstrapColors.card)
            .border(1.dp, DesktopBootstrapColors.border).padding(20.dp)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) { onCancel(); true } else false
            }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusText(t(DesktopUiText.CONTINUE_CONFIRM))
            StatusText(t(DesktopUiText.CONTINUE_CONFIRM_DESCRIPTION))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BootstrapButton(t(DesktopUiText.CANCEL), secondary = true, onClick = onCancel)
                BootstrapButton(t(DesktopUiText.CONTINUE), enabled = enabled, onClick = onConfirm)
            }
        }
    }
}
