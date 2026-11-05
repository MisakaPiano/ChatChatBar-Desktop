package com.example.chatbar.desktop

import java.awt.BorderLayout
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.Timer
import java.util.TimerTask
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JTextArea
import javax.swing.WindowConstants

/** A visibility deadline, NOT a timeout of the data operation. Independent of runBlocking/IO. */
internal class DesktopShutdownWaitObserver(
    warningDelayMillis: Long = 5_000,
    schedule: (Long, () -> Unit) -> AutoCloseable = ::scheduleShutdownWarning,
    private val dispatch: (() -> Unit) -> Unit = { EventQueue.invokeLater(it) },
    private val dispatchAndWait: (() -> Unit) -> Unit = {
        if (EventQueue.isDispatchThread()) it() else EventQueue.invokeAndWait(it)
    },
    private val show: () -> AutoCloseable = ::showShutdownWaitingWindow,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    // Accessed only through EDT dispatch in production.
    private var window: AutoCloseable? = null
    private val timer = schedule(warningDelayMillis) {
        synchronized(lock) {
            if (closed) return@schedule
        }
        // This fixed diagnostic also records an EDT which has not yet serviced presentation.
        System.err.println("CCB_SHUTDOWN_WAIT VISIBILITY_REQUESTED")
        dispatch {
            synchronized(lock) {
                if (!closed) {
                    try {
                        window = show()
                        System.err.println("CCB_SHUTDOWN_WAIT VISIBLE")
                    } catch (_: Exception) {
                        // Never cancel writers or turn a presentation failure into successful exit.
                        System.err.println("CCB_SHUTDOWN_WAIT UI_UNAVAILABLE")
                    }
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        timer.close()
        // Flush queued presentation too: a late EDT runnable cannot resurrect a completed close.
        dispatchAndWait {
            window?.close()
            window = null
        }
    }
}

private fun scheduleShutdownWarning(delayMillis: Long, action: () -> Unit): AutoCloseable {
    require(delayMillis >= 0)
    val timer = Timer("ccb-shutdown-visibility", true)
    timer.schedule(object : TimerTask() { override fun run() = action() }, delayMillis)
    return AutoCloseable { timer.cancel() }
}

/** No stop/force-exit/retry control: the original close is still running and remains sole owner. */
private fun showShutdownWaitingWindow(): AutoCloseable {
    check(EventQueue.isDispatchThread())
    val frame = JFrame("ChatChatBar — 正在安全关闭 / Safe shutdown in progress")
    DesktopBrandResources.applyTo(frame)
    frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
    frame.addWindowListener(object : WindowAdapter() {
        override fun windowClosing(event: WindowEvent) { frame.extendedState = Frame.ICONIFIED }
    })
    val diagnostic = "CCB shutdown: SAFE_CLOSE_PENDING"
    frame.add(JTextArea(
        "正在等待后台任务、备份或数据写入安全结束。完成后将自动关闭此窗口并退出。\n" +
            "请勿强制结束进程或移除数据目录锁。若持续无法完成，请复制诊断编号向 Project 报告。\n\n" +
            "Safe shutdown is still running. This window closes automatically when it finishes. " +
            "Do not force quit or remove the data lock.\n\n$diagnostic"
    ).apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true
        border = javax.swing.BorderFactory.createEmptyBorder(16, 16, 16, 16)
    }, BorderLayout.CENTER)
    frame.add(JButton("复制诊断编号 / Copy diagnostic code").apply {
        addActionListener { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(diagnostic), null) }
    }, BorderLayout.SOUTH)
    frame.setSize(640, 290)
    frame.setLocationRelativeTo(null)
    frame.isVisible = true
    return AutoCloseable { frame.dispose() }
}
