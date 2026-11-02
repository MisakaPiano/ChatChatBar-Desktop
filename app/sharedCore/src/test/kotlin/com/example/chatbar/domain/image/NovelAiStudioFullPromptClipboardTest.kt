package com.example.chatbar.domain.image

import kotlin.test.*
import kotlinx.serialization.json.*

class NovelAiStudioFullPromptClipboardTest {
    private fun source() = NovelAiStudioDraft(stylePrompt = "style\n【配置】\n\\literal\n",
        basePrompt = "base\n【角色 1 负向】\n\n", extraPrompt = "", negativePrompt = "",
        naturalLanguageMode = true, copyPositivePromptIgnoreStyle = true,
        characters = listOf(NovelAiCharacterPromptDraft(prompt = "first\n【任意标题】", negativePrompt = "bad first",
            center = DesignedCharacterCenter(.2f, .8f)), NovelAiCharacterPromptDraft(prompt = "disabled", negativePrompt = "", enabled = false)))

    @Test fun `complete readable prompt preserves escaped empty negative role controls and clears local bindings without importing resources or settings`() {
        val source = source()
        val text = NovelAiStudioFullPromptClipboard.encode(source)
        assertTrue(text.startsWith(NovelAiStudioFullPromptClipboard.PREAMBLE))
        val target = NovelAiStudioDraft(basePrompt = "target", negativePrompt = "target negative",
            characters = listOf(NovelAiCharacterPromptDraft(prompt = "target", negativePrompt = "old role negative", enabled = false)),
            importedCharacterCardId = "target-card", aiDesignModelId = "target-ai", imageDescription = "target AI input",
            continuousModeEnabled = true, continuousTargetCount = 99,
            v45Settings = NovelAiGenerationSettings(steps = 17), imageGuidance = NovelAiImageGuidanceDraft(imageToImageStrength = .7f))
        val content = NovelAiStudioFullPromptClipboard.decode(text.replace("\n", "\r\n"))
        val restored = content.applyTo(target)
        assertEquals(source.stylePrompt, restored.stylePrompt); assertEquals(source.basePrompt, restored.basePrompt)
        assertEquals("", restored.extraPrompt); assertEquals("", restored.negativePrompt)
        assertEquals(source.characters.map { listOf(it.prompt, it.negativePrompt, it.enabled, it.center) },
            restored.characters.map { listOf(it.prompt, it.negativePrompt, it.enabled, it.center) })
        assertTrue(restored.characters.first().id != source.characters.first().id)
        assertEquals(target.v45Settings, restored.v45Settings); assertEquals(target.imageGuidance, restored.imageGuidance)
        assertEquals(target.continuousModeEnabled, restored.continuousModeEnabled); assertEquals(target.continuousTargetCount, restored.continuousTargetCount)
        assertEquals(target.aiDesignModelId, restored.aiDesignModelId); assertEquals(target.imageDescription, restored.imageDescription)
        assertNull(restored.importedCharacterCardId); assertFalse(restored.followDefaultNovelAiImageModel)
    }

    @Test fun `unknown duplicate incomplete and malformed full documents refuse while old positive text semantics remain distinct`() {
        val source = source(); val text = NovelAiStudioFullPromptClipboard.encode(source)
        val old = NovelAiStudioPromptClipboard.encode(source)
        assertFails { NovelAiStudioPromptClipboard.apply(text, source) }
        assertFails { NovelAiStudioFullPromptClipboard.decode(old) }
        for (bad in listOf(text.replace("CCB-FULL-PROMPT/1", "CCB-FULL-PROMPT/2"),
            text + "\n\n【画风】\nduplicate", text.replace("【全局负向】", "【未知分段】"),
            text.substringBefore("\n\n【角色 2 负向】"), text.replace("\"naturalLanguageMode\":true", "\"naturalLanguageMode\":null"),
            text.replace("\"naturalLanguageMode\":true", "\"naturalLanguageMode\":true,\"naturalLanguageMode\":true"))) {
            assertFails { NovelAiStudioFullPromptClipboard.decode(bad) }
        }
        val target = NovelAiStudioDraft(negativePrompt = "retained negative",
            characters = listOf(NovelAiCharacterPromptDraft(negativePrompt = "retained role", enabled = false)))
        val positiveOnly = NovelAiStudioPromptClipboard.apply(old, target)
        assertEquals(target.negativePrompt, positiveOnly.negativePrompt)
        assertEquals("retained role", positiveOnly.characters.first().negativePrompt); assertFalse(positiveOnly.characters.first().enabled)
    }

    @Test fun `actual generation request receives source prompt fields without transport labels configuration or target negatives`() {
        val source = source().copy(stylePrompt = "watercolor", basePrompt = "quiet library", extraPrompt = "sunlight", negativePrompt = "lowres",
            characters = listOf(NovelAiCharacterPromptDraft(prompt = "adult traveler", negativePrompt = "bad hands", center = DesignedCharacterCenter(.3f, .7f))),
            v45Settings = NovelAiGenerationSettings(seedMode = NovelAiSeedMode.FIXED, seed = 123, useCharacterPositions = true))
        fun request(draft: NovelAiStudioDraft) = NovelAiImageService().buildRequestBody(draft.toPromptPlan(), draft.activeSettings.imageSize(), draft.activeSettings)
        for (model in listOf(NovelAiImageModel.V4_5_FULL, NovelAiImageModel.V5_FULL)) for (positions in listOf(true, false)) {
            val selected = source.copy(selectedModel = model, v45Settings = source.v45Settings.copy(useCharacterPositions = positions),
                v5Settings = source.v5Settings.copy(seedMode = NovelAiSeedMode.FIXED, seed = 123, useCharacterPositions = positions))
            val target = selected.copy(stylePrompt = "old", basePrompt = "old", negativePrompt = "old negative", characters = emptyList(),
                v45Settings = selected.v45Settings.copy(useCharacterPositions = !positions),
                v5Settings = selected.v5Settings.copy(useCharacterPositions = !positions))
            val restored = NovelAiStudioFullPromptClipboard.decode(NovelAiStudioFullPromptClipboard.encode(selected)).applyTo(target)
            val body = request(restored)
            assertEquals(request(selected), body)
            assertEquals(positions, Json.parseToJsonElement(body).jsonObject.getValue("parameters").jsonObject
                .getValue("v4_prompt").jsonObject.getValue("use_coords").jsonPrimitive.boolean)
            assertEquals(if (model == NovelAiImageModel.V4_5_FULL) target.v5Settings else target.v45Settings,
                if (model == NovelAiImageModel.V4_5_FULL) restored.v5Settings else restored.v45Settings)
            assertFalse(body.contains("CCB-FULL-PROMPT")); assertFalse(body.contains("【配置】")); assertFalse(body.contains("old negative"))
        }
    }
}
