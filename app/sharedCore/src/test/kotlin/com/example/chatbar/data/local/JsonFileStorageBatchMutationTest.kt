package com.example.chatbar.data.local

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Encoder
import kotlin.test.*

class JsonFileStorageBatchMutationTest {
    @Test fun `second staged serialization failure retains every original and cache`() = runTest {
        val root = Files.createTempDirectory("batch-mutation-")
        try {
            val storage = JsonFileStorage(root)
            storage.saveEntity("items", "a", "old-a", String.serializer())
            storage.saveEntity("items", "b", "old-b", String.serializer())
            val before = listOf("a", "b").associateWith { Files.readAllBytes(root.resolve("entities/items/$it.json")) }
            val serializer = object : KSerializer<String> by String.serializer() {
                override fun serialize(encoder: Encoder, value: String) {
                    encoder.encodeString(value)
                    if (value == "fail-b") throw IOException("synthetic second staging failure")
                }
            }
            assertFailsWith<IOException> {
                storage.mutateEntitiesAtomically("items", linkedSetOf("a", "b"), serializer) { originals ->
                    assertEquals("old-a", (originals.getValue("a") as JsonFileStorage.EntityReadResult.Valid).value)
                    linkedMapOf("a" to "new-a", "b" to "fail-b")
                }
            }
            before.forEach { (id, bytes) -> assertContentEquals(bytes, Files.readAllBytes(root.resolve("entities/items/$id.json"))) }
            assertEquals(setOf("old-a", "old-b"), storage.observeAll("items", String.serializer()).first().toSet())
            assertEquals(setOf("old-a", "old-b"), JsonFileStorage(root).loadAll("items", String.serializer()).toSet())
            assertEquals(setOf("a.json", "b.json"), Files.list(root.resolve("entities/items")).use { s -> s.map { it.fileName.toString() }.toList().toSet() })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `selected batch preserves untouched entities and strict missing corrupt and read error states`() = runTest {
        val root = Files.createTempDirectory("batch-read-states-")
        try {
            val storage = JsonFileStorage(root)
            storage.saveEntity("items", "kept", "untouched", String.serializer())
            storage.saveEntity("items", "a", "old-a", String.serializer())
            Files.writeString(root.resolve("entities/items/b.json"), "{bad")
            Files.createDirectory(root.resolve("entities/items/directory.json"))
            assertFailsWith<IllegalStateException> {
                storage.mutateEntitiesAtomically("items", linkedSetOf("a", "b", "missing", "directory"), String.serializer()) { originals ->
                    assertIs<JsonFileStorage.EntityReadResult.Corrupt>(originals["b"])
                    assertEquals(JsonFileStorage.EntityReadResult.Missing, originals["missing"])
                    assertIs<JsonFileStorage.EntityReadResult.ReadError>(originals["directory"])
                    error("strict refusal")
                }
            }
            storage.mutateEntitiesAtomically("items", setOf("a"), String.serializer()) { mapOf("a" to "new-a") }
            assertEquals("new-a", storage.loadEntity("items", "a", String.serializer()))
            assertEquals("untouched", storage.loadEntity("items", "kept", String.serializer()))
            assertEquals("{bad", Files.readString(root.resolve("entities/items/b.json")))
            assertTrue(Files.isDirectory(root.resolve("entities/items/directory.json")))
        } finally { root.toFile().deleteRecursively() }
    }
}
