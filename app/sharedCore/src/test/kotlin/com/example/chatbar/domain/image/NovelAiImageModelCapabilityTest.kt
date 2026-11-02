package com.example.chatbar.domain.image

import kotlin.test.*

class NovelAiImageModelCapabilityTest {
    @Test fun `official model capabilities normalize effective settings without rewriting stored card choices`() {
        assertTrue(NovelAiImageModel.V4_5_FULL.supportsVarietyPlus)
        assertFalse(NovelAiImageModel.V5_FULL.supportsVarietyPlus)
        assertTrue(NovelAiSampler.DPM_PLUS_PLUS_2M_SDE in NovelAiImageModel.V5_FULL.samplers)
        assertFalse(NovelAiSampler.DPM_PLUS_PLUS_2M_SDE in NovelAiImageModel.V4_5_FULL.samplers)
        for (model in NovelAiImageModel.entries) for (sampler in NovelAiSampler.entries) {
            val stored = NovelAiCharacterImageSettings(sampler = sampler, steps = 23, guidance = 5f, cfgRescale = .2f, varietyPlus = true)
            val effective = stored.applyTo(NovelAiGenerationSettings.legacy(321, model = model))
            assertEquals(if (sampler in model.samplers) sampler else NovelAiSampler.EULER_ANCESTRAL, effective.sampler)
            assertTrue(effective.sampler in model.samplers)
            assertEquals(sampler, stored.sampler)
            assertEquals(23, effective.steps); assertEquals(5f, effective.guidance)
            assertEquals(.2f, effective.cfgRescale); assertEquals(321L, effective.seed)
            assertEquals(NovelAiSeedMode.FIXED, effective.seedMode)
            assertTrue(stored.varietyPlus)
        }
    }
}
