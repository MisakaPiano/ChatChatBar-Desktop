package com.example.chatbar.data.local

import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlin.test.*

class JsonFileStorageInventoryDiagnosticsTest {
    @Test fun `entry diagnostics precede strict decoding without skipping bad records or altering bytes`(): Unit = runBlocking {
        val root = Files.createTempDirectory("inventory-diagnostics-")
        try {
            val storage = JsonFileStorage(root)
            storage.saveEntity("items", "good", "value", String.serializer())
            val bad = Files.writeString(root.resolve("entities/items/broken.json"), "{invalid}")
            val before = Files.readAllBytes(bad)
            var current: String? = null
            assertFailsWith<SerializationException> {
                storage.visitEntitiesStrict("items", String.serializer(), onEntryRead = { current = it }) { _, _ -> }
            }
            assertEquals("broken", current)
            assertContentEquals(before, Files.readAllBytes(bad))
            assertEquals("value", assertIs<JsonFileStorage.EntityReadResult.Valid<String>>(
                storage.readEntityStrict("items", "good", String.serializer())).value)
            var visited = false
            assertFailsWith<CancellationException> {
                storage.visitEntitiesStrict("items", String.serializer(), onEntryRead = { throw CancellationException("synthetic") }) { _, _ -> visited = true }
            }
            assertFalse(visited)
            assertContentEquals(before, Files.readAllBytes(bad))
        } finally { root.toFile().deleteRecursively() }
    }
}
