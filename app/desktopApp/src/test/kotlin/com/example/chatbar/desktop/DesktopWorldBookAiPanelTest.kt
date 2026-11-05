@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.worldbook.WorldBookAiOperation
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopWorldBookAiPanelTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `narrow and wide create select apply fill preserve source until normal save`() = runBlocking(edt) {
        for (width in listOf(420, 1100)) {
            val parent = Files.createTempDirectory("world-book-ai-ui-").toAbsolutePath().normalize()
            val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
                parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
                    check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray()
                })
            try {
                val seed = WorldBook("book", "合成世界书", entries = listOf(WorldBookEntry("original", "旧条目", listOf("旧词"), "原正文")))
                val model = ModelConfig("model", "合成模型", "http://127.0.0.1:1/v1", "", "synthetic", createdAt = 1)
                app.worldBookRepository.save(seed); app.modelRepository.saveModel(model)
                val book = assertNotNull(app.worldBookRepository.getById(seed.id))
                app.settingsRepository.updateAppSettings { it.copy(defaultModelId = model.id, allowCleartextModelApi = true,
                    worldBookAiResearchSourceMode = CharacterResearchSourceMode.NONE) }
                val editor = app.worldBookEditorController; editor.openExisting(book.id); editor.ai?.closeAndDrain()
                val ai = DesktopWorldBookAiController(editor, app.settingsRepository, app.effectiveModelResolver,
                    app.taskRuntime, app.dataOperationCoordinator, stream = { op, _, messages, _, _ ->
                        val raw = if (op == WorldBookAiOperation.CREATE)
                            """{"entries":[{"name":"山脉","keys":["群峰"]},{"name":"河流","keys":["清溪"]}],"hasMore":false}"""
                        else {
                            val ids = Json.parseToJsonElement(messages.last().content.jsonPrimitive.content).jsonObject.getValue("targets").jsonArray
                            buildJsonObject { put("entries", buildJsonArray { ids.forEach { target ->
                                add(buildJsonObject { put("targetId", target.jsonObject.getValue("targetId")); put("content", "合成群峰环绕城镇，保持明确的地理关系。") })
                            } }) }.toString()
                        }
                        flowOf(StreamEvent.Delta(raw), StreamEvent.Done)
                    }, research = { worldBookOfflineResearch }).also { editor.ai = it }
                val activity = DesktopModalActivity()
                val scene = ImageComposeScene(width, 900, coroutineContext = edt) {
                    CompositionLocalProvider(LocalDesktopModalActivity provides activity) { DesktopWorldBookEditorOverlay(editor) }
                }
                try {
                    scene.until { has("AI 创建条目") }; scene.click("AI 创建条目")
                    scene.until { ai.state.value.open && !ai.state.value.loading }
                    ai.setRequest("为合成世界书规划地理条目")
                    scene.click("重新生成"); scene.until { !ai.state.value.busy && ai.state.value.plans.size == 2 }
                    assertNull(ai.state.value.error); assertEquals(book, editor.state.value.book)
                    scene.shot("world-book-ai-create-$width.png")
                    scene.click("河流"); scene.until { ai.state.value.selectedIds.size == 1 }
                    scene.click("应用选中 1 项"); scene.until { !ai.state.value.open }
                    assertEquals(listOf("旧条目", "山脉"), editor.state.value.book!!.entries.map { it.name })
                    assertEquals(book, app.worldBookRepository.getById(book.id))
                    scene.click("AI 填充空白正文"); scene.until { ai.state.value.open && !ai.state.value.loading }
                    scene.click("重新生成"); scene.until { !ai.state.value.busy && ai.state.value.contents.size == 1 }
                    assertNull(ai.state.value.error); assertTrue(editor.state.value.book!!.entries.last().content.isBlank())
                    scene.shot("world-book-ai-fill-$width.png")
                    scene.click("应用选中 1 项"); scene.until { !ai.state.value.open }
                    assertTrue(editor.state.value.book!!.entries.last().content.isNotBlank())
                    assertEquals(book, app.worldBookRepository.getById(book.id)); assertTrue(editor.save())
                    assertEquals(2, app.worldBookRepository.getById(book.id)!!.entries.size)
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
