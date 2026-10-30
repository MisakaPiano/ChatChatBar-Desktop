package com.example.chatbar.desktop

import com.sun.jna.*
import com.sun.jna.platform.win32.*
import com.sun.jna.platform.win32.WinDef.*
import com.sun.jna.platform.win32.WinUser.*
import com.sun.jna.win32.StdCallLibrary
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A thread-local hook brands the existing ownership MessageBox; it never opens a data root. */
internal object DesktopNativeDialogBrand {
    private interface CbtHook : HOOKPROC {
        fun callback(code: Int, wParam: WPARAM, lParam: LPARAM): LRESULT
    }
    private interface Icons : StdCallLibrary {
        fun CreateIconFromResourceEx(bytes: Pointer, size: Int, icon: Boolean, version: Int, width: Int, height: Int, flags: Int): HICON?
    }
    fun show(messageBox: () -> Int, onBrandFailure: () -> Unit = { System.err.println("CCB NATIVE_DIALOG_BRAND_UNAVAILABLE") }): Int {
        val user = User32.INSTANCE
        var icon: HICON? = null
        var hook: HHOOK? = null
        var callback: HOOKPROC? = null
        try {
            val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream("brand/ccb.ico")).use { it.readBytes() }
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(data.getShort(0).toInt() == 0 && data.getShort(2).toInt() == 1 && data.getShort(4).toInt() > 0)
            val length = data.getInt(14); val offset = data.getInt(18)
            require(offset >= 22 && length > 0 && offset.toLong() + length <= bytes.size)
            val memory = Memory(length.toLong()).apply { write(0, bytes, offset, length) }
            icon = checkNotNull(Native.load("user32", Icons::class.java)
                .CreateIconFromResourceEx(memory, length, true, 0x00030000, 32, 32, 0))
            val currentIcon = icon
            callback = object : CbtHook {
                override fun callback(code: Int, wParam: WPARAM, lParam: LPARAM): LRESULT {
                    if (code == 5) { // HCBT_ACTIVATE on this calling thread only.
                        val window = HWND(Pointer(wParam.toLong()))
                        user.SendMessage(window, 0x0080, WPARAM(0), LPARAM(Pointer.nativeValue(currentIcon.pointer)))
                        user.SendMessage(window, 0x0080, WPARAM(1), LPARAM(Pointer.nativeValue(currentIcon.pointer)))
                    }
                    return user.CallNextHookEx(hook, code, wParam, lParam)
                }
            }
            hook = checkNotNull(user.SetWindowsHookEx(5, callback, null, Kernel32.INSTANCE.GetCurrentThreadId()))
        } catch (_: Exception) { onBrandFailure() }
        try { return messageBox() }
        finally {
            hook?.let { user.UnhookWindowsHookEx(it) }
            icon?.let { user.DestroyIcon(it) }
            java.lang.ref.Reference.reachabilityFence(callback)
        }
    }
}
