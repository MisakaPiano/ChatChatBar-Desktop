@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopCharacterExportCompatibilityUiTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.text() = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    private suspend fun ImageComposeScene.until(predicate: () -> Boolean) = withTimeout(15000) {
        while (!predicate()) { render(System.nanoTime()).close(); delay(10) }
        render(System.nanoTime()).close()
    }
    private fun ImageComposeScene.click(text: String) {
        val node = nodes().single { it.text() == text && it.config.contains(SemanticsActions.OnClick) }
        assertTrue(assertNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }
    private fun ImageComposeScene.capture(name: String) {
        System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
            val path = Path.of(directory).resolve("character-export-$name.png"); Files.createDirectories(path.parent)
            render(System.nanoTime()).use { it.encodeToData()!!.use { data -> Files.write(path, data.bytes) } }
        }
    }

    @Test fun `real JSON dialog opens compatible selects full warning and resets compatibility on reopen`() = runBlocking(edt) {
        val parent = Files.createTempDirectory("character-export-ui-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { path -> check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray() })
        try {
            val card = app.characterTransfers.importNew(CharacterCardPackage(card = PackagedCharacterCard("Synthetic export")))
            val controller = app.createTypedTransferController(); controller.beginCharacterJsonExport(card.id)
            val modal = DesktopModalActivity()
            val scene = ImageComposeScene(900, 700) {
                CompositionLocalProvider(LocalDesktopModalActivity provides modal) {
                    DesktopCharacterExportDialogs(controller)
                    DesktopTypedTransferPanel(controller)
                }
            }
            try {
                val trigger = "文件格式 · 官方 CCB 兼容（推荐） ▾"
                scene.until { !controller.state.value.busy && controller.state.value.characters.any { it.id == card.id } &&
                    scene.nodes().any { it.text() == trigger } }
                assertEquals(1, modal.count)
                assertEquals(1, scene.nodes().count { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("关闭角色导出") })
                scene.capture("official")
                scene.click(trigger)
                scene.until { scene.nodes().any { it.text() == "Desktop 完整格式" } }
                scene.click("Desktop 完整格式")
                scene.until { scene.nodes().any { it.text().contains("当前原版 CCB 不支持此格式") } }
                scene.capture("desktop-full")
                controller.cancelCharacterJsonExport()
                scene.until { scene.nodes().none { it.text().contains("选择保存位置并导出") } }
                assertEquals(0, modal.count)
                controller.beginCharacterJsonExport(card.id)
                scene.until { scene.nodes().any { it.text() == trigger } }
                assertEquals(1, modal.count)
                assertTrue(scene.nodes().none { it.text().contains("当前原版 CCB 不支持此格式") })
            } finally { scene.close() }
        } finally { app.close(); parent.toFile().deleteRecursively() }
    }
}
