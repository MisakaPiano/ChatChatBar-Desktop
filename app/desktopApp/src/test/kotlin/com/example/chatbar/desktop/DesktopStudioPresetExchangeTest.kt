@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopStudioPresetExchangeTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `narrow and wide original preset content keeps fixed confirm controls and performs real cancel apply and undo without generation`() = runBlocking(edt) {
        val parent = Files.createTempDirectory("preset-exchange-ui-")
        val root = Files.createDirectory(parent.resolve("data"))
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { """{"entries":[]}""".toByteArray() })
        val controller = app.novelAiStudioController
        val file = parent.resolve("synthetic.ccbpreset")
        try {
            controller.load()
            controller.edit { it.copy(basePrompt = "Source base", negativePrompt = "Source negative", stylePrompt = "Style ".repeat(80),
                continuousModeEnabled = false, followDefaultNovelAiImageModel = false,
                characters = listOf(NovelAiCharacterPromptDraft(prompt = "Person", negativePrompt = "Person negative", center = DesignedCharacterCenter(.2f, .8f)))) }
            assertTrue(controller.exportGenerationPreset(file))
            for (width in listOf(420, 1100)) {
                controller.edit { it.copy(basePrompt = "Target base", continuousModeEnabled = true, continuousTargetCount = 100) }
                val original = Files.readAllBytes(root.resolve("entities/novelai_studio_draft.json"))
                assertTrue(controller.inspectGenerationPreset(file))
                var businessBusy by mutableStateOf(false)
                var closed = false
                val scene = ImageComposeScene(width, 900, coroutineContext = edt) {
                    val state by controller.state.collectAsState()
                    val scope = rememberCoroutineScope()
                    Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DesktopStudioPresetExchangeContent(state.presetPreview, businessBusy, false, false, "", state.status,
                            onExport = { error("No file picker expected") }, onInspect = { error("Already inspected") },
                            onApply = { scope.launch { controller.applyGenerationPreset() } },
                            onDiscard = { scope.launch { controller.discardGenerationPreset() } }, onStop = {}, onClose = { closed = true })
                    }
                }
                fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                fun all() = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
                fun label(node: SemanticsNode, text: String): Boolean = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
                    node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true || node.children.any { label(it, text) }
                fun action(text: String) = all().first { label(it, text) && it.config.contains(SemanticsActions.OnClick) }
                fun click(text: String) {
                    val node = action(text)
                    assertTrue(node.boundsInRoot.left >= 0 && node.boundsInRoot.top >= 0 &&
                        node.boundsInRoot.right <= width && node.boundsInRoot.bottom <= 900, "$width/$text ${node.boundsInRoot}")
                    assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
                }
                suspend fun frames() { repeat(5) { scene.render().close(); delay(30) } }
                try {
                    frames(); businessBusy = true; frames()
                    assertTrue(action("确认替换生成草稿").config.contains(SemanticsProperties.Disabled))
                    businessBusy = false; frames()
                    assertContentEquals(original, Files.readAllBytes(root.resolve("entities/novelai_studio_draft.json")))
                    System.getenv("CCB_MEDIA_USAGE_REVIEW_DIR")?.let { directory ->
                        val output = Path.of(directory).resolve("generation-preset-preview-$width.png")
                        Files.createDirectories(output.parent)
                        scene.render().use { image -> image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { Files.write(output, it.bytes) } }
                    }
                    click("取消导入")
                    withTimeout(5000) { while (controller.state.value.presetPreview != null) frames() }
                    assertContentEquals(original, Files.readAllBytes(root.resolve("entities/novelai_studio_draft.json")))
                    assertTrue(controller.inspectGenerationPreset(file)); frames(); click("确认替换生成草稿")
                    withTimeout(5000) { while (controller.state.value.presetOutcome != DesktopPresetApplyOutcome.APPLIED) frames() }
                    assertEquals("Source base", controller.draft.value!!.basePrompt); assertFalse(controller.draft.value!!.continuousModeEnabled)
                    assertTrue(controller.undo()); assertEquals("Target base", controller.draft.value!!.basePrompt)
                    click("关闭"); assertTrue(closed); assertTrue(app.taskRuntime.tasks.value.isEmpty())
                } finally { scene.close() }
            }
        } finally { app.close(); check(parent.toFile().deleteRecursively()) }
    }
}
