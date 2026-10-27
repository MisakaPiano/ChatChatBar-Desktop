package com.example.chatbar.data.local

import com.example.chatbar.data.local.entity.VectorChunk
import com.example.chatbar.data.local.entity.ChunkSourceType
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlin.test.*

class JsonFileStorageBoundedScanTest {
    @Test fun `missing directories remain missing`() = runBlocking {
        val root = Files.createTempDirectory("bounded-scan-")
        try {
            assertEquals(0L, JsonFileStorage(root).visitEntitiesStrict("items", String.serializer()) { _, _ -> fail() })
            assertFalse(Files.exists(root.resolve("entities")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `unknown fields corrupt utf8 side files and oversized entries fail closed`() = runBlocking {
        val root = Files.createTempDirectory("bounded-errors-")
        try {
            val directory = Files.createDirectories(root.resolve("entities/vector_chunks"))
            val path = directory.resolve("x.json")
            val storage = JsonFileStorage(root)
            val valid = storage.json.encodeToString(VectorChunk.serializer(), VectorChunk("x", ChunkSourceType.DOCUMENT, "card", content = "body", embedding = emptyList(), createdAt = 1))
            for (text in listOf(valid.dropLast(1) + ",\"unexpected\":true}", "{invalid")) {
                Files.writeString(path, text)
                assertFails { storage.visitEntitiesStrict("vector_chunks", VectorChunk.serializer()) { _, _ -> fail() } }
            }
            Files.write(path, byteArrayOf(0xc3.toByte(), 0x28))
            assertFails { storage.visitEntitiesStrict("vector_chunks", VectorChunk.serializer()) { _, _ -> fail() } }
            Files.writeString(path, valid)
            assertFails { storage.visitEntitiesStrict("vector_chunks", VectorChunk.serializer(), maximumFileBytes = 8) { _, _ -> fail() } }
            Files.writeString(directory.resolve(".interrupted.tmp"), "protected")
            assertFails { storage.visitEntitiesStrict("vector_chunks", VectorChunk.serializer()) { _, _ -> } }
            assertEquals("protected", Files.readString(directory.resolve(".interrupted.tmp")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `many embeddings are delivered one record at a time without accumulating results`() = runBlocking {
        val root = Files.createTempDirectory("bounded-many-")
        try {
            val storage = JsonFileStorage(root)
            val directory = Files.createDirectories(root.resolve("entities/vector_chunks"))
            val embedding = List(1024) { .125f }
            repeat(600) { id -> Files.writeString(directory.resolve("$id.json"), storage.json.encodeToString(VectorChunk.serializer(),
                VectorChunk("$id", ChunkSourceType.DOCUMENT, "card", content = "body", embedding = embedding, createdAt = 1))) }
            var count = 0
            assertEquals(600L, storage.visitEntitiesStrict("vector_chunks", VectorChunk.serializer()) { id, chunk ->
                assertEquals(id, chunk.id); assertEquals(1024, chunk.embedding.size); count++
            })
            assertEquals(600, count)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `callback failure or cancellation does not retain the entity mutex`() = runBlocking {
        val root = Files.createTempDirectory("bounded-release-")
        try {
            val storage = JsonFileStorage(root)
            storage.saveEntity("items", "x", "body", String.serializer())
            assertFails { storage.visitEntitiesStrict("items", String.serializer()) { _, _ -> throw kotlinx.coroutines.CancellationException() } }
            assertEquals(1L, storage.visitEntitiesStrict("items", String.serializer()) { _, value -> assertEquals("body", value) })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `excessive json recursion is refused before decoder allocation`() = runBlocking {
        val root = Files.createTempDirectory("bounded-depth-")
        try {
            val path = Files.createDirectories(root.resolve("entities/items")).resolve("x.json")
            Files.writeString(path, "[".repeat(70) + "0" + "]".repeat(70))
            assertFailsWith<java.io.IOException> {
                JsonFileStorage(root).visitEntitiesStrict("items", kotlinx.serialization.json.JsonElement.serializer()) { _, _ -> fail() }
            }
            assertTrue(Files.exists(path))
        } finally { root.toFile().deleteRecursively() }
    }
}
