package com.example.chatbar.domain.image

import com.example.chatbar.domain.prompt.CharacterCardAiPromptAuthority
import com.example.chatbar.domain.prompt.CharacterNaiPromptDefaults
import kotlin.test.*

class CharacterAvatarPromptPlanTest {
    @Test fun `structured and manual avatar sources precede fixed positive and custom or default negative suffixes`() {
        for ((style, person, negative) in listOf(
            Triple(" style, ", " person, ", " custom-negative, "),
            Triple("style", "person", " "), Triple("", "manual-avatar", "manual-negative"),
        )) {
            val plan = CharacterAvatarImagePolicy.promptPlan(style, person, negative)
            val expected = listOf(style, person).map { it.trim().trim(',').trim() }.filter(String::isNotEmpty)
            assertEquals((expected + CharacterCardAiPromptAuthority.CHARACTER_AVATAR_NAI_POSITIVE_PROMPT).joinToString(", "), plan.baseCaption)
            assertEquals(CharacterNaiPromptDefaults.effectiveCharacterNaiNegativePrompt(negative).trim().trim(',').trim() + ", " +
                CharacterCardAiPromptAuthority.CHARACTER_AVATAR_NAI_NEGATIVE_PROMPT, plan.negativePrompt)
            assertTrue(plan.characterCaptions.isEmpty()); assertEquals(NovelAiImageSizePreset.SQUARE, plan.sizePreset)
            assertEquals(512, CharacterAvatarImagePolicy.imageSize.width); assertEquals(512, CharacterAvatarImagePolicy.imageSize.height)
        }
    }

    @Test fun `avatar negative helper normalizes separators and retains an explicitly selected suffix`() {
        assertEquals("original, selected", CharacterCardAiPromptAuthority.novelAiCharacterAvatarNegativePrompt(" original, ", " ,selected, "))
        assertEquals("original", CharacterCardAiPromptAuthority.novelAiCharacterAvatarNegativePrompt("original", " "))
        assertEquals(CharacterCardAiPromptAuthority.CHARACTER_AVATAR_NAI_POSITIVE_PROMPT,
            CharacterCardAiPromptAuthority.CHARACTER_AVATAR_NAI_COMPOSITION_TAGS)
    }
}
