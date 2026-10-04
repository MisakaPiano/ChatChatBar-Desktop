package com.example.chatbar.desktop

import com.sun.jna.CallbackReference
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.POINT
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser.MONITORINFO
import com.sun.jna.platform.win32.WinUser.WindowProc
import com.sun.jna.ptr.LongByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Dimension
import java.awt.EventQueue
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JFrame

/** Windows-only, window-owned WndProc bridge. It owns no application/domain lifetime. */
internal class WindowsWindowChrome private constructor(
    private val window: JFrame,
    private val chrome: DesktopWindowChrome,
) : AutoCloseable {
    companion object {
        private const val OWNER = "chatbar.windowChrome"
        private const val GWL_STYLE = -16
        private const val GWLP_WNDPROC = -4
        internal const val NATIVE_STYLES = 0x00CF0000 // WS_OVERLAPPEDWINDOW, including thick frame and system menu.
        private const val WM_NCCALCSIZE = 0x83
        private const val WM_NCHITTEST = 0x84
        private const val WM_NCDESTROY = 0x82
        private const val WM_SYSCOMMAND = 0x112
        private const val WM_CLOSE = 0x10
        private const val WM_SIZE = 0x5
        private const val WM_GETMINMAXINFO = 0x24
        private const val WM_NCMOUSEMOVE = 0xA0
        private const val WM_NCLBUTTONDOWN = 0xA1
        private const val WM_NCLBUTTONUP = 0xA2
        private const val WM_NCMOUSELEAVE = 0x2A2
        private const val WM_MOUSEMOVE = 0x200
        private const val WM_LBUTTONUP = 0x202
        private const val WM_CAPTURECHANGED = 0x215
        private const val WM_CANCELMODE = 0x1F

        fun install(window: JFrame, chrome: DesktopWindowChrome): WindowsWindowChrome {
            check(DesktopWindowChrome.isWindows)
            check(EventQueue.isDispatchThread()) { "Chrome installation must run on the AWT EDT" }
            check(window.rootPane.getClientProperty(OWNER) == null) { "Window chrome already installed" }
            if (!window.isDisplayable) window.addNotify()
            return WindowsWindowChrome(window, chrome).also { bridge ->
                window.rootPane.putClientProperty(OWNER, bridge)
                try { bridge.install() } catch (failure: Throwable) {
                    bridge.close()
                    throw IllegalStateException("Windows title bar installation failed", failure)
                }
            }
        }
    }

    private val user = User32.INSTANCE
    private val api = Native.load("user32", ChromeUser32::class.java)
    private val dwm = Native.load("dwmapi", ChromeDwmApi::class.java)
    internal val hwnd = HWND(Native.getWindowPointer(window))
    private val originalStyle = user.GetWindowLong(hwnd, GWL_STYLE)
    private val originalMinimum = window.minimumSize
    private data class Hook(val handle: HWND, val original: Pointer, val callback: WindowProc)
    // Strong references remain until each corresponding WndProc is restored/destroyed.
    private val hooks = ConcurrentHashMap<Long, Hook>()
    @Volatile private var closed = false
    @Volatile private var pressed: ChromeHit? = null
    private var dark: Boolean? = null
    internal val installedHookCount get() = hooks.size

    private fun install() {
        window.minimumSize = Dimension(330, 240)
        hook(hwnd, topLevel = true)
        Native.setLastError(0)
        // AWT undecorated peers start as WS_POPUP. Use a real overlapped window for shell/Snap behavior.
        val previous = user.SetWindowLong(hwnd, GWL_STYLE, (originalStyle and Int.MAX_VALUE) or NATIVE_STYLES)
        check(previous != 0 || Native.getLastError() == 0) { "SetWindowLong(style): ${Native.getLastError()}" }
        // One physical pixel of DWM frame preserves compositor shadow; the client paints the chrome.
        check(dwm.DwmExtendFrameIntoClientArea(hwnd, ChromeMargins()) >= 0) { "DwmExtendFrameIntoClientArea failed" }
        setDwmAttribute(2, 2) // DWMWA_NCRENDERING_POLICY = ENABLED
        setDwmAttribute(33, 2) // Rounded corners where supported (Windows 11).
        check(user.SetWindowPos(hwnd, null, 0, 0, 0, 0, 0x37)) { "SetWindowPos(FRAMECHANGED) failed" }
        refreshChildren()
        updateWindowState()
    }

    private fun pointer(handle: HWND) = Pointer.nativeValue(handle.pointer)
    private fun getProc(handle: HWND): Pointer = if (Native.POINTER_SIZE == 8)
        user.GetWindowLongPtr(handle, GWLP_WNDPROC).toPointer()
    else Pointer.createConstant(user.GetWindowLong(handle, GWLP_WNDPROC).toLong() and 0xffffffffL)

    private fun setProc(handle: HWND, proc: Pointer) {
        Native.setLastError(0)
        val previous = if (Native.POINTER_SIZE == 8) user.SetWindowLongPtr(handle, GWLP_WNDPROC, proc)
            else Pointer.createConstant(user.SetWindowLong(handle, GWLP_WNDPROC, Pointer.nativeValue(proc).toInt()).toLong())
        check(Pointer.nativeValue(previous) != 0L || Native.getLastError() == 0) {
            "SetWindowLongPtr(WNDPROC): ${Native.getLastError()}"
        }
    }

    private fun hook(handle: HWND, topLevel: Boolean) {
        val key = pointer(handle)
        if (hooks.containsKey(key)) return
        val original = getProc(handle)
        val callback = WindowProc { h, message, w, l ->
            try {
                if (message == WM_NCDESTROY) {
                    setProc(h, original)
                    val result = user.CallWindowProc(original, h, message, w, l)
                    hooks.remove(key)
                    result
                } else if (topLevel) dispatch(original, h, message, w, l)
                else if (message == WM_NCHITTEST && hit(l) != ChromeHit.CLIENT) LRESULT(-1) // HTTRANSPARENT to parent
                else user.CallWindowProc(original, h, message, w, l)
            } catch (failure: Throwable) {
                // Never unwind a Kotlin/JNA exception through the Windows callback stack.
                reportFailure(failure)
                try { user.CallWindowProc(original, h, message, w, l) }
                catch (_: Throwable) { LRESULT(0) }
            }
        }
        val entry = Hook(handle, original, callback)
        hooks[key] = entry
        try { setProc(handle, CallbackReference.getFunctionPointer(callback)) }
        catch (failure: Throwable) { hooks.remove(key); throw failure }
    }

    /** Skiko's heavyweight rendering child must let native non-client hits reach its parent. */
    fun refreshChildren() {
        if (closed) return
        if (!EventQueue.isDispatchThread()) { EventQueue.invokeLater { refreshChildren() }; return }
        user.EnumChildWindows(hwnd, { child, _ ->
            try { if (!closed) hook(child, topLevel = false) }
            catch (failure: Throwable) { reportFailure(failure) }
            true
        }, null)
    }

    private fun reportFailure(failure: Throwable) {
        System.err.println("Windows chrome: ${failure.javaClass.simpleName}: ${failure.message}")
        chrome.updateState { it.copy(failure = failure.message ?: failure.javaClass.simpleName) }
    }

    private fun dispatch(original: Pointer, h: HWND, message: Int, w: WPARAM, l: LPARAM): LRESULT {
        when (message) {
            WM_NCCALCSIZE -> {
                // First RECT is the proposed client bounds for both NCCALCSIZE variants.
                if (api.IsZoomed(h)) {
                    val work = workArea(Pointer(l.toLong()))
                    Pointer(l.toLong()).apply {
                        setInt(0, work.left); setInt(4, work.top); setInt(8, work.right); setInt(12, work.bottom)
                    }
                }
                return LRESULT(0)
            }
            WM_GETMINMAXINFO -> {
                val result = user.CallWindowProc(original, h, message, w, l)
                val monitor = MONITORINFO()
                check(user.GetMonitorInfo(user.MonitorFromWindow(h, 2), monitor).booleanValue())
                Pointer(l.toLong()).apply {
                    setInt(8, monitor.rcWork.right - monitor.rcWork.left)
                    setInt(12, monitor.rcWork.bottom - monitor.rcWork.top)
                    setInt(16, monitor.rcWork.left - monitor.rcMonitor.left)
                    setInt(20, monitor.rcWork.top - monitor.rcMonitor.top)
                    setInt(24, (330 * api.GetDpiForWindow(h) / 96))
                    setInt(28, (240 * api.GetDpiForWindow(h) / 96))
                }
                return result
            }
            WM_NCHITTEST -> return LRESULT(hit(l).nativeValue.toLong())
            WM_CLOSE -> { chrome.requestNativeClose(); return LRESULT(0) }
            WM_SYSCOMMAND -> if ((w.toInt() and 0xFFF0) == 0xF060) {
                chrome.requestNativeClose(); return LRESULT(0)
            }
            WM_NCMOUSEMOVE -> {
                setHover(hit(l))
                api.TrackMouseEvent(ChromeTrackMouse(hwnd = h))
                // DWM receives real non-client hover, including HTMAXBUTTON for Snap Layouts.
                dwm.DwmDefWindowProc(h, message, w, l, LongByReference())
            }
            WM_NCMOUSELEAVE -> {
                chrome.updateState { it.copy(hovered = null) }
                dwm.DwmDefWindowProc(h, message, w, l, LongByReference())
            }
            WM_NCLBUTTONDOWN -> {
                val target = hit(l)
                if (desktopCaptionCommand(target, api.IsZoomed(h)) != null) {
                    pressed = target
                    chrome.updateState { it.copy(pressed = target, hovered = target) }
                    api.SetCapture(h)
                    return LRESULT(0)
                }
            }
            WM_MOUSEMOVE -> if (pressed != null) { setHover(cursorHit()); return LRESULT(0) }
            WM_LBUTTONUP, WM_NCLBUTTONUP -> if (pressed != null) {
                val target = pressed
                val over = cursorHit()
                pressed = null
                api.ReleaseCapture()
                chrome.updateState { it.copy(pressed = null, hovered = over) }
                if (target == over) desktopCaptionCommand(over, api.IsZoomed(h))?.let {
                    if (it == DesktopWindowCommand.CLOSE) chrome.requestNativeClose() else command(it)
                }
                return LRESULT(0)
            }
            WM_CAPTURECHANGED, WM_CANCELMODE -> {
                pressed = null
                chrome.updateState { it.copy(pressed = null, hovered = null) }
            }
        }
        val result = user.CallWindowProc(original, h, message, w, l)
        if (message == WM_SIZE) updateWindowState()
        return result
    }

    private fun workArea(proposed: Pointer): RECT {
        val info = MONITORINFO()
        val rect = RECT().apply {
            left = proposed.getInt(0); top = proposed.getInt(4)
            right = proposed.getInt(8); bottom = proposed.getInt(12)
        }
        check(user.GetMonitorInfo(user.MonitorFromRect(rect, 2), info).booleanValue())
        return info.rcWork
    }

    private fun setHover(hit: ChromeHit) {
        val hovered = hit.takeIf { desktopCaptionCommand(it, false) != null }
        chrome.updateState { it.copy(hovered = hovered) }
    }

    private fun cursorHit(): ChromeHit {
        val point = POINT()
        check(user.GetCursorPos(point))
        return hitScreenPoint(point.x, point.y)
    }

    private fun hit(l: LPARAM): ChromeHit = DesktopChromeHitTest.screenPoint(l.toLong()).let {
        hitScreenPoint(it.first, it.second)
    }

    internal fun hitScreenPoint(x: Int, y: Int): ChromeHit {
        val origin = POINT()
        check(api.ClientToScreen(hwnd, origin))
        val client = RECT()
        check(user.GetClientRect(hwnd, client))
        val point = DesktopChromeHitTest.screenToClient(x, y, origin.x, origin.y)
        val dpi = api.GetDpiForWindow(hwnd)
        val border = api.GetSystemMetricsForDpi(32, dpi) + api.GetSystemMetricsForDpi(92, dpi)
        return DesktopChromeHitTest.hit(point.first.toFloat(), point.second.toFloat(), client.right.toFloat(),
            client.bottom.toFloat(), border.toFloat(), api.IsZoomed(hwnd), chrome.layout)
    }

    private fun updateWindowState() {
        chrome.updateState { it.copy(maximized = api.IsZoomed(hwnd), minimized = api.IsIconic(hwnd)) }
    }

    fun command(command: DesktopWindowCommand) {
        val code = when (command) {
            DesktopWindowCommand.MINIMIZE -> 0xF020
            DesktopWindowCommand.MAXIMIZE -> 0xF030
            DesktopWindowCommand.RESTORE -> 0xF120
            DesktopWindowCommand.CLOSE -> { chrome.requestNativeClose(); return }
        }
        user.PostMessage(hwnd, WM_SYSCOMMAND, WPARAM(code.toLong()), LPARAM(0))
    }

    fun appearance(value: Boolean) {
        if (closed || dark == value) return
        dark = value
        setDwmAttribute(20, if (value) 1 else 0)
    }

    private fun setDwmAttribute(attribute: Int, value: Int) {
        // Unsupported appearance-only attributes on older Windows versions do not affect input.
        com.sun.jna.Memory(4).use { memory ->
            memory.setInt(0, value)
            dwm.DwmSetWindowAttribute(hwnd, attribute, memory, 4)
        }
    }

    override fun close() {
        if (closed) return
        check(EventQueue.isDispatchThread()) { "Chrome disposal must run on the AWT EDT" }
        closed = true
        // Restore children before their parent. No native window is destroyed here.
        hooks.values.sortedBy { if (pointer(it.handle) == pointer(hwnd)) 1 else 0 }.forEach {
            if (user.IsWindow(it.handle)) setProc(it.handle, it.original)
            hooks.remove(pointer(it.handle))
        }
        if (user.IsWindow(hwnd)) {
            dwm.DwmExtendFrameIntoClientArea(hwnd, ChromeMargins().apply { left = 0; right = 0; top = 0; bottom = 0 })
            user.SetWindowLong(hwnd, GWL_STYLE, originalStyle)
            user.SetWindowPos(hwnd, null, 0, 0, 0, 0, 0x37)
        }
        window.minimumSize = originalMinimum
        window.rootPane.putClientProperty(OWNER, null)
    }
}

