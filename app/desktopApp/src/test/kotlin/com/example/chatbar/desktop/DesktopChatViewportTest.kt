package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ChatScrollPosition
import kotlin.test.*

class DesktopChatViewportTest {
    private val ids = listOf("a", "b", "c")
    private fun item(index: Int, key: String, offset: Int = 0, size: Int = 60) =
        DesktopVisibleTimelineItem(index, key, offset, size)
    private fun position(anchor: String = "b", fallback: Int = 0, offset: Int = 27) =
        ChatScrollPosition("session", anchor, fallback, offset, 1)

    @Test fun `message and lazy indices round trip with or without older row`() {
        for (older in listOf(false, true)) {
            val map = DesktopChatTimelineMapping(ids, hasOlder = older)
            ids.indices.forEach { index ->
                assertEquals(index + if (older) 1 else 0, map.messageToLazy(index))
                assertEquals(index, map.lazyToMessage(map.messageToLazy(index)!!))
            }
            if (older) assertNull(map.lazyToMessage(0))
        }
    }
    @Test fun `hidden regeneration target does not shift source message indices`() {
        val map = DesktopChatTimelineMapping(ids, listOf("a", "c"), hasOlder = true)
        assertNull(map.messageToLazy(1))
        assertEquals(2, map.lazyToMessage(2))
        assertEquals(DesktopScrollTarget(2, 27), map.initialTarget(position()))
    }
    @Test fun `synthetic older row captures first actual visible message with its own offset`() {
        val map = DesktopChatTimelineMapping(ids, hasOlder = true)
        val saved = map.captureStable("session", listOf(item(0, "older", -20), item(1, "a", 40)), 0, true, false, false)
        assertEquals("a", saved?.anchorMessageId)
        assertEquals(0, saved?.scrollOffset)
        assertNull(map.captureStable("session", listOf(item(0, "older")), 0, true, false, false))
    }
    @Test fun `stream and newer rows can never be persisted anchors`() {
        val map = DesktopChatTimelineMapping(ids, hasNewer = true, streamKey = "stream")
        assertNull(map.lazyToMessage(3))
        assertNull(map.lazyToMessage(4))
        assertNull(map.captureStable("session", listOf(item(4, "stream")), 0, true, false, false))
        val saved = map.captureStable("session", listOf(item(2, "c", -17), item(4, "stream", 43)), 0, true, false, false)
        assertEquals("c", saved?.anchorMessageId)
        assertEquals(17, saved?.scrollOffset)
    }
    @Test fun `stable capture is gated by initial restore and scrolling`() {
        val map = DesktopChatTimelineMapping(ids)
        val visible = listOf(item(1, "b", -12))
        assertNull(map.captureStable("s", visible, 0, false, false, false))
        assertNull(map.captureStable("s", visible, 0, true, true, false))
        assertNull(map.captureStable("s", visible, 0, true, false, true))
        assertEquals(12, map.captureStable("s", visible, 0, true, false, false)?.scrollOffset)
    }
    @Test fun `no saved position targets visual bottom including stream`() {
        assertEquals(DesktopScrollTarget(4), DesktopChatTimelineMapping(ids, hasOlder = true, streamKey = "stream").initialTarget(null))
        assertNull(DesktopChatTimelineMapping(emptyList()).initialTarget(null))
    }
    @Test fun `saved anchor offset maps past older row`() {
        assertEquals(DesktopScrollTarget(2, 27), DesktopChatTimelineMapping(ids, hasOlder = true).initialTarget(position()))
    }
    @Test fun `deleted anchor uses formal bounded fallback and accepts a new valid anchor`() {
        val map = DesktopChatTimelineMapping(ids, hasOlder = true)
        assertEquals(DesktopScrollTarget(3, 27), map.initialTarget(position("deleted", 999)))
        assertEquals(DesktopScrollTarget(1, 27), map.initialTarget(position("deleted", -1)))
        assertEquals("c", map.captureStable("session", listOf(item(3, "c")), 0, true, false, false)?.anchorMessageId)
    }
    @Test fun `previous within loaded window skips synthetic rows`() {
        val map = DesktopChatTimelineMapping(ids, hasOlder = true)
        assertEquals(2, map.previousTarget(listOf(item(3, "c"))))
        assertEquals(1, map.previousTarget(listOf(item(1, "a"))))
    }
    @Test fun `partial message goes to preceding real message or start of first`() {
        val map = DesktopChatTimelineMapping(ids)
        assertEquals(0, map.previousTarget(listOf(item(1, "b", -10))))
        assertEquals(0, map.previousTarget(listOf(item(0, "a", -10))))
    }
    @Test fun `previous from stream only targets last real message`() {
        assertEquals(2, DesktopChatTimelineMapping(ids, streamKey = "stream").previousTarget(listOf(item(3, "stream"))))
    }
    @Test fun `old window is never latest bottom even if last item fits`() {
        assertFalse(DesktopChatTimelineMapping(ids, hasNewer = true).isAtBottom(listOf(item(3, "newer")), 100))
    }
    @Test fun `latest bottom includes stream and oversized last content`() {
        val map = DesktopChatTimelineMapping(ids, streamKey = "stream")
        assertFalse(map.isAtBottom(listOf(item(2, "c")), 100))
        assertFalse(map.isAtBottom(listOf(item(3, "stream", 0, 150)), 100))
        assertTrue(map.isAtBottom(listOf(item(3, "stream", 0, 116)), 100))
    }
    @Test fun `earlier affordances are meaningful for partial first and older pages only`() {
        val map = DesktopChatTimelineMapping(ids)
        assertFalse(map.canJumpEarlier(listOf(item(0, "a")), 0))
        assertTrue(map.canJumpEarlier(listOf(item(0, "a", -1)), 0))
        assertTrue(map.canJumpEarlier(listOf(item(1, "b")), 0))
        assertTrue(DesktopChatTimelineMapping(ids, hasOlder = true).canJumpEarlier(listOf(item(0, "older")), 0))
        assertFalse(DesktopChatTimelineMapping(emptyList()).canJumpEarlier(emptyList(), 0))
        assertTrue(DesktopChatTimelineMapping(emptyList()).isAtBottom(emptyList(), 0))
        assertTrue(DesktopUiText.PREVIOUS_MESSAGE.zhCn.isNotBlank())
        assertEquals("Jump to bottom", DesktopUiText.JUMP_BOTTOM.en)
        assertEquals("First message", DesktopUiText.FIRST_MESSAGE.en)
    }
    @Test fun `historical reader never follows streaming growth`() {
        assertFalse(desktopShouldFollowBottom(true, false, false, false, false))
        assertFalse(desktopShouldFollowBottom(true, false, true, false, true))
    }
    @Test fun `bottom follows only after restore and when idle`() {
        assertTrue(desktopShouldFollowBottom(true, false, true, false, false))
        assertFalse(desktopShouldFollowBottom(false, false, true, false, false))
        assertFalse(desktopShouldFollowBottom(true, true, true, false, false))
        assertFalse(desktopShouldFollowBottom(true, false, true, true, false))
    }
    @Test fun `stale layout from replaced page must not be captured`() {
        val map = DesktopChatTimelineMapping(ids)
        assertFalse(map.matches(listOf(item(0, "other-session")), 3))
        assertFalse(map.matches(listOf(item(0, "a")), 4))
        assertTrue(map.matches(listOf(item(0, "a")), 3))
    }
}
