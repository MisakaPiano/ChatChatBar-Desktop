package com.example.chatbar.desktop

import java.awt.Component
import java.awt.EventQueue
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

fun interface DesktopDirectoryPicker {
    /** Returns an absolute normalized directory selection, or null when the user cancels. */
    fun pickDirectory(): Path?
    fun pickDirectory(title: String): Path? = pickDirectory()
}

class SwingDesktopDirectoryPicker(
    private val parent: Component? = null,
) : DesktopDirectoryPicker {
    override fun pickDirectory(): Path? = pickDirectory("Choose a new ChatChatBar data directory")
    override fun pickDirectory(title: String): Path? {
        val selected = AtomicReference<Path?>()
        val showPicker = Runnable {
            val chooser = JFileChooser().apply {
                dialogTitle = title
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isAcceptAllFileFilterUsed = false
            }
            if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                selected.set(chooser.selectedFile.toPath().toAbsolutePath().normalize())
            }
        }
        if (EventQueue.isDispatchThread()) {
            showPicker.run()
        } else {
            SwingUtilities.invokeAndWait(showPicker)
        }
        return selected.get()
    }
}
