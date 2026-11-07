package com.example.chatbar.desktop

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopSaveSlotOwnedWorkTest {
    @Test fun `success receipt refuses foreign bytes children and replacement directories without sweeping siblings`() = runBlocking {
        val parent = Files.createTempDirectory("slot-work-custody-").toAbsolutePath().normalize()
        try {
            val sibling = Files.writeString(parent.resolve("unrelated.txt"), "keep sibling")
            val owned = DesktopSaveSlotOwnedWork.create(parent)
            val original = Files.writeString(owned.directory.resolve("source.archive"), "original stage")
            val proof = owned.seal()
            val foreign = Files.writeString(owned.directory.resolve("foreign.txt"), "keep foreign")
            assertFails { proof.release() }
            assertEquals("original stage", Files.readString(original)); assertEquals("keep foreign", Files.readString(foreign))
            Files.delete(foreign)
            Files.writeString(original, "changed stage")
            assertFails { proof.release() }
            assertEquals("changed stage", Files.readString(original))
            Files.writeString(original, "original stage")
            val retained = parent.resolve("retained-owned")
            Files.move(owned.directory, retained)
            Files.createDirectory(owned.directory)
            val replacement = Files.writeString(owned.directory.resolve("new.txt"), "replacement")
            assertFails { proof.release() }
            assertEquals("replacement", Files.readString(replacement))
            assertEquals("original stage", Files.readString(retained.resolve("source.archive")))
            assertEquals("keep sibling", Files.readString(sibling))
        } finally { check(parent.toFile().deleteRecursively()) }
    }
}
