package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.prompt.*
import com.example.chatbar.domain.worldbook.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class WorldBookAiRequestParityTest {
    @Test fun `six world book stages retain exact Android HTTP body auth and general envelope`() = runBlocking {
        MockWebServer().use { server ->
            val desktop = OpenAiStreamingTransport { true }
            try {
                val android = AndroidWorldBookAiGateway(StreamingChatService { true })
                val model = ModelConfig("model", "合成", server.url("/v1").toString(), "fixture-key", "synthetic",
                    enableThinking = true, maxOutputTokens = 739, reasoningEffort = "high", createdAt = 1,
                    customParams = mapOf("temperature" to ParamValue.NumberValue(0.7), "seed" to ParamValue.NumberValue(17.0)))
                val cases = listOf(WorldBookAiOperation.CREATE to WorldBookAiStage.GENERATE,
                    WorldBookAiOperation.CREATE to WorldBookAiStage.REPAIR, WorldBookAiOperation.FILL to WorldBookAiStage.GENERATE,
                    WorldBookAiOperation.FILL to WorldBookAiStage.REPAIR, WorldBookAiOperation.RESEARCH to WorldBookAiStage.PLAN,
                    WorldBookAiOperation.BRIEF to WorldBookAiStage.SUMMARIZE)
                for ((op, stage) in cases) {
                    val system = when (op) {
                        WorldBookAiOperation.CREATE -> if (stage == WorldBookAiStage.GENERATE)
                            PromptTemplates.WORLD_BOOK_CREATE_ENTRIES_SYSTEM_PROMPT else PromptTemplates.WORLD_BOOK_CREATE_ENTRIES_REPAIR_PROMPT
                        WorldBookAiOperation.FILL -> if (stage == WorldBookAiStage.GENERATE)
                            PromptTemplates.WORLD_BOOK_FILL_CONTENT_SYSTEM_PROMPT else PromptTemplates.WORLD_BOOK_FILL_CONTENT_REPAIR_PROMPT
                        WorldBookAiOperation.RESEARCH -> PromptTemplates.worldBookResearchPlannerSystemPrompt(10)
                        WorldBookAiOperation.BRIEF -> PromptTemplates.worldBookResearchBriefSystemPrompt()
                    }
                    val messages = listOf(ChatApiMessage.text("system", system.trimIndent()), ChatApiMessage.text("user", "合成输入"))
                    repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")) }
                    assertEquals("ok", withTimeout(10_000) { android.complete(op, stage, messages, model, {}, {}) })
                    val events = withTimeout(10_000) { desktop.streamWorldBookAi(op, messages, model).toList() }
                    assertTrue(events.last() is StreamEvent.Done)
                    val original = server.takeRequest(1, TimeUnit.SECONDS)!!; val port = server.takeRequest(1, TimeUnit.SECONDS)!!
                    assertEquals(original.path, port.path); assertEquals(original.getHeader("Authorization"), port.getHeader("Authorization"))
                    val expected = Json.parseToJsonElement(original.body.readUtf8())
                    val actual = Json.parseToJsonElement(port.body.readUtf8())
                    assertEquals(expected, actual)
                    assertEquals(8, actual.jsonObject.getValue("messages").jsonArray.size)
                    assertEquals(739, actual.jsonObject.getValue("max_tokens").jsonPrimitive.int)
                    assertEquals(17, actual.jsonObject.getValue("seed").jsonPrimitive.int)
                    assertEquals(0.7, actual.jsonObject.getValue("temperature").jsonPrimitive.double, 0.0)
                    assertTrue(actual.jsonObject.getValue("enable_thinking").jsonPrimitive.boolean)
                    assertEquals("high", actual.jsonObject.getValue("reasoning_effort").jsonPrimitive.content)
                }
                assertEquals(12, server.requestCount)
            } finally { desktop.closeAndDrain() }
        }
    }
}
