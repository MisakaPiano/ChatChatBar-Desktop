package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatSession
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticChatImagePolicyTest {
    @Test
    fun `only explicit normal completion passes transport gate`() {
        assertNull(AutomaticChatImagePolicy.skipReason(ChatReplyCompletion("stop"), "故事正文"))
        listOf(null, "", "length", "content_filter", "tool_calls", "unknown").forEach { reason ->
            assertNotNull(AutomaticChatImagePolicy.skipReason(ChatReplyCompletion(reason), "已有部分正文"))
        }
        assertNotNull(AutomaticChatImagePolicy.skipReason(null, "故事正文"))
        assertNotNull(AutomaticChatImagePolicy.skipReason(ChatReplyCompletion("stop", refused = true), "正文"))
        assertNotNull(AutomaticChatImagePolicy.skipReason(ChatReplyCompletion("stop", transportFailed = true), "正文"))
        assertNotNull(AutomaticChatImagePolicy.skipReason(ChatReplyCompletion("stop"), "  "))
    }

    @Test
    fun `semantic verdict requires complete related non refusal reply`() {
        assertNull(AutomaticChatImageJudge.parseSkipReason(
            """{"complete":true,"continuesStory":true,"refused":false}"""
        ))
        listOf(
            """{"complete":false,"continuesStory":true,"refused":false}""",
            """{"complete":true,"continuesStory":false,"refused":false}""",
            """{"complete":true,"continuesStory":true,"refused":true}"""
        ).forEach { assertNotNull(AutomaticChatImageJudge.parseSkipReason(it)) }
        listOf("", "yes", "{}", "null", """{"complete":true}""").forEach {
            assertTrue(runCatching { AutomaticChatImageJudge.parseSkipReason(it) }.isFailure)
        }
    }

    @Test
    fun `legacy session defaults off and new setting survives serialization`() {
        val session = Json.decodeFromString<ChatSession>(
            """{"id":"s","characterCardId":"c","title":"story","createdAt":1,"updatedAt":1}"""
        )
        assertFalse(session.automaticImageGenerationEnabled)
        val restored = Json.decodeFromString<ChatSession>(
            Json.encodeToString(ChatSession.serializer(), session.copy(automaticImageGenerationEnabled = true))
        )
        assertTrue(restored.automaticImageGenerationEnabled)
    }
}
