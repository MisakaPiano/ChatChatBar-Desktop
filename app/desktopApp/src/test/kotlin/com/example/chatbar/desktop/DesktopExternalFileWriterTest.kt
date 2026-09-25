package com.example.chatbar.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopExternalFileWriterTest {
    @Test
    fun `writer creates and safely replaces destination without residue`() {
        val root = Files.createTempDirectory("desktop-export-writer-")
        try {
            val destination = root.resolve("card.json")
            DesktopExternalFileWriter.writeText(destination, "first")
            assertEquals("first", Files.readString(destination))
            DesktopExternalFileWriter.writeBytes(destination, "second".toByteArray())
            assertContentEquals("second".toByteArray(), Files.readAllBytes(destination))
            Files.list(root).use { children ->
                assertTrue(children.noneMatch { it.fileName.toString().startsWith(".ccb-export-") })
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failure before commit preserves existing destination and cleans temp`() {
        val root = Files.createTempDirectory("desktop-export-failure-")
        try {
            val destination = Files.writeString(root.resolve("card.json"), "old")
            assertFailsWith<IllegalStateException> {
                DesktopExternalFileWriter.write(destination) {
                    write("partial".toByteArray())
                    error("injected")
                }
            }
            assertEquals("old", Files.readString(destination))
            Files.list(root).use { children ->
                assertTrue(children.noneMatch { it.fileName.toString().startsWith(".ccb-export-") })
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
