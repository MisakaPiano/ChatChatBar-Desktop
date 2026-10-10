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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.GeneratedImageMetadata
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import java.awt.image.BufferedImage
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
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
    private suspend fun ImageComposeScene.rightClick(point: Offset) {
        sendPointerEvent(PointerEventType.Press, point, button = PointerButton.Secondary)
        sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Secondary)
        frames()
    }
    private fun ImageComposeScene.imagePoint(): Offset = nodes().first { it.label("打开图片预览") }.boundsInRoot.let {
        Offset(it.left + it.width / 2f, it.top + it.height / 2f)
    }
    private fun ImageComposeScene.headerPoint(label: String): Offset = nodes().first {
        it.label(label) && it.boundsInRoot.height > 0f
    }.boundsInRoot.let { Offset(it.left + it.width / 2f, it.top + it.height / 2f) }
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
    private fun coloredPng(width: Int, height: Int, color: Color): ByteArray {
        val bitmap = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val canvas = bitmap.createGraphics()
        try {
            canvas.color = color
            canvas.fillRect(0, 0, width, height)
            canvas.color = Color.WHITE
            canvas.fillRect(width / 5, height / 5, width / 3, height / 3)
        } finally { canvas.dispose() }
        return DesktopImageEditing.png(bitmap)
    }
    private fun ImageComposeScene.shot(name: String) = render().use { image ->
        val output = Path.of("build/phase7-b1-r2-6-evidence/$name.png")
        Files.createDirectories(output.parent)
        image.encodeToData()?.use { Files.write(output, it.bytes) }
    }
    private fun metadata(path: String) = GeneratedImageMetadata(path, "scene", negativePrompt = "", sizePreset = "fixture", width = 16, height = 16)

    @Test fun `source dimensions survive preview sampling at high display density`() = runBlocking(awt) {
        val bytes = png(2048, 1536)
        assertEquals(1024, DesktopImageEditing.decode(bytes, longestSide = 1600).width)
        var source = IntSize.Zero
        val display = DesktopImageEditing.decode(bytes, longestSide = 1600,
            onSourceDimensions = { width, height -> source = IntSize(width, height) },
            displayTargetForSource = { _, _ -> 560 to 420 })
        assertEquals(IntSize(2048, 1536), source)
        assertTrue(display.width >= 560)
        assertTrue(display.width < 1024)
        assertEquals(280f, desktopMessageImageSize(source, 664.dp, 2f).width.value)
        assertEquals(210f, desktopMessageImageSize(source, 664.dp, 2f).height.value)
        var reads = 0
        val scene = ImageComposeScene(1400, 1100) {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                DesktopMessageImageItem("large", { reads++; bytes }, null, false, false, {}, {}, {})
            }
        }
        try {
            val bounds = scene.imageBounds(560f)
            assertEquals(420f, bounds.height, 2f)
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
            assertEquals(140f, scene.imageBounds(280f).height, 2f)
            reference = "second"
            assertEquals(12f, scene.imageBounds(24f).height, 2f)
        } finally { scene.close() }
    }

    @Test fun `display size observes both 280dp caps aspect density and native pixels`() {
        assertEquals(280f, desktopMessageImageSize(IntSize(800, 400), 500.dp, 2f).width.value)
        assertEquals(140f, desktopMessageImageSize(IntSize(800, 400), 500.dp, 2f).height.value)
        assertEquals(140f, desktopMessageImageSize(IntSize(400, 800), 500.dp, 2f).width.value)
        assertEquals(280f, desktopMessageImageSize(IntSize(400, 800), 500.dp, 2f).height.value)
        assertEquals(20f, desktopMessageImageSize(IntSize(40, 20), 500.dp, 2f).width.value)
        assertEquals(10f, desktopMessageImageSize(IntSize(40, 20), 500.dp, 2f).height.value)
        assertEquals(280f, desktopMessageImageSize(IntSize(1000, 3000), 200.dp, 1f).height.value)
        assertEquals(280f / 3f, desktopMessageImageSize(IntSize(1000, 3000), 200.dp, 1f).width.value, 0.01f)
        assertEquals(140f, desktopMessageImageSize(IntSize(800, 400), 140.dp, 1f).width.value)
        assertEquals(70f, desktopMessageImageSize(IntSize(800, 400), 140.dp, 1f).height.value)
        assertEquals(280f, desktopMessageImageSize(IntSize(800, 400), 500.dp, 1f).width.value)
        assertEquals(140f, desktopMessageImageSize(IntSize(800, 400), 500.dp, 1f).height.value)
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
                val expectedWidth = minOf(width.toFloat(), 280f, (sceneWidth - 36).toFloat(),
                    height.toFloat().let { 280f * width / it })
                val bounds = scene.imageBounds(expectedWidth)
                assertEquals(expectedWidth, bounds.width, 2f)
                assertEquals(expectedWidth * height / width, bounds.height, 2f)
                assertTrue(bounds.right <= sceneWidth + 1f)
                val more = scene.nodes().first { it.label("图片操作") &&
                    it.config.getOrNull(SemanticsActions.OnClick) != null }.boundsInRoot
                assertTrue(more.left >= bounds.right + 7f)
                assertEquals(bounds.bottom, more.bottom, 2f)
                assertTrue(more.right <= sceneWidth + 1f)
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

    @Test fun `image menu never acquires whole-message actions`() {
        val imageDeletes = mutableListOf<String>()
        val images = desktopMessageImageMenuItems("image-2", null, false, false, {}, imageDeletes::add)
        assertEquals(listOf("删除这张图片"), images.map { it.label })
        images[0].onClick()
        assertEquals(listOf("image-2"), imageDeletes)
        assertTrue(desktopMessageImageMenuItems("image-2", null, false, true, {}, imageDeletes::add).isEmpty())
    }

    @Test fun `one image More stays beside bottom edge while right click stays image scoped`() = runBlocking(awt) {
        val bytes = png(300, 600)
        var imageDelete: String? = null
        val scene = ImageComposeScene(400, 500) {
            DesktopMessageImageItem("portrait", { bytes }, null, false, false, {}, {}, { imageDelete = it })
        }
        try {
            val image = scene.imageBounds(140f)
            val more = scene.nodes().filter { it.label("图片操作") &&
                it.config.getOrNull(SemanticsActions.OnClick) != null }
            assertEquals(1, more.size)
            assertTrue(more.single().boundsInRoot.left >= image.right + 7f)
            assertEquals(image.bottom, more.single().boundsInRoot.bottom, 2f)
            assertTrue(scene.nodes().none { it.label("更多消息操作") })
            val point = Offset(image.left + 20f, image.top + 20f)
            scene.rightClick(point)
            assertTrue(scene.nodes().any { it.label("删除这张图片") })
            assertTrue(scene.nodes().none { it.label("复制整条") || it.label("编辑整条") || it.label("删除整条") })
            val delete = scene.nodes().first { it.label("删除这张图片") &&
                it.config.getOrNull(SemanticsActions.OnClick) != null }
            delete.config[SemanticsActions.OnClick].action!!.invoke()
            assertEquals("portrait", imageDelete)
        } finally { scene.close() }
    }

    @Test fun `image More and right click expose the same metadata actions`() = runBlocking(awt) {
        val bytes = png(300, 600)
        var imageDelete: String? = null
        var edited: String? = null
        val scene = ImageComposeScene(400, 500) {
            DesktopMessageImageItem("portrait", { bytes }, metadata("portrait"), true, false, {},
                { edited = it.imagePath }, { imageDelete = it })
        }
        try {
            scene.imageBounds(140f)
            val more = scene.nodes().first { it.label("图片操作") &&
                it.config.getOrNull(SemanticsActions.OnClick) != null }
            scene.rightClick(scene.imagePoint())
            assertTrue(scene.nodes().any { it.label("删除这张图片") })
            assertTrue(scene.nodes().any { it.label("编辑并重新生成") })
            assertTrue(scene.nodes().none { it.label("复制整条") || it.label("编辑整条") || it.label("删除整条") })
            val edit = scene.nodes().first { it.label("编辑并重新生成") &&
                it.config.getOrNull(SemanticsActions.OnClick) != null }
            edit.config[SemanticsActions.OnClick].action!!.invoke()
            assertEquals("portrait", edited)
            assertNull(imageDelete)
            scene.frames()
            more.config[SemanticsActions.OnClick].action!!.invoke(); scene.frames()
            assertTrue(scene.nodes().any { it.label("删除这张图片") })
            assertTrue(scene.nodes().any { it.label("编辑并重新生成") })
            assertTrue(scene.nodes().none { it.label("复制整条") || it.label("编辑整条") || it.label("删除整条") })
        } finally { scene.close() }
    }

    @Test fun `image-only bubble has distinct pointer menus while mixed and omitted keep toolbar`() = runBlocking(awt) {
        val fixture = DesktopAssistantImageActionTest.Fixture()
        try {
            fixture.initialize()
            val pure = ChatMessage.create("session", MessageRole.USER, "").copy(images = listOf("missing-image"))
            val mixed = ChatMessage.create("session", MessageRole.USER, "visible text").copy(images = listOf("missing-image"))
            val multiple = ChatMessage.create("session", MessageRole.USER, "").copy(images = listOf("missing-1", "missing-2"))
            val omitted = ChatMessage.create("session", MessageRole.USER, "").copy(
                images = listOf(com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX + "missing"))
            assertTrue(desktopImageOnlyMessage(pure, desktopPresentMessage(pure, null, null, false)))
            assertFalse(desktopImageOnlyMessage(mixed, desktopPresentMessage(mixed, null, null, false)))
            assertFalse(desktopImageOnlyMessage(omitted, desktopPresentMessage(omitted, null, null, false)))
            var current by mutableStateOf(pure)
            val actions = listOf(DesktopMessageAction.COPY, DesktopMessageAction.EDIT, DesktopMessageAction.DELETE)
            val messageCalls = mutableListOf<DesktopMessageAction>()
            val scene = ImageComposeScene(500, 500) {
                PrimaryMessageBubble(current, DesktopPrimaryChatState(messages = listOf(current)),
                    fixture.c.primaryChatController, DesktopSafeClipboard(LocalClipboard.current), actions,
                    onAction = messageCalls::add)
            }
            try {
                scene.frames()
                assertTrue(scene.nodes().none { it.label("更多消息操作") })
                assertEquals(1, scene.nodes().count { it.label("图片操作") &&
                    it.config.getOrNull(SemanticsActions.OnClick) != null })
                val image = scene.nodes().first { it.label("打开图片预览") }.boundsInRoot
                val header = scene.nodes().first { it.label("你") }.boundsInRoot
                assertTrue(header.bottom <= image.top)
                scene.rightClick(Offset(image.left + 10f, image.top + 10f))
                assertTrue(scene.nodes().any { it.label("删除这张图片") })
                assertTrue(scene.nodes().none { it.label("复制整条") || it.label("编辑整条") || it.label("删除整条") })
                // The focusable native context popup dismisses on the first outside pointer event.
                scene.sendPointerEvent(PointerEventType.Press, Offset(header.left + 2f, header.top + 2f))
                scene.sendPointerEvent(PointerEventType.Release, Offset(header.left + 2f, header.top + 2f))
                scene.frames()
                scene.rightClick(Offset(header.left + header.width / 2f, header.top + header.height / 2f))
                assertTrue(scene.nodes().any { it.label("复制整条") && it.config.getOrNull(SemanticsActions.OnClick) != null })
                assertTrue(scene.nodes().none { it.label("删除这张图片") })
                val deleteMessage = scene.nodes().first { it.label("删除整条") &&
                    it.config.getOrNull(SemanticsActions.OnClick) != null }
                deleteMessage.config[SemanticsActions.OnClick].action!!.invoke()
                assertEquals(listOf(DesktopMessageAction.DELETE), messageCalls)
                current = mixed; scene.frames()
                assertEquals(1, scene.nodes().count { it.label("图片操作") &&
                    it.config.getOrNull(SemanticsActions.OnClick) != null })
                val mixedImage = scene.nodes().first { it.label("打开图片预览") }.boundsInRoot
                val toolbarMore = scene.nodes().first { it.label("更多消息操作") &&
                    it.config.getOrNull(SemanticsActions.OnClick) != null }.boundsInRoot
                assertTrue(toolbarMore.top >= mixedImage.bottom)
                assertTrue(scene.nodes().any { it.label("复制整条") && it.config.getOrNull(SemanticsActions.OnClick) != null })
                scene.rightClick(Offset(mixedImage.left + 8f, mixedImage.top + 8f))
                assertTrue(scene.nodes().any { it.label("删除这张图片") })
                assertTrue(scene.nodes().none { it.label("删除整条") })
                current = multiple; scene.frames()
                assertTrue(scene.nodes().none { it.label("更多消息操作") })
                assertEquals(2, scene.nodes().count { it.label("图片操作") &&
                    it.config.getOrNull(SemanticsActions.OnClick) != null })
                assertEquals(2, scene.nodes().count { it.label("打开图片预览") &&
                    it.config.getOrNull(SemanticsActions.OnClick) != null })
                current = omitted; scene.frames()
                assertTrue(scene.nodes().none { it.label("图片操作") })
                assertTrue(scene.nodes().any { it.label("复制整条") &&
                    it.config.getOrNull(SemanticsActions.OnClick) != null })
            } finally { scene.close() }
        } finally { fixture.close() }
    }

    @Test fun `segmented image-only never synthesizes a speaker header for message actions`() = runBlocking(awt) {
        val fixture = DesktopAssistantImageActionTest.Fixture()
        try {
            fixture.initialize()
            val message = ChatMessage.create("session", MessageRole.ASSISTANT, "").copy(images = listOf("missing-image"))
            assertFalse(desktopPresentMessage(message, null, null, true).showWholeMessageHeader)
            var actions by mutableStateOf(listOf(DesktopMessageAction.COPY, DesktopMessageAction.DELETE))
            val scene = ImageComposeScene(500, 420) {
                PrimaryMessageBubble(message, DesktopPrimaryChatState(messages = listOf(message),
                    assistantSegmentedBubblesEnabled = true), fixture.c.primaryChatController,
                    DesktopSafeClipboard(LocalClipboard.current), actions)
            }
            try {
                scene.frames()
                assertTrue(scene.nodes().none { it.label("更多消息操作") || it.label("助手") })
                assertEquals(1, scene.nodes().count { it.label("图片操作") &&
                    it.config.getOrNull(SemanticsActions.OnClick) != null })
                scene.rightClick(scene.imagePoint())
                assertTrue(scene.nodes().any { it.label("删除这张图片") })
                assertTrue(scene.nodes().none { it.label("复制整条") || it.label("删除整条") })
                actions = emptyList(); scene.frames()
                assertTrue(scene.nodes().none { it.label("更多消息操作") || it.label("助手") ||
                    it.label("删除整条") || it.label("复制整条") })
                assertTrue(scene.nodes().any { it.label("图片操作") })
            } finally { scene.close() }
        } finally { fixture.close() }
    }

    @Test fun `consecutive segmented image-only messages have no synthetic headers in wide and narrow scenes`() = runBlocking(awt) {
        val fixture = DesktopAssistantImageActionTest.Fixture()
        try {
            fixture.initialize()
            val imageRoot = fixture.design.root.resolve("data/images")
            Files.createDirectories(imageRoot)
            Files.write(imageRoot.resolve("r2-6-blue.png"), coloredPng(480, 270, Color(0x396CC6)))
            Files.write(imageRoot.resolve("r2-6-green.png"), coloredPng(300, 420, Color(0x5D9E72)))
            val messages = listOf("images/r2-6-blue.png", "images/r2-6-green.png").map { reference ->
                ChatMessage.create("session", MessageRole.ASSISTANT, "").copy(images = listOf(reference))
            }
            val state = DesktopPrimaryChatState(messages = messages, assistantSegmentedBubblesEnabled = true)
            val actions = listOf(DesktopMessageAction.COPY, DesktopMessageAction.DELETE)
            for ((name, width) in listOf("wide" to 820, "narrow" to 320)) {
                val scene = ImageComposeScene(width, 720) { Column(Modifier.fillMaxWidth()) {
                    messages.forEach { message -> PrimaryMessageBubble(message, state, fixture.c.primaryChatController,
                        DesktopSafeClipboard(LocalClipboard.current), actions) }
                } }
                try {
                    withTimeout(10_000) {
                        var bounds: List<Rect>
                        do {
                            scene.frames()
                            bounds = scene.nodes().filter { it.label("打开图片预览") }.map { it.boundsInRoot }
                        } while (bounds.size != 2 ||
                            kotlin.math.abs(bounds[0].width - minOf(width - 36, 280)) > 2f ||
                            kotlin.math.abs(bounds[1].height - 280f) > 2f)
                    }
                    scene.frames()
                    assertTrue(scene.nodes().none { it.label("更多消息操作") ||
                        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "助手" } == true })
                    val images = scene.nodes().filter { it.label("打开图片预览") }.map { it.boundsInRoot }
                    val more = scene.nodes().filter { it.label("图片操作") &&
                        it.config.getOrNull(SemanticsActions.OnClick) != null }.map { it.boundsInRoot }
                    assertEquals(2, images.size)
                    assertEquals(2, more.size)
                    images.zip(more).forEach { (image, button) ->
                        assertTrue(button.left >= image.right + 7f)
                        assertEquals(image.bottom, button.bottom, 2f)
                        assertTrue(button.right <= width + 1f)
                    }
                    scene.shot("consecutive-image-only-$name")
                } finally { scene.close() }
            }
        } finally { fixture.close() }
    }

    @Test fun `running image task tightens both pointer menus without a synthetic message button`() = runBlocking(awt) {
        val fixture = DesktopAssistantImageActionTest.Fixture()
        try {
            fixture.initialize()
            val message = ChatMessage.create("session", MessageRole.USER, "").copy(images = listOf("missing-image"))
            val actions = desktopMessageActions(listOf(message), message, null)
            assertTrue(DesktopMessageAction.DELETE in actions)
            val taskId = fixture.c.taskRuntime.launchNovelAi("local fixture", "session", message.id) { awaitCancellation() }
            val scene = ImageComposeScene(500, 420) {
                PrimaryMessageBubble(message, DesktopPrimaryChatState(messages = listOf(message)),
                    fixture.c.primaryChatController, DesktopSafeClipboard(LocalClipboard.current), actions)
            }
            try {
                scene.frames()
                assertTrue(scene.nodes().none { it.label("图片操作") || it.label("更多消息操作") })
                scene.rightClick(scene.imagePoint())
                assertTrue(scene.nodes().none { it.label("删除这张图片") })
                scene.rightClick(scene.headerPoint("你"))
                assertTrue(scene.nodes().any { it.label("复制整条") })
                assertTrue(scene.nodes().none { it.label("编辑整条") || it.label("删除整条") || it.label("删除这张图片") })
            } finally { scene.close(); fixture.c.taskRuntime.requestUserStop(taskId) }
        } finally { fixture.close() }
    }

    @Test fun `multiple images each retain image-only More and right-click targets`() = runBlocking(awt) {
        val bytes = png(32, 32)
        val deleted = mutableListOf<String>()
        val scene = ImageComposeScene(400, 300) {
            Column { listOf("first", "second").forEach { reference ->
                DesktopMessageImageItem(reference, { bytes }, null, false, false, {}, {}, deleted::add)
            } }
        }
        try {
            scene.frames()
            val buttons = scene.nodes().filter { it.label("图片操作") &&
                it.config.getOrNull(SemanticsActions.OnClick) != null }
            assertEquals(2, buttons.size)
            val images = scene.nodes().filter { it.label("打开图片预览") }
            assertEquals(2, images.size)
            val second = images[1].boundsInRoot
            scene.rightClick(Offset(second.left + 4f, second.top + 4f))
            val delete = scene.nodes().first { it.label("删除这张图片") &&
                it.config.getOrNull(SemanticsActions.OnClick) != null }
            delete.config[SemanticsActions.OnClick].action!!.invoke()
            assertEquals(listOf("second"), deleted)
            scene.frames()
            buttons[0].config[SemanticsActions.OnClick].action!!.invoke(); scene.frames()
            val firstDelete = scene.nodes().first { it.label("删除这张图片") &&
                it.config.getOrNull(SemanticsActions.OnClick) != null }
            firstDelete.config[SemanticsActions.OnClick].action!!.invoke()
            assertEquals(listOf("second", "first"), deleted)
        } finally { scene.close() }
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
            val bounds = scene.imageBounds(280f)
            val point = Offset(bounds.left + 40f, bounds.top + 40f)
            scene.sendPointerEvent(PointerEventType.Press, point, button = PointerButton.Secondary)
            scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Secondary)
            scene.frames()
            assertFalse(previewed)
            val imageLabels = listOf("编辑并重新生成", "删除这张图片")
            val rightClickLabels = imageLabels.filter { label -> scene.nodes().any { it.label(label) } }
            assertEquals(imageLabels, rightClickLabels)
            assertTrue(scene.nodes().none { it.label("复制整条") || it.label("编辑整条") || it.label("删除整条") })
            val delete = scene.nodes().first { it.label("删除这张图片") && it.config.getOrNull(SemanticsActions.OnClick) != null }
            delete.config[SemanticsActions.OnClick].action!!.invoke()
            assertEquals(reference, deleteRequested)
            assertFalse(previewed)
            scene.frames()
            assertEquals(1, scene.nodes().count { it.label("图片操作") &&
                it.config.getOrNull(SemanticsActions.OnClick) != null })
            assertTrue(scene.nodes().none { it.label("更多消息操作") })
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
