package com.example.chatbar.desktop

import java.awt.BorderLayout
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.CountDownLatch
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.WindowConstants
import kotlin.system.exitProcess

/** A close failure is not evidence that all writers stopped, even when it is not a timeout. */
internal class DesktopUnsafeShutdownException(cause: Throwable) :
    IllegalStateException("Desktop shutdown did not complete", cause)

/** Only successful completion of the entire outer ownership/picker lifetime may terminate the JVM. */
internal fun runDesktopProcessLifecycle(
    application: () -> Unit,
    terminate: (Int) -> Unit = ::exitProcess,
    reportFailure: () -> Unit = { DesktopShutdownRecoveryWindow.awaitAction(retryAllowed = false) },
) {
    var exitCode = 0
    try {
        application()
    } catch (_: DesktopStartupBlockedException) {
        // Only emitted after structured preflight/probe completion and successful ownership
        // release (or before ownership acquisition). No business writers were constructed.
        exitCode = 2
    } catch (_: Throwable) {
        DesktopShutdownTrace.record(DesktopShutdownStage.SHUTDOWN_FAILED)
        reportFailure()
        return
    }
    DesktopShutdownTrace.record(DesktopShutdownStage.SAFE_EXIT_HANDOFF)
    terminate(exitCode)
}

/** Retry only documented writer drains and retained numeric tails, never a failed entity transaction. */
internal suspend fun closeDesktopWithRecovery(
    close: suspend () -> Unit,
    awaitRetry: (() -> Unit)? = null,
    observeWait: () -> AutoCloseable = { DesktopShutdownWaitObserver() },
) {
    while (true) {
        try {
            // The observer owns only its timer/window, never the writer or its coroutine Job.
            // Dispose before recovery presentation, or before picker/root/terminal cleanup.
            observeWait().use { close() }
            return
        } catch (failure: Throwable) {
            val resumable = failure is DesktopTaskDrainTimeoutException ||
                failure is DesktopDraftDrainTimeoutException || failure is DesktopReadingPositionDrainTimeoutException ||
                failure is DesktopUsageDrainException
            if (!resumable) throw DesktopUnsafeShutdownException(failure)
            DesktopShutdownTrace.record(DesktopShutdownStage.DRAIN_DEFERRED)
            try {
                if (awaitRetry != null) awaitRetry()
                else DesktopShutdownRecoveryWindow.awaitAction(retryAllowed = true, statisticsPending = failure is DesktopUsageDrainException)
            } catch (presentationFailure: Throwable) {
                failure.addSuppressed(presentationFailure)
                throw DesktopUnsafeShutdownException(failure)
            }
        }
    }
}

/** Remains visible after Compose disposal; closing this status window minimizes it, never exits. */
internal object DesktopShutdownRecoveryWindow {
    fun awaitAction(retryAllowed: Boolean, statisticsPending: Boolean = false) {
        check(!EventQueue.isDispatchThread())
        val retry = CountDownLatch(1)
        EventQueue.invokeAndWait { present(retryAllowed, statisticsPending = statisticsPending) { retry.countDown() } }
        // An interrupted waiting thread must not release the ownership of unfinished writers.
        while (true) {
            try { retry.await(); return } catch (_: InterruptedException) { /* remain in visible recovery */ }
        }
    }

    /** EDT presentation shared by the real blocking recovery and isolated lifecycle verification. */
    internal fun present(retryAllowed: Boolean, onRetry: () -> Unit): JFrame = present(retryAllowed, false, onRetry)

    internal fun present(retryAllowed: Boolean, statisticsPending: Boolean, onRetry: () -> Unit): JFrame {
        check(EventQueue.isDispatchThread())
        val frame = JFrame("ChatChatBar — 安全关闭 / Safe shutdown")
        DesktopBrandResources.applyTo(frame)
        val diagnostic = "CCB shutdown: ${if (retryAllowed) "DRAIN_DEFERRED" else "SHUTDOWN_FAILED"}"
        val message = if (statisticsPending) {
            "部分本地用量统计尚未保存，资料目录锁仍保留。请检查磁盘空间或写入权限，再点“重试安全关闭”。\n" +
                "重试只保存待写统计，不会重新发送AI请求。不要移除资料目录锁。\n\n" +
                "Local usage records are pending. Restore writable storage, then retry safe shutdown. No AI request is repeated."
        } else if (retryAllowed) {
            "关闭尚未完成，正在保护数据。请等待后台写入结束后点击“重试安全关闭”。\n" +
                "不要强制结束进程或移除数据目录锁。\n\n" +
                "Shutdown is waiting for writers. Retry after work finishes. Do not force quit or remove the data lock."
        } else {
            "未能确认安全关闭。请保留此窗口，并复制诊断编号向 Project 报告以取得恢复步骤。\n" +
                "不要强制结束进程、清理数据或移除数据目录锁。\n\n" +
                "Safe shutdown could not be confirmed. Keep this window open and report the diagnostic code for recovery."
        }
        frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent) { frame.extendedState = Frame.ICONIFIED }
        })
        frame.add(JTextArea("$message\n\n$diagnostic").apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true
            border = javax.swing.BorderFactory.createEmptyBorder(16, 16, 16, 16)
        }, BorderLayout.CENTER)
        frame.add(JPanel().apply {
            add(JButton("复制诊断编号 / Copy diagnostic code").apply {
                addActionListener { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(diagnostic), null) }
            })
            if (retryAllowed) add(JButton("重试安全关闭 / Retry safe shutdown").apply {
                addActionListener { frame.dispose(); onRetry() }
            })
        }, BorderLayout.SOUTH)
        frame.setSize(640, 290)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        return frame
    }
}
