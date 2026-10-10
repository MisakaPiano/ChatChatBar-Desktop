package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.*
import java.awt.EventQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import org.junit.Assume.assumeTrue

class WindowsWindowChromeTest {
    interface MenuApi : com.sun.jna.win32.StdCallLibrary {
        fun GetSystemMenu(hwnd: HWND, revert: Boolean): HMENU?
    }
    @Test fun `real Compose HWND preserves native contracts and unhooks on dispose`() {
        assumeTrue(DesktopWindowChrome.isWindows)
        val user = User32.INSTANCE
        val closes = AtomicInteger()
        val measured = CountDownLatch(1)
        val chrome = DesktopWindowChrome { closes.incrementAndGet() }
        lateinit var window: ComposeWindow
        lateinit var native: WindowsWindowChrome
        var created = false
        var installed = false
        var originalProc: Long = 0
        var originalStyle = 0
        try {
            EventQueue.invokeAndWait {
                window = ComposeWindow().apply {
                    isUndecorated = true
                    setSize(800, 600)
                    setLocation(100, 100)
                    addNotify()
                }
                created = true
                val h = HWND(Native.getWindowPointer(window))
                originalProc = user.GetWindowLongPtr(h, -4).toLong()
                originalStyle = user.GetWindowLong(h, -16)
                native = WindowsWindowChrome.install(window, chrome)
                installed = true
                chrome.platform = native
                val recorder = DesktopChromeLayoutRecorder(chrome)
                window.setContent {
                    Column(Modifier.fillMaxSize().onGloballyPositioned(recorder::root)) {
                        DesktopTitleBar(DesktopShellSize.MEDIUM, DesktopPrimaryRoute.CHAT, false, chrome, recorder) {}
                        Box(Modifier.fillMaxSize().onGloballyPositioned { measured.countDown() })
                    }
                }
                window.isVisible = true
            }
            assertTrue(measured.await(30, TimeUnit.SECONDS), "Compose layout handshake")
            EventQueue.invokeAndWait { native.refreshChildren() }
            val h = native.hwnd
            assertEquals(WindowsWindowChrome.NATIVE_STYLES, user.GetWindowLong(h, -16) and WindowsWindowChrome.NATIVE_STYLES)
            assertEquals(0, user.GetWindowLong(h, -16) and Int.MIN_VALUE, "main window is overlapped, not WS_POPUP")
            assertTrue(native.installedHookCount >= 2, "Compose rendering child is hooked too")
            val rect = RECT(); val client = RECT()
            assertTrue(user.GetWindowRect(h, rect)); assertTrue(user.GetClientRect(h, client))
            assertEquals(rect.right - rect.left, client.right)
            assertEquals(rect.bottom - rect.top, client.bottom)
            val max = assertNotNull(chrome.layout.captions[ChromeHit.MAXIMIZE])
            val point = (rect.left + ((max.left + max.right) / 2).toInt()) to (rect.top + 20)
            val packed = ((point.second.toLong() and 0xffff) shl 16) or (point.first.toLong() and 0xffff)
            assertEquals(9, user.SendMessage(h, 0x84, WPARAM(0), LPARAM(packed)).toInt())
            val childHits = mutableListOf<Int>()
            user.EnumChildWindows(h, { child, _ ->
                childHits += user.SendMessage(child, 0x84, WPARAM(0), LPARAM(packed)).toInt()
                true
            }, null)
            assertTrue(childHits.isNotEmpty())
            assertTrue(childHits.all { it == -1 }, "rendering children return HTTRANSPARENT for native caption input")
            user.SendMessage(h, 0xA0, WPARAM(9), LPARAM(packed))
            assertEquals(ChromeHit.MAXIMIZE, chrome.state.value.hovered)
            user.SendMessage(h, 0x2A2, WPARAM(0), LPARAM(0))
            assertNull(chrome.state.value.hovered)
            assertNotNull(Native.load("user32", MenuApi::class.java).GetSystemMenu(h, false), "Alt+Space has a native system menu")
            assertEquals(ChromeHit.TOP_LEFT, native.hitScreenPoint(rect.left + 1, rect.top + 1))
            assertEquals(ChromeHit.CAPTION, native.hitScreenPoint(rect.left + 500, rect.top + 20))
            assertEquals(ChromeHit.CLIENT, native.hitScreenPoint(rect.left + 70, rect.top + 20))
            user.SendMessage(h, 0x112, WPARAM(0xF030), LPARAM(0))
            assertTrue(chrome.state.value.maximized)
            val maximizedClient = RECT()
            user.GetClientRect(h, maximizedClient)
            val clientOrigin = POINT()
            Native.load("user32", ChromeUser32::class.java).ClientToScreen(h, clientOrigin)
            val monitor = com.sun.jna.platform.win32.WinUser.MONITORINFO()
            user.GetMonitorInfo(user.MonitorFromWindow(h, 2), monitor)
            // Windows may retain invisible resize borders outside the work area. Visible client content must fit it exactly.
            assertEquals(monitor.rcWork.toRectangle(), java.awt.Rectangle(clientOrigin.x, clientOrigin.y,
                maximizedClient.right, maximizedClient.bottom), "maximized client respects taskbar work area")
            assertEquals(ChromeHit.CAPTION, native.hitScreenPoint(clientOrigin.x + 500, clientOrigin.y + 1))
            user.SendMessage(h, 0x112, WPARAM(0xF120), LPARAM(0))
            assertFalse(chrome.state.value.maximized)
            assertEquals(0, user.GetWindowLong(h, -16) and Int.MIN_VALUE, "AWT restore does not reintroduce WS_POPUP")
            val restored = RECT(); user.GetWindowRect(h, restored)
            assertEquals(rect.toRectangle(), restored.toRectangle(), "Windows restores the original geometry")
            // Use Scheme A's actual Compose geometry in the native WM_NCHITTEST path.
            val studioRecorder = DesktopChromeLayoutRecorder(chrome)
            EventQueue.invokeAndWait {
                window.setSize(1240, 800)
                window.setContent {
                    Box(Modifier.fillMaxSize().onGloballyPositioned(studioRecorder::root)) {
                        DesktopStudioWorkspace(
                            chromeRecorder = studioRecorder,
                            navigation = { DesktopTitleBar(DesktopShellSize.COMPACT, DesktopPrimaryRoute.TOOLS, false, chrome, studioRecorder, captionsVisible = false) {} },
                            captions = { DesktopTitleBar(DesktopShellSize.COMPACT, DesktopPrimaryRoute.TOOLS, false, chrome, studioRecorder, navigationVisible = false) {} },
                            compact = {}, editor = { StatusText("Local fixture") }, footer = { StatusText("Generate") },
                            preview = { StatusText("Local preview") })
                    }
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while ((chrome.layout.width < 1200f || chrome.layout.title?.right?.let { it > 800f } != false) && System.nanoTime() < deadline) Thread.sleep(30)
            assertTrue(chrome.layout.width >= 1200f)
            assertTrue(assertNotNull(chrome.layout.title).right < 800f)
            val studioOrigin = POINT()
            Native.load("user32", ChromeUser32::class.java).ClientToScreen(h, studioOrigin)
            fun nativeHit(x: Int, y: Int): Int {
                val packedPoint = (((studioOrigin.y + y).toLong() and 0xffff) shl 16) or ((studioOrigin.x + x).toLong() and 0xffff)
                return user.SendMessage(h, 0x84, WPARAM(0), LPARAM(packedPoint)).toInt()
            }
            val titleRight = chrome.layout.title!!.right.toInt()
            assertEquals(ChromeHit.CAPTION.nativeValue, nativeHit(titleRight - 20, 20))
            assertEquals(ChromeHit.CLIENT.nativeValue, nativeHit(titleRight + 6, 20), "divider top is client")
            assertEquals(ChromeHit.CLIENT.nativeValue, nativeHit(titleRight + 6, 400), "divider middle is client")
            assertEquals(ChromeHit.CAPTION.nativeValue, nativeHit(titleRight + 40, 20), "blank preview caption band")
            assertEquals(ChromeHit.CLIENT.nativeValue, nativeHit(titleRight + 40, 60), "interactive preview remains client")
            assertEquals(ChromeHit.SYSTEM_MENU.nativeValue, nativeHit(20, 20))
            assertEquals(ChromeHit.CLIENT.nativeValue, nativeHit(60, 20))
            val studioMax = chrome.layout.captions.getValue(ChromeHit.MAXIMIZE)
            assertEquals(ChromeHit.MAXIMIZE.nativeValue, nativeHit(((studioMax.left + studioMax.right) / 2).toInt(), 20))
            user.SendMessage(h, 0x112, WPARAM(0xF020), LPARAM(0))
            assertTrue(chrome.state.value.minimized)
            user.SendMessage(h, 0x112, WPARAM(0xF120), LPARAM(0))
            user.SendMessage(h, 0x10, WPARAM(0), LPARAM(0))
            EventQueue.invokeAndWait {}
            assertEquals(1, closes.get(), "WM_CLOSE reaches the deferrable application authority")
            assertTrue(user.IsWindow(h), "the authority may defer close; hook must not destroy the window")
            user.SendMessage(h, 0x112, WPARAM(0xF060), LPARAM(0))
            EventQueue.invokeAndWait {}
            assertEquals(2, closes.get(), "system close uses the same authority")
            assertNull(chrome.state.value.failure)
            EventQueue.invokeAndWait {
                assertFailsWith<IllegalStateException> { WindowsWindowChrome.install(window, chrome) }
                native.close()
                assertEquals(0, native.installedHookCount)
                assertEquals(originalProc, user.GetWindowLongPtr(h, -4).toLong())
                assertEquals(originalStyle, user.GetWindowLong(h, -16))
            }
        } finally {
            EventQueue.invokeAndWait {
                chrome.platform = null
                if (installed) native.close()
                if (created) window.dispose()
            }
        }
    }
}
