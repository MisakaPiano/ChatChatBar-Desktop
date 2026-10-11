@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopUnifiedViewerTest {
    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private val bytes = DesktopImageEditing.png(BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB).apply {
        for (x in 0 until width) for (y in 0 until height) setRGB(x, y, java.awt.Color(x * 6, y * 8, 100).rgb)
    })
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.label(s: String) = config.getOrNull(SemanticsProperties.ContentDescription)?.contains(s) == true ||
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == s } == true
    private suspend fun ImageComposeScene.frames() { repeat(4) { render().close(); yield() }; delay(20); render().close() }
    private suspend fun ImageComposeScene.until(check: () -> Boolean) = withTimeout(5_000) { while (!check()) frames() }
    private suspend fun ImageComposeScene.tap(p: Offset) {
        sendPointerEvent(PointerEventType.Press, p, button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, p, button = PointerButton.Primary)
        frames()
    }
    private suspend fun ImageComposeScene.double(p: Offset) { tap(p); delay(70); tap(p); frames() }
    private fun ImageComposeScene.point(label: String) = nodes().first { it.label(label) }.boundsInRoot.center
    private fun ImageComposeScene.pixels() = render().use { it.encodeToData()!!.use { data -> data.bytes.contentHashCode() } }

    @Test fun `chat double click opens exactly once without changing image actions`() = runBlocking(awt) {
        val opens = mutableListOf<String>(); val deletes = mutableListOf<String>()
        val scene = ImageComposeScene(450, 300) {
            DesktopMessageImageItem("owned", { bytes }, null, false, false, opens::add, {}, deletes::add)
        }
        try {
            scene.until { scene.nodes().any { it.label("图片") } }
            scene.double(scene.point("打开图片预览"))
            scene.until { opens.isNotEmpty() }; delay(400); scene.frames()
            assertEquals(listOf("owned"), opens); assertTrue(deletes.isEmpty())
            assertEquals(1, scene.nodes().count { it.label("图片操作") && it.config.getOrNull(SemanticsActions.OnClick) != null })
        } finally { scene.close() }
    }

    @Test fun `filmstrip double click previews without pending single selection in both layouts`() = runBlocking(awt) {
        for (vertical in listOf(false, true)) {
            var selected = "first"; val opens = mutableListOf<String>()
            val scene = ImageComposeScene(400, 400) {
                if (vertical) StudioVerticalFilmstrip(listOf("first", "second"), selected, { bytes }, onPreview = opens::add) { selected = it }
                else StudioFilmstrip(listOf("first", "second"), selected, { bytes }, onPreview = opens::add) { selected = it }
            }
            try {
                scene.frames(); scene.double(scene.point("选择结果缩略图 2"))
                scene.until { opens.isNotEmpty() }; delay(400); scene.frames()
                assertEquals(listOf("second"), opens); assertEquals("first", selected)
                scene.tap(scene.point("选择结果缩略图 2")); scene.until { selected == "second" }
            } finally { scene.close() }
        }
    }

    @Test fun `studio inline double click opens once while default zoom surface retains double zoom`() = runBlocking(awt) {
        var opened = 0
        val scene = ImageComposeScene(400, 300) {
            DesktopImageZoomSurface("saved", { bytes }, Modifier.fillMaxSize(), onOpenViewer = { opened++ })
        }
        try {
            scene.until { scene.nodes().any { it.label("图片") } }
            scene.double(Offset(200f, 150f)); scene.until { opened == 1 }
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(200f, 150f), scrollDelta = Offset(0f, -4f))
            scene.frames(); assertEquals(1, opened)
        } finally { scene.close() }
        // Without the opt-in callback the existing Viewer interaction remains in-place.
        val viewer = ImageComposeScene(400, 300) { DesktopImageZoomSurface("saved", { bytes }, Modifier.fillMaxSize()) }
        try {
            viewer.until { viewer.nodes().any { it.label("图片") } }
            val fit = viewer.pixels()
            viewer.double(Offset(200f, 150f)); assertNotEquals(fit, viewer.pixels()); assertEquals(1, opened)
            viewer.double(Offset(200f, 150f)); assertEquals(fit, viewer.pixels())
        }
        finally { viewer.close() }
    }

    @Test fun `compact saved double click opens viewer without expanding or editing draft`() = runBlocking(awt) {
        val file = java.nio.file.Files.createTempFile("viewer-fixture-", ".png")
        java.nio.file.Files.write(file, bytes)
        var expanded = 0; var opened = 0; var actions = 0
        val scene = ImageComposeScene(700, 240) {
            DesktopStudioCompactResult(file, null, false, { expanded++ }, { opened++ }, { actions++ })
        }
        try {
            scene.until { scene.nodes().any { it.label("图片") } }
            scene.double(scene.point("图片")); scene.until { opened == 1 }; delay(400); scene.frames()
            assertEquals(0, expanded); assertEquals(0, actions)
        } finally { scene.close(); java.nio.file.Files.delete(file) }
    }

    @Test fun `hover arrows occupy image sides navigate boundaries and disappear for a single image`() = runBlocking(awt) {
        var index by mutableStateOf(0); var count by mutableStateOf(3)
        val visited = mutableListOf<Int>()
        val scene = ImageComposeScene(700, 550) {
            Box(Modifier.padding(start = 90.dp, top = 70.dp)) {
                DesktopViewerImageRegion(index, count, { index = it; visited.add(it) }, Modifier.size(500.dp, 400.dp)) {
                    DesktopImageZoomSurface("image-$index", { bytes }, Modifier.fillMaxSize())
                }
            }
        }
        try {
            scene.frames(); assertFalse(scene.nodes().any { it.label("预览下一张") })
            scene.sendPointerEvent(PointerEventType.Move, Offset(570f, 270f)); scene.frames()
            scene.until { scene.nodes().any { it.label("预览下一张") } }
            val right = scene.nodes().first { it.label("预览下一张") }.boundsInRoot
            assertTrue(right.width >= 44 && right.height >= 44); assertTrue(right.right <= 590 && right.left > 500)
            assertEquals(270f, right.center.y, 1f)
            assertFalse(scene.nodes().any { it.label("预览上一张") })
            scene.tap(right.center); assertEquals(1, index)
            scene.tap(scene.point("预览下一张")); assertEquals(2, index)
            assertFalse(scene.nodes().any { it.label("预览下一张") })
            val left = scene.nodes().first { it.label("预览上一张") }.boundsInRoot
            assertTrue(left.left >= 90 && left.right < 180)
            scene.tap(left.center); assertEquals(1, index)
            assertEquals(listOf(1, 2, 1), visited)
            count = 1; index = 0; scene.frames()
            assertFalse(scene.nodes().any { it.label("预览上一张") || it.label("预览下一张") })
        } finally { scene.close() }
    }

    @Test fun `history direct viewer follows filtered order across batches without selecting or opening detail`() = runBlocking(awt) {
        val f = FinalProductDesignFixture()
        try {
            f.initialize(); f.seedLocalImages()
            val c = f.container.novelAiStudioController
            val seed = c.history.first().first()
            c.repository.saveHistory(seed.copy(images = seed.images.take(2), createdAt = 100,
                recipe = seed.recipe.copy(basePrompt = "matched")))
            c.repository.saveHistory(seed.copy(id = "second-batch", images = seed.images.drop(2).take(2), createdAt = 200,
                recipe = seed.recipe.copy(basePrompt = "matched")))
            c.repository.saveHistory(seed.copy(id = "excluded", images = seed.images.takeLast(2), createdAt = 300,
                recipe = seed.recipe.copy(basePrompt = "excluded")))
            c.repository.saveHistoryFoldPreference(NovelAiHistoryFoldPreference(0, false, NovelAiHistoryFoldType.FULL))
            val opens = mutableListOf<Pair<List<String>, Int>>()
            val draft = c.draft.value
            val scene = ImageComposeScene(1000, 800) {
                DesktopStudioHistory(c, { _, _, _ -> error("No apply") }, { paths, index -> opens.add(paths to index) }, { _, _ -> error("No use") })
            }
            try {
                scene.until { scene.nodes().any { it.label("配方详情") } }
                val field = scene.nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null }
                field.config[SemanticsActions.SetText].action!!(AnnotatedString("matched"))
                scene.until { scene.nodes().count { it.label("配方详情") && it.config.getOrNull(SemanticsActions.OnClick) != null } == 4 }
                val expected = com.example.chatbar.ui.imageprompt.NovelAiHistoryFilterPolicy.filter(c.history.first(), "matched", null).map { it.image.path }
                scene.double(scene.nodes().filter { it.label("图片") }[1].boundsInRoot.center)
                scene.until { opens.isNotEmpty() }; delay(400); scene.frames()
                assertEquals(listOf(expected to 1), opens)
                assertTrue(c.state.value.selected.isEmpty()); assertEquals(draft, c.draft.value)
                assertFalse(scene.nodes().any { it.label("返回历史") })
            } finally { scene.close() }
        } finally { f.close() }
    }

    @Test fun `history album single click still enters album but double click only opens unified collection`() = runBlocking(awt) {
        val f = FinalProductDesignFixture()
        try {
            f.initialize(); f.seedLocalImages()
            val c = f.container.novelAiStudioController
            c.repository.saveHistoryFoldPreference(NovelAiHistoryFoldPreference(0, true, NovelAiHistoryFoldType.FULL))
            val opens = mutableListOf<Pair<List<String>, Int>>()
            val scene = ImageComposeScene(900, 750) {
                DesktopStudioHistory(c, { _, _, _ -> }, { paths, index -> opens.add(paths to index) }, { _, _ -> })
            }
            try {
                scene.until { scene.nodes().any { it.label("图片") } }
                scene.double(scene.point("图片")); scene.until { opens.size == 1 }; delay(400); scene.frames()
                assertEquals(8, opens.single().first.size)
                assertFalse(scene.nodes().any { it.label("返回上层") })
                scene.tap(scene.point("图片")); scene.until { scene.nodes().any { it.label("返回上层") } }
                assertEquals(1, opens.size)
                assertTrue(c.state.value.selected.isEmpty())
            } finally { scene.close() }
        } finally { f.close() }
    }
}
