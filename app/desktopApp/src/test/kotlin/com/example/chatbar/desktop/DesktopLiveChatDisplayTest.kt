@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Uses the actual Panel effects, controller, transport and durable repository, with synthetic text. */
class DesktopLiveChatDisplayTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun texts(scene: ImageComposeScene): List<String> = nodes(scene).flatMap {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { value -> value.text }
    }
    private suspend fun frame(scene: ImageComposeScene) { scene.render(System.nanoTime()).close(); yield(); delay(15) }
    private suspend fun await(scene: ImageComposeScene, condition: () -> Boolean) {
        withTimeout(8_000) { while (!condition()) frame(scene) }
        repeat(3) { frame(scene) }
    }
    private suspend fun configure(app: DesktopAppContainer, baseUrl: String): String {
        val card = CharacterCard.create("Fixture", greeting = "")
        app.characterRepository.save(card)
        app.modelRepository.saveModel(ModelConfig("model", "Fixture", baseUrl, "synthetic-key", "fixture-model", createdAt = 0))
        app.settingsRepository.saveAppSettings(AppSettings(defaultModelId = "model", allowCleartextModelApi = true, ragInjectionMode = "OFF"))
        val session = app.characterSessionService.createSessionForCharacter(card.id)
        app.chatRepository.updateSession(checkNotNull(app.chatRepository.getSession(session)).copy(longTermMemoryEnabled = false))
        app.primaryChatController.refresh(); app.primaryChatController.selectSession(session)
        return session
    }
    private suspend fun fixture(block: suspend (DesktopAppContainer) -> Unit) {
        val root = Files.createTempDirectory("live-chat-display-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
            root.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { """{"entries":[]}""".toByteArray() })
        try { block(app) } finally { app.close(); root.toFile().deleteRecursively() }
    }
    private fun delta(content: String, reasoning: String = "", finish: Boolean = false): String = buildJsonObject {
        putJsonArray("choices") { add(buildJsonObject {
            putJsonObject("delta") { put("content", content); put("reasoning_content", reasoning) }
            if (finish) put("finish_reason", "stop")
        }) }
    }.toString()

    @Test fun `durable USER appears before response and remains once after completion stop error and session switch`() = runBlocking(edt) {
        for (outcome in listOf("COMPLETE", "STOP", "ERROR")) fixture { app ->
            MockWebServer().use { server ->
                val arrived = CountDownLatch(1); val release = CountDownLatch(1)
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        arrived.countDown(); check(release.await(30, TimeUnit.SECONDS))
                        return if (outcome == "ERROR") MockResponse().setResponseCode(400).setBody("synthetic rejection") else
                            MockResponse().setHeader("Content-Type", "text/event-stream")
                                .setBody("data: ${delta("Fixture reply", finish = true)}\n\ndata: [DONE]\n\n")
                    }
                }
                server.start()
                val session = configure(app, server.url("/v1").toString())
                val controller = app.primaryChatController
                val scene = ImageComposeScene(1200, 900, coroutineContext = edt) {
                    DesktopPrimaryChatPanel(controller, DesktopShellSize.WIDE, remember { DesktopComposerLayoutState() })
                }
                try {
                    repeat(6) { frame(scene) }
                    if (outcome == "COMPLETE") controller.editSessionSettings { it.copy(supplementarySetting = "unsaved fixture setting") }
                    val original = "本轮完整输入-".repeat(420)
                    controller.editComposer(original)
                    val id = assertNotNull(controller.send())
                    await(scene) { arrived.count == 0L }
                    val durable = app.chatRepository.getMessagesReadOnly(session).single { it.role == MessageRole.USER }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                    while (System.nanoTime() < deadline && controller.state.value.messages.none { it.id == durable.id }) frame(scene)
                    assertEquals(DesktopTaskStatus.RUNNING, app.taskRuntime.tasks.value.single { it.taskId == id }.status)
                    assertTrue(controller.state.value.messages.any { it.id == durable.id && it.content == original }, "LIVE_USER_NOT_VISIBLE_BEFORE_REPLY_COMPLETES")
                    await(scene) { texts(scene).any { it.contains(original) } }
                    assertTrue(texts(scene).any { it.contains(original) }, "LIVE_USER_BUBBLE_NOT_VISIBLE_BEFORE_REPLY_COMPLETES")
                    if (outcome == "COMPLETE") {
                        assertTrue(controller.state.value.sessionSettingsDirty)
                        assertEquals("unsaved fixture setting", controller.state.value.sessionSettingsDraft?.supplementarySetting)
                    }
                    if (outcome == "STOP") {
                        val otherCard = CharacterCard.create("Other fixture", greeting = "")
                        app.characterRepository.save(otherCard)
                        val other = app.characterSessionService.createSessionForCharacter(otherCard.id)
                        controller.selectSession(other); repeat(3) { frame(scene) }
                        assertTrue(controller.state.value.messages.none { it.id == durable.id })
                        controller.selectSession(session); repeat(3) { frame(scene) }
                        assertTrue(controller.state.value.messages.any { it.id == durable.id })
                        assertTrue(app.taskRuntime.requestUserStop(id))
                    } else release.countDown()
                    await(scene) { app.taskRuntime.tasks.value.single { it.taskId == id }.status != DesktopTaskStatus.RUNNING }
                    await(scene) { controller.state.value.messages.any { it.id == durable.id } }
                    assertEquals(1, controller.state.value.messages.count { it.id == durable.id })
                    assertEquals(original, app.chatRepository.getMessage(durable.id, session)?.content)
                    if (outcome == "COMPLETE") assertTrue(controller.state.value.sessionSettingsDirty)
                } finally { release.countDown(); scene.close() }
            }
        }
    }

    @Test fun `actual streaming bubbles show full long text and reasoning while task summaries stay bounded and regeneration preserves original`() = runBlocking(edt) {
        for (outcome in listOf("COMPLETE", "REGEN_STOP", "ERROR")) fixture { app ->
            val regenerate = outcome == "REGEN_STOP"
            val first = "流式完整正文-".repeat(520); val reason = "完整推理文字-".repeat(510)
            ControlledDesktopSseServer(delta(first, reason), holdSeconds = 60).use { server ->
                val session = configure(app, server.baseUrl)
                val user = app.chatRepository.addMessage(ChatMessage.create(session, MessageRole.USER, "Fixture user"))
                val old = if (regenerate) app.chatRepository.addMessage(ChatMessage.create(session, MessageRole.ASSISTANT, "Original candidate")) else null
                val controller = app.primaryChatController
                controller.refreshAfterTerminalTask(session)
                val scene = ImageComposeScene(1200, 900, coroutineContext = edt) {
                    DesktopPrimaryChatPanel(controller, DesktopShellSize.WIDE, remember { DesktopComposerLayoutState() })
                }
                try {
                    repeat(6) { frame(scene) }
                    val id = if (old != null) app.taskRuntime.launchRegeneration(session, old.id) else {
                        controller.editComposer("Fixture next user"); assertNotNull(controller.send())
                    }
                    assertTrue(server.awaitReady())
                    await(scene) { app.taskRuntime.tasks.value.single { it.taskId == id }.contentPreview.isNotBlank() }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                    while (System.nanoTime() < deadline && texts(scene).none { it.contains(first) }) frame(scene)
                    assertTrue(texts(scene).any { it.contains(first) }, "LIVE_STREAM_BODY_TRUNCATED")
                    assertTrue(app.taskRuntime.tasks.value.single { it.taskId == id }.contentPreview.length < first.length)
                    val toggle = nodes(scene).first { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "推理过程" } == true }
                    val clickable = generateSequence(toggle) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
                    assertTrue(clickable.config[SemanticsActions.OnClick].action!!.invoke())
                    await(scene) { texts(scene).any { it.contains(reason) } }
                    assertFalse(texts(scene).filter { it.contains("流式完整正文") }.any { it.contains("[truncated]") })
                    if (old != null) {
                        assertEquals("Original candidate", app.chatRepository.getMessage(old.id, session)?.content)
                        assertTrue(app.taskRuntime.requestUserStop(id))
                    } else if (outcome == "ERROR") {
                        server.close()
                    } else {
                        server.sendEvent(delta("完成尾段-".repeat(410), "推理尾段", finish = true))
                        server.sendEvent("[DONE]")
                    }
                    await(scene) { app.taskRuntime.tasks.value.single { it.taskId == id }.status != DesktopTaskStatus.RUNNING }
                    val saved = if (old != null) assertNotNull(app.chatRepository.getMessage(old.id, session)) else
                        app.chatRepository.getMessagesReadOnly(session).last { it.role == MessageRole.ASSISTANT }
                    if (outcome == "ERROR") {
                        assertEquals(DesktopTaskStatus.FAILED, app.taskRuntime.tasks.value.single { it.taskId == id }.status)
                        assertTrue(saved.content.startsWith("错误: "))
                        assertFalse(saved.content.contains(first)); assertNull(saved.reasoningContent)
                    } else {
                        assertEquals(if (outcome == "COMPLETE") first + "完成尾段-".repeat(410) else first, saved.content)
                        assertEquals(if (outcome == "COMPLETE") reason + "推理尾段" else reason, saved.reasoningContent)
                    }
                    if (old != null) assertEquals("Original candidate", saved.alternatives.first())
                    assertNotNull(app.chatRepository.getMessage(user.id, session))
                } finally { scene.close() }
            }
        }
    }
}
