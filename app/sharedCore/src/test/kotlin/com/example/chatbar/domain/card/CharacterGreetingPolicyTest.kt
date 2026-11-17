package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard
import kotlin.test.*

class CharacterGreetingPolicyTest {
    private val card = CharacterCard("synthetic", "Card", greeting = " Main\n exact  ",
        alternateGreetings = listOf("", "  ", "First  ", "Second\nexact"), createdAt = 1, updatedAt = 1)

    @Test fun `choices keep original positions and text without normalizing the card`() {
        val choices = CharacterGreetingPolicy.choices(card)
        assertEquals(listOf(0, 3, 4), choices.map { it.position })
        assertEquals(listOf(card.greeting, "First  ", "Second\nexact"), choices.map { it.text })
        assertEquals(card.greeting, CharacterGreetingPolicy.resolve(card, null))
        assertEquals("First  ", CharacterGreetingPolicy.resolve(card, choices[1]))
        assertEquals(listOf("", "  ", "First  ", "Second\nexact"), card.alternateGreetings)
    }

    @Test fun `changed main reordered alternatives wrong card and removed choice are refused`() {
        val choices = CharacterGreetingPolicy.choices(card)
        for ((current, choice) in listOf(
            card.copy(greeting = "Changed") to choices.first(),
            card.copy(alternateGreetings = card.alternateGreetings.reversed()) to choices[1],
            card.copy(id = "other") to choices.last(),
            card.copy(alternateGreetings = emptyList()) to choices.last(),
        )) assertFailsWith<IllegalArgumentException> { CharacterGreetingPolicy.resolve(current, choice) }
    }

    @Test fun `legacy blank main remains a valid single opening`() {
        val empty = card.copy(greeting = "", alternateGreetings = listOf("", "\n"))
        assertEquals(listOf(CharacterGreetingChoice(empty.id, 0, "")), CharacterGreetingPolicy.choices(empty))
        assertEquals("", CharacterGreetingPolicy.resolve(empty, null))
    }
}
