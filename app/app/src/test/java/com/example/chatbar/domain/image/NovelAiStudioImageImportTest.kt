package com.example.chatbar.domain.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAiStudioImageImportTest {
    private fun rolesOnly(mode: NovelAiCharacterImportMode) = NovelAiStudioMetadataSelection(
        positivePrompt = false, negativePrompt = false, characterPrompts = mode,
        generationSettings = false, seed = false, imageGuidance = false
    )

    @Test
    fun `append preserves existing role identity content disabled state and ordering`() {
        val existing = NovelAiCharacterPromptDraft(
            id = "old", prompt = "existing", negativePrompt = "old negative", enabled = false,
            negativeExpanded = true, center = DesignedCharacterCenter(0.3f, 0.7f)
        )
        val draft = NovelAiStudioDraft(basePrompt = "keep base", characters = listOf(existing))
        val source = metadata.copy(characters = listOf(
            NovelAiImportedCharacterPrompt("alice", "bad alice", DesignedCharacterCenter(0.9f, 0.1f)),
            NovelAiImportedCharacterPrompt("bob", "bad bob")
        ))
        val result = draft.applyImportedMetadata(source, rolesOnly(NovelAiCharacterImportMode.APPEND))
        assertEquals(existing, result.characters.first())
        assertEquals(listOf("existing", "alice", "bob"), result.characters.map { it.prompt })
        assertEquals(listOf("bad alice", "bad bob"), result.characters.drop(1).map { it.negativePrompt })
        assertEquals(source.characters.first().center, result.characters[1].center)
        assertTrue(result.characters.drop(1).all { it.enabled })
        assertEquals(3, result.characters.map { it.id }.distinct().size)
        assertEquals(draft, result.copy(characters = draft.characters))
        val again = result.applyImportedMetadata(source, rolesOnly(NovelAiCharacterImportMode.APPEND))
        assertEquals(result.characters, again.characters.take(3))
        assertEquals(5, again.characters.map { it.id }.distinct().size)
    }

    @Test
    fun `off preserves roles and replace retains previous overwrite behavior`() {
        val draft = NovelAiStudioDraft(characters = listOf(NovelAiCharacterPromptDraft(prompt = "old")))
        assertEquals(draft, draft.applyImportedMetadata(metadata, rolesOnly(NovelAiCharacterImportMode.OFF)))
        val replaced = draft.applyImportedMetadata(metadata, rolesOnly(NovelAiCharacterImportMode.REPLACE))
        assertEquals(listOf("alice", "bob"), replaced.characters.map { it.prompt })
        assertTrue(replaced.characters.none { it.id == draft.characters.first().id })
    }

    @Test
    fun `missing roles never erase existing roles and append empty list is a no-op`() {
        val draft = NovelAiStudioDraft(
            characters = listOf(NovelAiCharacterPromptDraft(prompt = "old")),
            conversionSnapshot = NovelAiPositivePromptSnapshot("snapshot")
        )
        val absent = metadata.copy(hasCharacterPrompts = false, characters = emptyList())
        for (mode in NovelAiCharacterImportMode.entries) {
            assertEquals(draft, draft.applyImportedMetadata(absent, rolesOnly(mode)))
        }
        val empty = absent.copy(hasCharacterPrompts = true)
        assertEquals(draft, draft.applyImportedMetadata(empty, rolesOnly(NovelAiCharacterImportMode.APPEND)))
        assertTrue(draft.applyImportedMetadata(empty, rolesOnly(NovelAiCharacterImportMode.REPLACE)).characters.isEmpty())
    }

    private val metadata = NovelAiStudioPngMetadata(
        imagePath = "/tmp/import.png",
        positivePrompt = "imported positive",
        negativePrompt = "imported negative",
        characters = listOf(
            NovelAiImportedCharacterPrompt("alice", "bad alice"),
            NovelAiImportedCharacterPrompt("bob", "bad bob")
        ),
        hasCharacterPrompts = true,
        settings = NovelAiImportedGenerationSettings(
            model = NovelAiImageModel.V5_FULL,
            sizeTier = NovelAiSizeTier.LARGE,
            aspectRatio = NovelAiAspectRatio.LANDSCAPE,
            count = 2,
            steps = 35,
            guidance = 5.5f,
            cfgRescale = 0.4f,
            sampler = NovelAiSampler.DPM_PLUS_PLUS_SDE
        ),
        seed = 987654321L,
        width = 1536,
        height = 1024
    )

    @Test
    fun `applies selected metadata sections and preserves studio-only fields`() {
        val draft = NovelAiStudioDraft(
            stylePrompt = "keep style",
            basePrompt = "old positive",
            characters = listOf(NovelAiCharacterPromptDraft(prompt = "old character", negativePrompt = "old negative")),
            negativePrompt = "keep negative",
            naturalLanguageMode = true,
            conversionSnapshot = NovelAiPositivePromptSnapshot("snapshot")
        )

        val result = draft.applyImportedMetadata(
            metadata,
            NovelAiStudioMetadataSelection(negativePrompt = false)
        )

        assertEquals("keep style", result.stylePrompt)
        assertEquals(true, result.naturalLanguageMode)
        assertEquals("imported positive", result.basePrompt)
        assertEquals("keep negative", result.negativePrompt)
        assertEquals(listOf("alice", "bob"), result.characters.map { it.prompt })
        assertEquals(listOf("bad alice", "bad bob"), result.characters.map { it.negativePrompt })
        assertEquals(NovelAiImageModel.V5_FULL, result.selectedModel)
        assertEquals(NovelAiSizeTier.LARGE, result.activeSettings.sizeTier)
        assertEquals(NovelAiAspectRatio.LANDSCAPE, result.activeSettings.aspectRatio)
        assertEquals(2, result.activeSettings.count)
        assertEquals(35, result.activeSettings.steps)
        assertEquals(5.5f, result.activeSettings.guidance)
        assertEquals(0.4f, result.activeSettings.cfgRescale)
        assertEquals(NovelAiSampler.DPM_PLUS_PLUS_SDE, result.activeSettings.sampler)
        assertEquals(NovelAiSeedMode.FIXED, result.activeSettings.seedMode)
        assertEquals(987654321L, result.activeSettings.seed)
        assertNull(result.conversionSnapshot)
    }

    @Test
    fun `unchecked sections do not change draft`() {
        val draft = NovelAiStudioDraft(
            stylePrompt = "style",
            basePrompt = "base",
            characters = listOf(NovelAiCharacterPromptDraft(prompt = "character", negativePrompt = "negative")),
            negativePrompt = "base negative"
        )

        val result = draft.applyImportedMetadata(
            metadata,
            NovelAiStudioMetadataSelection(
                positivePrompt = false,
                negativePrompt = false,
                characterPrompts = NovelAiCharacterImportMode.OFF,
                generationSettings = false,
                seed = false
            )
        )

        assertEquals(draft, result)
    }
}
