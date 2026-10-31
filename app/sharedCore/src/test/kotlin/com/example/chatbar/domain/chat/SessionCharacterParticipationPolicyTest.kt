package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*
import kotlinx.serialization.json.Json
import com.example.chatbar.data.repository.mergeSettingsDraft
import kotlin.test.*

class SessionCharacterParticipationPolicyTest {
    private val first = CharacterInfo.create("First").copy(profile = "first profile", fishAudioVoice = FishAudioVoiceBinding("one", "One"))
    private val second = CharacterInfo.create("Second").copy(profile = "second profile", fishAudioVoice = FishAudioVoiceBinding("two", "Two"))
    private val card = CharacterCard.create("Card").copy(characters = listOf(first, second), basicSetting = "Shared setting")
    private val session = ChatSession.create(card.id, "Chat")
    private val json = Json { encodeDefaults = true }

    @Test fun `residency does not pause by default and optional pause never overrides the card gate`() {
        val absent = SessionCharacterParticipationPolicy.afterReply(session, card, emptySet(), setOf(second.id))
        assertEquals(session, absent)
        val auto = SessionCharacterParticipationPolicy.withAutomaticPause(session, true)
        val paused = SessionCharacterParticipationPolicy.afterReply(auto, card, emptySet(), setOf(second.id))
        assertFalse(SessionCharacterParticipationPolicy.isEnabled(paused, card, second.id))
        val manuallyClosed = SessionCharacterParticipationPolicy.change(paused, card, first.id, false)
        val optionOff = SessionCharacterParticipationPolicy.withAutomaticPause(manuallyClosed, false)
        assertFalse(SessionCharacterParticipationPolicy.isEnabled(optionOff, card, first.id))
        assertTrue(SessionCharacterParticipationPolicy.isEnabled(optionOff, card, second.id))
        val reopened = SessionCharacterParticipationPolicy.afterReply(manuallyClosed, card, setOf(first.id, second.id), emptySet())
        assertTrue(reopened.desktopCharacterParticipation.isEmpty())
        val hardClosed = card.copy(characters = listOf(first.copy(desktopEnabled = false), second))
        val kept = SessionCharacterParticipationPolicy.afterReply(manuallyClosed, hardClosed, setOf(first.id, second.id), emptySet())
        assertFalse(SessionCharacterParticipationPolicy.isEnabled(kept, hardClosed, first.id))
        assertTrue(first.id in kept.desktopCharacterParticipation.single().disabledPersonIds)
        val slot = SaveSlotSessionPolicy.create(manuallyClosed, "Snapshot", null, null, SaveSlotImagePolicy.ORIGINAL, false)
        val restored = SaveSlotSessionPolicy.restore(session, slot, null)
        assertTrue(restored.desktopAutoPauseAbsentPeople)
        assertEquals(manuallyClosed.desktopCharacterParticipation, restored.desktopCharacterParticipation)
        assertFalse("desktopAutoPauseAbsentPeople" in json.encodeToString(ChatSession.serializer(), session))
    }

    @Test fun `card variant gate beats session enable and never removes source fields`() {
        val variant = card.copy(characters = listOf(first, second.copy(desktopEnabled = false)))
        assertEquals(listOf(first), SessionCharacterParticipationPolicy.effectiveCard(session, variant).characters)
        assertFalse(SessionCharacterParticipationPolicy.isEnabled(null, variant, second.id))
        assertFailsWith<IllegalArgumentException> { SessionCharacterParticipationPolicy.change(session, variant, second.id, true) }
        assertEquals(second.profile, variant.characters.last().profile)
        assertEquals(second.fishAudioVoice, variant.characters.last().fishAudioVoice)
        val encoded = json.encodeToString(CharacterCard.serializer(), variant)
        assertEquals(variant, json.decodeFromString(CharacterCard.serializer(), encoded))
        assertFalse("desktopEnabled" in json.encodeToString(CharacterCard.serializer(), card))
        val freeform = variant.copy(editMode = CharacterEditMode.FREEFORM)
        assertSame(freeform, SessionCharacterParticipationPolicy.effectiveCard(session, freeform))
    }

    @Test fun `clearing omitted defaults persists while unrelated latest runtime and unedited participation survive`() {
        val disabled = SessionCharacterParticipationPolicy.change(session, card, second.id, false)
        val latest = disabled.copy(lastMessagePreview = "new runtime preview", replyLength = 1234)
        val cleared = SessionCharacterParticipationPolicy.change(disabled, card, second.id, true)
        val merged = mergeSettingsDraft(ChatSession.serializer(), disabled, cleared, latest)
        assertTrue(merged.desktopCharacterParticipation.isEmpty())
        assertEquals("new runtime preview", merged.lastMessagePreview)
        assertEquals(1234, merged.replyLength)
        val unrelatedEdit = session.copy(replyLanguage = "new language")
        val preserved = mergeSettingsDraft(ChatSession.serializer(), session, unrelatedEdit, disabled)
        assertEquals(disabled.desktopCharacterParticipation, preserved.desktopCharacterParticipation)
        assertEquals("new language", preserved.replyLanguage)
    }

