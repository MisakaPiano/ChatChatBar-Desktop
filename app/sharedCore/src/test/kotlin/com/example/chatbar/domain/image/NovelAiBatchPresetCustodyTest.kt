package com.example.chatbar.domain.image

import kotlinx.serialization.json.*
import kotlin.test.*

class NovelAiBatchPresetCustodyTest {
    private val json = Json { encodeDefaults = true }

    @Test fun `full preset round trip replaces pacing without carrying running consent`() {
        val pacing = ImageBatchPacing(ImageBatchInterval.RANGE, minSeconds = 20, maxSeconds = 40)
        val source = NovelAiStudioDraft(basePrompt = "synthetic image", batchPacing = pacing,
            continuousModeEnabled = true, continuousTargetCount = 9)
        val preset = NovelAiGenerationPreset.capture(source, { error("No resources") }, { error("No encodings") })
        val restored = json.decodeFromString<NovelAiGenerationPreset>(json.encodeToString(NovelAiGenerationPreset.serializer(), preset))
        val target = NovelAiStudioDraft(batchPacing = ImageBatchPacing(fixedSeconds = 4))
        val applied = restored.applyTo(target, restored.imageGuidance)
        assertEquals(pacing, applied.batchPacing)
        assertEquals(9, applied.continuousTargetCount)
        assertTrue(applied.continuousModeEnabled)
        val keys = json.encodeToJsonElement(NovelAiGenerationPreset.serializer(), restored).jsonObject.keys
        assertFalse(keys.any { it in setOf("budget", "control", "remainingSeconds", "estimatedSpent", "consent") })
    }

    @Test fun `older full preset without pacing remains readable with default cadence`() {
        val preset = NovelAiGenerationPreset.capture(NovelAiStudioDraft(), { error("No resources") }, { error("No encodings") })
        val old = JsonObject(json.encodeToJsonElement(NovelAiGenerationPreset.serializer(), preset).jsonObject - "batchPacing")
        val restored = json.decodeFromJsonElement<NovelAiGenerationPreset>(old)
        restored.validate()
        assertEquals(ImageBatchPacing(), restored.batchPacing)
        assertFalse(restored.continuousModeEnabled)
    }

    @Test fun `invalid imported cadence is rejected without mutating target`() {
        val target = NovelAiStudioDraft(basePrompt = "retained", batchPacing = ImageBatchPacing(fixedSeconds = 7))
        val preset = NovelAiGenerationPreset.capture(target, { error("No resources") }, { error("No encodings") })
            .copy(batchPacing = ImageBatchPacing(minSeconds = 40, maxSeconds = 10))
        assertFailsWith<IllegalArgumentException> { preset.applyTo(target, preset.imageGuidance) }
        assertEquals("retained", target.basePrompt)
        assertEquals(7, target.batchPacing.fixedSeconds)
    }
}
