@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopCharacterPositionViewportTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `default tool frame and short viewport keep both coordinate fields and reset reachable at real click height`() = runBlocking(edt) {
        for (height in listOf(650, 480)) {
            var draft by mutableStateOf(NovelAiStudioDraft(selectedModel = NovelAiImageModel.V5_FULL,
                v5Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, useCharacterPositions = true),
                characters = listOf(NovelAiCharacterPromptDraft(id = "person", prompt = "Synthetic person", center = DesignedCharacterCenter(.2f, .3f)))))
            val chrome = DesktopWindowChrome {}
            val recorder = DesktopChromeLayoutRecorder(chrome)
            val scene = ImageComposeScene(640, height, coroutineContext = edt) {
                DesktopImageToolFrame("角色位置", chrome, recorder) { DesktopCharacterPositions(draft) { draft = it(draft) } }
            }
            fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
            fun nodes() = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
            fun label(n: SemanticsNode, value: String): Boolean = n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value) == true ||
                n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true || n.children.any { label(it, value) }
            fun target(value: String) = nodes().first { label(it, value) &&
                (it.config.contains(SemanticsActions.SetText) || it.config.contains(SemanticsActions.OnClick)) }
            fun ancestry(layout: LayoutInfo) = generateSequence(layout) { it.parentInfo }.toList()
            fun controlBounds(n: SemanticsNode, value: String): Rect {
                val layout = if (n.config.contains(SemanticsActions.SetText)) {
                    val caption = nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
                    val path = ancestry(n.layoutInfo)
                    val captionPath = ancestry(caption.layoutInfo)
                    val column = assertNotNull(path.firstOrNull { item -> captionPath.any { it === item } })
                    // The label and decorated BasicTextField are direct siblings. Its internal
                    // SetText node measures the 13sp text, not the padded input click region.
                    assertNotNull(path.firstOrNull { it.parentInfo === column }).also {
                        assertFalse(it === n.layoutInfo, "Measure the decorated input, not the editable text")
                    }
                } else n.layoutInfo
                val coordinates = layout.coordinates
                assertTrue(layout.isAttached && layout.isPlaced && coordinates.isAttached)
                assertEquals(layout.width, coordinates.size.width)
                assertEquals(layout.height, coordinates.size.height)
                val origin = coordinates.localToRoot(Offset.Zero)
                return Rect(origin.x, origin.y, origin.x + layout.width, origin.y + layout.height)
            }
            suspend fun frames() { repeat(4) { scene.render().close(); delay(20) } }
            suspend fun reach(value: String): SemanticsNode {
                repeat(5) {
                    val n = target(value); val b = controlBounds(n, value)
                    val scroll = nodes().firstOrNull { it.config.contains(SemanticsActions.ScrollBy) }
                    val viewport = scroll?.boundsInRoot ?: Rect(0f, 44f, 640f, height.toFloat())
                    println("POSITION_CONTROL height=$height label=$value text=${n.boundsInRoot} control=$b viewport=$viewport")
                    if (b.width >= 24 && b.height >= 24 && b.left >= viewport.left && b.right <= viewport.right &&
                        b.top >= viewport.top && b.bottom <= viewport.bottom) return n
                    assertNotNull(scroll, "$height/$value has unusable bounds $b and no scroll owner")
                    assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 160f))
                    frames()
                }
                error("$height/$value not reachable at decorated control bounds: ${controlBounds(target(value), value)}")
            }
            suspend fun clickInput(value: String) {
                val b = controlBounds(reach(value), value)
                // Exercise the actual pointer in the decoration padding, outside the text node.
                val point = Offset(b.left + 3f, b.top + 3f)
                scene.sendPointerEvent(PointerEventType.Press, point, button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
                frames()
                assertEquals(true, target(value).config.getOrNull(SemanticsProperties.Focused), "$value padding must focus its input")
            }
            try {
                frames()
                clickInput("X %"); val x = target("X %")
                assertTrue(x.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("25"))); frames()
                clickInput("Y %"); val y = target("Y %")
                assertTrue(y.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("75"))); frames()
                assertEquals(DesignedCharacterCenter(.25f, .75f), draft.characters.single().center)
                val reset = reach("均匀重置")
                System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
                    val p = Path.of(directory).resolve("position-controls-reachable-$height.png"); Files.createDirectories(p.parent)
                    scene.render().use { image -> image.encodeToData()!!.use { Files.write(p, it.bytes) } }
                }
                val resetPoint = controlBounds(reset, "均匀重置").center
                scene.sendPointerEvent(PointerEventType.Press, resetPoint, button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, resetPoint, button = PointerButton.Primary)
                frames()
                assertNull(draft.characters.single().center)
            } finally { scene.close() }
        }
    }
}
