package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.moment.*
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.msgpack.core.MessagePack
import kotlin.test.*

class DesktopMomentImageUsageTest {
    @Test fun `manual moment regeneration applies card sampling defaults and retains explicit prompt dimensions and stored post`() = runBlocking {
        val f = Fixture()
        try {
            f.prepare(); f.server.enqueue(response(image()))
            val parameters = NovelAiCharacterImageSettings(steps = 21, guidance = 4.2f, cfgRescale = .25f,
                sampler = NovelAiSampler.EULER, varietyPlus = true)
            val card = f.card.copy(defaultImageGenerationSettings = parameters)
            val result = f.generation.generate(f.post, card, f.session, f.app.settingsRepository.getAppSettings(), f.draft, {}, {})
            val sent = assertNotNull(f.server.takeRequest(5, TimeUnit.SECONDS))
            val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
            val wire = body.getValue("parameters").jsonObject
            assertEquals(21, wire.getValue("steps").jsonPrimitive.int)
            assertEquals(4.2f, wire.getValue("scale").jsonPrimitive.float)
            assertEquals(.25f, wire.getValue("cfg_rescale").jsonPrimitive.float)
            assertEquals("k_euler", wire.getValue("sampler").jsonPrimitive.content)
            assertEquals(58.0 * kotlin.math.sqrt(8.0 * 8 / (104 * 152)), wire.getValue("skip_cfg_above_sigma").jsonPrimitive.double, 1e-9)
            assertEquals(64, wire.getValue("width").jsonPrimitive.int); assertEquals(64, wire.getValue("height").jsonPrimitive.int)
            assertEquals(f.draft.baseCaption, body.getValue("input").jsonPrimitive.content)
            assertTrue(Files.isRegularFile(f.root.resolve(result.path)))
            assertEquals(f.post, f.app.jsonFileStorage.loadEntity("moment_posts", f.post.id, MomentPost.serializer()))
            assertEquals(1, f.server.requestCount)
        } finally { f.close() }
    }

