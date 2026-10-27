package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class FullBackupHiddenMediaSafetyTest {
    @Test fun `unclassified hidden media blocks whole export and retains original`() = runBlocking<Unit> {
        val base = Files.createTempDirectory("preview-hidden-media-")
        val root = Files.createDirectory(base.resolve("root"))
        val work = Files.createDirectory(base.resolve("work"))
        val image = root.resolve("images/.unclassified/copy.bin")
        Files.createDirectories(image.parent)
        val original = byteArrayOf(1, 3, 5, 7)
        Files.write(image, original)
        val failure = assertFailsWith<IllegalStateException> { DesktopFullBackupMapping(root).export(work) }
        assertEquals("UNCLASSIFIED_HIDDEN_BACKUP_ENTRY", failure.message)
        assertContentEquals(original, Files.readAllBytes(image))
        assertEquals(0, Files.list(work).use { it.count() }.toInt())
    }

    @Test fun `recognized unfinished single file suffix stays excluded`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("preview-pending-file-")
        Files.createDirectories(root.resolve("images"))
        Files.write(root.resolve("images/.publication.part"), byteArrayOf(2))
        Files.write(root.resolve("images/live.png"), byteArrayOf(4))
        assertEquals(setOf("images/live.png"), DesktopFullBackupMapping(root).inventory().keys)
        assertTrue(Files.exists(root.resolve("images/.publication.part")))
    }

    @Test fun `hidden ancestor is not excused by a temporary suffix`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("preview-hidden-parent-")
        val path = root.resolve("images/.unclassified/original.tmp")
        Files.createDirectories(path.parent); Files.write(path, byteArrayOf(9))
        assertFailsWith<IllegalStateException> { DesktopFullBackupMapping(root).inventory() }
        assertContentEquals(byteArrayOf(9), Files.readAllBytes(path))
    }
}
