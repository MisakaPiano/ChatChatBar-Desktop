package com.example.chatbar.desktop

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import java.awt.EventQueue
import kotlin.test.*
import org.junit.Assume.assumeTrue

class WindowsNativeDialogIntegrationTest {
    @Test fun `export picker uses its actual child top level HWND and refuses a disposed export window`() {
        requireDesktopGraphicsForNativeTest()
        assumeTrue(DesktopWindowChrome.isWindows)
        EventQueue.invokeAndWait {
            val main = ComposeWindow().apply { setSize(600, 400); addNotify() }
            val export = ComposeWindow().apply { setSize(500, 350); addNotify() }
            val seen = mutableListOf<Long>()
            val backend = object : CommonDialogBackend {
                override fun initializeSta() = AutoCloseable {}
                override fun create(kind: DesktopDialogKind) = object : CommonDialogHandle {
                    override fun getOptions() = 0
                    override fun setOptions(options: Int) {}
                    override fun setFilter(description: String, pattern: String) {}
                    override fun setDefaultExtension(extension: String) {}
                    override fun setFileName(name: String) {}
                    override fun setTitle(title: String) {}
                    override fun show(owner: Long): Int { seen += owner; return WINDOWS_DIALOG_CANCELLED }
                    override fun result(): CommonDialogItem = error("Cancelled synthetic chooser")
                    override fun close() {}
                }
            }
            val mainHandle = Pointer.nativeValue(com.sun.jna.Native.getWindowPointer(main))
            val exportHandle = Pointer.nativeValue(com.sun.jna.Native.getWindowPointer(export))
            try {
                assertNotEquals(mainHandle, exportHandle)
                WindowsCommonItemDialog({ mainHandle }, backend).use { service ->
                    val ordinary = WindowsNativeFilePicker(service)
                    val child = ordinary.withWindowOwner(export)
                    assertNull(child.pickOpenFile(DesktopFileType("Image", listOf("png"))))
                    assertNull(child.pickSaveFile(DesktopFileType("PNG", listOf("png")), "synthetic.png"))
                    assertNull(ordinary.pickOpenFile(DesktopFileType("Card", listOf("json"))))
                    assertEquals(listOf(exportHandle, exportHandle, mainHandle), seen)
                    export.dispose()
                    assertFailsWith<IllegalStateException> { child.pickOpenFile(DesktopFileType("Image", listOf("png"))) }
                    assertEquals(3, seen.size)
                }
            } finally { export.dispose(); main.dispose() }
        }
    }

    @Test fun `real COM open save and folder configure and release on dedicated STA without showing UI`() {
        assumeTrue(DesktopWindowChrome.isWindows)
        WindowsCommonItemDialog({ error("proof must not request owner or show a dialog") }).use { service ->
            DesktopDialogKind.entries.forEach { kind ->
                service.prepare(DesktopDialogRequest(kind,
                    if (kind == DesktopDialogKind.FOLDER) null else DesktopFileType("角色 JSON or PNG", listOf("json", "png")),
                    "角色.json"))
            }
        }
    }

    @Test fun `owner is chrome top-level Compose HWND and detach never returns stale handle`() {
        requireDesktopGraphicsForNativeTest()
        assumeTrue(DesktopWindowChrome.isWindows)
        EventQueue.invokeAndWait {
            val owner = DesktopDialogOwner()
            val window = ComposeWindow().apply { isUndecorated = true; setSize(600, 400); addNotify() }
            val chrome = DesktopWindowChrome {}
            val native = WindowsWindowChrome.install(window, chrome)
            try {
                assertNull(owner.currentHandle())
                owner.attach(window)
                assertEquals(Pointer.nativeValue(native.hwnd.pointer), owner.requireHandle())
                assertEquals(native.hwnd, User32.INSTANCE.GetAncestor(native.hwnd, 2))
                User32.INSTANCE.EnumChildWindows(native.hwnd, { child, _ ->
                    assertNotEquals(Pointer.nativeValue(child.pointer), owner.requireHandle())
                    true
                }, null)
                owner.detach(window)
                assertNull(owner.currentHandle())
                assertFailsWith<IllegalStateException> { owner.requireHandle() }
                owner.attach(window)
            } finally { native.close(); window.dispose() }
            assertNull(owner.currentHandle(), "disposed window cannot be reused even before detach")
            owner.detach(window)
        }
    }
}
