package com.example.chatbar.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.*

class DesktopInputFocusTest {
    @Test fun `Ctrl Enter sends exactly once on key down only`() {
        var sends = 0
        val input = TextFieldValue("hello")
        assertTrue(desktopComposerSendKey(Key.Enter, KeyEventType.KeyDown, true, input, true) { sends++ })
        assertFalse(desktopComposerSendKey(Key.Enter, KeyEventType.KeyUp, true, input, true) { sends++ })
        assertEquals(1, sends)
    }
    @Test fun `Chinese and Japanese composing input never sends or consumes Ctrl Enter`() {
        for (text in listOf("nihao", "nihongo", "你好", "にほんご")) {
            val input = TextFieldValue(text, TextRange(text.length), TextRange(0, text.length))
            assertFalse(desktopComposerSendKey(Key.Enter, KeyEventType.KeyDown, true, input, true) { fail("IME send") })
        }
    }
    @Test fun `plain and Shift Enter remain unhandled`() {
        repeat(2) { assertFalse(desktopComposerSendKey(Key.Enter, KeyEventType.KeyDown, false, TextFieldValue("text"), true) { fail() }) }
    }
    @Test fun `unavailable running and blank text cannot send`() {
        assertFalse(desktopComposerSendKey(Key.Enter, KeyEventType.KeyDown, true, TextFieldValue("text"), false) { fail() })
        assertFalse(desktopComposerSendKey(Key.Enter, KeyEventType.KeyDown, true, TextFieldValue(" \n"), true) { fail() })
    }
    @Test fun `async draft echo preserves active composition selection and identity through resize`() {
        val input = TextFieldValue("nihao\nnihongo", TextRange(2, 5), TextRange(0, 5))
        val layout = DesktopComposerLayoutState()
        for (delta in listOf(1000f, -80f, 1000f, -40f)) {
            layout.drag(delta, 600f)
            assertSame(input, desktopComposerDraftEcho(input, "stale draft"))
            assertSame(input, desktopComposerDraftEcho(input, input.text))
        }
        val confirmed = input.copy(composition = null)
        assertEquals("new draft", desktopComposerDraftEcho(confirmed, "new draft").text)
        assertSame(confirmed, desktopComposerDraftEcho(confirmed, confirmed.text))
    }
    @Test fun `Model Esc and Ctrl S defer to composition but remain available afterward`() {
        assertNull(desktopModelEditorCommand(Key.Escape, false, composing = true))
        assertNull(desktopModelEditorCommand(Key.S, true, composing = true))
        assertEquals(DesktopModelEditorCommand.CLOSE, desktopModelEditorCommand(Key.Escape, false))
        assertEquals(DesktopModelEditorCommand.SAVE, desktopModelEditorCommand(Key.S, true))
    }
    @Test fun `standard editing and traversal keys are never Model commands`() {
        for (key in listOf(Key.A, Key.C, Key.V, Key.X, Key.DirectionLeft, Key.DirectionRight,
            Key.MoveHome, Key.MoveEnd, Key.Backspace, Key.Delete, Key.Tab, Key.Enter)) {
            for (ctrl in listOf(false, true)) assertNull(desktopModelEditorCommand(key, ctrl))
        }
    }
    @Test fun `focused field composition ownership survives delayed blur of prior field`() {
        val focus = DesktopModelInputFocus()
        val a = Any(); val b = Any()
        focus.focused(a, true)
        assertTrue(focus.composing)
        focus.focused(b, true)
        focus.blurred(a)
        focus.changed(a, false)
        assertTrue(focus.composing)
        focus.changed(b, false)
        assertFalse(focus.composing)
        focus.changed(b, true)
        focus.blurred(b)
        assertFalse(focus.composing)
    }

    @Test fun `Model Save is keydown only dirty idle and composition safe`() {
        var saves = 0
        var closes = 0
        fun command(type: KeyEventType = KeyEventType.KeyDown, composing: Boolean = false,
            dirty: Boolean = true, busy: Boolean = false) = desktopModelEditorKey(Key.S, type, true, composing,
                dirty, busy, { saves++ }, { closes++ })
        assertTrue(command())
        assertTrue(command(dirty = false))
        assertTrue(command(busy = true))
        assertFalse(command(composing = true))
        assertFalse(command(type = KeyEventType.KeyUp))
        assertEquals(1, saves)
        assertEquals(0, closes)
    }
}
