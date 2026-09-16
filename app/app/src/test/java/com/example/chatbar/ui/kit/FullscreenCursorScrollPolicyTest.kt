package com.example.chatbar.ui.kit

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FullscreenCursorScrollPolicyTest {
    @Test
    fun visibleCursorKeepsSurroundingTextInPlace() {
        assertNull(fullscreenCursorScrollTarget(240f, 260f, 100, 400, 900, true, TextRange(12)))
        assertNull(fullscreenCursorScrollTarget(100f, 120f, 100, 400, 900, true, TextRange(12)))
        assertNull(fullscreenCursorScrollTarget(480f, 500f, 100, 400, 900, true, TextRange(12)))
    }

    @Test
    fun obscuredCursorScrollsOnlyEnoughToBecomeVisible() {
        assertEquals(80, fullscreenCursorScrollTarget(80f, 100f, 200, 400, 900, true, TextRange(12)))
        assertEquals(221, fullscreenCursorScrollTarget(600f, 620.4f, 100, 400, 900, true, TextRange(12)))
    }

    @Test
    fun firstLineAndEndOfDocumentAreClamped() {
        assertEquals(0, fullscreenCursorScrollTarget(-4f, 16f, 0, 400, 900, true, TextRange(0)))
        assertEquals(900, fullscreenCursorScrollTarget(1_400f, 1_420f, 0, 400, 900, true, TextRange(80)))
    }

    @Test
    fun hiddenImeDoesNotMoveViewport() {
        assertNull(fullscreenCursorScrollTarget(600f, 620f, 0, 400, 900, false, TextRange(12)))
    }

    @Test
    fun expandedSelectionLeavesScrollingToTextField() {
        assertNull(fullscreenCursorScrollTarget(600f, 620f, 0, 400, 900, true, TextRange(4, 12)))
    }

    @Test
    fun unmeasuredViewportDoesNotMoveCursor() {
        assertNull(fullscreenCursorScrollTarget(240f, 260f, 0, 0, 900, true, TextRange(12)))
    }
}
