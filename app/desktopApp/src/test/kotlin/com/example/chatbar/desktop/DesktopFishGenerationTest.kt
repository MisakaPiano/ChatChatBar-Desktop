package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.model.*
import com.example.chatbar.domain.voice.*
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import kotlinx.serialization.json.*
import com.example.chatbar.domain.chat.ChatApiMessage
import kotlin.test.*

class DesktopFishGenerationTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("s4-generation-")
        val storage = JsonFileStorage(root)
        val chats = ChatRepository(storage); val characters = CharacterRepository(storage)
        val settings = SettingsRepository(storage); val models = ModelRepository(storage)
        val voices = VoiceMessageRepository(storage, strictReads = true)
        val store = DesktopVoiceAudioStore(root)
        val output = PreviewOutputFake(); val playback = DesktopVoicePlayback(store, output)
        val credentials = DesktopFishAudioCredentials(InMemoryDesktopSecretStore()).also { it.save("synthetic-only") }
        val server = MockWebServer().also { it.start() }
        val transport = DesktopFishVoiceTransport(credentials, store, OkHttpClient(), server.url("/").toString().trimEnd('/'),
            today = { LocalDate.of(2026, 10, 19) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var textCalls = 0; var rewrite = false
        var textReply: (FishVoiceTextTask, List<ChatApiMessage>) -> String = { task, messages ->
            val raw = messages.last().content.jsonPrimitive.content.lineSequence().filter { it.isNotBlank() }.last()
            val inputs = Json.parseToJsonElement(raw).jsonArray
            buildJsonObject { put("segments", buildJsonArray { inputs.forEach { input -> add(buildJsonObject {
                put("id", input.jsonObject["id"]!!)
                put(if (task == FishVoiceTextTask.TRANSLATE) "translatedText" else "ttsText",
                    (if (rewrite) "changed " else "") + input.jsonObject["text"]!!.jsonPrimitive.content)
            }) } }) }.toString()
        }
        val tags = FishAudioTagService(FishVoiceTextGateway { task, messages, _, _ ->
            textCalls++
            flowOf(StreamEvent.Delta(textReply(task, messages)), StreamEvent.Done)
        })
        val resolver = EffectiveModelResolver(models, settings, object : PresetModelCatalogSource {
            override val catalog = PresetModelCatalog(); override val modelCatalogVersion: Int? = null
        })
        val coordinator = DesktopFishGenerationCoordinator(scope, chats, characters, settings, voices,
            credentials, transport, tags, resolver, store, playback)
        lateinit var session: ChatSession
        lateinit var message: ChatMessage
        suspend fun seed(content: String = "<n=\"Alice\"/>[hello]()") {
            characters.save(CharacterCard(id = "card", name = "synthetic", characters = listOf(CharacterInfo(
                id = "alice", name = "Alice", fishAudioVoice = FishAudioVoiceBinding(referenceId = "reference", title = "snapshot"))), createdAt = 1, updatedAt = 1))
            session = ChatSession.create("card", "synthetic").copy(audiobookModeEnabled = true)
            chats.createSession(session)
            message = ChatMessage("message", session.id, MessageRole.ASSISTANT, content, createdAt = 1, updatedAt = 1)
            chats.addMessage(message)
        }
        fun audio() = MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(fishPreviewMp3()))
        suspend fun done(id: String) = withTimeout(8000) {
            while (coordinator.batches.value.any { it.id == id && it.phase !in setOf(VoiceGenerationPhase.FAILED, VoiceGenerationPhase.CANCELLED) }) delay(10)
        }
        override fun close() { runBlocking { coordinator.closeAndDrain(); transport.closeAndDrain(); playback.closeAndDrain(); scope.cancel() }; server.shutdown() }
    }

    @Test fun `whole fills missing anchors repeated single keeps takes and restart preserves snapshots`() = runBlocking {
        Fixture().use { f ->
            f.seed(); repeat(2) { f.server.enqueue(f.audio()) }
            val first = f.coordinator.generateWhole(f.session.id, f.message.id); f.done(first)
            assertEquals(1, f.voices.listForCurrentVersion(f.message).size)
            val skip = f.coordinator.generateWhole(f.session.id, f.message.id); f.done(skip)
            assertEquals(1, f.server.requestCount)
            val single = f.coordinator.generateSingle(f.session.id, f.message.id, 0); f.done(single)
            assertEquals(2, f.voices.listForCurrentVersion(f.message).size)
            assertEquals(0, f.textCalls); assertEquals(0, f.output.plays)
            val request = f.server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("s2.1-pro-free", request.getHeader("model")); assertTrue(request.body.readUtf8().contains("\"reference_id\":\"reference\""))
            assertEquals(2, VoiceMessageRepository(JsonFileStorage(f.root), strictReads = true).listForSession(f.session.id).size)
        }
    }

    @Test fun `partial HTTP failure keeps successes suppresses autoplay and never resends 503`() = runBlocking {
        Fixture().use { f ->
            f.seed("<n=\"Alice\"/>[one]()\n[two]()")
            f.server.enqueue(f.audio()); f.server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            f.coordinator.setForegroundSession(f.session.id)
            val id = f.coordinator.generateWhole(f.session.id, f.message.id); f.done(id)
            assertEquals(VoiceGenerationPhase.FAILED, f.coordinator.batches.value.single().phase)
            assertEquals(1, f.voices.listForSession(f.session.id).size)
            assertEquals(2, f.server.requestCount); assertEquals(0, f.output.plays)
        }
    }

    @Test fun `cancellation drains body and cannot resurrect a deleted message`() = runBlocking {
        Fixture().use { f ->
            f.seed()
            // QueueDispatcher.peek exposes throttle policy while reading the POST as well.
            // Select throttling only after the complete request has reached dispatch instead.
            f.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest) = f.audio().throttleBody(1, 1, TimeUnit.SECONDS)
            }
            f.coordinator.generateWhole(f.session.id, f.message.id)
            withTimeout(3000) {
                while (f.server.requestCount == 0) {
                    assertFalse(f.coordinator.batches.value.any { it.phase == VoiceGenerationPhase.FAILED },
                        f.coordinator.batches.value.map { it.errors }.toString())
                    delay(10)
                }
            }
            assertNotNull(f.server.takeRequest(0, TimeUnit.SECONDS))
            withTimeout(3000) { while (f.coordinator.batches.value.none { it.receivedBytes > 0 }) delay(10) }
            withTimeout(5000) { f.coordinator.cancelAndJoinForMessage(f.session.id, f.message.id) }
            f.chats.deleteMessage(f.message.id, f.session.id)
            assertTrue(f.voices.listForSession(f.session.id).isEmpty())
            Files.walk(f.root).use { files -> assertEquals(0, files.filter { it.toString().endsWith(".mp3") || it.toString().endsWith(".part") }.count()) }
            assertEquals(1, f.server.requestCount)
        }
    }

    @Test fun `replacement failure preserves old recording and success replaces only its artifact`() = runBlocking {
        Fixture().use { f ->
            f.seed(); f.server.enqueue(f.audio())
            f.done(f.coordinator.generateWhole(f.session.id, f.message.id))
            val old = f.voices.listForSession(f.session.id).single()
            f.server.enqueue(MockResponse().setResponseCode(401).setBody("synthetic-secret-response"))
            f.done(f.coordinator.regenerate(old.id, "hello"))
            assertEquals(old, f.voices.get(old.id)); assertTrue(Files.exists(java.nio.file.Path.of(old.audioPath)))
            f.coordinator.dismiss(f.coordinator.batches.value.single().id)
            f.server.enqueue(f.audio()); f.done(f.coordinator.regenerate(old.id, "hello"))
            val replacement = assertNotNull(f.voices.get(old.id))
            assertNotEquals(old.audioPath, replacement.audioPath); assertFalse(Files.exists(java.nio.file.Path.of(old.audioPath)))
            assertEquals(1, f.voices.listForSession(f.session.id).size)
        }
    }

    @Test fun `paid stale and expired free profile never send requests`() = runBlocking {
        Fixture().use { f ->
            f.seed()
            for (model in listOf("missing", "s2.1-pro")) {
                f.settings.updateAppSettings { it.copy(fishAudioTtsModelId = model) }
                f.done(f.coordinator.generateWhole(f.session.id, f.message.id))
            }
            val expired = DesktopFishVoiceTransport(f.credentials, f.store, OkHttpClient(), f.server.url("/").toString(),
                today = { LocalDate.of(2026, 12, 1) })
            try { assertFails { expired.synthesize("s2.1-pro-free", "reference", "hi", f.session.id, "expired") } }
            finally { expired.closeAndDrain() }
            assertEquals(0, f.server.requestCount); assertEquals(0, f.textCalls)
        }
    }
    @Test fun `tag rewrite waits for explicit confirmation and rejection sends no TTS`() = runBlocking {
        Fixture().use { f ->
            f.seed()
            f.models.saveModel(ModelConfig("model", "synthetic", "https://example.invalid", "synthetic", "mock", createdAt = 1))
            f.chats.updateSession(f.session.copy(audiobookModeEnabled = false, modelId = "model"))
            f.rewrite = true
            val id = f.coordinator.generateWhole(f.session.id, f.message.id)
            withTimeout(5000) { while (f.coordinator.batches.value.none { it.phase == VoiceGenerationPhase.AWAITING_TEXT_CONFIRMATION }) delay(10) }
            assertEquals(0, f.server.requestCount)
            f.coordinator.resolveTextConfirmation(id, false); f.done(id)
            assertEquals(0, f.server.requestCount); assertTrue(f.voices.listForSession(f.session.id).isEmpty())
            val next = f.coordinator.generateWhole(f.session.id, f.message.id)
            withTimeout(5000) { while (f.coordinator.batches.value.none { it.id == next && it.phase == VoiceGenerationPhase.AWAITING_TEXT_CONFIRMATION }) delay(10) }
            f.server.enqueue(f.audio()); f.coordinator.resolveTextConfirmation(next, true); f.done(next)
            assertTrue(f.voices.listForSession(f.session.id).single().taggedText.startsWith("changed"))
        }
    }

    @Test fun `global five permit batch preserves order and foreground eligibility`() = runBlocking {
        Fixture().use { f ->
            f.seed((1..8).joinToString("\n") { "<n=\"Alice\"/>[part $it]()" })
            val active = AtomicInteger(); val peak = AtomicInteger()
            f.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val now = active.incrementAndGet(); peak.updateAndGet { maxOf(it, now) }
                    try { Thread.sleep(100); return f.audio() } finally { active.decrementAndGet() }
                }
            }
            f.coordinator.setForegroundSession(f.session.id)
            val id = f.coordinator.generateWhole(f.session.id, f.message.id)
            f.coordinator.setForegroundSession(null)
            f.done(id)
            assertEquals(8, f.voices.listForSession(f.session.id).size)
            assertTrue(peak.get() in 2..5); assertEquals(8, f.server.requestCount); assertEquals(0, f.output.plays)
            val ordered = f.voices.placementsForMessage(f.message).map { it.voice }
            assertEquals(ordered.sortedBy { it.sourceOrder }.map { it.id }, ordered.map { it.id })
        }
    }

    @Test fun `explicit translation preserves source snapshot and audiobook skips only tagging`() = runBlocking {
        Fixture().use { f ->
            f.seed()
            f.models.saveModel(ModelConfig("model", "synthetic", "https://example.invalid", "synthetic", "mock", createdAt = 1))
            f.chats.updateSession(f.session.copy(audiobookModeEnabled = true, modelId = "model", voiceLanguage = "English"))
            f.textReply = { task, messages ->
                assertEquals(FishVoiceTextTask.TRANSLATE, task)
                val inputs = Json.parseToJsonElement(messages.last().content.jsonPrimitive.content.lineSequence().filter { it.isNotBlank() }.last()).jsonArray
                buildJsonObject { put("segments", buildJsonArray { inputs.forEach { input -> add(buildJsonObject {
                    put("id", input.jsonObject["id"]!!); put("translatedText", "translated hello")
                }) } }) }.toString()
            }
            f.server.enqueue(f.audio()); f.done(f.coordinator.generateWhole(f.session.id, f.message.id))
            val v = f.voices.listForSession(f.session.id).single()
            assertEquals("hello", v.sourceText); assertEquals("translated hello", v.synthesisText)
            assertTrue(f.server.takeRequest().body.readUtf8().contains("translated hello")); assertEquals(1, f.textCalls)
        }
    }

    @Test fun `multi character whole audiobook requires an explicitly selected current binding`() = runBlocking {
        Fixture().use { f ->
            f.seed("Synthetic whole narration")
            val card = f.characters.getById("card")!!
            f.characters.save(card.copy(characters = card.characters + CharacterInfo("bob", "Bob",
                fishAudioVoice = FishAudioVoiceBinding("bob-reference", "Bob snapshot"))))
            f.settings.updateAppSettings { it.copy(assistantSegmentedBubblesEnabled = false) }
            f.done(f.coordinator.generateWhole(f.session.id, f.message.id))
            assertEquals(0, f.server.requestCount)
            f.server.enqueue(f.audio()); f.done(f.coordinator.generateWhole(f.session.id, f.message.id, "bob"))
            val voice = f.voices.listForSession(f.session.id).single()
            assertEquals("bob-reference", voice.voice.referenceId)
            assertEquals(VoiceGenerationPolicy.WHOLE_MESSAGE_SOURCE_KIND, voice.sourceSegmentKind)
            assertNull(voice.anchorId); assertEquals(0, f.textCalls)
        }
    }

}
