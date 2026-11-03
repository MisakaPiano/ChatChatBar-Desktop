@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.NovelAiStudioStateRepository
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopStudioVarietyPlusTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `actual Studio toggle reaches durable draft request undo preset and history without changing source text`() = runBlocking(edt) {
        val parent = Files.createTempDirectory("studio-vplus-")
        val root = parent.resolve("data")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { """{"entries":[]}""".toByteArray() })
        try {
            val controller = app.novelAiStudioController; controller.load()
            controller.edit { it.copy(selectedModel = NovelAiImageModel.V4_5_FULL, followDefaultNovelAiImageModel = false,
                stylePrompt = "Original style  ", basePrompt = "Original scene  ", negativePrompt = "Original negative  ") }
            val scene = ImageComposeScene(520, 760, coroutineContext = edt) {
                val draft by controller.draft.collectAsState()
                val scope = rememberCoroutineScope()
                Column { draft?.activeSettings?.let { settings -> StudioAdvancedSettings(settings) { transform ->
                    scope.launch { controller.edit { it.withActiveSettings(transform(it.activeSettings)) } }
                } } }
            }
            fun current() = controller.draft.value!!
            fun parameters() = Json.parseToJsonElement(NovelAiImageService().buildRequestBody(
                current().toPromptPlan(), current().activeSettings.imageSize(), current().activeSettings)).jsonObject.getValue("parameters").jsonObject
            try {
                scene.frames(); assertTrue(scene.has("Variety+（V+）")); assertFalse(current().activeSettings.varietyPlus)
                scene.click("Variety+（V+）"); scene.until { current().activeSettings.varietyPlus }
                assertEquals(58, parameters().getValue("skip_cfg_above_sigma").jsonPrimitive.int)
                val enabled = current()
                assertEquals("Original scene  ", enabled.basePrompt); assertEquals("Original negative  ", enabled.negativePrompt)
                assertEquals(enabled, NovelAiStudioStateRepository(JsonFileStorage(root)).loadDraft())
                assertTrue(controller.undo()); scene.until { !current().activeSettings.varietyPlus }
                assertEquals(JsonNull, parameters()["skip_cfg_above_sigma"])
                scene.click("Variety+（V+）"); scene.until { current().activeSettings.varietyPlus }
                scene.click("Sampler · Euler Ancestral ▾"); scene.click("DPM++ 2M SDE")
                scene.until { current().activeSettings.sampler == NovelAiSampler.DPM_PLUS_PLUS_2M_SDE }
                assertEquals("k_dpmpp_2m_sde", parameters().getValue("sampler").jsonPrimitive.content)
                val preset = parent.resolve("synthetic.ccbpreset")
                assertTrue(controller.exportGenerationPreset(preset))
                val recipe = current().toRecipe()
                controller.edit { it.copy(selectedModel = NovelAiImageModel.V5_FULL) }
                scene.frames(); assertFalse(scene.has("Variety+（V+）")); assertFalse(parameters().containsKey("skip_cfg_above_sigma"))
                controller.edit { it.copy(v5Settings = it.v5Settings.copy(effort = NovelAiEffort.MEDIUM)) }
                scene.frames(); assertFalse(scene.has("Variety+（V+）")); assertFalse(parameters().containsKey("skip_cfg_above_sigma"))
                assertTrue(current().v45Settings.varietyPlus)
                controller.edit { it.applyHistoryRecipe(recipe, 7, NovelAiHistoryApplyMode.FULL) }
                scene.frames(); assertTrue(scene.has("Variety+（V+）")); assertTrue(current().activeSettings.varietyPlus)
                assertEquals(58, parameters().getValue("skip_cfg_above_sigma").jsonPrimitive.int)
                controller.edit { it.withActiveSettings(it.activeSettings.copy(varietyPlus = false)) }
                assertTrue(controller.inspectGenerationPreset(preset)); assertTrue(controller.applyGenerationPreset())
                assertTrue(current().activeSettings.varietyPlus)
                assertEquals(enabled.basePrompt, current().basePrompt); assertEquals(enabled.stylePrompt, current().stylePrompt)
                scene.frames()
                System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
                    val file = Path.of(directory).resolve("studio-variety-plus.png"); Files.createDirectories(file.parent)
                    scene.render().use { image -> image.encodeToData()!!.use { Files.write(file, it.bytes) } }
                }
            } finally { scene.close() }
        } finally { app.close(); check(parent.toFile().deleteRecursively()) }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.has(label: String): Boolean = config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true || children.any { it.has(label) }
    private fun ImageComposeScene.has(label: String) = nodes().any { it.has(label) }
    private suspend fun ImageComposeScene.frames() { repeat(5) { render().close(); delay(15) } }
    private suspend fun ImageComposeScene.until(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) { render().close(); delay(15) }; frames()
    }
    private suspend fun ImageComposeScene.click(label: String) {
        val node = nodes().first { it.has(label) && it.config.contains(SemanticsActions.OnClick) }
        assertTrue(node.boundsInRoot.width > 0 && node.boundsInRoot.height > 0)
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); frames()
    }
}
