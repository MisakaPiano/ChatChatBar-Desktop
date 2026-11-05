@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopPeopleDividerGestureTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private suspend fun frames(scene: ImageComposeScene) { repeat(4) { scene.render(System.nanoTime()).close(); yield(); delay(10) } }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    @Test fun `real divider drag double click and collapsed handle restore share one control`() = runBlocking(edt) {
        var width by mutableStateOf(160f)
        var collapsed by mutableStateOf(false)
        var saved = 0
        var automatic = 0
        val scene = ImageComposeScene(800, 500, coroutineContext = edt) {
            Row(Modifier.fillMaxSize()) {
                if (!collapsed) Box(Modifier.width(width.dp).fillMaxHeight())
                DesktopPeopleResizeHandle(if (collapsed) 0f else width,
                    onDrag = { width = it }, onEnd = { saved++ }, onCollapse = { collapsed = true },
                    collapsed = collapsed, onExpand = { collapsed = false }, onAutomatic = { automatic++; width = 160f })
            }
        }
        suspend fun tap(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, point, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
        }
        try {
            frames(scene)
            val handle = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == "people-resize" }
            val from = Offset(handle.boundsInRoot.center.x, 60f)
            scene.sendPointerEvent(PointerEventType.Press, from, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Move, from + Offset(30f, 0f))
            frames(scene)
            scene.sendPointerEvent(PointerEventType.Move, from + Offset(100f, 0f))
            scene.sendPointerEvent(PointerEventType.Release, from + Offset(100f, 0f), button = PointerButton.Primary)
            frames(scene)
            assertTrue(width > 200f, "width=$width")
            assertEquals(1, saved)
            val line = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == "people-resize" }.boundsInRoot
            tap(Offset(line.center.x, 60f)); delay(80); tap(Offset(line.center.x, 60f)); frames(scene)
            assertEquals(1, automatic)
            assertEquals(160f, width)
            val collapse = nodes(scene).single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("收起角色栏") == true }
            tap(collapse.boundsInRoot.center); frames(scene)
            assertTrue(collapsed)
            val expand = nodes(scene).single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("展开角色栏") == true }
            tap(expand.boundsInRoot.center); frames(scene)
            assertFalse(collapsed)
            assertEquals(160f, width)
        } finally { scene.close() }
    }
}
