@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopImageWindowPresentationTest {
    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private suspend fun ImageComposeScene.frames() { repeat(8) { render(System.nanoTime()).close(); yield() } }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.text(value: String) = config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true

    @Test fun `initial sizes retain task preference on roomy work areas`() {
        assertEquals(DpSize(960.dp, 800.dp), desktopImageWindowSize(DpSize(960.dp, 800.dp), 1920, 1040))
        assertEquals(DpSize(560.dp, 760.dp), desktopImageWindowSize(DpSize(560.dp, 760.dp), 1600, 900))
    }

    @Test fun `initial sizes fit small and scaled logical work areas including taskbar space`() {
        // AWT already reports logical units at the monitor scale; never apply scale twice.
        for (scale in listOf(1f, 1.25f, 1.5f, 2f)) {
            val width = (1920 / scale).toInt()
            val height = (1040 / scale).toInt()
            val size = desktopImageWindowSize(DpSize(980.dp, 820.dp), width, height)
            assertTrue(size.width.value <= width - 48)
            assertTrue(size.height.value <= height - 64)
            assertTrue(size.width.value > 0 && size.height.value > 0)
        }
        assertEquals(DpSize(592.dp, 416.dp), desktopImageWindowSize(DpSize(900.dp, 820.dp), 640, 480))
    }

    @Test fun `long library keeps completion action visible while content scrolls`() = runBlocking(awt) {
        for ((width, height) in listOf(360 to 280, 900 to 700)) {
            var completed = 0
            val scene = ImageComposeScene(width, height) {
                DesktopImageDialogBody(footer = { BootstrapButton("完成") { completed++ } }) {
                    repeat(50) { StatusText("library item $it") }
                }
            }
            try {
                scene.frames()
                val before = scene.nodes().first { it.text("完成") }.boundsInRoot
                assertTrue(before.top >= 0 && before.bottom <= height)
                val scroll = scene.nodes().first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
                assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!(0f, 10000f))
                scene.frames()
                val after = scene.nodes().first { it.text("完成") }.boundsInRoot
                assertEquals(before, after)
                scene.sendPointerEvent(PointerEventType.Press, after.center, button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, after.center, button = PointerButton.Primary)
                scene.frames(); assertEquals(1, completed)
            } finally { scene.close() }
        }
    }

    @Test fun `short tool workspace scrolls all actions without consuming preview space`() = runBlocking(awt) {
        var applied = 0
        val scene = ImageComposeScene(420, 300) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = "preview" })
                DesktopImageToolControls(150.dp) {
                    repeat(25) { StatusText("tool $it") }
                    BootstrapButton("应用") { applied++ }
                }
            }
        }
        try {
            scene.frames()
            val preview = scene.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("preview") == true }.boundsInRoot
            assertTrue(preview.height >= 150f)
            val scroll = scene.nodes().first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
            assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!(0f, 10000f))
            fun scrollNode() = scene.nodes().first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
            fun settled(): Boolean {
                val node = scrollNode()
                val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
                val button = scene.nodes().first { it.text("应用") }.boundsInRoot
                val viewport = node.boundsInRoot
                return range.maxValue() > 0f && range.value() == range.maxValue() &&
                    button.width > 0f && button.height > 0f &&
                    button.left >= viewport.left && button.right <= viewport.right &&
                    button.top >= viewport.top && button.top >= preview.bottom && button.bottom <= viewport.bottom
            }
            try {
                withTimeout(2_000) { while (!settled()) scene.frames() }
            } catch (failure: TimeoutCancellationException) {
                val node = scrollNode()
                val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
                val button = scene.nodes().first { it.text("应用") }.boundsInRoot
                val evidence = Files.createTempDirectory("ccb-win-tool-scroll-")
                val diagnostic = "preview=$preview; controls=${node.boundsInRoot}; apply=$button; scroll=${range.value()}/${range.maxValue()}"
                Files.writeString(evidence.resolve("geometry.txt"), diagnostic)
                scene.render().use { image -> image.encodeToData()?.use { Files.write(evidence.resolve("fixture.png"), it.bytes) } }
                println("$diagnostic; fixture evidence=$evidence")
                throw failure
            }
            val apply = scene.nodes().first { it.text("应用") }.boundsInRoot
            assertTrue(apply.top >= preview.bottom && apply.bottom <= 300)
            scene.sendPointerEvent(PointerEventType.Press, apply.center, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, apply.center, button = PointerButton.Primary)
            scene.frames(); assertEquals(1, applied)
        } finally { scene.close() }
    }
}
