package com.example.chatbar.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import com.example.chatbar.data.local.entity.ModelConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit

class MessageFormatRepairServiceTest {
    @Test
    fun `repair stream allows long silent model responses`() {
        assertEquals(600L, MessageFormatRepairService.FORMAT_REPAIR_READ_TIMEOUT_SECONDS)
    }

    private fun verifyThinking(legacy: Boolean) = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"fixed\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            val model = ModelConfig("fixture", "Synthetic", server.url("/v1").toString(), "fixture-key", "fixture-model",
                enableThinking = if (legacy) true else null, reasoningEffort = "high", createdAt = 0)
            val service = MessageFormatRepairService(StreamingChatService { true })
            val events = withTimeout(10_000) { service.streamRepair("Synthetic source", "Synthetic rule", null, model).toList() }
            assertTrue(events.last() is StreamEvent.Done)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("high", body.getValue("reasoning_effort").jsonPrimitive.content)
            if (legacy) assertTrue(body.getValue("enable_thinking").jsonPrimitive.boolean)
            else assertFalse("enable_thinking" in body)
            assertEquals(listOf("system", "assistant", "user", "assistant", "user", "assistant", "assistant", "user"),
                body.getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content })
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `repair actual Android request keeps selected legacy thinking enabled`() = verifyThinking(true)
    @Test fun `repair actual Android request keeps selected reasoning effort`() = verifyThinking(false)
}
