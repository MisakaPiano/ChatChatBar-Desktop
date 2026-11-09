package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PlayerRolePolicyTest {
    private val person = CharacterInfo.create("linked person").copy(appearanceImage = "person.png", enabled = false)
    private val card = CharacterCard.create("synthetic", characters = listOf(person))
    private val session = ChatSession.create(card.id, "synthetic").copy(playerName = "override", desktopPlayerAvatar = "session.png")

    @Test fun sameNamesNeverCreateAssociation() {
        val matching = session.copy(playerName = person.name)
        assertNull(PlayerRolePolicy.person(matching, card))
        assertEquals("session.png", PlayerRolePolicy.avatar(matching, card, "global.png"))
    }
    @Test fun explicitRoleFollowsNameAndAvatarEvenWhenPersonIsDisabledWithoutChangingGates() {
        val linked = session.copy(desktopPlayerRole = SessionPlayerRole(card.id, person.id))
        assertEquals(person.name, PlayerRolePolicy.name(linked, card, "global"))
        assertEquals("person.png", PlayerRolePolicy.avatar(linked, card, "global.png"))
        assertFalse(card.characters.single().enabled)
        assertEquals("override", linked.playerName)
    }
    @Test fun independentFollowFlagsFallBackToSessionThenGlobalWithoutOverwritingOverrides() {
        val linked = session.copy(desktopPlayerRole = SessionPlayerRole(card.id, person.id, followName = false))
        assertEquals("override", PlayerRolePolicy.name(linked, card, "global"))
        assertEquals("person.png", PlayerRolePolicy.avatar(linked, card, "global.png"))
        val avatarOff = linked.copy(desktopPlayerRole = linked.desktopPlayerRole!!.copy(followName = true, followAvatar = false))
        assertEquals(person.name, PlayerRolePolicy.name(avatarOff, card, "global"))
        assertEquals("session.png", PlayerRolePolicy.avatar(avatarOff, card, "global.png"))
        assertEquals("global.png", PlayerRolePolicy.avatar(avatarOff.copy(desktopPlayerAvatar = null), card, "global.png"))
    }
    @Test fun wrongCardMissingOrAmbiguousIdsNeverRebindByName() {
        val linked = session.copy(desktopPlayerRole = SessionPlayerRole(card.id, person.id))
        for (other in listOf(card.copy(id = "other"), card.copy(characters = emptyList()), card.copy(characters = listOf(person, person)))) {
            assertNull(PlayerRolePolicy.person(linked, other))
            assertEquals("override", PlayerRolePolicy.name(linked, other, "global"))
            assertEquals("session.png", PlayerRolePolicy.avatar(linked, other, "global.png"))
        }
    }
    @Test fun renamedPersonWithSameIdFollowsNewNameAndUnlinkRestoresOriginalOverrides() {
        val linked = session.copy(desktopPlayerRole = SessionPlayerRole(card.id, person.id))
        val renamed = card.copy(characters = listOf(person.copy(name = "renamed", appearanceImage = "new.png")))
        assertEquals("renamed", PlayerRolePolicy.name(linked, renamed, "global"))
        assertEquals("new.png", PlayerRolePolicy.avatar(linked, renamed, "global.png"))
        assertEquals("override", PlayerRolePolicy.name(linked.copy(desktopPlayerRole = null), renamed, "global"))
    }
    @Test fun oldRecordsDefaultToNoPlayerImageOrRoleAndDesktopFieldNamesRoundTrip() {
        val json = Json { encodeDefaults = true }
        val old = json.decodeFromString(ChatSession.serializer(), """{"id":"s","characterCardId":"c","title":"old","createdAt":1,"updatedAt":1}""")
        assertNull(old.desktopPlayerRole); assertNull(old.desktopPlayerAvatar)
        val linked = session.copy(desktopPlayerRole = SessionPlayerRole(card.id, person.id))
        assertEquals(linked, json.decodeFromString(ChatSession.serializer(), json.encodeToString(ChatSession.serializer(), linked)))
        val player = PlayerSetting(desktopAvatar = "global.png")
        assertEquals(player, json.decodeFromString(PlayerSetting.serializer(), json.encodeToString(PlayerSetting.serializer(), player)))
    }
}
