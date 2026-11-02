package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.card.CharacterCardAiStage
import com.example.chatbar.domain.search.CharacterResearchAiStage
import com.example.chatbar.domain.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SelectedAuxiliaryModelParametersTransportTest {
    private val messages = listOf(ChatApiMessage.text("system", "synthetic rules"), ChatApiMessage.text("user", "synthetic input"))

    private fun models(server: MockWebServer): List<ModelConfig> {
        val plain = ModelConfig("fixture", "Synthetic", server.url("/v1").toString(), "fixture-key", "selected-model", createdAt = 1)
        val common = mapOf("temperature" to ParamValue.NumberValue(0.37), "top_p" to ParamValue.NumberValue(0.81),
            "seed" to ParamValue.NumberValue(271.0), "model" to ParamValue.StringValue("custom-selected-model"))
        val legacy = plain.copy(maxOutputTokens = 317, enableThinking = true, reasoningEffort = "high", supportsDisableThinking = true,
            customParams = common + mapOf("thinking_budget" to ParamValue.NumberValue(1536.0), "max_thinking_tokens" to ParamValue.NumberValue(1024.0)))
        return listOf(plain, legacy, legacy.copy(enableThinking = null, supportsDisableThinking = false,
            outputTokenParameter = OutputTokenParameter.MAX_COMPLETION_TOKENS, customParams = common))
    }

    private fun response(text: String = "ok") = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        "data: " + buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
            put("delta", buildJsonObject { put("content", text) }); put("finish_reason", "stop")
        }) }) } + "\n\ndata: [DONE]\n\n")

    private fun assertWire(server: MockWebServer, model: ModelConfig, jsonOutput: Boolean) {
        val request = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("/v1/chat/completions", request.path); assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        if (model.maxOutputTokens == null) {
            for (key in listOf("max_tokens", "max_completion_tokens", "enable_thinking", "reasoning_effort", "thinking_budget", "max_thinking_tokens"))
                assertFalse(key in body, key)
            assertEquals("selected-model", body.getValue("model").jsonPrimitive.content)
        } else {
            val cap = if (model.outputTokenParameter == OutputTokenParameter.MAX_TOKENS) "max_tokens" else "max_completion_tokens"
            assertEquals(317, body.getValue(cap).jsonPrimitive.int)
            assertEquals("high", body.getValue("reasoning_effort").jsonPrimitive.content)
            assertEquals("custom-selected-model", body.getValue("model").jsonPrimitive.content)
            assertEquals(0.37, body.getValue("temperature").jsonPrimitive.double)
            assertEquals(0.81, body.getValue("top_p").jsonPrimitive.double); assertEquals(271, body.getValue("seed").jsonPrimitive.int)
            if (model.enableThinking == true) {
                assertTrue(body.getValue("enable_thinking").jsonPrimitive.boolean)
                assertEquals(1536, body.getValue("thinking_budget").jsonPrimitive.int)
                assertEquals(1024, body.getValue("max_thinking_tokens").jsonPrimitive.int)
            } else assertFalse("enable_thinking" in body)
        }
        if (jsonOutput) assertEquals("json_object", body.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue(body.getValue("stream").jsonPrimitive.boolean)
    }

    private fun verify(stages: Int, jsonOutput: Boolean = false,
        send: suspend (OpenAiStreamingTransport, ModelConfig, Int) -> Unit) = runBlocking {
        MockWebServer().use { server ->
            val transport = OpenAiStreamingTransport { true }
            try {
                for (model in models(server)) repeat(stages) { stage ->
                    server.enqueue(response()); withTimeout(10_000) { send(transport, model, stage) }; assertWire(server, model, jsonOutput)
                }
                assertEquals(3 * stages, server.requestCount)
            } finally { transport.closeAndDrain() }
        }
    }

    @Test fun `research PLAN and SUMMARIZE inherit default legacy and reasoning only model fields`() = verify(2) { transport, model, stage ->
        assertEquals(StreamEvent.Done, transport.streamCharacterResearch(CharacterResearchAiStage.entries[stage], messages, model).toList().last())
    }

    @Test fun `character generation and repair do not replace configured thinking with fixed stage budgets`() = verify(2) { transport, model, stage ->
        assertEquals(StreamEvent.Done, transport.streamCharacterCardCandidate(CharacterCardAiStage.entries[stage], messages, model).toList().last())
    }

    @Test fun `character image understanding retains JSON with selected model controls`() = verify(1, jsonOutput = true) { transport, model, _ ->
        assertEquals(StreamEvent.Done, transport.streamCharacterAppearance(messages, model).toList().last())
    }

    @Test fun `actual Fish translation and tags retain selected model through shared service and Desktop transport`() = runBlocking {
        MockWebServer().use { server ->
            val transport = OpenAiStreamingTransport { true }
            try {
                val service = FishAudioTagService(FishVoiceTextGateway { _, input, model, disabled ->
                    assertFalse(disabled); transport.streamFishVoice(input, model)
                })
                val inputs = listOf(VoiceTagInput("one", "hello", "Alice", "calm"))
                for (model in models(server)) {
                    server.enqueue(response("""{"segments":[{"id":"one","translatedText":"hello"}]}"""))
                    withTimeout(10_000) { service.translate(model, "English", "", "", inputs) }
                    assertWire(server, model, false)
                    server.enqueue(response("""{"segments":[{"id":"one","ttsText":"hello"}]}"""))
                    withTimeout(10_000) { service.generate(model, "s2.1-pro-free", "", "", inputs) }
                    assertWire(server, model, false)
                }
                assertEquals(6, server.requestCount)
            } finally { transport.closeAndDrain() }
        }
    }
}
