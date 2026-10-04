package com.example.chatbar.desktop

import java.awt.Component
import java.awt.EventQueue
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

internal data class DesktopFileType(
    val description: String,
    val extensions: List<String>,
    val defaultExtension: String = extensions.first(),
)

internal interface DesktopFilePicker {
    fun pickOpenFile(type: DesktopFileType): Path?
    fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path?
}

internal class SwingDesktopFilePicker(private val parent: Component? = null) : DesktopFilePicker {
    override fun pickOpenFile(type: DesktopFileType): Path? = choose(type, null, save = false)

    override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? =
        choose(type, suggestedName, save = true)?.let { selected ->
            if (selected.fileName.toString().substringAfterLast('.', "").lowercase() in type.extensions) selected
            else selected.resolveSibling("${selected.fileName}.${type.defaultExtension}")
        }

    private fun choose(type: DesktopFileType, suggestedName: String?, save: Boolean): Path? {
        val selected = AtomicReference<Path?>()
        val action = Runnable {
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.FILES_ONLY
                isAcceptAllFileFilterUsed = false
                fileFilter = FileNameExtensionFilter(type.description, *type.extensions.toTypedArray())
                suggestedName?.let { selectedFile = java.io.File(it) }
            }
            val result = if (save) chooser.showSaveDialog(parent) else chooser.showOpenDialog(parent)
            if (result == JFileChooser.APPROVE_OPTION) {
                selected.set(chooser.selectedFile.toPath().toAbsolutePath().normalize())
            }
        }
        if (EventQueue.isDispatchThread()) action.run() else SwingUtilities.invokeAndWait(action)
        return selected.get()
    }
}
