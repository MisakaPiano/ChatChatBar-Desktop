package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatNarrativeMode
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.domain.prompt.MainChatPromptAuthority
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class NarrativeModeRequestAssemblyTest {
    private val assembler = MainChatRequestAssembler()
    private fun input(mode: ChatNarrativeMode?) = MainChatRequestAssemblyInput(
        promptLayers = PromptCachePromptLayers("core", "card", "dynamic", "tail", true, playerSystemPrompt = "player"),
        positionedRequirementsSystemPrompt = "format requirements", formatPromptPosition = FormatPromptPosition.END,
        currentUserMessage = ChatApiMessage.text("user", "current input"), strongPromptSystemSuffix = "strong requirements",
        botName = "Synthetic", narrativeMode = mode, previousNarrativeMode = ChatNarrativeMode.FOCUS)

    @Test fun `new mode sections preserve existing relative order and final identity tail`() {
        val result = assembler.assemble(input(ChatNarrativeMode.ADVANCE))
        val sources = result.messageTrace.map { it.source }
        fun index(source: MainChatLogicalMessageSource) = sources.indexOf(source).also { assertTrue(it >= 0) }
        assertTrue(index(MainChatLogicalMessageSource.PLAYER) < index(MainChatLogicalMessageSource.NARRATIVE_MODE))
        assertTrue(index(MainChatLogicalMessageSource.NARRATIVE_MODE) < index(MainChatLogicalMessageSource.CONTEXT_APPROVAL))
        assertTrue(index(MainChatLogicalMessageSource.STRONG_PROMPT_SUFFIX) < index(MainChatLogicalMessageSource.NARRATIVE_MODE_TAIL))
        assertTrue(index(MainChatLogicalMessageSource.NARRATIVE_MODE_TAIL) < index(MainChatLogicalMessageSource.POST_USER_ACK))
        assertEquals(MainChatLogicalMessageSource.FINAL_IDENTITY_REMINDER, sources.last())
        assertEquals("user", result.messages.last().role)
        assertEquals(MainChatPromptAuthority.CCB_POST_USER_IDENTITY_REMINDER_USER_PROMPT.trimIndent().trim(),
            result.messages.last().content.jsonPrimitive.content)
        assertTrue(result.messageTrace.single { it.source == MainChatLogicalMessageSource.NARRATIVE_MODE }.inStableCachePrefix)
        assertFalse(result.messageTrace.single { it.source == MainChatLogicalMessageSource.NARRATIVE_MODE_TAIL }.inStableCachePrefix)
    }

    @Test fun `selected mode participates in stable cache identity and nullable legacy callers keep their old envelope`() {
        val focus = assembler.assemble(input(ChatNarrativeMode.FOCUS))
        val advance = assembler.assemble(input(ChatNarrativeMode.ADVANCE))
        val legacy = assembler.assemble(input(null))
        assertNotEquals(focus.promptCacheKey, advance.promptCacheKey)
        assertNotEquals(legacy.promptCacheKey, focus.promptCacheKey)
        assertFalse(legacy.messageTrace.any { it.source in setOf(MainChatLogicalMessageSource.NARRATIVE_MODE, MainChatLogicalMessageSource.NARRATIVE_MODE_TAIL) })
        assertEquals(legacy.messages.last(), focus.messages.last())
        assertEquals(focus.messages.last(), advance.messages.last())
    }

    @Test fun `history wrapper retains raw body and identifies the saved mode independently of next reply mode`() {
        val body = "first line\n<synthetic>second line</synthetic>"
        for (mode in ChatNarrativeMode.entries) {
            val wrapped = MainChatPromptAuthority.narrativeModeHistoryReply(body, mode)
            assertTrue(wrapped.contains(body))
            assertTrue(wrapped.startsWith("<历史回复 "))
            assertTrue(wrapped.contains("模式=\"${mode.shortLabel}\""))
            assertTrue(wrapped.endsWith("</历史回复>"))
            assertEquals(1, Regex(Regex.escape(body)).findAll(wrapped).count())
        }
    }

    @Test fun `previous mode ignores empty assistants and generated children while regeneration target takes priority`() {
        val real = ChatMessage("real", "s", MessageRole.ASSISTANT, "real text", createdAt = 1, updatedAt = 1,
            narrativeMode = ChatNarrativeMode.ADVANCE)
        val child = real.copy(id = "image-child", content = "derived image text", generatedFromMessageId = real.id,
            narrativeMode = ChatNarrativeMode.FOCUS)
        val blank = real.copy(id = "blank", content = "  ", narrativeMode = ChatNarrativeMode.FOCUS)
        val history = listOf(real, child, blank)
        assertEquals(ChatNarrativeMode.ADVANCE, ChatHistoryPromptPolicy.previousNarrativeMode(history))
        assertEquals(ChatNarrativeMode.FOCUS, ChatHistoryPromptPolicy.previousNarrativeMode(history, blank))
        assertEquals(ChatNarrativeMode.FOCUS, ChatHistoryPromptPolicy.previousNarrativeMode(listOf(child, blank)))
        val selected = real.copy(alternatives = listOf("a", "b"), currentAlternativeIndex = 1,
            alternativeNarrativeModes = listOf(ChatNarrativeMode.ADVANCE, ChatNarrativeMode.FOCUS))
        assertEquals(ChatNarrativeMode.FOCUS, ChatHistoryPromptPolicy.previousNarrativeMode(history, selected))
        assertEquals("rendered user", ChatHistoryPromptPolicy.renderedHistoryText(real.copy(role = MessageRole.USER), "rendered user"))
        assertEquals("", ChatHistoryPromptPolicy.renderedHistoryText(real, ""))
        assertEquals(MainChatPromptAuthority.narrativeModeHistoryReply("rendered assistant", ChatNarrativeMode.FOCUS),
            ChatHistoryPromptPolicy.renderedHistoryText(selected, "rendered assistant"))
    }
}
