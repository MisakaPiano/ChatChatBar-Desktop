package com.example.chatbar.desktop

import java.nio.file.*
import java.security.MessageDigest
import kotlin.test.*

class DesktopInstalledShortcutsTest {
    @Test fun `shortcut helper admits current receipted manager and refuses stale or tampered program`() {
        val root = Files.createTempDirectory("ccb-shortcut-admission-").toRealPath()
        fun hash(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
        try {
            val home = Files.createDirectories(root.resolve("versions/0.1.0-preview.1-aaaaaaaaaaaa"))
            Files.writeString(root.resolve("install-owner.txt"), "ccb-desktop-nsis-v1")
            Files.writeString(root.resolve("current.txt"), home.fileName.toString())
            val manager = Files.writeString(home.resolve("install-manager.exe"), "synthetic manager, not executable")
            val manifest = Files.writeString(home.resolve("install-manifest.json"),
                """{"product":"ccb-desktop-nsis-v1","files":[{"path":"install-manager.exe","bytes":${Files.size(manager)},"sha256":"${hash(manager)}"}]}""")
            Files.createDirectory(root.resolve("receipts"))
            val receipt = Files.writeString(root.resolve("receipts/${home.fileName}.sha256"), hash(manifest))
            assertEquals(manager to root, desktopInstalledShortcutManager(home))
            Files.writeString(root.resolve("current.txt"), "some-other-version")
            assertFails { desktopInstalledShortcutManager(home) }
            Files.writeString(root.resolve("current.txt"), home.fileName.toString())
            Files.writeString(manager, "changed manager")
            assertFails { desktopInstalledShortcutManager(home) }
            Files.writeString(manager, "synthetic manager, not executable")
            Files.writeString(receipt, "0".repeat(64))
            assertFails { desktopInstalledShortcutManager(home) }
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
