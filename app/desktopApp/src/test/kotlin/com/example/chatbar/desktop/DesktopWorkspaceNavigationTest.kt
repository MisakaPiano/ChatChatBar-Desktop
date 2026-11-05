@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopWorkspaceNavigationTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private suspend fun frames(scene: ImageComposeScene) { repeat(4) { scene.render(System.nanoTime()).close(); yield(); delay(10) } }

    @Test fun `changing workspace subsection preserves a transient edit without leaving hidden inputs exposed`() = runBlocking(edt) {
        val shown = mutableStateOf(true)
        var created = 0; var disposed = 0
        val scene = ImageComposeScene(900, 600, coroutineContext = edt) {
            Box(Modifier.fillMaxSize()) {
                DesktopRetainedPane(shown.value) {
                    var draft by remember { mutableStateOf("Original") }
                    DisposableEffect(Unit) { created++; onDispose { disposed++ } }
                    BasicTextField(draft, { draft = it }, Modifier.width(300.dp))
                }
            }
        }
        try {
            frames(scene)
            val field = nodes(scene).single { it.config.contains(SemanticsActions.SetText) }
            field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Unsaved synthetic draft"))
            frames(scene)
            shown.value = false; frames(scene)
            assertTrue(nodes(scene).none { it.config.contains(SemanticsActions.SetText) })
            assertEquals(0, disposed)
            shown.value = true; frames(scene)
            val restored = nodes(scene).single { it.config.contains(SemanticsActions.SetText) }
            assertEquals("Unsaved synthetic draft", restored.config[SemanticsProperties.EditableText].text)
            assertEquals(1, created)
        } finally { scene.close() }
        assertEquals(1, disposed)
    }

    @Test fun `navigation and full content stay inside compact and wide workspace bounds`() = runBlocking(edt) {
        for (width in listOf(420, 1240)) {
            var content: Rect? = null
            val scene = ImageComposeScene(width, 700, coroutineContext = edt) {
                DesktopWorkspacePage("Workspace", listOf(DesktopWorkspaceDestination("A", "First"), DesktopWorkspaceDestination("B", "Second")), "A", onSelect = {}) {
                    Box(Modifier.fillMaxSize().onGloballyPositioned { content = it.boundsInRoot() })
                }
            }
            try {
                frames(scene)
                val bounds = assertNotNull(content)
                assertTrue(bounds.width > 200 && bounds.height > 400)
                assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= 700)
                if (width > 700) assertTrue(bounds.left >= 164) else assertTrue(bounds.top > 0)
            } finally { scene.close() }
        }
    }

    @Test fun `studio physical image path rejoins the visible owned gallery without adding external files`() {
        val root = Path.of("build/synthetic-studio-gallery").toAbsolutePath()
        val references = listOf("images/third.png", "images/second.png", "images/first.png")
        assertEquals(1, desktopStudioPreviewIndex(root.resolve("images/../images/second.png"), references, root::resolve))
        assertEquals(-1, desktopStudioPreviewIndex(root.resolve("external.png"), references, root::resolve))
    }
}
