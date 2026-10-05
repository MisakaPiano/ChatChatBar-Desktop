package com.example.chatbar.domain.moment

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import org.junit.Assert.*
import org.junit.Test

class MomentPostEditingTest {
    @Test fun `sender choices retain ids and use same avatar precedence as generation`() {
        val card = CharacterCard(id = "card", name = "卡", avatar = "card.png", characters = listOf(
            CharacterInfo("a", "同名", appearanceImage = "a.png"),
            CharacterInfo("b", "同名", appearanceImage = " ")
        ), createdAt = 0, updatedAt = 0)
        val options = MomentPostEditing.senderOptions(card)
        assertEquals(3, options.map { it.key }.distinct().size)
        assertEquals(MomentSender(null, "卡", "card.png"), options[0].sender)
        assertEquals(MomentSender("a", "同名", "a.png"), options[1].sender)
        assertEquals(MomentSender("b", "同名", "card.png"), options[2].sender)
        assertTrue(MomentPostEditing.senderOptions(null).isEmpty())
    }
}
