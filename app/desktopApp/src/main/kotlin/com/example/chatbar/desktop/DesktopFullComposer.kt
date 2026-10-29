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
import androidx.compose.ui.text.TextRange
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
    var continuationPending by mutableStateOf(false)
        private set
    private var submitting by mutableStateOf(false)
    private data class ChoiceEdit(val before: TextFieldValue, val after: TextFieldValue)
    private val choiceEdits = mutableStateListOf<ChoiceEdit>()
    val canUndoChoiceInsertion get() = choiceEdits.isNotEmpty() && input.composition == null && !submitting

    fun echo(draft: String) {
        val next = desktopComposerDraftEcho(input, draft)
        if (next.text != input.text) { continuationPending = false; choiceEdits.clear() }
        input = next
    }
    fun open(canLaunch: Boolean) { if (sessionId != null && canLaunch) expanded = true }
    fun close() { if (expanded) { expanded = false; returnFocus++ } }
    fun edit(full: Boolean, value: TextFieldValue, persist: (String) -> Unit) {
        // Ignore focus-loss echoes from the now-covered/removed text field, not active IME edits.
        if (full != expanded) return
        if (value.text != input.text) choiceEdits.clear()
        if (value.text != input.text || value.composition != input.composition) continuationPending = false
        input = value
        persist(value.text)
    }
    /** Insert after the current selection without replacing any existing draft characters. */
    fun insertChoice(text: String, persist: (String, String) -> Boolean): Boolean {
        if (sessionId == null || submitting || input.composition != null || text.isBlank()) return false
        val before = input
        val at = before.selection.max.coerceIn(0, before.text.length)
        val prefix = before.text.substring(0, at)
        val suffix = before.text.substring(at)
        val inserted = (if (prefix.isNotEmpty() && !prefix.last().isWhitespace()) "\n" else "") + text +
            (if (suffix.isNotEmpty() && !suffix.first().isWhitespace()) "\n" else "")
        val after = TextFieldValue(prefix + inserted + suffix, TextRange(at + inserted.length))
        if (!persist(before.text, after.text)) return false
        input = after
        choiceEdits.add(ChoiceEdit(before, after))
        continuationPending = false
        returnFocus++
        return true
    }
    fun undoChoiceInsertion(persist: (String, String) -> Boolean): Boolean {
        if (!canUndoChoiceInsertion) return false
        val edit = choiceEdits.last()
        if (input.text != edit.after.text || !persist(input.text, edit.before.text)) return false
        input = edit.before
        choiceEdits.removeAt(choiceEdits.lastIndex)
        returnFocus++
        return true
    }
    var hasAttachments by mutableStateOf(false)

    fun canClickSend(canLaunch: Boolean) = sessionId != null && canLaunch && !submitting && input.composition == null
    fun canSend(canLaunch: Boolean) = canClickSend(canLaunch) && (input.text.isNotBlank() || hasAttachments)
    fun requestContinuation(canLaunch: Boolean) {
        if (canClickSend(canLaunch) && input.text.isBlank() && !hasAttachments) continuationPending = true
    }
    fun cancelContinuation() { continuationPending = false }
    suspend fun confirmContinuation(canLaunch: Boolean, continueReply: suspend () -> String?) {
        if (!continuationPending) return
        continuationPending = false
        if (!canClickSend(canLaunch) || input.text.isNotBlank() || hasAttachments) return
        submitting = true
        try { if (continueReply() != null) close() }
        finally { submitting = false }
    }
    suspend fun send(canLaunch: Boolean, send: suspend () -> String?) {
        if (!canSend(canLaunch)) return
        submitting = true
        try { if (send() != null) close() }
        finally { submitting = false }
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
    sendKey: DesktopSendKey = DesktopSendKey.CTRL_ENTER,
    verticallyCentered: Boolean = false,
) {
    val focus = remember(composer) { FocusRequester() }
    val t = LocalDesktopUiStrings.current
    LaunchedEffect(composer, composer.returnFocus) {
        if (full == composer.expanded && (full || composer.returnFocus > 0)) focus.requestFocus()
    }
    BasicTextField(
        value = composer.input,
        onValueChange = { composer.edit(full, it, onDraft) },
        modifier = modifier.focusRequester(focus).semantics { contentDescription = t(DesktopUiText.COMPOSER_TEXT) }
            .onPreviewKeyEvent { event ->
                full == composer.expanded && desktopComposerSendKey(event.key, event.type, event.isCtrlPressed,
                    composer.input, canLaunch, mode = sendKey, shift = event.isShiftPressed,
                    altOrMeta = event.isAltPressed || event.isMetaPressed, hasAttachments = composer.hasAttachments, send = onSend)
            },
        textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp),
        decorationBox = { field -> Box(contentAlignment = if (verticallyCentered) Alignment.CenterStart else Alignment.TopStart) {
            if (composer.input.text.isEmpty()) androidx.compose.foundation.text.BasicText(desktopComposerPlaceholder(sendKey),
                style = TextStyle(color = DesktopBootstrapColors.mutedForeground, fontSize = 13.sp))
            field()
        } },
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
    attachments: @Composable () -> Unit = {},
    onPickImage: (() -> Unit)? = null,
    onUndoChoice: () -> Unit = {},
    ingressModifier: Modifier = Modifier,
    sendKey: DesktopSendKey = DesktopSendKey.CTRL_ENTER,
    onSendKey: (DesktopSendKey) -> Unit = {},
    voiceControls: @Composable () -> Unit = {},
) {
    val t = LocalDesktopUiStrings.current
    val colors = DesktopBootstrapColors
    DesktopModalSurface {
        Column(Modifier.fillMaxSize().then(ingressModifier).background(colors.background).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { StatusText(t(DesktopUiText.FULL_COMPOSER), colors.foreground) }
                BootstrapButton(t(DesktopUiText.CLOSE), secondary = true, onClick = composer::close)
            }
            configurationMessage?.let { StatusText(t.status(it), colors.warning) }
            error?.let { StatusText(t.status(it), colors.destructive) }
            attachments()
            DesktopChoiceUndo(composer, onUndoChoice)
            DesktopComposerTextField(composer, full = true, canLaunch, onDraft, onSend,
                Modifier.weight(1f).fillMaxWidth()
                    .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                    .background(colors.input, RoundedCornerShape(8.dp)).padding(12.dp), sendKey = sendKey)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                voiceControls()
                Spacer(Modifier.weight(1f))
                onPickImage?.let { DesktopChatAttachmentAction(canLaunch, onPick = it) }
                DesktopComposerSendAction(null, composer.canClickSend(canLaunch), false, sendKey, onSendKey, onSend)
            }
        }
    }
}

@Composable
internal fun DesktopChoiceUndo(composer: DesktopComposerInput, undo: () -> Unit) {
    if (composer.canUndoChoiceInsertion) BootstrapButton(LocalDesktopUiStrings.current(DesktopUiText.UNDO_ACTION_CHOICE),
        secondary = true, onClick = undo)
}
