@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopUsageBreakdownPanelTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    @Test fun `statistics pages past thirty select an actual request and expose its numeric attempt without mutation`() = runBlocking(edt) {
        val root = Files.createTempDirectory("usage-breakdown-scene-"); val gate = DesktopDataOperationCoordinator()
        val store = DesktopProviderUsageStore(root, gate); val owner = DesktopProviderUsageOwner(store)
        try {
            val rows = (0 until 37).map { index -> DesktopUsageRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                1, 1, DesktopUsageSource.CHAT, sessionId = "synthetic-session-$index", taskId = "synthetic-task-$index",
                modelId = "model-$index", modelName = "Synthetic model $index", provider = "Synthetic provider",
                accountId = "synthetic-account-$index", startedAt = index + 1L, updatedAt = index + 2L,
                outcome = DesktopUsageOutcome.COMPLETED, counters = DesktopUsageCounters(prompt = index.toLong())) }
            rows.forEach { store.save(it) }
            val original = rows.associate { it.id to Files.readAllBytes(root.resolve("diagnostics/provider-usage/${it.id}.json")) }
            for (width in listOf(420, 1100)) {
                val scroll = ScrollState(0)
                val scene = ImageComposeScene(width, 900, coroutineContext = edt) {
                    Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).verticalScroll(scroll).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) { DesktopProviderUsagePanel(owner) }
                }
                try {
                    await(scene) { !owner.state.value.loading && owner.state.value.totals?.requests == 37 &&
                        owner.state.value.breakdown.page == 0 && owner.state.value.breakdown.dimension == DesktopUsageDimension.MODEL_ACCOUNT }
                    assertTrue(text(scene).contains("37 个分组"))
                    capture(scene, "usage-breakdown-summary-$width")
                    click(scene, "分组：模型与账户"); frames(scene); click(scene, "请求")
                    await(scene) { !owner.state.value.loading && owner.state.value.breakdown.dimension == DesktopUsageDimension.REQUEST }
                    val before = owner.state.value.breakdown.rows.map { it.sample.requestId }.toSet()
                    click(scene, "下一页分组")
                    await(scene) { !owner.state.value.loading && owner.state.value.breakdown.page == 1 }
                    assertTrue(owner.state.value.breakdown.rows.none { it.sample.requestId in before })
                    val selected = owner.state.value.breakdown.rows.first().sample
                    click(scene, "查看各次尝试")
                    await(scene) { !owner.state.value.loading && owner.state.value.breakdown.dimension == DesktopUsageDimension.ATTEMPT && owner.state.value.totals?.requests == 1 }
                    assertEquals(selected.requestId, owner.state.value.breakdown.rows.single().sample.requestId)
                    click(scene, "展开记录详情"); frames(scene)
                    assertTrue(text(scene).contains(selected.requestId)); assertTrue(text(scene).contains(selected.accountId!!))
                    assertTrue(text(scene).contains(selected.sessionId!!)); assertTrue(text(scene).contains(selected.taskId!!))
                    scroll.scrollTo(450); frames(scene); capture(scene, "usage-breakdown-attempt-$width")
                    click(scene, "返回全部请求")
                    await(scene) { !owner.state.value.loading && owner.state.value.totals?.requests == 37 && owner.state.value.breakdown.dimension == DesktopUsageDimension.REQUEST }
                } finally { scene.close() }
            }
            original.forEach { (id, bytes) -> assertContentEquals(bytes, Files.readAllBytes(root.resolve("diagnostics/provider-usage/$id.json"))) }
        } finally { owner.closeAndDrain(); gate.closeAndDrain(); check(root.toFile().deleteRecursively()) }
    }

    private suspend fun frames(scene: ImageComposeScene) { repeat(4) { scene.render(System.nanoTime()).close(); yield(); delay(20) } }
    private suspend fun await(scene: ImageComposeScene, condition: () -> Boolean) { withTimeout(5000) { do { frames(scene) } while (!condition()) } }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun text(scene: ImageComposeScene) = nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString("\n") { it.text }
    private fun click(scene: ImageComposeScene, label: String) {
        fun matches(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true || node.children.any(::matches)
        val node = nodes(scene).first { matches(it) && it.config.contains(SemanticsActions.OnClick) }
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
    }
    private fun capture(scene: ImageComposeScene, name: String) {
        System.getenv("CCB_MEDIA_USAGE_REVIEW_DIR")?.let { directory ->
            val path = Path.of(directory).resolve("$name.png"); Files.createDirectories(path.parent)
            scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(path, it.bytes) } }
        }
    }
}
