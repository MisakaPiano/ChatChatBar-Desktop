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
        assertFalse(shown.segments.last().statusDefaultExpanded)
        assertFalse(shown.copyText.contains("<n="))
        assertFalse(shown.copyText.contains("hidden"))
        assertFalse(shown.copyText.contains("```"))
    }

    @Test
    fun `unsegmented assistant becomes one sanitized visible bubble`() {
        val message = ChatMessage.create("session", MessageRole.ASSISTANT,
            "<n=\"Alice\"/>[Hello]()<!-- metadata -->\n---\nOption\n---")
            .copy(reasoningContent = "Why $" + "botname")

        val whole = desktopPresentMessage(message, card, "Player", segmentedAssistant = false)
        val split = desktopPresentMessage(message, card, "Player", segmentedAssistant = true)

        assertFalse(whole.segmented)
        assertEquals(1, whole.segments.size)
        assertTrue(split.segments.size > whole.segments.size)
        assertFalse(whole.segments.single().text.contains("<n="))
        assertFalse(whole.segments.single().text.contains("metadata"))
        assertEquals("Why Bot", whole.reasoning)
        assertFalse(whole.defaultReasoningExpanded)
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
    fun `streaming preview uses shared parser and hides speaker metadata`() {
        val visible = desktopVisibleAssistantText("<n=\"Alice\"/>[Hello]()<!-- hidden -->", "Player", "Bot")

        assertTrue(visible.contains("Hello"))
        assertFalse(visible.contains("<n="))
        assertFalse(visible.contains("hidden"))
    }
}