    private class Fixture {
        val parent = Files.createTempDirectory("moment-image-usage-")
        val root = Files.createDirectory(parent.resolve("data"))
        val rawSecrets = InMemoryDesktopSecretStore().apply { save(DesktopCredentialKey.NovelAiToken, "synthetic-image-key") }
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { rawSecrets })
        val secrets = app.desktopSecretStore
        val records = Collections.synchronizedList(mutableListOf<DesktopUsageRecord>())
        val diagnostics = DesktopTransportDiagnosticsOwner(usageSink = { records.add(it) })
        val server = MockWebServer()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()
        val resources = DesktopCharacterResourceStore(root)
        val cleanup = DesktopMomentImageCleanup(root, app.jsonFileStorage, app.dataOperationCoordinator,
            DesktopOwnedImageCleanup(root, resources, app.dataOperationCoordinator))
        val text = object : MomentTextGateway {
            override suspend fun completeText(messages: List<ChatApiMessage>, modelConfig: ModelConfig,
                operation: MomentTextOperation): String = error("Completed checkpoint must not call text AI")
            override fun streamText(messages: List<ChatApiMessage>, modelConfig: ModelConfig,
                operation: MomentTextOperation): Flow<StreamEvent> = error("Completed checkpoint must not call text AI")
        }
        val generation = DesktopMomentGeneration(text, app.effectiveModelResolver, app.settingsRepository, secrets,
            { error("Completed prompt must not open designer") }, resources, cleanup, { "" }, diagnostics, { client })
        val card = CharacterCard("card", "Synthetic", createdAt = 1, updatedAt = 1)
        val session = ChatSession("session", card.id, "Synthetic", modelId = "model", imageModelId = "model", createdAt = 1, updatedAt = 1)
        val post = MomentPost("post", card.id, session.id, senderName = "Synthetic", text = "Retained text", scheduledAt = 1, generatedAt = 1)
        val draft = NovelAiImageRegenerationDraft("synthetic-private-prompt", emptyList(), "synthetic-negative", "SQUARE", 64, 64)
        suspend fun prepare() {
            app.jsonFileStorage.saveEntity("character_cards", card.id, card, CharacterCard.serializer())
            app.jsonFileStorage.saveEntity("chat_sessions", session.id, session, ChatSession.serializer())
            app.jsonFileStorage.saveEntity("moment_posts", post.id, post, MomentPost.serializer())
            val model = ModelConfig("model", "Synthetic", "https://synthetic.invalid/v1", "", "synthetic", createdAt = 1)
            secrets.save(DesktopCredentialKey.ModelApiKey(model.id), "synthetic-text-key")
            app.jsonFileStorage.saveEntity("model_configs", model.id, model, ModelConfig.serializer())
            app.settingsRepository.updateAppSettings { it.copy(momentsEnabled = true, momentsImagesEnabled = true) }
        }
        fun rows() = synchronized(records) { records.groupBy { it.id }.values.map { it.maxBy { row -> row.revision } } }
        suspend fun close() {
            generation.closeAndDrain(); server.close(); app.close(); check(parent.toFile().deleteRecursively())
        }
    }
    private fun image() = DesktopImageEditing.png(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB))
    private fun response(bytes: ByteArray): MockResponse {
        val pack = MessagePack.newDefaultBufferPacker()
        pack.packMapHeader(2); pack.packString("event_type"); pack.packString("final")
        pack.packString("image"); pack.packBinaryHeader(bytes.size); pack.writePayload(bytes); pack.close()
        val body = pack.toByteArray()
        return MockResponse().setBody(Buffer().write(ByteBuffer.allocate(body.size + 4).putInt(body.size).put(body).array()))
    }

    @Test fun `resumed original moment core image factory records actual network independently of checkpoint text and local save`() = runBlocking {
        val f = Fixture()
        try {
            f.prepare(); val png = image(); f.server.enqueue(response(png))
            val checkpoint = Json.encodeToString(MomentGenerationCheckpoint.serializer(), MomentGenerationCheckpoint(
                draft = MomentDraft(senderName = "Synthetic", text = "Retained text", imageBrief = "Synthetic brief"),
                imagePrompt = f.draft.toPromptPlan()))
            val context = DesktopUsageContext(DesktopUsageSource.MOMENTS, taskId = "scheduled-task", sessionId = f.session.id, cardId = f.card.id)
            val credential = DesktopCredentialCall(f.secrets, DesktopCredentialKey.NovelAiToken, "frozen", "synthetic-image-key", "account")
            val canary = Files.writeString(f.root.resolve("canary"), "original")
            withContext(DesktopUsageParent(context) + credential) {
                assertFails {
                    f.generation.generate(f.card, f.session, listOf(ChatMessage("m", f.session.id, MessageRole.USER, "Synthetic", createdAt = 1, updatedAt = 1)),
                        null, f.app.settingsRepository.getAppSettings(), MomentTask.create(f.card.id, f.session.id, 1), checkpoint,
                        { error("Completed checkpoint must not repeat design") }, {}, { error("synthetic-local-save-refusal") })
                }
            }
            val row = f.rows().single()
            assertEquals(DesktopUsageOutcome.COMPLETED, row.outcome); assertEquals(DesktopUsageMediaKind.NAI_IMAGE_GENERATION, row.media!!.kind)
            assertEquals(1L, row.media!!.outputImages); assertEquals(png.size.toLong(), row.media!!.outputImageBytes)
            assertEquals("account", row.accountId); assertEquals("scheduled-task", row.taskId); assertEquals(f.session.id, row.sessionId)
            assertEquals(1, f.server.requestCount); assertEquals("original", Files.readString(canary))
            assertEquals(f.post, f.app.jsonFileStorage.loadEntity("moment_posts", f.post.id, MomentPost.serializer()))
            assertEquals(DesktopTaskStatus.COMPLETED, f.diagnostics.entries.value.single().status)
        } finally { f.close() }
    }

    @Test fun `manual moment image original worker supplies exact post session card and work identity`() = runBlocking {
        val f = Fixture(); var controller: DesktopMomentsController? = null
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            f.prepare()
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    check(release.await(5, TimeUnit.SECONDS)) { "Original image request barrier was not released" }
                    return response(image())
                }
            }
            val accounts = f.app.apiAccounts
            val a = accounts.saveAccount(DesktopAccountService.NOVELAI, null, "A", "NovelAI", "", "synthetic-A")
            val b = accounts.saveAccount(DesktopAccountService.NOVELAI, null, "B", "NovelAI", "", "synthetic-B")
            accounts.select(DesktopAccountService.NOVELAI, a)
            val owner = DesktopMomentsController(DesktopMomentStore(f.app.jsonFileStorage, f.app.dataOperationCoordinator),
                f.app.settingsRepository, f.app.characterRepository, f.app.chatRepository, f.app.dataOperationCoordinator, f.app.taskRuntime,
                DesktopMomentGenerator { _, _, _, _, _, _, _, _, _, _ -> error("No whole moment generation") },
                imageGenerator = f.generation)
            controller = owner
            withTimeout(5000) { owner.state.first { it.enabled } }
            owner.generateImage(f.post, f.draft)
            val request = assertNotNull(withContext(Dispatchers.IO) { f.server.takeRequest(5, TimeUnit.SECONDS) })
            assertTrue(owner.workRows.value.single().active)
            accounts.select(DesktopAccountService.NOVELAI, b)
            release.countDown()
            val work = withTimeout(5000) { owner.workRows.first { it.isNotEmpty() && it.none { row -> row.active } } }.single()
            assertEquals(DesktopWorkPhase.COMPLETED, work.phase)
            val row = f.rows().single()
            assertEquals(work.id, row.taskId); assertEquals(f.post.id, row.subjectId)
            assertEquals(f.card.id, row.cardId); assertEquals(f.session.id, row.sessionId); assertEquals(DesktopUsageSource.MOMENTS, row.source)
            assertEquals(DesktopUsageOutcome.COMPLETED, row.outcome); assertEquals(1, f.server.requestCount)
            assertEquals("Bearer synthetic-A", request.getHeader("Authorization")); assertEquals(a, row.accountId)
            assertEquals("synthetic-B", accounts.load(DesktopCredentialKey.NovelAiToken))
            val saved = requireNotNull(f.app.jsonFileStorage.loadEntity("moment_posts", f.post.id, MomentPost.serializer()))
            assertEquals(f.post.text, saved.text); assertNotNull(saved.imagePath); assertEquals(f.post.baseLikeCount, saved.baseLikeCount)
        } finally { release.countDown(); controller?.closeAndDrain(); f.close() }
    }

    @Test fun `manual image cancellation and invalid returned raster never become success from outer normal return`() = runBlocking {
        val f = Fixture()
        try {
            f.prepare(); f.server.enqueue(response(byteArrayOf(1, 2, 3)))
            assertFails { f.generation.generate(f.post, f.card, f.session, f.app.settingsRepository.getAppSettings(), f.draft, {}, {}) }
            assertEquals(DesktopUsageOutcome.FAILED, f.rows().single().outcome); assertNull(f.rows().single().media!!.outputImages)
            f.server.takeRequest(5, TimeUnit.SECONDS)
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.IO) { f.generation.generate(f.post, f.card, f.session, f.app.settingsRepository.getAppSettings(), f.draft, {}, {}) }
            assertNotNull(withContext(Dispatchers.IO) { f.server.takeRequest(5, TimeUnit.SECONDS) })
            withTimeout(5000) { job.cancelAndJoin() }
            assertEquals(setOf(DesktopUsageOutcome.FAILED, DesktopUsageOutcome.CANCELLED), f.rows().map { it.outcome }.toSet())
            assertEquals(2, f.server.requestCount)
            assertTrue(f.rows().all { it.counters == DesktopUsageCounters() })
            val encoded = f.rows().joinToString { Json.encodeToString(DesktopUsageRecord.serializer(), it) }
            assertFalse(encoded.contains("synthetic-private-prompt")); assertFalse(encoded.contains("synthetic-image-key"))
            assertEquals(f.post, f.app.jsonFileStorage.loadEntity("moment_posts", f.post.id, MomentPost.serializer()))
        } finally { f.close() }
    }
}
