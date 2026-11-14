package com.example.chatbar.domain.budget

import com.example.chatbar.data.local.entity.*
import org.junit.Assert.*
import org.junit.Test

class EditorPromptFieldEstimatesTest {
    @Test fun heuristicMatchesDesktopAndNeverUsesProvider() {
        assertEquals(0L, EditorPromptFieldEstimates.tokens(""))
        assertEquals(3L, EditorPromptFieldEstimates.tokens("abcde你好"))
    }
    @Test fun characterCandidatesExcludeMetadataImageOnlyAndDisabledPeople() {
        val card = CharacterCard.create("metadata", characters = listOf(
            CharacterInfo(id="a",name="Alice",profile="visible",imagePrompt="image only"),
            CharacterInfo(id="b",name="Bob",profile="disabled",enabled=false)))
            .copy(creatorNotes="notes",greeting="greeting",defaultImagePrompt="image only", basicSetting="world")
        val report = EditorPromptFieldEstimates.character(card)
        assertTrue(report.any { it.id == "basic" })
        assertTrue(report.any { it.id.contains("person:a") })
        assertFalse(report.any { it.id.contains("person:b") || it.id in setOf("notes","greeting","name","image") })
        assertEquals(report, EditorPromptFieldEstimates.character(card.copy(creatorNotes="changed notes",defaultImagePrompt="changed image only")))
    }
    @Test fun freeformDoesNotDoubleCountStructuredRoster() {
        val card = CharacterCard.create("card",characters=listOf(CharacterInfo(id="a",name="Alice",profile="roster")))
            .copy(editMode=CharacterEditMode.FREEFORM,freeformCharacterText="freeform")
        val report=EditorPromptFieldEstimates.character(card)
        assertEquals(listOf("freeform"),report.map { it.id })
    }
}