internal interface ChromeUser32 : StdCallLibrary {
    fun GetDpiForWindow(hwnd: HWND): Int
    fun GetSystemMetricsForDpi(index: Int, dpi: Int): Int
    fun IsZoomed(hwnd: HWND): Boolean
    fun IsIconic(hwnd: HWND): Boolean
    fun ClientToScreen(hwnd: HWND, point: POINT): Boolean
    fun TrackMouseEvent(event: ChromeTrackMouse): Boolean
    fun SetCapture(hwnd: HWND): HWND?
    fun ReleaseCapture(): Boolean
}

internal interface ChromeDwmApi : StdCallLibrary {
    fun DwmExtendFrameIntoClientArea(hwnd: HWND, margins: ChromeMargins): Int
    fun DwmSetWindowAttribute(hwnd: HWND, attribute: Int, value: Pointer, size: Int): Int
    fun DwmDefWindowProc(hwnd: HWND, message: Int, w: WPARAM, l: LPARAM, result: LongByReference): Boolean
}

@Structure.FieldOrder("left", "right", "top", "bottom")
internal class ChromeMargins : Structure() {
    @JvmField var left = 1; @JvmField var right = 1; @JvmField var top = 1; @JvmField var bottom = 1
}

@Structure.FieldOrder("cbSize", "flags", "hwnd", "hoverTime")
internal class ChromeTrackMouse(@JvmField var hwnd: HWND = HWND()) : Structure() {
    @JvmField var cbSize = size()
    @JvmField var flags = 0x12 // TME_LEAVE | TME_NONCLIENT
    @JvmField var hoverTime = 0
}
