package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.prompt.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MomentTextTransportTest {
    private fun model(server: MockWebServer) = ModelConfig("fixture", "Moment fixture", server.url("/v1").toString(),
        "synthetic-key", "fixture-model", enableThinking = true, maxOutputTokens = 973,
        customParams = mapOf("seed" to ParamValue.NumberValue(7.0)), createdAt = 0)
    private val messages = listOf(ChatApiMessage.text("system", "合成任务"), ChatApiMessage.text("user", "合成输入"))

    @Test fun `stream and complete use original task envelope and selected model parameters in actual requests`() = runBlocking {
        MockWebServer().use { server ->
            val transport = OpenAiStreamingTransport { true }
            try {
                val model = model(server)
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"fixture\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n"))
                assertIs<StreamEvent.Done>(withTimeout(10_000) { transport.streamMoment(messages, model).toList() }.last())
                val streaming = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"fixture"},"finish_reason":"stop"}]}"""))
                assertEquals("fixture", transport.completeMoment(messages, model))
                val complete = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                for ((request, stream) in listOf(streaming to true, complete to false)) {
                    val actual = Json.parseToJsonElement(request.body.readUtf8())
                    val expected = Json.parseToJsonElement(OpenAiChatRequestSerializer.serialize(
                        AuxiliaryMessageAssembler.assemble(messages, false), model, stream = stream,
                        allowCleartextHttp = true, isolatedTaskParameters = false, disableThinking = false,
                        includeStreamUsage = stream && model.supportsOpenAiPromptCacheInstrumentation()))
                    assertEquals(expected, actual)
                    assertEquals("Bearer synthetic-key", request.getHeader("Authorization"))
                }
                assertEquals(2, server.requestCount)
            } finally { transport.closeAndDrain() }
        }
    }

    @Test fun `complete truncation stays typed and 503 cannot trigger a second billed request`() = runBlocking {
        MockWebServer().use { server ->
            val transport = OpenAiStreamingTransport { true }
            try {
                server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"partial"},"finish_reason":"length"}]}"""))
                assertFailsWith<ModelResponseTruncatedException> { transport.completeMoment(messages, model(server)) }
                server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
                server.enqueue(MockResponse().setBody("must not be requested"))
                assertEquals(503, assertFailsWith<ModelRequestException> { transport.completeMoment(messages, model(server)) }.httpStatus)
                assertEquals(2, server.requestCount)
            } finally { transport.closeAndDrain() }
        }
    }
}
