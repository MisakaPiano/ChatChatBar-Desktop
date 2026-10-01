package com.example.chatbar.domain.image

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NovelAiCharacterEnabledTest {
    private val hidden = NovelAiCharacterPromptDraft(
        id = "hidden", prompt = "hidden person, \"hidden words\"", negativePrompt = "hidden negative",
        negativeExpanded = true, center = DesignedCharacterCenter(0.1f, 0.9f), enabled = false
    )
    private val visible = NovelAiCharacterPromptDraft(
        id = "visible", prompt = "visible person", negativePrompt = "visible negative",
        center = DesignedCharacterCenter(0.7f, 0.3f)
    )

    @Test
    fun `old roles default active and folded content survives draft and history reload`() {
        val old = Json.decodeFromString<NovelAiStudioDraft>("""{"characters":[{"id":"old","prompt":"person"}]}""")
        assertTrue(old.characters.single().enabled)
        val draft = old.copy(characters = listOf(hidden, visible))
        val restored = Json.decodeFromString<NovelAiStudioDraft>(Json.encodeToString(draft))
        assertEquals(draft, restored)
        val recipe = Json.decodeFromString<NovelAiGenerationRecipe>(Json.encodeToString(draft.toRecipe()))
        for (mode in listOf(NovelAiHistoryApplyMode.FULL, NovelAiHistoryApplyMode.NEW_SEED)) {
            val applied = old.applyHistoryRecipe(recipe, 123L, mode)
            assertEquals(draft.characters, applied.characters)
            assertEquals(listOf(visible), applied.activeCharacters)
        }
    }

    @Test
    fun `both models omit folded positive negative text expansion and coordinates`() {
        for (model in NovelAiImageModel.entries) {
            val draft = NovelAiStudioDraft(basePrompt = "scene", characters = listOf(hidden, visible))
                .withActiveSettings(NovelAiGenerationSettings(model = model, useCharacterPositions = true))
            val body = NovelAiImageService().buildRequestBody(draft.toPromptPlan(), draft.activeSettings.imageSize(), draft.activeSettings)
            assertFalse(body.contains("hidden"))
            val parameters = Json.parseToJsonElement(body).jsonObject.getValue("parameters").jsonObject
            for ((key, text) in listOf("v4_prompt" to visible.prompt, "v4_negative_prompt" to visible.negativePrompt)) {
                val caption = parameters.getValue(key).jsonObject.getValue("caption").jsonObject
                    .getValue("char_captions").jsonArray.single().jsonObject
                assertEquals(text, caption.getValue("char_caption").jsonPrimitive.content)
                assertEquals("0.7", caption.getValue("centers").jsonArray.single().jsonObject.getValue("x").jsonPrimitive.content)
            }
            val restored = draft.copy(characters = draft.characters.map { it.copy(enabled = true) }).toPromptPlan()
            assertEquals(listOf(hidden.prompt, visible.prompt), restored.characterCaptions.map { it.prompt })
            assertEquals(hidden.center, restored.characterCaptions.first().center)
        }
    }

    @Test
    fun `all folded roles yield base only request and disable coordinates`() {
        for (model in NovelAiImageModel.entries) {
            val draft = NovelAiStudioDraft(basePrompt = "scene", characters = List(30) { hidden.copy(id = "$it") })
                .withActiveSettings(NovelAiGenerationSettings(model = model, useCharacterPositions = true))
            assertNull(draft.activeSettings.validationError(draft.activeCharacters.size))
            val parameters = Json.parseToJsonElement(NovelAiImageService().buildRequestBody(
                draft.toPromptPlan(), draft.activeSettings.imageSize(), draft.activeSettings
            )).jsonObject.getValue("parameters").jsonObject
            assertEquals("false", parameters.getValue("use_coords").jsonPrimitive.content)
            for (key in listOf("v4_prompt", "v4_negative_prompt")) {
                val block = parameters.getValue(key).jsonObject
                assertEquals("false", block.getValue("use_coords").jsonPrimitive.content)
                assertTrue(block.getValue("caption").jsonObject.getValue("char_captions").jsonArray.isEmpty())
            }
        }
    }

    @Test
    fun `automatic centers use active order and clipboard preserves folded roles beyond capacity`() {
        val draft = NovelAiStudioDraft(basePrompt = "scene", characters =
            List(7) { hidden.copy(id = "hidden-$it") } + visible.copy(center = null))
        assertEquals(NovelAiPromptDesigner.fallbackCenter(0, 1), draft.toPromptPlan().characterCaptions.single().center)
        val pasted = NovelAiStudioPromptClipboard.apply(NovelAiStudioPromptClipboard.encode(draft), draft)
        assertEquals(draft.characters, pasted.characters)
    }
}
