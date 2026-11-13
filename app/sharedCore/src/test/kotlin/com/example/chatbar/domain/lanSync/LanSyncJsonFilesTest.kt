package com.example.chatbar.domain.lanSync

import java.io.IOException
import java.nio.file.Files
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.builtins.MapSerializer
import kotlin.test.*

class LanSyncJsonFilesTest {
    @Test fun streamedLocalMetadataAboveTheOldNetworkFrameLimitReopensWithoutWholeDocumentByteArrays() {
        val root = Files.createTempDirectory("lan-streamed-state-").toRealPath()
        try {
            val file = root.resolve("large.json")
            val fragment = "synthetic-metadata".repeat(64)
            val value = (0 until 70_000).associate { it.toString() to fragment }
            val serializer = MapSerializer(String.serializer(), String.serializer())
            LanSyncJsonFiles.write(file, serializer, value)
            assertTrue(Files.size(file) > 64L * 1024 * 1024)
            val reopened = LanSyncJsonFiles.read(file, serializer)
            assertTrue(value == reopened)
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun interruptedMetadataEncodingRetainsThePreviousDocumentAndMalformedUtf8Refuses() {
        val root = Files.createTempDirectory("lan-streamed-state-fault-").toRealPath()
        try {
            val file = root.resolve("state.json")
            LanSyncJsonFiles.write(file, String.serializer(), "原状态")
            val original = Files.readAllBytes(file)
            assertFailsWith<IOException> { LanSyncJsonFiles.write(file) { output ->
                output.write("incomplete".toByteArray()); throw IOException("synthetic serializer failure")
            } }
            assertContentEquals(original, Files.readAllBytes(file))
            assertEquals(1L, Files.list(root).use { it.count() })
            Files.write(file, byteArrayOf(34, 0xc3.toByte(), 0x28, 34))
            assertEquals("SYNC_INVALID_UTF8", assertFailsWith<IllegalArgumentException> { LanSyncJsonFiles.read(file, String.serializer()) }.message)
            Files.write(file, "\"one\" \"unexpected-second-value\"".toByteArray())
            assertFails { LanSyncJsonFiles.read(file, String.serializer()) }
        } finally { check(root.toFile().deleteRecursively()) }
    }
}
