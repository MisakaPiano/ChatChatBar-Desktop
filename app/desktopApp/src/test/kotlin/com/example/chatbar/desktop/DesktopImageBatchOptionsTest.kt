@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopImageBatchOptionsTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    @Test fun `narrow and wide chat confirmation keeps submit visible and changing plan invalidates consent`() = runBlocking(edt) {
        for (width in listOf(380, 900)) {
            var config by mutableStateOf(ImageBatchConfiguration(4, true, 10))
            var submission: ImageBatchRunPlan? = null
            val scene = ImageComposeScene(width, 700, coroutineContext = edt) {
                var confirmed by remember(config) { mutableStateOf(false) }
                val plan = if (confirmed && config.validationError() == null) config.freeze(ImageBatchOrigin.CHAT_MANUAL) else null
                DesktopChatImageRequirementsForm("synthetic scene", "", {}, {}, false, "", {},
                    canGenerate = plan != null, options = {
                        DesktopImageBatchOptions(config, ImageBatchOrigin.CHAT_MANUAL, { config = it }, confirmed, { confirmed = it }); Unit
                    }) { submission = plan }
            }
            try {
                scene.frames()
                assertTrue(scene.action("生成").config.contains(SemanticsProperties.Disabled))
                val button = scene.action("生成").boundsInRoot
                assertTrue(button.width > 0 && button.left >= 0 && button.right <= width && button.bottom <= 700)
                assertTrue(scene.nodes().any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { "4 + 4 + 2" in it.text } == true })
                scene.capture("batch-options-top-$width.png")
                scene.scroll(10000f)
                val checkbox = scene.action("我确认本次请求计划和估算预算")
                assertTrue(checkbox.boundsInRoot.height > 0 && checkbox.boundsInRoot.top >= 0 && checkbox.boundsInRoot.bottom <= button.top)
                assertTrue(checkbox.config[SemanticsActions.OnClick].action!!.invoke()); scene.frames()
                assertFalse(scene.action("生成").config.contains(SemanticsProperties.Disabled))
                scene.capture("batch-options-confirm-$width.png")
                scene.action("生成").config[SemanticsActions.OnClick].action!!.invoke()
                assertEquals(listOf(4, 4, 2), assertNotNull(submission).batches)
                scene.scroll(-10000f)
                scene.action("1").config[SemanticsActions.OnClick].action!!.invoke(); scene.frames()
                assertTrue(scene.action("生成").config.contains(SemanticsProperties.Disabled))
                assertEquals(1, config.requestCount)
            } finally { scene.close() }
        }
    }

    @Test fun `reversed wait range keeps actual confirmation submit disabled`() = runBlocking(edt) {
        var config by mutableStateOf(ImageBatchConfiguration(4, true, 10,
            ImageBatchPacing(ImageBatchInterval.RANGE, minSeconds = 40, maxSeconds = 10)))
        var confirmed by mutableStateOf(true)
        var calls = 0
        val scene = ImageComposeScene(480, 650, coroutineContext = edt) {
            val plan = if (confirmed && config.validationError() == null) config.freeze(ImageBatchOrigin.CHAT_MANUAL) else null
            DesktopChatImageRequirementsForm("synthetic", "", {}, {}, false, "", {}, canGenerate = plan != null,
                options = { DesktopImageBatchOptions(config, ImageBatchOrigin.CHAT_MANUAL, { config = it }, confirmed, { confirmed = it }); Unit }) { calls++ }
        }
        try {
            scene.frames(); assertTrue(scene.action("生成").config.contains(SemanticsProperties.Disabled))
            val input = scene.nodes().first { node -> node.config.contains(SemanticsActions.SetText) && node.matches("随机等待最小秒数") }
            input.config[SemanticsActions.SetText].action!!.invoke(androidx.compose.ui.text.AnnotatedString("5")); scene.frames()
            assertNull(config.validationError())
            assertEquals(0, calls)
        } finally { scene.close() }
    }

    private suspend fun ImageComposeScene.frames() { repeat(12) { render().close(); delay(20) } }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.matches(text: String): Boolean =
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
            config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true || children.any { it.matches(text) }
    private fun ImageComposeScene.action(text: String) = nodes().first { it.matches(text) && it.config.contains(SemanticsActions.OnClick) }
    private suspend fun ImageComposeScene.scroll(delta: Float) {
        nodes().first { it.config.contains(SemanticsActions.ScrollBy) && it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
            .config[SemanticsActions.ScrollBy].action!!.invoke(0f, delta)
        frames()
    }
    private fun ImageComposeScene.capture(name: String) {
        System.getenv("CCB_IMAGE_BATCH_REVIEW_DIR")?.let { directory ->
            val file = Path.of(directory).resolve(name); Files.createDirectories(file.parent)
            render().use { it.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { data -> Files.write(file, data.bytes) } }
        }
    }
}
