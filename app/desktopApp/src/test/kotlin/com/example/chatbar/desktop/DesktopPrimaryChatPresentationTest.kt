package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.chat.RoleplaySegmentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPrimaryChatPresentationTest {
    private val card = CharacterCard.create("Card").copy(
        botName = "Bot",
        avatar = "images/card.png",
        characters = listOf(CharacterInfo("a", "Alice", appearanceImage = "images/alice.png")),
    )

    @Test
    fun `segmented assistant uses shared visible roleplay semantics and no literal markers`() {
        val message = ChatMessage.create("session", MessageRole.ASSISTANT,
            "旁白<!-- hidden --><n=\"Alice\"/>[你好]()『心声』\n```status\n状态\n```")
        val shown = desktopPresentMessage(message, card, "Player", segmentedAssistant = true)

        assertTrue(shown.segmented)
        assertEquals(listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.DIALOGUE,
            RoleplaySegmentKind.THOUGHT, RoleplaySegmentKind.STATUS), shown.segments.map { it.kind })
        assertEquals("Alice", shown.segments[1].speaker?.displayName)
        assertEquals("images/alice.png", shown.segments[1].speaker?.avatarReference)
        assertTrue(shown.segments.last().statusDefaultExpanded)
        assertFalse(shown.copyText.contains("<n="))
        assertFalse(shown.copyText.contains("hidden"))
        assertFalse(shown.copyText.contains("```"))
    }

    @Test
    fun `unsegmented assistant keeps whole-message text and expandable status`() {
        val message = ChatMessage.create("session", MessageRole.ASSISTANT,
            "<n=\"Alice\"/>[Hello]()<!-- metadata -->\n---\nOption\n---")
            .copy(reasoningContent = "Why $" + "botname")

        val whole = desktopPresentMessage(message, card, "Player", segmentedAssistant = false)
        val split = desktopPresentMessage(message, card, "Player", segmentedAssistant = true)

        assertFalse(whole.segmented)
        assertTrue(whole.segments.any { it.kind == RoleplaySegmentKind.STATUS && it.statusDefaultExpanded })
        assertTrue(split.segments.any { it.kind == RoleplaySegmentKind.DIALOGUE })
        assertFalse(whole.segments.any { it.kind == RoleplaySegmentKind.DIALOGUE })
        assertFalse(whole.segments.joinToString("") { it.text }.contains("<n="))
        assertFalse(whole.segments.joinToString("") { it.text }.contains("metadata"))
        assertEquals("Why Bot", whole.reasoning)
        assertFalse(whole.defaultReasoningExpanded)
        assertTrue(split.segments.any { it.kind == RoleplaySegmentKind.STATUS && it.statusDefaultExpanded })
    }

    @Test
    fun `speaker avatar uses card fallback for unmarked dialogue`() {
        val message = ChatMessage.create("session", MessageRole.ASSISTANT, "[Hello]()")
        val shown = desktopPresentMessage(message, card, null, segmentedAssistant = true)

        assertEquals("images/card.png", desktopPresentedAvatarReference(shown.segments.single().speaker, card))
        assertEquals("Bot", shown.speakerLabel)
    }

    @Test
    fun `home title and preview honor player override global fallback bot name and persisted source`() {
        val original = ChatSession.create(card.id, "$" + "botname meets $" + "username")
            .copy(lastMessagePreview = "Hello $" + "username from $" + "botname")
        assertEquals("Bot meets Global", desktopRenderedSessionTitle(original, card, "Global"))
        assertEquals("Hello Global from Bot", desktopRenderSessionText(original,
            original.lastMessagePreview.orEmpty(), card, "Global"))
        val overridden = original.copy(playerName = "Local", displayTitleOverride = "Room for $" + "username")
        assertEquals("Room for Local", desktopRenderedSessionTitle(overridden, card, "Global"))
        assertEquals("Hello Local from Bot", desktopRenderSessionText(overridden,
            overridden.lastMessagePreview.orEmpty(), card, "Global"))
        assertEquals("$" + "botname meets $" + "username", original.title)
        assertEquals("Hello $" + "username from $" + "botname", original.lastMessagePreview)
    }

    @Test
    fun `desktop markdown presents emphasis heading links and lists without raw delimiters`() {
        val shown = desktopMarkdown("# Heading\n**bold** and *italic* with [link](https://example.test)\n- item")

        assertEquals("Heading\nbold and italic with link\n• item", shown.text)
        assertTrue(shown.spanStyles.isNotEmpty())
    }

    @Test
    fun `non-assistant visible text hides comments and keeps status panels`() {
        for (role in listOf(MessageRole.USER, MessageRole.SYSTEM)) {
            val message = ChatMessage.create("session", role,
                "Hello<!-- private -->\n```status\nHealth: 3\n```\nAfter")
            val shown = desktopPresentMessage(message, card, "Player", segmentedAssistant = true)
            assertFalse(shown.segmented)
            assertTrue(shown.segments.any { it.kind == RoleplaySegmentKind.STATUS })
            assertFalse(shown.copyText.contains("private"))
            assertFalse(shown.copyText.contains("```"))
        }
    }

    @Test
    fun `streaming and persisted assistant use identical segmented and legacy semantics`() {
        val content = "<n=\"Alice\"/>[Hello]()<!-- private -->\n```status\nHealth: 3\n```"
        val streaming = desktopStreamingMessage("session", "task", content, "Thinking")
        val persisted = ChatMessage.create("session", MessageRole.ASSISTANT, content,
            reasoningContent = "Thinking")
        assertEquals("stream:task", streaming.id)
        for (segmented in listOf(true, false)) {
            val live = desktopPresentMessage(streaming, card, "Player", segmented)
            val saved = desktopPresentMessage(persisted, card, "Player", segmented)
            assertEquals(saved, live)
            assertTrue(live.segments.any { it.kind == RoleplaySegmentKind.STATUS })
            assertFalse(live.copyText.contains("private"))
            assertEquals("Thinking", live.reasoning)
            assertFalse(live.defaultReasoningExpanded)
        }
        assertTrue(desktopPresentMessage(
            desktopStreamingMessage("session", "partial", "<!-- unfinished", ""), card, null, true,
        ).copyText.contains("unfinished"))
    }

    @Test
    fun `markdown covers baseline visible constructs`() {
        val shown = desktopMarkdown(
            "## Header\n**bold** *italic* ~~gone~~ `code` [link](https://example.test)\n" +
                "- bullet\n1. first\n> quotation\nnext line",
        )
        assertEquals("Header\nbold italic gone code link\n• bullet\n1. first\n❝ quotation\nnext line", shown.text)
        assertTrue(shown.spanStyles.size >= 6)
        val fenced = desktopMarkdown("```kotlin\nval value = 1\n```")
        assertEquals("val value = 1", fenced.text)
    }

    @Test
    fun `streaming preview uses shared parser and hides speaker metadata`() {
        val visible = desktopVisibleAssistantText("<n=\"Alice\"/>[Hello]()<!-- hidden -->", "Player", "Bot")

        assertTrue(visible.contains("Hello"))
        assertFalse(visible.contains("<n="))
        assertFalse(visible.contains("hidden"))
    }

    @Test
    fun `status options expand and reasoning stays collapsed with UI-only toggles`() {
        val message = ChatMessage.create("session", MessageRole.ASSISTANT,
            "```status\nHealth: 3\n```\n---\nOption A\n---").copy(reasoningContent = "private reasoning")
        val original = message.copy()
        val shown = desktopPresentMessage(message, card, null, segmentedAssistant = true)
        val status = shown.segments.filter { it.kind == RoleplaySegmentKind.STATUS }
        assertEquals(2, status.size)
        assertTrue(status.all { it.statusDefaultExpanded })
        assertFalse(shown.defaultReasoningExpanded)
        val statusExpansion = DesktopPresentationExpansion(status.first().statusDefaultExpanded)
        statusExpansion.toggle()
        assertFalse(statusExpansion.expanded)
        statusExpansion.toggle()
        assertTrue(statusExpansion.expanded)
        val reasoningExpansion = DesktopPresentationExpansion(shown.defaultReasoningExpanded)
        reasoningExpansion.toggle()
        assertTrue(reasoningExpansion.expanded)
        assertEquals(original, message)
    }

    @Test
    fun `role fallback labels localize without changing placeholder content`() {
        val chinese = DesktopRoleLabels("助手", "你", "系统")
        val user = ChatMessage.create("session", MessageRole.USER, "Hi")
        val assistant = ChatMessage.create("session", MessageRole.ASSISTANT, "$" + "botname")
        assertEquals("你", desktopPresentMessage(user, null, null, false, chinese).speakerLabel)
        val shown = desktopPresentMessage(assistant, null, null, false, chinese)
        assertEquals("助手", shown.speakerLabel)
        assertEquals("Assistant", shown.copyText)
        assertEquals("System", desktopPresentMessage(
            ChatMessage.create("session", MessageRole.SYSTEM, "System message"), null, null, false,
        ).speakerLabel)
    }
}
