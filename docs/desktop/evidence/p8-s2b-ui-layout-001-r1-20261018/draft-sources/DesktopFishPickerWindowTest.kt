@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.test.*

class DesktopFishPickerWindowTest {
    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private suspend fun ImageComposeScene.frames() { repeat(4) { render().close(); yield(); delay(10) } }
    private fun ImageComposeScene.labels(): List<String> {
        fun walk(n: SemanticsNode): List<String> = n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }

    @Test fun `missing explicit main owner fails closed without composing picker content`() = runBlocking(awt) {
        var contentCreated = false
        val scene = ImageComposeScene(500, 200) {
            DesktopFishPickerWindow(onClose = {}) { SideEffect { contentCreated = true } }
        }
        try {
            scene.frames()
            assertFalse(contentCreated)
            assertTrue("音色库未打开：主窗口身份不可用" in scene.labels())
        } finally { scene.close() }
    }

    @Test fun `identity scoped overlapping leases restore once in either order and remove callbacks`() = runBlocking(awt) {
        val parent = ComposeWindow()
        val other = ComposeWindow()
        try {
            val baseline = parent.windowListeners.size
            repeat(4) { iteration ->
                val first = DesktopFishWindowOwnerLease(parent) {}
                val second = DesktopFishWindowOwnerLease(parent) {}
                assertFalse(parent.isEnabled); assertTrue(other.isEnabled)
                if (iteration % 2 == 0) { first.close(); assertFalse(parent.isEnabled); second.close() }
                else { second.close(); assertFalse(parent.isEnabled); first.close() }
                first.close(); second.close()
                assertTrue(parent.isEnabled); assertEquals(baseline, parent.windowListeners.size)
            }
            parent.isEnabled = false
            DesktopFishWindowOwnerLease(parent) {}.close()
            assertFalse(parent.isEnabled); assertTrue(other.isEnabled)
            parent.isEnabled = true
            var closes = 0
            var lease: DesktopFishWindowOwnerLease? = null
            lease = DesktopFishWindowOwnerLease(parent) { closes++; lease?.close() }
            parent.dispatchEvent(WindowEvent(parent, WindowEvent.WINDOW_CLOSING))
            assertEquals(1, closes); assertTrue(parent.isEnabled)
            parent.dispatchEvent(WindowEvent(parent, WindowEvent.WINDOW_CLOSING))
            assertEquals(1, closes); assertEquals(baseline, parent.windowListeners.size)
        } finally { parent.dispose(); other.dispose() }
    }

    @Test fun `public main WindowScope supplies exact owner through maximize restore reopen and parent close`() = runBlocking(awt) {
        var hostOpen by mutableStateOf(true)
        var pickerOpen by mutableStateOf(true)
        val main = AtomicReference<ComposeWindow?>()
        val captured = AtomicReference<ComposeWindow?>()
        val pixels = AtomicReference(IntSize.Zero)
        val created = AtomicInteger()
        val disposed = AtomicInteger()
        var pickerState: WindowState? = null
        val scene = ImageComposeScene(16, 16) {
            val state = rememberWindowState(width = 600.dp, height = 500.dp)
            SideEffect { pickerState = state }
            if (hostOpen) Window(onCloseRequest = { hostOpen = false }, title = "Fish owner test host",
                state = rememberWindowState(width = 800.dp, height = 600.dp)) {
                val actualMain = window
                SideEffect { main.set(actualMain) }
                CompositionLocalProvider(LocalDesktopFishParentWindow provides actualMain) {
                    if (pickerOpen) DesktopFishPickerWindow(onClose = { pickerOpen = false }, state = state,
                        title = "Fish owned native fixture") {
                        val providedOwner = LocalDesktopFishParentWindow.current
                        SideEffect { captured.set(providedOwner) }
                        DisposableEffect(Unit) { created.incrementAndGet(); onDispose { disposed.incrementAndGet() } }
                        Box(Modifier.fillMaxSize().onSizeChanged { pixels.set(it) })
                    }
                }
            }
        }
        suspend fun await(check: () -> Boolean) = withTimeout(15_000) {
            while (!check()) { scene.frames(); delay(10) }
        }
        try {
            await { main.get() != null && captured.get() === main.get() && pixels.get().width > 0 &&
                created.get() == 1 && !main.get()!!.isEnabled }
            assertSame(main.get(), captured.get())
            assertFalse(main.get()!!.isEnabled); assertEquals(1, created.get())
            val initial = pixels.get()
            pickerState!!.placement = WindowPlacement.Maximized
            await { pixels.get().width > initial.width && pixels.get().height > initial.height }
            val maximized = pixels.get()
            assertEquals(1, created.get()); assertFalse(main.get()!!.isEnabled)
            pickerState!!.placement = WindowPlacement.Floating
            await { abs(pixels.get().width - initial.width) <= 4 && abs(pixels.get().height - initial.height) <= 4 }
            assertEquals(1, created.get()); assertSame(main.get(), captured.get())
            println("FISH_NATIVE_OWNER maximize=${maximized.width}x${maximized.height} restore=${pixels.get().width}x${pixels.get().height} compositionInstances=1")
            pickerOpen = false
            await { disposed.get() == 1 && main.get()!!.isEnabled }
            pickerOpen = true
            await { created.get() == 2 && !main.get()!!.isEnabled }
            main.get()!!.dispatchEvent(WindowEvent(main.get(), WindowEvent.WINDOW_CLOSING))
            await { !hostOpen && disposed.get() == 2 }
            assertTrue(main.get()!!.isEnabled)
        } finally {
            pickerOpen = false; hostOpen = false
            scene.close()
            repeat(4) { yield(); delay(10) }
            main.get()?.dispose()
        }
    }
}
