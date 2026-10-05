package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.image.*
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.msgpack.core.MessagePack
import kotlin.test.*

class DesktopNovelAiSafetyTest {
    private val settings = NovelAiGenerationSettings(aspectRatio = NovelAiAspectRatio.SQUARE)
    private val prompt = NovelAiPromptPlan("inline test scene", emptyList(), negativePrompt = "inline")
    private val opus = NovelAiAccountUsage(100, 3, true, null, false)
    private val token = "fake-p7-test-credential-123"

    @Test fun `free gate rejects every disallowed mode and ambiguous account`() {
        DesktopNovelAiLivePolicy.requireFree(settings, NovelAiPreparedImageGuidance.NONE, opus)
        listOf(settings.copy(model = NovelAiImageModel.V5_FULL), settings.copy(count = 2), settings.copy(steps = 29),
            settings.copy(aspectRatio = NovelAiAspectRatio.PORTRAIT), settings.copy(sizeTier = NovelAiSizeTier.LARGE),
            settings.copy(customWidth = 1024, customHeight = 1024)).forEach {
            assertFailsWith<IllegalArgumentException> { DesktopNovelAiLivePolicy.requireFree(it, NovelAiPreparedImageGuidance.NONE, opus) }
        }
        listOf(NovelAiPreparedImageGuidance(NovelAiGenerationAction.IMAGE_TO_IMAGE),
            NovelAiPreparedImageGuidance(NovelAiGenerationAction.INPAINT),
            NovelAiPreparedImageGuidance.NONE.copy(preciseReferenceBase64 = "inline"),
            NovelAiPreparedImageGuidance.NONE.copy(vibes = listOf(NovelAiPreparedVibeReference("inline", 1f, 1f)))).forEach {
            assertFailsWith<IllegalArgumentException> { DesktopNovelAiLivePolicy.requireFree(settings, it, opus) }
        }
        listOf(opus.copy(active = false), opus.copy(tier = 2)).forEach {
            assertFailsWith<IllegalArgumentException> { DesktopNovelAiLivePolicy.requireFree(settings, NovelAiPreparedImageGuidance.NONE, it) }
        }
    }

    @Test fun `durable fuse enforces interval window phase cap and cross instance concurrency`() = root { dir ->
        var now = 1_000_000L
        fun fuse() = DesktopNovelAiLiveFuse(dir) { now }
        fuse().acquire().use { lease ->
            assertFailsWith<IllegalStateException> { fuse().acquire() }
            lease.reserve(); lease.finish(true)
        }
        fuse().acquire().use { assertFailsWith<IllegalStateException> { it.reserve() } }
        now += 30_000
        fuse().acquire().use { it.reserve(); it.finish(true) }
        now += 30_000
        fuse().acquire().use { assertFailsWith<IllegalStateException> { it.reserve() } }
        repeat(6) { now += 300_000; fuse().acquire().use { it.reserve(); it.finish(true) } }
        now += 300_000
        fuse().acquire().use { assertFailsWith<IllegalStateException> { it.reserve() } }
        val saved = Json.parseToJsonElement(Files.readString(dir.resolve("phase7-requests.json"))).jsonObject
        assertEquals(8, saved.getValue("requests").jsonArray.size)
    }

    @Test fun `crash corrupt and failed records never reset themselves`() = root { dir ->
        DesktopNovelAiLiveFuse(dir).acquire().use { it.reserve() }
        DesktopNovelAiLiveFuse(dir).acquire().use { assertFailsWith<IllegalStateException> { it.reserve() } }
        Files.writeString(dir.resolve("phase7-requests.json"), "{broken")
        DesktopNovelAiLiveFuse(dir).acquire().use { assertFailsWith<IllegalStateException> { it.reserve() } }
        assertEquals("{broken", Files.readString(dir.resolve("phase7-requests.json")))
    }

    @Test fun `credential UI stores and reloads status without JSON or state disclosure`() = runBlocking { root { dir ->
        val secrets = InMemoryDesktopSecretStore()
        val controller = DesktopNovelAiSettingsController(secrets, SettingsRepository(JsonFileStorage(dir)))
        controller.saveCredential(token)
        assertEquals(token, secrets.load(DesktopCredentialKey.NovelAiToken))
        controller.selectModel(NovelAiImageModel.V5_FULL)
        controller.load()
        assertTrue(controller.state.value.credentialPresent)
        assertFalse(controller.state.value.toString().contains(token))
        Files.walk(dir).use { paths -> paths.filter(Files::isRegularFile).forEach { assertFalse(Files.readString(it).contains(token)) } }
        controller.clearCredential()
        assertNull(secrets.load(DesktopCredentialKey.NovelAiToken))
    } }

