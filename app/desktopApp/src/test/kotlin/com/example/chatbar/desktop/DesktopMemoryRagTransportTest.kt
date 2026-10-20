package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.prompt.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class DesktopMemoryRagTransportTest {
    private val task = MemoryRagTask(MemoryRagTaskKind.MEMORY_HEAD, MemoryRagTaskStage.SUMMARIZE)
    private fun model(server: MockWebServer) = ModelConfig.create("Synthetic", server.url("/v1").toString(), "synthetic", "test")
        .copy(supportsJsonMode = true, maxOutputTokens = 7, customParams = mapOf("max_tokens" to ParamValue.NumberValue(5.0)))

    @Test fun `oversized response is cancelled with a visible nonretryable memory budget failure`(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        val transport = OpenAiStreamingTransport { true }
        try {
            val delta = "x".repeat(1024 * 1024 + 1)
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"$delta\"}}]}\n\ndata: [DONE]\n\n"))
            val error = assertFailsWith<AiTaskResponseBudgetException> {
                DesktopMemoryRagTextClient(transport).completeText(task, listOf(ChatApiMessage.text("user", "synthetic")), model(server), true)
            }
            assertFalse(com.example.chatbar.domain.memory.memoryFailureInfo(MemoryFailureArea.ARCHIVE, error).automaticallyRetryable)
            assertEquals(1, server.requestCount)
        } finally { transport.closeAndDrain(); server.shutdown() }
    }

    @Test fun `memory wire uses isolated envelope JSON mode and no inherited output cap`() = runBlocking {
        val server = MockWebServer(); server.start()
        val transport = OpenAiStreamingTransport { true }
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"throughT\\\":1}\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            val client = DesktopMemoryRagTextClient(transport)
            val result = client.completeText(task, listOf(ChatApiMessage.text("user", "synthetic input")), model(server), false,
                responseFormatJson = true)
            assertEquals("{\"throughT\":1}", result)
            val request = server.takeRequest()
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("json_object", body.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
            assertFalse("max_tokens" in body); assertFalse("max_completion_tokens" in body)
            assertEquals(AuxiliaryMessageAssembler.assemble(listOf(ChatApiMessage.text("user", "synthetic input")), false).size,
                body.getValue("messages").jsonArray.size)
            assertEquals(1, server.requestCount)
        } finally { transport.closeAndDrain(); server.shutdown() }
    }
    @Test fun `transport does not hide HTTP retry or turn refusal into valid output`() = runBlocking {
        val server = MockWebServer(); server.start()
        val transport = OpenAiStreamingTransport { true }
        try {
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("synthetic unavailable"))
            val client = DesktopMemoryRagTextClient(transport)
            assertFailsWith<ModelRequestException> { client.completeText(task, listOf(ChatApiMessage.text("user", "synthetic")), model(server), true) }
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"refusal\":\"synthetic refusal\"},\"finish_reason\":\"content_filter\"}]}\n\ndata: [DONE]\n\n"))
            assertFailsWith<AiTaskRefusalException> { client.completeText(task, listOf(ChatApiMessage.text("user", "synthetic")), model(server), true) }
            assertEquals(2, server.requestCount)
        } finally { transport.closeAndDrain(); server.shutdown() }
    }
}
