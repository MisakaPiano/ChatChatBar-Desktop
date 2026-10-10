package com.example.chatbar.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import java.awt.EventQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** All bounds are measured Compose client pixels, never estimated from translated labels. */
internal data class ChromeRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

internal enum class ChromeHit(val nativeValue: Int) {
    CLIENT(1), CAPTION(2), SYSTEM_MENU(3), MINIMIZE(8), MAXIMIZE(9),
    LEFT(10), RIGHT(11), TOP(12), TOP_LEFT(13), TOP_RIGHT(14),
    BOTTOM(15), BOTTOM_LEFT(16), BOTTOM_RIGHT(17), CLOSE(20),
}

internal data class DesktopChromeLayout(
    val width: Float = 0f,
    val height: Float = 0f,
    val title: ChromeRect? = null,
    val interactive: List<ChromeRect> = emptyList(),
    val captions: Map<ChromeHit, ChromeRect> = emptyMap(),
    val icon: ChromeRect? = null,
    val dragRegions: List<ChromeRect> = emptyList(),
)

internal object DesktopChromeHitTest {
    /** Physical client coordinates enter here; the layout may still be at the previous DPI/size. */
    fun hit(x: Float, y: Float, clientWidth: Float, clientHeight: Float, edge: Float,
        maximized: Boolean, layout: DesktopChromeLayout): ChromeHit {
        if (x < 0 || y < 0 || x >= clientWidth || y >= clientHeight) return ChromeHit.CLIENT
        if (!maximized) {
            val left = x < edge; val right = x >= clientWidth - edge
            val top = y < edge; val bottom = y >= clientHeight - edge
            when {
                top && left -> return ChromeHit.TOP_LEFT
                top && right -> return ChromeHit.TOP_RIGHT
                bottom && left -> return ChromeHit.BOTTOM_LEFT
                bottom && right -> return ChromeHit.BOTTOM_RIGHT
                left -> return ChromeHit.LEFT
                right -> return ChromeHit.RIGHT
                top -> return ChromeHit.TOP
                bottom -> return ChromeHit.BOTTOM
            }
        }
        if (layout.width <= 0 || layout.height <= 0) return ChromeHit.CLIENT
        val lx = x * layout.width / clientWidth
        val ly = y * layout.height / clientHeight
        layout.captions.entries.firstOrNull { it.value.contains(lx, ly) }?.let { return it.key }
        if (layout.interactive.any { it.contains(lx, ly) }) return ChromeHit.CLIENT
        if (layout.icon?.contains(lx, ly) == true) return ChromeHit.SYSTEM_MENU
        // External maximized edges stay stationary; caption buttons retain earlier priority.
        val maximizedEdge = maximized && (x < edge || y < edge ||
            x >= clientWidth - edge || y >= clientHeight - edge)
        if (!maximizedEdge && layout.dragRegions.any { it.contains(lx, ly) }) return ChromeHit.CAPTION
        return if (layout.title?.contains(lx, ly) == true) ChromeHit.CAPTION else ChromeHit.CLIENT
    }

    /** GET_X/Y_LPARAM sign extension is required for monitors left/above the primary monitor. */
    fun screenPoint(lParam: Long) = (lParam.toInt() and 0xffff).toShort().toInt() to
        ((lParam shr 16).toInt() and 0xffff).toShort().toInt()

    fun screenToClient(screenX: Int, screenY: Int, originX: Int, originY: Int) =
        (screenX - originX) to (screenY - originY)
}

internal enum class DesktopWindowCommand { MINIMIZE, MAXIMIZE, RESTORE, CLOSE }
internal fun desktopCaptionCommand(hit: ChromeHit, maximized: Boolean) = when (hit) {
    ChromeHit.MINIMIZE -> DesktopWindowCommand.MINIMIZE
    ChromeHit.MAXIMIZE -> if (maximized) DesktopWindowCommand.RESTORE else DesktopWindowCommand.MAXIMIZE
    ChromeHit.CLOSE -> DesktopWindowCommand.CLOSE
    else -> null
}

internal data class DesktopChromeState(
    val maximized: Boolean = false,
    val minimized: Boolean = false,
    val hovered: ChromeHit? = null,
    val pressed: ChromeHit? = null,
    val failure: String? = null,
)

internal class DesktopWindowChrome(private val closeRequest: () -> Unit) {
    companion object { val isWindows: Boolean get() = System.getProperty("os.name").startsWith("Windows", true) }
    private val mutableState = MutableStateFlow(DesktopChromeState())
    val state = mutableState.asStateFlow()
    @Volatile var layout = DesktopChromeLayout()
        private set
    internal var platform: WindowsWindowChrome? = null

    fun updateLayout(value: DesktopChromeLayout) { layout = value; platform?.refreshChildren() }
    internal fun updateState(transform: (DesktopChromeState) -> DesktopChromeState) {
        synchronized(mutableState) { mutableState.value = transform(mutableState.value) }
    }
    fun command(command: DesktopWindowCommand) {
        if (command == DesktopWindowCommand.CLOSE) closeRequest() else platform?.command(command)
    }
    internal fun requestNativeClose() { EventQueue.invokeLater { command(DesktopWindowCommand.CLOSE) } }
    fun appearance(dark: Boolean) { platform?.appearance(dark) }
}

/** Native HT edges already own resizing. Compose's separate undecorated overlay can resize a
 * maximized HWND through setBounds even when WM_NCHITTEST correctly returns HTCLIENT. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal class DesktopNativeResizeOwnership(private val window: ComposeWindow) : AutoCloseable {
    private val previous = window.undecoratedResizerThickness
    init { window.undecoratedResizerThickness = 0.dp }
    override fun close() { window.undecoratedResizerThickness = previous }
}

@Composable
internal fun rememberDesktopWindowChrome(window: ComposeWindow, onCloseRequest: () -> Unit): DesktopWindowChrome {
    val close = rememberUpdatedState(onCloseRequest)
    val chrome = remember(window) { DesktopWindowChrome { close.value() } }
    DisposableEffect(window, chrome) {
        val native = if (DesktopWindowChrome.isWindows) WindowsWindowChrome.install(window, chrome) else null
        val resizing = if (native != null) DesktopNativeResizeOwnership(window) else null
        chrome.platform = native
        onDispose { chrome.platform = null; native?.close(); resizing?.close() }
    }
    return chrome
}
