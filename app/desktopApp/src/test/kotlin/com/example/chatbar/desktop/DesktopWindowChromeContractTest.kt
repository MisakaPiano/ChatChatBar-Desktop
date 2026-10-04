package com.example.chatbar.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopWindowChromeContractTest {
    private fun source(name: String) = Files.readString(Path.of("src/main/kotlin/com/example/chatbar/desktop", name))

    @Test fun `title bar is the single app route presentation and calls the existing leave guard`() {
        val shell = source("DesktopPrimaryShell.kt")
        assertTrue(shell.contains("DesktopTitleBar("))
        assertTrue(shell.contains("onNavigate = ::navigate"))
        assertFalse(shell.contains("DesktopNavigationPlacement"))
        assertFalse(shell.contains("RouteControl("))
        assertTrue(shell.contains("primaryChatController.requestSessionSettingsLeave"))
        assertTrue(shell.contains("worldBookEditorController.requestLeave"))
        assertTrue(shell.contains("formatCardEditorController.requestLeave"))
        assertTrue(shell.contains("characterEditorController.requestLeave"))
        assertTrue(shell.contains("modelSettingsController.requestLeave"))
        assertTrue(shell.contains("modelTemplateController.state.value.busy"))
    }

    @Test fun `window integrates native chrome while preserving the close and drain authorities`() {
        val main = source("Main.kt")
        assertTrue(main.contains("rememberDesktopWindowChrome("))
        assertTrue(main.contains("rootSwitchController.requestWindowClose()"))
        assertTrue(main.contains("close = { appContainer.close() }"))
        assertTrue(main.contains("exitProcessOnExit = false"))
        assertFalse(main.contains("System.exit("))
        assertFalse(main.contains("exitProcess("))
    }
}
