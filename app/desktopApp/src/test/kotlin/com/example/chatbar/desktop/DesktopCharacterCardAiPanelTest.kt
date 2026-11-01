@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.CharacterCardAiOperation
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.search.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlin.test.*

class DesktopCharacterCardAiPanelTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `narrow and wide UI resumes partial research after model change then applies real diff explicitly`() = runBlocking(edt) {
        for (width in listOf(420, 1100)) {
            val parent = Files.createTempDirectory("character-ai-ui-").toAbsolutePath().normalize()
            val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
                parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
                    check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray()
                })
            try {
                val card = CharacterCard("synthetic-ui", "合成探索卡", greeting = "原开场",
                    characters = listOf(CharacterInfo("person", "合成人物", profile = "既有简介")),
                    defaultImageNegativePrompt = "synthetic-negative", createdAt = 1, updatedAt = 1)
                val model = ModelConfig("model", "合成模型", "http://127.0.0.1:1/v1", "", "synthetic", createdAt = 1)
                val replacement = model.copy(id = "replacement", modelName = "replacement")
                app.characterRepository.save(card); app.modelRepository.saveModel(model); app.modelRepository.saveModel(replacement)
                app.settingsRepository.updateAppSettings { it.copy(allowCleartextModelApi = true, defaultModelId = model.id) }
                val editor = app.characterEditorController; editor.openExisting(card.id)
                val ai = DesktopCharacterCardAiController(editor, app.settingsRepository, app.effectiveModelResolver,
                    app.taskRuntime, app.dataOperationCoordinator, stream = { _, _, _, selected, _ ->
                        assertEquals("replacement", selected.modelName)
                        flowOf(StreamEvent.Delta("""{"characters":[{"targetIndex":0,"appearance":"合成新外观"}]}"""), StreamEvent.Done)
                    }, streamResearch = { _, _, _, _ -> error("No unplanned model call") },
                    prepareResearch = { _, _, selected, _, _, resume, checkpoint, _, _, _ ->
                        val plan = CharacterResearchPlan(true, listOf(CharacterResearchQuery("https://example.test/source")))
                        if (resume?.plan == null) {
                            checkpoint(ResearchDebugSnapshot(plan = plan)); error("synthetic source failure")
                        }
                        assertEquals(plan, resume.plan); assertEquals("replacement", selected.modelName)
                        ResearchBrief(facts = listOf("合成整理事实")).also {
                            checkpoint(ResearchDebugSnapshot(plan = plan, brief = it))
                        }
                    })
                editor.cardAi = ai
                val activity = DesktopModalActivity()
                val scene = ImageComposeScene(width, 900, coroutineContext = edt) {
                    CompositionLocalProvider(LocalDesktopModalActivity provides activity) {
                        DesktopCharacterEditorOverlay(editor, editor.state.value)
                    }
                }
                try {
                    ai.open(CharacterCardAiOperation.FILL)
                    scene.until { ai.state.value.open && !ai.state.value.loading }
                    assertTrue(scene.has("关闭角色卡 AI"))
                    ai.setRequest("补全外观"); ai.selectSourceMode(CharacterResearchSourceMode.MANUAL_URLS)
                    ai.setManualUrls("https://example.test/source"); ai.generate()
                    scene.until { !ai.state.value.busy && ai.state.value.error != null }
                    ai.selectModel(replacement.id)
                    scene.until { ai.state.value.error == null && has("重试未完成步骤") }
                    scene.click("重试未完成步骤")
                    scene.until { !ai.state.value.busy && ai.state.value.candidate != null }
                    assertEquals(card, editor.state.value.card)
                    assertEquals(card, app.characterRepository.getById(card.id))
                    scene.shot("character-card-ai-candidate-$width.png")
                    scene.click("应用到当前草稿")
                    scene.until { !ai.state.value.open }
                    assertEquals("合成新外观", editor.state.value.card!!.characters.single().appearance)
                    assertTrue(editor.state.value.dirty); assertEquals(card, app.characterRepository.getById(card.id))
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
        assertFalse(node.config.contains(SemanticsProperties.Disabled)); assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        repeat(3) { render().close(); delay(20) }
    }
    private fun ImageComposeScene.shot(name: String) {
        val target = System.getenv("CCB_FORMAT_AI_REVIEW_DIR") ?: return
        val path = Path.of(target).resolve(name); Files.createDirectories(path.parent)
        render().use { image -> image.encodeToData()!!.use { Files.write(path, it.bytes) } }
    }
}
