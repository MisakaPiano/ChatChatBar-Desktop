package com.example.chatbar.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.win32.StdCallLibrary
import kotlin.test.*

/** Native Windows clipboard contract with one private message-only window and synthetic text only. */
class DesktopLanSyncSensitiveClipboardTest {
    private interface Clip : StdCallLibrary {
        fun OpenClipboard(owner: Pointer): Boolean
        fun CloseClipboard(): Boolean
        fun EmptyClipboard(): Boolean
        fun GetClipboardData(format: Int): Pointer?
        fun GetClipboardOwner(): Pointer?
        fun RegisterClipboardFormatW(name: WString): Int
        fun IsClipboardFormatAvailable(format: Int): Boolean
        fun GetDesktopWindow(): Pointer
    }
    private interface Heap : StdCallLibrary {
        fun GlobalLock(memory: Pointer): Pointer?
        fun GlobalUnlock(memory: Pointer): Boolean
    }
    @Test fun `sensitive native copy publishes exact Unicode plus exclusion marker and refuses a foreign owner`() {
        org.junit.Assume.assumeTrue(System.getenv("CCB_LAN_SENSITIVE_CLIPBOARD_PROOF") == "true")
        assertEquals("true", System.getenv("GITHUB_ACTIONS"))
        assertTrue(System.getProperty("os.name").startsWith("Windows"))
        val clip = Native.load("user32", Clip::class.java); val heap = Native.load("kernel32", Heap::class.java)
        val window = requireNotNull(User32.INSTANCE.CreateWindowEx(0, "STATIC", "CCB synthetic clipboard owner", 0,
            0, 0, 0, 0, HWND(Pointer.createConstant(-3L)), null, null, null))
        try {
            val text = "synthetic-恢复码-\uD83D\uDD10-abcdef0123456789"
            assertTrue(DesktopLanSyncSensitiveClipboard.copyOwned(text, window.pointer))
            assertTrue(clip.OpenClipboard(window.pointer))
            try {
                val flag = clip.RegisterClipboardFormatW(WString(DesktopLanSyncSensitiveClipboard.EXCLUSION_FORMAT))
                assertTrue(flag != 0 && clip.IsClipboardFormatAvailable(flag))
                assertTrue(clip.IsClipboardFormatAvailable(13))
                val handle = requireNotNull(clip.GetClipboardData(13)); val pointer = requireNotNull(heap.GlobalLock(handle))
                try { assertEquals(text, pointer.getWideString(0)) } finally { heap.GlobalUnlock(handle) }
                assertNotNull(clip.GetClipboardData(flag))
            } finally { assertTrue(clip.CloseClipboard()) }
            assertFalse(DesktopLanSyncSensitiveClipboard.copyOwned("synthetic-must-not-publish", clip.GetDesktopWindow()))
        } finally {
            // Never clear a clipboard subsequently taken by any other process/window.
            if (clip.OpenClipboard(window.pointer)) {
                try {
                    if (clip.GetClipboardOwner()?.let { Pointer.nativeValue(it) } == Pointer.nativeValue(window.pointer)) {
                        assertTrue(clip.EmptyClipboard())
                    }
                } finally { assertTrue(clip.CloseClipboard()) }
            }
            assertTrue(User32.INSTANCE.DestroyWindow(window))
        }
    }
}
