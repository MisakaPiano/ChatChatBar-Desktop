package com.example.chatbar.domain.image

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.prompt.NovelAiPromptAuthority as PromptTemplates
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamEvent
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class NovelAiActionOptionsProjectionTest {
    private val options = "————————\n【行动选项】\n1. 去图书馆\n2. 回旅店\n————————"
    private fun reply() = ChatMessage.create("synthetic", MessageRole.ASSISTANT,
        "站在广场。\n$options\n```text\n衣着：蓝外套\n```")
    @Test fun finalDesignMessagesKeepNarrationClothesAndManualHintButOmitActions() {
        val reply = reply()
        for (target in listOf(NovelAiImageModel.V4_5_FULL, NovelAiImageModel.V5_FULL)) {
            val messages = NovelAiPromptDesigner.conversationDesignMessages(listOf(reply), null,
                imageContentHint = "夕阳", finalPromptRequirement = "", characterImagePrompts = emptyList(),
                structured = false, targetImageModel = target, naturalLanguageMode = target == NovelAiImageModel.V5_FULL)
            val assistant = messages.first { it.role == "assistant" }.content.jsonPrimitive.content
            assertTrue(assistant.contains("站在广场")); assertTrue(assistant.contains("蓝外套"))
            assertFalse(assistant.contains("去图书馆")); assertFalse(assistant.contains("回旅店"))
            assertTrue(messages.any { it.content.toString().contains("夕阳") })
        }
        assertTrue(reply.content.contains("回旅店"))
    }
    @Test fun researchInputUsesSameReadOnlyProjection() {
        val prompt = PromptTemplates.novelAiImagePromptConversation(listOf(reply()))
        assertTrue(prompt.contains("站在广场")); assertTrue(prompt.contains("蓝外套"))
        assertFalse(prompt.contains("去图书馆")); assertFalse(prompt.contains("回旅店"))
    }
    @Test fun actualUserTextIsNeverFiltered() {
        val user = ChatMessage.create("synthetic", MessageRole.USER, options)
        assertTrue(PromptTemplates.novelAiImagePromptAssistantScene(user).contains("回旅店"))
        assertTrue(PromptTemplates.novelAiImagePromptConversation(listOf(user)).contains("回旅店"))
    }
    @Test fun actionOnlyReplyProjectsToEmptySceneWithoutMutatingSavedContent() {
        val message = ChatMessage.create("synthetic", MessageRole.ASSISTANT, options)
        assertTrue(PromptTemplates.novelAiImagePromptAssistantScene(message).isBlank())
        assertEquals(options, message.content)
        assertTrue(NovelAiPromptDesigner.contextForAnchor(listOf(message), message.id).isEmpty())
    }

    @Test fun `real designer planner and final transport both omit future actions and preserve status`() = runTest {
        for (target in listOf(NovelAiImageModel.V4_5_FULL, NovelAiImageModel.V5_FULL)) {
            val requests = mutableListOf<Pair<NovelAiTextStage, List<ChatApiMessage>>>()
            val answers = ArrayDeque(listOf(
                """{"sceneDescription":"正在广场穿着蓝外套。","queries":["synthetic"]}""",
                """{"baseCaption":"safe scene","characters":[]}"""))
            val transport = object : NovelAiTextTransport {
                override fun streamText(stage: NovelAiTextStage, messages: List<ChatApiMessage>, modelConfig: ModelConfig) = flow {
                    requests += stage to messages
                    emit(StreamEvent.Delta(answers.removeFirst())); emit(StreamEvent.Done)
                }
            }
            val search = object : NovelAiTagSearchClient {
                override suspend fun search(query: String) = NovelAiTagSearchOutcome(query, emptyList())
            }
            val designer = NovelAiPromptDesigner(transport, NovelAiTagResearchService(LlmNovelAiTagSearchPlanner(transport), search))
            val message = ChatMessage.create("synthetic", MessageRole.ASSISTANT,
                "SCENE_PRESENT_AT_SQUARE\n" + options.replace("去图书馆", "FUTURE_ONLY_LIBRARY").replace("回旅店", "FUTURE_ONLY_INN") +
                    "\n```text\n衣着：CLOTHING_PRESENT_BLUE_COAT\n```")
            val original = message.copy()
            val model = ModelConfig("fixture", "Fixture", "https://fixture.invalid", "fake", "fixture", createdAt = 1)
            designer.design(listOf(message), message.id, CharacterCard.create("Synthetic"), model,
                imageContentHint = "CURRENT_MANUAL_HINT", targetImageModel = target, naturalLanguageMode = target == NovelAiImageModel.V5_FULL)
            assertEquals(listOf(NovelAiTextStage.PLAN, NovelAiTextStage.GENERATE), requests.map { it.first })
            requests.forEach { (_, messages) ->
                val payload = messages.joinToString("\n") { it.content.jsonPrimitive.content }
                assertTrue(payload.contains("SCENE_PRESENT_AT_SQUARE"))
                assertTrue(payload.contains("CLOTHING_PRESENT_BLUE_COAT"))
                assertTrue(payload.contains("CURRENT_MANUAL_HINT"))
                assertFalse(payload.contains("FUTURE_ONLY_LIBRARY")); assertFalse(payload.contains("FUTURE_ONLY_INN"))
            }
            assertEquals(original, message)
            val count = requests.size
            val onlyOptions = message.copy(content = options)
            assertTrue(runCatching { designer.design(listOf(onlyOptions), onlyOptions.id,
                CharacterCard.create("Synthetic"), model) }.isFailure)
            assertEquals(count, requests.size, "Action-only source must stop before research or final request")
        }
    }
}
