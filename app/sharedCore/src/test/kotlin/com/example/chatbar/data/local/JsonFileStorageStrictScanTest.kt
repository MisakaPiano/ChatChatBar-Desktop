package com.example.chatbar.data.local

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertFailsWith

class JsonFileStorageStrictScanTest {
    @Test fun `missing entity directory scans empty without creating data root`(): Unit = runBlocking {
        val parent = Files.createTempDirectory("strict-scan-missing-")
        try {
            val root = parent.resolve("data")
            assertEquals(emptyList(), JsonFileStorage(root).scanEntitiesStrict("items", String.serializer()))
            assertFalse(Files.exists(root))
        } finally { parent.toFile().deleteRecursively() }
    }

    @Test fun `valid corrupt and unreadable files are all surfaced while loadAll stays permissive`(): Unit = runBlocking {
        val root = Files.createTempDirectory("strict-scan-results-")
        try {
            val storage = JsonFileStorage(root)
            storage.saveEntity("items", "good", "value", String.serializer())
            val directory = root.resolve("entities/items")
            Files.writeString(directory.resolve("bad.json"), "{invalid")
            Files.createDirectory(directory.resolve("unreadable.json"))
            val results = storage.scanEntitiesStrict("items", String.serializer()).associateBy { it.storageId }
            assertEquals(setOf("good", "bad", "unreadable"), results.keys)
            assertEquals("value", assertIs<JsonFileStorage.EntityReadResult.Valid<String>>(results.getValue("good").result).value)
            assertIs<JsonFileStorage.EntityReadResult.Corrupt>(results.getValue("bad").result)
            assertIs<JsonFileStorage.EntityReadResult.ReadError>(results.getValue("unreadable").result)
            assertEquals(listOf("value"), storage.loadAll("items", String.serializer()))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `directory enumeration failure is explicit`(): Unit = runBlocking {
        val root = Files.createTempDirectory("strict-scan-directory-")
        try {
            val entities = root.resolve("entities")
            Files.createDirectory(entities)
            Files.writeString(entities.resolve("items"), "not a directory")
            assertFailsWith<IOException> { JsonFileStorage(root).scanEntitiesStrict("items", String.serializer()) }
        } finally { root.toFile().deleteRecursively() }
    }
}
