package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.*
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.RootPaneContainer
import com.example.chatbar.data.local.entity.ThemeMode
import com.example.chatbar.domain.appearance.DefaultThemeColorHsv
import kotlin.test.*

/** Production image shells, synthetic pixels only; no installed app, profile or network. */
class WindowsImageViewerChromeTest {
    private val user by lazy { User32.INSTANCE }
    private fun await(label: String, check: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000L
        while (!check() && System.nanoTime() < end) Thread.sleep(30)
        assertTrue(check(), label)
    }
    private fun title(w: Window) = when(w) { is Frame -> w.title; is Dialog -> w.title; else -> "" }
    @Test fun `production viewer and owned screenshot have themed captions and real minimize maximize restore`() {
        requireDesktopGraphicsForNativeTest()
        org.junit.Assume.assumeTrue(DesktopWindowChrome.isWindows)
        val bytes = DesktopImageEditing.png(BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().let { g -> try { g.color = Color(40, 130, 160); g.fillRect(0, 0, width, height) } finally { g.dispose() } }
        })
        for (owned in listOf(false, true)) {
            lateinit var host: ComposeWindow
            var created = false
            val open = mutableStateOf(true)
            val expectedTitle = if (owned) "PNG native fixture" else "图片预览"
            var child: Window? = null
            try {
                EventQueue.invokeAndWait {
                    host = ComposeWindow().apply { setSize(660, 480); setLocation(80, 80) }
                    created = true
                    host.setContent {
                        CompositionLocalProvider(LocalDesktopFishParentWindow provides host,
                            LocalDesktopPalette provides desktopSemanticColors(ThemeMode.DARK, DefaultThemeColorHsv, false)) {
                            Box(Modifier.fillMaxSize()) {
                                if (open.value) {
                                    if (owned) DesktopScreenshotWindow({ open.value = false }, { StatusText("Synthetic screenshot") }, expectedTitle)
                                    else DesktopTransientImagePreview("synthetic", { bytes }) { open.value = false }
                                }
                            }
                        }
                    }
                    host.isVisible = true
                }
                await("production child visible") {
                    EventQueue.invokeAndWait { child = Window.getWindows().singleOrNull { it !== host && it.isShowing && title(it) == expectedTitle } }
                    child != null
                }
                val w = assertNotNull(child)
                if (owned) assertSame(host, w.owner)
                var native: WindowsWindowChrome? = null
                await("production measured caption") {
                    EventQueue.invokeAndWait { native = (w as RootPaneContainer).rootPane.getClientProperty("chatbar.windowChrome") as? WindowsWindowChrome }
                    native?.chrome?.layout?.captions?.size == 3
                }
                val bridge = assertNotNull(native)
                val chrome = bridge.chrome
                val handle = bridge.hwnd
                val before = RECT(); user.GetWindowRect(handle, before)
                fun click(hit: ChromeHit) {
                    // SetCapture requires the foreground fixture. Do not simulate native mouse
                    // capture through another app or steal foreground from the user.
                    // https://learn.microsoft.com/windows/win32/api/winuser/nf-winuser-setcapture
                    org.junit.Assume.assumeTrue("Native caption input requires foreground fixture; NOT_TESTED",
                        user.GetForegroundWindow() == handle)
                    // WM_SIZE changes native geometry before Compose measures all captions.
                    // Observe one complete layout, then calculate/input on its owning EDT.
                    await("caption geometry settled before $hit") {
                        val layout = chrome.layout
                        val close = layout.captions[ChromeHit.CLOSE]
                        close != null && kotlin.math.abs(close.right - layout.width) < .5f
                    }
                    var admitted = false
                    EventQueue.invokeAndWait {
                    if (user.GetForegroundWindow() != handle) return@invokeAndWait
                    admitted = true
                    val layout = chrome.layout
                    val rect = layout.captions.getValue(hit)
                    val client = RECT(); user.GetClientRect(handle, client)
                    val origin = POINT(); Native.load("user32", ChromeUser32::class.java).ClientToScreen(handle, origin)
                    val x = origin.x + ((rect.left + rect.right) / 2 * client.right / layout.width).toInt()
                    val y = origin.y + ((rect.top + rect.bottom) / 2 * client.bottom / layout.height).toInt()
                    val packed = ((y.toLong() and 0xffff) shl 16) or (x.toLong() and 0xffff)
                    assertEquals(hit.nativeValue, user.SendMessage(handle, 0x84, WPARAM(0), LPARAM(packed)).toInt())
                    check(user.SetCursorPos(x.toLong(), y.toLong()))
                    user.SendMessage(handle, 0xA1, WPARAM(hit.nativeValue.toLong()), LPARAM(packed))
                    user.SendMessage(handle, 0xA2, WPARAM(hit.nativeValue.toLong()), LPARAM(packed))
                    }
                    org.junit.Assume.assumeTrue("Foreground changed before native input; NOT_TESTED", admitted)
                }
                click(ChromeHit.MAXIMIZE); await("maximized") { chrome.state.value.maximized }
                await("maximized layout settled") { chrome.layout.width > before.right - before.left }
                click(ChromeHit.MAXIMIZE); await("restored") { !chrome.state.value.maximized }
                val restored = RECT(); user.GetWindowRect(handle, restored)
                assertEquals(before.toRectangle(), restored.toRectangle())
                click(ChromeHit.MINIMIZE); await("minimized") { chrome.state.value.minimized }
                chrome.command(DesktopWindowCommand.RESTORE); await("restored from taskbar state") { !chrome.state.value.minimized }
                // HWND-targeted functionality stays independent of desktop foreground/capture.
                // Other apps may be foreground or topmost; a desktop crop is not visual proof.
                click(ChromeHit.CLOSE); await("child closed") { !w.isDisplayable }
                assertTrue(host.isDisplayable && host.isShowing, "Image close must leave parent alive")
                assertNull(chrome.state.value.failure)
            } finally {
                EventQueue.invokeAndWait { open.value = false; child?.dispose(); if (created) host.dispose() }
            }
        }
        println("NATIVE_IMAGE_CAPTIONS_VERIFIED; desktop capture and human visual acceptance are separate")
    }
}
