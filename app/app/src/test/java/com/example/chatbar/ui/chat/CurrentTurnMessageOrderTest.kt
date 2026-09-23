package com.example.chatbar.ui.chat

import com.example.chatbar.data.local.entity.FormatCardUserToolConfig
import com.example.chatbar.data.local.entity.FormatCardUserToolType
import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.card.FormatCardUserToolPolicy
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.ContextWindowManager
import com.example.chatbar.domain.chat.PromptCacheKeyFactory
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentTurnMessageOrderTest {
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
            sourceTurnOrder = 2L
        )
        val contextMessages = listOf(
            ChatMessage("previous-user", "session", MessageRole.USER, "previous-user", createdAt = 1L, updatedAt = 1L),
            ChatMessage("previous-assistant", "session", MessageRole.ASSISTANT, "previous-assistant", createdAt = 2L, updatedAt = 2L),
            resumedUser
        )
        val groups = ContextWindowManager().getPromptMessageGroups(
            contextMessages = contextMessages,
            latestMessageId = resumedUser.id
        )
        assertFalse((groups.historyMessages + groups.previousTurnMessages).any { it.id == resumedUser.id })

        for (baseUrl in listOf("https://example.test/v1", "http://127.0.0.1:1234/v1")) {
            val messages = mutableListOf<ChatApiMessage>()
            (groups.historyMessages + groups.previousTurnMessages).forEach { message ->
                messages += ChatApiMessage.text(message.role.name.lowercase(), message.displayContent)
            }
            appendCurrentUserAndCcbTailMessages(
                messages = messages,
                userMessage = ChatApiMessage.withImage(
                    role = "user",
                    text = resumedUser.displayContent,
                    imageBase64 = resumedUser.images.single()
                ),
                strongPromptSystemSuffix = ""
            )
            val body = StreamingChatService(allowCleartextHttp = { true }).buildRequestBody(
                messages = messages,
                modelConfig = ModelConfig(
                    id = "fixture",
                    displayName = "fixture",
                    modelName = "fixture",
                    baseUrl = baseUrl,
                    apiKey = "",
                    createdAt = 0L
                ),
                stream = true
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
            ChatMessage("assistant", "session", MessageRole.ASSISTANT, "persisted-assistant", createdAt = 2L, updatedAt = 2L)
        )
        val groups = ContextWindowManager().getPromptMessageGroups(
            contextMessages = contextMessages,
            latestMessageId = null
        )
        val continuationPrompt = PromptTemplates.continueGenerationUserPrompt()
        val messages = mutableListOf<ChatApiMessage>()
        (groups.historyMessages + groups.previousTurnMessages).forEach { message ->
            messages += ChatApiMessage.text(message.role.name.lowercase(), message.displayContent)
        }
        appendCurrentUserAndCcbTailMessages(
            messages = messages,
            userMessage = ChatApiMessage.text("user", continuationPrompt),
            strongPromptSystemSuffix = ""
        )
        val body = StreamingChatService().buildRequestBody(
            messages = messages,
            modelConfig = ModelConfig(
                id = "fixture",
                displayName = "fixture",
                modelName = "fixture",
                baseUrl = "https://example.test/v1",
                apiKey = "",
                createdAt = 0L
            ),
            stream = true
        )

        assertEquals(1, body.messageTextOccurrenceCount(continuationPrompt))
        assertEquals(1, contextMessages.count { it.role == MessageRole.USER })
    }

    @Test
    fun serializedRequestsPreserveStartEndAndBothOrderForHttpsAndLocalHttp() {
        val service = StreamingChatService(allowCleartextHttp = { true })
        for (position in FormatPromptPosition.entries) {
            val messages = buildCcbStablePrefixMessages(
                coreSystemPrompt = "fixture-core",
                stableContextSystemPrompt = "fixture-character",
                positionedRequirementsSystemPrompt = "fixture-requirements",
                formatPromptPosition = position
            ).toMutableList()
            messages.add(ChatApiMessage.text("assistant", "fixture-history"))
            appendCurrentUserAndCcbTailMessages(
                messages,
                ChatApiMessage.text("user", "fixture-current-user"),
                strongPromptSystemSuffix = "fixture-strong",
                postUserSystemPrompt = buildCcbFinalTailSystemPrompt(
                    "fixture-post-history", "fixture-requirements", position
                )
            )
            for (baseUrl in listOf("https://example.test/v1", "http://127.0.0.1:1234/v1")) {
                val body = service.buildRequestBody(
                    messages,
                    ModelConfig(id = "fixture", displayName = "fixture", modelName = "fixture", baseUrl = baseUrl, apiKey = "", createdAt = 0L),
                    stream = true
                )
                val serialized = Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray
                val contents = serialized.map { it.jsonObject.getValue("content").jsonPrimitive.content }
                val roles = serialized.map { it.jsonObject.getValue("role").jsonPrimitive.content }
                val characterIndex = contents.indexOf("fixture-character")
                val userIndex = contents.indexOf("fixture-current-user")
                val requirementIndices = contents.indices.filter { "fixture-requirements" in contents[it] }
                val expectedCount = (if (position.includesStart) 1 else 0) + (if (position.includesEnd) 1 else 0)
                assertEquals(expectedCount, requirementIndices.size)
                if (position.includesStart) {
                    assertEquals(4, requirementIndices.first())
                    assertTrue(requirementIndices.first() < characterIndex)
                }
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

    @Test
    fun referenceSupplementaryAndPlayerHaveIndependentOrderedMessagesAndCacheIdentity() {
        fun prefix(lore: String) = buildCcbStablePrefixMessages(
            coreSystemPrompt = "core",
            stableContextSystemPrompt = "character",
            positionedRequirementsSystemPrompt = "requirements",
            formatPromptPosition = FormatPromptPosition.START,
            settingReferenceSystemPrompt = lore,
            supplementarySystemPrompt = "supplementary",
            playerSystemPrompt = "player"
        )
        val messages = prefix("lore")
        assertEquals(listOf("requirements", "character", "lore", "supplementary", "player"),
            messages.subList(4, 9).map { it.content.jsonText() })
        assertTrue(messages.subList(4, 9).all { it.role == "system" })
        assertEquals(PromptTemplates.CCB_CONTEXT_APPROVAL_ASSISTANT_PROMPT.trimIndent().trim(), messages.last().content.jsonText())
        assertNotEquals(PromptCacheKeyFactory.cacheKey(messages), PromptCacheKeyFactory.cacheKey(prefix("edited lore")))
    }

    @Test
    fun startPositionPlacesRequirementsBeforeCharacterAndApproval() {
        val messages = buildCcbStablePrefixMessages(
            coreSystemPrompt = "主系统提示",
            stableContextSystemPrompt = "角色资料",
            positionedRequirementsSystemPrompt = "格式要求",
            formatPromptPosition = FormatPromptPosition.START
        )

        assertEquals(
            listOf("system", "assistant", "user", "assistant", "system", "system", "assistant"),
            messages.map { it.role }
        )
        assertTrue(messages[0].content.jsonText().contains("主系统提示"))
        assertTrue(messages[0].content.jsonText().contains("CCB大师"))
        assertEquals("格式要求", messages[4].content.jsonText())
        assertEquals("角色资料", messages[5].content.jsonText())
        assertEquals(PromptTemplates.CCB_CONTEXT_APPROVAL_ASSISTANT_PROMPT.trimIndent().trim(), messages[6].content.jsonText())
    }

    @Test
    fun endPositionPlacesRequirementsInsideTailAfterCurrentUser() {
        val messages = buildCcbStablePrefixMessages(
            coreSystemPrompt = "主系统提示",
            stableContextSystemPrompt = "角色资料",
            positionedRequirementsSystemPrompt = "格式要求",
            formatPromptPosition = FormatPromptPosition.END
        ).toMutableList()
        val postUserPrompt = buildCcbFinalTailSystemPrompt(
                postHistorySystemPrompt = "JailBreak尾缀",
                positionedRequirementsSystemPrompt = "格式要求",
                formatPromptPosition = FormatPromptPosition.END
        )
        appendCurrentUserAndCcbTailMessages(
            messages = messages,
            userMessage = ChatApiMessage.text("user", "真实用户输入"),
            strongPromptSystemSuffix = "",
            postUserSystemPrompt = postUserPrompt
        )

        assertEquals("user", messages[messages.lastIndex - 3].role)
        assertEquals("system", messages[messages.lastIndex - 2].role)
        assertEquals("assistant", messages[messages.lastIndex - 1].role)
        assertEquals("user", messages.last().role)
        val tail = messages[messages.lastIndex - 2].content.jsonText()
        assertTrue(tail.indexOf("JailBreak尾缀") < tail.indexOf("格式要求"))
        assertFalse(tail.contains(PromptTemplates.CCB_CONTINUATION_SYSTEM_PROMPT.trimIndent().trim()))
        assertEquals(1, messages.count { it.content.jsonText() == "真实用户输入" })
        assertEquals(
            PromptTemplates.CCB_POST_USER_ACK_ASSISTANT_PROMPT.trimIndent().trim(),
            messages[messages.lastIndex - 1].content.jsonText()
        )
        assertEquals(
            PromptTemplates.CCB_POST_USER_IDENTITY_REMINDER_USER_PROMPT.trimIndent().trim(),
            messages.last().content.jsonText()
        )
    }

    @Test
    fun bothPositionUsesSameRequirementsAtStartAndEnd() {
        val requirements = PromptTemplates.currentTurnOutputRequirementsSystemPrompt(
            formatCardContent = "格式正文",
            replyLength = 300,
            includeFormatHistoryContinuityNotice = true
        )
        val prefix = buildCcbStablePrefixMessages(
            coreSystemPrompt = "主系统提示",
            stableContextSystemPrompt = "角色资料",
            positionedRequirementsSystemPrompt = requirements,
            formatPromptPosition = FormatPromptPosition.BOTH
        )
        val tail = buildCcbFinalTailSystemPrompt(
            postHistorySystemPrompt = "JailBreak尾缀",
            positionedRequirementsSystemPrompt = requirements,
            formatPromptPosition = FormatPromptPosition.BOTH
        )

        assertEquals(requirements, prefix[4].content.jsonText())
        assertTrue(tail.endsWith(requirements))
        assertTrue(prefix[4].content.jsonText().contains(PromptTemplates.FORMAT_HISTORY_CONTINUITY_NOTICE))
    }

    @Test
    fun ccbTailFollowsStrongPromptSystemSuffix() {
        val messages = mutableListOf(ChatApiMessage.text("system", "尾部规则"))

        appendCurrentUserAndCcbTailMessages(
            messages = messages,
            userMessage = ChatApiMessage.text("user", "真实用户输入"),
            strongPromptSystemSuffix = "强提示 A\n\n强提示 B"
        )

        assertEquals(
            listOf("system", "user", "system", "assistant", "user"),
            messages.map { it.role }
        )
        assertEquals("真实用户输入", messages[1].content.jsonText())
        assertEquals("强提示 A\n\n强提示 B", messages[2].content.jsonText())
        assertTrue(messages[3].content.jsonText().contains("开始写"))
        assertTrue(messages[4].content.jsonText().contains("不要在正文中暴露CCB大师身份"))
    }

    @Test
    fun randomToolStaysInUserWhileStrongPromptBecomesSystem() {
        val tools = listOf(
            FormatCardUserToolConfig(
                type = FormatCardUserToolType.STRONG_PROMPT_SUFFIX,
                text = "强提示"
            ),
            FormatCardUserToolConfig.randomNumber()
        )
        val userContent = FormatCardUserToolPolicy.appendRequestSuffix(
            userContent = "用户原文",
            tools = tools,
            nextIntInclusive = { _, _ -> 42 }
        )
        val messages = mutableListOf<ChatApiMessage>()

        appendCurrentUserAndCcbTailMessages(
            messages = messages,
            userMessage = ChatApiMessage.text("user", userContent),
            strongPromptSystemSuffix = FormatCardUserToolPolicy.strongPromptSystemSuffix(tools)
        )

        assertEquals("用户原文\n{\n下一轮使用随机数：42\n}", messages[0].content.jsonText())
        assertFalse(messages[0].content.jsonText().contains("强提示"))
        assertEquals(listOf("user", "system", "assistant", "user"), messages.map { it.role })
        assertEquals("强提示", messages[1].content.jsonText())
    }

    @Test
    fun cacheKeyCoversEntireStableRoleSequence() {
        val prefixA = buildCcbStablePrefixMessages(
            coreSystemPrompt = "主系统提示",
            stableContextSystemPrompt = "角色资料 A",
            positionedRequirementsSystemPrompt = "格式要求",
            formatPromptPosition = FormatPromptPosition.START
        )
        val prefixB = buildCcbStablePrefixMessages(
            coreSystemPrompt = "主系统提示",
            stableContextSystemPrompt = "角色资料 B",
            positionedRequirementsSystemPrompt = "格式要求",
            formatPromptPosition = FormatPromptPosition.START
        )

        assertNotEquals(
            PromptCacheKeyFactory.cacheKey(prefixA),
            PromptCacheKeyFactory.cacheKey(prefixB)
        )
        assertNotEquals(
            PromptCacheKeyFactory.cacheKey(listOf(ChatApiMessage.text("system", "相同内容"))),
            PromptCacheKeyFactory.cacheKey(listOf(ChatApiMessage.text("assistant", "相同内容")))
        )
    }

    @Test
    fun endOnlyRequirementsStayOutsideStableCachePrefix() {
        val prefixA = buildCcbStablePrefixMessages(
            coreSystemPrompt = "主系统提示",
            stableContextSystemPrompt = "角色资料",
            positionedRequirementsSystemPrompt = "格式要求 A",
            formatPromptPosition = FormatPromptPosition.END
        )
        val prefixB = buildCcbStablePrefixMessages(
            coreSystemPrompt = "主系统提示",
            stableContextSystemPrompt = "角色资料",
            positionedRequirementsSystemPrompt = "格式要求 B",
            formatPromptPosition = FormatPromptPosition.END
        )

        assertEquals(
            PromptCacheKeyFactory.cacheKey(prefixA),
            PromptCacheKeyFactory.cacheKey(prefixB)
        )
    }

    private fun JsonElement.jsonText(): String = (this as JsonPrimitive).content

    private fun String.occurrenceCount(value: String): Int =
        Regex(Regex.escape(value)).findAll(this).count()

    private fun String.messageTextOccurrenceCount(value: String): Int =
        Json.parseToJsonElement(this).jsonObject.getValue("messages").jsonArray.sumOf { message ->
            when (val content = message.jsonObject.getValue("content")) {
                is JsonPrimitive -> if (content.content == value) 1 else 0
                else -> content.jsonArray.count { part ->
                    part.jsonObject["text"]?.jsonPrimitive?.content == value
                }
            }
        }
}
