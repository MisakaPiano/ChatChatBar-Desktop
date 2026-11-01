package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.card.*
import com.example.chatbar.domain.prompt.*
import com.example.chatbar.domain.search.ResearchBrief
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CharacterCardAiRequestParityTest {
    @Test fun desktopAndAndroidSendSameFillRewriteGenerateRepairRequests() = runBlocking {
        MockWebServer().use { server ->
            val desktop = OpenAiStreamingTransport { true }
            val android = StreamingChatService { true }
            try {
                val card = CharacterCard("synthetic-card", "合成卡", basicSetting = "合成设定",
                    characters = listOf(CharacterInfo("person", "合成人物", profile = "既有简介")), createdAt = 1, updatedAt = 1)
                val model = ModelConfig("synthetic-model", "合成模型", server.url("/v1").toString(), "fixture-key", "original-model",
                    maxOutputTokens = 717, enableThinking = true, createdAt = 1,
                    customParams = mapOf("temperature" to ParamValue.NumberValue(0.7), "seed" to ParamValue.NumberValue(17.0)))
                val brief = ResearchBrief(facts = listOf("合成外部事实"))
                val image = CharacterAutoFillImageContext(true, listOf("合成图片一", "合成图片二"))
                assertEquals(Json.parseToJsonElement(CharacterAutoFillService.buildPromptPayload("要求", card, brief, image)),
                    Json.parseToJsonElement(CharacterCardAiPayload.fill("要求", card,
                        Json { encodeDefaults = false }.parseToJsonElement(Json { encodeDefaults = false }.encodeToString(ResearchBrief.serializer(), brief)), image)))
                assertEquals(Json.parseToJsonElement(CharacterRewriteService.buildPromptPayload("要求", card, brief)),
                    Json.parseToJsonElement(CharacterCardAiPayload.rewrite("要求", card,
                        Json { encodeDefaults = false }.parseToJsonElement(Json { encodeDefaults = false }.encodeToString(ResearchBrief.serializer(), brief)))))
                for (operation in CharacterCardAiOperation.entries) for (stage in CharacterCardAiStage.entries) {
                    val generation = stage == CharacterCardAiStage.GENERATE
                    val kind = if (operation == CharacterCardAiOperation.FILL) AiTaskKind.CHARACTER_FILL else AiTaskKind.CHARACTER_REWRITE
                    val system = when (operation) {
                        CharacterCardAiOperation.FILL -> if (generation) PromptTemplates.CHARACTER_AUTO_FILL_SYSTEM_PROMPT else PromptTemplates.CHARACTER_AUTO_FILL_REPAIR_PROMPT
                        CharacterCardAiOperation.REWRITE -> if (generation) PromptTemplates.CHARACTER_REWRITE_SYSTEM_PROMPT else PromptTemplates.CHARACTER_REWRITE_REPAIR_PROMPT
                    }
                    val payload = if (generation) {
                        if (operation == CharacterCardAiOperation.FILL) CharacterAutoFillService.buildPromptPayload("要求", card, brief, image)
                        else CharacterRewriteService.buildPromptPayload("要求", card, brief)
                    } else if (operation == CharacterCardAiOperation.FILL) "合成不完整JSON"
                    else buildJsonObject { put("outputSchema", CharacterCardAiPayload.rewriteSchema(card)); put("text", "合成不完整JSON") }.toString()
                    val messages = listOf(ChatApiMessage.text("system", system), ChatApiMessage.text("user", payload))
                    repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")) }
                    val official = withTimeout(10_000) { android.streamText(messages, model, thinkingBudget = if (generation) 512 else 256,
                        readTimeoutSeconds = 600, taskContext = AiTaskContext(kind, if (generation) AiTaskStage.GENERATE else AiTaskStage.REPAIR)).toList() }
                    val port = withTimeout(10_000) { desktop.streamCharacterCardCandidate(stage, messages, model).toList() }
                    assertTrue(official.last() is StreamEvent.Done); assertTrue(port.last() is StreamEvent.Done)
                    val a = server.takeRequest(1, TimeUnit.SECONDS)!!; val b = server.takeRequest(1, TimeUnit.SECONDS)!!
                    assertEquals(a.path, b.path); assertEquals(a.getHeader("Authorization"), b.getHeader("Authorization"))
                    assertEquals(Json.parseToJsonElement(a.body.readUtf8()), Json.parseToJsonElement(b.body.readUtf8()))
                }
                assertEquals(8, server.requestCount)
            } finally { desktop.closeAndDrain() }
        }
    }
}
