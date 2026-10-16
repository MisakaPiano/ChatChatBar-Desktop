package com.example.chatbar.desktop

import java.awt.BorderLayout
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.WindowConstants

internal fun interface DesktopStartupBlockedPresenter {
    /** Returns after close, or throws if presentation is unavailable. Never constructs business UI. */
    fun show(diagnostic: DesktopStartupDiagnostic)
}

/** Fixed bilingual content, independent of persisted theme/language/settings and user paths. */
internal class DesktopStartupBlockedView(val diagnostic: DesktopStartupDiagnostic) {
    val closeLabel = "关闭 / Close"
    val copyLabel = "复制诊断代码 / Copy diagnostic code"
    val message = "当前不能启动普通业务。数据没有被自动清空或恢复，请保留现有文件。\n" +
        "Normal startup is blocked. No automatic data clearing or recovery was performed. Keep existing files.\n" +
        if (diagnostic.retainedProbe) "可能保留 Portable 探针文件；请保留证据。\nA Portable probe file may remain. Keep the evidence."
        else ""
    val diagnosticText = "CCB STARTUP_BLOCKED ${diagnostic.code}"
    fun copyDiagnostic(copy: (String) -> Unit) = copy(diagnosticText)
    fun close(onClose: () -> Unit) = onClose()
}

/** Real pre-container native prompt. Headless verification injects the interface above. */
internal object PlatformDesktopStartupBlockedPresenter : DesktopStartupBlockedPresenter {
    override fun show(diagnostic: DesktopStartupDiagnostic) {
        check(!EventQueue.isDispatchThread())
        val closed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        EventQueue.invokeLater {
            var frame: JFrame? = null
            try {
                val view = DesktopStartupBlockedView(diagnostic)
                frame = JFrame("ChatChatBar — 启动已阻断 / Startup blocked")
                val window = frame
                window.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
                window.addWindowListener(object : WindowAdapter() {
                    override fun windowClosed(event: WindowEvent) { closed.countDown() }
                })
                window.add(JTextArea(view.message + "\n\n" + view.diagnosticText).apply {
                    isEditable = false; lineWrap = true; wrapStyleWord = true
                    border = javax.swing.BorderFactory.createEmptyBorder(16, 16, 16, 16)
                }, BorderLayout.CENTER)
                window.add(JPanel().apply {
                    add(JButton(view.copyLabel).apply { addActionListener {
                        try { view.copyDiagnostic { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(it), null) } }
                        catch (_: Exception) { System.err.println("CCB STARTUP_BLOCKED ${diagnostic.code} COPY_FAILED") }
                    } })
                    add(JButton(view.closeLabel).apply { addActionListener { view.close { window.dispose() } } })
                }, BorderLayout.SOUTH)
                window.setSize(600, 260)
                window.setLocationRelativeTo(null)
                window.isVisible = true
            } catch (problem: Throwable) {
                failure.set(problem)
                try { frame?.dispose() } finally { closed.countDown() }
            }
        }
        // Do not leave a queued/later window behind on interruption. No storage IO is detached.
        var interrupted = false
        try {
            while (true) {
                try { closed.await(); break } catch (_: InterruptedException) { interrupted = true }
            }
            failure.get()?.let { throw it }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
}
