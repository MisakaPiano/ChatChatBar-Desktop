package com.example.chatbar.desktop

import kotlin.test.*

class DesktopAutomaticChatLayoutTest {
    @Test fun `automatic growth fills conversation before names status and exterior navigation`() {
        val growing = listOf(650f, 800f, 944f).map { desktopChatWorkspaceLayout(it, true) }
        assertEquals(listOf(64f, 64f, 64f), growing.map { it.peopleWidthDp })
        assertEquals(listOf(586f, 736f, 880f), growing.map { it.timeline.contentWidthDp })
        val names = desktopChatWorkspaceLayout(1060f, true)
        assertEquals(880f, names.timeline.contentWidthDp); assertTrue(names.names)
        val status = desktopChatWorkspaceLayout(1240f, true)
        assertEquals(360f, status.peopleWidthDp); assertFalse(status.timeline.external)
        val navigation = desktopChatWorkspaceLayout(1300f, true)
        assertTrue(navigation.timeline.external); assertEquals(60f, navigation.timeline.railAllowanceDp)
        assertEquals(navigation, desktopChatWorkspaceLayout(1800f, true))
    }

    @Test fun `manual width is temporarily constrained without changing the requested preference`() {
        val requested = 400f
        assertEquals(400f, desktopChatWorkspaceLayout(1500f, true, requested).peopleWidthDp)
        val squeezed = desktopChatWorkspaceLayout(700f, true, requested)
        assertEquals(220f, squeezed.peopleWidthDp); assertEquals(480f, squeezed.timeline.contentWidthDp)
        assertEquals(400f, desktopChatWorkspaceLayout(1500f, true, requested).peopleWidthDp)
        val hidden = desktopChatWorkspaceLayout(1500f, true, requested, peopleHidden = true)
        assertEquals(0f, hidden.peopleWidthDp)
        assertEquals(DesktopChatNavigationPlacementPolicy.resolve(1500f), hidden.timeline)
    }

    @Test fun `automatic collapse has independent hysteresis and does not represent a manual preference`() {
        assertTrue(DesktopAutomaticChatColumns.sessionsHidden(979f, false))
        assertTrue(DesktopAutomaticChatColumns.sessionsHidden(1000f, true))
        assertFalse(DesktopAutomaticChatColumns.sessionsHidden(1044f, true))
        assertFalse(DesktopAutomaticChatColumns.peopleHidden(650f, false))
        assertTrue(DesktopAutomaticChatColumns.peopleHidden(599f, false))
        assertTrue(DesktopAutomaticChatColumns.peopleHidden(630f, true))
        assertFalse(DesktopAutomaticChatColumns.peopleHidden(664f, true))
        assertEquals(0f, desktopChatWorkspaceLayout(1800f, true, peopleHidden = true).peopleWidthDp)
    }
}
