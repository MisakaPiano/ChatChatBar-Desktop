package com.example.chatbar.desktop

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.*

class DesktopResponsiveChatLayoutTest {
    @Test fun `external requires full rail and gap spare without reducing normal readable width`() {
        for (width in listOf(940f, 1000f, 1600f)) {
            val layout = DesktopChatNavigationPlacementPolicy.resolve(width)
            assertEquals(DesktopChatNavigationPlacement.EXTERNAL_RAIL, layout.placement)
            assertEquals(DesktopChatReadingWidth.forAvailableWidth(width), layout.contentWidthDp)
            assertEquals(60f, layout.railAllowanceDp)
            assertTrue(layout.workspaceWidthDp <= width)
        }
    }

    @Test fun `exact content and insufficient spare stay overlay without overflow`() {
        for (width in listOf(0f, 280f, 520f, 880f, 939f)) {
            val layout = DesktopChatNavigationPlacementPolicy.resolve(width)
            assertEquals(DesktopChatNavigationPlacement.OVERLAY, layout.placement)
            assertEquals(0f, layout.railAllowanceDp)
            assertEquals(DesktopChatReadingWidth.forAvailableWidth(width), layout.contentWidthDp)
            assertTrue(layout.workspaceWidthDp <= width)
        }
    }

    @Test fun `same window changes placement when 280dp sidebar collapses`() {
        val width = 1200f - 32f // Existing workspace padding.
        assertFalse(DesktopChatNavigationPlacementPolicy.resolve(width - 280f).external)
        assertTrue(DesktopChatNavigationPlacementPolicy.resolve(width).external)
        assertEquals(880f, DesktopChatNavigationPlacementPolicy.resolve(width - 280f).contentWidthDp)
        assertEquals(880f, DesktopChatNavigationPlacementPolicy.resolve(width).contentWidthDp)
    }

    @Test fun `both placements keep earlier and later groups separate`() {
        for (width in listOf(400f, 1000f)) {
            DesktopChatNavigationPlacementPolicy.resolve(width)
            val groups = desktopChatNavigationGroups(true, true, true)
            assertEquals(listOf(DesktopChatJump.PREVIOUS, DesktopChatJump.FIRST), groups.earlier)
            assertEquals(listOf(DesktopChatJump.NEXT, DesktopChatJump.BOTTOM), groups.later)
        }
    }

    @Test fun `grouping preserves E1B visibility for first middle tall last and bottom`() {
        assertEquals(emptyList(), desktopChatNavigationGroups(false, true, true).earlier)
        assertEquals(listOf(DesktopChatJump.NEXT, DesktopChatJump.BOTTOM), desktopChatNavigationGroups(false, true, true).later)
        assertEquals(listOf(DesktopChatJump.BOTTOM), desktopChatNavigationGroups(true, false, true).later)
        assertTrue(desktopChatNavigationGroups(true, false, false).later.isEmpty())
        assertEquals(listOf(DesktopChatJump.PREVIOUS, DesktopChatJump.FIRST), desktopChatNavigationGroups(true, false, false).earlier)
    }

    @Test fun `default is bounded and supports several lines`() {
        assertEquals(96f, DesktopComposerLayoutState().heightDp)
        assertTrue(DesktopComposerHeightPolicy.DEFAULT_DP in DesktopComposerHeightPolicy.MIN_DP..DesktopComposerHeightPolicy.maximum(600f))
        assertFalse(DesktopComposerHeightPolicy.collapsed(96f))
    }

    @Test fun `drag up grows continuously and drag down shrinks`() {
        val layout = DesktopComposerLayoutState()
        layout.drag(-20f, 600f)
        assertEquals(116f, layout.heightDp)
        layout.drag(-0.5f, 600f)
        assertEquals(116.5f, layout.heightDp)
        layout.drag(30f, 600f)
        assertEquals(86.5f, layout.heightDp)
    }

    @Test fun `drag clamps minimum and maximum without eating entire workspace`() {
        val layout = DesktopComposerLayoutState()
        layout.drag(1000f, 600f)
        assertEquals(48f, layout.heightDp)
        assertTrue(DesktopComposerHeightPolicy.collapsed(layout.heightDp))
        layout.drag(-1000f, 600f)
        assertEquals(252f, layout.heightDp, absoluteTolerance = 0.001f)
        assertFalse(DesktopComposerHeightPolicy.collapsed(layout.heightDp))
        layout.drag(-1000f, 2000f)
        assertEquals(360f, layout.heightDp)
    }

    @Test fun `shrinking workspace clamps preference and growing does not jump back`() {
        val layout = DesktopComposerLayoutState()
        layout.drag(-200f, 1000f)
        layout.clamp(200f)
        assertEquals(84f, layout.heightDp)
        layout.clamp(1000f)
        assertEquals(84f, layout.heightDp)
        layout.clamp(100f)
        assertEquals(48f, layout.heightDp)
    }

    @Test fun `resize state never rewrites multiline selection composition or session drafts`() {
        val drafts = mapOf("A" to TextFieldValue("line1\nline2\nline3", TextRange(3, 8), TextRange(1, 3)),
            "B" to TextFieldValue("other\nmessage"))
        val layout = DesktopComposerLayoutState()
        val input = drafts.getValue("A")
        layout.drag(1000f, 800f)
        assertEquals(48f, layout.heightDp)
        assertSame(input, drafts.getValue("A"))
        assertEquals(TextRange(1, 3), drafts.getValue("A").composition)
        // Session choice has no ownership of this shell preference.
        assertEquals("other\nmessage", drafts.getValue("B").text)
        assertEquals(48f, layout.heightDp)
        layout.drag(-50f, 800f)
        assertEquals(98f, layout.heightDp)
        assertEquals("line1\nline2\nline3", input.text)
    }
}
