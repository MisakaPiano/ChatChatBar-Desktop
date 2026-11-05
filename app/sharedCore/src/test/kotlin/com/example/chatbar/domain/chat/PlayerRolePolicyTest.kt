package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*
import kotlin.test.*

class PlayerRolePolicyTest {
    @Test fun `matching names never bind while explicit identity survives rename and rejects stale or ambiguous targets`() {
        val person = CharacterInfo.create("Alice").copy(appearanceImage = "images/alice.png")
        val card = CharacterCard.create("Card", characters = listOf(person))
        val session = ChatSession.create(card.id, "Chat").copy(playerName = "Alice")
        assertNull(PlayerRolePolicy.person(session, card))
        val bound = session.copy(desktopPlayerRole = SessionPlayerRole(card.id, person.id))
        assertEquals(person, PlayerRolePolicy.person(bound, card))
        assertEquals("Renamed", PlayerRolePolicy.name(bound, card.copy(characters = listOf(person.copy(name = "Renamed"))), "Global"))
        assertNull(PlayerRolePolicy.person(bound, card.copy(id = "other-card")))
        assertNull(PlayerRolePolicy.person(bound, card.copy(characters = listOf(person, person.copy(name = "Other")))))
        assertNull(PlayerRolePolicy.person(bound, card.copy(characters = listOf(person.copy(id = "replacement")))))
    }

    @Test fun `role following independently overrides session values which independently override global values`() {
        val person = CharacterInfo.create("Role").copy(appearanceImage = "images/role.png")
        val card = CharacterCard.create("Card", characters = listOf(person))
        val original = ChatSession.create(card.id, "Chat")
        assertEquals("Global", PlayerRolePolicy.name(original, card, "Global"))
        assertEquals("images/global.png", PlayerRolePolicy.avatar(original, card, "images/global.png"))
        val local = original.copy(playerName = "Session", desktopPlayerAvatar = "images/session.png")
        assertEquals("Session", PlayerRolePolicy.name(local, card, "Global"))
        assertEquals("images/session.png", PlayerRolePolicy.avatar(local, card, "images/global.png"))
        val bound = local.copy(desktopPlayerRole = SessionPlayerRole(card.id, person.id))
        assertEquals("Role", PlayerRolePolicy.name(bound, card, "Global"))
        assertEquals("images/role.png", PlayerRolePolicy.avatar(bound, card, "images/global.png"))
        val customName = bound.copy(desktopPlayerRole = bound.desktopPlayerRole!!.copy(followName = false))
        assertEquals("Session", PlayerRolePolicy.name(customName, card, "Global"))
        assertEquals("images/role.png", PlayerRolePolicy.avatar(customName, card, "images/global.png"))
        val customAvatar = bound.copy(desktopPlayerRole = bound.desktopPlayerRole!!.copy(followAvatar = false))
        assertEquals("Role", PlayerRolePolicy.name(customAvatar, card, "Global"))
        assertEquals("images/session.png", PlayerRolePolicy.avatar(customAvatar, card, "images/global.png"))
        assertEquals(original.desktopCharacterParticipation, bound.desktopCharacterParticipation)
    }
}
