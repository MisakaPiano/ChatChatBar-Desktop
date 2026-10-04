package com.example.chatbar.desktop

import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Path
import java.util.Locale

internal fun interface DesktopAlreadyInUsePresenter {
    fun showAlreadyInUse(appDataRoot: Path)
}

internal data class DesktopAlreadyInUseMessage(val title: String, val text: String)

/** No settings/repository access: this process does not own the selected data root. */
internal fun desktopAlreadyInUseMessage(root: Path, locale: Locale): DesktopAlreadyInUseMessage {
    val path = root.toAbsolutePath().normalize()
    val text = if (locale.language == "zh") {
        """
        ChatChatBar 已在运行

        当前数据目录正在被另一个 ChatChatBar Desktop 实例使用。
        为保护数据，同一数据目录不能同时由多个实例写入。

        请切换到已经打开的 ChatChatBar 窗口，或先关闭它后再试。

        数据目录：
        $path
        """.trimIndent()
    } else {
        """
        ChatChatBar is already using this data directory.

        The current data directory is in use by another ChatChatBar Desktop instance.
        To protect your data, the same data directory cannot be written by multiple instances at the same time.

        Switch to the already running ChatChatBar window, or close it before trying again.

        Data directory:
        $path
        """.trimIndent()
    }
    return DesktopAlreadyInUseMessage("ChatChatBar", text)
}

internal interface StartupMessageBoxApi : StdCallLibrary {
    fun MessageBoxW(owner: HWND?, text: WString, caption: WString, flags: Int): Int
}

internal class WindowsStartupMessagePresenter(
    private val api: () -> StartupMessageBoxApi = { Native.load("user32", StartupMessageBoxApi::class.java) },
    private val locale: () -> Locale = Locale::getDefault,
) : DesktopAlreadyInUsePresenter {
    override fun showAlreadyInUse(appDataRoot: Path) {
        val message = desktopAlreadyInUseMessage(appDataRoot, locale())
        // Startup has no main HWND. MB_OK | MB_ICONINFORMATION | MB_SETFOREGROUND.
        check(api().MessageBoxW(null, WString(message.text), WString(message.title), 0x10040) != 0) {
            "Windows startup message could not be displayed (error ${Native.getLastError()})"
        }
    }
}

internal object PlatformAlreadyInUsePresenter : DesktopAlreadyInUsePresenter {
    override fun showAlreadyInUse(appDataRoot: Path) {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            WindowsStartupMessagePresenter().showAlreadyInUse(appDataRoot)
        } else {
            val message = desktopAlreadyInUseMessage(appDataRoot, Locale.getDefault())
            javax.swing.JOptionPane.showMessageDialog(null, message.text, message.title,
                javax.swing.JOptionPane.INFORMATION_MESSAGE)
        }
    }
}
