package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal val LocalDesktopStudioTextHistories = staticCompositionLocalOf<DesktopStudioTextHistories?> { null }
internal val LocalDesktopStudioFieldScope = staticCompositionLocalOf { "studio" }

/** Stable field keys survive disclosure/role reordering; no text or key handler escapes Studio. */
internal class DesktopStudioTextHistories(private val onLocalReplay: () -> Unit = {}) {
    private val fields = linkedMapOf<String, DesktopStudioTextHistory>()
    var authorityRevision by mutableLongStateOf(0)
        private set
    fun field(key: String, text: String): DesktopStudioTextHistory {
        fields[key]?.let { return it }
        if (fields.size >= 256) fields.remove(fields.keys.first())
        return DesktopStudioTextHistory(text, onReplay = onLocalReplay).also { fields[key] = it }
    }
    fun authorityChanged() { authorityRevision++ }
}

internal class DesktopStudioTextHistory(text: String, private val clock: () -> Long = System::nanoTime,
    private val onReplay: () -> Unit = {}) {
    var value by mutableStateOf(TextFieldValue(text, TextRange(text.length)))
        private set
    private val undo = mutableListOf<TextFieldValue>()
    private val redo = mutableListOf<TextFieldValue>()
    private val pending = mutableListOf<String>()
    private var authority = text
    private var lastAt = 0L
    private var merging = false
    private var compositionOrigin: TextFieldValue? = null
    fun endTyping() { merging = false; onReplay() }

    fun edit(next: TextFieldValue, publish: (String) -> Unit) {
        if (next.composition != null) {
            if (compositionOrigin == null) compositionOrigin = value.copy(composition = null)
            value = next; merging = false
            return // IME preedit belongs to the active field, not the durable global draft.
        }
        compositionOrigin?.let { origin ->
            compositionOrigin = null; merging = false
            if (next.text != origin.text) { undo += origin; redo.clear(); trim(undo) }
            value = next
            if (next.text != origin.text) { pending += next.text; onReplay(); publish(next.text) }
            return
        }
        if (next.text != value.text) {
            val now = clock()
            val small = desktopStudioSmallTextEdit(value.text, next.text)
            val merge = merging && small && now - lastAt in 0..750_000_000L &&
                value.selection.collapsed && next.selection.collapsed
            if (!merge) undo += value.copy(composition = null)
            redo.clear(); trim(undo)
            merging = small; lastAt = now
            value = next; pending += next.text; publish(next.text)
        } else {
            if (next.selection != value.selection) { merging = false; onReplay() }
            value = next
        }
    }

    fun echo(text: String, forced: Boolean = false, equivalent: (String, String) -> Boolean = String::equals) {
        if (!forced && text == authority) return
        authority = text
        if (!forced) {
            val index = pending.indexOf(text)
            if (index >= 0) { repeat(index + 1) { pending.removeAt(0) }; return }
            if (equivalent(value.text, text)) return
        }
        pending.clear(); merging = false; compositionOrigin = null
        if (text != value.text) {
            undo.clear(); redo.clear()
            value = TextFieldValue(text, TextRange(text.length))
        }
    }

    fun replay(forward: Boolean, publish: (String) -> Unit): Boolean {
        if (value.composition != null) return false
        val source = if (forward) redo else undo
        val target = source.removeLastOrNull() ?: return true // Consume locally even at its boundary.
        (if (forward) undo else redo).add(value.copy(composition = null))
        merging = false; value = target; pending += target.text; onReplay(); publish(target.text)
        return true
    }

    fun fork() = DesktopStudioTextHistory(value.text, clock, onReplay).also {
        it.value = value; it.undo.addAll(undo); it.redo.addAll(redo)
    }

    private fun trim(values: MutableList<TextFieldValue>) {
        while (values.size > 100 || values.size > 1 && values.sumOf { it.text.length.toLong() } > 512 * 1024)
            values.removeAt(0)
    }
}

@Composable
internal fun rememberStudioTextHistory(label: String, text: String,
    equivalent: (String, String) -> Boolean = String::equals): DesktopStudioTextHistory {
    val owner = LocalDesktopStudioTextHistories.current
    val key = LocalDesktopStudioFieldScope.current + ":" + label
    val field = remember(owner, key) { owner?.field(key, text) ?: DesktopStudioTextHistory(text) }
    val revision = owner?.authorityRevision ?: 0
    var seenRevision by remember(field) { mutableLongStateOf(revision) }
    LaunchedEffect(field, text, revision) {
        field.echo(text, forced = revision != seenRevision, equivalent = equivalent)
        seenRevision = revision
    }
    return field
}

internal fun Modifier.studioTextHistoryKeys(field: DesktopStudioTextHistory, publish: (String) -> Unit) =
    onFocusChanged { field.endTyping() }.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed || event.isAltPressed ||
            event.isMetaPressed || field.value.composition != null) false
        else when (event.key) {
            Key.Z -> field.replay(event.isShiftPressed, publish)
            Key.Y -> field.replay(true, publish)
            else -> false
        }
    }
