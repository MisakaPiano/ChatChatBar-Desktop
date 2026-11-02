package com.example.chatbar.domain.image

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NovelAiReverseSceneTest {
    private val model = ModelConfig("fixture", "Synthetic", "https://fixture.invalid", "synthetic", "synthetic", isMultimodal = true, createdAt = 1)
    private fun designer(requests: MutableList<Pair<NovelAiTextStage, List<ChatApiMessage>>>): NovelAiPromptDesigner {
        val transport = object : NovelAiTextTransport {
            override fun streamText(stage: NovelAiTextStage, messages: List<ChatApiMessage>, modelConfig: ModelConfig) = flow {
                requests += stage to messages
                val response = if (stage == NovelAiTextStage.PLAN)
                    """{"sceneDescription":"A synthetic scene","queries":[]}"""
                    else """{"baseCaption":"a room","characters":[]}"""
                emit(StreamEvent.Delta(response)); emit(StreamEvent.Done)
            }
        }
        return NovelAiPromptDesigner(transport, NovelAiTagResearchService(LlmNovelAiTagSearchPlanner(transport),
            object : NovelAiTagSearchClient { override suspend fun search(query: String) = NovelAiTagSearchOutcome(query, emptyList()) }))
    }

    @Test fun `description uses only scene planner and confirmed text bypasses vision and image attachments`() = runTest {
        val requests = mutableListOf<Pair<NovelAiTextStage, List<ChatApiMessage>>>()
        val service = designer(requests)
        assertEquals("A synthetic scene", service.describeReferenceImage("SYNTHETIC_IMAGE", model, NovelAiImageModel.V5_FULL))
        assertEquals(listOf(NovelAiTextStage.PLAN), requests.map { it.first })
        assertTrue(requests.single().second.toString().contains("SYNTHETIC_IMAGE"))
        requests.clear()
        val result = service.designForPromptToolDetailed("", "", imageBase64s = listOf("MUST_NOT_SEND"),
            referenceImageProvided = true, model = model.copy(isMultimodal = false), confirmedSceneDescription = "USER_EDITED_SCENE")
        assertEquals("a room", result.plan.baseCaption)
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { !it.second.toString().contains("MUST_NOT_SEND") })
        assertTrue(requests.last().second.toString().contains("USER_EDITED_SCENE"))
    }

    @Test fun `reverse target is optional for legacy callers and explicit targets stop following default`() {
        val draft = NovelAiStudioDraft(stylePrompt = "style", negativePrompt = "negative", followDefaultNovelAiImageModel = true)
        val plan = NovelAiPromptPlan(baseCaption = "scene", characterCaptions = emptyList())
        assertTrue(draft.applyReversePromptPlan(plan).followDefaultNovelAiImageModel)
        for (target in NovelAiImageModel.entries) {
            val applied = draft.applyReversePromptPlan(plan, target)
            assertEquals(target, applied.selectedModel); assertFalse(applied.followDefaultNovelAiImageModel)
            assertEquals(draft.stylePrompt, applied.stylePrompt); assertEquals(draft.negativePrompt, applied.negativePrompt)
            assertEquals(draft.v45Settings, applied.v45Settings); assertEquals(draft.v5Settings, applied.v5Settings)
        }
    }

    @Test fun `empty scene and empty source refuse before any request`() = runTest {
        val requests = mutableListOf<Pair<NovelAiTextStage, List<ChatApiMessage>>>()
        val service = designer(requests)
        assertFailsWith<IllegalArgumentException> { service.describeReferenceImage(" ", model, NovelAiImageModel.V5_FULL) }
        assertFailsWith<IllegalArgumentException> {
            service.designForPromptToolDetailed("", "", model = model, confirmedSceneDescription = " ")
        }
        assertTrue(requests.isEmpty())
    }
}
