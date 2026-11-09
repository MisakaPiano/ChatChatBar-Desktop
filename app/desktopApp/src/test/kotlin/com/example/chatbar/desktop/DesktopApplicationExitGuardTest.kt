package com.example.chatbar.desktop

import kotlin.test.*

class DesktopApplicationExitGuardTest {
    @Test fun `root migration and required restart never consult the optional main window hide action`() {
        val path = java.nio.file.Path.of("synthetic-root")
        val states = listOf(DesktopDataRootSwitchState.Migrating(path, DesktopDataRootProvenance.CLI_OVERRIDE, path.resolve("next")),
            DesktopDataRootSwitchState.RestartRequired(path, DesktopDataRootProvenance.CLI_OVERRIDE, null, null, null))
        for (state in states) {
            var hides = 0; var exits = 0
            desktopMainWindowClose(state, { hides++; true }, { exits++ })
            assertEquals(0, hides); assertEquals(1, exits)
        }
    }

    @Test fun `dirty leave owner retains the real exit continuation until resolved without starting shutdown`() {
        val reached = mutableListOf<String>(); var resolve: (() -> Unit)? = null; var exits = 0
        val guard = DesktopApplicationExitGuard(
            chat = { next -> reached += "chat"; resolve = next },
            worldBooks = { next -> reached += "world"; next() },
            formats = { next -> reached += "format"; next() },
            characters = { next -> reached += "character"; next() },
            models = { next -> reached += "model"; next() },
            finish = { exits++ },
        )
        guard.requestExit()
        assertEquals(listOf("chat"), reached); assertEquals(0, exits)
        assertNotNull(resolve).invoke()
        assertEquals(listOf("chat", "world", "format", "character", "model"), reached); assertEquals(1, exits)
    }

    @Test fun `cancelled editor leave never invokes later owners or the exit action`() {
        var exits = 0; var afterDirty = 0
        val allow: (() -> Unit) -> Unit = { it() }
        val guard = DesktopApplicationExitGuard(allow, allow, { /* Existing dirty owner keeps/cancels its continuation. */ },
            { afterDirty++; it() }, allow, { exits++ })
        guard.requestExit()
        assertEquals(0, afterDirty); assertEquals(0, exits)
    }
}
