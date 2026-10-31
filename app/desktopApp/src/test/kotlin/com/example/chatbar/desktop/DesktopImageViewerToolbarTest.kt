@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.chatbar.data.local.entity.ThemeMode
import com.example.chatbar.domain.appearance.DefaultThemeColorHsv
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.*
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopImageViewerToolbarTest {
    @Test fun `production frame renders opaque matching captions and reachable browser toolbar in both themes`() = runBlocking(edt) {
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            val zoom = DesktopImageZoomState()
            val chrome = DesktopWindowChrome {}
            val recorder = DesktopChromeLayoutRecorder(chrome)
            val selection = DesktopViewerSelection(listOf("A", "B", "A"), 1)
            val bytes = DesktopImageEditing.png(java.awt.image.BufferedImage(320, 200, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().let { g -> try {
                    g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(45, 130, 155), 320f, 200f, java.awt.Color(25, 65, 90))
                    g.fillRect(0, 0, 320, 200)
                } finally { g.dispose() } }
            })
            val colors = desktopSemanticColors(mode, DefaultThemeColorHsv, false)
            val scene = ImageComposeScene(960, 800) {
                CompositionLocalProvider(LocalDesktopPalette provides colors) {
                    DesktopImageToolFrame("图片 2 / 3", chrome, recorder) {
                        DesktopImageZoomSurface("synthetic", { bytes }, Modifier.weight(1f).fillMaxWidth(), zoomState = zoom)
                        DesktopImageViewerToolbar(zoom, selection) {
                            ViewerToolbarSeparator()
                            DesktopChatIconAction("保存 PNG", DesktopAppIcons.SaveImage) {}
                            DesktopChatIconAction("复制图片", DesktopAppIcons.Copy) {}
                            DesktopChatIconAction("在文件夹中显示", DesktopAppIcons.Folder) {}
                            DesktopChatIconAction("图像工具", DesktopAppIcons.Tools) {}
                        }
                    }
                }
            }
            try {
                withTimeout(5000) { while (zoom.sourceSize.width != 320 || zoom.fitScale <= 1f) { scene.render().close(); yield() } }
                repeat(4) { scene.render().close(); yield() }
                val png = scene.render().use { it.encodeToData()!!.use { data -> data.bytes } }
                val path = java.nio.file.Path.of("build/preview-synthetic/viewer-frames/${mode.name.lowercase()}.png")
                java.nio.file.Files.createDirectories(path.parent); java.nio.file.Files.write(path, png)
                val raster = javax.imageio.ImageIO.read(png.inputStream())
                if (DesktopWindowChrome.isWindows) {
                    val actual = java.awt.Color(raster.getRGB(480, 22))
                    val expected = if (mode == ThemeMode.DARK) java.awt.Color(22, 27, 34) else java.awt.Color.WHITE
                    assertEquals(expected, actual)
                    assertEquals(3, chrome.layout.captions.size)
                }
                assertTrue(zoom.percent > 100)
            } finally { scene.close() }
        }
    }
    @Test fun `original size is exact for tiny avatars and very tall images`() {
        for (scale in listOf(.001f, .5f, 1f, 1000f)) {
            val zoom = DesktopImageZoomState().apply { fitScale = scale }
            zoom.actualSize(); assertEquals(100, zoom.percent)
            zoom.zoomBy(1f); assertEquals(100, zoom.percent)
            zoom.fit(); assertEquals(DesktopViewerTransform(), zoom.transform)
        }
    }
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `toolbar navigates exact occurrences and controls viewport without changing source`() = runBlocking(edt) {
        val zoom = DesktopImageZoomState().apply { fitScale = .5f }
        val selection = DesktopViewerSelection(listOf("A", "B", "A"), 2)
        val scene = ImageComposeScene(960, 100) { DesktopImageViewerToolbar(zoom, selection) }
        fun nodes(): List<SemanticsNode> {
            fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
        }
        suspend fun frames() { repeat(4) { scene.render().close(); yield() } }
        suspend fun click(s: String) {
            val n = nodes().single { (s in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() ||
                it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == s } == true) && it.config.contains(SemanticsActions.OnClick) }
            n.config[SemanticsActions.OnClick].action!!(); frames()
        }
        try {
            frames(); click("上一张"); assertEquals(1, selection.index)
            click("下一张"); assertEquals(2, selection.index)
            click("放大"); assertTrue(zoom.transform.zoom > 1f)
            click("缩小"); assertEquals(1f, zoom.transform.zoom)
            click("100%"); assertEquals(100, zoom.percent)
            click("适应窗口"); assertEquals(50, zoom.percent)
            assertEquals(listOf("A", "B", "A"), selection.references)
            assertTrue(nodes().filter { it.config.contains(SemanticsActions.OnClick) }.all { it.boundsInRoot.bottom <= 100f })
        } finally { scene.close() }
    }
}
