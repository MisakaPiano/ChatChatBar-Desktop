package com.example.chatbar.ui.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.ContextWindowManager
import com.example.chatbar.domain.chat.MainChatRequestAssembler
import com.example.chatbar.domain.chat.MainChatRequestAssemblyInput
import com.example.chatbar.domain.chat.PromptCachePromptLayers
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentTurnMessageOrderTest {
    private val assembler = MainChatRequestAssembler()

    @Test
    fun blankContinueReusesLatestUserExactlyOnceInSerializedRequest() {
        val resumedUser = ChatMessage(
            id = "latest-user-id",
            sessionId = "session",
            role = MessageRole.USER,
            content = "fixture-resumed-user",
            images = listOf("fixture-image-base64"),
            createdAt = 3L,
            updatedAt = 3L,
            sourceTurnId = "source-turn-id",
            sourceTurnOrder = 2L,
        )
        val contextMessages = listOf(
            ChatMessage("previous-user", "session", MessageRole.USER, "previous-user", createdAt = 1L, updatedAt = 1L),
            ChatMessage("previous-assistant", "session", MessageRole.ASSISTANT, "previous-assistant", createdAt = 2L, updatedAt = 2L),
            resumedUser,
        )
        val groups = ContextWindowManager().getPromptMessageGroups(
            contextMessages = contextMessages,
            latestMessageId = resumedUser.id,
        )
        assertFalse((groups.historyMessages + groups.previousTurnMessages).any { it.id == resumedUser.id })

        val messages = assemble(
            earlier = groups.historyMessages.map { ChatApiMessage.text(it.role.name.lowercase(), it.displayContent) },
            previous = groups.previousTurnMessages.map { ChatApiMessage.text(it.role.name.lowercase(), it.displayContent) },
            current = ChatApiMessage.withImage("user", resumedUser.displayContent, resumedUser.images.single()),
        )
        for (baseUrl in listOf("https://example.test/v1", "http://127.0.0.1:1234/v1")) {
            val body = StreamingChatService(allowCleartextHttp = { true }).buildRequestBody(
                messages = messages,
                modelConfig = model(baseUrl),
                stream = true,
            )
            assertEquals(1, body.messageTextOccurrenceCount(resumedUser.displayContent))
            assertEquals(1, body.occurrenceCount(resumedUser.images.single()))
            assertFalse(body.contains(PromptTemplates.continueGenerationUserPrompt()))
        }
        assertEquals("latest-user-id", resumedUser.id)
        assertEquals("source-turn-id", resumedUser.sourceTurnId)
        assertEquals(2L, resumedUser.sourceTurnOrder)
    }

    @Test
    fun blankContinueAfterAssistantUsesRequestOnlyContinuationPrompt() {
        val contextMessages = listOf(
            ChatMessage("user", "session", MessageRole.USER, "persisted-user", createdAt = 1L, updatedAt = 1L),
            ChatMessage("assistant", "session", MessageRole.ASSISTANT, "persisted-assistant", createdAt = 2L, updatedAt = 2L),
        )
        val groups = ContextWindowManager().getPromptMessageGroups(contextMessages, latestMessageId = null)
        val continuationPrompt = PromptTemplates.continueGenerationUserPrompt()
        val messages = assemble(
            earlier = groups.historyMessages.map { ChatApiMessage.text(it.role.name.lowercase(), it.displayContent) },
            previous = groups.previousTurnMessages.map { ChatApiMessage.text(it.role.name.lowercase(), it.displayContent) },
            current = ChatApiMessage.text("user", continuationPrompt),
        )
        val body = StreamingChatService().buildRequestBody(messages, model("https://example.test/v1"), stream = true)

        assertEquals(1, body.messageTextOccurrenceCount(continuationPrompt))
        assertEquals(1, contextMessages.count { it.role == MessageRole.USER })
    }

    @Test
    fun serializedRequestsPreserveStartEndAndBothOrderForHttpsAndLocalHttp() {
        val service = StreamingChatService(allowCleartextHttp = { true })
        for (position in FormatPromptPosition.entries) {
            val messages = assembler.assemble(
                MainChatRequestAssemblyInput(
                    promptLayers = layers(stableContext = "fixture-character", tail = "fixture-post-history"),
                    positionedRequirementsSystemPrompt = "fixture-requirements",
                    formatPromptPosition = position,
                    previousTurnMessages = listOf(ChatApiMessage.text("assistant", "fixture-history")),
                    currentUserMessage = ChatApiMessage.text("user", "fixture-current-user"),
                    strongPromptSystemSuffix = "fixture-strong",
                    botName = "fixture-bot",
                ),
            ).messages
            for (baseUrl in listOf("https://example.test/v1", "http://127.0.0.1:1234/v1")) {
                val body = service.buildRequestBody(messages, model(baseUrl), stream = true)
                val serialized = Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray
                val contents = serialized.map { it.jsonObject.getValue("content").jsonPrimitive.content }
                val roles = serialized.map { it.jsonObject.getValue("role").jsonPrimitive.content }
                val characterIndex = contents.indexOf("fixture-character")
                val userIndex = contents.indexOf("fixture-current-user")
                val requirementIndices = contents.indices.filter { "fixture-requirements" in contents[it] }
                val expectedCount = (if (position.includesStart) 1 else 0) + (if (position.includesEnd) 1 else 0)
                assertEquals(expectedCount, requirementIndices.size)
                if (position.includesStart) assertTrue(requirementIndices.first() < characterIndex)
                if (position.includesEnd) {
                    val end = requirementIndices.last()
                    assertTrue(end > userIndex)
                    assertTrue(contents[end].indexOf("fixture-post-history") < contents[end].indexOf("fixture-requirements"))
                    assertTrue(end < contents.indexOf("fixture-strong"))
                }
                assertEquals(messages.map { it.content.jsonText() }, contents)
                assertEquals("user", roles.last())
                assertEquals("user", roles[userIndex])
                assertEquals(if (baseUrl.startsWith("http:")) "assistant" else "system", roles[characterIndex])
            }
        }
    }

    private fun assemble(
        earlier: List<ChatApiMessage>,
        previous: List<ChatApiMessage>,
        current: ChatApiMessage,
    ): List<ChatApiMessage> = assembler.assemble(
        MainChatRequestAssemblyInput(
            promptLayers = layers(),
            positionedRequirementsSystemPrompt = "",
            formatPromptPosition = FormatPromptPosition.END,
            earlierHistoryMessages = earlier,
            previousTurnMessages = previous,
            currentUserMessage = current,
            botName = "fixture-bot",
        ),
    ).messages

    private fun layers(
        stableContext: String = "fixture-character",
        tail: String = "fixture-tail",
    ) = PromptCachePromptLayers(
        coreSystemPrompt = "fixture-core",
        stableContextSystemPrompt = stableContext,
        dynamicSystemPrompt = "",
        tailSystemPrompt = tail,
        stablePrefixCacheable = true,
    )

    private fun model(baseUrl: String) = ModelConfig(
        id = "fixture",
        displayName = "fixture",
        modelName = "fixture",
        baseUrl = baseUrl,
        apiKey = "",
        createdAt = 0L,
    )

    private fun JsonElement.jsonText(): String = (this as JsonPrimitive).content

    private fun String.occurrenceCount(value: String): Int = Regex(Regex.escape(value)).findAll(this).count()

    private fun String.messageTextOccurrenceCount(value: String): Int =
        Json.parseToJsonElement(this).jsonObject.getValue("messages").jsonArray.sumOf { message ->
            when (val content = message.jsonObject.getValue("content")) {
                is JsonPrimitive -> if (content.content == value) 1 else 0
                else -> content.jsonArray.count { part -> part.jsonObject["text"]?.jsonPrimitive?.content == value }
            }
        }
}
