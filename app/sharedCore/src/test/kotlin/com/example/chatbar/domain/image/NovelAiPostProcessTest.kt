package com.example.chatbar.domain.image

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class NovelAiPostProcessTest {
    @Test
    fun upscaleChargesUseInputAreaAndRejectOversizeInsteadOfResizing() {
        listOf(1_048_576 to 1, 1_747_627 to 2, 2_446_678 to 3, 3_145_728 to 4).forEach { (pixels, cost) ->
            assertEquals(cost, NovelAiPostProcessPolicy.upscaleCost(pixels, 1))
            assertEquals(cost, NovelAiPostProcessPolicy.upscaleCost(pixels - 1, 1))
            assertEquals(if (cost == 4) null else cost + 1, NovelAiPostProcessPolicy.upscaleCost(pixels + 1, 1))
        }
        assertEquals(1, NovelAiPostProcessPolicy.upscaleCost(1024, 1024))
        assertEquals(4, NovelAiPostProcessPolicy.upscaleCost(2048, 1536))
        assertNull(NovelAiPostProcessPolicy.upscaleCost(Int.MAX_VALUE, Int.MAX_VALUE))
        assertNull(NovelAiPostProcessPolicy.upscaleCost(0, 1024))
    }

    @Test
    fun maxIsV5OnlyAndHasIndependentOutputGeometry() {
        assertFalse(NovelAiEnhanceScale.MAX in NovelAiPostProcessPolicy.scales(1024, 1024, NovelAiImageModel.V4_5_FULL))
        assertTrue(NovelAiEnhanceScale.MAX in NovelAiPostProcessPolicy.scales(1024, 1024, NovelAiImageModel.V5_FULL))
        assertFalse(NovelAiEnhanceScale.MAX in NovelAiPostProcessPolicy.scales(2048, 1536, NovelAiImageModel.V5_FULL))
        val request = NovelAiPostProcessPolicy.requestSize(1024, 1024, NovelAiEnhanceScale.MAX)
        val output = NovelAiPostProcessPolicy.outputSize(1024, 1024, NovelAiEnhanceScale.MAX)
        assertEquals(1024, request.width)
        assertEquals(1760, output.width)
        assertEquals(1760, output.height)
        val portrait = NovelAiPostProcessPolicy.maxOutputSize(832, 1216)
        assertEquals(0, portrait.width % 32)
        assertEquals(0, portrait.height % 32)
        assertTrue(portrait.width.toLong() * portrait.height <= NovelAiPostProcessPolicy.MAX_PIXELS)
        assertTrue(NovelAiEnhanceScale.HALF in NovelAiPostProcessPolicy.scales(832, 1216, NovelAiImageModel.V4_5_FULL))
        assertFalse(NovelAiEnhanceScale.DOUBLE in NovelAiPostProcessPolicy.scales(832, 1216, NovelAiImageModel.V4_5_FULL))
    }

    @Test
    fun enhanceCostsIncludeStrengthAndRequireKnownAllowanceBeforeClaimingFree() {
        val source = source()
        val low = NovelAiPostProcessPolicy.enhanceCost(source, 1024, 1024, NovelAiEnhanceOptions(strength = 0.2f), null)
        val high = NovelAiPostProcessPolicy.enhanceCost(source, 1024, 1024, NovelAiEnhanceOptions(strength = 0.7f), null)
        assertTrue(low.anlas < high.anlas)
        assertEquals(6, low.anlas)
        assertEquals(18, NovelAiPostProcessPolicy.enhanceCost(source, 1024, 1024, NovelAiEnhanceOptions(strength = 0.6f), null).anlas)
        val unknownAllowance = NovelAiAccountUsage(100, 3, true, null, false)
        assertEquals(NovelAiGenerationChargeKind.ANLAS,
            NovelAiPostProcessPolicy.enhanceCost(source, 1024, 1024, NovelAiEnhanceOptions(), unknownAllowance).kind)
        assertEquals(NovelAiGenerationChargeKind.V5_ALLOWANCE,
            NovelAiPostProcessPolicy.enhanceCost(source, 1024, 1024, NovelAiEnhanceOptions(), unknownAllowance.copy(v5AllowancePercent = 50.0)).kind)
    }

    @Test
    fun pngEnhanceRecognizesKnownHashesButNeverGuessesUnknownOrCuratedModels() {
        val comment = """{"prompt":"scene","uc":"","width":1024,"height":1024,"steps":28,"sampler":"k_euler_ancestral","seed":1}"""
        assertEquals(NovelAiImageModel.V5_FULL,
            NovelAiPngMetadataReader.parseEnhanceComment(comment, "inline.png", "NovelAI Diffusion V5 657484A5").settings.model)
        assertEquals(NovelAiImageModel.V4_5_FULL,
            NovelAiPngMetadataReader.parseEnhanceComment(comment, "inline.png", "NovelAI Diffusion V4.5 4BDE2A90").settings.model)
        listOf("NovelAI Diffusion V5 UNKNOWN", "nai-diffusion-5-curated", "nai-diffusion-4-full").forEach { model ->
            assertTrue(runCatching { NovelAiPngMetadataReader.parseEnhanceComment(comment, "inline.png", model) }.isFailure)
        }
    }

    @Test
    fun resolvedCaptionsKeepOriginalCharacterCoordinates() {
        val comment = """{
          "width":1024,"height":1024,"model":"nai-diffusion-5-full",
          "v4_prompt":{"use_coords":true,"caption":{"base_caption":"random choice","char_captions":[{"char_caption":"random role","centers":[{"x":0.2,"y":0.7}]}]}},
          "actual_prompts":{"prompt":{"base_caption":"chosen scene","char_captions":[{"char_caption":"chosen role"}]}}
        }"""
        val source = NovelAiPngMetadataReader.parseEnhanceComment(comment, "inline.png", null)
        assertEquals("chosen scene", source.prompt.baseCaption)
        assertEquals("chosen role", source.prompt.characterCaptions.single().prompt)
        assertEquals(DesignedCharacterCenter(0.2f, 0.7f), source.prompt.characterCaptions.single().center)
    }

    @Test
    fun maxSerializesOneImg2imgRequestAndLeavesNormalGenerationUnchanged() {
        val service = NovelAiImageService(OkHttpClient())
        val prompt = NovelAiPromptPlan("literal，caption", emptyList(), negativePrompt = "")
        val settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, seed = 0)
        val size = NovelAiImageSize(1024, 1024, "fixture")
        val body = Json.parseToJsonElement(service.buildRequestBody(prompt, size, settings,
            NovelAiPreparedImageGuidance(NovelAiGenerationAction.IMAGE_TO_IMAGE, imageBase64 = "inline-image"),
            NovelAiEnhanceRequestOptions(true, JsonObject(emptyMap())))).jsonObject
        val params = body.getValue("parameters").jsonObject
        assertEquals("img2img", body.getValue("action").jsonPrimitive.content)
        assertEquals("literal，caption", body.getValue("input").jsonPrimitive.content)
        assertTrue(params.getValue("upscaled_enhance").jsonPrimitive.boolean)
        assertEquals(1L, params.getValue("n_samples").jsonPrimitive.long)
        assertEquals(4_294_967_295L, params.getValue("extra_noise_seed").jsonPrimitive.long)
        assertEquals("", params.getValue("negative_prompt").jsonPrimitive.content)
        val ordinary = Json.parseToJsonElement(service.buildRequestBody(prompt, size, settings)).jsonObject.getValue("parameters").jsonObject
        assertFalse(ordinary.containsKey("upscaled_enhance"))
        assertFalse(ordinary.containsKey("color_correct"))
    }

    @Test
    fun standaloneUpscaleUsesCurrentContractAndHasNoGenerationOptions() {
        val body = Json.parseToJsonElement(NovelAiUpscaleService(OkHttpClient()).buildRequestBody("inline-image")).jsonObject
        assertEquals("nai-diffusion-5-curated", body.getValue("model").jsonPrimitive.content)
        assertEquals(0L, body.getValue("declared_blur_sigma").jsonPrimitive.long)
        assertEquals(setOf("image", "model", "declared_blur_sigma"), body.keys)
    }

    private fun source() = NovelAiEnhanceSource(
        NovelAiPromptPlan("scene", emptyList()), NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL), JsonObject(emptyMap())
    )
}
