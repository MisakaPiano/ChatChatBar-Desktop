package com.example.chatbar.domain.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.chatbar.domain.prompt.CharacterCardAiPromptAuthority

class CharacterAvatarImagePolicyTest {
    @Test
    fun `avatar plan directly combines style character and fixed composition`() {
        val plan = CharacterAvatarImagePolicy.promptPlan(
            stylePrompt = "very aesthetic, anime screencap,",
            characterPrompt = "1girl, silver hair, blue-gray eyes",
            negativePrompt = "lowres"
        )

        assertEquals(
            "very aesthetic, anime screencap, 1girl, silver hair, blue-gray eyes, ${CharacterCardAiPromptAuthority.CHARACTER_AVATAR_NAI_POSITIVE_PROMPT}",
            plan.baseCaption
        )
        assertTrue(plan.characterCaptions.isEmpty())
        assertEquals(NovelAiImageSizePreset.SQUARE, plan.sizePreset)
        assertEquals("lowres, ${CharacterCardAiPromptAuthority.CHARACTER_AVATAR_NAI_NEGATIVE_PROMPT}", plan.negativePrompt)
    }

    @Test
    fun `avatar size is fixed small square`() {
        assertEquals(512, CharacterAvatarImagePolicy.imageSize.width)
        assertEquals(512, CharacterAvatarImagePolicy.imageSize.height)
        assertEquals("Small Square", CharacterAvatarImagePolicy.imageSize.label)
    }
}
