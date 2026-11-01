package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.model.PresetModelCatalogSource
import com.example.chatbar.domain.chat.OpenAiStreamingTransport
import com.example.chatbar.domain.voice.*
import java.nio.file.Files
import java.time.LocalDate
import java.util.Collections
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.*

class DesktopVoiceAuxiliaryUsageTest {
    @Test fun `actual default voice gateway records translation and tags with real batch session card and counters`() = runBlocking {
        val root = Files.createTempDirectory("voice-aux-usage-")
        val storage = JsonFileStorage(root)
        val chats = ChatRepository(storage); val characters = CharacterRepository(storage)
        val settings = SettingsRepository(storage); val models = ModelRepository(storage)
        val resolver = EffectiveModelResolver(models, settings, object : PresetModelCatalogSource {
            override val catalog = PresetModelCatalog(); override val modelCatalogVersion: Int? = null
        })
        val rows = Collections.synchronizedList(mutableListOf<DesktopUsageRecord>())
        val logs = DesktopTransportDiagnosticsOwner(usageSink = { rows += it })
        val credentials = DesktopFishAudioCredentials(InMemoryDesktopSecretStore()).also { it.save("synthetic-Fish-key") }
        val fish = MockWebServer().also { it.start() }
        fish.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().setResponseCode(401)
        }
        val audio = DesktopVoiceAudioStore(root)
        val runtime = DesktopChatVoiceRuntime(chats, characters, settings, resolver,
            VoiceMessageRepository(storage, strictReads = true), audio, credentials, PreviewOutputFake(), diagnostics = logs,
            transportOverride = DesktopFishVoiceTransport(credentials, audio, OkHttpClient(), fish.url("/").toString().trimEnd('/'),
                today = { LocalDate.of(2026, 11, 1) }))
        try {
            MockWebServer().use { server ->
                settings.updateAppSettings { it.copy(allowCleartextModelApi = true) }
                val model = ModelConfig("model", "Synthetic", server.url("/v1").toString(), "synthetic-LLM-key", "fixture-model", createdAt = 1)
                models.saveModel(model)
                characters.save(CharacterCard("card", "Synthetic", characters = listOf(CharacterInfo("person", "Alice",
                    fishAudioVoice = FishAudioVoiceBinding("reference", "Synthetic"))), createdAt = 1, updatedAt = 1))
                val session = ChatSession.create("card", "Synthetic").copy(modelId = model.id, voiceLanguage = "English", audiobookModeEnabled = false)
                chats.createSession(session)
                val message = chats.addMessage(ChatMessage("message", session.id, MessageRole.ASSISTANT,
                    "<n=\"Alice\"/>[hello]()", createdAt = 1, updatedAt = 1))
                server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        val allText = body.getValue("messages").jsonArray.mapNotNull { it.jsonObject["content"]?.jsonPrimitive?.content }
                        val segments = allText.asSequence().flatMap { it.lineSequence() }.mapNotNull {
                            runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull()
                        }.first { values -> (values.singleOrNull() as? JsonObject)?.containsKey("id") == true }
                        val id = segments.single().jsonObject.getValue("id").jsonPrimitive.content
                        val translating = server.requestCount == 1
                        val result = buildJsonObject { put("segments", buildJsonArray { add(buildJsonObject {
                            put("id", id); put(if (translating) "translatedText" else "ttsText", if (translating) "hello" else "[calm] changed")
                        }) }) }.toString()
                        val chunk = buildJsonObject {
                            put("choices", buildJsonArray { add(buildJsonObject {
                                put("delta", buildJsonObject { put("content", result) }); put("finish_reason", "stop")
                            }) })
                            put("usage", buildJsonObject { put("prompt_tokens", if (translating) 3 else 5); put("completion_tokens", if (translating) 1 else 2) })
                        }
                        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $chunk\n\ndata: [DONE]\n\n")
                    }
                }
                val batch = assertNotNull(runtime.generate(message, "person", auxiliaryAuthorized = true))
                withTimeout(15_000) { runtime.coordinator.batches.first { it.any { state -> state.id == batch && state.phase in
                    setOf(VoiceGenerationPhase.AWAITING_TEXT_CONFIRMATION, VoiceGenerationPhase.FAILED) } } }
                assertEquals(VoiceGenerationPhase.AWAITING_TEXT_CONFIRMATION, runtime.coordinator.batches.value.single().phase)
                assertEquals(2, server.requestCount)
                assertEquals(0, fish.requestCount)
                val latest = rows.toList().groupBy { it.id }.values.map { it.maxBy { row -> row.revision } }
                assertEquals(2, latest.size)
                assertEquals(setOf("voice:TRANSLATE", "voice:TAG"), logs.entries.value.map { it.sessionId }.toSet())
                latest.forEach { record ->
                    assertEquals(batch, record.taskId); assertEquals(session.id, record.sessionId)
                    assertEquals("card", record.cardId); assertEquals(message.id, record.subjectId)
                    assertEquals(DesktopUsageSource.CHAT, record.source)
                    assertEquals(DesktopUsageOutcome.COMPLETED, record.outcome)
                    val json = Json.encodeToString(DesktopUsageRecord.serializer(), record)
                    assertFalse(json.contains("synthetic-LLM-key")); assertFalse(json.contains("[calm] changed"))
                }
                assertEquals(8L, summarizeDesktopUsage(latest).prompt.value)
                assertEquals(3L, summarizeDesktopUsage(latest).completion.value)
                assertTrue(runtime.repository.listForSession(session.id).isEmpty())
            }
        } finally { runtime.closeAndDrain(); fish.shutdown(); root.toFile().deleteRecursively() }
    }

    @Test fun `feature retry keeps one logical translation and independent physical attempts with real parent`() = runBlocking {
        val rows = Collections.synchronizedList(mutableListOf<DesktopUsageRecord>())
        val logs = DesktopTransportDiagnosticsOwner(usageSink = { rows += it })
        val transport = OpenAiStreamingTransport { true }
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
                val output = """{"segments":[{"id":"one","translatedText":"hello"}]}"""
                val chunk = buildJsonObject {
                    put("choices", buildJsonArray { add(buildJsonObject {
                        put("delta", buildJsonObject { put("content", output) }); put("finish_reason", "stop")
                    }) })
                    put("usage", buildJsonObject { put("prompt_tokens", 0); put("completion_tokens", 2) })
                }
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $chunk\n\ndata: [DONE]\n\n"))
                val model = ModelConfig("model", "Synthetic", server.url("/v1").toString(), "synthetic", "fixture", createdAt = 1)
                val gateway = DesktopFishAuxiliaryGateway(logs) { messages, configured, log -> transport.streamFishVoice(messages, configured, log) }
                val context = DesktopUsageContext(DesktopUsageSource.CHAT, "actual-batch", "actual-session", "actual-card", subjectId = "actual-message")
                val result = withContext(DesktopUsageParent(context)) {
                    FishAudioTagService(gateway).translate(model, "English", "synthetic", "synthetic",
                        listOf(VoiceTagInput("one", "hello", "Alice", "")))
                }
                assertEquals("hello", result.translatedTextById["one"])
                assertEquals(2, server.requestCount)
                val latest = rows.toList().groupBy { it.id }.values.map { it.maxBy { row -> row.revision } }
                assertEquals(2, latest.size); assertEquals(1, latest.map { it.requestId }.distinct().size)
                assertEquals(setOf(1, 2), latest.map { it.attempt }.toSet())
                assertEquals(DesktopUsageOutcome.RETRIED, latest.single { it.attempt == 1 }.outcome)
                assertNull(latest.single { it.attempt == 1 }.counters.prompt)
                assertEquals(0L, latest.single { it.attempt == 2 }.counters.prompt)
                latest.forEach {
                    assertEquals(context.taskId, it.taskId); assertEquals(context.sessionId, it.sessionId)
                    assertEquals(context.cardId, it.cardId); assertEquals(context.subjectId, it.subjectId)
                }
                val totals = summarizeDesktopUsage(latest)
                assertEquals(1, totals.requests); assertEquals(2, totals.attempts)
                assertEquals(0L, totals.prompt.value); assertEquals(1, totals.prompt.unreportedAttempts)
            }
        } finally { transport.closeAndDrain() }
    }
}
