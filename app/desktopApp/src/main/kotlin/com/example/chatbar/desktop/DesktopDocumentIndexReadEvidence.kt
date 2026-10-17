package com.example.chatbar.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A data read and its file ID/link count are obtained from the SAME native handle.
 * No storage, ownership acquisition, asynchronous native I/O, or write access is used.
 * Synchronous Windows I/O has no claimed physical deadline. Cancellation waits for it.
 */
internal class DesktopDocumentIndexReadEvidence(
    private val operation: () -> Unit,
    private val step: suspend (Step) -> Unit = {},
) {
    internal enum class Step { OPENED, BEFORE_READ, AFTER_READ, CLOSED }
    internal data class Stamp(
        val volume: Long, val id: String, val created: Long, val modified: Long,
        val changed: Long, val size: Long, val links: Long, val directory: Boolean,
    )
    internal data class Read(val file: JFileEvidence, val stamp: Stamp)
    private interface Paths : StdCallLibrary {
        fun GetFinalPathNameByHandle(handle: WinNT.HANDLE, buffer: CharArray, length: Int, flags: Int): Int
        fun GetLongPathName(path: String, buffer: CharArray, length: Int): Int
    }
    private val paths by lazy { Native.load("kernel32", Paths::class.java, W32APIOptions.UNICODE_OPTIONS) }

    suspend fun observe(path: Path, directory: Boolean? = null): Stamp = handle(path, false) { h ->
        stamp(h).also { if (directory != null) jRequire(it.directory == directory, DesktopIndexInspectionCode.UNSAFE_PATH) }
    }

    suspend fun read(path: Path, expected: Stamp, consume: suspend (ByteArray, Int) -> Unit): Read = handle(path, true) { h ->
        val before = stamp(h)
        jRequire(before == expected && !before.directory, DesktopIndexInspectionCode.CHANGED_DURING_CHECK)
        step(Step.BEFORE_READ)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65_536)
        var count = 0L
        while (count < before.size) {
            currentCoroutineContext().ensureActive()
            val requested = minOf(buffer.size.toLong(), before.size - count).toInt()
            val actual = IntByReference()
            operation()
            if (!Kernel32.INSTANCE.ReadFile(h, buffer, requested, actual, null)) jRefuse(DesktopIndexInspectionCode.READ_ERROR)
            jRequire(actual.value in 1..requested, DesktopIndexInspectionCode.CHANGED_DURING_CHECK)
            count = jAdd(count, actual.value.toLong(), before.size)
            digest.update(buffer, 0, actual.value); consume(buffer, actual.value)
        }
        val sentinel = IntByReference()
        operation()
        if (!Kernel32.INSTANCE.ReadFile(h, buffer, 1, sentinel, null)) jRefuse(DesktopIndexInspectionCode.READ_ERROR)
        jRequire(sentinel.value == 0, DesktopIndexInspectionCode.CHANGED_DURING_CHECK)
        step(Step.AFTER_READ)
        jRequire(stamp(h) == before, DesktopIndexInspectionCode.CHANGED_DURING_CHECK)
        Read(JFileEvidence(count, jHex(digest.digest())), before)
    }

    private suspend fun <T> handle(path: Path, data: Boolean, action: suspend (WinNT.HANDLE) -> T): T {
        currentCoroutineContext().ensureActive()
        jRequire(System.getProperty("os.name").startsWith("Windows"), DesktopIndexInspectionCode.UNSAFE_PATH)
        operation()
        val h = Kernel32.INSTANCE.CreateFile(path.toString(), WinNT.FILE_READ_ATTRIBUTES or (if (data) WinNT.GENERIC_READ else 0),
            WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE or WinNT.FILE_SHARE_DELETE, null,
            WinNT.OPEN_EXISTING, WinNT.FILE_FLAG_BACKUP_SEMANTICS or WinNT.FILE_FLAG_OPEN_REPARSE_POINT, null)
        if (h == WinBase.INVALID_HANDLE_VALUE) jRefuse(DesktopIndexInspectionCode.READ_ERROR)
        var primary: Throwable? = null
        try {
            verifyPath(h, path)
            step(Step.OPENED)
            return action(h)
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            // Closing is never skipped because a budget was exhausted or the coroutine cancelled.
            val closed = Kernel32.INSTANCE.CloseHandle(h)
            if (!closed) {
                val failure = JReadRefusal(DesktopIndexInspectionCode.READ_ERROR)
                if (primary is kotlinx.coroutines.CancellationException) {
                    val cancelled=kotlinx.coroutines.CancellationException("Read-only handle close unconfirmed")
                    cancelled.initCause(primary);cancelled.addSuppressed(failure);throw cancelled
                }
                if (primary != null) failure.initCause(primary)
                throw failure
            }
            step(Step.CLOSED)
        }
    }

    private fun stamp(h: WinNT.HANDLE): Stamp {
        return Memory(40).use { basic -> Memory(24).use { standard -> Memory(24).use { identity ->
            val api = Kernel32.INSTANCE
            operation();if (!api.GetFileInformationByHandleEx(h, 0, basic, DWORD(40))) jRefuse(DesktopIndexInspectionCode.READ_ERROR)
            operation();if (!api.GetFileInformationByHandleEx(h, 1, standard, DWORD(24))) jRefuse(DesktopIndexInspectionCode.READ_ERROR)
            operation();if (!api.GetFileInformationByHandleEx(h, 18, identity, DWORD(24))) jRefuse(DesktopIndexInspectionCode.READ_ERROR)
            val attributes = basic.getInt(32)
            val directory = standard.getByte(21).toInt() != 0
            val links = Integer.toUnsignedLong(standard.getInt(16))
            jRequire(attributes and 0x400 == 0 && (attributes and 0x10 != 0) == directory &&
                standard.getByte(20).toInt() == 0, DesktopIndexInspectionCode.UNSAFE_PATH)
            jRequire(directory || links == 1L, DesktopIndexInspectionCode.UNSAFE_PATH)
            val size = standard.getLong(8)
            jRequire(size >= 0, DesktopIndexInspectionCode.READ_ERROR)
            Stamp(identity.getLong(0), jHex(identity.getByteArray(8, 16)),
                basic.getLong(0), basic.getLong(16), basic.getLong(24), size, links, directory)
        } } }
    }

    private fun verifyPath(h: WinNT.HANDLE, path: Path) {
        val expected = path.toAbsolutePath().normalize().toString()
        jRequire(expected.length <= 1024 && !expected.startsWith("\\\\"), DesktopIndexInspectionCode.UNSAFE_PATH)
        val final = CharArray(1025)
        operation()
        val length = paths.GetFinalPathNameByHandle(h, final, final.size, 0)
        jRequire(length in 1..1024, DesktopIndexInspectionCode.READ_ERROR)
        val resolved = String(final, 0, length).removePrefix("\\\\?\\")
        jRequire(resolved.equals(expected, true), DesktopIndexInspectionCode.UNSAFE_PATH)
        val long = CharArray(1025)
        operation()
        val longLength = paths.GetLongPathName(expected, long, long.size)
        jRequire(longLength in 1..1024, DesktopIndexInspectionCode.READ_ERROR)
        jRequire(String(long, 0, longLength).equals(expected, true), DesktopIndexInspectionCode.UNSAFE_PATH)
    }
}
