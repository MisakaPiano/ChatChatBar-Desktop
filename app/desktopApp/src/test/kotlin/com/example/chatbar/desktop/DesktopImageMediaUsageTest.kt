package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.image.*
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.msgpack.core.MessagePack
import kotlin.test.*

class DesktopImageMediaUsageTest {
    private class Fixture(val sink: ((DesktopUsageRecord) -> Unit)? = null) : AutoCloseable {
        val root = Files.createTempDirectory("image-media-usage-")
        val gate = DesktopDataOperationCoordinator()
        val server = MockWebServer()
        val records = Collections.synchronizedList(mutableListOf<DesktopUsageRecord>())
        val diagnostics = DesktopTransportDiagnosticsOwner(usageSink = { sink?.invoke(it) ?: records.add(it) })
        val secrets = InMemoryDesktopSecretStore().apply { save(DesktopCredentialKey.NovelAiToken, "synthetic-original-key") }
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()
        val store = DesktopNovelAiGenerationStore(JsonFileStorage(root, gate), DesktopCharacterResourceStore(root), gate)
        fun runtime(persist: suspend (List<ByteArray>, NovelAiGenerationRecipe) -> NovelAiGenerationHistoryEntry = store::persist) =
            DesktopNovelAiGenerationRuntime(secrets, persist, client, diagnostics)
        fun rows(): List<DesktopUsageRecord> = synchronized(records) { records.groupBy { it.id }.values.map { versions -> versions.maxBy { it.revision } } }
        override fun close() {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            check(client.dispatcher.executorService.awaitTermination(10, TimeUnit.SECONDS))
            server.close()
            runBlocking { gate.closeAndDrain() }
            check(root.toFile().deleteRecursively())
        }
    }
    private fun draft(count: Int = 1) = NovelAiStudioDraft(basePrompt = "synthetic-private-prompt", negativePrompt = "synthetic-private-negative",
        selectedModel = NovelAiImageModel.V5_FULL, v5Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, count = count))
    private fun png() = DesktopImageEditing.png(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB))
    private fun frame(bytes: ByteArray, type: String = "final"): ByteArray {
        val pack = MessagePack.newDefaultBufferPacker()
        pack.packMapHeader(2); pack.packString("event_type"); pack.packString(type)
        pack.packString("image"); pack.packBinaryHeader(bytes.size); pack.writePayload(bytes); pack.close()
        val body = pack.toByteArray()
        return ByteBuffer.allocate(body.size + 4).putInt(body.size).put(body).array()
    }

    @Test fun `original 429 retry freezes account parent seed and counts validated final images not frames or tokens`() = runBlocking {
        Fixture().use { f ->
            val image = png()
            f.server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
            f.server.enqueue(MockResponse().setBody(Buffer().write(frame(image, "intermediate") + frame(image) + frame(image))))
            val parent = DesktopUsageContext(DesktopUsageSource.CHAT, taskId = "actual-task", sessionId = "actual-session", cardId = "actual-card", subjectId = "actual-message")
            val credential = DesktopCredentialCall(f.secrets, DesktopCredentialKey.NovelAiToken, "frozen", "synthetic-original-key", "account-A")
            withContext(DesktopUsageParent(parent) + credential) {
                val result = f.runtime().generate(draft(2), maxRateLimitRetries = 1, onRetry = { _, _ ->
                    f.secrets.save(DesktopCredentialKey.NovelAiToken, "synthetic-switched-key")
                })
                assertEquals(2, result.images.size)
            }
            assertEquals(2, f.server.requestCount)
            val requests = List(2) { f.server.takeRequest(5, TimeUnit.SECONDS)!! }
            assertTrue(requests.all { it.getHeader("Authorization") == "Bearer synthetic-original-key" })
            val seeds = requests.map { Json.parseToJsonElement(it.body.readUtf8()).jsonObject.getValue("parameters").jsonObject.getValue("seed") }
            assertEquals(seeds[0], seeds[1])
            val rows = f.rows().sortedBy { it.attempt }
            assertEquals(listOf(DesktopUsageOutcome.RETRIED, DesktopUsageOutcome.COMPLETED), rows.map { it.outcome })
            assertEquals(1, rows.map { it.requestId }.distinct().size)
            assertTrue(rows.all { it.accountId == "account-A" && it.sessionId == "actual-session" && it.cardId == "actual-card" && it.taskId == "actual-task" && it.subjectId == "actual-message" })
            assertEquals(listOf(429, 200), rows.map { it.media!!.httpStatus })
            assertNull(rows[0].media!!.outputImages)
            assertEquals(2L, rows[1].media!!.outputImages); assertEquals(image.size * 2L, rows[1].media!!.outputImageBytes)
            val totals = summarizeDesktopUsage(rows)
            assertEquals(0, totals.prompt.unreportedAttempts); assertEquals(4L, totals.media.single().requestedImages.value)
            val store = DesktopProviderUsageStore(f.root, f.gate)
            rows.forEach { store.save(it) }
            assertEquals(rows.toSet(), DesktopProviderUsageStore(f.root, f.gate).records().toSet())
            val encoded = rows.joinToString { Json.encodeToString(DesktopUsageRecord.serializer(), it) }
            for (privateText in listOf("synthetic-original-key", "synthetic-switched-key", "synthetic-private-prompt", "synthetic-private-negative", "base64")) assertFalse(encoded.contains(privateText))
        }
    }

    @Test fun `rejection bad frames and invalid preflight keep their real request counts and unknown output`() = runBlocking {
        for (code in listOf(401, 503, 200)) Fixture().use { f ->
            f.server.enqueue(MockResponse().setResponseCode(code).setBody("synthetic-invalid-body"))
            assertFailsWith<DesktopNovelAiRequestException> { f.runtime().generate(draft(), maxRateLimitRetries = 0) }
            assertEquals(1, f.server.requestCount)
            val row = f.rows().single()
            assertEquals(DesktopUsageOutcome.FAILED, row.outcome); assertEquals(code, row.media!!.httpStatus)
            assertNull(row.media!!.outputImageBytes); assertNull(row.media!!.outputImages)
        }
        Fixture().use { f ->
            assertFails { f.runtime().generate(draft(0), maxRateLimitRetries = 0) }
            assertFailsWith<CancellationException> { withContext(Job().apply { cancel() }) { f.runtime().generate(draft()) } }
            assertEquals(0, f.server.requestCount); assertTrue(f.rows().isEmpty()); assertTrue(f.diagnostics.entries.value.isEmpty())
        }
    }

    @Test fun `cancelled dispatched call retains unknown response after log clear without restarting or resurrecting logs`() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.IO + DesktopUsageParent(DesktopUsageContext(DesktopUsageSource.CHAT, sessionId = "session"))) {
                f.runtime().generate(draft(), maxRateLimitRetries = 0)
            }
            assertNotNull(withContext(Dispatchers.IO) { f.server.takeRequest(5, TimeUnit.SECONDS) })
            f.diagnostics.clear("session")
            withTimeout(5_000) { job.cancelAndJoin() }
            val row = f.rows().single()
            assertEquals(1, f.server.requestCount); assertEquals(DesktopUsageOutcome.CANCELLED, row.outcome)
            assertNull(row.media!!.httpStatus); assertNull(row.media!!.outputImages)
            assertTrue(f.diagnostics.entries.value.isEmpty())
        }
    }

    @Test fun `local persistence refusal does not erase completed network output or replay generation`() = runBlocking {
        Fixture().use { f ->
            val image = png(); f.server.enqueue(MockResponse().setBody(Buffer().write(frame(image))))
            val retained = f.root.resolve("original-canary"); Files.writeString(retained, "original")
            assertFailsWith<DesktopNovelAiRequestException> {
                f.runtime { _, _ -> error("synthetic-local-publish-refusal") }.generate(draft(), maxRateLimitRetries = 0)
            }
            val row = f.rows().single()
            assertEquals(DesktopUsageOutcome.COMPLETED, row.outcome); assertEquals(1L, row.media!!.outputImages)
            assertEquals(image.size.toLong(), row.media!!.outputImageBytes)
            assertEquals("original", Files.readString(retained)); assertEquals(1, f.server.requestCount)
        }
    }

    @Test fun `character image generation shares actual parent and numeric failure cannot fail or replay output`() = runBlocking {
        Fixture { error("synthetic-private-sink-failure") }.use { f ->
            val image = png(); f.server.enqueue(MockResponse().setBody(Buffer().write(frame(image))))
            val owner = DesktopCharacterImageGeneration(f.secrets, f.client, f.diagnostics)
            val result = withContext(DesktopUsageParent(DesktopUsageContext(DesktopUsageSource.CHARACTER_CARD,
                editorSessionId = "draft", cardId = "card", personId = "person"))) {
                owner.generate(NovelAiPromptPlan("synthetic", emptyList()), CharacterAvatarImagePolicy.imageSize, NovelAiGenerationSettings.legacy(123))
            }
            assertContentEquals(image, result); assertEquals(1, f.server.requestCount)
            val log = f.diagnostics.entries.value.single()
            assertEquals("draft", log.sessionId); assertEquals(DesktopTaskStatus.COMPLETED, log.status)
            assertNotNull(log.error); assertFalse(log.error!!.contains("synthetic-private-sink-failure"))
            assertEquals(1L, log.image!!.outputImages); assertTrue(f.rows().isEmpty())
        }
    }
}
