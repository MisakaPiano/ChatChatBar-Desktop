@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.GeneratedImageMetadata
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopMessageImagePresentationTest {
    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.label(text: String) = config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true ||
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    private suspend fun ImageComposeScene.frames() { repeat(6) { render().close(); yield() }; delay(40); render().close() }
    private suspend fun ImageComposeScene.imageBounds(expectedWidth: Float): Rect = withTimeout(10_000) {
        var found: Rect? = null
        while (found == null) {
            frames()
            found = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("图片") == true &&
                kotlin.math.abs(it.boundsInRoot.width - expectedWidth) <= 2f }?.boundsInRoot
        }
        found
    }
    private fun png(width: Int, height: Int) = DesktopImageEditing.png(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB))
    private fun metadata(path: String) = GeneratedImageMetadata(path, "scene", negativePrompt = "", sizePreset = "fixture", width = 16, height = 16)

    @Test fun `source dimensions survive preview sampling at high display density`() = runBlocking(awt) {
        val bytes = png(2048, 1536)
        assertEquals(1024, DesktopImageEditing.decode(bytes, longestSide = 1600).width)
        var source = IntSize.Zero
        val display = DesktopImageEditing.decode(bytes, longestSide = 1600, minimumDisplayWidth = 1328,
            onSourceDimensions = { width, height -> source = IntSize(width, height) })
        assertEquals(IntSize(2048, 1536), source)
        assertTrue(display.width >= 1328)
        assertEquals(664f, desktopMessageImageSize(source, 664.dp, 2f).width.value)
        assertEquals(498f, desktopMessageImageSize(source, 664.dp, 2f).height.value)
        var reads = 0
        val scene = ImageComposeScene(1400, 1100) {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                DesktopMessageImageItem("large", { reads++; bytes }, null, false, false, {}, {}, {})
            }
        }
        try {
            val bounds = scene.imageBounds(1328f)
            assertEquals(996f, bounds.height, 2f)
            assertEquals(1, reads)
        } finally { scene.close() }
    }

    @Test fun `JPEG EXIF orientation supplies orientation-correct source dimensions`() {
        val output = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB), "jpeg", output)
        val jpeg = output.toByteArray()
        val exif = byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, 34,
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
            'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, 0, 1,
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0)
        val oriented = jpeg.copyOfRange(0, 2) + exif + jpeg.copyOfRange(2, jpeg.size)
        var source = IntSize.Zero
        val decoded = DesktopImageEditing.decode(oriented, longestSide = 1600,
            onSourceDimensions = { width, height -> source = IntSize(width, height) })
        assertEquals(IntSize(40, 80), source)
        assertEquals(40, decoded.width)
        assertEquals(80, decoded.height)
    }

    @Test fun `changing image reference never carries previous source dimensions`() = runBlocking(awt) {
        val first = png(800, 400)
        val second = png(24, 12)
        var reference by mutableStateOf("first")
        val scene = ImageComposeScene(500, 500) {
            DesktopMessageImageItem(reference, { if (it == "first") first else second },
                null, false, false, {}, {}, {})
        }
        try {
            assertEquals(232f, scene.imageBounds(464f).height, 2f)
            reference = "second"
            assertEquals(12f, scene.imageBounds(24f).height, 2f)
        } finally { scene.close() }
    }

    @Test fun `display size fits width keeps aspect and never enlarges native pixels`() {
        assertEquals(400f, desktopMessageImageSize(IntSize(800, 400), 500.dp, 2f).width.value)
        assertEquals(200f, desktopMessageImageSize(IntSize(800, 400), 500.dp, 2f).height.value)
        assertEquals(200f, desktopMessageImageSize(IntSize(400, 800), 500.dp, 2f).width.value)
        assertEquals(400f, desktopMessageImageSize(IntSize(400, 800), 500.dp, 2f).height.value)
        assertEquals(20f, desktopMessageImageSize(IntSize(40, 20), 500.dp, 2f).width.value)
        assertEquals(10f, desktopMessageImageSize(IntSize(40, 20), 500.dp, 2f).height.value)
        assertEquals(600f, desktopMessageImageSize(IntSize(1000, 3000), 200.dp, 1f).height.value)
    }

    @Test fun `actual message images fit landscape portrait small and narrow scenes and keep preview click`() = runBlocking(awt) {
        for ((width, height, sceneWidth) in listOf(Triple(800, 400, 500), Triple(300, 600, 500),
            Triple(24, 12, 500), Triple(800, 400, 240))) {
            val bytes = png(width, height)
            val reference = "image-$width-$height-$sceneWidth"
            var reads = 0
            var previewed: String? = null
            val scene = ImageComposeScene(sceneWidth, 900) { Column(Modifier.fillMaxWidth()) {
                DesktopMessageImageItem(reference, { reads++; bytes }, null, false, false,
                    { previewed = it }, {}, {})
            } }
            try {
                val expectedWidth = minOf(width.toFloat(), (sceneWidth - 36).toFloat())
                val bounds = scene.imageBounds(expectedWidth)
                assertEquals(expectedWidth, bounds.width, 2f)
                assertEquals(expectedWidth * height / width, bounds.height, 2f)
                assertTrue(bounds.right <= sceneWidth + 1f)
                assertEquals(1, reads)
                assertTrue(scene.nodes().none { it.label("编辑并重新生成") || it.label("删除这张图片") })
                val open = scene.nodes().first { it.label("打开图片预览") && it.config.getOrNull(SemanticsActions.OnClick) != null }
                open.config[SemanticsActions.OnClick].action!!.invoke()
                assertEquals(reference, previewed)
            } finally { scene.close() }
        }
    }

    @Test fun `each image menu follows its own metadata and running permission`() {
        val edits = mutableListOf<String>()
        val deletes = mutableListOf<String>()
        fun items(path: String, meta: GeneratedImageMetadata?, service: Boolean, running: Boolean) =
            desktopMessageImageMenuItems(path, meta, service, running,
                { edits += it.imagePath }, { deletes += it })
        val first = items("first", metadata("first"), true, false)
        val second = items("second", null, true, false)
        assertEquals(listOf("编辑并重新生成", "删除这张图片"), first.map { it.label })
        assertEquals(listOf("删除这张图片"), second.map { it.label })
        first[0].onClick(); first[1].onClick(); second[0].onClick()
        assertEquals(listOf("first"), edits)
        assertEquals(listOf("first", "second"), deletes)
        assertEquals(listOf("删除这张图片"), items("first", metadata("first"), false, false).map { it.label })
        assertEquals(listOf("编辑并重新生成"), items("first", metadata("first"), true, true).map { it.label })
        assertTrue(items("second", null, true, true).isEmpty())
    }

    @Test fun `right click opens the current image context actions`() = runBlocking(awt) {
        val bytes = png(320, 180)
        val reference = "right-click-image"
        var previewed = false
        var deleteRequested: String? = null
        val scene = ImageComposeScene(500, 400) {
            DesktopMessageImageItem(reference, { bytes }, metadata(reference), true, false,
                { previewed = true }, {}, { deleteRequested = it })
        }
        try {
            val bounds = scene.imageBounds(320f)
            val point = Offset(bounds.left + 40f, bounds.top + 40f)
            scene.sendPointerEvent(PointerEventType.Press, point, button = PointerButton.Secondary)
            scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Secondary)
            scene.frames()
            assertFalse(previewed)
            assertTrue(scene.nodes().any { it.label("编辑并重新生成") })
            val delete = scene.nodes().first { it.label("删除这张图片") && it.config.getOrNull(SemanticsActions.OnClick) != null }
            delete.config[SemanticsActions.OnClick].action!!.invoke()
            assertEquals(reference, deleteRequested)
            assertFalse(previewed)
            scene.frames()
            val more = scene.nodes().first { it.label("图片操作") && it.config.getOrNull(SemanticsActions.OnClick) != null }
            more.config[SemanticsActions.OnClick].action!!.invoke()
            scene.frames()
            assertTrue(scene.nodes().any { it.label("编辑并重新生成") })
        } finally { scene.close() }
    }

    @Test fun `decode failure remains visible without image actions taking over`() = runBlocking(awt) {
        val scene = ImageComposeScene(400, 250) {
            DesktopMessageImageItem("unreadable", { throw IllegalStateException("图片无法读取") }, null, false, false,
                {}, {}, {})
        }
        try {
            withTimeout(5000) { while (scene.nodes().none { it.label("图片无法读取") }) scene.frames() }
            assertTrue(scene.nodes().any { it.label("打开图片预览") })
            assertTrue(scene.nodes().none { it.label("编辑并重新生成") || it.label("删除这张图片") })
        } finally { scene.close() }
    }

    @Test fun `confirmation Cancel never invokes image deletion`() = runBlocking(awt) {
        val persisted = listOf("first", "second")
        var cancelled = false
        var confirmed = false
        val scene = ImageComposeScene(520, 220) {
            DesktopMessageImageDeleteConfirmationBody(false, null, { cancelled = true }, { confirmed = true })
        }
        try {
            scene.frames()
            assertTrue(scene.nodes().any { it.label("仅删除所选图片。没有正文和其他图片的消息将一并删除，来源回复保留。") })
            val cancel = scene.nodes().first { it.label("取消") && it.config.getOrNull(SemanticsActions.OnClick) != null }
            cancel.config[SemanticsActions.OnClick].action!!.invoke()
            assertTrue(cancelled)
            assertFalse(confirmed)
            assertEquals(listOf("first", "second"), persisted)
        } finally { scene.close() }
    }
}
