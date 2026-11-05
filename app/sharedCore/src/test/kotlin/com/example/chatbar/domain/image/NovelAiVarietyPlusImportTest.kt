package com.example.chatbar.domain.image

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class NovelAiVarietyPlusImportTest {
    private fun comment(model: String, sigma: String) = """{"model":"$model","prompt":"Original scene  ","uc":"Original negative  ","width":1024,"height":1024,"steps":28,"sampler":"k_euler_ancestral","seed":7,"skip_cfg_above_sigma":$sigma}"""
    private fun body(settings: NovelAiGenerationSettings, enhance: NovelAiEnhanceRequestOptions? = null) =
        Json.parseToJsonElement(NovelAiImageService().buildRequestBody(NovelAiPromptPlan("Original scene", emptyList()),
            settings.imageSize(), settings, imageGuidance = if (enhance == null) NovelAiPreparedImageGuidance.NONE else
                NovelAiPreparedImageGuidance(NovelAiGenerationAction.IMAGE_TO_IMAGE, imageBase64 = "synthetic"), enhance = enhance)).jsonObject.getValue("parameters").jsonObject

    @Test fun `V45 PNG import restores fixed variety enabled and disabled while respecting import selection`() {
        for ((sigma, expected) in listOf("58" to true, "null" to false)) {
            val metadata = assertNotNull(NovelAiPngMetadataReader.parseStudioComment(comment("nai-diffusion-4-5-full", sigma), "synthetic.png"))
            assertEquals(expected, metadata.settings.varietyPlus)
            val initial = NovelAiStudioDraft(v45Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V4_5_FULL, varietyPlus = !expected))
            val applied = initial.applyImportedMetadata(metadata, NovelAiStudioMetadataSelection())
            assertEquals(expected, applied.activeSettings.varietyPlus)
            if (expected) assertEquals(58.0, body(applied.activeSettings).getValue("skip_cfg_above_sigma").jsonPrimitive.double, 1e-9)
            else assertEquals(JsonNull, body(applied.activeSettings)["skip_cfg_above_sigma"])
            val skipped = initial.applyImportedMetadata(metadata, NovelAiStudioMetadataSelection(generationSettings = false, seed = false))
            assertEquals(initial.v45Settings, skipped.v45Settings)
            assertEquals("Original scene  ", applied.basePrompt)
        }
    }

    @Test fun `custom PNG sigma is normalized and retained while V5 never applies or replays V45 sigma`() {
        val unknown = assertNotNull(NovelAiPngMetadataReader.parseStudioComment(comment("nai-diffusion-4-5-full", "42"), "synthetic.png"))
        assertEquals(true, unknown.settings.varietyPlus)
        assertEquals(42.0 / NovelAiVarietyPlusPolicy.resolutionScale(1024, 1024), unknown.settings.varietyPlusSigma)
        val v5 = assertNotNull(NovelAiPngMetadataReader.parseStudioComment(comment("nai-diffusion-5-full", "58"), "synthetic.png"))
        assertNull(v5.settings.varietyPlus)
        val source = NovelAiPngMetadataReader.parseEnhanceComment(comment("nai-diffusion-5-full", "58"), "synthetic.png", null)
        assertFalse(body(source.settings, NovelAiEnhanceRequestOptions(false, source.parameters)).containsKey("skip_cfg_above_sigma"))
        val v45 = NovelAiPngMetadataReader.parseEnhanceComment(comment("nai-diffusion-4-5-full", "58"), "synthetic.png", null)
        assertTrue(v45.settings.varietyPlus)
        assertEquals(58.0 / NovelAiVarietyPlusPolicy.resolutionScale(1024, 1024) *
            NovelAiVarietyPlusPolicy.resolutionScale(v45.settings.imageSize().width, v45.settings.imageSize().height),
            body(v45.settings).getValue("skip_cfg_above_sigma").jsonPrimitive.double, 1e-9)
    }
}
