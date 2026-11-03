@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopStudioInputHistorySceneTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private suspend fun ImageComposeScene.frames() { repeat(5) { render(System.nanoTime()).close(); delay(15) } }
    private suspend fun ImageComposeScene.shortcut(key: Key) {
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = true))
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = true)); frames()
    }

    @Test fun `actual focused field Ctrl Z Y stay local and global rebase cannot replay stale field content`() = runBlocking(edt) {
        val registry = DesktopStudioTextHistories()
        val a = registry.field("A", "A"); val b = registry.field("B", "B")
        val focusA = FocusRequester(); val focusB = FocusRequester()
        var outside = 0
        val scene = ImageComposeScene(500, 220, coroutineContext = edt) {
            Column(Modifier.onPreviewKeyEvent { outside++; false }) {
                BasicTextField(a.value, { a.edit(it) {} }, Modifier.focusRequester(focusA).studioTextHistoryKeys(a) {})
                BasicTextField(b.value, { b.edit(it) {} }, Modifier.focusRequester(focusB).studioTextHistoryKeys(b) {})
            }
        }
        try {
            scene.frames()
            a.edit(TextFieldValue("A first paste")) {}; a.edit(TextFieldValue("A second paste")) {}
            b.edit(TextFieldValue("B first paste")) {}
            focusA.requestFocus(); scene.frames(); scene.shortcut(Key.Z)
            assertEquals("A first paste", a.value.text); assertEquals("B first paste", b.value.text)
            scene.shortcut(Key.Z); assertEquals("A", a.value.text)
            scene.shortcut(Key.Y); assertEquals("A first paste", a.value.text)
            focusB.requestFocus(); scene.frames(); scene.shortcut(Key.Z)
            assertEquals("B", b.value.text); assertEquals("A first paste", a.value.text)
            a.echo("global restored value", forced = true)
            focusA.requestFocus(); scene.frames(); scene.shortcut(Key.Y)
            assertEquals("global restored value", a.value.text)
            // The parent receives events but has no global undo shortcut; focus owns the mutation.
            assertTrue(outside > 0)
        } finally { scene.close() }
    }

    @Test fun `actual platform request uses live moved editor coordinates and preserves composing source text`() = runBlocking(edt) {
        var input by mutableStateOf(TextFieldValue("synthetic source", TextRange(3)))
        var offset by mutableStateOf(20.dp)
        var request: PlatformTextInputMethodRequest? = null
        val focus = FocusRequester()
        val capture = PlatformTextInputInterceptor { current, _ -> request = current; awaitCancellation() }
        val scene = ImageComposeScene(620, 600, coroutineContext = edt) {
            InterceptPlatformTextInput(capture) {
                Box(Modifier.padding(start = 35.dp).offset(y = offset).width(480.dp).height(180.dp)) {
                    StudioPromptTextEditor(input, { input = it }, true, emptyList(), Modifier.fillMaxWidth().focusRequester(focus))
                }
            }
        }
        try {
            scene.frames(); focus.requestFocus(); scene.frames()
            withTimeout(5_000) { while (request == null) { scene.frames() } }
            val actual = assertNotNull(request)
            val before = assertNotNull(actual.focusedRectInRoot())
            offset = 200.dp; scene.frames()
            val after = assertNotNull(actual.focusedRectInRoot())
            assertEquals(180f, after.top - before.top, 1f)
            assertTrue(after.height < 30f, "Candidate anchor must use source glyph height, not annotation leading")
            actual.onEditCommand(listOf(SetComposingTextCommand("ni", 1))); scene.frames()
            assertNotNull(input.composition); assertTrue(input.text.contains("ni"))
            assertEquals(input.text, actual.value().text)
            System.getenv("CCB_STUDIO_HISTORY_REVIEW_DIR")?.let { path ->
                val target = Path.of(path).resolve("synthetic-annotated-ime-anchor.png"); Files.createDirectories(target.parent)
                scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(target, it.bytes) } }
            }
        } finally { scene.close() }
    }
}
