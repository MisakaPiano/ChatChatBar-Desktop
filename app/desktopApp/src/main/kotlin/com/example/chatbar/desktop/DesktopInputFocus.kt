package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/** Native Compose focus isolation, retaining in-app bounds and explicit Close/Cancel decisions. */
@Composable
internal fun DesktopModalSurface(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        Popup(alignment = Alignment.TopStart, properties = PopupProperties(
            focusable = true, dismissOnBackPress = false, dismissOnClickOutside = false,
        )) {
            Box(Modifier.size(width, height)) { content() }
        }
    }
}

internal fun desktopComposerSendKey(
    key: Key, type: KeyEventType, ctrl: Boolean, input: TextFieldValue, canLaunch: Boolean, send: () -> Unit,
): Boolean {
    if (type != KeyEventType.KeyDown || key != Key.Enter || !ctrl || input.composition != null ||
        !canLaunch || input.text.isBlank()) return false
    send()
    return true
}

internal fun desktopComposerDraftEcho(input: TextFieldValue, draft: String): TextFieldValue =
    if (input.text != draft && input.composition == null) TextFieldValue(draft) else input

/** Only the focused Model text field can veto an editor command while composing. */
internal class DesktopModelInputFocus {
    private var owner: Any? = null
    var composing by mutableStateOf(false)
        private set
    fun focused(token: Any, composition: Boolean) { owner = token; composing = composition }
    fun changed(token: Any, composition: Boolean) { if (owner === token) composing = composition }
    fun blurred(token: Any) { if (owner === token) { owner = null; composing = false } }
}

internal val LocalDesktopModelInputFocus = staticCompositionLocalOf<DesktopModelInputFocus?> { null }
