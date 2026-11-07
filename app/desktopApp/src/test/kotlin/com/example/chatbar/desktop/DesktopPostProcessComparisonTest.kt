@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.test.*

class DesktopPostProcessComparisonTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `actual comparison shares source bounds and zoom pan while divider remains viewport based and reset restores fit`() = runBlocking(edt) {
        fun image(width: Int, height: Int, base: Color): ByteArray {
            val raster = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val g = raster.createGraphics()
            try {
                g.color = base; g.fillRect(0, 0, width, height)
                g.color = Color.GREEN; g.fillRect(0, (height * .4f).toInt(), width, (height * .2f).toInt())
                return DesktopImageEditing.png(raster)
            } finally { g.dispose(); raster.flush() }
        }
        val source = image(400, 300, Color.RED)
        // Slightly different rounded aspect ratio must still use the original's exact fitted bounds.
        val result = image(800, 604, Color.BLUE)
        val sourceCopy = source.copyOf(); val resultCopy = result.copyOf()
        val view = DesktopPostProcessComparisonState()
        val scene = ImageComposeScene(640, 440, coroutineContext = edt) {
            DesktopPostProcessComparison("synthetic", source, result, IntSize(400, 300), Modifier.fillMaxSize(), view)
        }
        fun nodes(): List<SemanticsNode> {
            fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
        }
        fun comparison() = nodes().single { it.config.contains(SemanticsActions.SetProgress) }
        suspend fun frames() { repeat(5) { scene.render(System.nanoTime()).close(); delay(20) } }
        fun pixels(): BufferedImage = scene.render(System.nanoTime()).use { bitmap ->
            bitmap.encodeToData()!!.use { ImageIO.read(ByteArrayInputStream(it.bytes)) }
        }
        fun review(name: String) { System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { dir ->
            val p = Path.of(dir).resolve(name); Files.createDirectories(p.parent)
            scene.render(System.nanoTime()).use { bitmap -> bitmap.encodeToData()!!.use { Files.write(p, it.bytes) } }
        } }
        fun greenRows(bitmap: BufferedImage, x: Int): List<Int> = (1 until view.viewport.height - 1).filter { y ->
            val c = Color(bitmap.getRGB(x, y), true); c.green > 240 && c.red < 15 && c.blue < 15
        }
        fun assertAligned() {
            val bitmap = pixels()
            try {
                val left = greenRows(bitmap, view.viewport.width / 4)
                val right = greenRows(bitmap, view.viewport.width * 3 / 4)
                assertTrue(left.size > 10 && right.size > 10, "Both source and result must actually render")
                assertTrue(abs(left.first() - right.first()) <= 2 && abs(left.last() - right.last()) <= 2,
                    "Normalized stripe must align across the viewport clip despite different raster sizes: $left / $right")
            } finally { bitmap.flush() }
        }
        suspend fun drag(from: Offset, to: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, from, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Move, from + (to - from) * .5f, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Move, to, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, to, button = PointerButton.Primary); frames()
        }
        try {
            withTimeout(10_000) { while (nodes().count { it.config.contains(DesktopDecodedImageIdentity) } != 2) frames() }
            frames(); assertAligned(); review("post-process-comparison-fit.png")
            val bounds = comparison().boundsInRoot
            val middle = bounds.center
            drag(Offset(middle.x, bounds.top + 35f), Offset(bounds.left + bounds.width * .7f, bounds.top + 35f))
            assertTrue(view.fraction in 0.65f..0.75f); assertEquals(1f, view.transform.zoom)
            assertTrue(comparison().config[SemanticsActions.SetProgress].action!!.invoke(.5f)); frames()
            scene.sendPointerEvent(PointerEventType.Scroll, middle, scrollDelta = Offset(0f, -7f)); frames()
            assertTrue(view.transform.zoom > 1.8f && view.transform.zoom < 2.2f)
            val divider = view.fraction
            drag(Offset(bounds.left + bounds.width * .2f, middle.y - 30f), Offset(bounds.left + bounds.width * .2f + 65f, middle.y + 15f))
            assertTrue(view.transform.pan.x > 20f && view.transform.pan.y > 10f)
            assertEquals(divider, view.fraction, "Dragging an image must not move the divider")
            frames(); assertAligned(); review("post-process-comparison-zoom-pan.png")
            val reset = nodes().first { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("适应 / 重置对比") == true }
            val point = reset.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, point, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary); frames()
            assertEquals(DesktopViewerTransform(), view.transform); assertEquals(.5f, view.fraction); assertAligned()
            scene.sendPointerEvent(PointerEventType.Scroll, middle, scrollDelta = Offset(0f, -100f)); frames()
            assertEquals(5f, view.transform.zoom)
            scene.sendPointerEvent(PointerEventType.Scroll, middle, scrollDelta = Offset(0f, 100f)); frames()
            assertEquals(DesktopViewerTransform(), view.transform)
            assertContentEquals(sourceCopy, source); assertContentEquals(resultCopy, result)
        } finally { scene.close() }
    }
}
