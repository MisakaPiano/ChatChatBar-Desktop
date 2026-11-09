@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.awt.ComposeWindow
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopEditorAuxiliaryWindowNativeTest {
    @org.junit.Before fun graphics() = requireDesktopGraphicsForNativeTest()
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `editor auxiliary host is movable resizable retains content and supplies its exact native picker owner`() = runBlocking(edt) {
        val title = "Synthetic editor auxiliary fixture"
        val child = AtomicReference<ComposeWindow?>()
        val owner = AtomicReference<java.awt.Window?>()
        val listener = AWTEventListener { event ->
            if (event is WindowEvent && event.id == WindowEvent.WINDOW_OPENED && event.window is ComposeWindow &&
                (event.window as ComposeWindow).title == title) child.set(event.window as ComposeWindow)
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.WINDOW_EVENT_MASK)
        var open by mutableStateOf(true)
        var creations = 0
        val activity = DesktopModalActivity()
        val scene = ImageComposeScene(16, 16) {
            CompositionLocalProvider(LocalDesktopModalActivity provides activity) {
                if (open) DesktopEditorAuxiliaryWindow(title, { open = false }) {
                    val actual = LocalDesktopFishParentWindow.current
                    SideEffect { owner.set(actual) }
                    DisposableEffect(Unit) { creations++; onDispose { } }
                    StatusText("Synthetic retained editor content")
                }
            }
        }
        suspend fun await(predicate: () -> Boolean) = withTimeout(15_000) {
            while (!predicate()) { scene.render(System.nanoTime()).close(); delay(20) }
        }
        try {
            await { child.get()?.isShowing == true && owner.get() != null && creations == 1 }
            val window = assertNotNull(child.get())
            assertTrue(window.isResizable)
            assertSame(window, owner.get())
            assertEquals(1, activity.count)
            val x = window.x; val y = window.y
            window.setLocation(x + 16, y + 16)
            window.setSize(760, 640)
            await { window.width == 760 && window.height == 640 && window.x == x + 16 && window.y == y + 16 }
            assertEquals(1, creations)
            assertSame(window, owner.get())
            window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
            await { !open && !window.isDisplayable && activity.count == 0 }
        } finally {
            open = false; scene.close()
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
            child.get()?.dispose()
        }
    }
}
