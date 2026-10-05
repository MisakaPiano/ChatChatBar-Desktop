package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One UI value for both presentations; controller remains the only durable String draft owner. */
internal class DesktopComposerInput(val sessionId: String?, draft: String) {
    var input by mutableStateOf(TextFieldValue(draft))
        private set
    var expanded by mutableStateOf(false)
        private set
    var returnFocus by mutableStateOf(0)
        private set

    fun echo(draft: String) { input = desktopComposerDraftEcho(input, draft) }
    fun open(canLaunch: Boolean) { if (sessionId != null && canLaunch) expanded = true }
    fun close() { if (expanded) { expanded = false; returnFocus++ } }
    fun edit(full: Boolean, value: TextFieldValue, persist: (String) -> Unit) {
        // Ignore focus-loss echoes from the now-covered/removed text field, not active IME edits.
        if (full != expanded) return
        input = value
        persist(value.text)
    }
    var hasAttachments by mutableStateOf(false)

    fun canSend(canLaunch: Boolean) = sessionId != null && canLaunch && (input.text.isNotBlank() || hasAttachments)
    suspend fun send(canLaunch: Boolean, send: suspend () -> String?) {
        if (canSend(canLaunch) && send() != null) close()
        // Never clear input here: only the controller's accepted-send draft echo may do so.
    }
}

@Composable
internal fun rememberDesktopComposerInput(sessionId: String?, draft: String): DesktopComposerInput {
    val composer = remember(sessionId) { DesktopComposerInput(sessionId, draft) }
    LaunchedEffect(composer, draft) { composer.echo(draft) }
    return composer
}

@Composable
internal fun DesktopComposerTextField(
    composer: DesktopComposerInput,
    full: Boolean,
    canLaunch: Boolean,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember(composer) { FocusRequester() }
    val t = LocalDesktopUiStrings.current
    LaunchedEffect(composer, composer.returnFocus) {
        if (full || composer.returnFocus > 0) focus.requestFocus()
    }
    BasicTextField(
        value = composer.input,
        onValueChange = { composer.edit(full, it, onDraft) },
        modifier = modifier.focusRequester(focus).semantics { contentDescription = t(DesktopUiText.COMPOSER_TEXT) }
            .onPreviewKeyEvent { event ->
                full == composer.expanded && desktopComposerSendKey(event.key, event.type, event.isCtrlPressed,
                    composer.input, canLaunch, onSend)
            },
        textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp),
    )
}

/** Must be hosted at the Chat workspace root, outside the height-limited inline composer. */
@Composable
internal fun DesktopFullComposer(
    composer: DesktopComposerInput,
    canLaunch: Boolean,
    configurationMessage: String?,
    error: String?,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
) {
    val t = LocalDesktopUiStrings.current
    val colors = DesktopBootstrapColors
    DesktopModalSurface {
        Column(Modifier.fillMaxSize().background(colors.background).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { StatusText(t(DesktopUiText.FULL_COMPOSER), colors.foreground) }
                BootstrapButton(t(DesktopUiText.CLOSE), secondary = true, onClick = composer::close)
            }
            configurationMessage?.let { StatusText(t.status(it), colors.warning) }
            error?.let { StatusText(t.status(it), colors.destructive) }
            DesktopComposerTextField(composer, full = true, canLaunch, onDraft, onSend,
                Modifier.weight(1f).fillMaxWidth()
                    .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                    .background(colors.input, RoundedCornerShape(8.dp)).padding(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { StatusText(t(DesktopUiText.COMPOSER_HINT)) }
                BootstrapButton(t(DesktopUiText.SEND), enabled = composer.canSend(canLaunch), onClick = onSend)
            }
        }
    }
}
