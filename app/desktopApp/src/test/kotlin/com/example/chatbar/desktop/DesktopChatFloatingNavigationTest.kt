package com.example.chatbar.desktop

import com.composables.icons.lucide.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DesktopChatFloatingNavigationTest {
    private val ids = listOf("a", "b", "c")
    private fun item(index: Int, key: String, size: Int = 200) = DesktopVisibleTimelineItem(index, key, 0, size)

    @Test fun `first top hides earlier but retains Next and Bottom`() {
        val map = DesktopChatTimelineMapping(ids)
        val items = listOf(item(0, "a"))
        assertFalse(map.canJumpEarlier(items, 0))
        assertTrue(map.canJumpLater(items, 100, "a"))
        assertFalse(map.isAtBottom(items, 100))
    }

    @Test fun `middle offers all directions and historical first keeps earlier`() {
        val map = DesktopChatTimelineMapping(ids)
        val items = listOf(item(1, "b"))
        assertTrue(map.canJumpEarlier(items, 0))
        assertTrue(map.canJumpLater(items, 100, "b"))
        assertFalse(map.isAtBottom(items, 100))
        val historical = DesktopChatTimelineMapping(ids, hasOlder = true, hasNewer = true)
        assertTrue(historical.canJumpEarlier(listOf(item(1, "a")), 0))
        assertTrue(historical.canJumpLater(listOf(item(3, "c")), 100, "c"))
    }

    @Test fun `tall last real has Bottom but not Next while true bottom hides both`() {
        val map = DesktopChatTimelineMapping(ids)
        val tall = listOf(item(2, "c"))
        assertTrue(map.canJumpEarlier(tall, 0))
        assertFalse(map.canJumpLater(tall, 100, "c"))
        assertFalse(map.isAtBottom(tall, 100))
        val bottom = listOf(item(2, "c", 100))
        assertFalse(map.canJumpLater(bottom, 100, "c"))
        assertTrue(map.isAtBottom(bottom, 100))
        // Even when a short preceding message is visible, visual bottom has no Next action.
        val shortBottom = listOf(item(1, "b", 40), DesktopVisibleTimelineItem(2, "c", 40, 40))
        assertFalse(map.canJumpLater(shortBottom, 100, "b"))
    }

    @Test fun `Next skips hidden real rows and never targets synthetic or streaming rows`() {
        val map = DesktopChatTimelineMapping(ids, listOf("a", "c"), hasOlder = true, hasNewer = true, streamKey = "stream")
        assertEquals(map.keys.indexOf("c"), map.nextTarget("a"))
        assertNull(map.nextTarget("c"))
        assertNull(map.nextTarget("newer"))
        assertNull(map.nextTarget("stream"))
        assertEquals("c", map.nextAnchor(listOf(item(map.keys.indexOf("newer"), "newer")), "a"))
        assertEquals("c", map.nextAnchor(listOf(item(map.keys.lastIndex, "stream")), "a"))
        assertEquals("a", map.nextAnchor(listOf(item(0, "older")), "a"))
        assertFalse(DesktopChatTimelineMapping(emptyList(), streamKey = "stream")
            .canJumpLater(listOf(item(0, "stream")), 100, null))
    }

    @Test fun `latest stream is Bottom-only never Next after final real message`() {
        val map = DesktopChatTimelineMapping(ids, streamKey = "stream")
        for (visible in listOf(item(2, "c"), item(3, "stream"))) {
            assertFalse(map.canJumpLater(listOf(visible), 100, "b"))
            assertFalse(map.isAtBottom(listOf(visible), 100))
        }
    }

    @Test fun `navigation icons are vertical while alternative icons stay horizontal`() {
        assertSame(Lucide.ArrowUp, DesktopAppIcons.MessagePrevious)
        assertSame(Lucide.ArrowDown, DesktopAppIcons.MessageNext)
        assertSame(Lucide.ArrowUpToLine, DesktopAppIcons.MessageFirst)
        assertSame(Lucide.ArrowDownToLine, DesktopAppIcons.JumpBottom)
        assertSame(Lucide.ChevronLeft, DesktopAppIcons.Previous)
        assertSame(Lucide.ChevronRight, DesktopAppIcons.Next)
        val alternatives = source("DesktopChatControls.kt").substringAfter("fun DesktopChatAlternativeControls(")
            .substringBefore("fun DesktopChatMessageToolbar(")
        assertTrue(alternatives.contains("DesktopAppIcons.Previous"))
        assertTrue(alternatives.contains("DesktopAppIcons.Next"))
    }

    @Test fun `timeline is Box overlay with full sized list and two floating surfaces`() {
        val timeline = source("DesktopPrimaryChatPanel.kt").substringAfter("private fun PrimaryTimeline(")
            .substringBefore("private fun PrimaryMessageBubble(")
        assertTrue(timeline.contains("Box(modifier.fillMaxWidth())"))
        assertTrue(timeline.contains("Modifier.width(placement.contentWidthDp.dp).fillMaxHeight().alpha"))
        assertFalse(timeline.contains("Modifier.weight("))
        assertFalse(timeline.contains("Column(modifier"))
        assertEquals(2, Regex("DesktopChatNavigationGroup\\(").findAll(timeline).count())
        assertTrue(timeline.contains("Alignment.TopEnd"))
        assertTrue(timeline.contains("Alignment.BottomEnd"))
        assertTrue(timeline.indexOf("LazyColumn(") < timeline.indexOf("DesktopChatNavigationGroup("))
        val controls = source("DesktopChatControls.kt")
        for (icon in listOf("MessagePrevious", "MessageNext", "MessageFirst", "JumpBottom")) {
            assertTrue(controls.contains("DesktopAppIcons.$icon"))
        }
        assertTrue(controls.contains("targetDp = 48) { onNavigate(action) }"))
        assertTrue(timeline.contains("desktopChatNavigationGroups(viewport.canEarlier(), viewport.canLater(state.messageWindowAnchorId)"))
        assertTrue(timeline.contains("scope.launch { viewport.navigate(controller, it) }"))
        assertTrue(timeline.contains("!viewport.atBottom()"))
        assertTrue(timeline.contains("DesktopUiText.LOAD_OLDER"))
        assertTrue(timeline.contains("DesktopUiText.LOAD_NEWER"))
        assertTrue(source("DesktopChatControls.kt").contains("card.copy(alpha = 0.48f)"))
        assertTrue(source("DesktopChatControls.kt").contains("border.copy(alpha = 0.46f)"))
    }

    private fun source(name: String) = Files.readString(Path.of("src/main/kotlin/com/example/chatbar/desktop", name))
}
