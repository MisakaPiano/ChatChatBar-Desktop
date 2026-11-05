package com.example.chatbar.domain.image

import kotlinx.serialization.json.*
import kotlin.test.*

class NovelAiCharacterV5DefaultsTest {
    @Test fun `legacy defaults omit new keys while explicit V5 settings round trip and drive actual request`() {
        val json = Json { encodeDefaults = true }
        val legacy = json.decodeFromString(NovelAiCharacterImageSettings.serializer(), """{"steps":31,"guidance":6.5,"cfgRescale":0.4}""")
        val legacyWire = json.encodeToJsonElement(NovelAiCharacterImageSettings.serializer(), legacy).jsonObject
        assertFalse("effort" in legacyWire); assertFalse("transparentBackground" in legacyWire)
        assertEquals(NovelAiEffort.HIGH, legacy.effort); assertFalse(legacy.transparentBackground)
        val selected = legacy.copy(sampler = NovelAiSampler.DPM_PLUS_PLUS_2M_SDE,
            effort = NovelAiEffort.MEDIUM, transparentBackground = true)
        assertEquals(selected, json.decodeFromString(NovelAiCharacterImageSettings.serializer(),
            json.encodeToString(NovelAiCharacterImageSettings.serializer(), selected)))
        val applied = selected.applyTo(NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL))
        val source = NovelAiPromptPlan(baseCaption = "synthetic scene", characterCaptions = emptyList(), negativePrompt = "synthetic custom")
        val body = Json.parseToJsonElement(NovelAiImageService().buildRequestBody(source, applied.imageSize(), applied)).jsonObject
        val p = body.getValue("parameters").jsonObject
        assertEquals("nai-diffusion-5-full-medium", body.getValue("model").jsonPrimitive.content)
        assertEquals(14, p.getValue("steps").jsonPrimitive.int)
        assertEquals("k_euler_ancestral", p.getValue("sampler").jsonPrimitive.content)
        assertEquals(0f, p.getValue("cfg_rescale").jsonPrimitive.float)
        assertTrue(p.getValue("tag_hint_transparent_background").jsonPrimitive.boolean)
        assertFalse(p.getValue("uc").jsonPrimitive.content.contains("synthetic custom"))
        assertEquals("synthetic scene", source.baseCaption)
        assertEquals(31, applied.steps); assertEquals(.4f, applied.cfgRescale)
        assertEquals(NovelAiSampler.DPM_PLUS_PLUS_2M_SDE, applied.sampler)
        val suspended = selected.applyTo(NovelAiGenerationSettings(model = NovelAiImageModel.V4_5_FULL))
        assertFalse(suspended.isMedium); assertFalse(suspended.effectiveTransparentBackground)
        assertEquals(31, suspended.effectiveSteps)
        val reset = selected.withDefaultSampling(NovelAiImageModel.V5_FULL)
        assertEquals(23, reset.steps); assertEquals(7f, reset.guidance); assertEquals(0f, reset.cfgRescale)
        assertEquals(NovelAiEffort.MEDIUM, reset.effort); assertTrue(reset.transparentBackground)
    }
}
