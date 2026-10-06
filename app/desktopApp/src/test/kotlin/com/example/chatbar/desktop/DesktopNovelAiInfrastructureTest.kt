package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.prompt.*
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.msgpack.core.MessagePack
import kotlin.test.*

class DesktopNovelAiInfrastructureTest {
    @Test fun `JDBC uses official research filtering ranking translation and ranked completion`(): Unit = runBlocking {
        val path = Files.createTempFile("p7-inline-catalog-", ".sqlite")
        try {
            DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { sql ->
                sql.execute("CREATE TABLE tags(name TEXT, cn_name TEXT, post_count INTEGER, category INTEGER)")
                sql.execute("INSERT INTO tags VALUES ('blue_hair','蓝发',100,0),('blue_artist','蓝画师',999,1),('blue_hat','蓝帽',50,0)")
                sql.execute("CREATE TABLE grams(gram INTEGER PRIMARY KEY,n INTEGER,ranks BLOB)")
                sql.execute("CREATE TABLE entries(rank INTEGER PRIMARY KEY,name TEXT,cn_name TEXT,post_count INTEGER,category INTEGER,a TEXT,b TEXT)")
                sql.execute("INSERT INTO entries VALUES (1,'blue_artist','蓝画师',999,1,'blue_artist','蓝画师'),(2,'blue_hair','蓝发',100,0,'blue_hair','蓝发')")
                completionGrams("blue").filter { it > 0x10000 }.forEach { gram ->
                    sql.execute("INSERT INTO grams VALUES ($gram,2,X'0101')")
                }
            } }
            DesktopNovelAiSqlDatabase(path).use { db ->
                assertEquals(listOf("blue_hair", "blue_hat"), queryNovelAiCandidates(db, "tags", "blue", 8).map { it.name })
                assertEquals(mapOf("blue_hair" to "蓝发"), queryNovelAiTranslations(db, "tags", listOf("blue hair")))
                val found = mutableListOf<NovelAiTagCandidate>()
                searchSharedRankedIndex(db, "blue", false) { found += it }
                assertEquals(listOf("blue_artist", "blue_hair"), found.map { it.name })
                assertFails { db.rawQuery("DELETE FROM tags RETURNING name", emptyArray()).close() }
            }
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun `Desktop auxiliary adapter preserves generate roles but merges planning and repair`(): Unit = runBlocking {
        MockWebServer().use { server ->
            val transport = DesktopNovelAiTextTransport(OpenAiStreamingTransport { true })
            val input = listOf(ChatApiMessage.text("system", "fixture system"), ChatApiMessage.text("user", "scene"),
                ChatApiMessage.text("assistant", "planned scene"), ChatApiMessage.text("user", "final requirement"))
            val model = ModelConfig(id = "inline", displayName = "Inline", apiKey = "fixture-key", modelName = "fixture", baseUrl = server.url("v1").toString(), createdAt = 1)
            NovelAiTextStage.entries.forEach { stage ->
                server.enqueue(sse("fixture result"))
                assertEquals("fixture result", transport.completeTextStreaming(stage, input, model, {}, {}))
                val body = Json.parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
                val actual = body.getValue("messages")
                val expected = AuxiliaryMessageAssembler.assemble(input, stage == NovelAiTextStage.GENERATE)
                assertEquals(expected.map { it.role }, actual.jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content })
                assertEquals(expected.map { it.content }, actual.jsonArray.map { it.jsonObject.getValue("content") })
            }
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n"))
            assertFailsWith<IllegalStateException> { transport.streamText(NovelAiTextStage.GENERATE, input, model).toList() }
        }
    }

