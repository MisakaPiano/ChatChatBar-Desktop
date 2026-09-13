package com.example.chatbar.domain.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.TimeZone

class NovelAiHistoryFoldingTest {
    private val recipe = NovelAiGenerationRecipe(
        stylePrompt = "ink", basePrompt = "forest", negativePrompt = "blur",
        characters = listOf(NovelAiCharacterPromptDraft(prompt = "girl", negativePrompt = "hat"))
    )
    private fun key(value: NovelAiGenerationRecipe, type: NovelAiHistoryFoldType) =
        NovelAiHistoryFolding.key(NovelAiGenerationHistoryEntry(recipe = value), type)

    @Test fun fullIgnoresIdentityAndSettingsButPreservesExactPrompts() {
        val copied = recipe.copy(
            characters = recipe.characters.map { it.copy(id = "different", negativeExpanded = true) },
            settings = recipe.settings.copy(seed = 999)
        )
        assertEquals(key(recipe, NovelAiHistoryFoldType.FULL), key(copied, NovelAiHistoryFoldType.FULL))
        assertNotEquals(key(recipe, NovelAiHistoryFoldType.FULL), key(recipe.copy(basePrompt = "forest "), NovelAiHistoryFoldType.FULL))
        assertNotEquals(key(recipe, NovelAiHistoryFoldType.FULL), key(recipe.copy(negativePrompt = "text"), NovelAiHistoryFoldType.FULL))
    }

    @Test fun modesCompareOnlyTheirOwnedFields() {
        val changedStyle = recipe.copy(stylePrompt = "oil")
        assertEquals(key(recipe, NovelAiHistoryFoldType.CONTENT), key(changedStyle, NovelAiHistoryFoldType.CONTENT))
        assertNotEquals(key(recipe, NovelAiHistoryFoldType.FULL), key(changedStyle, NovelAiHistoryFoldType.FULL))
        val changedRole = recipe.copy(characters = listOf(NovelAiCharacterPromptDraft(prompt = "boy")))
        assertEquals(key(recipe, NovelAiHistoryFoldType.BASE), key(changedRole, NovelAiHistoryFoldType.BASE))
        assertNotEquals(key(recipe, NovelAiHistoryFoldType.CONTENT), key(changedRole, NovelAiHistoryFoldType.CONTENT))
        assertEquals(key(recipe, NovelAiHistoryFoldType.STYLE), key(changedRole.copy(basePrompt = "sea"), NovelAiHistoryFoldType.STYLE))
    }

    @Test fun rolesRemainOrderedAndFieldsCannotCollide() {
        val pair = recipe.copy(characters = recipe.characters + NovelAiCharacterPromptDraft(prompt = "boy"))
        assertNotEquals(key(pair, NovelAiHistoryFoldType.FULL), key(pair.copy(characters = pair.characters.reversed()), NovelAiHistoryFoldType.FULL))
        assertNotEquals(key(recipe.copy(basePrompt = "a\u0000b", negativePrompt = "c"), NovelAiHistoryFoldType.CONTENT),
            key(recipe.copy(basePrompt = "a", negativePrompt = "b\u0000c"), NovelAiHistoryFoldType.CONTENT))
    }

    @Test fun calendarGroupingUsesLocalDateAcrossYearBoundary() {
        val entry = NovelAiGenerationHistoryEntry(createdAt = 1767198600000L) // 2025-12-31 16:30 UTC
        val east = TimeZone.getTimeZone("GMT+08:00")
        assertEquals(listOf("2026", "1", "1"), NovelAiHistoryFolding.key(entry, NovelAiHistoryFoldType.DAY, east))
        assertEquals(listOf("2026", "1"), NovelAiHistoryFolding.key(entry, NovelAiHistoryFoldType.MONTH, east))
        assertEquals(listOf("2026"), NovelAiHistoryFolding.key(entry, NovelAiHistoryFoldType.YEAR, east))
        assertEquals(listOf("2025"), NovelAiHistoryFolding.key(entry, NovelAiHistoryFoldType.YEAR, TimeZone.getTimeZone("UTC")))
    }
}
