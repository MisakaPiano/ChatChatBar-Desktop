package com.example.chatbar.desktop

import com.sun.jna.*
import com.sun.jna.Function as NativeFunction
import com.sun.jna.ptr.*
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.*
import java.awt.EventQueue
import java.nio.file.*

internal enum class DesktopPinResult { PINNED, DECLINED, UNAVAILABLE, NOT_FOREGROUND, FAILED, CHECKED_UNPINNED }

/** Official WinRT desktop capability/permission checks. No undocumented shell verbs or policy edits. */
internal object DesktopWindowsTaskbarPin {
    @Volatile var diagnostic: String? = null
        private set
    private class NativeFailure(val status: Int) : Exception()
    private fun checked(status: Int) { if (status < 0) throw NativeFailure(status) }
    const val APP_ID = "MisakaPiano.ChatChatBar.Desktop"
    private interface Runtime : StdCallLibrary {
        fun RoInitialize(kind: Int): Int
        fun RoUninitialize()
        fun WindowsCreateString(value: WString, length: Int, result: PointerByReference): Int
        fun WindowsDeleteString(value: Pointer): Int
        fun RoGetActivationFactory(name: Pointer, iid: Guid.GUID, result: PointerByReference): Int
    }
    private interface Shell : StdCallLibrary { fun SetCurrentProcessExplicitAppUserModelID(id: WString): Int }
    fun configureIdentity() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val home = (DesktopApplicationHome.resolve() as? DesktopApplicationHomeResult.Available)?.path ?: return
        if (home.parent?.fileName?.toString() != "versions") return
        try {
            if (Files.readString(home.parent.parent.resolve("install-owner.txt")) != "ccb-desktop-nsis-v1") return
            check(Native.load("shell32", Shell::class.java).SetCurrentProcessExplicitAppUserModelID(WString(APP_ID)) >= 0)
        } catch (_: Exception) { System.err.println("CCB TASKBAR_IDENTITY_UNAVAILABLE") }
    }
    private class Com(val pointer: Pointer) : AutoCloseable {
        fun call(slot: Int, vararg args: Any?): Int {
            val address = pointer.getPointer(0).getPointer(slot.toLong() * Native.POINTER_SIZE)
            return NativeFunction.getFunction(address, NativeFunction.ALT_CONVENTION).invokeInt(arrayOf(pointer, *args)).also(::checked)
        }
        fun query(id: String): Com {
            val iid = Guid.GUID(id).apply { write() }; val result = PointerByReference()
            check(call(0, iid.pointer, result) >= 0)
            return Com(checkNotNull(result.value))
        }
        override fun close() { call(2) }
    }
    private suspend fun awaitBoolean(operation: Com): Boolean = operation.use { async ->
        async.query("00000036-0000-0000-C000-000000000046").use { info ->
            try {
                withTimeout(120_000) {
                    while (true) {
                        val status = IntByReference(); check(info.call(7, status) >= 0)
                        when (status.value) {
                            0 -> delay(100)
                            1 -> { val result = ByteByReference(); check(async.call(8, result) >= 0); return@withTimeout result.value.toInt() != 0 }
                            2 -> return@withTimeout false
                            else -> {
                                val error = IntByReference(); info.call(8, error)
                                if (error.value < 0) throw NativeFailure(error.value)
                                error("TASKBAR_REQUEST_FAILED")
                            }
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") false
                }
            } catch (cancelled: CancellationException) { info.call(9); throw cancelled }
            finally { info.call(10) }
        }
    }
    suspend fun request(pin: Boolean = true): DesktopPinResult {
        diagnostic = null
        check(EventQueue.isDispatchThread()) { "Taskbar confirmation must originate on the UI thread" }
        if (pin) {
        val foreground = User32.INSTANCE.GetForegroundWindow() ?: return DesktopPinResult.NOT_FOREGROUND
        val pid = IntByReference(); User32.INSTANCE.GetWindowThreadProcessId(foreground, pid)
        if (pid.value != Kernel32.INSTANCE.GetCurrentProcessId()) return DesktopPinResult.NOT_FOREGROUND
        }
        val api = Native.load("combase", Runtime::class.java)
        val initialized = api.RoInitialize(0)
        if (initialized < 0 && initialized != 0x80010106.toInt()) return DesktopPinResult.UNAVAILABLE
        val name = PointerByReference()
        var stage = "ACTIVATION"
        try {
            val className = "Windows.UI.Shell.TaskbarManager"
            check(api.WindowsCreateString(WString(className), className.length, name) >= 0)
            val marker = PointerByReference()
            val markerId = Guid.GUID("CDFEFD63-E879-4134-B9A7-8283F05F9480")
            if (api.RoGetActivationFactory(name.value, markerId, marker) < 0) return DesktopPinResult.UNAVAILABLE
            Com(checkNotNull(marker.value)).close()
            val factory = PointerByReference()
            check(api.RoGetActivationFactory(name.value, Guid.GUID("DB32AB74-DE52-4FE6-B7B6-95FF9F8395DF"), factory) >= 0)
            Com(checkNotNull(factory.value)).use { statics ->
                stage = "GET_DEFAULT"
                val instance = PointerByReference(); check(statics.call(6, instance) >= 0)
                Com(checkNotNull(instance.value)).use { manager ->
                    stage = "CAPABILITY"
                    val supported = ByteByReference(); val allowed = ByteByReference()
                    check(manager.call(6, supported) >= 0 && manager.call(7, allowed) >= 0)
                    if (supported.value.toInt() == 0 || allowed.value.toInt() == 0) return DesktopPinResult.UNAVAILABLE
                    suspend fun booleanRequest(slot: Int): Boolean {
                        val operation = PointerByReference(); check(manager.call(slot, operation) >= 0)
                        return awaitBoolean(Com(checkNotNull(operation.value)))
                    }
                    stage = "IS_PINNED"
                    if (booleanRequest(8)) return DesktopPinResult.PINNED
                    if (!pin) return DesktopPinResult.CHECKED_UNPINNED
                    stage = "REQUEST_PIN"
                    return if (booleanRequest(10)) DesktopPinResult.PINNED else DesktopPinResult.DECLINED
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            diagnostic = "$stage / " + if (failure is NativeFailure) "HRESULT 0x%08X".format(failure.status) else failure.javaClass.simpleName
            if (failure is NativeFailure && failure.status == 0x80070005.toInt()) return DesktopPinResult.UNAVAILABLE
            return DesktopPinResult.FAILED
        }
        finally {
            name.value?.let { api.WindowsDeleteString(it) }
            if (initialized >= 0) api.RoUninitialize()
        }
    }
}
