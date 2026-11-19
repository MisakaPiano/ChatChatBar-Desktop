package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.prompt.MainChatPromptAuthority
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class DesktopNarrativeReplyEchoTest {
    @Test fun `focus echoed outer wrapper never reaches preview or durable new reply`() = runBlocking {
        assertCompleted(ChatNarrativeMode.FOCUS)
    }

    @Test fun `advance echoed outer wrapper never reaches preview or durable new reply`() = runBlocking {
        assertCompleted(ChatNarrativeMode.ADVANCE)
    }

    private suspend fun assertCompleted(mode: ChatNarrativeMode) = fixture { app ->
        MockWebServer().use { server ->
            val id = prepare(app, server.url("/v1").toString().trimEnd('/'), mode)
            app.chatRepository.addMessage(ChatMessage.create(id, MessageRole.USER, "Earlier synthetic input"))
            val old = app.chatRepository.addMessage(ChatMessage.create(id, MessageRole.ASSISTANT,
                "Earlier synthetic reply").copy(narrativeMode = mode))
            val opening = "<历史回复 模式=\"${mode.shortLabel}\">"
            val body = "门开了。\n灯亮了。"
            val pieces = opening.map(Char::toString) + "\n$body\n" + "</历史回复>".map(Char::toString)
            server.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream")
                .setBody(pieces.joinToString("") { "data: ${delta(it)}\n\n" } +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            val seen = mutableListOf<String>()
            val result = app.createRealChatRuntime().sendText(id, "New synthetic input",
                observer = DesktopRealChatObserver { seen += it.content })
            assertEquals(body, result.assistant.content)
            assertEquals(body, ChatRepository(JsonFileStorage(app.appDataRoot)).getMessage(result.assistant.id, id)?.content)
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.all { '<' !in it && '>' !in it }, "Even partial wrapper tokens must stay out of stream previews")
            assertEquals(old, app.chatRepository.getMessage(old.id, id), "Do not rewrite historical source replies")
            val wire = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                .getValue("messages").jsonArray.joinToString("\n") { it.jsonObject.getValue("content").jsonPrimitive.content }
            assertTrue(wire.contains(MainChatPromptAuthority.narrativeModeHistoryReply(old.content, mode)),
                "Request history still needs its actual mode wrapper")
        }
    }

    @Test fun `code and same name examples remain literal new reply text`() = runBlocking {
        fixture { app -> MockWebServer().use { server ->
            val id = prepare(app, server.url("/v1").toString().trimEnd('/'), ChatNarrativeMode.FOCUS)
            val example = "```text\n<历史回复 模式=\"聚焦\">\n示例正文\n</历史回复>\n```\n正文提到 <历史回复 模式=\"推进\">。"
            server.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream")
                .setBody("data: ${delta(example)}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            val result = app.createRealChatRuntime().sendText(id, "Explain a synthetic code example")
            assertEquals(example, result.assistant.content)
        } }
    }

    @Test fun `explicit stop finalizes echoed partial wrappers for both modes without changing prior replies`() = runBlocking {
        for (mode in ChatNarrativeMode.entries) fixture { app -> coroutineScope {
            val body = "已经生成的正文。"
            val raw = "<历史回复 模式=\"${mode.shortLabel}\">\n$body\n</历史回复"
            ControlledDesktopSseServer(delta(raw)).use { server ->
                val id = prepare(app, server.baseUrl, mode)
                val before = app.chatRepository.getMessages(id)
                val control = DesktopChatGenerationControl()
                val seen = CompletableDeferred<Unit>()
                val operation = async { app.createRealChatRuntime().sendText(id, "Synthetic stop input",
                    observer = DesktopRealChatObserver { if (body in it.content) seen.complete(Unit) }, control = control) }
                try {
                    withTimeout(5_000) { seen.await() }
                    assertTrue(control.requestUserStop())
                    assertFailsWith<DesktopUserStoppedChatException> { operation.await() }
                    val messages = ChatRepository(JsonFileStorage(app.appDataRoot)).getMessages(id)
                    val stopped = messages.single { it.role == MessageRole.ASSISTANT && it.id !in before.map(ChatMessage::id) }
                    assertEquals(body, stopped.content)
                    before.forEach { assertEquals(it, messages.single { m -> m.id == it.id }) }
                } finally { operation.cancelAndJoin() }
            }
        } }
    }

    private fun delta(text: String) = """{"choices":[{"delta":{"content":${JsonPrimitive(text)}}}]}"""

    private suspend fun prepare(app: DesktopAppContainer, url: String, mode: ChatNarrativeMode): String {
        val card = CharacterCard.create("Synthetic narrative", greeting = "Synthetic opening")
        app.characterRepository.save(card)
        val id = app.characterSessionService.createSessionForCharacter(card.id)
        val model = ModelConfig(id = "synthetic-model", displayName = "Synthetic", baseUrl = url,
            apiKey = "synthetic-narrative-key", modelName = "synthetic-model", createdAt = 1)
        app.modelRepository.saveModel(model)
        app.settingsRepository.saveAppSettings(AppSettings(defaultModelId = model.id,
            allowCleartextModelApi = true, ragInjectionMode = "OFF", defaultContextWindowSize = 20))
        app.chatRepository.setNarrativeMode(id, mode)
        return id
    }

    private suspend fun fixture(block: suspend (DesktopAppContainer) -> Unit) {
        val root = Files.createTempDirectory("narrative-reply-echo-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        try { block(app) } finally {
            app.close()
            check(root.fileName.toString().startsWith("narrative-reply-echo-"))
            check(root.toFile().deleteRecursively())
        }
    }
}