    @Test fun `credential store exceptions are not reflected by controller`() = runBlocking { root { dir ->
        val controller = DesktopNovelAiSettingsController(object : DesktopSecretStore {
            override fun load(key: DesktopCredentialKey): String? = error(token)
            override fun save(key: DesktopCredentialKey, secret: String) { error(secret) }
            override fun delete(key: DesktopCredentialKey) { error(token) }
        }, SettingsRepository(JsonFileStorage(dir)))
        controller.saveCredential(token)
        assertNotNull(controller.state.value.error)
        assertFalse(controller.state.value.toString().contains(token))
    } }

    @Test fun `no confirmation means zero HTTP and no generation journal`() = runBlocking { root { dir ->
        val calls = AtomicInteger()
        val runtime = runtime(dir, client(calls) { _, _ -> 200 to subscription() })
        assertFailsWith<IllegalStateException> { runtime.liveSmoke(false, prompt, settings) }
        assertEquals(0, calls.get())
        assertFalse(Files.exists(dir.resolve("phase7-requests.json")))
    } }

    @Test fun `fake HTTP 429 is one request with no retry no secret error and durable stop`() = runBlocking { root { dir ->
        val calls = AtomicInteger(); val generations = AtomicInteger()
        val http = client(calls) { request, _ ->
            assertEquals("Bearer $token", request.header("Authorization"))
            if (request.method == "POST") { generations.incrementAndGet(); 429 to token.toByteArray() }
            else 200 to subscription()
        }
        val failure = assertFailsWith<DesktopNovelAiRequestException> { runtime(dir, http).liveSmoke(true, prompt, settings) }
        assertFalse(failure.stackTraceToString().contains(token))
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.all { it is DesktopNovelAiRequestException })
        assertEquals(1, generations.get())
        assertFailsWith<DesktopNovelAiRequestException> { runtime(dir, http).liveSmoke(true, prompt, settings) }
        assertEquals(1, generations.get()); assertEquals(2, calls.get())
    } }

    @Test fun `auth and ambiguous account block before image generation`() = runBlocking {
        for (response in listOf(401 to token.toByteArray(), 200 to """{"tier":2,"active":true}""".toByteArray(),
            200 to "not json $token".toByteArray())) root { dir ->
            val calls = AtomicInteger()
            val http = client(calls) { request, _ -> assertEquals("GET", request.method); response }
            val error = assertFailsWith<DesktopNovelAiRequestException> { runtime(dir, http).liveSmoke(true, prompt, settings) }
            assertFalse(error.stackTraceToString().contains(token))
            assertFailsWith<DesktopNovelAiRequestException> { runtime(dir, http).liveSmoke(true, prompt, settings) }
            assertEquals(1, calls.get())
        }
    }

    @Test fun `successful fake response hydrates secure token preserves output and records resolved seed`() = runBlocking { root { dir ->
        val calls = AtomicInteger(); var persisted = false; var wireSeed = -1L
        val png = DesktopImageEditing.png(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB))
        val http = client(calls) { request, _ ->
            assertEquals("Bearer $token", request.header("Authorization"))
            if (request.method == "GET") 200 to subscription() else {
                val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                val parameters = body.getValue("parameters").jsonObject
                assertEquals(1, parameters.getValue("n_samples").jsonPrimitive.int)
                wireSeed = parameters.getValue("seed").jsonPrimitive.long
                200 to frame(png)
            }
        }
        val runtime = runtime(dir, http) { bytes, _, saved ->
            assertContentEquals(png, bytes); assertEquals(wireSeed, saved.seed)
            assertEquals(NovelAiSeedMode.FIXED, saved.seedMode)
            persisted = true; "images/fake.png"
        }
        assertEquals("images/fake.png", runtime.liveSmoke(true, prompt, settings))
        assertTrue(persisted); assertEquals(3, calls.get())
        assertFalse(Files.readString(dir.resolve("phase7-requests.json")).contains(token))
    } }

    @Test fun `real secure client disallows redirects retries and foreign destinations`() {
        val http = secureNovelAiClient()
        assertFalse(http.retryOnConnectionFailure); assertFalse(http.followRedirects); assertFalse(http.followSslRedirects)
        assertFailsWith<IllegalStateException> { http.newCall(Request.Builder().url("https://example.invalid/").build()).execute() }
    }

    @Test fun `cancellation stops actual mock HTTP and preserves consumed slot`() = runBlocking { root { dir ->
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(subscription().toString(Charsets.UTF_8)))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val http = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val job = launch(Dispatchers.IO) { runtime(dir, http).liveSmoke(true, prompt, settings) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) })
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) })
            withTimeout(5_000) { job.cancelAndJoin() }
            assertEquals(2, server.requestCount)
            val journal = Json.parseToJsonElement(Files.readString(dir.resolve("phase7-requests.json"))).jsonObject
            assertEquals(1, journal.getValue("requests").jsonArray.size)
            assertTrue(journal.getValue("blocked").jsonPrimitive.boolean)
            assertFailsWith<DesktopNovelAiRequestException> { runtime(dir, http).liveSmoke(true, prompt, settings) }
            assertEquals(2, server.requestCount)
        }
    } }

    @Test fun `Anlas change after fake success prevents artifact publication and blocks future live requests`() = runBlocking { root { dir ->
        val calls = AtomicInteger(); var accountReads = 0
        val http = client(calls) { request, _ ->
            if (request.method == "GET") {
                accountReads++
                200 to if (accountReads == 1) subscription() else """{"tier":3,"active":true,"trainingStepsLeft":99}""".toByteArray()
            } else 200 to frame(DesktopImageEditing.png(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)))
        }
        assertFailsWith<DesktopNovelAiRequestException> { runtime(dir, http).liveSmoke(true, prompt, settings) }
        assertFailsWith<DesktopNovelAiRequestException> { runtime(dir, http).liveSmoke(true, prompt, settings) }
        assertEquals(3, calls.get())
    } }

    @Test fun `owned output and exact regeneration inputs survive storage restart`() = runBlocking { root { dir ->
        val gate = DesktopDataOperationCoordinator()
        val storage = JsonFileStorage(dir, gate)
        val resources = DesktopCharacterResourceStore(dir)
        val store = DesktopNovelAiSmokeStore(storage, resources, gate)
        val png = DesktopImageEditing.png(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB))
        val path = store.persist(png, prompt, settings.copy(seed = 42, seedMode = NovelAiSeedMode.FIXED))
        val reopened = JsonFileStorage(dir).loadSingleton(DesktopNovelAiSmokeStore.KEY, JsonArray.serializer())!!
        val entry = reopened.single().jsonObject
        assertEquals(path, entry.getValue("imagePath").jsonPrimitive.content)
        assertEquals(42L, entry.getValue("settings").jsonObject.getValue("seed").jsonPrimitive.long)
        assertContentEquals(png, resources.readBytes(path))
        assertFalse(entry.toString().contains(token))
    } }

    private fun runtime(dir: Path, http: OkHttpClient,
        persist: suspend (ByteArray, NovelAiPromptPlan, NovelAiGenerationSettings) -> String = { _, _, _ -> error("unexpected persistence") }
    ) = DesktopNovelAiRuntime(InMemoryDesktopSecretStore().apply { save(DesktopCredentialKey.NovelAiToken, token) },
        DesktopNovelAiLiveFuse(dir), persist, http)

    private fun client(calls: AtomicInteger, reply: (Request, Int) -> Pair<Int, ByteArray>) = OkHttpClient.Builder()
        .retryOnConnectionFailure(false).followRedirects(false).addInterceptor { chain ->
            val (code, body) = reply(chain.request(), calls.incrementAndGet())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fake")
                .body(body.toResponseBody()).build()
        }.build()

    private fun subscription() = """{"tier":3,"active":true,"trainingStepsLeft":100}""".toByteArray()
    private fun frame(image: ByteArray): ByteArray {
        val p = MessagePack.newDefaultBufferPacker()
        p.packMapHeader(2); p.packString("event_type"); p.packString("final")
        p.packString("image"); p.packBinaryHeader(image.size); p.writePayload(image); p.close()
        val data = p.toByteArray()
        return ByteBuffer.allocate(4 + data.size).putInt(data.size).put(data).array()
    }
    private inline fun root(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("p7-novelai-mock-")
        try { block(dir) } finally { dir.toFile().deleteRecursively() }
    }
}
