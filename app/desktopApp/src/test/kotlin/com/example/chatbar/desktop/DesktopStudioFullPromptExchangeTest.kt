@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

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

class DesktopStudioFullPromptExchangeTest {
    @Test fun `paired Android fixture enters the original Desktop controller and exports exact text after confirm`() = runBlocking {
        val f = Fixture()
        try {
            f.controller.load()
            f.controller.edit { it.copy(basePrompt = "paired target", followDefaultNovelAiImageModel = false) }
            val text = javaClass.getResourceAsStream("/interop/ccb-full-prompt-v1.txt")!!.use { it.readBytes().toString(Charsets.UTF_8) }
            val before = Files.readAllBytes(f.file)
            assertTrue(f.controller.inspectFullPrompt(text))
            assertContentEquals(before, Files.readAllBytes(f.file))
            assertTrue(f.controller.applyFullPrompt())
            assertEquals(text, f.controller.copyFullPrompt())
            assertEquals(NovelAiImageModel.V5_FULL, f.controller.state.value.draft.selectedModel)
            assertEquals(listOf(true, false), f.controller.state.value.draft.characters.map { it.enabled })
            f.controller.undo()
            assertEquals("paired target", f.controller.state.value.draft.basePrompt)
        } finally { f.close() }
    }
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private class Fixture {
        val parent = Files.createTempDirectory("full-prompt-ui-")
        val root = Files.createDirectory(parent.resolve("data"))
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { """{"entries":[]}""".toByteArray() })
        val controller = app.novelAiStudioController
        val file = root.resolve("entities/novelai_studio_draft.json")
        suspend fun source(): String {
            controller.load()
            controller.edit { it.copy(stylePrompt = "source style", basePrompt = "source base", negativePrompt = "",
                followDefaultNovelAiImageModel = false, naturalLanguageMode = true,
                characters = listOf(NovelAiCharacterPromptDraft(prompt = "first", negativePrompt = "source negative", center = DesignedCharacterCenter(.2f, .8f))) +
                    List(12) { NovelAiCharacterPromptDraft(prompt = "disabled $it", negativePrompt = "", enabled = false) }) }
            return assertNotNull(controller.copyFullPrompt())
        }
        suspend fun close() { app.close(); check(parent.toFile().deleteRecursively()) }
    }

    @Test fun `original controller full text preview cancellation stale malformed and root refusal preserve effective draft`() = runBlocking {
        val f = Fixture()
        try {
            val source = f.source()
            f.controller.edit { it.copy(basePrompt = "target", negativePrompt = "target negative") }
            val original = Files.readAllBytes(f.file)
            assertTrue(f.controller.inspectFullPrompt(source)); assertContentEquals(original, Files.readAllBytes(f.file))
            f.controller.discardFullPrompt(); assertNull(f.controller.state.value.fullPromptPreview)
            assertFalse(f.controller.inspectFullPrompt("CCB-FULL-PROMPT/2\n")); assertContentEquals(original, Files.readAllBytes(f.file))
            assertTrue(f.controller.inspectFullPrompt(source)); f.controller.edit { it.copy(basePrompt = "newer target") }
            val newer = Files.readAllBytes(f.file)
            assertFalse(f.controller.applyFullPrompt()); assertContentEquals(newer, Files.readAllBytes(f.file))
            f.controller.discardFullPrompt(); assertTrue(f.controller.inspectFullPrompt(source))
            f.app.dataOperationCoordinator.withExclusiveMaintenance { requireRestart() }
            assertFalse(f.controller.applyFullPrompt()); assertContentEquals(newer, Files.readAllBytes(f.file))
            f.app.close(); assertNull(f.controller.state.value.fullPromptPreview)
            assertTrue(f.app.taskRuntime.tasks.value.isEmpty())
        } finally { f.close() }
    }

    @Test fun `actual full text content in narrow and wide windows keeps explicit apply cancel and original undo without clipboard or provider calls`() = runBlocking(edt) {
        val f = Fixture()
        try {
            val source = f.source()
            for (width in listOf(420, 1100)) {
                val height = if (width == 420) 420 else 900
                f.controller.edit { it.copy(basePrompt = "target", negativePrompt = "target negative", continuousModeEnabled = true,
                    continuousTargetCount = 100, v45Settings = it.v45Settings.copy(steps = 17), imageDescription = "retained AI input") }
                val original = Files.readAllBytes(f.file)
                assertTrue(f.controller.inspectFullPrompt(source))
                var busy by mutableStateOf(false)
                var closed = false
                val scene = ImageComposeScene(width, height, coroutineContext = edt) {
                    val state by f.controller.state.collectAsState()
                    val scope = rememberCoroutineScope()
                    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DesktopStudioFullPromptContent(state.fullPromptPreview, busy, "", state.status,
                            onCopy = { error("No clipboard write expected") }, onRead = { error("No clipboard read expected") },
                            onApply = { scope.launch { f.controller.applyFullPrompt() } },
                            onCancel = { scope.launch { f.controller.discardFullPrompt() } }, onClose = { closed = true })
                    }
                }
                fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
                fun matches(n: SemanticsNode, text: String): Boolean = n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true || n.children.any { matches(it, text) }
                fun action(text: String) = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }.first { matches(it, text) && it.config.contains(SemanticsActions.OnClick) }
                fun click(text: String) {
                    val node = action(text); val b = node.boundsInRoot
                    assertTrue(b.left >= 0 && b.top >= 0 && b.right <= width && b.bottom <= height && b.height >= 32)
                    assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
                }
                suspend fun frames() { repeat(4) { scene.render().close(); delay(25) } }
                try {
                    frames(); busy = true; frames(); assertTrue(action("确认替换完整 Prompt").config.contains(SemanticsProperties.Disabled))
                    busy = false; frames(); assertContentEquals(original, Files.readAllBytes(f.file))
                    System.getenv("CCB_MEDIA_USAGE_REVIEW_DIR")?.let { directory ->
                        val path = Path.of(directory).resolve("full-prompt-preview-$width.png"); Files.createDirectories(path.parent)
                        scene.render().use { image -> image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { Files.write(path, it.bytes) } }
                    }
                    click("取消文本导入"); withTimeout(5000) { while (f.controller.state.value.fullPromptPreview != null) frames() }
                    assertContentEquals(original, Files.readAllBytes(f.file))
                    assertTrue(f.controller.inspectFullPrompt(source)); frames(); click("确认替换完整 Prompt")
                    withTimeout(5000) { while (f.controller.state.value.fullPromptPreview != null) frames() }
                    val result = assertNotNull(f.controller.draft.value)
                    assertEquals("source base", result.basePrompt); assertEquals("", result.negativePrompt)
                    assertEquals("source negative", result.characters.first().negativePrompt); assertEquals(1, result.characters.count { it.enabled })
                    assertTrue(result.continuousModeEnabled); assertEquals(100, result.continuousTargetCount)
                    assertEquals(17, result.v45Settings.steps); assertEquals("retained AI input", result.imageDescription)
                    assertTrue(f.controller.undo()); assertEquals("target", f.controller.draft.value!!.basePrompt)
                    click("关闭"); assertTrue(closed); assertTrue(f.app.taskRuntime.tasks.value.isEmpty())
                } finally { scene.close() }
            }
        } finally { f.close() }
    }
}
