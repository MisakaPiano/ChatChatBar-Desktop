package com.example.chatbar.desktop

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/** Only the inherited IFileDialog / IShellItem slots used here are bound. Windows-only construction. */
internal class JnaCommonDialogBackend : CommonDialogBackend {
    override fun initializeSta(): AutoCloseable {
        checkDialogResult("CoInitializeEx", Ole32.INSTANCE.CoInitializeEx(null, 2).toInt())
        // Both S_OK and S_FALSE require a matching CoUninitialize on this STA.
        return AutoCloseable { Ole32.INSTANCE.CoUninitialize() }
    }

    override fun create(kind: DesktopDialogKind): CommonDialogHandle {
        val result = PointerByReference()
        val clsid = if (kind == DesktopDialogKind.SAVE) "{C0B4E2F3-BA21-4773-8DBA-335EC946EB8B}"
            else "{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}"
        val hr = Ole32.INSTANCE.CoCreateInstance(Guid.GUID(clsid), null, 1,
            Guid.GUID("{42F85136-DB7E-439C-85F1-E4075D135FC8}"), result).toInt()
        if (hr < 0) {
            result.value?.let { invoke(it, 2) }
            checkDialogResult("CoCreateInstance", hr)
        }
        return Dialog(requireNotNull(result.value))
    }

    private class Dialog(private val pointer: Pointer) : CommonDialogHandle {
        private val filterMemory = mutableListOf<Memory>()
        override fun getOptions(): Int = IntByReference().also {
            checkDialogResult("GetOptions", invoke(pointer, 10, it))
        }.value
        override fun setOptions(options: Int) = checked("SetOptions", 9, options)
        override fun setFilter(description: String, pattern: String) {
            fun wide(value: String): Memory = Memory((value.length + 1L) * Native.WCHAR_SIZE).also {
                filterMemory += it
                it.setWideString(0, value)
            }
            val label = wide(description)
            val spec = wide(pattern)
            val filter = Memory(2L * Native.POINTER_SIZE).also { filterMemory += it }
            filter.setPointer(0, label)
            filter.setPointer(Native.POINTER_SIZE.toLong(), spec)
            checked("SetFileTypes", 4, 1, filter)
        }
        override fun setDefaultExtension(extension: String) = checked("SetDefaultExtension", 22, WString(extension))
        override fun setFileName(name: String) = checked("SetFileName", 15, WString(name))
        override fun setTitle(title: String) = checked("SetTitle", 17, WString(title))
        override fun show(owner: Long): Int = invoke(pointer, 3, Pointer(owner))
        override fun result(): CommonDialogItem {
            val result = PointerByReference()
            val hr = invoke(pointer, 20, result)
            if (hr < 0) {
                result.value?.let { invoke(it, 2) }
                checkDialogResult("GetResult", hr)
            }
            return Item(requireNotNull(result.value))
        }
        private fun checked(name: String, slot: Int, vararg args: Any?) = checkDialogResult(name, invoke(pointer, slot, *args))
        override fun close() {
            try { invoke(pointer, 2) } finally { filterMemory.asReversed().forEach(Memory::close) }
        }
    }

    private class Item(private val pointer: Pointer) : CommonDialogItem {
        override fun displayName(): CommonDialogPath {
            val result = PointerByReference()
            val hr = invoke(pointer, 5, 0x80058000.toInt(), result) // SIGDN_FILESYSPATH, never a Shell display label
            if (hr < 0) {
                result.value?.let(Ole32.INSTANCE::CoTaskMemFree)
                checkDialogResult("GetDisplayName", hr)
            }
            val text = requireNotNull(result.value)
            return object : CommonDialogPath {
                override fun read(): String = text.getWideString(0)
                override fun close() = Ole32.INSTANCE.CoTaskMemFree(text)
            }
        }
        override fun close() { invoke(pointer, 2) }
    }

    private companion object {
        fun invoke(pointer: Pointer, slot: Int, vararg args: Any?): Int = Function.getFunction(
            pointer.getPointer(0).getPointer(slot.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION,
        ).invokeInt(arrayOf(pointer, *args))
    }
}
