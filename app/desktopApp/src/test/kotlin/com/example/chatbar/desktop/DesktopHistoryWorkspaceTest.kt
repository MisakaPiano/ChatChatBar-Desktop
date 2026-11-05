@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.example.chatbar.domain.image.NovelAiHistoryApplyMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopHistoryWorkspaceTest {
    private val edt = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block) }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private suspend fun frames(scene: ImageComposeScene) { repeat(6) { scene.render(System.nanoTime()).close(); yield(); delay(15) } }
    private fun description(node: SemanticsNode, text: String) = node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
    private fun text(node: SemanticsNode, text: String) = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true

    @Test fun `wide history keeps original gallery and recipe callbacks while filters and compact layout preserve source data`() = runBlocking(edt) {
        val fixture = FinalProductDesignFixture()
        try {
            fixture.initialize(); fixture.seedLocalImages()
            val controller = fixture.container.novelAiStudioController
            val before = controller.history.first(); val draft = controller.draft.value
            for (width in listOf(1280, 640)) {
                var preview: Pair<List<String>, Int>? = null
                var applied: NovelAiHistoryApplyMode? = null
                val scene = ImageComposeScene(width, 900, coroutineContext = edt) {
                    Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.background)) {
                        DesktopStudioHistory(controller, onApply = { _, _, mode -> applied = mode },
                            onPreview = { paths, index -> preview = paths to index }, onUse = { _, _ -> })
                    }
                }
                try {
                    frames(scene)
                    withTimeout(5000) { do { frames(scene) } while (nodes(scene).none { text(it, "配方详情") }) }
                    val pane = nodes(scene).singleOrNull { description(it, "历史图片详情") }
                    if (width == 1280) {
                        val bounds = assertNotNull(pane).boundsInRoot
                        assertTrue(bounds.right <= width && bounds.width >= 300 && bounds.height > 400)
                        val point = Offset(bounds.center.x, bounds.top + 100f)
                        scene.sendPointerEvent(PointerEventType.Press, point, button = PointerButton.Primary)
                        scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary); frames(scene)
                        withTimeout(5000) { while (preview == null) frames(scene) }
                        val gallery = assertNotNull(preview)
                        assertEquals(8, gallery.first.size); assertTrue(gallery.second in gallery.first.indices)
                        val label = nodes(scene).first { text(it, "完整复现") }
                        val action = generateSequence(label) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
                        assertTrue(action.config[SemanticsActions.OnClick].action!!.invoke())
                        assertEquals(NovelAiHistoryApplyMode.FULL, applied)
                    } else assertNull(pane)
                    val fields = nodes(scene).filter { it.config.contains(SemanticsActions.SetText) }.sortedBy { it.boundsInRoot.top }
                    fields.first().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("no-match-synthetic")); frames(scene)
                    assertFalse(nodes(scene).any { description(it, "历史图片详情") })
                    fields.first().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("")); frames(scene)
                    assertEquals(before, controller.history.first()); assertEquals(draft, controller.draft.value)
                    System.getProperty("chatbar.test.screenshot.dir")?.let { directory ->
                        Files.createDirectories(Path.of(directory)); scene.render(System.nanoTime()).use { it.encodeToData()?.use { image ->
                            Files.write(Path.of(directory, "history-workspace-$width.png"), image.bytes)
                        } }
                    }
                } finally { scene.close() }
            }
            assertTrue(fixture.requests.isEmpty())
        } finally { fixture.close() }
    }
}
