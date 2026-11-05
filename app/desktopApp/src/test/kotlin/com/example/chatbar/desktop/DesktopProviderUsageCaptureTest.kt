package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelCredentialAccount
import com.example.chatbar.domain.chat.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import kotlin.test.*

class DesktopProviderUsageCaptureTest {
    @Test fun `failed close remains retryable while concurrent and repeated successful closes do not reopen a closed data gate`() = runBlocking {
        val root = Files.createTempDirectory("usage-close-idempotence-"); val gate = DesktopDataOperationCoordinator()
        val home = root.resolve("diagnostics/provider-usage")
        Files.createDirectory(home.parent); Files.write(home, byteArrayOf(2))
        val store = DesktopProviderUsageStore(root, gate); val owner = DesktopProviderUsageOwner(store)
        try {
            val record = DesktopUsageRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, 1,
                DesktopUsageSource.CHAT, modelId = "model", modelName = "fixture", startedAt = 1, updatedAt = 1,
                counters = DesktopUsageCounters(prompt = 7))
            owner.submit(record)
            withTimeout(10_000) { owner.state.first { it.error != null } }
            assertEquals(1, assertFailsWith<DesktopUsageDrainException> { owner.closeAndDrain() }.pendingRecords)
            assertEquals(1, owner.state.value.pending)
            assertContentEquals(byteArrayOf(2), Files.readAllBytes(home))
            Files.delete(home)
            coroutineScope { listOf(async { owner.closeAndDrain() }, async { owner.closeAndDrain() }).awaitAll() }
            assertEquals(listOf(record), store.records())
            gate.closeAndDrain()
            owner.closeAndDrain()
            assertEquals(DesktopDataOperationCoordinatorState.CLOSED, gate.state)
            assertEquals(0, owner.state.value.pending)
        } finally { gate.closeAndDrain(); root.toFile().deleteRecursively() }
    }

    @Test fun `production shutdown preserves root lock and data gate until failed numeric tail can retry successfully`() = runBlocking {
        val parent = Files.createTempDirectory("usage-shutdown-recovery-")
        val root = Files.createDirectory(parent.resolve("data")); val gate = DesktopDataOperationCoordinator()
        val resolution = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json"))
        val ownership = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolution)).ownership
        val home = root.resolve("diagnostics/provider-usage")
        Files.createDirectory(home.parent); Files.write(home, byteArrayOf(3))
        val store = DesktopProviderUsageStore(root, gate); val owner = DesktopProviderUsageOwner(store)
        try {
            val record = DesktopUsageRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, 1,
                DesktopUsageSource.CHAT, modelId = "model", modelName = "fixture", startedAt = 1, updatedAt = 1,
                outcome = DesktopUsageOutcome.CANCELLED, counters = DesktopUsageCounters(completion = 6))
            owner.submit(record)
            withTimeout(10_000) { owner.state.first { it.error != null } }
            var retries = 0; var backupsClosed = 0; var migrationClosed = 0
            closeDesktopWithRecovery(close = {
                closeDesktopDataRuntimes(draftRuntimeClose = { owner.closeAndDrain() },
                    runtimeClose = { backupsClosed++ }, coordinatorClose = { gate.closeAndDrain() },
                    migrationServiceClose = { migrationClosed++ })
            }, awaitRetry = {
                retries++
                assertEquals(DesktopDataOperationCoordinatorState.OPEN, gate.state)
                assertEquals(0, backupsClosed); assertEquals(0, migrationClosed)
                assertEquals(1, owner.state.value.pending)
                assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(resolution))
                assertContentEquals(byteArrayOf(3), Files.readAllBytes(home))
                Files.delete(home)
            }, observeWait = { AutoCloseable {} })
            assertEquals(1, retries); assertEquals(1, backupsClosed); assertEquals(1, migrationClosed)
            assertEquals(DesktopDataOperationCoordinatorState.CLOSED, gate.state)
            assertEquals(record, Json.decodeFromString(DesktopUsageRecord.serializer(),
                Files.readString(home.resolve("${record.id}.json"))))
            owner.closeAndDrain()
        } finally { gate.closeAndDrain(); ownership.close(); parent.toFile().deleteRecursively() }
    }

    @Test fun `committed restart drains local statistics without reopening normal data admission`() = runBlocking {
        val root = Files.createTempDirectory("usage-restart-tail-"); val gate = DesktopDataOperationCoordinator()
        val store = DesktopProviderUsageStore(root, gate); val owner = DesktopProviderUsageOwner(store)
        try {
            gate.withExclusiveMaintenance { requireRestart() }
            val record = DesktopUsageRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, 1,
                DesktopUsageSource.MOMENTS, modelId = "model", modelName = "fixture", startedAt = 1, updatedAt = 1,
                outcome = DesktopUsageOutcome.COMPLETED, counters = DesktopUsageCounters(prompt = 4))
            owner.submit(record)
            withTimeout(10_000) { owner.state.first { it.error != null } }
            owner.closeAndDrain()
            assertEquals(0, owner.state.value.pending)
            assertEquals(DesktopDataOperationCoordinatorState.RESTART_REQUIRED, gate.state)
            assertFailsWith<DesktopDataOperationRejectedException> { store.records() }
            assertFailsWith<DesktopDataOperationRejectedException> { gate.withNormalOperation { error("must remain closed") } }
            val json = Files.readString(root.resolve("diagnostics/provider-usage/${record.id}.json"))
            assertEquals(record, Json.decodeFromString(DesktopUsageRecord.serializer(), json))
        } finally { gate.closeAndDrain(); root.toFile().deleteRecursively() }
    }

    @Test fun `actual streaming retry and complete usage retain physical attempts and independent parent identity after log clear`() = runBlocking {
        MockWebServer().use { server ->
            val captured = Collections.synchronizedList(mutableListOf<DesktopUsageRecord>())
            val logs = DesktopTransportDiagnosticsOwner(usageSink = { captured.add(it) })
            val model = ModelConfig("model", "Fixture", server.url("/v1").toString(), "synthetic-private-key", "fixture-model",
                createdAt = 0, credentialAccount = ModelCredentialAccount("account", "Fixture account", "Fixture provider"))
            val context = DesktopUsageContext(DesktopUsageSource.CHAT, "actual-task", "actual-session", "actual-card")
            val log = logs.begin("diagnostic-request", "display-group", model, context)
            logs.clear("display-group")
            val transport = OpenAiStreamingTransport { true }
            try {
                server.enqueue(MockResponse().setResponseCode(400).setBody("{\"code\":20015}"))
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"private-fixture-reply\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3}}\n\n" +
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4}}\n\n" +
                        "data: [DONE]\n\n"))
                val messages = listOf(ChatApiMessage.text("user", "private-fixture-input"))
                val events = withTimeout(15_000) { transport.streamMainChat(messages, model, diagnostics = log).toList() }
                assertIs<ProviderStreamEvent.Completed>(events.last())
                log.finish(DesktopTaskStatus.COMPLETED, null, null)
                val records = captured.toList().groupBy { it.id }.values.map { it.maxBy { row -> row.revision } }
                assertEquals(2, records.size); assertEquals(setOf(1, 2), records.map { it.attempt }.toSet())
                assertEquals(1, records.map { it.requestId }.distinct().size)
                assertEquals(DesktopUsageOutcome.RETRIED, records.single { it.attempt == 1 }.outcome)
                assertNull(records.single { it.attempt == 1 }.counters.prompt)
                assertTrue(records.all { it.sessionId == "actual-session" && it.taskId == "actual-task" && it.cardId == "actual-card" && it.accountId == "account" })
                assertEquals(10L, summarizeDesktopUsage(captured.toList()).prompt.value)
                assertEquals(4L, summarizeDesktopUsage(captured.toList()).completion.value)
                assertTrue(logs.entries.value.isEmpty())
                val complete = logs.begin("complete-request", "moments:GENERATE", model,
                    DesktopUsageContext(DesktopUsageSource.MOMENTS, "moment-task", "real-session", "real-card"))
                server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"fixture"},"finish_reason":"stop"}],"usage":{"prompt_tokens":0,"completion_tokens":5}}"""))
                assertEquals("fixture", transport.completeMoment(messages, model, complete))
                complete.finish(DesktopTaskStatus.COMPLETED, null, null)
                assertEquals(3, server.requestCount)
                val totals = summarizeDesktopUsage(captured.toList())
                assertEquals(2, totals.requests); assertEquals(3, totals.attempts)
                assertEquals(10L, totals.prompt.value); assertEquals(9L, totals.completion.value)
                captured.forEach { record ->
                    val stored = Json.encodeToString(DesktopUsageRecord.serializer(), record)
                    assertFalse(stored.contains("synthetic-private-key"))
                    assertFalse(stored.contains("private-fixture-input"))
                    assertFalse(stored.contains("private-fixture-reply"))
                    assertFalse(stored.contains("moments:GENERATE"))
                }
            } finally { transport.closeAndDrain() }
        }
    }

    @Test fun `failed local persistence retains latest pending report and explicit retry drains to the same numeric record`() = runBlocking {
        val root = Files.createTempDirectory("usage-pending-"); val gate = DesktopDataOperationCoordinator()
        val home = root.resolve("diagnostics/provider-usage")
        Files.createDirectory(home.parent); Files.write(home, byteArrayOf(1))
        val store = DesktopProviderUsageStore(root, gate); val owner = DesktopProviderUsageOwner(store)
        try {
            val first = DesktopUsageRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, 1,
                DesktopUsageSource.CHAT, modelId = "model", modelName = "fixture", startedAt = 1, updatedAt = 1)
            owner.submit(first)
            withTimeout(10_000) { owner.state.first { it.error != null } }
            assertEquals(1, owner.state.value.pending); assertContentEquals(byteArrayOf(1), Files.readAllBytes(home))
            val latest = first.copy(revision = 2, outcome = DesktopUsageOutcome.CANCELLED, counters = DesktopUsageCounters(completion = 7))
            owner.submit(latest)
            withTimeout(10_000) { owner.state.first { it.error != null && it.pending == 1 } }
            Files.delete(home) // synthetic repair, never a production recovery action
            owner.retrySave()
            withTimeout(10_000) { owner.state.first { it.pending == 0 && it.error == null } }
            assertEquals(listOf(latest), store.records())
        } finally { owner.closeAndDrain(); gate.closeAndDrain(); root.toFile().deleteRecursively() }
    }
}
