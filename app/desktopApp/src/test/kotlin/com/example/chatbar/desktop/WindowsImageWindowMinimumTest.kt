@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.*
import java.awt.Dimension
import java.awt.EventQueue
import kotlin.test.*

/** Hidden app-owned HWNDs only: no focus acquisition, mouse automation or desktop capture. */
class WindowsImageWindowMinimumTest {
    @Test fun `owned image dialog keeps its original minimum through native chrome and unhooks cleanly`() {
        requireDesktopGraphicsForNativeTest()
        org.junit.Assume.assumeTrue(DesktopWindowChrome.isWindows)
        EventQueue.invokeAndWait {
            val parent = ComposeWindow()
            val child = ComposeDialog(parent).apply {
                isUndecorated = true; minimumSize = Dimension(320, 360); setSize(560, 480); addNotify()
            }
            var native: WindowsWindowChrome? = null
            try {
                val handle = HWND(Native.getWindowPointer(child))
                val previous = User32.INSTANCE.GetWindowLongPtr(handle, -4).toLong()
                native = WindowsWindowChrome.install(child, DesktopWindowChrome {})
                assertEquals(Dimension(330, 360), child.minimumSize)
                Memory(40).use { info ->
                    info.clear()
                    User32.INSTANCE.SendMessage(handle, 0x24, WPARAM(0), LPARAM(Pointer.nativeValue(info)))
                    val dpi = Native.load("user32", ChromeUser32::class.java).GetDpiForWindow(handle)
                    assertEquals(330 * dpi / 96, info.getInt(24))
                    assertEquals(360 * dpi / 96, info.getInt(28))
                }
                native.close()
                assertEquals(Dimension(320, 360), child.minimumSize)
                assertEquals(previous, User32.INSTANCE.GetWindowLongPtr(handle, -4).toLong())
                assertFalse(child.isVisible); assertFalse(parent.isVisible)
            } finally { native?.close(); child.dispose(); parent.dispose() }
        }
    }
}
