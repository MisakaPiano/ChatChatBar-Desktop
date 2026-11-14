package com.example.chatbar.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.*
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.image.*
import com.example.chatbar.ui.components.SafeBatchReviewFiles
import com.example.chatbar.ui.kit.*
import java.io.File
import java.nio.ByteBuffer
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.msgpack.core.MessagePack

/** Production Chat VM and one PromptDesigner call, with loopback SSE and terminal fake NovelAI HTTP. */
class SafeBatchChatRuntimeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private data class Fixture(val vm: ChatViewModel, val session: ChatSession, val anchor: ChatMessage,
        val later: ChatMessage, val imageCounts: List<Int>, val server: MockWebServer,
        val quoted: CountDownLatch, val releaseQuote: CountDownLatch, val accountCalls: AtomicInteger)
    @Test fun manualChatGeneratesFourFourTwoWithOneDesignAndDurableOrderedMetadata() = withFixture(false) { f ->
        runBlocking {
            f.vm.previewManualImageRun(ImageBatchConfiguration(4, true, 10, budget = 10000))
            f.vm.previewManualImageRun(ImageBatchConfiguration(1, true, 10, budget = 10000))
        }
        assertEquals("Editing request counts reuses the short-lived read-only quote", 1, f.accountCalls.get())
        rule.runOnIdle { f.vm.generateManualNovelAiImages(f.anchor.id, ImageBatchConfiguration(4, true, 10, ImageBatchPacing(fixedSeconds = 1), 10000)) }
        rule.waitUntil(25000) { f.vm.imageGenerations.value.none { !it.isTerminal } }
        assertTrue(f.vm.imageGenerations.value.toString(), f.vm.imageGenerations.value.isEmpty())
        assertEquals(listOf(4, 4, 2), f.imageCounts); assertEquals(1, f.server.requestCount)
        assertEquals("One cached preview, one launch query and a fresh query for each batch", 5, f.accountCalls.get())
        val rows = runBlocking { ChatRepository(JsonFileStorage(ChatBarApp.instance)).getMessages(f.session.id) }
        val batches = rows.filter { it.generatedFromMessageId == f.anchor.id }
        assertEquals(listOf(4, 4, 2), batches.map { it.images.size })
        assertEquals(listOf(f.anchor.id) + batches.map { it.id } + f.later.id, rows.map { it.id })
        assertEquals(10, batches.sumOf { it.generatedImageMetadata.size })
        assertTrue(batches.flatMap { it.images }.all { File(it).isFile })
        assertFalse(runBlocking { ChatBarApp.instance.chatRepository.getSession(f.session.id) }!!.automaticImageGenerationEnabled)
        proof("chat-production-four-four-two", f, batches.size)
    }
    @Test fun deletingAnchorDuringAccountQuotePreventsFirstImageRequest() = withFixture(true) { f ->
        rule.runOnIdle { f.vm.generateManualNovelAiImages(f.anchor.id, ImageBatchConfiguration(1, true, 2, ImageBatchPacing(fixedSeconds = 1), 0)) }
        rule.waitUntil(20000) { f.quoted.count == 0L }
        runBlocking { ChatBarApp.instance.chatRepository.deleteMessage(f.anchor.id, f.session.id) }
        f.releaseQuote.countDown()
        rule.waitUntil(15000) { f.vm.imageGenerations.value.none { !it.isTerminal } }
        assertTrue(f.imageCounts.isEmpty()); assertEquals(1, f.server.requestCount)
        val rows = runBlocking { ChatRepository(JsonFileStorage(ChatBarApp.instance)).getMessages(f.session.id) }
        assertTrue(rows.none { it.generatedFromMessageId == f.anchor.id })
        proof("chat-owner-changed-during-quote", f, 0)
    }
    private fun withFixture(blockSecondQuote: Boolean, block: (Fixture) -> Unit) {
        val app = ChatBarApp.instance
        runBlocking { withTimeout(30000) { app.dependenciesReady.first { it } } }
        check(app.packageName == "com.example.chatbar.safebatch.n1")
        rule.setContent { ChatBarTheme { CbText("Synthetic Chat runtime checks") } }
        val oldToken = app.novelAiCredentialStore.load()
        val oldSettings = runBlocking { app.settingsRepository.getAppSettings() }
        runBlocking { app.settingsRepository.saveAppSettings(oldSettings.copy(allowCleartextModelApi = true)) }
        val server = MockWebServer()
        val designed = """{"baseCaption":"synthetic chat batch scene","sizePreset":"PORTRAIT","characters":[]}"""
        val event = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
            put("delta", buildJsonObject { put("content", designed) }); put("finish_reason", "stop")
        }) }) }.toString()
        repeat(4) { server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $event\n\ndata: [DONE]\n\n")) }
        server.start()
        val imageCounts = Collections.synchronizedList(mutableListOf<Int>())
        val quoted = CountDownLatch(1); val release = CountDownLatch(1); val accountCalls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            check(chain.request().header("Authorization")?.contains("synthetic-chat-runtime-") == true)
            if (chain.request().url.encodedPath == "/user/subscription") {
                if (accountCalls.incrementAndGet() == 2 && blockSecondQuote) { quoted.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                    .body("""{"active":true,"tier":3,"trainingStepsLeft":10000,"usage":{"percent":100,"isNegative":false}}""".toResponseBody("application/json".toMediaType())).build()
            } else {
                check(chain.request().url.encodedPath == "/ai/generate-image-stream")
                val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer)
                val count = Json.parseToJsonElement(buffer.readUtf8()).jsonObject.getValue("parameters").jsonObject.getValue("n_samples").jsonPrimitive.int
                imageCounts += count
                val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAAD0lEQVR4nGP4zwAE/xkaAAt+An+rqPSSAAAAAElFTkSuQmCC")
                val bytes = List(count) { frame(png) }.fold(byteArrayOf()) { a, b -> a + b }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                    .body(bytes.toResponseBody("application/octet-stream".toMediaType())).build()
            }
        }.build()
        val store = ViewModelStore()
        try {
            val model = ModelConfig.create("Synthetic loopback designer", server.url("/v1").toString(), "synthetic-local-model-key", "synthetic-model")
            val card = CharacterCard.create("Synthetic batch runtime ${UUID.randomUUID()}")
            val session = ChatSession.create(card.id, "Synthetic manual batch").copy(imageModelId = model.id,
                modelId = model.id, novelAiImageModel = NovelAiImageModel.V4_5_FULL, longTermMemoryEnabled = false, automaticImageGenerationEnabled = false)
            val anchor = ChatMessage.create(session.id, MessageRole.ASSISTANT, "Synthetic assistant anchor")
            val later = ChatMessage.create(session.id, MessageRole.USER, "Synthetic later text")
            runBlocking { app.modelRepository.saveModel(model); app.characterRepository.save(card); app.chatRepository.createSession(session)
                app.chatRepository.addMessage(anchor); app.chatRepository.addMessage(later) }
            val planner = object : NovelAiTagSearchPlanner {
                override suspend fun decide(taskInput: String, characterPrompts: List<Pair<String, String>>, imageBase64s: List<String>, model: ModelConfig,
                    playerName: String?, botName: String, onRawText: (String) -> Unit) =
                    NovelAiTagSearchDecisionResult(decision = NovelAiTagSearchDecision(sceneDescription = "Synthetic planned scene", queries = emptyList()))
            }
            val search = object : NovelAiTagSearchClient { override suspend fun search(query: String): NovelAiTagSearchOutcome = error("No external tag request allowed") }
            val designer = NovelAiPromptDesigner(StreamingChatService(allowCleartextHttp = { true }), NovelAiTagResearchService(planner, search))
            app.novelAiCredentialStore.save("synthetic-chat-runtime-${UUID.randomUUID()}")
            var vm: ChatViewModel? = null
            rule.runOnIdle { vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(session.id,
                    novelAiPromptDesigner = designer, novelAiImageService = NovelAiImageService(client),
                    novelAiAccountService = NovelAiAccountService(client)) as T
            })[ChatViewModel::class.java] }
            rule.waitUntil(15000) { vm!!.session.value?.id == session.id }
            block(Fixture(requireNotNull(vm), session, anchor, later, imageCounts, server, quoted, release, accountCalls))
        } finally {
            release.countDown(); rule.runOnIdle { store.clear() }
            if (oldToken == null) app.novelAiCredentialStore.clear() else app.novelAiCredentialStore.save(oldToken)
            runBlocking { app.settingsRepository.saveAppSettings(oldSettings) }
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); server.shutdown()
        }
    }
    private fun proof(name: String, f: Fixture, batches: Int) {
        val directory = File(rule.activity.getExternalFilesDir(null), "safe-batch-review").also { it.mkdirs() }
        val original = File(directory, "$name.json")
        original.writeText(buildJsonObject { put("syntheticOnly", true); put("actualChatViewModel", true)
            put("httpCounts", JsonArray(f.imageCounts.map { JsonPrimitive(it) })); put("localDesignRequests", f.server.requestCount); put("savedBatches", batches); put("accountQueries", f.accountCalls.get())
        }.toString())
        SafeBatchReviewFiles.retain(rule.activity, original)
    }
    private fun frame(image: ByteArray): ByteArray {
        val p = MessagePack.newDefaultBufferPacker(); p.packMapHeader(2); p.packString("event_type"); p.packString("final")
        p.packString("image"); p.packBinaryHeader(image.size); p.writePayload(image); p.close()
        val body = p.toByteArray(); return ByteBuffer.allocate(body.size + 4).putInt(body.size).put(body).array()
    }
}
