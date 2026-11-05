package com.example.chatbar.domain.image

import kotlinx.serialization.json.*
import kotlin.test.*

class NovelAiGenerationDefaultsTest {
    @Test fun `explicit reset changes only selected model settings and keeps prompts roles and inactive settings`() {
        for (model in NovelAiImageModel.entries) {
            val role = NovelAiCharacterPromptDraft(id = "role", prompt = "Synthetic role", negativePrompt = "Synthetic negative")
            val draft = NovelAiStudioDraft(basePrompt = "Synthetic scene", negativePrompt = "Synthetic exclusions",
                selectedModel = model, characters = listOf(role), followDefaultNovelAiImageModel = true,
                v45Settings = NovelAiGenerationSettings(steps = 44, guidance = 8f),
                v5Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, steps = 45, guidance = 9f))
            val reset = draft.withActiveSettings(NovelAiGenerationDefaults.forModel(model))
            assertEquals(draft.basePrompt, reset.basePrompt); assertEquals(draft.negativePrompt, reset.negativePrompt)
            assertEquals(draft.characters, reset.characters); assertTrue(reset.followDefaultNovelAiImageModel)
            assertEquals(if (model == NovelAiImageModel.V5_FULL) draft.v45Settings else draft.v5Settings,
                if (model == NovelAiImageModel.V5_FULL) reset.v45Settings else reset.v5Settings)
            val settings = reset.activeSettings
            assertEquals(832, settings.imageSize().width); assertEquals(1216, settings.imageSize().height)
            assertEquals(NovelAiSeedMode.RANDOM, settings.seedMode); assertEquals(1, settings.count)
            val body = Json.parseToJsonElement(NovelAiImageService().buildRequestBody(reset.toPromptPlan(), settings.imageSize(), settings)).jsonObject
            val wire = body.getValue("parameters").jsonObject
            assertEquals("23", wire.getValue("steps").jsonPrimitive.content)
            assertEquals(if (model == NovelAiImageModel.V5_FULL) 7f else 5f, wire.getValue("scale").jsonPrimitive.float)
            assertEquals(NovelAiSampler.EULER_ANCESTRAL.apiId, wire.getValue("sampler").jsonPrimitive.content)
            assertTrue(NovelAiSampler.DDIM !in model.samplers)
        }
    }
}
