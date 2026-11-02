package com.example.chatbar.domain.budget

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.rag.RetrievedKnowledgeCard
import kotlin.test.*

class PromptRuntimeBudgetTest {
    @Test fun `original outlet then placeholder rendering and serialized cache identity survive numeric observation`() {
        for (coreOutlet in listOf(false, true)) {
            val raw = "{{outlet::memo}} {{outlet::missing}} {{user}} {{char}}"
            val card = CharacterCard("card", "Synthetic", systemPrompt = if (coreOutlet) raw else "synthetic core",
                basicSetting = "synthetic setting", createdAt = 1, updatedAt = 1)
            val measurements = mutableListOf<PromptRenderMeasurement>()
            val assembler = PromptAssembler()
            fun layers(observe: Boolean) = assembler.assembleCachePromptLayers(card,
                playerName = "Player", playerSetting = if (coreOutlet) "persona" else raw,
                worldBookOutlets = mapOf("memo" to "{{user}} plus {{char}}", "unused" to "not referenced"),
                ragResults = listOf(RetrievedKnowledgeCard("doc", ChunkSourceType.DOCUMENT, "s1", "doc", "document"),
                    RetrievedKnowledgeCard("memory", ChunkSourceType.CHAT_MEMORY, "s2", "memory", "recalled")),
                renderObserver = if (observe) ({ measurements.add(it); Unit }) else null)
            val plain = layers(false)
            val observed = layers(true)
            assertEquals(plain, observed)
            assertEquals(PromptRenderSource.entries.toSet(), measurements.map { it.source }.toSet())
            assertEquals(7, measurements.size)
            val changed = measurements.single { it.source == if (coreOutlet) PromptRenderSource.CORE else PromptRenderSource.PLAYER }
            assertEquals(1, changed.resolvedOutletOccurrences)
            assertEquals(1, changed.unresolvedOutletOccurrences)
            assertNotEquals(changed.raw.utf16Units, changed.afterOutlets.utf16Units)
            val final = if (coreOutlet) observed.coreSystemPrompt else observed.playerSystemPrompt
            assertTrue(final.contains("Player plus Synthetic"))
            assertTrue(final.contains("{{outlet::missing}}"))
            assertEquals(WeightedCharacterTokenEstimator.estimate(final), changed.afterPlaceholders)
            assertFalse(changed.toString().contains("Player"))
            val requestAssembler = MainChatRequestAssembler()
            fun request(value: PromptCachePromptLayers) = requestAssembler.assemble(MainChatRequestAssemblyInput(
                value, "synthetic constraints", FormatPromptPosition.END,
                currentUserMessage = ChatApiMessage.text("user", "input"), botName = "Synthetic"))
            val before = request(plain); val after = request(observed)
            assertEquals(before, after)
            val model = ModelConfig("m", "Synthetic", "https://example.test/v1", "", "fixture", createdAt = 1)
            assertEquals(OpenAiChatRequestSerializer.serialize(before.messages, model, true),
                OpenAiChatRequestSerializer.serialize(after.messages, model, true))
        }
    }

    @Test fun `wire mismatch cannot carry stale selection or cache guesses and matched totals do not add render stages`() {
        val model = ModelConfig("m", "Synthetic", "https://example.test/v1", "", "fixture", createdAt = 1)
        val messages = listOf(ChatApiMessage.text("system", "synthetic prefix"), ChatApiMessage.text("user", "input"))
        val trace = listOf(MainChatLogicalMessageTrace(messages[0], MainChatLogicalMessageSource.CORE, true),
            MainChatLogicalMessageTrace(messages[1], MainChatLogicalMessageSource.CURRENT_USER, false))
        val selection = PromptRuntimeBudgetSelection(12, 4, 2, 1, 1, 1, 3, 1, null, emptyList(), true)
        fun estimate(items: List<ChatApiMessage>, plan: PromptRuntimeBudgetSelection = selection) =
            WirePromptBudgetEstimator.estimate(OpenAiChatRequestSerializer.serialize(items, model, true),
                model.promptEstimateTarget(), trace, runtimeSelection = plan)
        val exact = estimate(messages)
        assertEquals(selection, exact.runtimeSelection)
        assertEquals(WeightedCharacterTokenEstimator.estimate("synthetic prefix").tokens, exact.stablePrefixEstimatedTextTokens)
        assertEquals(messages.sumOf { WeightedCharacterTokenEstimator.estimate((it.content as kotlinx.serialization.json.JsonPrimitive).content).tokens },
            exact.estimatedTextTokens)
        val replaced = estimate(listOf(ChatApiMessage.text("user", "custom override")))
        assertFalse(replaced.attributedToLogicalSections)
        assertNull(replaced.runtimeSelection); assertNull(replaced.stablePrefixEstimatedTextTokens)
        assertNull(estimate(messages, selection.copy(stablePrefixCacheable = false)).stablePrefixEstimatedTextTokens)
    }
}
