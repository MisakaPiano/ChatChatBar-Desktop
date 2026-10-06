package com.example.chatbar.domain.image

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.prompt.NovelAiPromptAuthority
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class NovelAiDesignerTransportTest {
    @Test fun `first design preserves planner scene and V5 natural prose through repair`() = runTest {
        val requests = mutableListOf<Pair<NovelAiTextStage, List<ChatApiMessage>>>()
        val responses = ArrayDeque(listOf(
            """{"sceneDescription":"窗边有一只白色杯子，阳光从左侧照入，桌面干净，镜头平视杯子。","queries":["杯子"]}""",
            "invalid first response",
            """{"baseCaption":"白色杯子放在窗边，柔和阳光。","characters":[]}""",
        ))
        val transport = object : NovelAiTextTransport {
            override fun streamText(stage: NovelAiTextStage, messages: List<ChatApiMessage>, modelConfig: ModelConfig) = flow {
                requests += stage to messages
                assertEquals(true, modelConfig.enableThinking)
                val answer = responses.removeFirst()
                answer.chunked(9).forEach { emit(StreamEvent.Delta(it)) }
                emit(StreamEvent.Done)
            }
        }
        val lookup = object : NovelAiTagSearchClient {
            override suspend fun search(query: String) = NovelAiTagSearchOutcome(query,
                listOf(NovelAiTagCandidate("cup", "杯子", 20, NovelAiTagCategory.GENERAL)))
        }
        val designer = NovelAiPromptDesigner(transport,
            NovelAiTagResearchService(LlmNovelAiTagSearchPlanner(transport), lookup),
            NovelAiPromptPostProcessor(listOf(NovelAiTagRewriteRule(listOf("白色杯子放在窗边，柔和阳光。"), listOf("must_not_replace")))))
        val result = designer.designForPromptToolDetailed("画一只杯子", "", model = ModelConfig("fixture", "Fixture", "https://fixture.invalid", "fake", "fixture", enableThinking = true, createdAt = 1),
            targetImageModel = NovelAiImageModel.V5_FULL, naturalLanguageMode = true)
        assertEquals(listOf(NovelAiTextStage.PLAN, NovelAiTextStage.GENERATE, NovelAiTextStage.REPAIR), requests.map { it.first })
        assertEquals("白色杯子放在窗边，柔和阳光。", result.plan.baseCaption)
        val generated = requests[1].second
        assertTrue(generated.any { it.role == "assistant" && it.content.jsonPrimitive.content.startsWith("窗边有一只") })
        assertTrue(generated.any { it.role == "system" && it.content.jsonPrimitive.content == NovelAiPromptAuthority.novelAiImageNaturalLanguagePromptCoreSystem() })
        assertEquals(NovelAiPromptAuthority.NOVELAI_IMAGE_NATURAL_LANGUAGE_PROMPT_REPAIR_SYSTEM_V5,
            requests.last().second.first { it.role == "system" }.content.jsonPrimitive.content)
        assertTrue(result.research.tagEvidence.isNotEmpty())
    }
}
