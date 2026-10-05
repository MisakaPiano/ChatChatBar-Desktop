package com.example.chatbar.domain.image

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NovelAiStudioCustomSizeTest {
    @Test
    fun customDimensionsRoundWithoutChangingTheEditableDraft() {
        val settings = NovelAiGenerationSettings(customWidth = 1000, customHeight = 750)
        assertEquals(1024, settings.imageSize().width)
        assertEquals(768, settings.imageSize().height)
        assertEquals(1000, settings.normalized().customWidth)
        assertNull(settings.validationError(0))
    }

    @Test
    fun validatesRoundedPixelBudgetAndPartialDimensions() {
        assertNull(NovelAiStudioSizePolicy.validationError(2048, 1536))
        assertNotNull(NovelAiStudioSizePolicy.validationError(2048, 1568))
        assertNotNull(NovelAiStudioSizePolicy.validationError(2049, 512))
        assertNotNull(NovelAiStudioSizePolicy.validationError(0, 512))
        assertNotNull(NovelAiGenerationSettings(customWidth = 1024).validationError(0))
        assertEquals(64, NovelAiStudioSizePolicy.resolve(Int.MIN_VALUE, 64).width)
        assertEquals(2048, NovelAiStudioSizePolicy.resolve(Int.MAX_VALUE, 64).width)
    }

    @Test
    fun oldSettingsDecodeAndCustomSettingsSurviveHistoryRoundTrip() {
        val old = Json.decodeFromString(NovelAiGenerationSettings.serializer(), """{"sizeTier":"NORMAL","aspectRatio":"LANDSCAPE"}""")
        assertFalse(old.usesCustomSize)
        assertEquals(1216, old.imageSize().width)
        val recipe = NovelAiGenerationRecipe(settings = old.copy(customWidth = 1000, customHeight = 750))
        val decoded = Json.decodeFromString(
            NovelAiGenerationRecipe.serializer(), Json.encodeToString(NovelAiGenerationRecipe.serializer(), recipe)
        )
        val restored = NovelAiStudioDraft().applyHistoryRecipe(decoded, 123L, NovelAiHistoryApplyMode.FULL)
        assertEquals(1000, restored.activeSettings.customWidth)
        assertEquals(1024, restored.activeSettings.imageSize().width)
        assertEquals(123L, restored.activeSettings.seed)
    }

    @Test
    fun pngSettingsImportRestoresCustomSizeAndPresetImportClearsIt() {
        val metadata = NovelAiStudioPngMetadata(
            imagePath = "inline.png", positivePrompt = "scene", width = 960, height = 1280,
            settings = NovelAiImportedGenerationSettings(customWidth = 960, customHeight = 1280)
        )
        val custom = NovelAiStudioDraft().applyImportedMetadata(metadata, NovelAiStudioMetadataSelection())
        assertEquals(960, custom.activeSettings.imageSize().width)
        val preset = custom.applyImportedMetadata(
            metadata.copy(settings = NovelAiImportedGenerationSettings(
                sizeTier = NovelAiSizeTier.NORMAL, aspectRatio = NovelAiAspectRatio.SQUARE
            )), NovelAiStudioMetadataSelection()
        )
        assertFalse(preset.activeSettings.usesCustomSize)
        assertEquals(1024, preset.activeSettings.imageSize().width)
    }
}
