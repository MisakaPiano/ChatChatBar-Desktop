@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
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

    @Test fun `actual focused field Ctrl Z Y stay local and global rebase cannot replay stale field content`(): Unit = runBlocking(edt) {
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

    @Test fun `actual platform request uses live moved editor coordinates and preserves composing source text`(): Unit = runBlocking(edt) {
        var input by mutableStateOf(TextFieldValue("synthetic source", TextRange(3)))
        var secondInput by mutableStateOf(TextFieldValue("second synthetic person", TextRange(5)))
        var offset by mutableStateOf(20.dp)
        var request: PlatformTextInputMethodRequest? = null
        val focus = FocusRequester()
        val secondFocus = FocusRequester()
        val capture = PlatformTextInputInterceptor { current, _ -> request = current; awaitCancellation() }
        val scene = ImageComposeScene(620, 600, coroutineContext = edt) {
            InterceptPlatformTextInput(capture) {
                Box(Modifier.padding(start = 35.dp).offset(y = offset).width(480.dp).height(180.dp)) {
                    StudioPromptTextEditor(input, { input = it }, true, emptyList(), Modifier.fillMaxWidth().focusRequester(focus))
                }
                Box(Modifier.padding(start = 35.dp).offset(y = 430.dp).width(480.dp).height(100.dp)) {
                    StudioPromptTextEditor(secondInput, { secondInput = it }, true, emptyList(), Modifier.fillMaxWidth().focusRequester(secondFocus))
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
            secondFocus.requestFocus(); scene.frames()
            withTimeout(5_000) { while (request === actual) scene.frames() }
            val second = assertNotNull(request)
            assertTrue(assertNotNull(second.focusedRectInRoot()).top >= 430f)
            second.onEditCommand(listOf(SetComposingTextCommand("ni", 1))); scene.frames()
            assertNotNull(secondInput.composition); assertTrue(secondInput.text.contains("ni"))
            assertEquals("synthetic source", input.text)
            assertEquals(secondInput.text, second.value().text)
            System.getenv("CCB_STUDIO_HISTORY_REVIEW_DIR")?.let { path ->
                val target = Path.of(path).resolve("synthetic-annotated-ime-anchor.png"); Files.createDirectories(target.parent)
                scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(target, it.bytes) } }
            }
        } finally { scene.close() }
    }

    @Test fun `rendered selection highlights source glyph band and leaves annotation row unselected`(): Unit = runBlocking(edt) {
        val input = TextFieldValue("synthetic source", TextRange(1, 8))
        val scene = ImageComposeScene(420, 120, coroutineContext = edt) {
            CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(Color.Red, Color.Red)) {
                Box(Modifier.fillMaxSize().background(Color.Black).padding(12.dp)) {
                    StudioPromptTextEditor(input, {}, true, emptyList(), Modifier.fillMaxWidth())
                }
            }
        }
        try {
            scene.frames()
            val bytes = scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { it.bytes } }
            val image = ImageIO.read(ByteArrayInputStream(bytes))
            val selectedRows = (0 until image.height).filter { y -> (0 until image.width).any { x ->
                val rgb = image.getRGB(x, y); (rgb and 0xffffff) == 0xff0000
            } }
            assertTrue(selectedRows.isNotEmpty(), "Actual selection background must be drawn")
            assertTrue(selectedRows.last() - selectedRows.first() < 24, "Translation leading must not be selected")
            assertTrue(selectedRows.last() < 36, "Source begins at12px; annotation row is outside selection")
            System.getenv("CCB_STUDIO_HISTORY_REVIEW_DIR")?.let { directory ->
                val target = Path.of(directory).resolve("synthetic-source-only-selection.png")
                Files.createDirectories(target.parent); Files.write(target, bytes)
            }
        } finally { scene.close() }
    }
}
