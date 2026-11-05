package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.prompt.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class FormatCardAutoFillRequestParityTest {
    @Test fun desktopAndAndroidSendSameFinalFormatCardGenerateAndRepairBodies() = runBlocking {
        MockWebServer().use { server ->
            val desktop = OpenAiStreamingTransport { true }
            val android = StreamingChatService { true }
            try {
                val card = CharacterCard("synthetic-parity", "合成角色卡", basicSetting = "完整合成设定\n".repeat(10_000), createdAt = 0, updatedAt = 0)
                val model = ModelConfig("synthetic-model", "合成模型", server.url("/v1").toString(), "fixture-key", "original-model",
                    maxOutputTokens = 617, enableThinking = true, createdAt = 0,
                    customParams = mapOf("temperature" to ParamValue.NumberValue(0.7), "model" to ParamValue.StringValue("custom-model"),
                        "seed" to ParamValue.NumberValue(31.0)))
                for (stage in listOf(AiTaskStage.GENERATE, AiTaskStage.REPAIR)) {
                    val messages = if (stage == AiTaskStage.GENERATE) listOf(
                        ChatApiMessage.text("system", PromptTemplates.formatCardAutoFillSystemPrompt()),
                        ChatApiMessage.text("user", PromptTemplates.formatCardAutoFillUserPrompt(card, "合成要求", "手填名称")))
                    else listOf(ChatApiMessage.text("system", PromptTemplates.FORMAT_CARD_AUTO_FILL_REPAIR_PROMPT), ChatApiMessage.text("user", "合成未完成JSON\n".repeat(8_000)))
                    repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")) }
                    val official = withTimeout(10_000) { android.streamText(messages, model, readTimeoutSeconds = 600,
                        taskContext = AiTaskContext(AiTaskKind.FORMAT_CARD, stage)).toList() }
                    val transplanted = withTimeout(10_000) { desktop.streamFormatCardAutoFill(messages, model).toList() }
                    assertTrue(official.last() is StreamEvent.Done); assertTrue(transplanted.last() is StreamEvent.Done)
                    val androidRequest = server.takeRequest(1, TimeUnit.SECONDS)!!
                    val desktopRequest = server.takeRequest(1, TimeUnit.SECONDS)!!
                    assertEquals(androidRequest.path, desktopRequest.path)
                    assertEquals(androidRequest.getHeader("Authorization"), desktopRequest.getHeader("Authorization"))
                    assertEquals(Json.parseToJsonElement(androidRequest.body.readUtf8()), Json.parseToJsonElement(desktopRequest.body.readUtf8()))
                }
                assertEquals(4, server.requestCount)
            } finally { desktop.closeAndDrain() }
        }
    }
}
