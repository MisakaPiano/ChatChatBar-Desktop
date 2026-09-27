package com.example.chatbar.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopShellLayoutPolicyTest {
    @Test
    fun `capacity boundaries are deterministic`() {
        assertEquals(DesktopShellSize.COMPACT, DesktopShellLayoutPolicy.sizeForWidth(719f))
        assertEquals(DesktopShellSize.MEDIUM, DesktopShellLayoutPolicy.sizeForWidth(720f))
        assertEquals(DesktopShellSize.MEDIUM, DesktopShellLayoutPolicy.sizeForWidth(1099f))
        assertEquals(DesktopShellSize.WIDE, DesktopShellLayoutPolicy.sizeForWidth(1100f))
    }
}
