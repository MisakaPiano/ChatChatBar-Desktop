package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.DesktopBackupRecords
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class DesktopSessionInventoryTest {
    @Test fun `streamed ownership census exceeds former aggregate and file caps without losing owners`() = runBlocking {
        val root = Files.createTempDirectory("session-inventory-large-")
        try {
            val home = Files.createDirectories(root.resolve("entities/vector_chunks"))
            val text = "x".repeat(1024 * 1024)
            repeat(120) { index ->
                Files.writeString(home.resolve("v$index.json"), """{"id":"v$index","sourceId":"s","sourceType":"CHAT_MEMORY","content":"$text"}""")
            }
            val bigText = text.repeat(9)
            Files.writeString(home.resolve("large.json"), """{"id":"large","sourceId":"s","sourceType":"CHAT_MEMORY","content":"$bigText"}""")
            Files.writeString(home.resolve("foreign.json"), """{"id":"foreign","sourceId":"other","sourceType":"CHAT_MEMORY","content":"Original foreign text"}""")
            val inventory = DesktopSessionInventory(root).read("s")
            assertEquals(121, inventory.size)
            assertTrue("entities/vector_chunks/large.json" in inventory)
            assertTrue(inventory.values.all { it.matches(Regex("[a-f0-9]{64}")) })
            assertFalse("entities/vector_chunks/foreign.json" in inventory)
            assertEquals(emptyMap(), DesktopSessionInventory(root).read("validation-only"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `working budget malformed identities and cancellation still refuse ownership authority`() = runBlocking {
        val root = Files.createTempDirectory("session-inventory-guards-")
        try {
            val home = Files.createDirectories(root.resolve("entities/vector_chunks"))
            val file = home.resolve("v.json")
            RandomAccessFile(file.toFile(), "rw").use { it.setLength(DesktopBackupRecords.recordBudget + 1) }
            assertFailsWith<DesktopBackupRecords.ResourceLimit> { DesktopSessionInventory(root).read("s") }
            Files.writeString(file, """{"id":"different","sourceId":"s","sourceType":"CHAT_MEMORY"}""")
            assertFails { DesktopSessionInventory(root).read("s") }
            Files.writeString(file, "{broken")
            assertFails { DesktopSessionInventory(root).read("s") }
            Files.writeString(file, """{"id":"v","sourceId":"s","sourceType":"CHAT_MEMORY"}""")
            assertFailsWith<CancellationException> { coroutineScope { cancel(); DesktopSessionInventory(root).read("s") } }
            assertTrue(Files.isRegularFile(file))
        } finally { root.toFile().deleteRecursively() }
    }
}
