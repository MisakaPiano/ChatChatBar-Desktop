@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopChatImageProcessCardTest {
    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private suspend fun ImageComposeScene.frames() { repeat(8) { render().close(); yield() }; delay(50); render().close() }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun ImageComposeScene.tag(tag: String) = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun ImageComposeScene.text(text: String) = nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.contains(text) } == true }
    private fun ImageComposeScene.click(label: String) {
        val node = nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null &&
            (it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
                it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == label } == true) }
        sendPointerEvent(PointerEventType.Press, node.boundsInRoot.center, button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, node.boundsInRoot.center, button = PointerButton.Primary)
    }
    private fun ImageComposeScene.geometry(width: Int) {
        val card = tag("image-process-card-task").boundsInRoot
        val stop = tag("image-process-stop-task").boundsInRoot
        assertTrue(stop.left >= card.right + 7, "$card / $stop")
        assertTrue(kotlin.math.abs(stop.bottom - card.bottom) <= 1, "$card / $stop")
        assertTrue(stop.right <= width && card.left >= 0)
        assertTrue(stop.width >= 30 && stop.height >= 30)
    }
    private val task = DesktopTaskEntry("task", DesktopTaskKind.NOVELAI, "session", 1,
        targetMessageId = "message", message = "正在设计 Prompt")

    @Test fun `expanded collapsed Stop sibling geometry is safe at wide and narrow widths with real pointer`() = runBlocking(awt) {
        for (width in listOf(1240, 700, 320, 240)) {
            var stopped = 0
            val text = (1..100).joinToString("\n") { "actual streamed research $it" }
            val scene = ImageComposeScene(width, 600) {
                Column(Modifier.fillMaxSize()) {
                    DesktopChatImageProcessCard(task, DesktopImageProcessProgress(designText = text), true,
                        { stopped++ }, {}, {})
                }
            }
            try {
                scene.frames(); scene.geometry(width)
                val scroll = scene.tag("image-process-scroll-task").boundsInRoot
                assertTrue(scroll.height <= 220)
                scene.sendPointerEvent(PointerEventType.Scroll, scroll.center, scrollDelta = Offset(0f, 4f)); scene.frames()
                assertTrue(scene.nodes().any { (it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke() ?: 0f) > 0f },
                    "Long process content actually scrolls inside the bounded card")
                scene.geometry(width)
                scene.click("折叠生图过程"); scene.frames(); scene.geometry(width)
                assertFalse(scene.text("actual streamed research")); assertEquals(0, stopped)
                scene.click("停止此图片任务"); scene.frames(); assertEquals(1, stopped)
                scene.click("展开生图过程"); scene.frames(); scene.geometry(width)
                scene.click("停止此图片任务"); assertEquals(2, stopped)
                val dir = Path.of("H:/ChatChatBar-Desktop/app/desktopApp/build/phase7-b2-gate/screenshots")
                Files.createDirectories(dir)
                scene.render().use { image -> image.encodeToData()!!.use { data -> Files.write(dir.resolve("process-$width.png"), data.bytes) } }
            } finally { scene.close() }
        }
    }

    @Test fun `generation transition retains research then terminal retry dismiss and fresh task reset`() = runBlocking(awt) {
        var entry by mutableStateOf(task)
        var progress by mutableStateOf(DesktopImageProcessProgress(designText = "actual design"))
        var retries = 0; var dismisses = 0
        val scene = ImageComposeScene(700, 600) {
            DesktopChatImageProcessCard(entry, progress, true, {}, { retries++ }, { dismisses++ })
        }
        try {
            scene.frames(); assertTrue(scene.text("actual design"))
            progress = progress.copy(phase = DesktopImageProcessPhase.GENERATION, stage = "图片生成", generationStatus = "Step 12")
            scene.frames(); assertTrue(scene.text("Step 12")); assertTrue(scene.text("actual design"))
            for ((status, expected) in listOf(DesktopTaskStatus.COMPLETED to "已完成", DesktopTaskStatus.FAILED to "失败",
                DesktopTaskStatus.USER_STOPPED to "用户已停止", DesktopTaskStatus.CANCELLED to "已取消")) {
                entry = entry.copy(status = status, canRetry = status != DesktopTaskStatus.COMPLETED)
                scene.frames(); assertTrue(scene.text(expected))
                assertTrue(scene.nodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == "image-process-stop-task" })
                if (entry.canRetry) { scene.click("重试此图片任务") }
            }
            assertEquals(3, retries)
            scene.click("关闭图片任务"); assertEquals(1, dismisses)
            scene.click("折叠生图过程"); scene.frames()
            entry = task.copy(taskId = "fresh"); progress = DesktopImageProcessProgress(designText = "fresh real output")
            scene.frames(); assertTrue(scene.text("fresh real output")); assertFalse(scene.text("actual design"))
        } finally { scene.close() }
    }

    @Test fun `task association uses kind session message and ignores selected session coincidence`() {
        val message = com.example.chatbar.data.local.entity.ChatMessage.create("session", com.example.chatbar.data.local.entity.MessageRole.ASSISTANT, "source").copy(id = "message")
        val entries = listOf(task, task.copy(taskId = "other-session", sessionId = "other"),
            task.copy(taskId = "other-message", targetMessageId = "other"), task.copy(taskId = "chat", kind = DesktopTaskKind.REAL_CHAT))
        assertEquals(listOf(task), desktopImageTasksForMessage(entries, message))
    }
}
