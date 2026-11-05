@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
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
            for (width in listOf(420, 1100)) {
                val store = DesktopSettingsStore(root.resolve("view-$width"), gate)
                val controller = DesktopNetworkProxyController(store)
                val scene = ImageComposeScene(width, 780, coroutineContext = edt) {
                    Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background)
                        .verticalScroll(rememberScrollState()).padding(20.dp)) { DesktopNetworkProxyPanel(controller) }
                }
                try {
                    repeat(5) { scene.render().close(); delay(20) }
                    fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                    fun texts() = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.flatMap {
                        it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text }
                    }
                    fun allNodes() = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
                    fun matches(node: SemanticsNode, label: String): Boolean =
                        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true || node.children.any { matches(it, label) }
                    fun click(label: String) {
                        val node = allNodes().first { matches(it, label) && it.config.contains(SemanticsActions.OnClick) }
                        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
                    }
                    fun shot(name: String) { System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
                        val file = Path.of(directory).resolve(name); Files.createDirectories(file.parent)
                        scene.render().use { image -> image.encodeToData()!!.use { Files.write(file, it.bytes) } }
                    } }
                    assertTrue("应用网络代理" in texts()); assertTrue("保存网络设置" in texts()); assertTrue("查看当前运行路由" in texts())
                    assertFalse(Files.exists(store.settingsPath))
                    shot("network-proxy-$width.png")
                    click("HTTP 代理")
                    repeat(3) { scene.render().close(); delay(20) }
                    assertTrue("代理主机" in texts()); assertTrue("端口" in texts())
                    val fields = allNodes().filter { it.config.contains(SemanticsActions.SetText) }
                    assertEquals(2, fields.size)
                    val port = if (com.example.chatbar.domain.ProxyAwareClient.applicationProxy.port == 19099) 19100 else 19099
                    assertTrue(fields.last().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(port.toString())))
                    repeat(3) { scene.render().close(); delay(20) }
                    click("保存网络设置")
                    withTimeout(15_000) { while (!controller.state.value.restartRequired) { scene.render().close(); delay(20) } }
                    repeat(3) { scene.render().close(); delay(20) }
                    assertEquals(port, controller.state.value.saved.port)
                    assertEquals(com.example.chatbar.domain.ApplicationProxyMode.HTTP, controller.state.value.saved.mode)
                    assertEquals(controller.state.value.saved,
                        assertIs<DesktopSettingsLoadResult.Loaded>(store.load()).document.settings.networkProxy)
                    shot("network-proxy-http-awaiting-restart-$width.png")
                } finally { scene.close() }
            }
        } finally { gate.closeAndDrain(); root.toFile().deleteRecursively() }
    }
}
