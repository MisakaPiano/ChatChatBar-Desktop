@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.chat.StreamEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlin.test.*

class DesktopFormatCardAutoFillPanelTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `new card exposes AI entry and candidate only applies after explicit button at narrow and wide widths`() = runBlocking(edt) {
        for (width in listOf(420, 1100)) {
            val parent = Files.createTempDirectory("format-ai-ui-").toAbsolutePath().normalize()
            val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
                parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
                    check(path == "presets/manifest.json")
                    """{"entries":[]}""".toByteArray()
                })
            try {
                val card = CharacterCard("synthetic-ui", "合成探索角色卡", basicSetting = "合成设定", createdAt = 0, updatedAt = 0)
                val model = ModelConfig("synthetic-model", "合成设计模型", "http://127.0.0.1:1/v1", "", "synthetic", createdAt = 0)
                app.characterRepository.save(card); app.modelRepository.saveModel(model)
                app.settingsRepository.updateAppSettings { it.copy(allowCleartextModelApi = true, defaultModelId = model.id) }
                val editor = app.formatCardEditorController; editor.openNew()
                editor.autoFill = DesktopFormatCardAutoFillController(editor, app.characterRepository, app.settingsRepository,
                    app.effectiveModelResolver, app.taskRuntime, app.dataOperationCoordinator,
                    stream = { _, _, _, _ -> flowOf(StreamEvent.Delta("""{"name":"合成格式卡","content":"记录旅途与人物对白。\n---\n行动选项\n---","userTools":[]}"""), StreamEvent.Done) })
                val ai = editor.autoFill!!
                val activity = DesktopModalActivity()
                val scene = ImageComposeScene(width, 900, coroutineContext = edt) {
                    CompositionLocalProvider(LocalDesktopModalActivity provides activity) {
                        DesktopFormatCardEditorOverlay(editor)
                    }
                }
                try {
                    scene.until { has("AI 设计格式卡") }; scene.click("AI 设计格式卡")
                    scene.until { ai.state.value.open && !ai.state.value.loadingOptions }
                    assertTrue(scene.has("关闭格式卡设计"))
                    scene.click("展开要求编辑"); scene.until { has("返回候选预览") }
                    scene.click("返回候选预览")
                    ai.selectCharacter(card.id); ai.generate()
                    scene.until { !ai.state.value.busy && ai.state.value.candidate != null }
                    assertEquals("", editor.state.value.card!!.content)
                    assertTrue(app.formatCardRepository.getAll().isEmpty())
                    scene.shot("format-card-ai-candidate-$width.png")
                    scene.click("应用到新卡")
                    scene.until { !ai.state.value.open }
                    assertEquals("合成格式卡", editor.state.value.card!!.name)
                    assertTrue(editor.state.value.dirty)
                    assertTrue(app.formatCardRepository.getAll().isEmpty())
                    assertFalse(scene.has("AI 设计格式卡"))
                } finally { scene.close() }
            } finally {
                app.close()
                check(parent.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
                parent.toFile().deleteRecursively()
            }
        }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun collect(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::collect)
        return semanticsOwners.flatMap { collect(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.matches(label: String): Boolean = config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
        config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true || children.any { it.matches(label) }
    private fun ImageComposeScene.has(label: String) = nodes().any { it.matches(label) }
    private suspend fun ImageComposeScene.until(condition: ImageComposeScene.() -> Boolean) = withTimeout(15_000) {
        while (!condition()) { render().close(); delay(20) }
        repeat(3) { render().close(); delay(20) }
    }
    private suspend fun ImageComposeScene.click(label: String) {
        val node = nodes().first { it.matches(label) && it.config.contains(SemanticsActions.OnClick) }
        assertFalse(node.config.contains(SemanticsProperties.Disabled))
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        repeat(3) { render().close(); delay(20) }
    }
    private fun ImageComposeScene.shot(name: String) {
        val target = System.getenv("CCB_FORMAT_AI_REVIEW_DIR") ?: return
        val file = Path.of(target).resolve(name); Files.createDirectories(file.parent)
        render().use { image -> image.encodeToData()!!.use { Files.write(file, it.bytes) } }
    }
}
