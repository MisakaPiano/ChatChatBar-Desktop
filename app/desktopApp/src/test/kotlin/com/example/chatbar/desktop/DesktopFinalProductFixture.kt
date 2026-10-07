package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow

/** Test-source-only manual fixture. No real transports or credentials; shared Designer/runner/repositories. */
internal class FinalProductDesignFixture {
    @Volatile var failure: DesktopDesignFailure? = null
    val requests = java.util.concurrent.CopyOnWriteArrayList<List<ChatApiMessage>>()
    val transport = object : NovelAiTextTransport {
        override fun streamText(stage: NovelAiTextStage, messages: List<ChatApiMessage>, modelConfig: ModelConfig) = flow {
            requests.add(messages)
            failure?.let { throw DesktopDesignException(it) }
            val reply = if (stage == NovelAiTextStage.PLAN)
                """{"sceneDescription":"窗边阅读的成年旅人，柔和阳光。","queries":[]}"""
            else """{"baseCaption":"sunlit reading room, watercolor","characters":[{"caption":"adult traveler, reading a book"}]}"""
            reply.chunked(12).forEach { delay(5); emit(StreamEvent.Delta(it)) }
            emit(StreamEvent.Done)
        }
    }
    val designer = NovelAiPromptDesigner(transport, NovelAiTagResearchService(LlmNovelAiTagSearchPlanner(transport),
        object : NovelAiTagSearchClient { override suspend fun search(query: String) = NovelAiTagSearchOutcome(query, emptyList()) }),
        NovelAiPromptPostProcessor(emptyList()))
    val root = Files.createTempDirectory("ccb-final-product-local-")
    val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"),
        DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")),
        secretStoreFactory = { InMemoryDesktopSecretStore() }, novelAiDesigner = designer)
    suspend fun initialize() {
        container.modelRepository.saveModel(ModelConfig("local-design", "Local fake design — no network", "https://fixture.invalid", "", "fixture", createdAt = 1))
        container.novelAiStudioController.load()
        container.novelAiStudioController.edit { it.copy(aiDesignModelId = "local-design", imageDescription = "窗边阅读的成年旅人", basePrompt = "original studio", aiDesignNaturalLanguageMode = true) }
    }
    suspend fun seedLocalImages() {
        val paths = (1..8).map { index ->
            val raster = java.awt.image.BufferedImage(600, 800, java.awt.image.BufferedImage.TYPE_INT_RGB)
            raster.createGraphics().let { g ->
                g.color = java.awt.Color(60 + index * 10, 110 + index * 8, 180); g.fillRect(0, 0, 600, 800)
                g.color = java.awt.Color(235, 225, 170); g.fillOval(330, 130, 150, 150)
                g.color = java.awt.Color(50, 90, 70); g.fillOval(-90, 510, 500, 550); g.fillOval(200, 450, 620, 550)
                g.color = java.awt.Color.WHITE; g.drawString("LOCAL FIXTURE $index", 30, 40); g.dispose()
            }
            val path = "images/local-fixture-$index.png"
            val file = container.appDataRoot.resolve(path); Files.createDirectories(file.parent)
            Files.write(file, DesktopImageEditing.png(raster)); NovelAiGenerationHistoryImage(path, index.toLong())
        }
        container.novelAiStudioController.repository.saveHistory(NovelAiGenerationHistoryEntry(images = paths))
        container.novelAiStudioController.load()
    }
    suspend fun idle() = withTimeout(10_000) {
        while (container.taskRuntime.tasks.value.any { it.status == DesktopTaskStatus.RUNNING }) delay(10)
    }
    suspend fun close() { container.close(); root.toFile().deleteRecursively() }
}

object DesktopFinalProductFixture {
    @JvmStatic fun main(args: Array<String>) {
        val fixture = FinalProductDesignFixture()
        runBlocking { fixture.initialize(); fixture.seedLocalImages() }
        try { application {
            Window(onCloseRequest = ::exitApplication, title = "Phase 7 · Local fake AI Design · No network", state = rememberWindowState(width = 1280.dp, height = 850.dp)) {
                var failure by remember { mutableStateOf<DesktopDesignFailure?>(null) }
                Column(Modifier.fillMaxSize()) {
                    StatusText("本地测试：打开 AI 设计，正常输入中文。切换下方响应后发送 / 重试。关闭自动清除临时 profile。")
                    CompactChoice("Fake response", listOf<DesktopDesignFailure?>(null) + DesktopDesignFailure.entries, failure,
                        { it?.name ?: "SUCCESS" }) { failure = it; fixture.failure = it }
                    Box(Modifier.weight(1f)) { DesktopNovelAiStudioPanel(fixture.container.novelAiStudioController) }
                }
            }
        } } finally { runBlocking { fixture.close() } }
    }
}
