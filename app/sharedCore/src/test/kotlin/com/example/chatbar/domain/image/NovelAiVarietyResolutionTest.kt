package com.example.chatbar.domain.image

import kotlinx.serialization.json.*
import kotlin.math.sqrt
import kotlin.test.*

class NovelAiVarietyResolutionTest {
    private val source = """{"model":"nai-diffusion-4-5-full","prompt":"Original scene  ","uc":"Original negative  ","steps":28,"sampler":"k_euler_ancestral","seed":7,"width":512,"height":768,"skip_cfg_above_sigma":37}"""
    private fun wire(settings: NovelAiGenerationSettings, width: Int, height: Int, enhance: NovelAiEnhanceRequestOptions? = null): JsonObject =
        Json.parseToJsonElement(NovelAiImageService().buildRequestBody(NovelAiPromptPlan("Original scene  ", emptyList()),
            NovelAiImageSize(width, height, "Synthetic"), settings,
            if (enhance == null) NovelAiPreparedImageGuidance.NONE else NovelAiPreparedImageGuidance(NovelAiGenerationAction.IMAGE_TO_IMAGE, imageBase64 = "synthetic"),
            enhance)).jsonObject.getValue("parameters").jsonObject

    @Test fun `actual request scales nominal VPlus by final latent canvas and never emits it for disabled or V5`() {
        val settings = NovelAiGenerationSettings(varietyPlus = true)
        for ((width, height) in listOf(832 to 1216, 512 to 768, 1024 to 1024)) {
            val expected = 58.0 * sqrt((width / 8).toDouble() * (height / 8) / (104 * 152))
            assertEquals(expected, wire(settings, width, height).getValue("skip_cfg_above_sigma").jsonPrimitive.double, 1e-9)
            assertEquals(JsonNull, wire(settings.copy(varietyPlus = false), width, height)["skip_cfg_above_sigma"])
            assertFalse("skip_cfg_above_sigma" in wire(settings.copy(model = NovelAiImageModel.V5_FULL), width, height))
        }
        assertEquals(NovelAiVarietyPlusPolicy.resolutionScale(512, 768), NovelAiVarietyPlusPolicy.resolutionScale(519, 775))
    }

    @Test fun `custom PNG sigma survives ordinary import JSON reopen preset and history and Enhance scales once for new canvas`() {
        val metadata = assertNotNull(NovelAiPngMetadataReader.parseStudioComment(source, "synthetic.png"))
        val normalized = 37.0 / sqrt(64.0 * 96 / (104 * 152))
        assertEquals(normalized, metadata.settings.varietyPlusSigma!!, 1e-9)
        val draft = NovelAiStudioDraft().applyImportedMetadata(metadata, NovelAiStudioMetadataSelection())
        val reopened = Json.decodeFromString(NovelAiStudioDraft.serializer(), Json.encodeToString(NovelAiStudioDraft.serializer(), draft))
        assertEquals(normalized, reopened.activeSettings.varietyPlusSigma!!, 1e-9)
        assertEquals(37.0, wire(reopened.activeSettings, 512, 768).getValue("skip_cfg_above_sigma").jsonPrimitive.double, 1e-9)
        val recipe = reopened.toRecipe()
        val historical = NovelAiStudioDraft().applyHistoryRecipe(recipe, 11, NovelAiHistoryApplyMode.FULL)
        val preset = NovelAiGenerationPreset.capture(reopened, { it }, { it })
        val transported = Json.decodeFromString(NovelAiGenerationPreset.serializer(), Json.encodeToString(NovelAiGenerationPreset.serializer(), preset))
            .applyTo(NovelAiStudioDraft(), NovelAiImageGuidanceDraft())
        assertEquals(historical.activeSettings.varietyPlusSigma, transported.activeSettings.varietyPlusSigma)
        assertEquals("Original scene  ", transported.basePrompt)
        val enhance = NovelAiPngMetadataReader.parseEnhanceComment(source, "synthetic.png", null)
        assertEquals(JsonPrimitive(37), enhance.parameters["skip_cfg_above_sigma"])
        val expected = normalized * sqrt(128.0 * 128 / (104 * 152))
        assertEquals(expected, wire(enhance.settings, 1024, 1024, NovelAiEnhanceRequestOptions(false, enhance.parameters))
            .getValue("skip_cfg_above_sigma").jsonPrimitive.double, 1e-9)
        assertNull(NovelAiCharacterImageSettings(varietyPlus = true).applyTo(reopened.activeSettings).varietyPlusSigma)
    }
}
