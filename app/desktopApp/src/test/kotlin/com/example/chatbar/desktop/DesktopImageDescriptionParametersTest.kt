package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.prompt.AiTaskRefusalException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DesktopImageDescriptionParametersTest {
    private val encoded = "AAAA" // describe/withImage accept raw base64, not a complete data URI.
    private val controls = setOf("enable_thinking", "thinking_budget", "max_thinking_tokens", "reasoning_effort",
        "max_tokens", "max_completion_tokens")
    private fun model(server: MockWebServer) = ModelConfig("vision", "Synthetic vision", server.url("/v1").toString(),
        "synthetic-vision-key", "selected-vision", isMultimodal = true, createdAt = 1)
    private fun response(delta: String = "{\"content\":\"scene  description\"}", finish: String = "stop") =
        MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":$delta,\"finish_reason\":\"$finish\"}]}\n\ndata: [DONE]\n\n")

    private fun verify(change: (ModelConfig) -> ModelConfig, expected: JsonObject) = runBlocking {
        MockWebServer().use { server ->
            val transport = OpenAiStreamingTransport { true }
            try {
                server.enqueue(response())
                val selected = change(model(server))
                val emitted = StringBuilder()
                assertEquals("scene description", withTimeout(10_000) {
                    DesktopAuxiliaryImageUnderstanding(transport).describe(encoded, selected) { emitted.append(it) }
                })
                assertEquals("scene  description", emitted.toString())
                val request = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("/v1/chat/completions", request.path)
                assertEquals("Bearer synthetic-vision-key", request.getHeader("Authorization"))
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertEquals(expected, JsonObject(body.filterKeys { it in controls }))
                assertTrue(body.getValue("stream").jsonPrimitive.boolean)
                assertEquals("selected-vision", body.getValue("model").jsonPrimitive.content)
                assertFalse("response_format" in body)
                val messages = body.getValue("messages").jsonArray
                assertEquals(listOf("system", "assistant", "user", "assistant", "user", "assistant", "assistant", "user"),
                    messages.map { it.jsonObject.getValue("role").jsonPrimitive.content })
                val imageInput = messages[4].jsonObject.getValue("content").jsonArray
                    .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "image_url" }
                assertEquals(1, imageInput.size)
                assertEquals("data:image/jpeg;base64,$encoded", imageInput.single().jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
                assertEquals(1, server.requestCount)
            } finally { transport.closeAndDrain() }
        }
    }

    @Test fun `default description does not invent thinking or output controls`() = verify({ it }, JsonObject(emptyMap()))

    @Test fun `actual description inherits selected legacy thinking budgets and output cap`() = verify({ it.copy(
        enableThinking = true, supportsDisableThinking = true, maxOutputTokens = 317,
        customParams = mapOf("thinking_budget" to ParamValue.NumberValue(1536.0),
            "max_thinking_tokens" to ParamValue.NumberValue(1024.0))) }, buildJsonObject {
        put("enable_thinking", true); put("thinking_budget", 1536); put("max_thinking_tokens", 1024); put("max_tokens", 317)
    })

    @Test fun `actual description retains explicitly selected custom controls`() = verify({ it.copy(customParams = mapOf(
        "enable_thinking" to ParamValue.BooleanValue(true), "reasoning_effort" to ParamValue.StringValue("medium"),
        "thinking_budget" to ParamValue.NumberValue(2048.0), "max_completion_tokens" to ParamValue.NumberValue(422.0))) },
        buildJsonObject { put("enable_thinking", true); put("reasoning_effort", "medium"); put("thinking_budget", 2048); put("max_completion_tokens", 422) })

    @Test fun `actual description retains reasoning only model and configured output alias`() = verify({ it.copy(
        reasoningEffort = "high", maxOutputTokens = 433, outputTokenParameter = OutputTokenParameter.MAX_COMPLETION_TOKENS) },
        buildJsonObject { put("reasoning_effort", "high"); put("max_completion_tokens", 433) })

    @Test fun `description refusal truncation and terminal HTTP rejection never become successful descriptions`() = runBlocking {
        MockWebServer().use { server ->
            val transport = OpenAiStreamingTransport { true }
            val vision = DesktopAuxiliaryImageUnderstanding(transport)
            try {
                server.enqueue(response(finish = "length"))
                assertFailsWith<IllegalStateException> { withTimeout(10_000) { vision.describe(encoded, model(server)) {} } }
                server.enqueue(response(delta = "{\"refusal\":\"synthetic refusal\"}"))
                assertFailsWith<AiTaskRefusalException> { withTimeout(10_000) { vision.describe(encoded, model(server)) {} } }
                server.enqueue(MockResponse().setResponseCode(401).setBody("synthetic rejection"))
                assertFailsWith<IllegalStateException> { withTimeout(10_000) { vision.describe(encoded, model(server)) {} } }
                assertEquals(3, server.requestCount)
            } finally { transport.closeAndDrain() }
        }
    }

    @Test fun `description cancellation drains its actual request without retry or returned output`() = runBlocking {
        MockWebServer().use { server ->
            val transport = OpenAiStreamingTransport { true }
            try {
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val selected = model(server)
                val request = async { DesktopAuxiliaryImageUnderstanding(transport).describe(encoded, selected) {} }
                try {
                    withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
                } finally { request.cancelAndJoin() }
                assertTrue(request.isCancelled)
                assertEquals(1, server.requestCount)
            } finally { transport.closeAndDrain() }
        }
    }
}
