package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.memory.*
import com.example.chatbar.domain.prompt.*
import com.example.chatbar.domain.rag.RetrievalPlanner
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.*

class DesktopMemoryRagUsageTest {
    private fun response(text: String, prompt: Int, completion: Int) = MockResponse()
        .setHeader("Content-Type", "text/event-stream").setBody("data: " + buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject {
                put("delta", buildJsonObject { put("content", text) }); put("finish_reason", "stop")
            }) })
            put("usage", buildJsonObject { put("prompt_tokens", prompt); put("completion_tokens", completion) })
        } + "\n\ndata: [DONE]\n\n")
    private fun model(server: MockWebServer) = ModelConfig.create("Synthetic", server.url("/v1").toString(),
        "FAKE_MEMORY_KEY", "fixture-memory").copy(supportsJsonMode = true)
    private fun latest(rows: List<DesktopUsageRecord>) = rows.toList().groupBy { it.id }.values.map { group -> group.maxBy { it.revision } }

    @Test fun `actual shared head JSON repair retains one logical request and reported usage for both physical attempts`() = runBlocking {
        MockWebServer().use { server ->
            val rows = Collections.synchronizedList(mutableListOf<DesktopUsageRecord>())
            val logs = DesktopTransportDiagnosticsOwner(usageSink = { rows.add(it) })
            val transport = OpenAiStreamingTransport { true }
            try {
                server.enqueue(response("not-json", 2, 1))
                server.enqueue(response("""{"throughT":1,"location":"synthetic"}""", 3, 0))
                val parent = DesktopUsageContext(DesktopUsageSource.CHAT, "actual-memory-job", "actual-session", "actual-card")
                val client = DesktopMemoryRagTextClient(transport, diagnostics = { logs })
                val selected = model(server).copy(maxOutputTokens = 811, enableThinking = true,
                    customParams = mapOf("thinking_budget" to com.example.chatbar.data.local.entity.ParamValue.NumberValue(2048.0)))
                val result = withContext(DesktopUsageParent(parent)) {
                    MemoryAiGateway(client).head(selected, MemoryHeadUpdateMode.INITIALIZE, 1,
                        "", "", "PRIVATE_MEMORY_SOURCE") { require(it.throughT == 1L) }
                }
                assertEquals(1L, result.throughT)
                repeat(2) {
                    val body = Json.parseToJsonElement(assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)).body.readUtf8()).jsonObject
                    assertEquals(811, body.getValue("max_tokens").jsonPrimitive.int)
                    assertTrue(body.getValue("enable_thinking").jsonPrimitive.boolean)
                    assertEquals(2048, body.getValue("thinking_budget").jsonPrimitive.int)
                }
                val records = latest(rows); assertEquals(2, records.size)
                assertEquals(1, records.map { it.requestId }.distinct().size)
                assertEquals(setOf(1, 2), records.map { it.attempt }.toSet())
                assertEquals(DesktopUsageOutcome.RETRIED, records.single { it.attempt == 1 }.outcome)
                assertEquals(DesktopUsageOutcome.COMPLETED, records.single { it.attempt == 2 }.outcome)
                records.forEach {
                    assertEquals(parent.sessionId, it.sessionId); assertEquals(parent.taskId, it.taskId)
                    assertEquals(parent.cardId, it.cardId); assertEquals(DesktopUsageSource.CHAT, it.source)
                    val raw = Json.encodeToString(DesktopUsageRecord.serializer(), it)
                    assertFalse(raw.contains("PRIVATE_MEMORY_SOURCE")); assertFalse(raw.contains("FAKE_MEMORY_KEY"))
                    assertFalse(raw.contains("throughT"))
                }
                assertEquals(5L, summarizeDesktopUsage(records).prompt.value)
                assertEquals(1L, summarizeDesktopUsage(records).completion.value)
                assertEquals(1, logs.entries.value.size)
                assertEquals(2, server.requestCount)
            } finally { transport.closeAndDrain() }
        }
    }

    @Test fun `nested summary failure does not relabel the completed planning request and does not retry transport`() = runBlocking {
        MockWebServer().use { server ->
            val rows = Collections.synchronizedList(mutableListOf<DesktopUsageRecord>())
            val logs = DesktopTransportDiagnosticsOwner(usageSink = { rows.add(it) })
            val transport = OpenAiStreamingTransport { true }
            try {
                server.enqueue(response("synthetic plan", 0, 0))
                server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
                val configured = model(server)
                val client = DesktopMemoryRagTextClient(transport, diagnostics = { logs })
                assertFailsWith<ModelRequestException> {
                    client.withTaskRun {
                        client.completeText(MemoryRagTask(MemoryRagTaskKind.MEMORY_COMPRESSION_PLAN, MemoryRagTaskStage.PLAN),
                            listOf(ChatApiMessage.text("user", "synthetic")), configured, true)
                        client.withTaskRun {
                            client.completeText(MemoryRagTask(MemoryRagTaskKind.MEMORY_COMPRESSION, MemoryRagTaskStage.SUMMARIZE),
                                listOf(ChatApiMessage.text("user", "synthetic")), configured, true)
                        }
                    }
                }
                val records = latest(rows); assertEquals(2, records.size)
                assertEquals(2, records.map { it.requestId }.distinct().size)
                assertTrue(records.all { it.attempt == 1 && it.source == DesktopUsageSource.OTHER && it.sessionId == null })
                assertEquals(0L, records.single { it.outcome == DesktopUsageOutcome.COMPLETED }.counters.prompt)
                assertNull(records.single { it.outcome == DesktopUsageOutcome.FAILED }.counters.prompt)
                assertEquals(2, server.requestCount)
            } finally { transport.closeAndDrain() }
        }
    }

    @Test fun `actual retrieval fallback retains failed HTTP outcome despite normal planner return`() = runBlocking {
        MockWebServer().use { server ->
            val rows = Collections.synchronizedList(mutableListOf<DesktopUsageRecord>())
            val logs = DesktopTransportDiagnosticsOwner(usageSink = { rows.add(it) })
            val transport = OpenAiStreamingTransport { true }
            try {
                server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
                val client = DesktopMemoryRagTextClient(transport, diagnostics = { logs })
                val parent = DesktopUsageContext(DesktopUsageSource.CHAT, sessionId = "actual-session", cardId = "actual-card",
                    subjectId = "actual-user")
                val result = withContext(DesktopUsageParent(parent)) {
                    RetrievalPlanner(client).plan("PRIVATE_QUERY", emptyList(), "Synthetic", model(server))
                }
                assertNull(result.plan)
                val row = latest(rows).single()
                assertEquals(DesktopUsageOutcome.FAILED, row.outcome); assertNull(row.counters.prompt)
                assertEquals(parent.sessionId, row.sessionId); assertEquals(parent.subjectId, row.subjectId)
                assertEquals(1, row.attempt); assertEquals(1, server.requestCount)
            } finally { transport.closeAndDrain() }
        }
    }

    @Test fun `cancelled child stream stays cancelled when its logical caller returns normally`() = runBlocking {
        MockWebServer().use { server ->
            val rows = Collections.synchronizedList(mutableListOf<DesktopUsageRecord>())
            val logs = DesktopTransportDiagnosticsOwner(usageSink = { rows.add(it) })
            val transport = OpenAiStreamingTransport { true }
            try {
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val client = DesktopMemoryRagTextClient(transport, diagnostics = { logs })
                val configured = model(server)
                client.withTaskRun { coroutineScope {
                    val child = async {
                        client.completeText(MemoryRagTask(MemoryRagTaskKind.RETRIEVAL_PLAN, MemoryRagTaskStage.PLAN),
                            listOf(ChatApiMessage.text("user", "synthetic")), configured, true)
                    }
                    assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
                    child.cancelAndJoin()
                } }
                val row = latest(rows).single()
                assertEquals(DesktopUsageOutcome.CANCELLED, row.outcome)
                assertEquals(DesktopUsageCounters(), row.counters); assertEquals(1, server.requestCount)
            } finally { transport.closeAndDrain() }
        }
    }
}
