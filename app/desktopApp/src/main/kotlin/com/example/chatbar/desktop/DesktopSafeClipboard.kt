package com.example.chatbar.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.NativeClipboard
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class DesktopClipboardResult { COPIED, UNAVAILABLE }

/** Scoped to selectable chat output, not editable fields or the application-wide clipboard.
 * Compose 1.10.3 SelectionContainer menu Copy and Ctrl+C both call LocalClipboard.setClipEntry.
 */
internal class DesktopSafeClipboard(
    private val delegate: Clipboard,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : Clipboard {
    private val writes = Mutex()
    var unavailable by mutableStateOf(false)
        private set

    @OptIn(ExperimentalComposeUiApi::class)
    suspend fun copyText(text: String): DesktopClipboardResult = write(ClipEntry(StringSelection(text)))

    override suspend fun setClipEntry(clipEntry: ClipEntry?) { write(clipEntry) }

    private suspend fun write(entry: ClipEntry?): DesktopClipboardResult = writes.withLock {
        val success = attempt { delegate.setClipEntry(entry); true } ?: false
        unavailable = !success
        if (success) DesktopClipboardResult.COPIED else DesktopClipboardResult.UNAVAILABLE
    }

    override suspend fun getClipEntry(): ClipEntry? = attempt { delegate.getClipEntry() }

    // SelectionContainer does not query nativeClipboard. Do not broaden this wrapper to text fields,
    // whose native paste-availability checks can bypass Clipboard's suspend API.
    override val nativeClipboard: NativeClipboard get() = delegate.nativeClipboard

    private suspend fun <T> attempt(operation: suspend () -> T): T? {
        for (attempt in 0..RETRY_DELAYS.size) {
            try {
                return operation()
            } catch (error: IllegalStateException) {
                // The specific Windows AWT platform contract; unrelated programming errors propagate.
                if (!error.message.equals("cannot open system clipboard", ignoreCase = true)) throw error
                if (attempt == RETRY_DELAYS.size) {
                    unavailable = true
                    return null
                }
                pause(RETRY_DELAYS[attempt])
            }
        }
        error("Unreachable clipboard attempt")
    }

    private companion object {
        val RETRY_DELAYS = longArrayOf(20, 40, 80)
    }
}
