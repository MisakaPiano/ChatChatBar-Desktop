@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DesktopFullComposerTest {
    private fun source(name: String) = Files.readString(Path.of("src/main/kotlin/com/example/chatbar/desktop", name))
    private fun owner(text: String = "draft") = DesktopComposerInput("session", text)
    private suspend fun ImageComposeScene.frames() { repeat(3) { render().close(); yield() } }
    private fun enter(ctrl: Boolean = false, shift: Boolean = false, up: Boolean = false) = KeyEvent(
        Key.Enter, if (up) KeyEventType.KeyUp else KeyEventType.KeyDown,
        isCtrlPressed = ctrl, isShiftPressed = shift,
    )

    @Test fun `open and close preserve the exact text selection and composition without writes`() {
        val composer = owner()
        val value = TextFieldValue("中文 nihongo\n".repeat(100), TextRange(2, 9), TextRange(3, 10))
        val writes = mutableListOf<String>()
        composer.edit(false, value, writes::add)
        composer.open(true)
        assertSame(value, composer.input)
        composer.close()
        assertSame(value, composer.input)
        assertEquals(listOf(value.text), writes)
        assertEquals(1, composer.returnFocus)
    }

    @Test fun `both presentations call the same draft sink`() {
        val composer = owner()
        val writes = mutableListOf<String>()
        composer.edit(false, TextFieldValue("inline"), writes::add)
        composer.open(true)
        composer.edit(true, TextFieldValue("full"), writes::add)
        composer.close()
        assertEquals("full", composer.input.text)
        composer.edit(false, TextFieldValue("inline again"), writes::add)
        assertEquals(listOf("inline", "full", "inline again"), writes)
    }

    @Test fun `blur echoes from covered or removed fields cannot discard active input`() {
        val composer = owner()
        composer.open(true)
        val composing = TextFieldValue("转换", TextRange(2), TextRange(0, 2))
        composer.edit(true, composing) { }
        composer.edit(false, TextFieldValue("old")) { fail("Covered field persisted") }
        assertSame(composing, composer.input)
        composer.close()
        composer.edit(true, TextFieldValue("stale")) { fail("Removed field persisted") }
        assertSame(composing, composer.input)
    }

    @Test fun `controller echo retains composing value and accepts external draft only outside composition`() {
        val composer = owner()
        val value = TextFieldValue("nihao", TextRange(5), TextRange(0, 5))
        composer.edit(false, value) { }
        composer.open(true)
        composer.echo("older draft")
        assertSame(value, composer.input)
        composer.edit(true, value.copy(composition = null)) { }
        composer.echo("accepted external draft")
        assertEquals("accepted external draft", composer.input.text)
    }

    @Test fun `disabled launch and absent session cannot open or send`() = runBlocking {
        for (composer in listOf(owner(), DesktopComposerInput(null, "draft"))) {
            val canLaunch = composer.sessionId == null
            composer.open(canLaunch)
            assertFalse(composer.expanded)
            assertFalse(composer.canSend(canLaunch))
            composer.send(canLaunch) { fail("Unavailable action") }
        }
    }

    @Test fun `blank full composer cannot send`() = runBlocking {
        val composer = owner(" \n")
        composer.open(true)
        composer.send(true) { fail("Blank send") }
        assertTrue(composer.expanded)
    }

    @Test fun `send acceptance closes only after result and never clears input itself`() = runBlocking {
        val composer = owner()
        composer.open(true)
        val result = CompletableDeferred<String?>()
        val entered = CompletableDeferred<Unit>()
        val sending = launch { composer.send(true) { entered.complete(Unit); result.await() } }
        entered.await()
        assertTrue(composer.expanded)
        assertEquals("draft", composer.input.text)
        result.complete("task")
        sending.join()
        assertFalse(composer.expanded)
        assertEquals("draft", composer.input.text)
        composer.echo("")
        assertEquals("", composer.input.text)
    }

    @Test fun `rejected send remains open editable and preserves draft`() = runBlocking {
        val composer = owner()
        composer.open(true)
        var sends = 0
        composer.send(true) { sends++; null }
        assertEquals(1, sends)
        assertTrue(composer.expanded)
        assertEquals("draft", composer.input.text)
        composer.edit(true, TextFieldValue("corrected")) { assertEquals("corrected", it) }
    }

    @Test fun `generation becoming active while open blocks sending without discarding`() = runBlocking {
        val composer = owner()
        composer.open(true)
        composer.send(false) { fail("Running send") }
        assertTrue(composer.expanded)
        assertEquals("draft", composer.input.text)
    }

    @Test fun `same keyboard policy consumes only eligible Ctrl Enter KeyDown once`() {
        for (full in listOf(false, true)) {
            val composer = owner()
            if (full) composer.open(true)
            var sends = 0
            fun key(ctrl: Boolean, up: Boolean = false) = desktopComposerSendKey(Key.Enter,
                if (up) KeyEventType.KeyUp else KeyEventType.KeyDown, ctrl, composer.input, true) { sends++ }
            assertTrue(key(true))
            assertFalse(key(true, up = true))
            assertFalse(key(false)) // Plain and Shift+Enter both have ctrl=false.
            assertEquals(1, sends)
            composer.edit(full, TextFieldValue("中文 nihongo", composition = TextRange(0, 2))) { }
            assertFalse(key(true))
            assertEquals(1, sends)
        }
    }

    @Test fun `session keyed owner closes full editor and adopts new draft`() = runBlocking {
        var session by mutableStateOf("A")
        var draft by mutableStateOf("A draft")
        lateinit var composer: DesktopComposerInput
        val scene = ImageComposeScene(600, 700) { composer = rememberDesktopComposerInput(session, draft) }
        try {
            scene.frames()
            val old = composer
            composer.open(true)
            session = "B"; draft = "B draft"; scene.frames()
            assertNotSame(old, composer)
            assertFalse(composer.expanded)
            assertEquals("B", composer.sessionId)
            assertEquals("B draft", composer.input.text)
            assertEquals("A draft", old.input.text)
        } finally { scene.close() }
    }

    @Test fun `actual full modal autofocus isolates shortcuts and close returns inline focus`() = runBlocking {
        val composer = owner()
        var inlineFocused = false
        var inlineSends = 0
        var fullSends = 0
        val writes = mutableListOf<String>()
        val scene = ImageComposeScene(700, 800) {
            Box(Modifier.fillMaxSize()) {
                DesktopComposerTextField(composer, false, true, writes::add, { inlineSends++ },
                    Modifier.height(96.dp).onFocusChanged { inlineFocused = it.isFocused })
                if (composer.expanded) DesktopFullComposer(composer, true, null, null, writes::add, { fullSends++ })
            }
        }
        try {
            scene.frames(); composer.open(true); scene.frames()
            scene.sendKeyEvent(enter(ctrl = true)); scene.sendKeyEvent(enter(ctrl = true, up = true))
            assertEquals(1, fullSends)
            assertEquals(0, inlineSends)
            assertFalse(inlineFocused)
            composer.close(); scene.frames()
            assertTrue(inlineFocused)
            scene.sendKeyEvent(enter(ctrl = true))
            assertEquals(1, inlineSends)
            assertEquals("draft", composer.input.text)
        } finally { scene.close() }
    }

    @Test fun `full field extends beyond height cap and delegates multiline keys like plain BasicTextField`() = runBlocking {
        val composer = owner()
        composer.open(true)
        val writes = mutableListOf<String>()
        val scene = ImageComposeScene(700, 800) {
            Box(Modifier.fillMaxSize()) {
                DesktopFullComposer(composer, true, null, null, writes::add, onSend = { fail("Plain Enter sent") })
            }
        }
        try {
            scene.frames()
            // Below the 360dp inline cap: this must still hit the full text input, not background.
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 550f), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 550f), button = PointerButton.Primary)
            scene.frames()
            scene.sendKeyEvent(enter()); scene.sendKeyEvent(enter(up = true)); scene.frames()
            assertEquals(1, composer.input.text.count { it == '\n' }, "Plain Enter")
            scene.sendKeyEvent(enter(shift = true)); scene.sendKeyEvent(enter(shift = true, up = true)); scene.frames()
            // Synthetic Shift+Enter alone also does not insert a newline in an unmodified
            // BasicTextField in ImageComposeScene. Preserve the native path rather than adding
            // an IME-sensitive replacement handler to satisfy this headless test.
            assertEquals(baselineAfterEnterKeys(), composer.input.text)
            assertEquals(composer.input.text, writes.last())
        } finally { scene.close() }
    }

    private suspend fun baselineAfterEnterKeys(): String {
        var input by mutableStateOf(TextFieldValue("draft", TextRange(5)))
        val focus = FocusRequester()
        val scene = ImageComposeScene(700, 800) {
            BasicTextField(input, { input = it }, Modifier.fillMaxSize().focusRequester(focus))
        }
        try {
            scene.frames(); focus.requestFocus(); scene.frames()
            scene.sendKeyEvent(enter()); scene.sendKeyEvent(enter(up = true)); scene.frames()
            assertEquals(1, input.text.count { it == '\n' }, "Baseline Enter")
            scene.sendKeyEvent(enter(shift = true)); scene.sendKeyEvent(enter(shift = true, up = true)); scene.frames()
            return input.text
        } finally { scene.close() }
    }

    @Test fun `actual field round trip retains selection and long text`() = runBlocking {
        val composer = owner("中文 Japanese long text\n".repeat(100))
        val selected = composer.input.copy(selection = TextRange(3, 21))
        composer.edit(false, selected) { }
        val scene = ImageComposeScene(700, 800) {
            Box(Modifier.fillMaxSize()) {
                DesktopComposerTextField(composer, false, true, {}, {}, Modifier.height(96.dp))
                if (composer.expanded) DesktopFullComposer(composer, true, null, null, {}, {})
            }
        }
        try {
            scene.frames(); composer.open(true); scene.frames()
            assertEquals(selected.text, composer.input.text)
            assertEquals(selected.selection, composer.input.selection)
            composer.close(); scene.frames()
            assertEquals(selected.text, composer.input.text)
            assertEquals(selected.selection, composer.input.selection)
        } finally { scene.close() }
    }

    @Test fun `actual field rejects composing Ctrl Enter without calling send`() = runBlocking {
        val composer = owner()
        composer.open(true)
        var sends = 0
        val scene = ImageComposeScene(700, 800) {
            DesktopFullComposer(composer, true, null, null, {}, { sends++ })
        }
        try {
            scene.frames()
            composer.edit(true, TextFieldValue("nihongo", TextRange(7), TextRange(0, 7))) { }
            scene.frames()
            assertNotNull(composer.input.composition)
            scene.sendKeyEvent(enter(ctrl = true)); scene.frames()
            assertEquals(0, sends)
        } finally { scene.close() }
    }

    @Test fun `height preference is independent of presentation transitions`() {
        val layout = DesktopComposerLayoutState()
        layout.drag(-40f, 800f)
        val height = layout.heightDp
        val composer = owner()
        composer.open(true); composer.close()
        assertEquals(height, layout.heightDp)
    }

    @Test fun `workspace wiring has discoverable all-height entrance and only existing authorities`() {
        val panel = source("DesktopPrimaryChatPanel.kt")
        val inline = panel.substringAfter("private fun PrimaryComposer(").substringBefore("private fun PrimaryComposerAction(")
        assertTrue(inline.indexOf("DesktopUiText.EXPAND_COMPOSER") < inline.indexOf("if (!collapsed)"))
        assertTrue(inline.contains("DesktopChatAttachmentAction"))
        assertFalse(inline.contains("DesktopChatImageToolbar"))
        assertTrue(inline.contains("enabled = canLaunch"))
        assertTrue(panel.contains("state.modelUsable && !state.selectedCharacterMissing && running == null"))
        assertTrue(panel.contains("onDraft = controller::editComposer"))
        assertTrue(panel.contains("composer.send(canLaunch, controller::send)"))
        assertTrue(panel.contains("controller.state.value.selectedSession?.id == composer.sessionId"))
        val full = source("DesktopFullComposer.kt")
        assertTrue(full.contains("DesktopModalSurface {"))
        assertFalse(full.contains("Window("))
        assertFalse(full.contains("Key.Escape"))
        assertFalse(full.contains("ImagePicker"))
        assertFalse(full.contains("Repository"))
    }
}
