package com.example.chatbar.data.local

import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.domain.image.NovelAiInlinePayloadStore
import java.nio.file.Files
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class JsonFileStorageStudioStreamingTest {
    private val value = "AQID".repeat(2048)
    private fun json() = buildJsonObject { put("encodedVibe", value); put("ordinary", "原文 \"encodedVibe\":\"保留\"") }.toString()
    private val types = listOf("novelai_studio_draft", "novelai_studio_history_undo", "novelai_studio_guidance_checkpoint")

    @Test fun `only Studio singleton and history reads compact under their existing operation gate`() = runTest {
        val root = Files.createTempDirectory("studio-stream-types-")
        try {
            var entries = 0
            val gate = object : AppDataOperationGate {
                override suspend fun <T> withNormalOperation(operation: suspend () -> T): T { entries++; return operation() }
            }
            val storage = JsonFileStorage(root, gate); val original = json()
            Files.createDirectories(root.resolve("entities"))
            Files.writeString(root.resolve("entities/ordinary.json"), original)
            assertEquals(value, storage.loadSingleton("ordinary", JsonObject.serializer())!!.getValue("encodedVibe").jsonPrimitive.content)
            assertFalse(Files.exists(root.resolve("novelai-vibe-payloads")))
            for (type in types) {
                val file = Files.writeString(root.resolve("entities/$type.json"), original)
                val before = entries
                val record = assertNotNull(storage.loadSingleton(type, JsonObject.serializer()))
                assertEquals(before + 1, entries)
                val ref = record.getValue("encodedVibe").jsonPrimitive.content
                assertTrue(ref.startsWith(NovelAiInlinePayloadStore.PREFIX))
                assertEquals(value, NovelAiInlinePayloadStore(root.toFile()).resolve(ref))
                assertEquals(Json.parseToJsonElement(original).jsonObject.getValue("ordinary"), record.getValue("ordinary"))
                assertEquals(original, Files.readString(file))
            }
            val history = Files.createDirectory(root.resolve("entities/novelai_generation_history"))
            val file = Files.writeString(history.resolve("h.json"), original)
            val loaded = storage.loadAll("novelai_generation_history", JsonObject.serializer()).single()
            assertTrue(loaded.getValue("encodedVibe").jsonPrimitive.content.startsWith(NovelAiInlinePayloadStore.PREFIX))
            assertIs<JsonFileStorage.EntityReadResult.Valid<JsonObject>>(storage.readEntityStrict("novelai_generation_history", "h", JsonObject.serializer()))
            assertEquals(original, Files.readString(file))
            assertEquals(1L, Files.list(root.resolve("novelai-vibe-payloads")).use { it.count() })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `only absent original is Missing and bad JSON remains corrupt without default overwrite`() = runTest {
        val root = Files.createTempDirectory("studio-stream-corrupt-")
        try {
            val storage = JsonFileStorage(root); val type = types.first()
            assertNull(storage.loadSingleton(type, JsonObject.serializer()))
            assertFalse(Files.exists(root.resolve("novelai-vibe-payloads")))
            val file = root.resolve("entities/$type.json")
            for (bad in listOf("{\"encodedVibe\":\"\\x\"}", "{\"encodedVibe\":\"" + "Q".repeat(10000))) {
                Files.writeString(file, bad)
                val error = assertFailsWith<JsonFileStorage.SingletonReadException> { storage.loadSingleton(type, JsonObject.serializer()) }
                assertTrue(error.corrupt)
                assertFailsWith<JsonFileStorage.SingletonReadException> { storage.saveSingleton(type, buildJsonObject {}, JsonObject.serializer()) }
                assertEquals(bad, Files.readString(file))
            }
            Files.delete(file); Files.createDirectory(file)
            val unreadable = assertFailsWith<JsonFileStorage.SingletonReadException> { storage.loadSingleton(type, JsonObject.serializer()) }
            assertFalse(unreadable.corrupt)
            assertTrue(Files.isDirectory(file))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `unavailable derived directory and content collision are read errors never Missing or reset`() = runTest {
        for (collision in listOf(false, true)) {
            val root = Files.createTempDirectory("studio-stream-derived-failure-")
            try {
                val storage = JsonFileStorage(root); val type = types.first(); val original = json()
                Files.createDirectories(root.resolve("entities"))
                val file = Files.writeString(root.resolve("entities/$type.json"), original)
                val bad = if (collision) {
                    val directory = Files.createDirectory(root.resolve("novelai-vibe-payloads"))
                    val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
                    Files.writeString(directory.resolve(hash), "unknown collision")
                } else Files.writeString(root.resolve("novelai-vibe-payloads"), "not a directory")
                val before = Files.readAllBytes(bad)
                val error = assertFailsWith<JsonFileStorage.SingletonReadException> { storage.loadSingleton(type, JsonObject.serializer()) }
                assertFalse(error.corrupt)
                assertFailsWith<JsonFileStorage.SingletonReadException> { storage.saveSingleton(type, buildJsonObject {}, JsonObject.serializer()) }
                assertEquals(original, Files.readString(file)); assertContentEquals(before, Files.readAllBytes(bad))
                Files.delete(bad)
                storage.retryFailedSingletonReads()
                assertTrue(storage.singletonReadFailures.value.isEmpty())
                assertEquals(original, Files.readString(file))
            } finally { root.toFile().deleteRecursively() }
        }
    }

    @Test fun `failed Studio history projection preserves loaded cache and can retry after directory repair`() = runTest {
        val root = Files.createTempDirectory("studio-history-stream-failure-")
        try {
            val storage = JsonFileStorage(root)
            val type = "novelai_generation_history"
            val directory = Files.createDirectories(root.resolve("entities/$type"))
            val known = buildJsonObject { put("encodedVibe", "small"); put("id", "known") }
            val knownFile = Files.writeString(directory.resolve("known.json"), known.toString())
            assertEquals(listOf(known), storage.loadAll(type, JsonObject.serializer()))
            val original = json()
            val largeFile = Files.writeString(directory.resolve("large.json"), original)
            val blocked = Files.writeString(root.resolve("novelai-vibe-payloads"), "unknown directory occupant")
            assertFailsWith<IOException> { storage.loadAll(type, JsonObject.serializer()) }
            assertEquals(listOf(known), storage.observeAll(type, JsonObject.serializer()).first())
            assertFailsWith<IOException> { storage.loadEntity(type, "large", JsonObject.serializer()) }
            assertFailsWith<IOException> { storage.loadAllMapped(type, JsonObject.serializer()) { it } }
            assertFailsWith<IOException> { storage.queryUncached(type, JsonObject.serializer()) { true } }
            assertFailsWith<IOException> { storage.loadByIdsUncached(type, listOf("large"), JsonObject.serializer()) }
            assertFailsWith<IOException> { storage.mapByIdPrefixUncached(type, "large", JsonObject.serializer()) { it } }
            assertFailsWith<IOException> { storage.forEachUncached(type, JsonObject.serializer(), action = {}) }
            assertEquals(known.toString(), Files.readString(knownFile))
            assertEquals(original, Files.readString(largeFile))
            assertEquals("unknown directory occupant", Files.readString(blocked))
            assertEquals(listOf(known), storage.observeAll(type, JsonObject.serializer()).first())
            Files.delete(blocked) // Only the synthetic test's obstruction is removed.
            val restored = storage.loadAll(type, JsonObject.serializer())
            assertEquals(2, restored.size)
            assertEquals(2, storage.observeAll(type, JsonObject.serializer()).first().size)
            assertEquals(original, Files.readString(largeFile))
        } finally { root.toFile().deleteRecursively() }
    }
}
