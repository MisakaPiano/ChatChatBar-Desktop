package com.example.chatbar.desktop

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopDataDirectoryTest {
    @Test
    fun `uses LOCALAPPDATA when available`() {
        val localAppData = Path.of("local-app-data").toAbsolutePath()

        val result = DesktopDataDirectory.resolve(
            environment = mapOf("LOCALAPPDATA" to localAppData.toString()),
            userHome = Path.of("unused-home").toAbsolutePath(),
        )

        assertEquals(localAppData.resolve("ChatChatBarDesktop"), result)
    }

    @Test
    fun `falls back to user home AppData Local when LOCALAPPDATA is blank`() {
        val userHome = Path.of("user-home").toAbsolutePath()

        val result = DesktopDataDirectory.resolve(
            environment = mapOf("LOCALAPPDATA" to "  "),
            userHome = userHome,
        )

        assertEquals(
            userHome.resolve("AppData").resolve("Local").resolve("ChatChatBarDesktop"),
            result,
        )
    }
}
