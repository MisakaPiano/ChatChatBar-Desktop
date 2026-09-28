package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ChatSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPrimaryChatBrowserStateTest {
    @Test
    fun `session summaries are collapsed until explicitly expanded and only one stays open`() {
        val persisted = ChatSession(id = "a", characterCardId = "character", title = "Original",
            lastMessagePreview = "Preview", createdAt = 1L, updatedAt = 1L)
        val item = DesktopPrimarySessionItem("a", "Original", null, false, "Character", "Preview")
        val initial = DesktopPrimaryChatBrowserState()
        assertNull(initial.expandedSessionId)
        assertNull(initial.visiblePreview(item))
        assertEquals("Preview", persisted.lastMessagePreview)
        assertEquals("Original", persisted.title)
        val first = initial.toggleSummary("a")
        assertEquals("a", first.expandedSessionId)
        assertEquals("Preview", first.visiblePreview(item))
        assertNull(first.toggleSummary("a").expandedSessionId)
        assertNull(first.toggleSummary("a").visiblePreview(item))
        assertEquals("b", first.toggleSummary("b").expandedSessionId)
        assertNull(initial.expandedSessionId)
    }

    @Test
    fun `context settings carries the clicked session identity without selecting an entity`() {
        val state = DesktopPrimaryChatBrowserState().openSettings("other-session")
        assertEquals("other-session", state.settingsSessionId)
        assertNull(state.expandedSessionId)
    }

    @Test
    fun `new chat search filters names and closing picker creates no selection`() {
        val characters = listOf(DesktopPrimaryChoice("one", "Alice"), DesktopPrimaryChoice("two", "Bob"))
        val open = DesktopPrimaryChatBrowserState().openNewChat().copy(characterQuery = "ali")
        assertEquals(listOf("one"), open.filteredCharacters(characters).map { it.id })
        val closed = open.closeNewChat()
        assertFalse(closed.newChatOpen)
        assertEquals("", closed.characterQuery)
        assertTrue(closed.filteredCharacters(characters).size == 2)
    }
}
