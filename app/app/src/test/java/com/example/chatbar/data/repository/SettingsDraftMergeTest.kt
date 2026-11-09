package com.example.chatbar.data.repository

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.ThemeMode
import org.junit.Assert.*
import org.junit.Test

class SettingsDraftMergeTest {
    @Test
    fun narrativeModeToggleMergesWithoutOverwritingNewRuntimeSettings() {
        val baseline = AppSettings()
        val latest = baseline.copy(lastSeenChatAt = 42L, chatBubbleFontScale = 1.2f)
        val merged = mergeSettingsDraft(AppSettings.serializer(), baseline,
            baseline.copy(disableNarrativeModes = true), latest)
        assertTrue(merged.disableNarrativeModes)
        assertEquals(latest.lastSeenChatAt, merged.lastSeenChatAt)
        assertEquals(latest.chatBubbleFontScale, merged.chatBubbleFontScale)
        assertTrue(mergeSettingsDraft(AppSettings.serializer(), baseline, baseline,
            latest.copy(disableNarrativeModes = true)).disableNarrativeModes)
    }

    @Test
    fun editedModelPreservesNewAppearanceAndRuntimeState() {
        val baseline = AppSettings(defaultModelId = "old", lastSeenMomentsAt = 10L)
        val draft = baseline.copy(defaultModelId = "chosen")
        val latest = baseline.copy(themeMode = ThemeMode.DARK, lastSeenMomentsAt = 80L)
        val merged = mergeSettingsDraft(AppSettings.serializer(), baseline, draft, latest)
        assertEquals("chosen", merged.defaultModelId)
        assertEquals(ThemeMode.DARK, merged.themeMode)
        assertEquals(80L, merged.lastSeenMomentsAt)
    }

    @Test
    fun explicitResetToInheritedValuePreservesUpdatedMemory() {
        val baseline = ChatSession("session", "character", "title", modelId = "override",
            longTermMemory = "before", createdAt = 1L, updatedAt = 1L)
        val draft = baseline.copy(modelId = null, audiobookModeEnabled = false)
        val latest = baseline.copy(longTermMemory = "new summary", nextSourceTurnOrder = 9L, imagePromptPreference = "new preference")
        val merged = mergeSettingsDraft(ChatSession.serializer(), baseline, draft, latest)
        assertNull(merged.modelId)
        assertEquals(false, merged.audiobookModeEnabled)
        assertEquals("new summary", merged.longTermMemory)
        assertEquals(9L, merged.nextSourceTurnOrder)
        assertEquals("new preference", merged.imagePromptPreference)
    }

    @Test
    fun untouchedDraftDoesNotUndoExternalChangesOrDefaultValues() {
        val baseline = AppSettings()
        val latest = baseline.copy(defaultModelId = "external", momentsEnabled = true)
        assertEquals(latest, mergeSettingsDraft(AppSettings.serializer(), baseline, baseline, latest))
    }
    @Test fun explicitClearOfOmittedDefaultFieldDoesNotKeepPriorPlayerBinding() {
        val baseline = ChatSession.create("synthetic-card", "synthetic").copy(
            desktopPlayerRole = com.example.chatbar.data.local.entity.SessionPlayerRole("synthetic-card", "synthetic-person"),
            desktopPlayerAvatar = "old.png")
        val latest = baseline.copy(lastMessagePreview = "new preview", desktopPlayerAvatar = "new.png")
        val merged = mergeSettingsDraft(ChatSession.serializer(), baseline, baseline.copy(desktopPlayerRole = null), latest)
        assertNull(merged.desktopPlayerRole)
        assertEquals("new.png", merged.desktopPlayerAvatar)
        assertEquals("new preview", merged.lastMessagePreview)
        assertEquals(latest, mergeSettingsDraft(ChatSession.serializer(), baseline, baseline, latest))
    }

    @Test fun resettingOmittedCollectionAndBooleanToDefaultsPreservesOtherExternalChoices() {
        val baseline = ChatSession.create("synthetic-card", "synthetic").copy(autoPauseAbsentPeople = true,
            characterParticipation = listOf(com.example.chatbar.data.local.entity.SessionCharacterParticipation("synthetic-card", setOf("person"))))
        val latest = baseline.copy(modelId = "new model")
        val merged = mergeSettingsDraft(ChatSession.serializer(), baseline,
            baseline.copy(autoPauseAbsentPeople = false, characterParticipation = emptyList()), latest)
        assertFalse(merged.autoPauseAbsentPeople)
        assertTrue(merged.characterParticipation.isEmpty())
        assertEquals("new model", merged.modelId)
    }

}
