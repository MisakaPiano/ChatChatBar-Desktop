@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopTaskCenterPanelTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `narrow and wide task center distinguishes same names and routes exact view stop hide and close actions`() = runBlocking(edt) {
        for (width in listOf(420, 1100)) {
            val root = Files.createTempDirectory("task-panel-")
            val gate = DesktopDataOperationCoordinator(); val center = DesktopTaskCenter()
            val backend = object : DesktopTrayBackend {
                override suspend fun attach(actions: DesktopTrayActions) = true
                override suspend fun notify(kind: DesktopWorkKind, phase: DesktopWorkPhase) {}
                override suspend fun detach() {}
            }
            val tray = DesktopTrayController(center, DesktopSettingsStore(root, gate), backend)
            val rows = MutableStateFlow(listOf("aaaaaaaa-0000", "bbbbbbbb-0000").mapIndexed { index, id ->
                DesktopWorkRow(id, DesktopWorkKind.DOCUMENT, DesktopWorkPhase.RUNNING, (2 - index).toLong(),
                    target = DesktopWorkTarget(DesktopPrimaryRoute.MANAGE, cardId = "card-$index", subjectId = "document-$index"),
                    canStop = true, contextLabel = "同名文档 · " + "用于核对换行的长名称 ".repeat(3))
            })
            val stopped = CompletableDeferred<String>(); center.watch("owner", rows, stop = { stopped.complete(it); true })
            var viewed: DesktopWorkTarget? = null; var closed = 0
            tray.initialize({}, {})
            withTimeout(5000) { center.state.first { it.rows.size == 2 } }
            val scene = ImageComposeScene(width, 760, coroutineContext = edt) {
                DesktopTaskCenterPanel(center, onClose = { closed++ }, onView = { viewed = it }, onHideToTray = tray::hide, tray = tray)
            }
            try {
                frames(scene)
                for (id in listOf("aaaaaaaa-0000", "bbbbbbbb-0000")) assertTrue(nodes(scene).any { hasText(it, "任务 ${id.take(12)}") })
                val views = buttons(scene, "前往页面")
                assertEquals(2, views.size)
                views.forEach { assertTrue(it.boundsInRoot.left >= 0 && it.boundsInRoot.right <= width + 1) }
                views[1].config[SemanticsActions.OnClick].action!!.invoke()
                assertEquals(center.state.value.rows[1].target, viewed)
                buttons(scene, "停止")[0].config[SemanticsActions.OnClick].action!!.invoke()
                withTimeout(5000) { center.state.first { it.rows.first().stopRequested } }
                assertEquals("aaaaaaaa-0000", withTimeout(5000) { stopped.await() }); assertFalse(center.state.value.rows[1].stopRequested)
                frames(scene)
                System.getenv("CCB_TASK_CENTER_REVIEW_DIR")?.let { directory ->
                    val output = Path.of(directory); Files.createDirectories(output)
                    scene.render().use { image -> Files.write(output.resolve("task-center-$width.png"), image.encodeToData()!!.bytes) }
                }
                buttons(scene, "隐藏到托盘").single().config[SemanticsActions.OnClick].action!!.invoke()
                assertFalse(tray.state.value.windowVisible); assertTrue(center.state.value.rows.all { it.active })
                assertEquals(1, closed)
                val close = nodes(scene).single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("关闭任务中心") == true && it.config.contains(SemanticsActions.OnClick) }
                close.config[SemanticsActions.OnClick].action!!.invoke(); assertEquals(2, closed)
            } finally { scene.close(); tray.closeAndDrain(); center.closeAndDrain(); gate.closeAndDrain(); root.toFile().deleteRecursively() }
        }
    }
    private suspend fun frames(scene: ImageComposeScene) { repeat(4) { scene.render().close(); yield(); delay(20) } }
    private fun buttons(scene: ImageComposeScene, label: String) = nodes(scene).filter {
        it.config.contains(SemanticsActions.OnClick) && hasText(it, label)
    }
    private fun hasText(node: SemanticsNode, label: String): Boolean =
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true || node.children.any { hasText(it, label) }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
}
