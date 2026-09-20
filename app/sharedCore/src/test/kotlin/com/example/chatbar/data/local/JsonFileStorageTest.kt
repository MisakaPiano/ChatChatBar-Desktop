package com.example.chatbar.data.local

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonFileStorageTest {
    private lateinit var appDataRoot: Path
    private val serializer = String.serializer()

    @BeforeTest
    fun setUp() {
        appDataRoot = Files.createTempDirectory("json-file-storage-")
    }

    @AfterTest
    fun tearDown() {
        appDataRoot.toFile().deleteRecursively()
    }

    @Test
    fun `construction does not create app root`() {
        val missingRoot = appDataRoot.resolve("not-created")

        JsonFileStorage(missingRoot)

        assertFalse(Files.exists(missingRoot))
    }

    @Test
    fun `custom app root stores entities under one entities directory`() = runTest {
        val storage = JsonFileStorage(appDataRoot)

        storage.saveEntity("notes", "first", "hello", serializer)

        assertEquals("hello", storage.loadEntity("notes", "first", serializer))
        assertTrue(Files.isRegularFile(appDataRoot.resolve("entities/notes/first.json")))
        assertFalse(Files.exists(appDataRoot.resolve("entities/entities")))
    }

    @Test
    fun `singleton round trip uses entities root`() = runTest {
        val storage = JsonFileStorage(appDataRoot)

        storage.saveSingleton("settings", "value", serializer)

        assertEquals("value", storage.loadSingleton("settings", serializer))
        assertTrue(Files.isRegularFile(appDataRoot.resolve("entities/settings.json")))
    }

    @Test
    fun `corrupt entity returns null and load all skips it`() = runTest {
        val storage = JsonFileStorage(appDataRoot)
        storage.saveEntity("notes", "good", "valid", serializer)
        Files.writeString(appDataRoot.resolve("entities/notes/bad.json"), "not-json")

        assertNull(storage.loadEntity("notes", "bad", serializer))
        assertEquals(listOf("valid"), storage.loadAll("notes", serializer))
    }

    @Test
    fun `observe all stays empty until load all initializes cache`() = runTest {
        JsonFileStorage(appDataRoot).saveEntity("notes", "disk", "stored", serializer)
        val storage = JsonFileStorage(appDataRoot)

        assertEquals(emptyList(), storage.observeAll("notes", serializer).first())
        assertEquals(listOf("stored"), storage.loadAll("notes", serializer))
        assertEquals(listOf("stored"), storage.observeAll("notes", serializer).first())
    }

    @Test
    fun `save and delete update cache`() = runTest {
        val storage = JsonFileStorage(appDataRoot)

        storage.saveEntity("notes", "first", "value", serializer)
        assertEquals(listOf("value"), storage.observeAll("notes", serializer).first())

        storage.deleteEntity<String>("notes", "first")
        assertEquals(emptyList(), storage.observeAll("notes", serializer).first())
    }

    @Test
    fun `atomic replacement leaves no temporary file`() = runTest {
        val storage = JsonFileStorage(appDataRoot)
        storage.saveEntity("notes", "first", "old", serializer)

        storage.saveEntity("notes", "first", "new", serializer)

        assertEquals("new", storage.loadEntity("notes", "first", serializer))
        assertTrue(storageDirectory("notes").none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `streaming producer failure preserves originals and removes staging`() = runTest {
        val storage = JsonFileStorage(appDataRoot)
        storage.saveEntity("notes", "session-old", "old", serializer)

        val error = runCatching {
            storage.replaceByIdPrefixStreamingUncached("notes", "session-", serializer) { emit ->
                emit("session-new", "new")
                error("producer failed")
            }
        }.exceptionOrNull()

        assertIs<IllegalStateException>(error)
        assertEquals("old", storage.loadEntity("notes", "session-old", serializer))
        assertNull(storage.loadEntity("notes", "session-new", serializer))
        assertNoTransactionDirectories("notes")
    }

    @Test
    fun `successful streaming replacement removes transaction directories`() = runTest {
        val storage = JsonFileStorage(appDataRoot)
        storage.saveEntity("notes", "session-old", "old", serializer)

        val count = storage.replaceByIdPrefixStreamingUncached(
            "notes",
            "session-",
            serializer,
        ) { emit ->
            emit("session-new", "new")
        }

        assertEquals(1, count)
        assertNull(storage.loadEntity("notes", "session-old", serializer))
        assertEquals("new", storage.loadEntity("notes", "session-new", serializer))
        assertNoTransactionDirectories("notes")
    }

    private fun storageDirectory(entityType: String) =
        appDataRoot.resolve("entities").resolve(entityType).toFile().listFiles().orEmpty().toList()

    private fun assertNoTransactionDirectories(entityType: String) {
        val names = storageDirectory(entityType).map { it.name }
        assertTrue(names.none { it.startsWith(".replace-") }, names.toString())
        assertTrue(names.none { it.startsWith(".backup-") }, names.toString())
    }
}
