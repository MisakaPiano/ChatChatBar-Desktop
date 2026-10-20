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
                val height = if (width == 360) 320 else 700
                val scene = ImageComposeScene(width, height) {
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
                    click("适应宽度"); scene.render(System.nanoTime()).close()
                    // The test fixture has no native picker: failure feedback must remain
                    // reachable and retryable without moving the fixed footer offscreen.
                    click("导出 PNG")
                    until { nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("关闭提示") == true } }
                    for (label in listOf("返回聊天", "导出 PNG", "关闭")) {
                        val n = action(label)
                        assertTrue(n.boundsInRoot.top >= 0 && n.boundsInRoot.bottom <= height)
                        assertTrue(n.boundsInRoot.left >= 0 && n.boundsInRoot.right <= width)
                    }
                    assertFalse(action("导出 PNG").config.contains(SemanticsProperties.Disabled))
                    click("关闭提示");scene.render(System.nanoTime()).close()
                    val out = Path.of("build/post-p8-screenshot-r2-boundary-20261021/ui-previews")
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

    @Test fun `very long PNG at high zoom renders only a bounded viewport and saves original pixels`(): Unit = runBlocking(edt) {
        val bytes = DesktopImageEditing.png(java.awt.image.BufferedImage(320,16000,java.awt.image.BufferedImage.TYPE_INT_RGB))
        val png = DesktopScreenshotPng(bytes,320,16000)
        val viewport = DesktopScreenshotViewport(320,16000)
        viewport.resize(340,440); viewport.manualZoom(8.0); viewport.scrollTo(900.0,80000.0)
        val scene = ImageComposeScene(360,500) { DesktopScreenshotPreview(png,viewport,androidx.compose.ui.Modifier) }
        val root = Files.createTempDirectory("ccb-shot-long-r2-");val target = root.resolve("long.png")
        try {
            repeat(4) { scene.render(System.nanoTime()).close(); yield() }
            assertEquals(8.0,viewport.zoom)
            viewport.adapter(true).scrollTo(1000.0);viewport.adapter(false).scrollTo(90000.0)
            val output = Path.of("build/post-p8-screenshot-r2-boundary-20261021/ui-previews")
            Files.createDirectories(output)
            scene.render(System.nanoTime()).use { image ->
                assertEquals(360,image.width);assertEquals(500,image.height)
                image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(output.resolve("long-800-percent-viewport.png"),it.bytes) }
            }
            saveDesktopScreenshotVerified(png,{}, {error("no resources")}) { target }
            assertContentEquals(bytes,Files.readAllBytes(target))
            val decoded=javax.imageio.ImageIO.read(target.toFile())
            assertEquals(320,decoded.width);assertEquals(16000,decoded.height)
            Files.write(output.resolve("long-source.png"),bytes)
        }finally{scene.close();Files.deleteIfExists(target);Files.delete(root)}
    }
}
