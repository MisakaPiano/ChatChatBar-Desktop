@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.example.chatbar.desktop.backup.*
import kotlinx.coroutines.*
import java.nio.file.*
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopBackupExportDialogTest {
    private val edt = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block) }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun matches(n: SemanticsNode, label: String): Boolean = n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true || n.children.any { matches(it, label) }
    private suspend fun frames(scene: ImageComposeScene) { repeat(5) { scene.render(System.nanoTime()).close(); yield(); delay(15) } }
    private suspend fun click(scene: ImageComposeScene, label: String) {
        val n = nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && matches(it, label) }
        assertFalse(n.config.contains(SemanticsProperties.Disabled)); assertTrue(n.config[SemanticsActions.OnClick].action!!.invoke()); frames(scene)
    }
    @Test fun `selection presets global options and optional repeated password reach actual export callback within narrow and wide viewport`(): Unit = runBlocking(edt) {
        val root = Files.createTempDirectory("backup-dialog-")
        try {
            val card = Files.writeString(root.resolve("card.json"), """{"id":"card","name":"Synthetic card"}""")
            val session = Files.writeString(root.resolve("session.json"), """{"id":"session","characterCardId":"card","title":"Synthetic session"}""")
            val files = mapOf("entities/character_cards/card.json" to card, "entities/chat_sessions/session.json" to session)
            val catalog = BackupExportCatalog.read(root.toFile(), files) {}
            for (width in listOf(480, 1100)) {
                var result: AppBackupSelection? = null
                var captured: CharArray? = null
                val scene = ImageComposeScene(width, 760, coroutineContext = edt) {
                    DesktopBackupExportDialog(DesktopFullBackupState(catalog = catalog), {}, {}, { choice, secret -> result = choice; captured = secret })
                }
                try {
                    // Initial font/layout preparation is synchronous on a cold CI runner.
                    // Start the readiness deadline after that first render, while still waiting
                    // for the real asynchronous estimate and checking the enabled export flow.
                    frames(scene)
                    withTimeout(5000) { do { frames(scene) } while (nodes(scene).none { it.config.contains(SemanticsActions.OnClick) && matches(it, "下一步") && !it.config.contains(SemanticsProperties.Disabled) }) }
                    click(scene, "轻量")
                    val next = nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && matches(it, "下一步") }
                    assertTrue(next.boundsInRoot.bottom <= 760 && next.boundsInRoot.left >= 0)
                    click(scene, "下一步")
                    assertTrue(nodes(scene).any { matches(it, "当前导出不加密。持有文件者可读取所选聊天和密钥。") })
                    click(scene, "密码加密（可选）")
                    val inputs = nodes(scene).filter { it.config.contains(SemanticsActions.SetText) }.sortedBy { it.boundsInRoot.top }
                    assertEquals(2, inputs.size)
                    inputs[0].config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("synthetic-password")); frames(scene)
                    assertTrue(nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && matches(it, "选择保存位置并导出") }.config.contains(SemanticsProperties.Disabled))
                    inputs[1].config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("synthetic-password")); frames(scene)
                    click(scene, "选择保存位置并导出")
                    assertTrue(requireNotNull(result).sessions.values.all { !it.images && !it.voice && !it.archives })
                    assertEquals(BackupSettingOptions.ids, requireNotNull(result).globalOptions)
                    assertContentEquals("synthetic-password".toCharArray(), captured)
                    assertTrue(nodes(scene).filter { it.config.contains(SemanticsActions.SetText) }.all { it.config[SemanticsProperties.EditableText].text.isEmpty() })
                    System.getProperty("chatbar.test.screenshot.dir")?.let { directory ->
                        Files.createDirectories(Path.of(directory)); scene.render(System.nanoTime()).use { image -> image.encodeToData()?.let { Files.write(Path.of(directory, "backup-protection-$width.png"), it.bytes) } }
                    }
                } finally { captured?.fill('\u0000'); scene.close() }
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