    @Test fun `disabled person changes only a request view and stays scoped to card and session`() {
        val changed = SessionCharacterParticipationPolicy.change(session, card, second.id, false)
        val effective = SessionCharacterParticipationPolicy.effectiveCard(changed, card)
        assertEquals(listOf(first), effective.characters)
        assertEquals(card.copy(characters = listOf(first)), effective)
        assertEquals(listOf(first, second), card.characters)
        assertEquals(listOf(first, second), SessionCharacterParticipationPolicy.effectiveCard(session, card).characters)
        assertEquals(listOf(first, second), SessionCharacterParticipationPolicy.effectiveCard(changed, card.copy(id = "other-card")).characters)
        assertEquals(session.copy(desktopCharacterParticipation = listOf(SessionCharacterParticipation(card.id, setOf(second.id)))), changed)
        assertEquals(session, SessionCharacterParticipationPolicy.change(changed, card, second.id, true))
    }

    @Test fun `all people can be disabled without erasing shared setting and unknown older IDs remain preserved`() {
        val withUnknown = session.copy(desktopCharacterParticipation = listOf(SessionCharacterParticipation("old-card", setOf("old-person")),
            SessionCharacterParticipation(card.id, setOf("deleted-person"))))
        val one = SessionCharacterParticipationPolicy.change(withUnknown, card, first.id, false)
        val all = SessionCharacterParticipationPolicy.change(one, card, second.id, false)
        assertTrue(SessionCharacterParticipationPolicy.effectiveCard(all, card).characters.isEmpty())
        assertEquals(card.basicSetting, SessionCharacterParticipationPolicy.effectiveCard(all, card).basicSetting)
        assertEquals(setOf("old-person"), all.desktopCharacterParticipation.single { it.cardId == "old-card" }.disabledPersonIds)
        assertTrue("deleted-person" in all.desktopCharacterParticipation.single { it.cardId == card.id }.disabledPersonIds)
    }

    @Test fun `freeform body is neither split nor changed and ambiguous targets are refused`() {
        val freeform = card.copy(editMode = CharacterEditMode.FREEFORM, freeformCharacterText = "First and Second share prose")
        assertFailsWith<IllegalArgumentException> { SessionCharacterParticipationPolicy.change(session, freeform, second.id, false) }
        val disabled = SessionCharacterParticipationPolicy.change(session, card, second.id, false)
        assertSame(freeform, SessionCharacterParticipationPolicy.effectiveCard(disabled, freeform))
        assertFailsWith<IllegalArgumentException> { SessionCharacterParticipationPolicy.change(session, card, "missing", false) }
        assertFailsWith<IllegalArgumentException> { SessionCharacterParticipationPolicy.change(session, card.copy(characters = listOf(first, first)), first.id, false) }
    }

    @Test fun `legacy JSON defaults to enabled while session and SaveSlot settings roundtrip current overrides`() {
        val legacy = """{"id":"s","characterCardId":"c","title":"Legacy","createdAt":1,"updatedAt":1}"""
        assertTrue(json.decodeFromString(ChatSession.serializer(), legacy).desktopCharacterParticipation.isEmpty())
        assertFalse("desktopCharacterParticipation" in json.encodeToString(ChatSession.serializer(), session))
        val changed = SessionCharacterParticipationPolicy.change(session, card, second.id, false)
        assertEquals(changed, json.decodeFromString(ChatSession.serializer(), json.encodeToString(ChatSession.serializer(), changed)))
        val slot = SaveSlotSessionPolicy.create(changed, "Saved", null, null, SaveSlotImagePolicy.ORIGINAL, false)
        val decoded = json.decodeFromString(SaveSlot.serializer(), json.encodeToString(SaveSlot.serializer(), slot))
        assertEquals(changed.desktopCharacterParticipation, decoded.desktopCharacterParticipation)
        assertEquals(changed.desktopCharacterParticipation,
            SaveSlotSessionPolicy.restore(session, decoded, null).desktopCharacterParticipation)
        assertTrue(json.decodeFromString(SaveSlot.serializer(), """{"id":"slot","sessionId":"s","name":"Legacy","createdAt":1}""").desktopCharacterParticipation.isEmpty())
    }
}
