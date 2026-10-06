package com.example.chatbar.domain.prompt

import com.example.chatbar.domain.image.NovelAiImageModel
import org.junit.Assert.assertEquals
import org.junit.Test

class NovelAiPromptFacadeTest {
    @Test fun `Android facade exposes the one shared NovelAI literal authority`() {
        listOf("NOVELAI_TAG_SEARCH_PLANNER_SYSTEM", "NOVELAI_TAG_REVISION_QUERY_PLANNER_SYSTEM",
            "NOVELAI_IMAGE_PROMPT_SYSTEM", "NOVELAI_IMAGE_NATURAL_LANGUAGE_PROMPT_SYSTEM_V5",
            "NOVELAI_IMAGE_PROMPT_REPAIR_SYSTEM", "NOVELAI_IMAGE_NATURAL_LANGUAGE_PROMPT_REPAIR_SYSTEM_V5").forEach { symbol ->
            assertEquals(NovelAiPromptAuthority::class.java.getField(symbol).get(null),
                PromptTemplates::class.java.getField(symbol).get(null))
        }
        assertEquals(NovelAiPromptAuthority.NOVELAI_IMAGE_PROMPT_SYSTEM_V5, PromptTemplates.NOVELAI_IMAGE_PROMPT_SYSTEM_V5)
    }

    @Test fun `version routing and user modifiers delegate without text or parameter drift`() {
        NovelAiImageModel.entries.forEach { model ->
            assertEquals(NovelAiPromptAuthority.novelAiImagePromptCoreSystem("player", "character", model),
                PromptTemplates.novelAiImagePromptCoreSystem("player", "character", model))
        }
        assertEquals(NovelAiPromptAuthority.novelAiImageNaturalLanguagePromptCoreSystem("player", "character"),
            PromptTemplates.novelAiImageNaturalLanguagePromptCoreSystem("player", "character"))
        assertEquals(NovelAiPromptAuthority.novelAiRevisionWithCharacterReference("change", "reference"),
            PromptTemplates.novelAiRevisionWithCharacterReference("change", "reference"))
        assertEquals(NovelAiPromptAuthority.novelAiImagePromptMoment("scene", "requirement", "player", "character"),
            PromptTemplates.novelAiImagePromptMoment("scene", "requirement", "player", "character"))
    }
}
