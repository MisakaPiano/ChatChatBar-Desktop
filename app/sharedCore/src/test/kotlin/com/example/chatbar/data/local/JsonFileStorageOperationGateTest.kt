package com.example.chatbar.data.local

import com.example.chatbar.data.operation.AppDataOperationGate
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer

class JsonFileStorageOperationGateTest {
    @Test
    fun `every disk touching public leaf participates exactly once`() = runTest {
        val root = Files.createTempDirectory("json-gate-")
        try {
            val gate = RecordingGate()
            val storage = JsonFileStorage(root, gate)
            val serializer = String.serializer()
            val entity = "one"

            suspend fun assertOne(operation: suspend () -> Unit) {
                val before = gate.entries
                operation()
                assertEquals(before + 1, gate.entries)
            }

            assertOne { storage.saveEntity("items", "one", entity, serializer) }
            assertOne { storage.loadEntity("items", "one", serializer) }
            assertOne { storage.loadAll("items", serializer) }
            assertOne { storage.loadAllMapped("items", serializer) { it } }
            assertOne { storage.mapRawFilesUncached("items") { _, _ -> Unit } }
            assertOne {
                storage.copyEntityRawUncached("items", "one", ByteArrayOutputStream())
            }
            assertOne { storage.queryUncached("items", serializer) { true } }
            assertOne { storage.forEachUncached("items", serializer, action = {}) }
            assertOne { storage.mapByIdPrefixUncached("items", "o", serializer) { it } }
            assertOne { storage.loadByIdsUncached("items", listOf("one"), serializer) }
            assertOne { storage.fileSetSignatureByIdPrefix("items", "o") }
            assertOne { storage.saveEntityUncached("items", "two", "two", serializer) }
            assertOne {
                storage.saveAllUncached("items", mapOf("three" to "three"), serializer)
            }
            assertOne {
                storage.replaceByIdPrefixStreamingUncached("items", "replace", serializer) { emit ->
                    emit("replace-one", "replace")
                }
            }
            assertOne {
                storage.replaceWhereStreamingUncached("items", serializer, { false }) { emit ->
                    emit("stream-one", "stream")
                }
            }
            assertOne { storage.deleteEntityUncached("items", "missing") }
            assertOne { storage.deleteWhereUncached("items", serializer) { false } }
            assertOne { storage.deleteEntity<String>("items", "missing") }
            assertOne { storage.deleteByIdPrefix<String>("items", "missing") }
            assertOne { storage.deleteWhere("items", serializer) { false } }
            assertOne { storage.exists("items", "one") }
            assertOne { storage.saveSingleton("singleton", entity, serializer) }
            assertOne { storage.loadSingleton("singleton", serializer) }
            assertOne { storage.deleteByIdPrefixUncached("items", "missing") }
            assertOne { storage.deleteSingleton("singleton") }
            assertOne { storage.saveAll("items", mapOf("four" to "four"), serializer) }
            assertOne { storage.deleteAll<String>("items") }

            val beforeQuery = gate.entries
            storage.query("items", serializer) { true }
            assertEquals(beforeQuery + 1, gate.entries)

            val beforeObserve = gate.entries
            storage.observeAll("items", serializer)
            assertEquals(beforeObserve, gate.entries)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `default no-op gate preserves existing construction and singleton behavior`() = runTest {
        val root = Files.createTempDirectory("json-default-gate-")
        try {
            val storage = JsonFileStorage(root)
            storage.saveSingleton("singleton", "value", String.serializer())
            assertEquals(
                "value",
                storage.loadSingleton("singleton", String.serializer()),
            )
            assertFalse(storage.deleteSingleton("missing"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private class RecordingGate : AppDataOperationGate {
        var entries = 0

        override suspend fun <T> withNormalOperation(operation: suspend () -> T): T {
            entries++
            return operation()
        }
    }
}
