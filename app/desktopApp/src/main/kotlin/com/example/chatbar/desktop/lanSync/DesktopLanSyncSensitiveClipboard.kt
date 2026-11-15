package com.example.chatbar.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.BaseTSD.SIZE_T
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/** One native clipboard transaction, exclusion marker before text. No AWT publication/fallback. */
internal object DesktopLanSyncSensitiveClipboard {
    internal const val EXCLUSION_FORMAT = "ExcludeClipboardContentFromMonitorProcessing"
    private const val UNICODE_TEXT = 13
    private interface User : StdCallLibrary {
        fun RegisterClipboardFormatW(name: WString): Int
        fun OpenClipboard(owner: Pointer): Boolean
        fun EmptyClipboard(): Boolean
        fun SetClipboardData(format: Int, memory: Pointer): Pointer?
        fun CloseClipboard(): Boolean
        fun GetWindowThreadProcessId(window: Pointer, process: IntByReference): Int
    }
    private interface Kernel : StdCallLibrary {
        fun GlobalAlloc(flags: Int, bytes: SIZE_T): Pointer?
        fun GlobalLock(memory: Pointer): Pointer?
        fun GlobalUnlock(memory: Pointer): Boolean
        fun GlobalFree(memory: Pointer): Pointer?
        fun GetCurrentProcessId(): Int
    }
    private val user by lazy { Native.load("user32", User::class.java) }
    private val kernel by lazy { Native.load("kernel32", Kernel::class.java) }
    fun copy(text: String, owner: Window?): Boolean {
        if (owner == null || !owner.isDisplayable || !System.getProperty("os.name").startsWith("Windows")) return false
        return try { copyOwned(text, Native.getComponentPointer(owner)) }
        catch (_: Exception) { false } catch (_: LinkageError) { false }
    }
    internal fun copyOwned(text: String, owner: Pointer?): Boolean {
        if (!System.getProperty("os.name").startsWith("Windows") || owner == null || Pointer.nativeValue(owner) == 0L ||
            text.isEmpty() || text.length > 64 * 1024 || '\u0000' in text) return false
        return try {
            val pid = IntByReference()
            if (user.GetWindowThreadProcessId(owner, pid) == 0 || pid.value != kernel.GetCurrentProcessId()) return false
            val format = user.RegisterClipboardFormatW(WString(EXCLUSION_FORMAT))
            if (format == 0) return false
            val bytes = (text + '\u0000').toByteArray(Charsets.UTF_16LE)
            try {
                Buffer(byteArrayOf(0, 0, 0, 0)).use { marker -> Buffer(bytes).use { value ->
                    if (!user.OpenClipboard(owner)) return false
                    var success = false
                    try {
                        if (user.EmptyClipboard() && marker.publish(format) && value.publish(UNICODE_TEXT)) success = true
                    } finally { if (!user.CloseClipboard()) success = false }
                    success
                } }
            } finally { bytes.fill(0) }
        } catch (_: Exception) { false } catch (_: LinkageError) { false }
    }
    private class Buffer(bytes: ByteArray) : AutoCloseable {
        private val length = bytes.size.toLong()
        private val handle = requireNotNull(kernel.GlobalAlloc(0x0042, SIZE_T(length))) { "SYNC_CLIPBOARD_ALLOCATION" }
        private var transferred = false
        init {
            try {
                val pointer = requireNotNull(kernel.GlobalLock(handle)) { "SYNC_CLIPBOARD_LOCK" }
                try { pointer.write(0, bytes, 0, bytes.size) } finally { kernel.GlobalUnlock(handle) }
            } catch (failure: Throwable) { kernel.GlobalFree(handle); throw failure }
        }
        fun publish(format: Int): Boolean {
            if (user.SetClipboardData(format, handle) == null) return false
            transferred = true; return true
        }
        override fun close() {
            if (!transferred) {
                val pointer = kernel.GlobalLock(handle)
                if (pointer != null) { try { pointer.clear(length) } finally { kernel.GlobalUnlock(handle) } }
                kernel.GlobalFree(handle)
            }
        }
    }
}
