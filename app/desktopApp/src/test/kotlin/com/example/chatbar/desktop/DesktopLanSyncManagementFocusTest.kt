package com.example.chatbar.desktop

import kotlin.test.*

class DesktopLanSyncManagementFocusTest {
    private class NativeWindow(val owner: NativeWindow? = null)
    @Test fun rootToOwnedDialogThenExternalAppLocksOnlyWhenTheDialogLeavesTheApp() {
        val root = NativeWindow(); val dialog = NativeWindow(root); val otherApp = NativeWindow()
        var locks = 0
        fun transition(from: NativeWindow, to: NativeWindow?) {
            if (desktopLanSyncManagementLeavesApp(root, from, to) { it.owner }) locks++
        }
        transition(root, dialog); assertEquals(0, locks)
        transition(dialog, otherApp); assertEquals(1, locks)
        transition(otherApp, root); assertEquals(1, locks)
    }
    @Test fun nestedOwnedDialogsCanReturnOrSwitchInternallyAndUnknownExternalFocusLocks() {
        val root = NativeWindow(); val dialog = NativeWindow(root); val nested = NativeWindow(dialog); val sibling = NativeWindow(root)
        fun leaves(from: NativeWindow, to: NativeWindow?) = desktopLanSyncManagementLeavesApp(root, from, to) { it.owner }
        assertFalse(leaves(dialog, nested)); assertFalse(leaves(nested, sibling)); assertFalse(leaves(sibling, root))
        assertTrue(leaves(nested, null)); assertTrue(leaves(root, null))
        assertFalse(leaves(NativeWindow(), null))
    }
}
