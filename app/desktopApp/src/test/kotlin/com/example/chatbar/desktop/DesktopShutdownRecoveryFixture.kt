package com.example.chatbar.desktop

import java.awt.EventQueue
import java.awt.Frame
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JPanel
import kotlinx.coroutines.runBlocking

/** Opt-in real AWT recovery surface fixture. No Robot, external windows, or user profile. */
internal object DesktopShutdownRecoveryFixture {
    @JvmStatic fun main(args: Array<String>) {
        val root = Files.createTempDirectory("ccb-shutdown-recovery-fixture-")
        val selected = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, root.resolveSibling("unused.json"))
        val finished = CompletableFuture<Unit>()
        var attempts = 0
        val worker = Thread {
            try {
                runDesktopApplicationWithDataRootOwnership(selected) {
                    runBlocking {
                        closeDesktopWithRecovery(close = {
                            if (attempts++ == 0) throw DesktopDraftDrainTimeoutException()
                        })
                    }
                }
                finished.complete(Unit)
            } catch (failure: Throwable) { finished.completeExceptionally(failure) }
        }
        worker.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var recovery: JFrame? = null
        while (recovery == null && System.nanoTime() < deadline) {
            EventQueue.invokeAndWait {
                recovery = Frame.getFrames().filterIsInstance<JFrame>().firstOrNull {
                    it.title == "ChatChatBar — 安全关闭 / Safe shutdown" && it.isShowing
                }
            }
            Thread.yield()
        }
        check(recovery != null) { "Visible recovery window was not created" }
        check(DesktopDataRootOwnership.acquire(selected) is DesktopDataRootOwnershipResult.AlreadyInUse)
        EventQueue.invokeAndWait {
            val frame = requireNotNull(recovery)
            frame.dispatchEvent(WindowEvent(frame, WindowEvent.WINDOW_CLOSING))
            check(frame.isDisplayable && frame.extendedState == Frame.ICONIFIED)
            frame.extendedState = Frame.NORMAL
            val button = frame.contentPane.components.filterIsInstance<JPanel>().flatMap { it.components.toList() }
                .filterIsInstance<JButton>().single { it.text.startsWith("重试安全关闭") }
            button.doClick()
        }
        finished.get(10, TimeUnit.SECONDS)
        check(attempts == 2)
        (DesktopDataRootOwnership.acquire(selected) as DesktopDataRootOwnershipResult.Acquired).ownership.close()
        EventQueue.invokeAndWait { check(!requireNotNull(recovery).isDisplayable) }
        println("S0D_VISIBLE_RECOVERY_LOCK_RETRY_PASS")
        root.toFile().deleteRecursively()
    }
}
