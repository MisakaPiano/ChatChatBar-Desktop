@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.platform.win32.WinDef.*
import java.awt.EventQueue
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.test.*
import org.junit.Assume.assumeTrue

/** Real OS pointer input, isolated HWND, local UI only; never starts an application container. */
class WindowsStudioEdgeDragTest {
    interface PointerTargetApi : com.sun.jna.win32.StdCallLibrary {
        fun WindowFromPoint(point: POINT.ByValue): HWND
        fun GetAncestor(hwnd: HWND, flags: Int): HWND
    }
    private fun captureClient(hwnd: HWND, width: Int, height: Int): BufferedImage {
        // Render this HWND into an off-screen bitmap. Screen capture can include unrelated
        // always-on-top windows and must never be used for shareable fixture evidence.
        val user = User32.INSTANCE; val gdi = GDI32.INSTANCE
        val dc = user.GetDC(hwnd)
        val memory = gdi.CreateCompatibleDC(dc)
        val info = WinGDI.BITMAPINFO().apply {
            bmiHeader.biWidth = width; bmiHeader.biHeight = -height
            bmiHeader.biPlanes = 1; bmiHeader.biBitCount = 32; bmiHeader.biCompression = WinGDI.BI_RGB
        }
        val bits = PointerByReference()
        val bitmap = gdi.CreateDIBSection(memory, info, WinGDI.DIB_RGB_COLORS, bits, null, 0)
        val previous = gdi.SelectObject(memory, bitmap)
        try {
            assertTrue(user.PrintWindow(hwnd, memory, 3), "PrintWindow client capture")
            return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
                setRGB(0, 0, width, height, bits.value.getIntArray(0, width * height), 0, width)
            }
        } finally {
            gdi.SelectObject(memory, previous); gdi.DeleteObject(bitmap)
            gdi.DeleteDC(memory); user.ReleaseDC(hwnd, dc)
        }
    }
    @Test fun `maximized Studio outer edges remain stationary and restore remains resizable`() {
        assumeTrue(DesktopWindowChrome.isWindows)
        val user = User32.INSTANCE
        val api = Native.load("user32", ChromeUser32::class.java)
        val pointerApi = Native.load("user32", PointerTargetApi::class.java)
        val robot = Robot().apply { autoDelay = 20 }
        val evidence = Path.of("build/phase7-slice-c-scheme-a-r1-evidence/native")
        Files.createDirectories(evidence)
        val log = mutableListOf<String>()
        val problems = mutableListOf<String>()
        val chrome = DesktopWindowChrome {}
        val workspace = DesktopStudioWorkspaceState()
        val previewBounds = AtomicReference<Rect>()
        val composePointer = AtomicReference<Pair<Offset, Boolean>>()
        val composeDensity = AtomicReference(1f)
        val layoutTrace = AtomicReference("")
        var route by mutableStateOf(DesktopPrimaryRoute.TOOLS)
        lateinit var window: ComposeWindow
        lateinit var native: WindowsWindowChrome
        lateinit var resizing: DesktopNativeResizeOwnership
        var originalThickness = 0.dp
        EventQueue.invokeAndWait {
            window = ComposeWindow().apply { isUndecorated = true; isAlwaysOnTop = true; title = "CCB isolated Studio edge fixture"; setSize(1100, 700); setLocation(100, 100); addNotify() }
            native = WindowsWindowChrome.install(window, chrome); chrome.platform = native
            originalThickness = window.undecoratedResizerThickness
            resizing = DesktopNativeResizeOwnership(window)
            val recorder = DesktopChromeLayoutRecorder(chrome)
            window.setContent {
                composeDensity.set(LocalDensity.current.density)
                Box(Modifier.fillMaxSize().onGloballyPositioned(recorder::root)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.firstOrNull()?.let {
                                    composePointer.set(it.position to event.buttons.isPrimaryPressed)
                                }
                            }
                        }
                    }) {
                    if (route != DesktopPrimaryRoute.TOOLS) Column(Modifier.fillMaxSize()) {
                        DesktopTitleBar(DesktopShellSize.WIDE, route, false, chrome, recorder) {}
                        Box(Modifier.fillMaxSize()) { StatusText("LOCAL FIXTURE — $route") }
                    } else DesktopStudioWorkspace(workspace,
                        chromeRecorder = recorder,
                        navigation = { DesktopTitleBar(DesktopShellSize.COMPACT, DesktopPrimaryRoute.TOOLS, false, chrome, recorder, captionsVisible = false) {} },
                        captions = { DesktopTitleBar(DesktopShellSize.COMPACT, DesktopPrimaryRoute.TOOLS, false, chrome, recorder, navigationVisible = false) {} },
                        compact = {}, editor = { StatusText("LOCAL FIXTURE — Prompt") }, footer = { StatusText("LOCAL FIXTURE — Footer") },
                        preview = { Box(Modifier.fillMaxSize().onGloballyPositioned { child ->
                            // Observe the actual full-height preview Column, outside its content padding.
                            val ancestors = generateSequence(child.parentCoordinates) { it.parentCoordinates }.toList()
                            val rootSize = ancestors.last().size
                            layoutTrace.set(ancestors.joinToString { "${it.size}/${it.boundsInRoot()}" })
                            val panel = ancestors.firstOrNull { it.size.height == rootSize.height &&
                                it.size.width < rootSize.width }
                            previewBounds.set(panel?.boundsInRoot())
                        }.background(Color(0xff446688))) { StatusText("LOCAL FIXTURE — full-height preview") } })
                }
            }
            window.isVisible = true; window.toFront()
        }
        fun settle() { Thread.sleep(600); EventQueue.invokeAndWait { native.refreshChildren() } }
        fun rect() = RECT().also { assertTrue(user.GetWindowRect(native.hwnd, it)) }.toRectangle()
        fun client() = RECT().also { assertTrue(user.GetClientRect(native.hwnd, it)) }.toRectangle()
        fun origin() = POINT().also { assertTrue(api.ClientToScreen(native.hwnd, it)) }
        fun awaitReady(description: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 3_000_000_000L
            while (!condition() && System.nanoTime() < deadline) {
                EventQueue.invokeAndWait { native.refreshChildren() }
                Thread.sleep(10)
            }
            assertTrue(condition(), description)
        }
        fun record(name: String) {
            val placement = com.sun.jna.platform.win32.WinUser.WINDOWPLACEMENT()
            assertTrue(user.GetWindowPlacement(native.hwnd, placement).booleanValue())
            log += "$name zoomed=${api.IsZoomed(native.hwnd)} window=${rect()} client=${client()} origin=${origin().let { "${it.x},${it.y}" }} compose=${chrome.layout.width}x${chrome.layout.height} fraction=${workspace.fraction} placement=${placement.showCmd}/${placement.rcNormalPosition.toRectangle()} resizer=${window.undecoratedResizerThickness}"
            Files.write(evidence.resolve("states.txt"), log)
            val c = client()
            ImageIO.write(captureClient(native.hwnd, c.width, c.height), "png", evidence.resolve("$name.png").toFile())
        }
        fun drag(x: Int, y: Int, dx: Int, dy: Int, composeInput: Boolean = false) {
            // toFront requests native z-order asynchronously. Never send Robot input until
            // Windows confirms this point belongs to the fixture (e.g. after a shell overlay).
            awaitReady("Pointer target becomes the isolated fixture before any input") {
                EventQueue.invokeAndWait { window.toFront() }
                pointerApi.GetAncestor(pointerApi.WindowFromPoint(POINT.ByValue(x, y)), 2) == native.hwnd
            }
            assertEquals(native.hwnd, pointerApi.GetAncestor(pointerApi.WindowFromPoint(POINT.ByValue(x, y)), 2),
                "Pointer must target the isolated fixture, never another application")
            fun movePhysical(px: Int, py: Int) {
                // Robot uses AWT logical screen coordinates; HWND/Compose bounds use physical pixels.
                lateinit var location: java.awt.Point
                lateinit var transform: java.awt.geom.AffineTransform
                EventQueue.invokeAndWait {
                    location = window.locationOnScreen
                    transform = window.graphicsConfiguration.defaultTransform
                }
                val r = rect()
                robot.mouseMove(location.x + kotlin.math.round((px - r.x) / transform.scaleX).toInt(),
                    location.y + kotlin.math.round((py - r.y) / transform.scaleY).toInt())
            }
            movePhysical(x, y)
            if (composeInput) {
                val cursor = POINT(); assertTrue(user.GetCursorPos(cursor))
                assertTrue(kotlin.math.abs(cursor.x - x) <= 2 && kotlin.math.abs(cursor.y - y) <= 2,
                    "Robot physical cursor must hit measured divider: ${cursor.x},${cursor.y} vs $x,$y")
            }
            if (composeInput) awaitReady("Compose receives hover at divider") {
                composePointer.get()?.let { (p, pressed) ->
                    val o = origin(); !pressed && kotlin.math.abs(p.x - (x - o.x)) <= 2 &&
                        kotlin.math.abs(p.y - (y - o.y)) <= 2
                } == true
            }
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            try {
                if (composeInput) awaitReady("Compose receives divider press") { composePointer.get()?.second == true }
                for (i in 1..12) movePhysical(x + dx * i / 12, y + dy * i / 12)
            }
            finally {
                try {
                    if (composeInput) awaitReady("Compose receives final divider drag before release") {
                        composePointer.get()?.let { (p, pressed) ->
                            val o = origin(); pressed && kotlin.math.abs(p.x - (x + dx - o.x)) <= 2 &&
                                kotlin.math.abs(p.y - (y + dy - o.y)) <= 2
                        } == true
                    }
                } finally { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
            }
            settle()
        }
        fun rightCaptionPoint(): POINT {
            awaitReady("Right preview blank caption geometry is ready") { chrome.layout.dragRegions.size == 1 }
            val r = chrome.layout.dragRegions.single()
            return POINT((r.left + 40 * composeDensity.get()).toInt(), ((r.top + r.bottom) / 2).toInt())
                .also { assertTrue(api.ClientToScreen(native.hwnd, it))
                    assertEquals(ChromeHit.CAPTION, native.hitScreenPoint(it.x, it.y)) }
        }
        fun verifyCaptionButtons() {
            for (hit in listOf(ChromeHit.MINIMIZE, ChromeHit.MAXIMIZE, ChromeHit.CLOSE)) {
                val r = chrome.layout.captions.getValue(hit)
                val p = POINT(((r.left + r.right) / 2).toInt(), ((r.top + r.bottom) / 2).toInt())
                assertTrue(api.ClientToScreen(native.hwnd, p))
                assertEquals(hit, native.hitScreenPoint(p.x, p.y))
            }
        }
        try {
            settle(); record("normal-before")
            verifyCaptionButtons()
            val beforeRightDrag = rect(); val beforeRightClient = client()
            val rightCaption = rightCaptionPoint()
            drag(rightCaption.x, rightCaption.y, 80, 60); record("normal-right-caption-drag")
            assertNotEquals(beforeRightDrag.location, rect().location)
            assertEquals(beforeRightDrag.size, rect().size); assertEquals(beforeRightClient, client())
            user.SendMessage(native.hwnd, 0x112, WPARAM(0xF030), LPARAM(0)); settle(); record("max-before")
            verifyCaptionButtons()
            val initial = rect(); val initialClient = client()
            val o = origin(); val w = initialClient.width; val h = initialClient.height
            val edges = listOf("left" to (1 to h / 2), "right" to (w - 2 to h / 2),
                "top-right-pane" to (w * 3 / 4 to 1),
                "bottom-left-pane" to (w / 4 to h - 2), "bottom-right-pane" to (w * 3 / 4 to h - 2),
                "top-left" to (1 to 1), "top-right" to (w - 2 to 1), "bottom-left" to (1 to h - 2), "bottom-right" to (w - 2 to h - 2))
            for ((name, point) in edges) {
                val x = o.x + point.first; val y = o.y + point.second
                val packed = ((y.toLong() and 0xffff) shl 16) or (x.toLong() and 0xffff)
                val hit = user.SendMessage(native.hwnd, 0x84, WPARAM(0), LPARAM(packed)).toInt()
                log += "$name WM_NCHITTEST=$hit before=${rect()}"
                user.EnumChildWindows(native.hwnd, { child, _ -> log += "child=${child.pointer} hit=${user.SendMessage(child, 0x84, WPARAM(0), LPARAM(packed)).toInt()}"; true }, null)
                drag(x, y, if (point.first > w / 2) -60 else 60, if (point.second > h / 2) -50 else 50)
                record("max-after-$name")
                if (hit in 10..17 || !api.IsZoomed(native.hwnd) || rect() != initial || client() != initialClient) problems += name
                assertEquals(client().width.toFloat(), chrome.layout.width, 2f)
                assertEquals(client().height.toFloat(), chrome.layout.height, 2f)
                if (!api.IsZoomed(native.hwnd) || rect() != initial) { user.SendMessage(native.hwnd, 0x112, WPARAM(0xF030), LPARAM(0)); settle() }
            }
            log += "preview ancestors=${layoutTrace.get()} measured=${previewBounds.get()}"
            Files.write(evidence.resolve("states.txt"), log)
            val beforeFraction = workspace.fraction
            awaitReady("Measured maximized preview and root are ready") {
                previewBounds.get()?.let { it.height == h.toFloat() && it.right == w.toFloat() } == true
            }
            val measured = previewBounds.get()
            val dividerWidthPx = 12f * composeDensity.get()
            val divider = Rect(measured.left - dividerWidthPx, 0f, measured.left, measured.bottom)
            val start = divider.center
            assertTrue(divider.contains(start))
            val screen = POINT(start.x.toInt(), start.y.toInt())
            assertTrue(api.ClientToScreen(native.hwnd, screen))
            assertEquals(ChromeHit.CLIENT, native.hitScreenPoint(screen.x, screen.y))
            log += "divider measured=$divider density=${composeDensity.get()} screen=${screen.x},${screen.y} awtScale=${window.graphicsConfiguration.defaultTransform}"
            drag(screen.x, screen.y, -70, 0, composeInput = true)
            awaitReady("Divider changes preview width after delivered drag") {
                workspace.fraction != beforeFraction && previewBounds.get().width != measured.width
            }
            record("max-divider")
            assertNotEquals(beforeFraction, workspace.fraction, "Internal divider must remain interactive")
            assertEquals(initial, rect()); assertTrue(api.IsZoomed(native.hwnd))
            user.SendMessage(native.hwnd, 0x112, WPARAM(0xF120), LPARAM(0)); settle()
            record("restore-before")
            for ((name, fractions) in listOf("left" to (0f to .5f), "right" to (1f to .5f),
                "top" to (.5f to 0f), "bottom" to (.5f to 1f), "top-left" to (0f to 0f),
                "top-right" to (1f to 0f), "bottom-left" to (0f to 1f), "bottom-right" to (1f to 1f))) {
                EventQueue.invokeAndWait { window.setBounds(100, 100, 1100, 700) }; settle()
                val r = rect()
                val x = r.x + 1 + ((r.width - 3) * fractions.first).toInt()
                val y = r.y + 1 + ((r.height - 3) * fractions.second).toInt()
                assertTrue(native.hitScreenPoint(x, y).nativeValue in 10..17)
                drag(x, y, if (fractions.first == .5f) 0 else if (fractions.first == 0f) -30 else 30,
                    if (fractions.second == .5f) 0 else if (fractions.second == 0f) -30 else 30)
                record("restore-after-$name")
                assertTrue(rect().width > r.width || rect().height > r.height, "Normal $name still resizes")
                assertEquals(client().width.toFloat(), chrome.layout.width, 2f)
                assertEquals(client().height.toFloat(), chrome.layout.height, 2f)
            }
            user.SendMessage(native.hwnd, 0x112, WPARAM(0xF030), LPARAM(0)); settle(); record("remaximized")
            assertEquals(initialClient, client())
            assertEquals(client().width.toFloat(), chrome.layout.width, 2f)
            assertEquals(client().height.toFloat(), chrome.layout.height, 2f)
            // Real caption drag still restores; no blanket suppression of native dragging.
            val maxRightCaption = rightCaptionPoint()
            drag(maxRightCaption.x, maxRightCaption.y, 60, 70); record("right-caption-drag-restored")
            assertFalse(api.IsZoomed(native.hwnd))
            assertEquals(client().width.toFloat(), chrome.layout.width, 2f)
            assertEquals(client().height.toFloat(), chrome.layout.height, 2f)
            for (next in listOf(DesktopPrimaryRoute.CHAT, DesktopPrimaryRoute.MANAGE)) {
                EventQueue.invokeAndWait { route = next }; settle()
                user.SendMessage(native.hwnd, 0x112, WPARAM(0xF030), LPARAM(0)); settle()
                val r = rect(); val co = origin()
                drag(co.x + 1, co.y + client().height / 2, 40, 0)
                record("${next.name}-max-edge")
                assertTrue(api.IsZoomed(native.hwnd)); assertEquals(r, rect())
                user.SendMessage(native.hwnd, 0x112, WPARAM(0xF120), LPARAM(0)); settle()
            }
            assertTrue(problems.isEmpty(), "Maximized edges changed geometry: $problems")
        } finally {
            Files.write(evidence.resolve("states.txt"), log)
            EventQueue.invokeAndWait {
                chrome.platform = null; native.close(); resizing.close()
                assertEquals(originalThickness, window.undecoratedResizerThickness)
                window.dispose()
            }
        }
    }
}
