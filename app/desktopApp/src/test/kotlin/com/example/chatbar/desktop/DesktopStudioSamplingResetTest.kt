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
import kotlin.test.*

class DesktopStudioSamplingResetTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun NovelAiStudioDraft.content() = copy(contentRevision = 0, promptContentRevision = 0, updatedAt = 0)
    private fun settings(model: NovelAiImageModel, effort: NovelAiEffort) = NovelAiGenerationSettings(
        model = model, sizeTier = NovelAiSizeTier.LARGE, aspectRatio = NovelAiAspectRatio.LANDSCAPE,
        count = 3, steps = 39, guidance = 9f, seedMode = NovelAiSeedMode.FIXED, seed = 123456L,
        sampler = NovelAiSampler.DPM_PLUS_PLUS_2M, cfgRescale = .6f, varietyPlus = true,
        varietyPlusSigma = 77.0, customWidth = 1024, customHeight = 768, useCharacterPositions = true,
        effort = effort, transparentBackground = true)

    @Test fun `actual sampling reset changes only four fields in each model and Medium and supports durable undo`(): Unit = runBlocking(edt) {
        val parent = Files.createTempDirectory("studio-sampling-reset-")
        val root = parent.resolve("data")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { """{"entries":[]}""".toByteArray() })
        try {
            val controller = app.novelAiStudioController
            controller.load()
            val scene = ImageComposeScene(580, 1000, coroutineContext = edt) {
                val draft by controller.draft.collectAsState()
                val scope = rememberCoroutineScope()
                Column { draft?.activeSettings?.let { s -> StudioAdvancedSettings(s) { transform ->
                    scope.launch { controller.edit { it.withActiveSettings(transform(it.activeSettings)) } }
                } } }
            }
            try {
                for ((model, effort) in listOf(NovelAiImageModel.V4_5_FULL to NovelAiEffort.HIGH,
                    NovelAiImageModel.V5_FULL to NovelAiEffort.HIGH, NovelAiImageModel.V5_FULL to NovelAiEffort.MEDIUM)) {
                    controller.edit { it.copy(selectedModel = model, followDefaultNovelAiImageModel = false,
                        stylePrompt = "original style  ", basePrompt = "original scene  ", extraPrompt = "extra  ",
                        negativePrompt = "negative  ", characters = listOf(NovelAiCharacterPromptDraft(id = "person",
                            prompt = "person positive", negativePrompt = "person negative")),
                        continuousModeEnabled = true, continuousTargetCount = 17,
                        v45Settings = settings(NovelAiImageModel.V4_5_FULL, NovelAiEffort.HIGH),
                        v5Settings = settings(NovelAiImageModel.V5_FULL, effort)) }
                    scene.frames()
                    val before = controller.draft.value!!
                    val expected = before.withActiveSettings(before.activeSettings.copy(steps = 23,
                        guidance = if (model == NovelAiImageModel.V4_5_FULL) 5f else 7f,
                        sampler = NovelAiSampler.EULER_ANCESTRAL, cfgRescale = 0f))
                    scene.click("使用 NovelAI 默认采样参数")
                    scene.until { controller.draft.value!!.activeSettings.steps == 23 }
                    assertEquals(expected.content(), controller.draft.value!!.content())
                    assertEquals(expected.content(), NovelAiStudioStateRepository(JsonFileStorage(root)).loadDraft().content())
                    assertTrue(controller.undo())
                    scene.until { controller.draft.value!!.activeSettings.steps == 39 }
                    assertEquals(before.content(), controller.draft.value!!.content())
                    assertTrue(controller.redo())
                    scene.until { controller.draft.value!!.activeSettings.steps == 23 }
                    assertEquals(expected.content(), controller.draft.value!!.content())
                }
                System.getenv("CCB_MEDIA_USAGE_REVIEW_DIR")?.let { directory ->
                    val file = Path.of(directory).resolve("studio-default-sampling.png")
                    Files.createDirectories(file.parent)
                    scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(file, it.bytes) } }
                }
            } finally { scene.close() }
        } finally { app.close(); check(parent.toFile().deleteRecursively()) }
    }

    @Test fun `existing broad reset still resets full generation settings and preserves effort and transparency`() = runBlocking(edt) {
        var draft by mutableStateOf(NovelAiStudioDraft(selectedModel = NovelAiImageModel.V5_FULL,
            basePrompt = "original source", v45Settings = settings(NovelAiImageModel.V4_5_FULL, NovelAiEffort.HIGH),
            v5Settings = settings(NovelAiImageModel.V5_FULL, NovelAiEffort.MEDIUM)))
        val before = draft
        val scene = ImageComposeScene(800, 1400, coroutineContext = edt) { StudioGenerationSettings(draft) { draft = it(draft) } }
        try {
            scene.frames(); scene.click("重置生成参数")
            assertEquals(before.withActiveSettings(NovelAiGenerationDefaults.forModel(NovelAiImageModel.V5_FULL)
                .copy(effort = NovelAiEffort.MEDIUM, transparentBackground = true)), draft)
            assertEquals(0L, draft.activeSettings.seed)
            assertNull(draft.activeSettings.customWidth)
            assertEquals(1, draft.activeSettings.count)
        } finally { scene.close() }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.has(label: String): Boolean =
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true || children.any { it.has(label) }
    private suspend fun ImageComposeScene.frames() { repeat(5) { render(System.nanoTime()).close(); delay(18) } }
    private suspend fun ImageComposeScene.until(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) { render(System.nanoTime()).close(); delay(18) }; frames()
    }
    private suspend fun ImageComposeScene.click(label: String) {
        val node = nodes().first { it.has(label) && it.config.contains(SemanticsActions.OnClick) }
        assertTrue(node.boundsInRoot.width > 0 && node.boundsInRoot.height > 0)
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); frames()
    }
}
