package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import org.junit.Assert.*
import org.junit.Test

class CharacterCoverAssetsTest {
    @Test fun candidatesContainOnlyExistingCardReferencesAndMergeRolesInFirstOccurrenceOrder() {
        val card = CharacterCard.create("synthetic").copy(coverImage = "original", avatar = "shared",
            chatBackground = "outside", chatBackgroundImages = listOf("shared", "gallery", "shared", "missing", ""),
            characters = listOf(CharacterInfo.create("甲").copy(appearanceImage = "person")))
        val assets = CharacterCoverAssets.available(card) { it != "missing" }
        assertEquals(listOf("original", "shared", "outside", "gallery", "person"), assets.map { it.path })
        assertEquals(listOf("角色卡头像", "背景图库 1", "背景图库 3"), assets[1].labels)
        assertFalse(assets.any { it.path == "foreign-card" || it.path == "missing" })
    }

    @Test fun selectionAndDisplayTextDoNotMutateCardOrDefaultAndBackgroundAloneKeepsRenderedMode() {
        val card = CharacterCard.create("synthetic").copy(chatBackground = "default")
        val defaults = CharacterCardPngExportOptions()
        assertEquals("default", defaults.baseImagePath(card))
        assertFalse(defaults.canKeepOriginal(card))
        val selected = defaults.copy(coverImagePath = "selected", displayText = "export title")
        assertTrue(selected.canKeepOriginal(card))
        assertEquals("selected", selected.baseImagePath(card))
        assertEquals("default", card.chatBackground)
        assertNull(card.coverImage)
        assertEquals("synthetic", card.name)
    }
}
