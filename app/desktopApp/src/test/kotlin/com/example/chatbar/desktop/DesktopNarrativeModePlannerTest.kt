package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.prompt.MainChatPromptAuthority
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.*

class DesktopNarrativeModePlannerTest {
    @Test fun `actual planner wraps selected history only and regeneration reads original target mode before blank candidate`() = runBlocking {
        val root = Files.createTempDirectory("narrative-planner-")
        val c = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() })
        try {
            val card = CharacterCard.create("Synthetic")
            c.characterRepository.save(card)
            val id = c.characterSessionService.createSessionForCharacter(card.id)
            // No eligible historical reply must stay unknown, including an image-derived child.
            val first = c.chatRepository.addMessage(ChatMessage.create(id, MessageRole.USER, "first input"))
            c.chatRepository.setNarrativeMode(id, ChatNarrativeMode.ADVANCE)
            val inputs = DesktopFakeChatInputs(effectiveContextWindowSize = 20)
            val empty = c.chatRequestPlanner.planPersistedUser(id, first.id, inputs, false)
            fun tail(plan: DesktopChatRequestPlan) = plan.assembly.messageTrace.single {
                it.source == MainChatLogicalMessageSource.NARRATIVE_MODE_TAIL }.message.content.jsonPrimitive.content
            assertEquals(MainChatPromptAuthority.narrativeModeTailSystemPrompt(ChatNarrativeMode.ADVANCE, null).trim(), tail(empty))
            val original = ChatMessage.create(id, MessageRole.ASSISTANT, "selected null version").copy(
                narrativeMode = null, alternatives = listOf("focus source", "selected null version"), currentAlternativeIndex = 1,
                alternativeNarrativeModes = listOf(ChatNarrativeMode.FOCUS, null))
            c.chatRepository.addMessage(original)
            val user = c.chatRepository.addMessage(ChatMessage.create(id, MessageRole.USER, "second input"))
            val enabled = c.chatRequestPlanner.planPersistedUser(id, user.id, inputs, false)
            assertTrue(enabled.assembly.messages.any { it.content.jsonPrimitive.content == original.content })
            assertEquals(tail(empty), tail(enabled))
            val regen = c.chatRequestPlanner.planRegeneration(id, original.id, inputs)
            assertEquals(tail(empty), tail(regen))
            assertFalse(regen.assembly.messages.any { it.content.toString().contains(original.content) })
            val selected = MessageAlternativeVersionPolicy.select(original, 0)
            c.chatRepository.updateMessage(selected)
            val focusHistory = c.chatRequestPlanner.planPersistedUser(id, user.id, inputs, false)
            assertTrue(focusHistory.assembly.messages.any { it.content.jsonPrimitive.content ==
                MainChatPromptAuthority.narrativeModeHistoryReply("focus source", ChatNarrativeMode.FOCUS) })
            assertEquals(MainChatPromptAuthority.narrativeModeTailSystemPrompt(ChatNarrativeMode.ADVANCE, ChatNarrativeMode.FOCUS).trim(), tail(focusHistory))
            assertEquals(enabled.assembly.promptCacheKey, focusHistory.assembly.promptCacheKey)
            val disabled = c.chatRequestPlanner.planPersistedUser(id, user.id, inputs.copy(narrativeModesEnabled = false), false)
            assertFalse(disabled.assembly.messageTrace.any { it.source in setOf(MainChatLogicalMessageSource.NARRATIVE_MODE, MainChatLogicalMessageSource.NARRATIVE_MODE_TAIL) })
            assertFalse(disabled.assembly.messages.any { "<历史回复 " in it.content.jsonPrimitive.content })
            assertNotEquals(enabled.assembly.promptCacheKey, disabled.assembly.promptCacheKey)
            assertEquals(selected, c.chatRepository.getMessage(original.id, id))
            c.chatRepository.setNarrativeMode(id, ChatNarrativeMode.FOCUS)
            val changed = c.chatRequestPlanner.planPersistedUser(id, user.id, inputs, false)
            assertNotEquals(enabled.assembly.promptCacheKey, changed.assembly.promptCacheKey)
        } finally { c.close(); root.toFile().deleteRecursively() }
    }
}
