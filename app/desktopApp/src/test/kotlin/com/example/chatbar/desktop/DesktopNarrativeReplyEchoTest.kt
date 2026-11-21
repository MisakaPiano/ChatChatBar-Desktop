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
    @Test fun `escaped and container code closings survive unsegmented streams completion cold read and next request history`() = runBlocking {
        for (mode in ChatNarrativeMode.entries) fixture { app -> MockWebServer().use { server ->
            val id = prepare(app, server.url("/v1").toString().trimEnd('/'), mode, segmented = false)
            var priorBody: String? = null
            var expectedRequests = 0
            for (body in listOf(
                "保留字面量 \\</历史回复>\n继续正文。",
                "> ~~~text\n> </历史回复>\n> ~~~\n\n门外传来脚步声。",
                "- 示例\n  ~~~text\n  </历史回复>\n  ~~~\n\n门外传来脚步声。",
                "- \n  ~~~text\n  </历史回复>\n  ~~~\n\n门外传来脚步声。",
                "+\n  ~~~text\n  </历史回复>\n  ~~~\n\n门外传来脚步声。",
                "普通正文中的标点与换行。\n下一段。",
                "她说‘the visitors’ plan\n</历史回复>\n仍在这里。’然后转身。",
                "她说“先保留这行\n</历史回复>\n再继续”。",
                "A visitor's note mentions \"a literal </历史回复> marker\" here."
            )) {
                val raw = MainChatPromptAuthority.narrativeModeHistoryReply(body, mode)
                server.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream").setBody(
                    raw.chunked(3).joinToString("") { "data: ${delta(it)}\n\n" } +
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
                val seen = mutableListOf<String>()
                val result = app.createRealChatRuntime().sendText(id, "Explain the next synthetic literal",
                    observer = DesktopRealChatObserver { seen += it.content })
                assertEquals(++expectedRequests, server.requestCount, "This fixture admits only the actual chat request, without memory/model auxiliaries")
                assertEquals(body, result.assistant.content)
                assertEquals(body, seen.last())
                assertTrue(seen.none { it.startsWith("<历史回复 模式=") }, "Unexpected preview for $body: ${seen.firstOrNull { it.startsWith("<历史回复 模式=") }}")
                assertEquals(body, ChatRepository(JsonFileStorage(app.appDataRoot)).getMessage(result.assistant.id, id)?.content)
                val wire = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                    .getValue("messages").jsonArray.joinToString("\n") { it.jsonObject.getValue("content").jsonPrimitive.content }
                priorBody?.let { assertTrue(MainChatPromptAuthority.narrativeModeHistoryReply(it, mode) in wire) }
                priorBody = body
            }
        } }
    }

    @Test fun `user stop retains escaped truncated literal closings in both unsegmented narrative modes`() = runBlocking {
        for (mode in ChatNarrativeMode.entries) fixture { app -> coroutineScope {
            val body = "> ~~~text\n> </历史回复>\n> ~~~\n\n保留未完成的字面量 \\</历史回复"
            val raw = "<历史回复 模式=\"${mode.shortLabel}\">\n$body"
            ControlledDesktopSseServer(delta(raw)).use { server ->
                val id = prepare(app, server.baseUrl, mode, segmented = false)
                val before = app.chatRepository.getMessages(id)
                val control = DesktopChatGenerationControl(); val seen = CompletableDeferred<Unit>()
                val job = async { app.createRealChatRuntime().sendText(id, "Synthetic literal stop input",
                    observer = DesktopRealChatObserver { if (it.content == body) seen.complete(Unit) }, control = control) }
                try {
                    withTimeout(5_000) { seen.await() }; assertTrue(control.requestUserStop())
                    assertFailsWith<DesktopUserStoppedChatException> { job.await() }
                    val messages = ChatRepository(JsonFileStorage(app.appDataRoot)).getMessages(id)
                    assertEquals(body, messages.single { it.role == MessageRole.ASSISTANT && it.id !in before.map(ChatMessage::id) }.content)
                    before.forEach { old -> assertEquals(old, messages.single { it.id == old.id }) }
                } finally { job.cancelAndJoin() }
            }
        } }
    }
    @Test fun `user Stop retains an unfinished curly possessive quote and its interior history closing`() = runBlocking {
        for (mode in ChatNarrativeMode.entries) fixture { app -> coroutineScope {
            val body = "她说‘the visitors’ plan\n</历史回复>\n仍在这里。"
            val raw = "<历史回复 模式=\"${mode.shortLabel}\">\n$body"
            ControlledDesktopSseServer(delta(raw)).use { server ->
                val id = prepare(app, server.baseUrl, mode, segmented = false)
                val before = app.chatRepository.getMessages(id)
                val control = DesktopChatGenerationControl(); val seen = CompletableDeferred<Unit>()
                val job = async { app.createRealChatRuntime().sendText(id, "Synthetic literal stop input",
                    observer = DesktopRealChatObserver { if (it.content == body) seen.complete(Unit) }, control = control) }
                try {
                    withTimeout(5_000) { seen.await() }; assertTrue(control.requestUserStop())
                    assertFailsWith<DesktopUserStoppedChatException> { job.await() }
                    val messages = ChatRepository(JsonFileStorage(app.appDataRoot)).getMessages(id)
                    assertEquals(body, messages.single { it.role == MessageRole.ASSISTANT && it.id !in before.map(ChatMessage::id) }.content)
                    before.forEach { old -> assertEquals(old, messages.single { it.id == old.id }) }
                } finally { job.cancelAndJoin() }
            }
        } }
    }
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

    @Test fun `leading paired examples and inline literal tags survive actual transport in both modes`() = runBlocking {
        for (mode in ChatNarrativeMode.entries) fixture { app -> MockWebServer().use { server ->
            val id = prepare(app, server.url("/v1").toString().trimEnd('/'), mode)
            val opening = "<历史回复 模式=\"${mode.shortLabel}\">"
            val example = MainChatPromptAuthority.narrativeModeHistoryReply("标签里是一段示例。", mode) + "\n\n这里开始解释。"
            for ((raw, expected) in listOf(example to example,
                MainChatPromptAuthority.narrativeModeHistoryReply("开头标记是 `$opening`，正文继续。", mode) to
                    "开头标记是 `$opening`，正文继续。",
                MainChatPromptAuthority.narrativeModeHistoryReply("写成 ``例：`$opening` 或\n</历史回复>`` 后接正文。", mode) to
                    "写成 ``例：`$opening` 或\n</历史回复>`` 后接正文。")) {
                server.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream").setBody(
                    raw.chunked(3).joinToString("") { "data: ${delta(it)}\n\n" } +
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
                val seen = mutableListOf<String>()
                val result = app.createRealChatRuntime().sendText(id, "Explain this synthetic example",
                    observer = DesktopRealChatObserver { seen += it.content })
                assertEquals(expected, result.assistant.content)
                assertEquals(expected, ChatRepository(JsonFileStorage(app.appDataRoot)).getMessage(result.assistant.id, id)?.content)
                assertEquals(expected, seen.last())
            }
        } }
    }

    @Test fun `explicit stop finalizes echoed partial wrappers for both modes without changing prior replies`() = runBlocking {
        for (mode in ChatNarrativeMode.entries) fixture { app -> coroutineScope {
            val body = "已经生成的正文。"
            val raw = "<历史回复 模式=\"${mode.shortLabel}\">\n$body\n</历史回"
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

    @Test fun `history-shaped response stays literal when narrative modes are disabled`() = runBlocking {
        fixture { app -> MockWebServer().use { server ->
            val id = prepare(app, server.url("/v1").toString().trimEnd('/'), ChatNarrativeMode.FOCUS,
                disableNarrativeModes = true)
            val literal = "<历史回复 模式=\"推进\">\n合法格式正文。\n</历史回复>"
            server.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream").setBody(
                "data: ${delta(literal)}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            val seen = mutableListOf<String>()
            val result = app.createRealChatRuntime().sendText(id, "Return the literal format",
                observer = DesktopRealChatObserver { seen += it.content })
            assertEquals(literal, seen.last())
            assertEquals(literal, result.assistant.content)
            assertEquals(literal, ChatRepository(JsonFileStorage(app.appDataRoot)).getMessage(result.assistant.id, id)?.content)
        } }
    }

    @Test fun `multipart tool reply boundaries clean preview and user Stop before terminal persistence`() = runBlocking {
        for (mode in ChatNarrativeMode.entries) for (stop in listOf(false, true)) fixture { app -> coroutineScope {
            MockWebServer().use { server ->
                val id = prepare(app, server.url("/v1").toString().trimEnd('/'), mode, segmented = false)
                app.settingsRepository.updateAppSettings { it.copy(experimentalApiModeEnabled = true) }
                val first = MainChatPromptAuthority.narrativeModeHistoryReply("第一段正文。", mode)
                val second = MainChatPromptAuthority.narrativeModeHistoryReply("第二段正文。", mode)
                val expected = "第一段正文。\n第二段正文。"
                val calls = buildJsonArray {
                    for ((index, body) in listOf(first, second).withIndex()) add(buildJsonObject {
                        put("index", index); put("id", "synthetic-$index"); put("type", "function")
                        putJsonObject("function") {
                            put("name", "game_content")
                            put("arguments", buildJsonObject { put("content", body) }.toString())
                        }
                    })
                }
                val chunk = buildJsonObject { putJsonArray("choices") { add(buildJsonObject {
                    putJsonObject("delta") { put("tool_calls", calls) }
                }) } }
                server.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream").setBody(
                    "data: $chunk\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"))
                val control = DesktopChatGenerationControl()
                val seen = mutableListOf<String>()
                val oldIds = app.chatRepository.getMessages(id).map { it.id }.toSet()
                val job = async { app.createRealChatRuntime().sendText(id, "Synthetic multipart input",
                    observer = DesktopRealChatObserver {
                        seen += it.content
                        if (stop && it.content == expected) control.requestUserStop()
                    }, control = control) }
                if (stop) assertFailsWith<DesktopUserStoppedChatException> { job.await() }
                else assertEquals(expected, job.await().assistant.content)
                assertEquals(expected, seen.last(), "Part boundaries must reach preview before Completed")
                val stored = ChatRepository(JsonFileStorage(app.appDataRoot)).getMessages(id)
                    .single { it.role == MessageRole.ASSISTANT && it.id !in oldIds }
                assertEquals(expected, stored.content)
            }
        } }
    }

    @Test fun `indented code quote cannot keep outer markers in actual preview Stop and completion`() = runBlocking {
        for (mode in ChatNarrativeMode.entries) for (stop in listOf(false, true)) fixture { app -> coroutineScope {
            val body = "    val mark = \"\n\n她写道：“保留 </历史回复> 作为示例。”"
            val raw = MainChatPromptAuthority.narrativeModeHistoryReply(body, mode)
            MockWebServer().use { server ->
                val id = prepare(app, server.url("/v1").toString().trimEnd('/'), mode, segmented = false)
                server.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream").setBody(
                    "data: ${delta(raw)}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
                val before = app.chatRepository.getMessages(id).map { it.id }.toSet()
                val control = DesktopChatGenerationControl()
                val seen = mutableListOf<String>()
                val operation = async { app.createRealChatRuntime().sendText(id, "Synthetic code quote input",
                    observer = DesktopRealChatObserver {
                        seen += it.content
                        if (stop && it.content == body) control.requestUserStop()
                    }, control = control) }
                try {
                    if (stop) assertFailsWith<DesktopUserStoppedChatException> { operation.await() }
                    else assertEquals(body, operation.await().assistant.content)
                    assertEquals(body, seen.last())
                    val saved = ChatRepository(JsonFileStorage(app.appDataRoot)).getMessages(id)
                        .single { it.role == MessageRole.ASSISTANT && it.id !in before }
                    assertEquals(body, saved.content)
                } finally { operation.cancelAndJoin() }
            }
        } }
    }

    @Test fun `literal English plural quote example retains its opening in real preview Stop and cold storage`() = runBlocking {
        for (mode in ChatNarrativeMode.entries) for (stop in listOf(true, false)) fixture { app -> coroutineScope {
            MockWebServer().use { server ->
                server.start()
                val id = prepare(app, server.url("/v1").toString().trimEnd('/'), mode, segmented = false)
                val tail = "This first pair is a markup example."
                val raw = "<历史回复 模式=\"${mode.shortLabel}\">\nI checked whether ‘the visitors’ arrived\n</历史回复>\n$tail"
                server.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream").setBody(
                    "data: ${delta(raw)}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
                val before = app.chatRepository.getMessages(id).map { it.id }.toSet()
                val control = DesktopChatGenerationControl()
                val fullPreviews = mutableListOf<String>()
                val operation = async { app.createRealChatRuntime().sendText(id, "Synthetic literal English example",
                    observer = DesktopRealChatObserver {
                        if (it.content.endsWith(tail)) {
                            fullPreviews += it.content
                            if (stop) control.requestUserStop()
                        }
                    }, control = control) }
                try {
                    if (stop) assertFailsWith<DesktopUserStoppedChatException> { operation.await() }
                    else assertEquals(raw, operation.await().assistant.content)
                    assertTrue(fullPreviews.isNotEmpty())
                    assertTrue(fullPreviews.all { it == raw }, "Literal English example lost its opening history tag")
                    val saved = ChatRepository(JsonFileStorage(app.appDataRoot)).getMessages(id)
                        .single { it.role == MessageRole.ASSISTANT && it.id !in before }
                    assertEquals(raw, saved.content)
                } finally { operation.cancelAndJoin() }
            }
        } }
    }

    private fun delta(text: String) = """{"choices":[{"delta":{"content":${JsonPrimitive(text)}}}]}"""

    private suspend fun prepare(
        app: DesktopAppContainer, url: String, mode: ChatNarrativeMode, segmented: Boolean = true,
        disableNarrativeModes: Boolean = false,
    ): String {
        val card = CharacterCard.create("Synthetic narrative", greeting = "Synthetic opening")
        app.characterRepository.save(card)
        val id = app.characterSessionService.createSessionForCharacter(card.id)
        val model = ModelConfig(id = "synthetic-model", displayName = "Synthetic", baseUrl = url,
            apiKey = "synthetic-narrative-key", modelName = "synthetic-model", createdAt = 1)
        app.modelRepository.saveModel(model)
        app.settingsRepository.saveAppSettings(AppSettings(defaultModelId = model.id,
            assistantSegmentedBubblesEnabled = segmented,
            disableNarrativeModes = disableNarrativeModes,
            allowCleartextModelApi = true, ragInjectionMode = "OFF", defaultContextWindowSize = 20))
        app.chatRepository.setNarrativeMode(id, mode)
        val session = assertNotNull(app.chatRepository.getSession(id))
        app.chatRepository.saveSessionSettingsDraft(session, session.copy(longTermMemoryEnabled = false))
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
