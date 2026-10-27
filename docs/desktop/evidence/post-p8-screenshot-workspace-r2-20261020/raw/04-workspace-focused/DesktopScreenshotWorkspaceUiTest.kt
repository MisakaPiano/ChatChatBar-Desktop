@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.skia.EncodedImageFormat
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
                DesktopChatScreenshotHost(c, c.state.value, workspaceWindow = { _, body -> body() }) {
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

    @Test fun `compact and wide workspaces keep bars reachable with preview and both scrollbars`(): Unit = runBlocking(edt) {
        val f = DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize(); val c = f.c.primaryChatController; c.refresh(); c.selectSession("session")
            for (width in listOf(360, 1100)) {
                val scene = ImageComposeScene(width, 700) {
                    DesktopChatScreenshotHost(c, c.state.value, workspaceWindow = { _, body -> body() }) {
                        val start = LocalDesktopChatScreenshot.current
                        StudioAction("打开截图") { start!!(f.source, null) }
                    }
                }
                fun nodes(): List<SemanticsNode> {
                    fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
                    return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                }
                fun action(label: String) = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true && it.config.contains(SemanticsActions.OnClick) }
                suspend fun until(check: () -> Boolean) = withTimeout(10000) {
                    while (true) { scene.render(System.nanoTime()).close(); yield(); if (check()) break; delay(10) }
                }
                fun click(label: String) { action(label).config[SemanticsActions.OnClick].action!!() }
                try {
                    until { nodes().isNotEmpty() }; click("打开截图")
                    until { nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("全选") == true } }
                    click("全不选"); scene.render(System.nanoTime()).close()
                    assertTrue(action("预览 PNG").config.contains(SemanticsProperties.Disabled))
                    click("全选"); scene.render(System.nanoTime()).close()
                    click("全部折叠"); scene.render(System.nanoTime()).close()
                    click("反选"); scene.render(System.nanoTime()).close()
                    assertTrue(action("预览 PNG").config.contains(SemanticsProperties.Disabled))
                    click("反选"); scene.render(System.nanoTime()).close()
                    click("预览 PNG")
                    until { nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("预览纵向滚动条") == true } }
                    assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("预览横向滚动条") == true })
                    click("100%"); scene.render(System.nanoTime()).close(); click("放大"); scene.render(System.nanoTime()).close()
                    for (label in listOf("返回聊天", "导出 PNG", "关闭")) {
                        val n = action(label)
                        assertTrue(n.boundsInRoot.top >= 0 && n.boundsInRoot.bottom <= 700)
                        assertTrue(n.boundsInRoot.left >= 0 && n.boundsInRoot.right <= width)
                    }
                    val out = Path.of("build/post-p8-screenshot-workspace-r2-20261020/ui-previews")
                    Files.createDirectories(out)
                    scene.render(System.nanoTime()).use { img -> img.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(out.resolve("workspace-$width.png"), it.bytes) } }
                    assertTrue(f.requests.isEmpty())
                } finally { scene.close() }
            }
        } finally { f.close() }
    }

    @Test fun `internal return Escape and native shell close callback leave host alive`(): Unit = runBlocking(edt) {
        val f = DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize(); val c = f.c.primaryChatController; c.refresh(); c.selectSession("session")
            var childClose: (() -> Unit)? = null
            var appCloseRequests = 0
            val scene = ImageComposeScene(1000, 700) {
                DesktopChatScreenshotHost(c, c.state.value, workspaceWindow = { close, body -> childClose = close; body() }) {
                    val start = LocalDesktopChatScreenshot.current
                    StudioAction("打开截图") { start!!(f.source, null) }
                    StudioAction("模拟主窗口关闭") { appCloseRequests++ }
                }
            }
            fun nodes(): List<SemanticsNode> {
                fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
                return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
            }
            fun actions(label: String) = nodes().filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true && it.config.contains(SemanticsActions.OnClick) }
            fun click(label: String) { actions(label).single().config[SemanticsActions.OnClick].action!!() }
            suspend fun until(check: () -> Boolean) = withTimeout(7000) {
                while (true) { scene.render(System.nanoTime()).close(); yield(); if (check()) break; delay(10) }
            }
            try {
                until { actions("打开截图").isNotEmpty() }
                for (source in listOf("关闭", "返回聊天", "Escape", "native-shell-callback")) {
                    click("打开截图"); until { actions("关闭").isNotEmpty() }
                    when (source) {
                        "Escape" -> { scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyDown)); scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyUp)) }
                        "native-shell-callback" -> checkNotNull(childClose).invoke()
                        else -> click(source)
                    }
                    until { actions("关闭").isEmpty() }
                    assertEquals(0, appCloseRequests); assertTrue(actions("打开截图").isNotEmpty())
                }
                click("模拟主窗口关闭"); assertEquals(1, appCloseRequests); assertTrue(f.requests.isEmpty())
            } finally { scene.close() }
        } finally { f.close() }
    }
}
