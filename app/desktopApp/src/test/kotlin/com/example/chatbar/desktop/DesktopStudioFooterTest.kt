@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.example.chatbar.domain.image.NovelAiPromptTokenUsage
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopStudioFooterTest {
    private val awt = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block) }
    private suspend fun ImageComposeScene.frames() { repeat(8) { render().close(); yield() }; delay(80); render().close() }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun ImageComposeScene.node(label: String) = nodes().first {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
            it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == label } == true
    }
    private suspend fun ImageComposeScene.click(label: String) {
        val p = node(label).boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, p, button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, p, button = PointerButton.Primary); frames()
    }
    @Test fun `footer coordinates callbacks and responsive fallback remain safe while prompt scrolls`() = runBlocking(awt) {
        val output = Path.of("build/phase7-slice-c-scheme-a-r1-evidence/footer")
        Files.createDirectories(output)
        val evidence = mutableListOf<String>()
        for ((w, h) in listOf(1240 to 800, 1600 to 900, 900 to 700, 720 to 650, 360 to 620, 330 to 480)) {
            var busy by mutableStateOf(false)
            var undo = 0; var redo = 0; var generate = 0; var stop = 0
            val scene = ImageComposeScene(w, h) {
                DesktopStudioWorkspace(compact = {},
                    editor = { repeat(70) { StatusText("Local Prompt line $it") } },
                    footer = { DesktopStudioFooter(NovelAiPromptTokenUsage(42, 21, 512), !busy, !busy,
                        if (busy) "停止当前任务" else "生成免费", true,
                        { undo++ }, { redo++ }, { if (busy) stop++ else generate++; busy = !busy }) },
                    preview = { StatusText("Local preview") })
            }
            try {
                scene.frames()
                fun verify(label: String) {
                    val footer = scene.node("Studio 固定生成栏").boundsInRoot
                    val tokens = scene.node("Studio Tokens").boundsInRoot
                    val undoBounds = scene.node("撤销上次载入/重置").boundsInRoot
                    val redoBounds = scene.node("重做").boundsInRoot
                    val button = scene.node(label).boundsInRoot
                    evidence += "$w x $h $label footer=$footer tokens=$tokens undo=$undoBounds redo=$redoBounds generate=$button"
                    for (bounds in listOf(tokens, undoBounds, redoBounds, button)) {
                        assertTrue(bounds.left >= footer.left && bounds.right <= footer.right, "$w: horizontal containment $bounds")
                        assertTrue(bounds.top >= footer.top && bounds.bottom <= footer.bottom, "$w: vertical containment $bounds")
                    }
                    assertEquals(footer.left + 8, tokens.left, 1f)
                    assertTrue(undoBounds.right <= redoBounds.left)
                    assertTrue(redoBounds.right <= button.left)
                    assertTrue(button.right >= footer.right - 26, "Generate remains at the trailing edge")
                    assertEquals(undoBounds.center.y, redoBounds.center.y, 1f)
                    assertEquals(undoBounds.center.y, button.center.y, 1f)
                    if (w >= 720) assertEquals(tokens.center.y, button.center.y, 1f, "$w single row")
                    else assertTrue(tokens.bottom <= undoBounds.top, "Explicit two-row minimum-width fallback")
                }
                verify("生成免费")
                val footerBefore = scene.node("Studio 固定生成栏").boundsInRoot
                scene.sendPointerEvent(PointerEventType.Scroll, scene.node("Studio Prompt 编辑区").boundsInRoot.center, scrollDelta = Offset(0f, 30f))
                scene.frames(); assertEquals(footerBefore, scene.node("Studio 固定生成栏").boundsInRoot)
                scene.click("撤销上次载入/重置"); scene.click("重做")
                assertEquals(1, undo); assertEquals(1, redo)
                scene.click("生成免费"); assertEquals(1, generate); verify("停止当前任务")
                scene.click("撤销上次载入/重置"); scene.click("重做")
                assertEquals(1, undo); assertEquals(1, redo)
                scene.click("停止当前任务"); assertEquals(1, stop)
                scene.render().use { image -> image.encodeToData()!!.use { Files.write(output.resolve("footer-$w.png"), it.bytes) } }
            } finally { scene.close(); Files.write(output.resolve("coordinates.txt"), evidence) }
        }
    }
}