    @Test fun `fake V5 batch has fixed launch seed and one durable authority across restart`(): Unit = runBlocking {
        val root = Files.createTempDirectory("p7-batch-")
        try {
            val secrets = InMemoryDesktopSecretStore().apply { save(DesktopCredentialKey.NovelAiToken, "fake-only-secret") }
            val gate = DesktopDataOperationCoordinator()
            val storage = JsonFileStorage(root, gate)
            val resources = DesktopCharacterResourceStore(root)
            val png = DesktopImageEditing.png(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB))
            var sentSeed = -1L
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                assertEquals("Bearer fake-only-secret", chain.request().header("Authorization"))
                val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer)
                val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                assertEquals(NovelAiImageModel.V5_FULL.apiId, body.getValue("model").jsonPrimitive.content)
                val params = body.getValue("parameters").jsonObject
                assertEquals(2, params.getValue("n_samples").jsonPrimitive.int)
                sentSeed = params.getValue("seed").jsonPrimitive.long
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fake")
                    .body((frame(png) + frame(png)).toResponseBody()).build()
            }.build()
            val runtime = DesktopNovelAiGenerationRuntime(secrets, DesktopNovelAiGenerationStore(storage, resources, gate)::persist, client)
            val draft = NovelAiStudioDraft(basePrompt = "inline fixture", negativePrompt = "inline", selectedModel = NovelAiImageModel.V5_FULL,
                v5Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, count = 2))
            val entry = runtime.generate(draft, maxRateLimitRetries = 0)
            assertEquals(listOf(sentSeed, sentSeed + 1), entry.images.map { it.seed })
            assertEquals(NovelAiSeedMode.FIXED, entry.recipe.settings.seedMode)
            assertEquals(entry, JsonFileStorage(root).loadEntity(DesktopNovelAiGenerationStore.KEY, entry.id, NovelAiGenerationHistoryEntry.serializer()))
            entry.images.forEach { assertContentEquals(png, resources.readBytes(it.path)) }
            assertFalse(Json.encodeToString(NovelAiGenerationHistoryEntry.serializer(), entry).contains("fake-only-secret"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `partial batch save failure rolls back only new exact files`(): Unit = runBlocking {
        val root = Files.createTempDirectory("p7-batch-failure-")
        try {
            val gate = DesktopDataOperationCoordinator()
            val store = DesktopNovelAiGenerationStore(JsonFileStorage(root, gate), DesktopCharacterResourceStore(root), gate)
            Files.createDirectories(root.resolve("images"))
            Files.writeString(root.resolve("images/existing.png"), "unrelated")
            val png = DesktopImageEditing.png(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB))
            assertFails { store.persist(listOf(png, byteArrayOf(0)), NovelAiGenerationRecipe(settings = NovelAiGenerationSettings(count = 2))) }
            assertEquals(listOf("existing.png"), Files.list(root.resolve("images")).use { it.map { p -> p.fileName.toString() }.toList() })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `generation cancellation interrupts mock HTTP and never persists partial output`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val secrets = InMemoryDesktopSecretStore().apply { save(DesktopCredentialKey.NovelAiToken, "fake-cancel") }
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("generate")).build())
            }.build()
            val runtime = DesktopNovelAiGenerationRuntime(secrets, { _, _ -> error("must not persist") }, client)
            val job = launch(Dispatchers.IO) { runtime.generate(NovelAiStudioDraft(basePrompt = "inline"), maxRateLimitRetries = 0) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            withTimeout(5_000) { job.cancelAndJoin() }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `production rate retry is bounded while auth and cost errors never retry or expose provider body`(): Unit = runBlocking {
        for (status in listOf(401, 402, 403, 429)) {
            var attempts = 0
            val secret = "fake-provider-reflection"
            val secrets = InMemoryDesktopSecretStore().apply { save(DesktopCredentialKey.NovelAiToken, secret) }
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                attempts++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fake")
                    .header("Retry-After", "0").body(secret.toResponseBody()).build()
            }.build()
            val runtime = DesktopNovelAiGenerationRuntime(secrets, { _, _ -> error("must not persist") }, client)
            val error = assertFailsWith<DesktopNovelAiRequestException> {
                runtime.generate(NovelAiStudioDraft(basePrompt = "inline"), maxRateLimitRetries = 1)
            }
            assertEquals(if (status == 429) 2 else 1, attempts)
            assertFalse(error.stackTraceToString().contains(secret))
        }
    }

    private fun sse(text: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
    private fun frame(bytes: ByteArray): ByteArray {
        val pack = MessagePack.newDefaultBufferPacker()
        pack.packMapHeader(2); pack.packString("event_type"); pack.packString("final")
        pack.packString("image"); pack.packBinaryHeader(bytes.size); pack.writePayload(bytes); pack.close()
        val data = pack.toByteArray()
        return ByteBuffer.allocate(data.size + 4).putInt(data.size).put(data).array()
    }
}
