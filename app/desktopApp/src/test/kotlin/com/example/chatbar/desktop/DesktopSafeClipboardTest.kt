@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.example.chatbar.desktop

import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.asAwtTransferable
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DesktopSafeClipboardTest {
    private class FakeClipboard(var busy: Int = 0, var error: Exception? = null) : Clipboard {
        var calls = 0
        val copied = mutableListOf<String>()
        override val nativeClipboard: Any get() = error("Selectable output must not bypass the safe suspend seam")
        override suspend fun getClipEntry(): ClipEntry? { checkAccess(); return null }
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            checkAccess()
            copied += clipEntry!!.asAwtTransferable!!.getTransferData(DataFlavor.stringFlavor) as String
        }
        private fun checkAccess() {
            calls++
            error?.let { throw it }
            if (busy-- > 0) throw IllegalStateException("cannot open system clipboard")
        }
    }

    @Test fun `busy once or twice retries and copies exact Unicode text`() = runTest {
        for (busy in 1..2) {
            val platform = FakeClipboard(busy)
            val waits = mutableListOf<Long>()
            val clipboard = DesktopSafeClipboard(platform) { waits += it }
            assertEquals(DesktopClipboardResult.COPIED, clipboard.copyText("精确\n**raw** 😀"))
            assertEquals(listOf("精确\n**raw** 😀"), platform.copied)
            assertEquals(busy + 1, platform.calls)
            assertEquals(listOf(20L, 40L).take(busy), waits)
            assertFalse(clipboard.unavailable)
        }
    }

    @Test fun `persistent Windows contention terminates with controlled failure`() = runTest {
        val platform = FakeClipboard(Int.MAX_VALUE)
        val clipboard = DesktopSafeClipboard(platform)
        assertEquals(DesktopClipboardResult.UNAVAILABLE, clipboard.copyText("unchanged"))
        assertEquals(4, platform.calls)
        assertEquals(140, testScheduler.currentTime)
        assertTrue(platform.copied.isEmpty())
        assertTrue(clipboard.unavailable)
    }

    @Test fun `unrelated illegal state and other exceptions are neither retried nor swallowed`() = runTest {
        for (error in listOf(IllegalStateException("broken invariant"), IllegalArgumentException("invalid"))) {
            val platform = FakeClipboard(error = error)
            val clipboard = DesktopSafeClipboard(platform) { fail("Must not retry unrelated errors") }
            assertSame(error, assertFails { clipboard.copyText("text") })
            assertEquals(1, platform.calls)
        }
    }

    @Test fun `Compose selected copy and Ctrl C clip entry seam preserves original transferable`() = runTest {
        val platform = FakeClipboard(2)
        val clipboard: Clipboard = DesktopSafeClipboard(platform)
        val selected = ClipEntry(StringSelection("only selected characters"))
        clipboard.setClipEntry(selected)
        assertEquals(listOf("only selected characters"), platform.copied)
        platform.busy = Int.MAX_VALUE
        clipboard.setClipEntry(selected) // Unit-returning Compose API must not leak contention.
        assertTrue((clipboard as DesktopSafeClipboard).unavailable)
    }

    @Test fun `success clears nonfatal failure status`() = runTest {
        val platform = FakeClipboard(Int.MAX_VALUE)
        val clipboard = DesktopSafeClipboard(platform)
        clipboard.copyText("first")
        assertTrue(clipboard.unavailable)
        platform.busy = 0
        assertEquals(DesktopClipboardResult.COPIED, clipboard.copyText("retry"))
        assertFalse(clipboard.unavailable)
    }

    @Test fun `concurrent copy requests retain order across suspended retry`() = runTest {
        val platform = FakeClipboard(1)
        val clipboard = DesktopSafeClipboard(platform)
        val first = async { clipboard.copyText("first") }
        val second = async { clipboard.copyText("second") }
        first.await(); second.await()
        assertEquals(listOf("first", "second"), platform.copied)
    }

    @Test fun `clipboard read contention is also bounded`() = runTest {
        val platform = FakeClipboard(Int.MAX_VALUE)
        val clipboard = DesktopSafeClipboard(platform)
        assertNull(clipboard.getClipEntry())
        assertTrue(clipboard.unavailable)
        assertEquals(4, platform.calls)
    }

    @Test fun `cancelled retries propagate cancellation without further clipboard writes`() = runTest {
        val platform = FakeClipboard(Int.MAX_VALUE)
        val clipboard = DesktopSafeClipboard(platform)
        val copy = async { clipboard.copyText("cancel") }
        delay(10)
        copy.cancel()
        copy.join()
        assertEquals(1, platform.calls)
    }

    @Test fun `chat wiring provides safe Compose clipboard and app copies without competing text areas`() {
        // Architecture check for Compose call sites that cannot be exercised by a pure unit test.
        val source = Files.readString(Path.of("src/main/kotlin/com/example/chatbar/desktop/DesktopPrimaryChatPanel.kt"))
        val timeline = source.substringAfter("private fun PrimaryTimeline(").substringBefore("private fun PrimaryAvatar(")
        assertFalse(timeline.contains("ContextMenuArea("))
        assertFalse(source.contains("LocalClipboardManager"))
        assertTrue(source.contains("CompositionLocalProvider(LocalClipboard provides clipboard)"))
        assertTrue(timeline.contains("clipboard.copyText(presented.copyText)"))
        assertTrue(timeline.contains("clipboard.copyText(segment.text)"))
        assertTrue(timeline.contains("ContextMenuDataProvider(items ="))
        assertTrue(timeline.contains("EDIT_SEGMENT"))
        assertTrue(timeline.contains("DELETE_SEGMENT"))
        assertTrue(timeline.contains("if (expansion.expanded) SelectionContainer"))
        assertEquals(2, Regex("""SelectionContainer\s*\{\s*DesktopMarkdownText\(segment.text, typography = DesktopChatTypography\(state.chatBubbleFontScale\)\)""")
            .findAll(timeline).count())
        assertTrue(timeline.contains("DesktopMarkdownText(reasoning, colors.mutedForeground, DesktopChatTypography(state.chatBubbleFontScale))"))
        // Persisted and live replies share the same state-bearing bubble path.
        assertTrue(timeline.contains("PrimaryMessageBubble(message, state, controller, clipboard"))
        assertTrue(timeline.contains("PrimaryMessageBubble(streamingMessage, state, controller, clipboard)"))
        assertTrue(source.contains("CLIPBOARD_UNAVAILABLE"))
    }
}
