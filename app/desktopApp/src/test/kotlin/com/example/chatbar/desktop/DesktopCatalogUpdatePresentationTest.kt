@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.domain.image.DanbooruCatalogMetadata
import com.example.chatbar.domain.update.DanbooruCatalogUpdateInfo
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopCatalogUpdatePresentationTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private suspend fun ImageComposeScene.frames() { repeat(5) { render().close(); yield(); delay(15) } }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.matches(label: String): Boolean = config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true || children.any { it.matches(label) }
    private suspend fun ImageComposeScene.click(label: String) {
        val n = nodes().first { it.matches(label) && it.config.contains(SemanticsActions.OnClick) }
        assertFalse(n.config.contains(SemanticsProperties.Disabled)); assertTrue(n.config[SemanticsActions.OnClick].action!!.invoke()); frames()
    }
    private fun ImageComposeScene.shot(name: String) {
        val folder = System.getenv("CCB_CATALOG_REVIEW_DIR") ?: return
        val target = Path.of(folder).resolve(name); Files.createDirectories(target.parent)
        render().use { image -> image.encodeToData()!!.use { Files.write(target, it.bytes) } }
    }
    @Test fun `actual update page checks both and shows catalog failure retry and separate explicit download`() = runBlocking(edt) {
        val scope = CoroutineScope(SupervisorJob() + edt); var fail = true; var appCalls = 0; var catalogCalls = 0; var installs = 0
        val info = DanbooruCatalogUpdateInfo(DanbooruCatalogMetadata("a".repeat(40), "inline", 1), "b".repeat(40), "inline", "https://raw.githubusercontent.com/synthetic/tag.sqlite", "", 42)
        val notices = DesktopUpdateNotices(scope, "0.1.0-preview.1", null, false) { appCalls++; DesktopUpdateResult(null, false) }
        val owner = DesktopCatalogUpdates(scope, { catalogCalls++; if (fail) error("fixture") else info }, { _, _, _ -> installs++ })
        val scene = ImageComposeScene(1100, 1400, coroutineContext = edt) {
            CompositionLocalProvider(LocalDesktopUpdateNotices provides notices, LocalDesktopCatalogUpdates provides { owner }) {
                Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.background)) { DesktopUpdatePanel() }
            }
        }
        try {
            scene.frames(); assertEquals(0, catalogCalls); scene.click("检查更新")
            assertEquals(1, appCalls); assertEquals(1, catalogCalls); assertEquals(0, installs)
            assertTrue(scene.nodes().any { it.matches("Danbooru 词库检查失败；程序更新不受影响，可重试") && it.boundsInRoot.height > 0 })
            scene.shot("catalog-check-failed.png")
            fail = false; scene.click("重试词库检查")
            assertEquals(1, appCalls); assertEquals(2, catalogCalls); assertEquals(0, installs)
            scene.shot("catalog-update-available.png"); scene.click("下载并更新词库")
            assertEquals(1, installs); assertTrue(scene.nodes().any { it.matches("Danbooru 词库已更新并启用") })
        } finally { scene.close(); owner.closeAndDrain(); notices.closeAndDrain(); scope.cancel() }
    }

    @Test fun `catalog owner preparation failure stays visible while actual program update check remains usable`() = runBlocking(edt) {
        val scope = CoroutineScope(SupervisorJob() + edt); var appCalls = 0; var prepareCalls = 0
        val notices = DesktopUpdateNotices(scope, "0.1.0-preview.1", null, false) { appCalls++; DesktopUpdateResult(null, false) }
        val provider: () -> DesktopCatalogUpdates = { prepareCalls++; error("SYNTHETIC_PREPARATION_FAILURE") }
        val scene = ImageComposeScene(1100, 1400, coroutineContext = edt) {
            CompositionLocalProvider(LocalDesktopUpdateNotices provides notices, LocalDesktopCatalogUpdates provides provider) {
                Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.background)) { DesktopUpdatePanel() }
            }
        }
        try {
            scene.frames(); assertEquals(1, prepareCalls)
            assertTrue(scene.nodes().any { it.matches("无法准备 Danbooru 词库检查；程序更新仍可单独使用。") })
            scene.click("检查更新"); assertEquals(1, appCalls); assertEquals(1, prepareCalls)
            scene.click("重试准备词库检查"); assertEquals(2, prepareCalls); assertEquals(1, appCalls)
        } finally { scene.close(); notices.closeAndDrain(); scope.cancel() }
    }
}
