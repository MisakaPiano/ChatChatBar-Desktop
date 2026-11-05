@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import com.example.chatbar.domain.card.FormatCardPackage
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopExternalImportHostTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `global host can hide and reopen then import exactly one head in narrow and wide layouts`(): Unit = runBlocking(edt) {
        for (width in listOf(420, 1100)) {
            val root = Files.createTempDirectory("external-host-")
            val secret = InMemoryDesktopSecretStore()
            val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(Files.createDirectory(root.resolve("data")),
                DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")), secretStoreFactory = { secret },
                bundledAssets = { """{"entries":[]}""".toByteArray() })
            val queue = DesktopExternalFileQueue(root.resolve("pending.json"))
            val file = Files.writeString(root.resolve("synthetic long file name ".repeat(7) + ".json"), app.transferJson.encodeToString(
                FormatCardPackage.serializer(), FormatCardPackage(name = "UI synthetic", content = "Synthetic content")))
            val original = Files.readAllBytes(file)
            queue.submit(UUID.randomUUID().toString(), listOf(file))
            val controller = DesktopExternalImportController(queue, app.dataOperationCoordinator, app.createTypedTransferController(),
                app.createModelTemplateTransferController(), app.mergeImportController, app.saveSlotController,
                app.novelAiStudioController, imageBusy = { false })
            val scene = ImageComposeScene(width, 700, coroutineContext = edt) {
                Box(Modifier.fillMaxSize()) {
                    Column { DesktopExternalImportNotice(controller) }
                    DesktopExternalImportHost(controller, emptyList(), {}, {})
                }
            }
            try {
                scene.render().close()
                withTimeout(5000) { controller.state.first { it.phase == DesktopExternalImportPhase.READY && !it.busy } }
                frames(scene)
                val import = clickNode(scene, "导入当前文件")
                assertTrue(import.boundsInRoot.left >= 0 && import.boundsInRoot.right <= width + 1)
                assertTrue(import.boundsInRoot.top >= 0 && import.boundsInRoot.bottom <= 701)
                val notice = nodes(scene).first { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("待处理文件") } == true }
                val layout = mutableListOf<TextLayoutResult>()
                notice.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layout)
                assertEquals(1, layout.single().lineCount)
                val review = System.getenv("CCB_EXTERNAL_IMPORT_REVIEW_DIR")?.let(Path::of)
                if (review != null) {
                    Files.createDirectories(review)
                    scene.render().use { image -> Files.write(review.resolve("external-import-$width.png"), image.encodeToData()!!.bytes) }
                }
                clickNode(scene, "暂时收起导入队列").config[SemanticsActions.OnClick].action!!.invoke(); frames(scene)
                assertFalse(controller.state.value.shown)
                assertEquals(1, queue.state.value.files.size)
                assertTrue(app.formatCardRepository.getAll().isEmpty())
                clickNode(scene, "查看导入队列").config[SemanticsActions.OnClick].action!!.invoke(); frames(scene)
                clickNode(scene, "导入当前文件").config[SemanticsActions.OnClick].action!!.invoke()
                withTimeout(5000) { controller.state.first { it.phase == DesktopExternalImportPhase.COMPLETE && !it.busy } }
                frames(scene)
                assertEquals("UI synthetic", app.formatCardRepository.getAll().single().name)
                assertEquals(1, queue.state.value.files.size)
                clickNode(scene, "完成，处理下一项").config[SemanticsActions.OnClick].action!!.invoke()
                withTimeout(5000) { queue.state.first { it.files.isEmpty() } }
                assertContentEquals(original, Files.readAllBytes(file))
            } finally { scene.close(); controller.closeAndDrain(); app.close(); root.toFile().deleteRecursively() }
        }
    }
    private suspend fun frames(scene: ImageComposeScene) { repeat(4) { scene.render().close(); yield(); delay(20) } }
    private fun clickNode(scene: ImageComposeScene, label: String): SemanticsNode = nodes(scene).first { node ->
        node.config.contains(SemanticsActions.OnClick) &&
            (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true || hasText(node, label))
    }.also { assertFalse(it.config.contains(SemanticsProperties.Disabled)) }
    private fun hasText(node: SemanticsNode, label: String): Boolean =
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true || node.children.any { hasText(it, label) }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
}
