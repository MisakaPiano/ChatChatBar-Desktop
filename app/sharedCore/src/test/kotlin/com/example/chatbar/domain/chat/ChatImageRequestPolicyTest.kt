package com.example.chatbar.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatImageRequestPolicyTest {
    @Test fun `only first user attachment is admitted and generated assistant images stay out`() {
        assertEquals("first", ChatImageRequestPolicy.firstUserImage("user", listOf("first", "second"), true))
        assertNull(ChatImageRequestPolicy.firstUserImage("assistant", listOf("generated"), true))
        assertNull(ChatImageRequestPolicy.firstUserImage("user", listOf("first"), false))
        assertNull(ChatImageRequestPolicy.firstUserImage("user", emptyList(), true))
    }
}
