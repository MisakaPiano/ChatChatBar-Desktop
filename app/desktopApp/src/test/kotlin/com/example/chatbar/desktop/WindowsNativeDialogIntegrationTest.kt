package com.example.chatbar.desktop

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import java.awt.EventQueue
import kotlin.test.*
import org.junit.Assume.assumeTrue

class WindowsNativeDialogIntegrationTest {
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
