package com.example.chatbar.desktop

import java.awt.EventQueue
import java.awt.Frame
import java.awt.SystemTray
import java.awt.event.ActionEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Cloud-only real AWT tray/window plus original close/exit owners; not a packaged Main or OS mouse-gesture proof. */
class DesktopNativeTrayLifecycleTest {
    private fun <T> awt(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
    private suspend fun until(check: () -> Boolean) = withTimeout(10000) { while (!check()) delay(20) }

    @Test fun `real tray main close hides restores and explicit exit retains dirty continuation then drains original work`(): Unit = runBlocking {
        val output = System.getenv("CCB_NATIVE_TRAY_REVIEW_DIR")?.let(Path::of)
        assumeTrue("Opt-in cloud native tray only", output != null && System.getenv("GITHUB_ACTIONS") == "true")
        val supported = awt { SystemTray.isSupported() }
        Files.createDirectories(output!!)
        Files.writeString(output.resolve("native-tray-capability.json"), """{"supported":$supported,"packagedMain":false}""")
        assumeTrue("Runner does not expose an AWT system tray; no native availability claim", supported)
        val root = Files.createTempDirectory("native-tray-owned-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val center = DesktopTaskCenter()
        val gate = DesktopDataOperationCoordinator()
        val tray = DesktopTrayController(center, DesktopSettingsStore(root, gate))
        val frame = awt { Frame("Synthetic CCB tray lifecycle").apply { setSize(480, 280); isVisible = true } }
        val dirty = AtomicReference<(() -> Unit)?>()
        val drained = CompletableDeferred<Unit>()
        val originalWork = scope.launch { awaitCancellation() }
        val finish: () -> Unit = { scope.launch { tray.prepareExit(); originalWork.cancelAndJoin(); drained.complete(Unit) } }
        val allow: (() -> Unit) -> Unit = { it() }
        val guard = DesktopApplicationExitGuard({ next -> dirty.set(next) }, allow, allow, allow, allow, finish)
        var visibleJob: Job? = null
        val initialIcons = awt { SystemTray.getSystemTray().trayIcons.toSet() }
        try {
            tray.initialize(onShow = { awt { frame.isVisible = true } }, onExit = { guard.requestExit() })
            assertTrue(tray.state.value.available)
            visibleJob = scope.launch { tray.state.collect { state -> awt { frame.isVisible = state.windowVisible } } }
            awt { frame.addWindowListener(object : WindowAdapter() {
                override fun windowClosing(event: WindowEvent) {
                    desktopMainWindowClose(DesktopDataRootSwitchState.Idle(root, DesktopDataRootProvenance.CLI_OVERRIDE, supported = true),
                        tray::hideOnWindowClose) { guard.requestExit() }
                }
            }) }
            tray.setCloseWindowToTray(true)
            until { tray.state.value.closeWindowToTray }
            awt { frame.dispatchEvent(WindowEvent(frame, WindowEvent.WINDOW_CLOSING)) }
            until { !tray.state.value.windowVisible && !awt { frame.isVisible } }
            assertTrue(originalWork.isActive); assertNull(dirty.get()); assertFalse(drained.isCompleted)
            val icon = awt { (SystemTray.getSystemTray().trayIcons.toSet() - initialIcons).single() }
            fun action(label: String) = awt {
                val menu = icon.popupMenu
                val item = (0 until menu.itemCount).map { menu.getItem(it) }.single { it.label == label }
                item.actionListeners.forEach { it.actionPerformed(ActionEvent(item, ActionEvent.ACTION_PERFORMED, item.actionCommand)) }
            }
            action("打开主窗口")
            until { tray.state.value.windowVisible && awt { frame.isVisible } }
            action("退出")
            until { dirty.get() != null }
            assertTrue(tray.state.value.windowVisible); assertTrue(originalWork.isActive); assertFalse(drained.isCompleted)
            requireNotNull(dirty.getAndSet(null)).invoke()
            withTimeout(10000) { drained.await() }
            assertTrue(originalWork.isCompleted)
            requireNotNull(visibleJob).cancelAndJoin(); visibleJob = null
            tray.closeAndDrain()
            assertTrue(awt { icon !in SystemTray.getSystemTray().trayIcons })
            awt { frame.dispose() }; assertFalse(awt { frame.isDisplayable })
            val saved = assertIs<DesktopSettingsLoadResult.Loaded>(DesktopSettingsStore(root, gate).load())
            assertTrue(saved.document.settings.closeWindowToTray)
            Files.writeString(output.resolve("native-tray-lifecycle.json"), """{"passed":true,"actualAwtTray":true,"mainCloseHide":true,"trayMenuRestore":true,"exitDirtyDeferred":true,"originalWorkDrained":true,"iconRemoved":true,"frameDisposed":true,"packagedMain":false,"osMouseGesture":false}""")
        } finally {
            visibleJob?.cancelAndJoin(); tray.closeAndDrain(); center.closeAndDrain(); scope.coroutineContext[Job]!!.cancelAndJoin()
            awt { frame.dispose() }; gate.closeAndDrain(); check(root.toFile().deleteRecursively())
        }
    }
}
