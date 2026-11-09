@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
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
                val model = ModelConfig("model", "合成模型", "http://127.0.0.1:1/v1", "", "synthetic", isMultimodal = true, createdAt = 1)
                val replacement = model.copy(id = "replacement", modelName = "replacement")
                app.characterRepository.save(card); app.modelRepository.saveModel(model); app.modelRepository.saveModel(replacement)
                app.settingsRepository.updateAppSettings { it.copy(allowCleartextModelApi = true, defaultModelId = model.id) }
                val editor = app.characterEditorController; editor.openExisting(card.id)
                val imagePath = parent.resolve("synthetic-reference.png")
                Files.write(imagePath, DesktopImageEditing.png(java.awt.image.BufferedImage(12, 8, java.awt.image.BufferedImage.TYPE_INT_RGB)))
                val picker = object : DesktopFilePicker {
                    override fun pickOpenFile(type: DesktopFileType) = imagePath
                    override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = error("No save")
                }
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
                    }, filePicker = picker)
                editor.cardAi = ai
                val appearance = DesktopCharacterAppearanceController(editor, app.settingsRepository, app.effectiveModelResolver,
                    app.taskRuntime, app.dataOperationCoordinator, picker, stream = { _, selected, _ ->
                        assertEquals(model.id, selected.id)
                        flowOf(StreamEvent.Delta("""{"appearance":"图片外貌","clothing":"图片服装"}"""), StreamEvent.Done)
                    })
                editor.appearanceAi = appearance
                val imageSecrets = InMemoryDesktopSecretStore().also {
                    it.save(com.example.chatbar.desktop.security.DesktopCredentialKey.NovelAiToken, "synthetic-key")
                }
                val imageAi = DesktopCharacterImageController(editor, app.settingsRepository, app.effectiveModelResolver,
                    app.taskRuntime, app.dataOperationCoordinator,
                    captureCredential = { com.example.chatbar.desktop.security.DesktopCredentialCall(imageSecrets,
                        com.example.chatbar.desktop.security.DesktopCredentialKey.NovelAiToken, "synthetic-account", "synthetic-key") },
                    loadRequirement = { "合成封面额外要求" },
                    design = { _, _, _, _, _ -> com.example.chatbar.domain.image.NovelAiPromptPlan("synthetic-cover", emptyList(), negativePrompt = "synthetic-negative") },
                    generateImage = { _, _, _, _, _ -> Files.readAllBytes(imagePath) })
                editor.cardImages = imageAi
                val activity = DesktopModalActivity()
                val scene = ImageComposeScene(width, 900, coroutineContext = edt) {
                    AuxiliaryReviewHost {
                        CompositionLocalProvider(LocalDesktopModalActivity provides activity) {
                            DesktopCharacterEditorOverlay(editor, editor.state.value)
                        }
                    }
                }
                try {
                    ai.open(CharacterCardAiOperation.FILL)
                    scene.until { ai.state.value.open && !ai.state.value.loading }
                    assertTrue(scene.has("关闭角色卡 AI"))
                    scene.click("添加参考图片")
                    scene.until { !ai.state.value.imagesLoading && ai.state.value.images.size == 1 }
                    assertTrue(scene.has("参考图 1"))
                    scene.shot("character-card-ai-reference-$width.png")
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
                    scene.click("为候选生成封面")
                    scene.until { imageAi.state.value.open && !imageAi.state.value.loading }
                    scene.click("生成新图片")
                    scene.until { !imageAi.state.value.busy && imageAi.state.value.candidate != null }
                    assertEquals(card, editor.state.value.card)
                    scene.shot("character-card-cover-candidate-$width.png")
                    scene.click("应用图片到草稿")
                    scene.until { !imageAi.state.value.open }
                    assertNotNull(ai.state.value.candidate?.avatar); assertEquals(card, editor.state.value.card)
                    scene.click("应用到当前草稿")
                    scene.until { !ai.state.value.open }
                    assertEquals("合成新外观", editor.state.value.card!!.characters.single().appearance)
                    assertTrue(editor.state.value.dirty); assertEquals(card, app.characterRepository.getById(card.id))
                    val beforeAppearance = editor.state.value.card
                    appearance.open("person")
                    scene.until { !appearance.state.value.busy && appearance.state.value.candidate != null }
                    assertEquals(beforeAppearance, editor.state.value.card)
                    assertTrue(scene.has("上传的人物参考图"))
                    scene.shot("character-card-appearance-candidate-$width.png")
                    scene.click("应用外貌与服装到草稿")
                    scene.until { !appearance.state.value.open }
                    assertEquals("图片外貌", editor.state.value.card!!.characters.single().appearance)
                    assertEquals("图片服装", editor.state.value.card!!.characters.single().clothing)
                    assertEquals(card, app.characterRepository.getById(card.id))
                } finally { scene.close() }
            } finally {
                app.close()
                check(parent.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
                parent.toFile().deleteRecursively()
            }
        }
    }
    /** Separate native tools are rendered above the editor only for synthetic visual review. */
    @Composable private fun AuxiliaryReviewHost(content: @Composable () -> Unit) {
        val tools = remember { mutableStateMapOf<Any, @Composable () -> Unit>() }
        val frame: @Composable (@Composable () -> Unit) -> Unit = { body ->
            val key = remember { Any() }
            val current by rememberUpdatedState(body)
            DisposableEffect(key) {
                tools[key] = { current() }
                onDispose { tools.remove(key) }
            }
        }
        Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.background)) {
            CompositionLocalProvider(LocalDesktopEditorAuxiliaryFrame provides frame) { content() }
            tools.values.toList().forEachIndexed { index, tool ->
                Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).zIndex(1f + index)) { tool() }
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
