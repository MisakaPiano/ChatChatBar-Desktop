package com.example.chatbar.domain.image

import kotlinx.serialization.json.*
import kotlin.test.*

class NovelAiEffectiveSamplerAdmissionTest {
    private val prompt = NovelAiPromptPlan("synthetic scene", emptyList(), negativePrompt = "synthetic negative")
    private fun body(settings: NovelAiGenerationSettings) = Json.parseToJsonElement(
        NovelAiImageService().buildRequestBody(prompt, settings.imageSize(), settings)).jsonObject

    @Test fun `legacy DDIM stays decodable but unsupported High never reaches final serialization`() {
        for (model in NovelAiImageModel.entries) {
            val stored = NovelAiGenerationSettings(model = model, sampler = NovelAiSampler.DDIM, steps = 31, cfgRescale = .4f)
            val json = Json { encodeDefaults = true }
            assertEquals(stored, json.decodeFromString(NovelAiGenerationSettings.serializer(), json.encodeToString(NovelAiGenerationSettings.serializer(), stored)))
            assertNull(stored.validationError(0))
            val draft = if (model == NovelAiImageModel.V4_5_FULL)
                NovelAiStudioDraft(selectedModel = model, v45Settings = stored)
            else NovelAiStudioDraft(selectedModel = model, v5Settings = stored)
            val preset = NovelAiGenerationPreset.capture(draft, { it }, { it })
            val restored = json.decodeFromString(NovelAiGenerationPreset.serializer(),
                json.encodeToString(NovelAiGenerationPreset.serializer(), preset))
            restored.validate()
            val reopened = restored.applyTo(NovelAiStudioDraft(), restored.imageGuidance)
            assertEquals(stored, if (model == NovelAiImageModel.V4_5_FULL) reopened.v45Settings else reopened.v5Settings)
            assertEquals(NovelAiSampler.EULER_ANCESTRAL, reopened.activeSettings.sampler)
            assertNotNull(stored.requestValidationError(0))
            assertFailsWith<IllegalStateException> { body(stored) }
            assertEquals(NovelAiSampler.DDIM, stored.sampler)
        }
    }

    @Test fun `six current High samplers still produce their actual wire identifiers`() {
        for (model in NovelAiImageModel.entries) {
            assertEquals(6, model.samplers.size)
            for (sampler in model.samplers) {
                val settings = NovelAiGenerationSettings(model = model, sampler = sampler)
                assertNull(settings.requestValidationError(0))
                assertEquals(sampler.apiId, body(settings).getValue("parameters").jsonObject.getValue("sampler").jsonPrimitive.content)
            }
        }
    }

    @Test fun `Medium admits effective EA without changing dormant unsupported High and card normalization remains separate`() {
        val stored = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, sampler = NovelAiSampler.DDIM,
            steps = 31, cfgRescale = .4f, effort = NovelAiEffort.MEDIUM)
        assertNull(stored.requestValidationError(0))
        assertEquals("k_euler_ancestral", body(stored).getValue("parameters").jsonObject.getValue("sampler").jsonPrimitive.content)
        assertEquals(NovelAiSampler.DDIM, stored.sampler); assertEquals(31, stored.steps); assertEquals(.4f, stored.cfgRescale)
        assertNotNull(stored.copy(effort = NovelAiEffort.HIGH).requestValidationError(0))
        val card = NovelAiCharacterImageSettings(sampler = NovelAiSampler.DDIM)
        val applied = card.applyTo(NovelAiGenerationSettings(model = NovelAiImageModel.V4_5_FULL))
        assertEquals(NovelAiSampler.EULER_ANCESTRAL, applied.sampler)
        assertEquals(NovelAiSampler.DDIM, card.sampler)
    }
}
