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
import com.example.chatbar.domain.voice.FishAudioStoredAudio
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopMediaUsagePanelTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `media debug displays actual metadata without LLM sections synthesis text or audio path`() = runBlocking(edt) {
        for (width in listOf(420, 1100)) {
            val owner = DesktopTransportDiagnosticsOwner()
            val recorder = owner.beginFishMedia("session", "s2.1-pro-free", "synthetic-private-key", null,
                DesktopUsageContext(DesktopUsageSource.CHAT, sessionId = "session"), 7)
            recorder.attemptStarted(Request.Builder().url("https://user:password@fixture.invalid/v1/tts?secret=synthetic-private-key")
                .header("Authorization", "Bearer synthetic-private-key")
                .post("synthetic-private-synthesis-text".toRequestBody("application/json".toMediaType())).build())
            recorder.response(200); recorder.audio(FishAudioStoredAudio("synthetic-private-audio-path", 1500, 321))
            recorder.finish(DesktopTaskStatus.COMPLETED)
            val scene = ImageComposeScene(width, 850) { DesktopChatDebugPanel(owner, "session", {}, modifier = Modifier.fillMaxSize()) }
            try {
                frames(scene)
                val visible = allText(scene)
                assertTrue(visible.contains("HTTP 200")); assertTrue(visible.contains("1500")); assertTrue(visible.contains("321"))
                assertFalse(visible.contains("最终请求 JSON")); assertFalse(visible.contains("System 消息")); assertFalse(visible.contains("原始 SSE"))
                for (sensitiveValue in listOf("synthetic-private-key", "synthetic-private-synthesis-text", "synthetic-private-audio-path", "password")) {
                    assertFalse(visible.contains(sensitiveValue))
                }
                capture(scene, "fish-media-debug-$width")
            } finally { scene.close() }
        }
    }

    @Test fun `statistics filter media type and show measured audio units without token mixing`() = runBlocking(edt) {
        val root = Files.createTempDirectory("media-panel-"); val gate = DesktopDataOperationCoordinator()
        val store = DesktopProviderUsageStore(root, gate); val owner = DesktopProviderUsageOwner(store)
        try {
            val token = DesktopUsageRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, 1, DesktopUsageSource.CHAT,
                modelId = "model", modelName = "Synthetic model", startedAt = 1, updatedAt = 1, counters = DesktopUsageCounters(prompt = 7))
            store.save(token)
            store.save(token.copy(id = UUID.randomUUID().toString(), requestId = UUID.randomUUID().toString(), schemaVersion = 2,
                counters = DesktopUsageCounters(), media = DesktopUsageMedia(DesktopUsageMediaKind.FISH_TTS, 3, 1000, 64, 200)))
            for (width in listOf(420, 1100)) {
                val scene = ImageComposeScene(width, 900) {
                    Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        DesktopProviderUsagePanel(owner)
                    }
                }
                try {
                    withTimeout(5000) { do { frames(scene) } while (owner.state.value.totals?.attempts != 2) }
                    fun labeled(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Fish TTS" } == true || node.children.any(::labeled)
                    val media = nodes(scene).first { labeled(it) && it.config.contains(SemanticsActions.OnClick) }
                    media.config[SemanticsActions.OnClick].action!!.invoke()
                    withTimeout(5000) { do { frames(scene) } while (owner.state.value.totals?.attempts != 1) }
                    assertEquals(0, owner.state.value.totals!!.prompt.unreportedAttempts)
                    assertEquals(1000L, owner.state.value.totals!!.media.single().outputAudioDurationMs.value)
                    val visible = allText(scene)
                    assertTrue(visible.contains("1000")); assertTrue(visible.contains("Unicode"))
                    assertFalse(visible.contains("输入Token："))
                    capture(scene, "fish-media-statistics-$width")
                } finally { scene.close() }
            }
        } finally { owner.closeAndDrain(); gate.closeAndDrain(); check(root.toFile().deleteRecursively()) }
    }

    private suspend fun frames(scene: ImageComposeScene) { repeat(4) { scene.render(System.nanoTime()).close(); yield(); delay(20) } }
    private fun allText(scene: ImageComposeScene) = nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString("\n") { it.text }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun capture(scene: ImageComposeScene, name: String) {
        System.getenv("CCB_MEDIA_USAGE_REVIEW_DIR")?.let { directory ->
            val path = Path.of(directory).resolve("$name.png"); Files.createDirectories(path.parent)
            scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(path, it.bytes) } }
        }
    }
}
