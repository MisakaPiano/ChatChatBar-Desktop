package com.example.chatbar.desktop

import androidx.compose.ui.text.style.TextDecoration
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.*
import kotlin.test.*

class DesktopPrimaryChatRepairPresentationTest {
    private val card = CharacterCard.create("Card").copy(avatar = "images/card.png",
        characters = listOf(CharacterInfo.create("Alice").copy(appearanceImage = "images/alice.png")))
    private val content = "Narration<!-- secret --><n=\"Alice\"/>[Hello]()『Thinking』\n```status\nHealthy\n```"

    @Test fun `segmented structure has only grouped speaker headers and independent status`() {
        val message = ChatMessage.create("s", MessageRole.ASSISTANT, content)
        val shown = desktopPresentMessage(message, card, "Player", true)
        assertFalse(shown.enclosingCard)
        assertFalse(shown.showWholeMessageHeader)
        assertEquals(setOf(1), shown.speakerHeaderIndexes)
        assertEquals(listOf(DesktopSegmentSurface.NARRATION, DesktopSegmentSurface.DIALOGUE,
            DesktopSegmentSurface.THOUGHT, DesktopSegmentSurface.STATUS), shown.segments.map(::desktopSegmentSurface))
        assertNull(shown.segments.first().speaker)
        assertEquals("images/alice.png", shown.segments[1].speaker?.avatarReference)
        assertEquals(shown.segments[1].speaker, shown.segments[2].speaker)
        assertTrue(shown.segments.last().statusDefaultExpanded)
        assertTrue(desktopPresentMessage(message, card, "Player", false).enclosingCard)
        assertTrue(desktopPresentMessage(message, card, "Player", false).showWholeMessageHeader)
        assertEquals(content, message.content)
    }

    @Test fun `dialogue markers never receive link decoration while genuine links remain links`() {
        for (raw in listOf("[Hello]()", "[Hello](soft)", "［Hello］（soft）")) {
            val message = ChatMessage.create("s", MessageRole.ASSISTANT, "<!-- secret --><n=\"Alice\"/>$raw")
            val shown = desktopPresentMessage(message, card, null, true)
            assertEquals("Hello", shown.copyText)
            val markdown = desktopMarkdown(shown.segments.single().text)
            assertEquals("Hello", markdown.text)
            assertTrue(markdown.spanStyles.none { it.item.textDecoration == TextDecoration.Underline })
            assertEquals("<!-- secret --><n=\"Alice\"/>$raw", message.content)
        }
        val message = ChatMessage.create("s", MessageRole.ASSISTANT, "See [docs](https://example.test)")
        val shown = desktopPresentMessage(message, card, null, true)
        val markdown = desktopMarkdown(shown.copyText)
        assertEquals("See docs", markdown.text)
        assertTrue(markdown.spanStyles.any { it.item.textDecoration == TextDecoration.Underline })
    }

    @Test fun `raw coordinates and copy projection retain shared segment identity`() {
        val message = ChatMessage.create("s", MessageRole.ASSISTANT, content)
        val source = parseRoleplayTextSegments(message.displayContent)
        val shown = desktopPresentMessage(message, card, null, true)
        shown.segments.forEachIndexed { index, segment ->
            assertEquals(source[index], segment.source)
            assertEquals(index, segment.index)
            assertEquals(roleplayTextBlockId(message.id, index), segment.blockId)
            assertFalse(segment.text.contains("secret"))
            assertFalse(segment.text.contains("<n="))
        }
    }

    @Test fun `streaming and persisted use equivalent segmented hierarchy`() {
        val stored = ChatMessage.create("s", MessageRole.ASSISTANT, content, reasoningContent = "Reason")
        val streaming = desktopStreamingMessage("s", "task", content, "Reason")
        val a = desktopPresentMessage(stored, card, null, true)
        val b = desktopPresentMessage(streaming, card, null, true)
        assertEquals(a.copy(segments = a.segments.map { it.copy(blockId = null) }),
            b.copy(segments = b.segments.map { it.copy(blockId = null) }))
        assertFalse(a.defaultReasoningExpanded)
    }

    @Test fun `composer exposes only Send or Stop`() {
        assertEquals(DesktopComposerAction.SEND, desktopComposerAction(false))
        assertEquals(DesktopComposerAction.STOP, desktopComposerAction(true))
        assertEquals(2, DesktopComposerAction.entries.size)
    }

    @Test fun `diagnostic disclosure is collapsed on session changes`() {
        val initial = DesktopDiagnosticDisclosure("a")
        assertFalse(initial.expanded)
        val open = initial.toggle("a")
        assertTrue(open.expanded)
        assertFalse(open.toggle("a").expanded)
        assertFalse(open.select("b").expanded)
        assertEquals("b", open.select("b").sessionId)
        assertFalse(open.select("b").select("a").expanded)
    }

    @Test fun `wide browser collapses and restores independently of compact selection`() {
        val initial = DesktopPrimaryChatBrowserState(settingsSessionId = "settings")
        for (size in DesktopShellSize.entries.filter { it != DesktopShellSize.COMPACT }) {
            assertTrue(initial.browserVisible(size, false))
            assertFalse(initial.toggleWideBrowser().browserVisible(size, false))
            assertTrue(initial.toggleWideBrowser().toggleWideBrowser().browserVisible(size, false))
        }
        assertTrue(initial.toggleWideBrowser().browserVisible(DesktopShellSize.COMPACT, true))
        assertFalse(initial.browserVisible(DesktopShellSize.COMPACT, false))
        assertEquals("settings", initial.toggleWideBrowser().settingsSessionId)
    }
}
