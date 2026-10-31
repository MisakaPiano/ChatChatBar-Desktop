@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ProviderRequestEvidence
import kotlinx.coroutines.*
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopChatDebugPanelTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.label(s: String) = config.getOrNull(SemanticsProperties.Text)?.any { it.text == s } == true ||
        config.getOrNull(SemanticsProperties.ContentDescription)?.contains(s) == true
    private suspend fun frames(scene: ImageComposeScene) { repeat(4) { scene.render(System.nanoTime()).close(); yield(); delay(10) } }
    private suspend fun tap(scene: ImageComposeScene, label: String) {
        val point = nodes(scene).first { it.label(label) }.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press, point, button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
        frames(scene)
    }

    @Test fun `console filters session and clears only after confirmation in compact and wide layouts`() = runBlocking(edt) {
        for (width in listOf(480, 1120)) {
            val owner = DesktopTransportDiagnosticsOwner()
            val model = ModelConfig("fake", "Local fixture", "https://synthetic.invalid/v1", "fake-key", "model", createdAt = 1)
            owner.begin("other", "other", model)
            owner.begin("visible", "current", model).onRequest(ProviderRequestEvidence("https://synthetic.invalid/v1/chat/completions",
                """{"messages":[{"role":"user","content":"synthetic-input"}]}"""))
            val scene = ImageComposeScene(width, 760, coroutineContext = edt) {
                DesktopChatDebugPanel(owner, "current", onClose = {})
            }
            try {
                frames(scene)
                assertTrue(nodes(scene).any { it.label("聊天调试日志") })
                assertFalse(nodes(scene).any { it.label("确认清空日志") })
                assertEquals(2, owner.entries.value.size)
                tap(scene, "清空本会话日志")
                assertEquals(2, owner.entries.value.size)
                tap(scene, "取消清空")
                assertEquals(2, owner.entries.value.size)
                tap(scene, "清空本会话日志")
                tap(scene, "确认清空日志")
                assertEquals(listOf("other"), owner.entries.value.map { it.taskId })
                assertTrue(nodes(scene).any { it.label("本会话暂无调试日志。新的对话请求会在这里出现。") })
            } finally { scene.close() }
        }
    }

    @Test fun `system view is derived from recorded provider body and refuses an incomplete body`() {
        val entry = DesktopTransportDiagnosticEntry("task", "session", "Model", "m", 1,
            serializedRequestBody = """{"messages":[{"role":"system","content":"actual-system"},{"role":"assistant","content":"adapted-later-system"}]}""")
        assertEquals("actual-system", desktopDiagnosticSystemMessages(entry))
        assertNull(desktopDiagnosticSystemMessages(entry.copy(requestTruncated = true)))
        assertNull(desktopDiagnosticSystemMessages(entry.copy(serializedRequestBody = "not-json")))
    }
}
