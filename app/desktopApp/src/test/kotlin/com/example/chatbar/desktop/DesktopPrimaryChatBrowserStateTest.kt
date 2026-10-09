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

    @Test
    fun `compact navigation distinguishes auto selection from an entered chat`() {
        val nav = DesktopCompactChatNavigation()
        assertTrue(nav.browserVisible(DesktopShellSize.COMPACT, "auto-selected"))
        assertFalse(nav.chatVisible(DesktopShellSize.COMPACT, "auto-selected"))
        nav.onWideChatDisplayed(DesktopShellSize.COMPACT, "auto-selected")
        assertFalse(nav.enteredChat)
        nav.onWideChatDisplayed(DesktopShellSize.WIDE, null)
        assertFalse(nav.enteredChat)
        nav.onWideChatDisplayed(DesktopShellSize.WIDE, "auto-selected")
        assertTrue(nav.enteredChat)
        for (size in listOf(DesktopShellSize.MEDIUM, DesktopShellSize.COMPACT, DesktopShellSize.WIDE, DesktopShellSize.COMPACT)) {
            assertTrue(nav.chatVisible(size, "auto-selected"))
        }
        assertTrue(nav.browserVisible(DesktopShellSize.COMPACT, null))
        assertTrue(DesktopCompactChatNavigation().browserVisible(DesktopShellSize.COMPACT, "auto-selected"))
    }

    @Test
    fun `explicit list survives width changes and only a successful selection dismisses it`() {
        val nav = DesktopCompactChatNavigation()
        val session = ChatSession.create("character", "Existing").copy(id = "a")
        nav.onWideChatDisplayed(DesktopShellSize.MEDIUM, session.id)
        nav.openBrowser()
        assertTrue(nav.browserVisible(DesktopShellSize.COMPACT, session.id))
        nav.onWideChatDisplayed(DesktopShellSize.WIDE, session.id)
        assertTrue(nav.browserVisible(DesktopShellSize.COMPACT, session.id))
        assertFalse(nav.onSessionEntered("missing", DesktopPrimaryChatState(selectedSession = session)))
        assertFalse(nav.onSessionEntered(session.id, DesktopPrimaryChatState(selectedSession = session, error = "Session no longer exists")))
        assertFalse(nav.onSessionEntered(session.id, DesktopPrimaryChatState(selectedSession = session, sessionSettingsLeavePrompt = true)))
        assertTrue(nav.browserVisible(DesktopShellSize.COMPACT, session.id))
        assertTrue(nav.onSessionEntered(session.id, DesktopPrimaryChatState(selectedSession = session)))
        assertTrue(nav.chatVisible(DesktopShellSize.COMPACT, session.id))
        nav.openBrowser()
        nav.returnToChat(session.id)
        assertTrue(nav.chatVisible(DesktopShellSize.COMPACT, session.id))
        nav.openBrowser()
        nav.returnToChat(null)
        assertTrue(nav.browserVisible(DesktopShellSize.COMPACT, null))
    }

    @Test
    fun `wide default rail is not compact intent but explicit wide toggles are`() {
        val nav = DesktopCompactChatNavigation()
        val browser = DesktopPrimaryChatBrowserState()
        nav.onWideChatDisplayed(DesktopShellSize.WIDE, "a")
        assertTrue(browser.browserVisible(DesktopShellSize.WIDE, false))
        assertFalse(nav.browserRequested)
        assertFalse(nav.browserVisible(DesktopShellSize.COMPACT, "a"))
        nav.openBrowser()
        assertTrue(nav.browserVisible(DesktopShellSize.COMPACT, "a"))
        nav.onWideBrowserToggled(browser.toggleWideBrowser().wideBrowserExpanded)
        assertFalse(nav.browserVisible(DesktopShellSize.COMPACT, "a"))
        nav.onWideBrowserToggled(browser.wideBrowserExpanded)
        assertTrue(nav.browserVisible(DesktopShellSize.COMPACT, "a"))
    }
}
