package com.example.chatbar.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import java.awt.EventQueue
import java.awt.Window
import java.nio.file.Path

/** Main attaches only its actual top-level ComposeWindow, never a foreground or renderer child. */
internal class DesktopDialogOwner {
    @Volatile private var attached: Pair<Window, Long>? = null

    fun attach(window: Window) {
        check(EventQueue.isDispatchThread())
        check(attached == null || attached?.first === window) { "A dialog owner is already attached" }
        if (DesktopWindowChrome.isWindows) {
            if (!window.isDisplayable) window.addNotify()
            val handle = HWND(Native.getWindowPointer(window))
            check(User32.INSTANCE.GetAncestor(handle, 2) == handle) { "Dialog owner must be the top-level HWND" }
            attached = window to Pointer.nativeValue(handle.pointer)
        }
    }

    fun detach(window: Window) {
        check(EventQueue.isDispatchThread())
        if (attached?.first === window) attached = null
    }

    fun currentHandle(): Long? = attached?.takeIf { (window, handle) ->
        window.isDisplayable && User32.INSTANCE.IsWindow(HWND(Pointer(handle)))
    }?.second

    fun requireHandle(): Long = checkNotNull(currentHandle()) { "Native file dialog requires the attached main window" }
}

internal class WindowsNativeFilePicker(private val service: WindowsCommonItemDialog) : DesktopFilePicker {
    override fun pickOpenFile(type: DesktopFileType): Path? = service.pick(DesktopDialogRequest(DesktopDialogKind.OPEN, type))
    override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? =
        service.pick(DesktopDialogRequest(DesktopDialogKind.SAVE, type, suggestedName))
}

internal class WindowsNativeDirectoryPicker(private val service: WindowsCommonItemDialog) : DesktopDirectoryPicker {
    override fun pickDirectory(): Path? = service.pick(DesktopDialogRequest(DesktopDialogKind.FOLDER))
}

internal class DesktopPlatformPickers private constructor(
    val files: DesktopFilePicker,
    val directories: DesktopDirectoryPicker,
    private val closeService: () -> Unit,
) : AutoCloseable {
    override fun close() = closeService()

    companion object {
        fun create(owner: DesktopDialogOwner): DesktopPlatformPickers = if (DesktopWindowChrome.isWindows) {
            val service = WindowsCommonItemDialog(owner::requireHandle)
            DesktopPlatformPickers(WindowsNativeFilePicker(service), WindowsNativeDirectoryPicker(service), service::close)
        } else {
            // No COM/User32 initialization on non-Windows platforms.
            DesktopPlatformPickers(SwingDesktopFilePicker(), SwingDesktopDirectoryPicker()) {}
        }
    }
}

/** Headless/container test default. Production must inject the application-owned picker. */
internal object UnconfiguredDesktopFilePicker : DesktopFilePicker {
    override fun pickOpenFile(type: DesktopFileType): Path? = error("Desktop file picker was not injected")
    override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = error("Desktop file picker was not injected")
}
