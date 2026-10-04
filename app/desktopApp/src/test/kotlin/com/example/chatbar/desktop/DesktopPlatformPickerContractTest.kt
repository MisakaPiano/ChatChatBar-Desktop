package com.example.chatbar.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DesktopPlatformPickerContractTest {
    private fun source(name: String) = Files.readString(Path.of("src/main/kotlin/com/example/chatbar/desktop", name))

    @Test fun `production uses one injected picker authority and attaches its window owner`() {
        val main = source("Main.kt")
        assertFalse(main.contains("SwingDesktop"), "Windows composition must not instantiate Swing pickers")
        assertTrue(main.contains("DesktopPlatformPickers.create("))
        assertTrue(main.contains("filePicker = pickers.files"))
        assertTrue(main.contains("directoryPicker = pickers.directories"))
        assertTrue(main.contains("dialogOwner.attach(window)"))
        assertTrue(main.contains("dialogOwner.detach(window)"))
        val container = source("DesktopAppContainer.kt")
        assertFalse(container.contains("SwingDesktopFilePicker("))
        assertTrue(container.contains("filePicker = filePicker"))
        assertFalse(source("DesktopCharacterEditorController.kt").contains("SwingDesktopFilePicker("))
    }
}
