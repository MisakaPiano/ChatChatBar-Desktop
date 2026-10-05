@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.example.chatbar.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.*

class DesktopInputFocusSceneTest {
    private fun key(code: Int, ctrl: Boolean = false, shift: Boolean = false, up: Boolean = false) = KeyEvent(
        key = when (code) {
            AwtKeyEvent.VK_ENTER -> Key.Enter
            AwtKeyEvent.VK_TAB -> Key.Tab
            AwtKeyEvent.VK_SPACE -> Key.Spacebar
            AwtKeyEvent.VK_ESCAPE -> Key.Escape
            else -> error("Unsupported test key")
        }, type = if (up) KeyEventType.KeyUp else KeyEventType.KeyDown,
        isCtrlPressed = ctrl, isShiftPressed = shift,
    )
    private suspend fun ImageComposeScene.frames() { repeat(3) { render().close(); yield() } }

    @Test fun `native modal layer blocks background shortcut and restores usable focus after close`() = runBlocking {
        var open by mutableStateOf(false)
        var backgroundSends = 0
        val focus = FocusRequester()
        val scene = ImageComposeScene(500, 400) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(100.dp).focusRequester(focus).onPreviewKeyEvent {
                    desktopComposerSendKey(it.key, it.type, it.isCtrlPressed, TextFieldValue("text"), true) { backgroundSends++ }
                }.focusable())
                if (open) DesktopModalSurface { Box(Modifier.size(100.dp).clickable { }) }
            }
        }
        try {
            scene.frames(); focus.requestFocus(); scene.frames()
            scene.sendKeyEvent(key(AwtKeyEvent.VK_ENTER, ctrl = true))
            assertEquals(1, backgroundSends)
            open = true; scene.frames()
            repeat(4) {
                scene.sendKeyEvent(key(AwtKeyEvent.VK_TAB))
                scene.sendKeyEvent(key(AwtKeyEvent.VK_ENTER, ctrl = true))
            }
            assertEquals(1, backgroundSends, "Modal must own input, not merely paint over the composer")
            open = false; scene.frames()
            scene.sendKeyEvent(key(AwtKeyEvent.VK_ENTER, ctrl = true))
            assertEquals(2, backgroundSends)
        } finally { scene.close() }
    }

    @Test fun `multiline text node keeps focus selection and composition through height changes`() = runBlocking {
        val initial = TextFieldValue("nihao\nnihongo", TextRange(1, 4), TextRange(0, 5))
        var input by mutableStateOf(initial)
        var collapsed by mutableStateOf(false)
        var focused = false
        val focus = FocusRequester()
        val scene = ImageComposeScene(500, 400) {
            Row(Modifier.height(if (collapsed) 48.dp else 96.dp)) {
                BasicTextField(input, { input = it }, Modifier.weight(1f).focusRequester(focus).onFocusChanged { focused = it.isFocused })
                if (collapsed) DesktopChatIconAction("Send", DesktopAppIcons.Send) { }
            }
        }
        try {
            scene.frames(); focus.requestFocus(); scene.frames()
            val accepted = input
            for (value in listOf(true, false, true)) {
                collapsed = value; scene.frames()
                assertTrue(focused)
                assertEquals(accepted, input)
                assertEquals(accepted.selection, input.selection)
                assertEquals(accepted.composition, input.composition)
            }
        } finally { scene.close() }
    }

    @Test fun `external and overlay rail buttons activate by keyboard without new key handlers`() = runBlocking {
        for (external in listOf(false, true)) {
            val focus = FocusRequester()
            val actions = mutableListOf<DesktopChatJump>()
            val scene = ImageComposeScene(500, 400) {
                Box(Modifier.focusRequester(focus)) {
                    DesktopChatNavigationGroup(listOf(DesktopChatJump.PREVIOUS, DesktopChatJump.FIRST), external, true) { actions += it }
                }
            }
            try {
                scene.frames(); focus.requestFocus(); scene.frames()
                scene.sendKeyEvent(key(AwtKeyEvent.VK_ENTER)); scene.sendKeyEvent(key(AwtKeyEvent.VK_ENTER, up = true))
                assertEquals(listOf(DesktopChatJump.PREVIOUS), actions)
                scene.sendKeyEvent(key(AwtKeyEvent.VK_TAB)); scene.frames()
                scene.sendKeyEvent(key(AwtKeyEvent.VK_SPACE)); scene.sendKeyEvent(key(AwtKeyEvent.VK_SPACE, up = true))
                assertEquals(listOf(DesktopChatJump.PREVIOUS, DesktopChatJump.FIRST), actions)
                scene.sendKeyEvent(key(AwtKeyEvent.VK_TAB, shift = true)); scene.frames()
                scene.sendKeyEvent(key(AwtKeyEvent.VK_ENTER)); scene.sendKeyEvent(key(AwtKeyEvent.VK_ENTER, up = true))
                assertEquals(DesktopChatJump.PREVIOUS, actions.last())
            } finally { scene.close() }
        }
    }

    @Test fun `resize handle is not a tab stop and disabled action cannot activate`() = runBlocking {
        for (icon in listOf(false, true)) {
            val focus = FocusRequester()
            var actions = 0
            val scene = ImageComposeScene(500, 400) {
                Column(Modifier.focusRequester(focus)) {
                    DesktopComposerResizeHandle { fail("Keyboard must not generate drag") }
                    DesktopChatIconAction("Disabled", DesktopAppIcons.Send, enabled = false) { fail("Disabled action") }
                    if (icon) DesktopChatIconAction("Send", DesktopAppIcons.Send) { actions++ }
                    else BootstrapButton("Send") { actions++ }
                }
            }
            try {
                scene.frames(); focus.requestFocus(); scene.frames()
                scene.sendKeyEvent(key(AwtKeyEvent.VK_ENTER)); scene.sendKeyEvent(key(AwtKeyEvent.VK_ENTER, up = true))
                assertEquals(1, actions, "Focus should reach the real enabled action, not the handle/disabled button")
            } finally { scene.close() }
        }
    }

    @Test fun `focusable menu outside dismiss restores parent input without changing content`() = runBlocking {
        var open by mutableStateOf(false)
        val content = TextFieldValue("unchanged message")
        val focus = FocusRequester()
        var parentKeys = 0
        val scene = ImageComposeScene(500, 400) {
            Box {
                Box(Modifier.focusRequester(focus).onKeyEvent { parentKeys++; true }.focusable())
                if (open) Popup(onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
                    Box(Modifier.size(80.dp).clickable { })
                }
            }
        }
        try {
            scene.frames(); focus.requestFocus(); scene.frames()
            open = true; scene.frames()
            // ImageComposeScene has no desktop platform Escape-to-navigation-event adapter.
            // Exercise native outside dismissal here; real-window Escape remains a smoke check.
            scene.sendPointerEvent(PointerEventType.Press, Offset(450f, 350f), button = PointerButton.Primary)
            scene.frames()
            assertFalse(open)
            assertEquals(0, parentKeys)
            scene.sendKeyEvent(key(AwtKeyEvent.VK_ENTER))
            assertEquals(1, parentKeys)
            assertEquals("unchanged message", content.text)
        } finally { scene.close() }
    }
}
