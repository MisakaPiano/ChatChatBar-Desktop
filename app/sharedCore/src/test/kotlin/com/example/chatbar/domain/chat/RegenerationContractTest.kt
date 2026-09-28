package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.ui.chat.*
import kotlin.test.*

class RegenerationContractTest {
    private fun message(id: String, role: MessageRole, text: String) =
        ChatMessage.create("session", role, text).copy(id = id)

    @Test fun `append preserves identity and aligns capped stable versions`() {
        val original = message("reply", MessageRole.ASSISTANT, "original").copy(
            sourceTurnId = "turn", sourceTurnOrder = 4, timelineTurn = 2, orderKey = 50,
        )
        var current = original
        repeat(7) { index ->
            current = MessageAlternativeVersionPolicy.append(current, "reply-$index", "version-$index")
            assertEquals(original.id, current.id)
            assertEquals(original.createdAt, current.createdAt)
            assertEquals(original.orderKey, current.orderKey)
            assertEquals(original.sourceTurnId, current.sourceTurnId)
            assertEquals(original.sourceTurnOrder, current.sourceTurnOrder)
            assertEquals(original.timelineTurn, current.timelineTurn)
            assertEquals(current.alternatives.lastIndex, current.currentAlternativeIndex)
            assertEquals("version-$index", current.currentAlternativeVersionId)
            if (index == 0) assertEquals(listOf("original", "reply-0"), current.alternatives)
        }
        assertEquals(5, current.alternatives.size)
        assertEquals((2..6).map { "version-$it" }, current.alternativeVersionIds)
        assertFailsWith<IllegalArgumentException> {
            MessageAlternativeVersionPolicy.append(current, "duplicate", "version-6")
        }
    }

    @Test fun `retry maps only latest official system error to latest eligible reply`() {
        val user = message("user", MessageRole.USER, "input")
        val reply = message("reply", MessageRole.ASSISTANT, "answer")
        val error = message("error", MessageRole.SYSTEM, "错误: failure")
        val messages = listOf(user, reply, error)
        assertTrue(error.isRetryableGenerationError())
        assertEquals(reply, resolveRegenerationTarget(error, messages))
        assertEquals(reply.id, regenerationTargetAssistantMessageId(messages, error.id))
        assertNull(resolveRegenerationTarget(user, messages))
        assertNull(resolveRegenerationTarget(error, messages + user.copy(id = "later")))
        assertNull(resolveRegenerationTarget(error.copy(content = "status"), messages))
        assertNull(resolveRegenerationTarget(reply.copy(content = ""), messages))
        assertFalse(error.copy(role = MessageRole.ASSISTANT).isRetryableGenerationError())
    }
}
