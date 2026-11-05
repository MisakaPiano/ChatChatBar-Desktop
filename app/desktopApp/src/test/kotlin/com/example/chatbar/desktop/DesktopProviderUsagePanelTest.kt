@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopProviderUsagePanelTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    @Test fun `narrow and wide statistics distinguish unknown counters and filter actual request source`() = runBlocking(edt) {
        val root = Files.createTempDirectory("usage-panel-"); val gate = DesktopDataOperationCoordinator()
        val store = DesktopProviderUsageStore(root, gate); val owner = DesktopProviderUsageOwner(store)
        try {
            val record = DesktopUsageRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, 1,
                DesktopUsageSource.CHAT, modelId = "model", modelName = "合成模型", startedAt = 1, updatedAt = 1,
                outcome = DesktopUsageOutcome.COMPLETED, counters = DesktopUsageCounters(prompt = 0, completion = 2))
            store.save(record)
            store.save(record.copy(id = UUID.randomUUID().toString(), requestId = UUID.randomUUID().toString(),
                source = DesktopUsageSource.WORLD_BOOK, counters = DesktopUsageCounters()))
            for (width in listOf(420, 1100)) {
                val scene = ImageComposeScene(width, 900, coroutineContext = edt) {
                    Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(16.dp)
                        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DesktopProviderUsagePanel(owner)
                    }
                }
                try {
                    suspend fun frames() { repeat(4) { scene.render().close(); delay(30) } }
                    withTimeout(10_000) { do { frames() } while (owner.state.value.totals?.requests != 2) }
                    fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                    fun all() = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
                    fun text(node: SemanticsNode, label: String): Boolean =
                        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(label) } == true || node.children.any { text(it, label) }
                    assertTrue(all().any { text(it, "输入Token：0（1次未报告）") })
                    System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
                        val file = Path.of(directory).resolve("usage-statistics-$width.png"); Files.createDirectories(file.parent)
                        scene.render().use { image -> image.encodeToData()!!.use { Files.write(file, it.bytes) } }
                    }
                    val chat = all().first { text(it, "对话") && it.config.contains(SemanticsActions.OnClick) }
                    assertTrue(chat.config[SemanticsActions.OnClick].action!!.invoke())
                    withTimeout(10_000) { do { frames() } while (owner.state.value.totals?.requests != 1) }
                    assertEquals(0, owner.state.value.totals!!.prompt.unreportedAttempts)
                    assertEquals(0L, owner.state.value.totals!!.prompt.value)
                } finally { scene.close() }
            }
        } finally { owner.closeAndDrain(); gate.closeAndDrain(); root.toFile().deleteRecursively() }
    }
}
