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
import androidx.compose.runtime.*
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
    @Test fun `image log entry opens and closes original overlay and clears only its actual group without numeric writes`() = runBlocking(edt) {
        val root = Files.createTempDirectory("image-log-entry-"); val gate = DesktopDataOperationCoordinator()
        val store = DesktopProviderUsageStore(root, gate)
        val record = DesktopUsageRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, 1,
            DesktopUsageSource.OTHER, modelId = "image", modelName = "Synthetic image", startedAt = 1, updatedAt = 1,
            schemaVersion = 2, media = DesktopUsageMedia(DesktopUsageMediaKind.NAI_IMAGE_GENERATION,
                requestedImages = 1, outputImages = 1, outputImageBytes = 64))
        try {
            store.save(record)
            val file = root.resolve("diagnostics/provider-usage/${record.id}.json"); val original = Files.readAllBytes(file)
            for (width in listOf(330, 1100)) {
                val logs = DesktopTransportDiagnosticsOwner()
                for (group in listOf("actual-image-task", "another-task")) logs.addMedia(DesktopTransportDiagnosticEntry(
                    group, group, "NovelAI", "synthetic", 1, image = DesktopImageRequestEvidence(1, "POST", 1)))
                var group by mutableStateOf<String?>("actual-image-task")
                val scene = ImageComposeScene(width, 650) {
                    Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background)) { DesktopImageLogEntry(logs, group) }
                }
                fun click(label: String) {
                    fun labeled(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
                        node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true || node.children.any(::labeled)
                    val node = nodes(scene).first { labeled(it) && it.config.contains(SemanticsActions.OnClick) }
                    assertTrue(node.boundsInRoot.left >= 0 && node.boundsInRoot.right <= width && node.boundsInRoot.top >= 0 && node.boundsInRoot.bottom <= 650,
                        "$width/$label ${node.boundsInRoot}")
                    node.config[SemanticsActions.OnClick].action!!.invoke()
                }
                try {
                    frames(scene); click("生图日志"); frames(scene)
                    assertTrue(allText(scene).contains("生图请求日志")); assertTrue(allText(scene).contains("实际 NovelAI 生图请求"))
                    assertEquals(2, logs.entries.value.size)
                    capture(scene, "image-log-entry-$width")
                    click("关闭调试日志"); frames(scene); assertFalse(allText(scene).contains("生图请求日志"))
                    click("生图日志"); frames(scene); click("清空本组生图日志"); frames(scene); click("确认清空日志"); frames(scene)
                    assertEquals(listOf("another-task"), logs.entries.value.map { it.sessionId })
                    assertTrue(allText(scene).contains("本组暂无生图请求记录"))
                    click("关闭调试日志"); frames(scene)
                    group = null; frames(scene)
                    assertContentEquals(original, Files.readAllBytes(file)); assertEquals(listOf(record), store.records())
                } finally { scene.close() }
            }
        } finally { gate.closeAndDrain(); check(root.toFile().deleteRecursively()) }
    }
    @Test fun `image metadata and statistics keep image counts separate from tokens audio and billing`() = runBlocking(edt) {
        val root = Files.createTempDirectory("image-media-panel-"); val gate = DesktopDataOperationCoordinator()
        val store = DesktopProviderUsageStore(root, gate); val usage = DesktopProviderUsageOwner(store)
        val logs = DesktopTransportDiagnosticsOwner()
        logs.addMedia(DesktopTransportDiagnosticEntry("image-task", "session", "NovelAI", "synthetic-model", 1,
            status = DesktopTaskStatus.COMPLETED, image = DesktopImageRequestEvidence(1, "POST", 2, 200, 2, 512)))
        try {
            store.save(DesktopUsageRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, 1,
                DesktopUsageSource.CHAT, modelId = "image", modelName = "Synthetic image", startedAt = 1, updatedAt = 1,
                schemaVersion = 2, media = DesktopUsageMedia(DesktopUsageMediaKind.NAI_IMAGE_GENERATION,
                    requestedImages = 2, outputImages = 2, outputImageBytes = 512, httpStatus = 200)))
            for (width in listOf(420, 1100)) {
                val debug = ImageComposeScene(width, 850) { DesktopChatDebugPanel(logs, "session", {}, modifier = Modifier.fillMaxSize()) }
                try {
                    frames(debug)
                    val text = allText(debug)
                    assertTrue(text.contains("NovelAI 生图")); assertTrue(text.contains("512")); assertTrue(text.contains("HTTP 200"))
                    assertFalse(text.contains("最终请求 JSON")); assertFalse(text.contains("有效音频时长")); assertFalse(text.contains("服务端 Token"))
                    capture(debug, "image-media-debug-$width")
                } finally { debug.close() }
                val scene = ImageComposeScene(width, 1000) {
                    Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        DesktopProviderUsagePanel(usage)
                    }
                }
                try {
                    withTimeout(5000) { do { frames(scene) } while (usage.state.value.totals?.attempts != 1) }
                    val text = allText(scene)
                    assertTrue(text.contains("有效返回图片数：2")); assertTrue(text.contains("有效图片字节：512"))
                    assertFalse(text.contains("输入Token：")); assertFalse(text.contains("有效音频时长（毫秒）"))
                    assertEquals(DesktopUsageMediaKind.NAI_IMAGE_GENERATION, usage.state.value.totals!!.media.single().kind)
                    capture(scene, "image-media-statistics-$width")
                } finally { scene.close() }
            }
        } finally { usage.closeAndDrain(); gate.closeAndDrain(); check(root.toFile().deleteRecursively()) }
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
