@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopHistoryBatchExportSceneTest {
    private val edt = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block) }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun text(node: SemanticsNode, value: String) = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
    private suspend fun frames(scene: ImageComposeScene) { repeat(5) { scene.render(System.nanoTime()).close(); yield(); delay(10) } }
    private suspend fun until(scene: ImageComposeScene, condition: () -> Boolean) = withTimeout(8000) { do { frames(scene) } while (!condition()) }
    private suspend fun click(scene: ImageComposeScene, label: String) {
        clickNode(scene, nodes(scene).first { text(it, label) })
    }
    private suspend fun clickNode(scene: ImageComposeScene, found: SemanticsNode) {
        val target = generateSequence(found) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
        assertFalse(target.config.contains(SemanticsProperties.Disabled)); assertTrue(target.config[SemanticsActions.OnClick].action!!.invoke()); frames(scene)
    }
    private fun shot(scene: ImageComposeScene, name: String) {
        (System.getenv("CCB_FORMAT_AI_REVIEW_DIR") ?: System.getProperty("chatbar.test.screenshot.dir"))?.let { directory ->
            Files.createDirectories(Path.of(directory)); scene.render(System.nanoTime()).use { it.encodeToData()?.use { image ->
                Files.write(Path.of(directory, "$name.png"), image.bytes)
            } }
        }
    }

    @Test fun `ordinary history opens name preview and remaining conflict control without losing selection draft or original operations`() = runBlocking(edt) {
        val out = Files.createTempDirectory("ccb-batch-scene-output-")
        var picks = 0
        val fixture = FinalProductDesignFixture(directoryPicker = DesktopDirectoryPicker { picks++; out })
        try {
            fixture.initialize(); fixture.seedLocalImages()
            val controller = fixture.container.novelAiStudioController
            val history = controller.history.first(); val draft = controller.draft.value
            val scene = ImageComposeScene(1100, 900, coroutineContext = edt) {
                Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.background)) {
                    DesktopStudioHistory(controller, onApply = { _, _, _ -> }, onPreview = { _, _ -> }, onUse = { _, _ -> })
                }
            }
            try {
                until(scene) { nodes(scene).count { text(it, "选择") } >= 2 }
                val originalOrder = controller.history.first().flatMap { entry -> entry.images.map { entry.id to it.path } }
                // The ordinary selection callback must retain clicks, not re-sort by gallery order.
                clickNode(scene, nodes(scene).filter { text(it, "选择") }[1])
                click(scene, "选择")
                assertEquals(2, controller.state.value.selected.size)
                val selected = controller.state.value.selected.toList()
                assertEquals(listOf(originalOrder[1], originalOrder[0]), selected.map { it.entryId to it.imagePath })
                click(scene, "批量保存")
                nodes(scene).last { it.config.contains(SemanticsActions.SetText) }.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("demo")); frames(scene)
                Files.writeString(out.resolve("demo_1.png"), "old-one"); Files.writeString(out.resolve("demo_2.png"), "old-two")
                click(scene, "选择目录并预览")
                until(scene) { controller.historyExport.state.value.plan != null && !controller.historyExport.state.value.busy }
                assertEquals(1, picks); assertEquals(2, Files.list(out).use { it.count() }.toInt())
                assertEquals(2, assertNotNull(controller.historyExport.state.value.plan).conflicts.size)
                click(scene, "将此选择应用到后续同名文件"); click(scene, "跳过")
                assertEquals(2, nodes(scene).count { text(it, "✓ 跳过") })
                shot(scene, "history-batch-conflict-preview")
                click(scene, "确认保存"); until(scene) { !controller.historyExport.state.value.busy && controller.historyExport.state.value.plan == null }
                assertEquals("old-one", Files.readString(out.resolve("demo_1.png"))); assertEquals("old-two", Files.readString(out.resolve("demo_2.png")))
                nodes(scene).last { it.config.contains(SemanticsActions.SetText) }.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("copy")); frames(scene)
                click(scene, "选择目录并预览"); until(scene) { controller.historyExport.state.value.plan != null && !controller.historyExport.state.value.busy }
                assertFalse(Files.exists(out.resolve("copy_1.png")))
                click(scene, "确认保存"); until(scene) { !controller.historyExport.state.value.busy && controller.historyExport.state.value.plan == null }
                selected.forEachIndexed { index, key -> assertContentEquals(Files.readAllBytes(fixture.container.appDataRoot.resolve(key.imagePath)), Files.readAllBytes(out.resolve("copy_${index + 1}.png"))) }
                assertEquals(history, controller.history.first()); assertEquals(draft, controller.draft.value); assertEquals(selected.toSet(), controller.state.value.selected)
                shot(scene, "history-batch-saved")
                val close = nodes(scene).first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("关闭批量保存") == true }
                close.config[SemanticsActions.OnClick].action!!.invoke(); frames(scene)
                assertTrue(nodes(scene).any { text(it, "删除选中 2") }); assertTrue(nodes(scene).any { text(it, "配方详情") })
                assertTrue(fixture.requests.isEmpty())
            } finally { scene.close() }
        } finally { fixture.close(); out.toFile().deleteRecursively() }
    }
}
