package com.example.chatbar.domain.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CharacterNaiPromptDefaultsTest {
    @Test
    fun defaultHelperUsesTrimmedAuthoritativeConstant() {
        assertEquals(
            CharacterNaiPromptDefaults.DEFAULT_CHARACTER_NAI_NEGATIVE_PROMPT.trim(),
            CharacterNaiPromptDefaults.defaultCharacterNaiNegativePrompt(),
        )
    }

    @Test
    fun emptyValueUsesDefault() {
        assertEquals(
            CharacterNaiPromptDefaults.defaultCharacterNaiNegativePrompt(),
            CharacterNaiPromptDefaults.effectiveCharacterNaiNegativePrompt(""),
        )
    }

    @Test
    fun whitespaceValueUsesDefault() {
        assertEquals(
            CharacterNaiPromptDefaults.defaultCharacterNaiNegativePrompt(),
            CharacterNaiPromptDefaults.effectiveCharacterNaiNegativePrompt("  \n\t"),
        )
    }

    @Test
    fun customValueIsTrimmed() {
        assertEquals(
            "custom negative",
            CharacterNaiPromptDefaults.effectiveCharacterNaiNegativePrompt("  custom negative  "),
        )
    }

    @Test
    fun authoritativeConstantIsNotBlank() {
        assertTrue(CharacterNaiPromptDefaults.DEFAULT_CHARACTER_NAI_NEGATIVE_PROMPT.isNotBlank())
    }
}
