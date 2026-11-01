@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopEditorPromptBudgetTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `three narrow and wide estimate dialogs read model identities and never alter entities or settings`() = runBlocking(edt) {
        val parent = Files.createTempDirectory("editor-token-budget-").toAbsolutePath().normalize()
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
                check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray()
            })
        try {
            val first = ModelConfig("first", "合成模型一", "https://example.test/v1", "fixture-key", "synthetic-one", createdAt = 1)
            val second = first.copy(id = "second", displayName = "合成模型二", modelName = "synthetic-two")
            app.modelRepository.saveModel(first); app.modelRepository.saveModel(second)
            app.settingsRepository.updateAppSettings { it.copy(defaultModelId = first.id) }
            val settings = app.settingsRepository.getAppSettings()
            val controller = assertNotNull(app.characterEditorController.promptBudget)
            val entities = listOf("character" to CharacterCard("card", "合成卡", basicSetting = "合成世界设定",
                characters = listOf(CharacterInfo("person", "合成人物", profile = "人物简介", desktopEnabled = false)), createdAt = 1, updatedAt = 1),
                "format" to FormatCard("format", "合成格式", "合成格式正文", createdAt = 1),
                "world" to WorldBook("world", "合成世界书", entries = listOf(WorldBookEntry("entry", "合成条目", listOf("触发"), "合成正文"))))
            for (width in listOf(420, 1100)) for ((name, entity) in entities) {
                val activity = DesktopModalActivity()
                val scene = ImageComposeScene(width, 900, coroutineContext = edt) {
                    CompositionLocalProvider(LocalDesktopModalActivity provides activity) { DesktopEditorPromptBudget(controller, entity) }
                }
                try {
                    scene.until { has("字段 Token 估算") }; scene.click("字段 Token 估算")
                    scene.until { !controller.state.value.loading && textContains("静态合计 ≈") && textContains("合成模型一") }
                    assertTrue(scene.textContains("不是本轮输入总量"))
                    scene.shot("editor-budget-$name-$width.png")
                    controller.select(second.id)
                    scene.until { textContains("合成模型二") && !controller.state.value.loading }
                    scene.click("关闭 Token 估算"); scene.until { !has("关闭 Token 估算") }
                    assertEquals(settings, app.settingsRepository.getAppSettings())
                    assertEquals("synthetic-one", app.modelRepository.getModel(first.id)!!.modelName)
                    assertEquals("synthetic-two", app.modelRepository.getModel(second.id)!!.modelName)
                    controller.select(null)
                } finally { scene.close() }
            }
        } finally {
            app.close()
            check(parent.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            parent.toFile().deleteRecursively()
        }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun collect(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::collect)
        return semanticsOwners.flatMap { collect(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.matches(label: String): Boolean = config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
        config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true || children.any { it.matches(label) }
    private fun ImageComposeScene.has(label: String) = nodes().any { it.matches(label) }
    private fun ImageComposeScene.textContains(value: String) = nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> value in text.text } == true }
    private suspend fun ImageComposeScene.until(condition: ImageComposeScene.() -> Boolean) = withTimeout(15_000) {
        while (!condition()) { render().close(); delay(20) }; repeat(3) { render().close(); delay(20) }
    }
    private suspend fun ImageComposeScene.click(label: String) {
        val node = nodes().first { it.matches(label) && it.config.contains(SemanticsActions.OnClick) }
        assertFalse(node.config.contains(SemanticsProperties.Disabled)); assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        repeat(3) { render().close(); delay(20) }
    }
    private fun ImageComposeScene.shot(name: String) {
        val target = System.getenv("CCB_FORMAT_AI_REVIEW_DIR") ?: return
        val path = Path.of(target).resolve(name); Files.createDirectories(path.parent)
        render().use { image -> image.encodeToData()!!.use { Files.write(path, it.bytes) } }
    }
}
