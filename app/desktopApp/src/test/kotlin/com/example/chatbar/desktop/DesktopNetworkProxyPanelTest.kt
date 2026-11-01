@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopNetworkProxyPanelTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `network settings remain accessible in narrow and wide management layouts`() = runBlocking(edt) {
        val root = Files.createTempDirectory("network-panel-"); val gate = DesktopDataOperationCoordinator()
        try {
            val controller = DesktopNetworkProxyController(DesktopSettingsStore(root, gate))
            for (width in listOf(420, 1100)) {
                val scene = ImageComposeScene(width, 780, coroutineContext = edt) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) { DesktopNetworkProxyPanel(controller) }
                }
                try {
                    repeat(5) { scene.render().close(); delay(20) }
                    fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                    fun texts() = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.flatMap {
                        it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text }
                    }
                    assertTrue("应用网络代理" in texts()); assertTrue("保存网络设置" in texts()); assertTrue("查看当前运行路由" in texts())
                    System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
                        val file = Path.of(directory).resolve("network-proxy-$width.png"); Files.createDirectories(file.parent)
                        scene.render().use { image -> image.encodeToData()!!.use { Files.write(file, it.bytes) } }
                    }
                } finally { scene.close() }
            }
            assertFalse(Files.exists(root.resolve("desktop-settings.json")))
        } finally { gate.closeAndDrain(); root.toFile().deleteRecursively() }
    }
}
