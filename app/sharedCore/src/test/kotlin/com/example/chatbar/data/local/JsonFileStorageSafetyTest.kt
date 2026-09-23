package com.example.chatbar.data.local

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class JsonFileStorageSafetyTest {
    @Test
    fun `failed streaming write keeps previous singleton and removes temporary file`() = withStorageRoot { root ->
        val storage = JsonFileStorage(root)
        storage.saveSingleton("settings", "old", String.serializer())
        val file = root.resolve("entities/settings.json")
        val original = Files.readAllBytes(file)

        expectFailure<IOException> {
            storage.saveSingleton("settings", "fail", failingSerializer)
        }

        assertContentEquals(original, Files.readAllBytes(file))
        assertEquals(listOf("settings.json"), Files.list(file.parent).use { stream ->
            stream.map { it.fileName.toString() }.sorted().toList()
        })
        assertEquals("old", JsonFileStorage(root).loadSingleton("settings", String.serializer()))
    }

    @Test
    fun `corrupt singleton is neither missing nor overwritable after restart`() = withStorageRoot { root ->
        val file = root.resolve("entities/settings.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, "{\"unfinished\":")
        val before = Files.readAllBytes(file)
        val storage = JsonFileStorage(root)

        val error = expectFailure<JsonFileStorage.SingletonReadException> {
            storage.loadSingleton("settings", String.serializer())
        }
        assertTrue(error.corrupt)
        assertTrue(storage.singletonReadFailures.value.containsKey("settings"))
        expectFailure<JsonFileStorage.SingletonReadException> {
            JsonFileStorage(root).saveSingleton("settings", "replacement", String.serializer())
        }
        assertContentEquals(before, Files.readAllBytes(file))
    }

    @Test
    fun `unreadable path is read failure and successful retry clears it`() = withStorageRoot { root ->
        val path = root.resolve("entities/settings.json")
        Files.createDirectories(path)
        val storage = JsonFileStorage(root)

        val error = expectFailure<JsonFileStorage.SingletonReadException> {
            storage.loadSingleton("settings", String.serializer())
        }
        assertFalse(error.corrupt)
        expectFailure<JsonFileStorage.SingletonReadException> {
            storage.saveSingleton("settings", "replacement", String.serializer())
        }
        assertTrue(Files.isDirectory(path))
        Files.delete(path)
        Files.writeString(path, "\"restored\"")

        storage.retryFailedSingletonReads()

        assertTrue(storage.singletonReadFailures.value.isEmpty())
        assertEquals("restored", storage.loadSingleton("settings", String.serializer()))
    }

    @Test
    fun `missing singleton returns null without failure`() = withStorageRoot { root ->
        val storage = JsonFileStorage(root)
        assertNull(storage.loadSingleton("settings", String.serializer()))
        assertTrue(storage.singletonReadFailures.value.isEmpty())
    }

    @Test
    fun `concurrent singleton updates remain serialized`() = withStorageRoot { root ->
        val storage = JsonFileStorage(root)
        val values = (0..40).map { "$it:" + "x".repeat(2000) }
        values.map { value ->
            async(Dispatchers.Default) { storage.saveSingleton("settings", value, String.serializer()) }
        }.awaitAll()
        assertTrue(storage.loadSingleton("settings", String.serializer()) in values)
        assertTrue(storage.singletonReadFailures.value.isEmpty())
    }

    @Test
    fun `concurrent singleton reads only see complete writes`() = withStorageRoot { root ->
        val storage = JsonFileStorage(root)
        val values = (0..30).map { "$it:" + "x".repeat(20000) }
        storage.saveSingleton("settings", values.first(), String.serializer())
        values.drop(1).map { value ->
            async(Dispatchers.Default) {
                storage.saveSingleton("settings", value, String.serializer())
                assertTrue(storage.loadSingleton("settings", String.serializer()) in values)
            }
        }.awaitAll()
        assertTrue(storage.singletonReadFailures.value.isEmpty())
    }

    @Test
    fun `cancellation is not converted into corruption`() = withStorageRoot { root ->
        val storage = JsonFileStorage(root)
        storage.saveSingleton("settings", "old", String.serializer())
        val serializer = object : KSerializer<String> by String.serializer() {
            override fun deserialize(decoder: Decoder): String = throw CancellationException("cancelled")
        }

        expectFailure<CancellationException> { storage.loadSingleton("settings", serializer) }

        assertTrue(storage.singletonReadFailures.value.isEmpty())
        assertEquals("old", storage.loadSingleton("settings", String.serializer()))
    }

    @Test
    fun `partial batch failure publishes only completed files`() = withStorageRoot { root ->
        val storage = JsonFileStorage(root)
        storage.saveAll(
            "items",
            linkedMapOf("a" to "old-a", "b" to "old-b", "c" to "old-c"),
            String.serializer(),
        )

        expectFailure<IOException> {
            storage.saveAll(
                "items",
                linkedMapOf("a" to "new-a", "b" to "fail", "c" to "new-c"),
                failingSerializer,
            )
        }

        val expected = setOf("new-a", "old-b", "old-c")
        assertEquals(expected, storage.observeAll("items", String.serializer()).first().toSet())
        assertEquals(expected, JsonFileStorage(root).loadAll("items", String.serializer()).toSet())
    }

    @Test
    fun `observation does not read disk before explicit load`() = withStorageRoot { root ->
        JsonFileStorage(root).saveEntity("items", "a", "saved", String.serializer())
        val storage = JsonFileStorage(root)

        assertTrue(storage.observeAll("items", String.serializer()).first().isEmpty())
        storage.loadAll("items", String.serializer())
        assertEquals(listOf("saved"), storage.observeAll("items", String.serializer()).first())
    }

    private val failingSerializer = object : KSerializer<String> by String.serializer() {
        override fun serialize(encoder: Encoder, value: String) {
            encoder.encodeString(if (value == "fail") "x".repeat(100000) else value)
            if (value == "fail") throw IOException("injected write failure")
        }
    }

    private fun withStorageRoot(block: suspend kotlinx.coroutines.CoroutineScope.(Path) -> Unit) = runTest {
        val root = Files.createTempDirectory("json-safety-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private suspend inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        fail("Expected ${T::class.simpleName}")
    }
}