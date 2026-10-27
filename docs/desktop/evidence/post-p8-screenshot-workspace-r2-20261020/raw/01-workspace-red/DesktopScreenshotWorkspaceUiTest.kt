@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.*
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopScreenshotWorkspaceUiTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    @Test fun `workspace exposes bulk selection and fixed export actions`(): Unit = runBlocking(edt) {
        val f = DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize(); val c = f.c.primaryChatController; c.refresh(); c.selectSession("session")
            val scene = ImageComposeScene(1000, 760) {
                DesktopChatScreenshotHost(c, c.state.value) {
                    val start = LocalDesktopChatScreenshot.current
                    StudioAction("打开截图") { start!!(f.source, null) }
                }
            }
            fun nodes(): List<SemanticsNode> {
                fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
                return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
            }
            fun actions(label: String) = nodes().filter {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true && it.config.contains(SemanticsActions.OnClick)
            }
            suspend fun until(check: () -> Boolean) = withTimeout(7000) {
                while (true) { scene.render(System.nanoTime()).close(); yield(); if (check()) break; delay(10) }
            }
            try {
                until { actions("打开截图").isNotEmpty() }
                actions("打开截图").single().config[SemanticsActions.OnClick].action!!()
                until { actions("关闭").isNotEmpty() }
                assertTrue(actions("全部展开").isNotEmpty(), "workspace must expose bulk expand before preview")
                assertTrue(actions("导出 PNG").isNotEmpty())
                assertTrue(f.requests.isEmpty())
            } finally { scene.close() }
        } finally { f.close() }
    }
}
