package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.*

class DesktopEmbeddingUsageTest {
    private class Fixture {
        val root = Files.createTempDirectory("embedding-usage-")
        val server = MockWebServer().apply { start() }
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(Files.createDirectory(root.resolve("data")),
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        val config = EmbeddingConfig("default", "Synthetic account", server.url("/v1").toString(),
            "FAKE_EMBEDDING_KEY", "synthetic-embedding", 2)
        val batch = DesktopIndexEmbeddingBatch(DesktopRagEmbeddingIdentity.from(config), 0,
            listOf("PRIVATE_VECTOR_INPUT" to emptyMap()))
        suspend fun configure() {
            app.settingsRepository.updateAppSettings { it.copy(allowCleartextModelApi = true) }
            app.modelRepository.saveEmbeddingModel(config)
        }
        suspend fun records(): List<DesktopUsageRecord> {
            app.embeddingGateway.closeAndDrain()
            app.providerUsage.closeAndDrain()
            return DesktopProviderUsageStore(app.appDataRoot, app.dataOperationCoordinator).records()
        }
        suspend fun close() { app.close(); server.shutdown(); root.toFile().deleteRecursively() }
    }

    @Test fun `actual container gateway persists numeric zero with its real document task parent and no private payload`() = runBlocking {
        val f = Fixture()
        try {
            f.configure()
            f.server.enqueue(MockResponse().setBody("""{"data":[{"index":0,"embedding":[1,0]}],"usage":{"prompt_tokens":0,"prompt_tokens_details":{"cached_tokens":0}},"private":"PRIVATE_PROVIDER_RESPONSE"}"""))
            assertEquals(listOf(listOf(1f, 0f)), f.app.taskRuntime.withDocumentPreparation("actual-card", "actual-document") {
                f.app.embeddingGateway.embed(f.batch)
            })
            val row = f.records().single()
            assertEquals(DesktopUsageSource.CHARACTER_CARD, row.source)
            assertNotNull(UUID.fromString(row.taskId)); assertNull(row.sessionId)
            assertEquals("actual-card", row.cardId); assertEquals("actual-document", row.subjectId)
            assertEquals(f.config.id, row.modelId); assertEquals(f.config.modelName, row.modelName)
            assertNull(row.provider); assertNull(row.accountId)
            assertEquals(DesktopUsageOutcome.COMPLETED, row.outcome)
            assertEquals(0L, row.counters.prompt); assertEquals(0L, row.counters.cacheHit)
            assertNull(row.counters.completion); assertEquals(1, row.attempt)
            val raw = Files.walk(f.app.appDataRoot.resolve("diagnostics/provider-usage")).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".json") }.toList().joinToString { Files.readString(it) }
            }
            listOf("PRIVATE_VECTOR_INPUT", "PRIVATE_PROVIDER_RESPONSE", f.config.apiKey, f.config.baseUrl,
                "\"embedding\"", "\"data\"").forEach { assertFalse(raw.contains(it), it) }
            assertEquals(1, f.server.requestCount)
        } finally { f.close() }
    }

    @Test fun `missing or malformed optional usage remains unknown without failing valid vectors`() = runBlocking {
        val f = Fixture()
        try {
            f.configure()
            for (usage in listOf("", ",\"usage\":{\"prompt_tokens\":{\"private\":\"ignored\"}}")) {
                f.server.enqueue(MockResponse().setBody("""{"data":[{"index":0,"embedding":[1,0]}]$usage}"""))
                assertEquals(listOf(listOf(1f, 0f)), f.app.embeddingGateway.embed(f.batch))
            }
            val rows = f.records(); assertEquals(2, rows.size)
            assertTrue(rows.all { it.outcome == DesktopUsageOutcome.COMPLETED && it.counters.prompt == null })
            assertTrue(rows.all { it.source == DesktopUsageSource.OTHER && it.taskId == null && it.sessionId == null })
        } finally { f.close() }
    }

    @Test fun `invalid vector keeps reported usage while explicit HTTP rejection stays unknown and is not retried`() = runBlocking {
        val f = Fixture()
        try {
            f.configure()
            f.server.enqueue(MockResponse().setBody("""{"data":[{"index":0,"embedding":[1]}],"usage":{"prompt_tokens":7}}"""))
            assertFailsWith<IllegalStateException> { f.app.embeddingGateway.embed(f.batch) }
            f.server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            f.server.enqueue(MockResponse().setBody("""{"data":[{"index":0,"embedding":[1,0]}]}"""))
            assertFailsWith<IllegalStateException> { f.app.embeddingGateway.embed(f.batch) }
            val rows = f.records(); assertEquals(2, rows.size)
            assertTrue(rows.all { it.outcome == DesktopUsageOutcome.FAILED && it.attempt == 1 })
            assertEquals(listOf(7L), rows.mapNotNull { it.counters.prompt })
            assertEquals(2, f.server.requestCount)
        } finally { f.close() }
    }

    @Test fun `cancellation retains one uncertain attempt and real chat identities without a replay`() = runBlocking {
        val f = Fixture()
        try {
            f.configure()
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val parent = DesktopUsageContext(DesktopUsageSource.CHAT, taskId = "actual-chat-task", sessionId = "actual-session",
                cardId = "actual-card", subjectId = "actual-user-message")
            val work = async(Dispatchers.IO + DesktopUsageParent(parent)) { f.app.embeddingGateway.embed(f.batch) }
            assertNotNull(withContext(Dispatchers.IO) { f.server.takeRequest(5, TimeUnit.SECONDS) })
            work.cancelAndJoin()
            val row = f.records().single()
            assertEquals(DesktopUsageOutcome.CANCELLED, row.outcome); assertNull(row.counters.prompt)
            assertEquals(parent.taskId, row.taskId); assertEquals(parent.sessionId, row.sessionId)
            assertEquals(parent.cardId, row.cardId); assertEquals(parent.subjectId, row.subjectId)
            assertEquals(1, f.server.requestCount)
        } finally { f.close() }
    }

    @Test fun `pinned config mismatch and predispatch refusal produce no numeric or HTTP attempts`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val config = EmbeddingConfig("default", "Synthetic", server.url("/v1").toString(), "fake", "test", 2)
        val rows = mutableListOf<DesktopUsageRecord>()
        val gateway = DesktopEmbeddingGateway({ config }, { true }, beforeRequest = { error("SYNTHETIC_ADMISSION_REFUSAL") }, usageSink = { rows += it })
        try {
            val batch = DesktopIndexEmbeddingBatch(DesktopRagEmbeddingIdentity.from(config), 0, listOf("synthetic" to emptyMap()))
            assertFails { gateway.pinned(config.copy(apiKey = "different")).embed(batch) }
            assertFails { gateway.embed(batch) }
            assertTrue(rows.isEmpty()); assertEquals(0, server.requestCount)
        } finally { gateway.closeAndDrain(); server.shutdown() }
    }
}
