package com.example.chatbar.domain.image

import kotlinx.serialization.json.*
import kotlin.test.*

class NovelAiV5GenerationPolicyTest {
    private val high = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, steps = 37,
        sampler = NovelAiSampler.DPM_PLUS_PLUS_2M, cfgRescale = .4f, guidance = 5.5f,
        seedMode = NovelAiSeedMode.FIXED, seed = 17)
    private val plan = NovelAiPromptPlan(baseCaption = "synthetic scene", negativePrompt = "custom base",
        characterCaptions = listOf(NovelAiCharacterCaption("synthetic person", DesignedCharacterCenter(.3f, .6f), "custom role")))
    private fun body(settings: NovelAiGenerationSettings, prompt: NovelAiPromptPlan = plan,
        guidance: NovelAiPreparedImageGuidance = NovelAiPreparedImageGuidance.NONE,
        enhance: NovelAiEnhanceRequestOptions? = null) = Json.parseToJsonElement(NovelAiImageService().buildRequestBody(
            prompt, settings.imageSize(), settings, guidance, enhance)).jsonObject

    @Test fun `Medium enforces actual sampling model and UC without destroying High preferences`() {
        val medium = high.copy(effort = NovelAiEffort.MEDIUM)
        val wire = body(medium); val p = wire.getValue("parameters").jsonObject
        assertEquals("nai-diffusion-5-full-medium", wire.getValue("model").jsonPrimitive.content)
        assertEquals(14, p.getValue("steps").jsonPrimitive.int)
        assertEquals("k_euler_ancestral", p.getValue("sampler").jsonPrimitive.content)
        assertEquals(0f, p.getValue("cfg_rescale").jsonPrimitive.float)
        assertEquals(5.5f, p.getValue("scale").jsonPrimitive.float)
        assertEquals(2, p.getValue("tag_hint_uc_preset").jsonPrimitive.int)
        assertFalse("ucPreset" in p)
        assertFalse(p.toString().contains("custom"))
        val negative = p.getValue("v4_negative_prompt").jsonObject.getValue("caption").jsonObject
        assertEquals(p.getValue("uc"), negative.getValue("base_caption"))
        assertEquals("", negative.getValue("char_captions").jsonArray.single().jsonObject.getValue("char_caption").jsonPrimitive.content)
        assertEquals(37, medium.steps); assertEquals(.4f, medium.cfgRescale)
        assertEquals(high, medium.copy(effort = NovelAiEffort.HIGH))
        val inpaint = body(medium, guidance = NovelAiPreparedImageGuidance.NONE.copy(action = NovelAiGenerationAction.INPAINT,
            imageBase64 = "AA==", maskBase64 = "AA=="))
        assertEquals("nai-diffusion-5-full-medium-inpainting", inpaint.getValue("model").jsonPrimitive.content)
        assertFalse(body(medium, plan.copy(baseCaption = "NSFW synthetic")).getValue("parameters").jsonObject
            .getValue("uc").jsonPrimitive.content.startsWith("nsfw, "))
    }

    @Test fun `default High retains old wire and V45 suspends V5 options`() {
        val original = body(high); val p = original.getValue("parameters").jsonObject
        assertEquals(3, p.getValue("params_version").jsonPrimitive.int)
        assertEquals(3, p.getValue("ucPreset").jsonPrimitive.int)
        assertFalse("uc" in p); assertFalse("tag_hint_transparent_background" in p)
        assertEquals("custom base", p.getValue("negative_prompt").jsonPrimitive.content)
        val v45 = high.copy(model = NovelAiImageModel.V4_5_FULL, effort = NovelAiEffort.MEDIUM, transparentBackground = true)
        assertFalse(v45.isMedium); assertFalse(v45.effectiveTransparentBackground)
        assertEquals(body(v45.copy(effort = NovelAiEffort.HIGH, transparentBackground = false)), body(v45))
        assertEquals(original, body(high.copy(effort = NovelAiEffort.MEDIUM).copy(effort = NovelAiEffort.HIGH)))
    }

    @Test fun `transparency inserts only into first chunk before text and never mutates original`() {
        val source = plan.copy(baseCaption = "scene ||red|blue||, text: synthetic | later chunk")
        val wire = body(high.copy(transparentBackground = true), source)
        val positive = wire.getValue("input").jsonPrimitive.content
        assertTrue(positive.contains("transparent background, text:"))
        assertTrue(positive.endsWith("| later chunk"))
        assertEquals(1, Regex("transparent background").findAll(positive).count())
        assertEquals("scene ||red|blue||, text: synthetic | later chunk", source.baseCaption)
        val p = wire.getValue("parameters").jsonObject
        assertTrue(p.getValue("tag_hint_transparent_background").jsonPrimitive.boolean)
        assertEquals(4, p.getValue("params_version").jsonPrimitive.int)
        assertEquals("custom base", p.getValue("uc").jsonPrimitive.content)
        assertTrue(body(high.copy(transparentBackground = true), plan.copy(baseCaption = "text: synthetic"))
            .getValue("input").jsonPrimitive.content.startsWith("transparent background, text:"))
    }

    @Test fun `draft recipe and complete preset preserve dormant High choices and new options`() {
        val draft = NovelAiStudioDraft(basePrompt = plan.baseCaption, negativePrompt = plan.negativePrompt,
            selectedModel = NovelAiImageModel.V5_FULL, v5Settings = high.copy(effort = NovelAiEffort.MEDIUM, transparentBackground = true))
        val json = Json { encodeDefaults = true }
        val restored = json.decodeFromString(NovelAiStudioDraft.serializer(), json.encodeToString(NovelAiStudioDraft.serializer(), draft))
        assertEquals(draft, restored)
        val recipe = draft.toRecipe(draft.activeSettings)
        assertEquals(recipe, json.decodeFromString(NovelAiGenerationRecipe.serializer(), json.encodeToString(NovelAiGenerationRecipe.serializer(), recipe)))
        val preset = NovelAiGenerationPreset.capture(draft, { it }, { it })
        val applied = json.decodeFromString(NovelAiGenerationPreset.serializer(), json.encodeToString(NovelAiGenerationPreset.serializer(), preset))
            .applyTo(NovelAiStudioDraft(), preset.imageGuidance)
        assertEquals(draft.v5Settings, applied.v5Settings)
        val legacy = json.encodeToString(NovelAiGenerationSettings.serializer(), high)
        assertFalse(legacy.contains("effort")); assertFalse(legacy.contains("transparentBackground"))
        assertEquals(high, json.decodeFromString(NovelAiGenerationSettings.serializer(), legacy))
    }

    @Test fun `PNG import resolves exact Medium source and Enhance cannot replay custom role UC`() {
        val comment = """{"prompt":"synthetic scene","uc":"custom old","width":832,"height":1216,"steps":14,"scale":5.5,"sampler":"k_euler_ancestral","seed":17,"tag_hint_transparent_background":true,"v4_negative_prompt":{"caption":{"base_caption":"custom old","char_captions":[{"char_caption":"custom role"}]}}}"""
        for (source in listOf("nai-diffusion-5-full-medium", "NovelAI Diffusion V5 93F4BD30", "NovelAI Diffusion V5 70AB5786")) {
            val imported = assertNotNull(NovelAiPngMetadataReader.parseStudioComment(comment, "synthetic.png", source))
            assertEquals(NovelAiEffort.MEDIUM, imported.settings.effort)
            assertEquals(true, imported.settings.transparentBackground)
            val draft = NovelAiStudioDraft().applyImportedMetadata(imported, NovelAiStudioMetadataSelection())
            assertTrue(draft.activeSettings.isMedium); assertTrue(draft.activeSettings.effectiveTransparentBackground)
            val enhance = NovelAiPngMetadataReader.parseEnhanceComment(comment, "synthetic.png", source)
            val p = body(enhance.settings, enhance.prompt,
                NovelAiPreparedImageGuidance.NONE.copy(action = NovelAiGenerationAction.IMAGE_TO_IMAGE, imageBase64 = "AA=="),
                NovelAiEnhanceRequestOptions(false, enhance.parameters)).getValue("parameters").jsonObject
            assertEquals(14, p.getValue("steps").jsonPrimitive.int)
            assertFalse(p.getValue("v4_negative_prompt").toString().contains("custom"))
        }
        assertFails { NovelAiPngMetadataReader.parseEnhanceComment(comment, "synthetic.png", "NovelAI Diffusion V5 unknown") }
    }
}
