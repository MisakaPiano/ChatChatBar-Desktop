package com.example.chatbar.domain.image

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.SaveSlot
import com.example.chatbar.domain.chat.CleartextHttpChatTemplatePolicy
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAiChatNaturalLanguageModeTest {
    @Test
    fun `chat mode selects studio natural language system and preserves request roles`() {
        val request = messages(NovelAiImageModel.V5_FULL, true)
        assertEquals(
            PromptTemplates.novelAiImageNaturalLanguagePromptCoreSystem(botName = "林雾"),
            request[2].content.jsonPrimitive.content
        )
        assertEquals(
            listOf("assistant", "user", "system", "system", "system", "user"),
            request.map { it.role }
        )
        assertEquals("\$username站在窗边。", request.first().content.jsonPrimitive.content)
        assertTrue(request[4].content.jsonPrimitive.content.contains("silver hair"))
        assertEquals(
            "user",
            CleartextHttpChatTemplatePolicy.adaptMessages(
                request, allowCleartextHttp = true, baseUrl = "http://127.0.0.1:8080/v1"
            ).last().role
        )
    }

    @Test
    fun `disabled mode keeps model specific tag system`() {
        NovelAiImageModel.entries.forEach { model ->
            assertEquals(
                PromptTemplates.novelAiImagePromptCoreSystem(botName = "林雾", targetImageModel = model),
                messages(model, false)[2].content.jsonPrimitive.content
            )
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `natural language request rejects V4_5`() {
        messages(NovelAiImageModel.V4_5_FULL, true)
    }

    @Test
    fun `legacy session and save decode disabled and enabled preference round trips`() {
        val session = Json.decodeFromString<ChatSession>(
            """{"id":"session","characterCardId":"card","title":"chat","createdAt":1,"updatedAt":1}"""
        )
        val slot = Json.decodeFromString<SaveSlot>(
            """{"id":"slot","sessionId":"session","name":"save","createdAt":1}"""
        )
        assertFalse(session.novelAiNaturalLanguageMode)
        assertFalse(slot.novelAiNaturalLanguageMode)
        assertTrue(Json.decodeFromString<ChatSession>(
            Json.encodeToString(ChatSession.serializer(), session.copy(novelAiNaturalLanguageMode = true))
        ).novelAiNaturalLanguageMode)
        assertTrue(Json.decodeFromString<SaveSlot>(
            Json.encodeToString(SaveSlot.serializer(), slot.copy(novelAiNaturalLanguageMode = true))
        ).novelAiNaturalLanguageMode)
    }

    private fun messages(model: NovelAiImageModel, naturalLanguage: Boolean) =
        NovelAiPromptDesigner.conversationDesignMessages(
            messages = listOf(ChatMessage.create("session", MessageRole.ASSISTANT, "林远站在窗边。")),
            playerName = "林远",
            botName = "林雾",
            imageContentHint = "保留窗光",
            finalPromptRequirement = "侧面构图",
            characterImagePrompts = listOf("林雾" to "1girl, silver hair"),
            structured = true,
            targetImageModel = model,
            naturalLanguageMode = naturalLanguage
        )
}
