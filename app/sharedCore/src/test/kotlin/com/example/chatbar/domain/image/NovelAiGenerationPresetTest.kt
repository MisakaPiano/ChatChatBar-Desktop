package com.example.chatbar.domain.image

import kotlinx.serialization.json.Json
import kotlin.test.*

class NovelAiGenerationPresetTest {
    private val hash = "a".repeat(64)
    private val asset = NovelAiStudioAssetRef(id = "private-local-image-id", path = "images/private-local-image.png",
        sha256 = hash, width = 1024, height = 1024, containsPaint = false)
    private fun draft() = NovelAiStudioDraft(stylePrompt = "style\n【基础】", basePrompt = "base", extraPrompt = "extra",
        negativePrompt = "negative", naturalLanguageMode = true,
        characters = listOf(NovelAiCharacterPromptDraft(id = "private-local-person-id", prompt = "person", negativePrompt = "person-negative",
            center = DesignedCharacterCenter(0.2f, 0.8f)), NovelAiCharacterPromptDraft(prompt = "disabled-person", enabled = false)),
        importedCharacterCardId = "private-card-id", aiDesignModelId = "private-model-id", followDefaultNovelAiImageModel = true,
        v45Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V4_5_FULL, seedMode = NovelAiSeedMode.FIXED, seed = 123,
            customWidth = 832, customHeight = 1216, varietyPlus = true, useCharacterPositions = true),
        v5Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, steps = 16),
        imageGuidance = NovelAiImageGuidanceDraft(action = NovelAiGenerationAction.INPAINT, baseImage = asset,
            maskImage = asset.copy(containsPaint = true), focusedInpaintRegion = NovelAiFocusedInpaintRegion(0.1f, 0.1f, 0.6f, 0.6f),
            referenceMode = NovelAiReferenceMode.PRECISE, preciseReference = NovelAiPreciseReferenceDraft(asset, strength = 0.7f),
            vibes = listOf(NovelAiVibeReferenceDraft(id = "private-vibe-id", asset = asset, encodedVibe = "private-local-encoding"))))
    private fun capture(source: NovelAiStudioDraft) = NovelAiGenerationPreset.capture(source,
        { it.copy(id = it.sha256, path = "resources/${it.sha256}.image") }, { "resources/$hash.vibe" })

    @Test fun `full settings and inactive references survive transport without local editor identities or history loss`() {
        val source = draft(); val preset = capture(source)
        val encoded = Json.encodeToString(NovelAiGenerationPreset.serializer(), preset)
        listOf("private-card-id", "private-model-id", "private-local-person-id", "private-local-image", "private-vibe-id", "private-local-encoding")
            .forEach { assertFalse(encoded.contains(it)) }
        val restored = Json.decodeFromString(NovelAiGenerationPreset.serializer(), encoded)
        val local = restored.imageGuidance.mapPresetResources({ it.copy(path = "images/new.png", id = "new") }, { "retained-new-encoding" })
        val target = NovelAiStudioDraft(aiDesignModelId = "target-model", imageDescription = "target-input", continuousModeEnabled = true, continuousTargetCount = 100)
        val result = restored.applyTo(target, local)
        assertEquals(source.stylePrompt, result.stylePrompt); assertEquals(source.negativePrompt, result.negativePrompt)
        assertEquals(source.v45Settings, result.v45Settings); assertEquals(source.v5Settings, result.v5Settings)
        assertEquals(source.naturalLanguageMode, result.naturalLanguageMode)
        assertEquals(source.characters.map { it.enabled }, result.characters.map { it.enabled })
        assertEquals(source.characters.map { it.negativePrompt }, result.characters.map { it.negativePrompt })
        assertEquals(source.characters.map { it.center }, result.characters.map { it.center })
        assertEquals(NovelAiGenerationAction.INPAINT, result.imageGuidance.action); assertEquals(3 + source.imageGuidance.vibes.size, local.presetAssets().size)
        assertEquals("retained-new-encoding", result.imageGuidance.vibes.single().encodedVibe)
        assertEquals("target-model", result.aiDesignModelId); assertEquals("target-input", result.imageDescription)
        assertFalse(result.continuousModeEnabled); assertEquals(source.continuousTargetCount, result.continuousTargetCount)
        assertFalse(result.followDefaultNovelAiImageModel); assertNull(result.importedCharacterCardId)
        assertTrue(result.characters.first().id != source.characters.first().id)
    }

    @Test fun `v5 paused references and random seed remain intact rather than using history restoration`() {
        val source = draft().copy(selectedModel = NovelAiImageModel.V5_FULL,
            continuousModeEnabled = true, continuousTargetCount = 23,
            v5Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, seedMode = NovelAiSeedMode.RANDOM))
        val result = capture(source).applyTo(NovelAiStudioDraft(), source.imageGuidance)
        assertEquals(source.imageGuidance, result.imageGuidance)
        assertEquals(NovelAiSeedMode.RANDOM, result.activeSettings.seedMode)
        assertTrue(result.continuousModeEnabled); assertEquals(23, result.continuousTargetCount)
        assertEquals(NovelAiReferenceMode.NONE, result.imageGuidance.effectiveReferenceMode(result.selectedModel))
        assertEquals(NovelAiReferenceMode.PRECISE, result.imageGuidance.referenceMode)
    }

    @Test fun `future versions unsafe resources wrong model settings and invalid inactive parameters are refused`() {
        val valid = capture(draft())
        for (bad in listOf(valid.copy(version = 2), valid.copy(format = "other"), valid.copy(v5Settings = valid.v45Settings),
            valid.copy(imageGuidance = valid.imageGuidance.copy(baseImage = asset)),
            valid.copy(imageGuidance = valid.imageGuidance.copy(vibes = listOf(NovelAiVibeReferenceDraft(encodedVibe = "C:/private")))),
            valid.copy(selectedModel = NovelAiImageModel.V5_FULL, imageGuidance = valid.imageGuidance.copy(
                preciseReference = valid.imageGuidance.preciseReference.copy(strength = Float.NaN))))) {
            assertFails { bad.validate() }
        }
    }
}
