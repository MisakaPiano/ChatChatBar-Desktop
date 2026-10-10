@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopStudioFilmstripTest {
    @Test fun `vertical rail virtualizes twenty images and follows selection without stealing it on refresh`() = runBlocking(awt) {
        val bytes = DesktopImageEditing.png(BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB))
        var paths by mutableStateOf((1..24).map { "rail-$it" })
        var selected by mutableStateOf(paths.last())
        val scroll = LazyListState()
        val scene = ImageComposeScene(100, 520) { StudioVerticalFilmstrip(paths, selected, { bytes }, scroll) { selected = it } }
        try {
            withTimeout(5000) { while (scroll.layoutInfo.visibleItemsInfo.none { it.key == selected }) scene.frames() }
            assertTrue(scroll.layoutInfo.visibleItemsInfo.size < 10)
            assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("竖向结果缩略图滚动条") == true })
            selected = paths.first()
            withTimeout(5000) { while (scroll.firstVisibleItemIndex != 0) scene.frames() }
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(40f, 200f), scrollDelta = Offset(0f, 4f))
            withTimeout(5000) { while (scroll.firstVisibleItemIndex == 0 && scroll.firstVisibleItemScrollOffset == 0) scene.frames() }
            val visible = scroll.layoutInfo.visibleItemsInfo.first { it.offset >= 0 && it.offset + it.size < 500 }
            val point = Offset(40f, visible.offset + visible.size / 2f)
            scene.sendPointerEvent(PointerEventType.Press, point, button = androidx.compose.ui.input.pointer.PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, point, button = androidx.compose.ui.input.pointer.PointerButton.Primary)
            scene.frames(); assertEquals(visible.key, selected)
            val old = selected
            paths = listOf("refresh") + paths; scene.frames(); assertEquals(old, selected)
            selected = paths.last()
            withTimeout(5000) { while (scroll.layoutInfo.visibleItemsInfo.none { it.key == selected }) scene.frames() }
        } finally { scene.close() }
    }
    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private suspend fun ImageComposeScene.frames() { repeat(5) { render().close(); yield() }; delay(30) }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }

    @Test fun `twenty image filmstrip reveals selection and shares wheel with visible scrollbar`() = runBlocking(awt) {
        val bytes = DesktopImageEditing.png(BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB))
        var paths by mutableStateOf((1..20).map { "image-$it" })
        var selected by mutableStateOf(paths.last())
        val scroll = LazyListState()
        val scene = ImageComposeScene(280, 130) {
            StudioFilmstrip(paths, selected, { bytes }, scroll) { selected = it }
        }
        try {
            withTimeout(5000) { while (scroll.layoutInfo.visibleItemsInfo.none { it.index == 19 }) scene.frames() }
            assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("结果缩略图滚动条") == true })
            selected = paths.first()
            withTimeout(5000) { while (scroll.firstVisibleItemIndex != 0) scene.frames() }
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(120f, 40f), scrollDelta = Offset(0f, 4f))
            withTimeout(5000) { while (scroll.firstVisibleItemIndex == 0 && scroll.firstVisibleItemScrollOffset == 0) scene.frames() }
            val old = selected
            paths = listOf("history-refresh") + paths
            scene.frames()
            assertEquals(old, selected)
            selected = paths.last()
            withTimeout(5000) { while (scroll.layoutInfo.visibleItemsInfo.none { it.key == selected }) scene.frames() }
            assertEquals(selected, paths.last())
        } finally { scene.close() }
    }
}
