package com.example.chatbar.desktop

import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.Frame
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JFrame
import kotlinx.coroutines.runBlocking

/** Opt-in Windows/AWT evidence only. Real timer/window, isolated data; no Robot or user input. */
internal object DesktopShutdownWaitFixture {
    @JvmStatic fun main(args: Array<String>) {
        val path = Files.createTempDirectory("ccb-long-shutdown-fixture-")
        val root = DesktopDataRootResolution.Resolved(path, DesktopDataRootProvenance.CLI_OVERRIDE, path.resolve("unused.json"))
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val done = CompletableFuture<Unit>()
        var exitCode: Int? = null
        val worker = Thread {
            try {
                runDesktopProcessLifecycle(application = {
                    runDesktopApplicationWithDataRootOwnership(root) {
                        runBlocking { closeDesktopWithRecovery(close = {
                            entered.countDown()
                            // Intentionally blocks the closing thread. The independent deadline must still work.
                            release.await()
                            Files.writeString(path.resolve("finished.txt"), "safe writer completed")
                        }) }
                    }
                }, terminate = { exitCode = it }, reportFailure = { error("unexpected failure") })
                done.complete(Unit)
            } catch (failure: Throwable) { done.completeExceptionally(failure) }
        }
        worker.start()
        try {
            check(entered.await(5, TimeUnit.SECONDS))
            val frame = awaitFrame("ChatChatBar — 正在安全关闭 / Safe shutdown in progress")
            check(!done.isDone && exitCode == null && worker.isAlive)
            check(DesktopDataRootOwnership.acquire(root) is DesktopDataRootOwnershipResult.AlreadyInUse)
            EventQueue.invokeAndWait {
                check(buttons(frame).size == 1 && buttons(frame).single().text.startsWith("复制诊断"))
                frame.dispatchEvent(WindowEvent(frame, WindowEvent.WINDOW_CLOSING))
                check(frame.isDisplayable && frame.extendedState == Frame.ICONIFIED)
                frame.extendedState = Frame.NORMAL
            }
            println("WAIT_VISIBLE_BEFORE_RELEASE writerAlive=true lockHeld=true exitCalled=false")
            release.countDown()
            done.get(10, TimeUnit.SECONDS)
            check(exitCode == 0)
            EventQueue.invokeAndWait { check(!frame.isDisplayable) }
            check(Files.readString(path.resolve("finished.txt")) == "safe writer completed")
            (DesktopDataRootOwnership.acquire(root) as DesktopDataRootOwnershipResult.Acquired).ownership.close()
            println("WAIT_COMPLETE windowDisposed=true dataWritten=true lockReacquired=true exit=0")

            // Same renderer used by the indefinitely waiting production failure presenter.
            // Only this test owns disposal after proving the failure is visible and non-green.
            lateinit var held: DesktopDataRootOwnership
            var failureFrame: JFrame? = null
            runDesktopProcessLifecycle(application = {
                runDesktopApplicationWithDataRootOwnership(root, acquireOwnership = {
                    DesktopDataRootOwnership.acquire(it).also { result -> held = (result as DesktopDataRootOwnershipResult.Acquired).ownership }
                }) {
                    runBlocking { closeDesktopWithRecovery(close = { error("synthetic close failure") }) }
                }
            }, terminate = { error("unsafe exit") }, reportFailure = {
                EventQueue.invokeAndWait { failureFrame = DesktopShutdownRecoveryWindow.present(false) { error("unsafe retry") } }
                check(DesktopDataRootOwnership.acquire(root) is DesktopDataRootOwnershipResult.AlreadyInUse)
                EventQueue.invokeAndWait {
                    val failed = requireNotNull(failureFrame)
                    check(failed.isShowing)
                    check(buttons(failed).size == 1 && buttons(failed).single().text.startsWith("复制诊断"))
                    failed.dispatchEvent(WindowEvent(failed, WindowEvent.WINDOW_CLOSING))
                    check(failed.isDisplayable && failed.extendedState == Frame.ICONIFIED)
                }
                println("UNSAFE_FAILURE visible=true closeMinimizes=true lockHeld=true retryAbsent=true exitAbsent=true")
            })
            // No real writers exist in this failure fixture; release only its own synthetic resources.
            EventQueue.invokeAndWait { failureFrame?.dispose() }
            held.close()
        } finally {
            release.countDown()
            worker.join(10_000)
            check(!worker.isAlive)
            path.toFile().deleteRecursively()
        }
    }

    private fun awaitFrame(title: String): JFrame {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (System.nanoTime() < deadline) {
            var found: JFrame? = null
            EventQueue.invokeAndWait {
                found = Frame.getFrames().filterIsInstance<JFrame>().firstOrNull { it.title == title && it.isShowing }
            }
            found?.let { return it }
            Thread.yield()
        }
        error("Waiting status was not visible before deadline")
    }

    private fun buttons(component: Component): List<JButton> =
        (if (component is JButton) listOf(component) else emptyList()) +
            (if (component is Container) component.components.flatMap(::buttons) else emptyList())
}
