package com.example.chatbar.data.local

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Encoder
import kotlin.test.*

class StorageCommitRevisionTest {
    private val serializer = String.serializer()
    private val failingSerializer = object : KSerializer<String> by String.serializer() {
        override fun serialize(encoder: Encoder, value: String) {
            encoder.encodeString(value)
            if (value == "fail") throw IOException("synthetic staged serialization failure")
        }
    }

    @Test fun finalWritesInvalidateButHydrationObservationAndAnotherInstanceDoNot() = withRoot { root ->
        val storage = JsonFileStorage(root)
        assertEquals(0L, storage.committedRevision.value)
        storage.saveEntity("items", "a", "one", serializer)
        storage.saveEntityUncached("items", "b", "two", serializer)
        var observed = false
        storage.saveEntityObserved("items", "c", "three", serializer) {
            assertEquals(3L, storage.committedRevision.value)
            assertEquals("\"three\"", Files.readString(root.resolve("entities/items/c.json")))
            observed = true
        }
        assertTrue(observed)
        storage.saveSingleton("settings", "setting", serializer)
        assertEquals(4L, storage.committedRevision.value)
        assertEquals(setOf("one", "two", "three"), storage.loadAll("items", serializer).toSet())
        assertEquals(setOf("one", "two", "three"), storage.observeAll("items", serializer).first().toSet())
        assertEquals("setting", storage.loadSingleton("settings", serializer))
        storage.readEntityStrict("items", "a", serializer)
        storage.scanEntitiesStrict("items", serializer)
        storage.retryFailedSingletonReads()
        assertEquals(4L, storage.committedRevision.value)
        val separate = JsonFileStorage(root)
        separate.loadAll("items", serializer)
        assertEquals(0L, separate.committedRevision.value)
        assertEquals(4L, storage.committedRevision.value)
    }

    @Test fun retainedPartialBatchesInvalidateOnceAndUninstalledFailuresDoNot() = withRoot { root ->
        val storage = JsonFileStorage(root)
        storage.saveAll("items", linkedMapOf("a" to "old-a", "b" to "old-b"), serializer)
        assertEquals(1L, storage.committedRevision.value)
        assertFailsWith<IOException> {
            storage.saveAll("items", linkedMapOf("a" to "new-a", "b" to "fail"), failingSerializer)
        }
        assertEquals(2L, storage.committedRevision.value)
        assertEquals(setOf("new-a", "old-b"), storage.observeAll("items", serializer).first().toSet())
        assertFailsWith<IOException> {
            storage.saveAllUncached("uncached", linkedMapOf("a" to "retained", "b" to "fail"), failingSerializer)
        }
        assertEquals(3L, storage.committedRevision.value)
        assertEquals("retained", JsonFileStorage(root).loadEntity("uncached", "a", serializer))
        assertNull(JsonFileStorage(root).loadEntity("uncached", "b", serializer))
        assertFailsWith<IOException> { storage.saveEntity("items", "a", "fail", failingSerializer) }
        assertFailsWith<IOException> { storage.saveSingleton("settings", "fail", failingSerializer) }
        assertFailsWith<IOException> { storage.saveAll("items", mapOf("a" to "fail"), failingSerializer) }
        storage.saveAll("items", emptyMap(), serializer)
        storage.saveAllUncached("uncached", emptyMap(), serializer)
        assertEquals(3L, storage.committedRevision.value)
        assertEquals("new-a", JsonFileStorage(root).loadEntity("items", "a", serializer))
    }

    @Test fun streamingStagingAndRolledBackInstallationsDoNotInvalidateButCompleteReplacementDoes() = withRoot { root ->
        val storage = JsonFileStorage(root)
        storage.saveEntity("items", "a", "original", serializer)
        val before = Files.readAllBytes(root.resolve("entities/items/a.json"))
        assertFailsWith<IOException> {
            storage.replaceByIdPrefixStreamingUncached("items", "a", failingSerializer) { emit ->
                emit("a", "staged")
                assertEquals(1L, storage.committedRevision.value)
                emit("b", "fail")
            }
        }
        assertEquals(1L, storage.committedRevision.value)
        assertContentEquals(before, Files.readAllBytes(root.resolve("entities/items/a.json")))
        val blockingDirectory = root.resolve("entities/items/blocked.json")
        Files.createDirectory(blockingDirectory)
        Files.write(blockingDirectory.resolve("owned-fixture"), byteArrayOf(7))
        assertFailsWith<IOException> {
            storage.replaceByIdPrefixStreamingUncached("items", "a", serializer) { emit ->
                emit("a", "staged-a"); emit("blocked", "cannot-replace-nonempty-directory")
                assertEquals(1L, storage.committedRevision.value)
            }
        }
        assertEquals(1L, storage.committedRevision.value)
        assertContentEquals(before, Files.readAllBytes(root.resolve("entities/items/a.json")))
        assertContentEquals(byteArrayOf(7), Files.readAllBytes(blockingDirectory.resolve("owned-fixture")))
        storage.replaceByIdPrefixStreamingUncached("items", "a", serializer) { emit ->
            emit("a", "complete-a"); emit("new", "complete-new")
            assertEquals(1L, storage.committedRevision.value)
        }
        assertEquals(2L, storage.committedRevision.value)
        assertEquals("complete-a", JsonFileStorage(root).loadEntity("items", "a", serializer))
        assertEquals("complete-new", JsonFileStorage(root).loadEntity("items", "new", serializer))
        storage.mutateEntitiesAtomically("items", setOf("a", "new"), serializer) { mapOf("a" to "mutated", "new" to null) }
        assertEquals(3L, storage.committedRevision.value)
        assertEquals("mutated", storage.loadEntity("items", "a", serializer))
        assertFalse(Files.exists(root.resolve("entities/items/new.json")))
    }

    @Test fun installedObservedWriteStillInvalidatesWhenItsReceiptCallbackFails() = withRoot { root ->
        val storage = JsonFileStorage(root)
        storage.saveEntity("items", "a", "old", serializer)
        assertFailsWith<IOException> {
            storage.saveEntityObserved("items", "a", "installed", serializer) { throw IOException("synthetic receipt failure") }
        }
        assertEquals(2L, storage.committedRevision.value)
        assertEquals("installed", JsonFileStorage(root).loadEntity("items", "a", serializer))
        assertEquals(listOf("old"), storage.observeAll("items", serializer).first())
    }

    @Test fun failedInstallationAndFailedRollbackPreserveErrorsAndBackupButInvalidatePossiblyChangedSourceOnce() = withRoot { root ->
        val storage = JsonFileStorage(root)
        storage.saveEntity("items", "a", "retained original", serializer)
        storage.saveEntityUncached("derived", "index", "unrelated index", serializer)
        val directory = root.resolve("entities/items")
        val original = Files.readAllBytes(directory.resolve("a.json"))
        val relevant = storage.committedRevisionFor(setOf("items"))
        val unrelated = storage.committedRevisionFor(setOf("derived"))
        val before = storage.committedRevision.value
        val unrelatedBefore = unrelated.first()
        assertEquals(2L, before)
        assertEquals(1L, relevant.first())
        val installationFailure = IOException("synthetic second final installation failure")
        val rollbackFailure = IOException("synthetic original restoration failure")
        var installationCalls = 0
        var rollbackCalls = 0
        var installedTarget: Path? = null
        storage.replacementFileMove = { source, target, replaceExisting ->
            when {
                source.parent.fileName.toString().startsWith(".replace-") -> {
                    installationCalls++
                    if (installationCalls == 2) throw installationFailure
                }
                source.parent.fileName.toString().startsWith(".backup-") -> {
                    rollbackCalls++
                    throw rollbackFailure
                }
            }
            if (replaceExisting) Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            else Files.move(source, target)
            if (source.parent.fileName.toString().startsWith(".replace-")) {
                installedTarget = target
                assertTrue(Files.isRegularFile(target), "At least one replacement must really reach the final directory")
                assertEquals(before, storage.committedRevision.value, "A partial install is still inside its rollback boundary")
            }
        }
        try {
            val failure = assertFailsWith<IOException> {
                storage.replaceByIdPrefixStreamingUncached("items", "a", serializer) { emit ->
                    emit("a", "replacement a"); emit("b", "replacement b")
                    assertEquals(before, storage.committedRevision.value)
                }
            }
            assertSame(installationFailure, failure)
            assertEquals(1, failure.suppressed.size)
            assertSame(rollbackFailure, failure.suppressed.single())
            assertEquals(2, installationCalls)
            assertEquals(1, rollbackCalls)
            assertFalse(Files.exists(requireNotNull(installedTarget)))
            assertFalse(Files.exists(directory.resolve("a.json")), "The original final path really remains changed after failed rollback")
            val backup = Files.newDirectoryStream(directory).use { entries ->
                entries.single { it.fileName.toString().startsWith(".backup-") }
            }
            assertContentEquals(original, Files.readAllBytes(backup.resolve("a.json")))
            assertTrue(Files.newDirectoryStream(directory).use { entries ->
                entries.none { it.fileName.toString().startsWith(".replace-") }
            })
            assertEquals(before + 1, storage.committedRevision.value)
            assertEquals(before + 1, relevant.first())
            assertEquals(unrelatedBefore, unrelated.first())
            assertEquals(before + 1, storage.committedRevision.value, "Reading the invalidation token cannot create another signal")
        } finally { storage.replacementFileMove = null }
    }

    @Test fun successfulDeletesInvalidateAndMissingDeletesOrCacheOnlyClearsDoNot() = withRoot { root ->
        val storage = JsonFileStorage(root)
        storage.saveAll("items", linkedMapOf("a" to "a", "b" to "b", "p1" to "p", "p2" to "p",
            "u1" to "u", "u2" to "u", "w1" to "where", "w2" to "uncached-where", "remaining" to "all"), serializer)
        storage.deleteEntity<String>("items", "a")
        assertTrue(storage.deleteEntityUncached("items", "b"))
        assertEquals(2, storage.deleteByIdPrefix<String>("items", "p"))
        assertEquals(2, storage.deleteByIdPrefixUncached("items", "u"))
        assertEquals(1, storage.deleteWhere("items", serializer) { it == "where" })
        assertEquals(1, storage.deleteWhereUncached("items", serializer) { it == "uncached-where" })
        storage.deleteAll<String>("items")
        assertEquals(8L, storage.committedRevision.value)
        storage.saveSingleton("settings", "value", serializer)
        assertTrue(storage.deleteSingleton("settings"))
        assertEquals(10L, storage.committedRevision.value)
        storage.deleteEntity<String>("items", "missing")
        assertFalse(storage.deleteEntityUncached("items", "missing"))
        assertEquals(0, storage.deleteByIdPrefix<String>("items", "missing"))
        assertEquals(0, storage.deleteByIdPrefixUncached("items", "missing"))
        assertEquals(0, storage.deleteWhere("items", serializer) { true })
        assertEquals(0, storage.deleteWhereUncached("items", serializer) { true })
        storage.deleteAll<String>("items")
        assertFalse(storage.deleteSingleton("settings"))
        assertEquals(10L, storage.committedRevision.value)
    }

    @Test fun concurrentCommitsAcrossEntityLocksDoNotLoseGenerationChanges() = withRoot { root ->
        val storage = JsonFileStorage(root)
        (0 until 40).map { index -> async(Dispatchers.Default) {
            storage.saveEntityUncached("items-$index", "value", "synthetic-$index", serializer)
        } }.awaitAll()
        assertEquals(40L, storage.committedRevision.value)
        assertEquals("synthetic-39", JsonFileStorage(root).loadEntity("items-39", "value", serializer))
    }

    @Test fun scopedInvalidationIgnoresUnrelatedWritesWithoutLosingEarlierConflatedRelevantCommits() = withRoot { root ->
        val storage = JsonFileStorage(root)
        val types = mutableSetOf("synced")
        val relevant = storage.committedRevisionFor(types)
        types += "derived"
        val observed = mutableListOf<Long>()
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) { relevant.collect { observed += it } }
        try {
            storage.saveEntityUncached("synced", "a", "first", serializer)
            yield()
            assertEquals(listOf(0L, 1L), observed)
            storage.saveEntityUncached("derived", "index", "derived-only", serializer)
            yield()
            assertEquals(listOf(0L, 1L), observed)
            assertEquals(2L, storage.committedRevision.value)
            // Starting a subscriber after an unrelated last write still observes the retained dirty token.
            assertEquals(1L, relevant.first())
            storage.saveEntityUncached("synced", "b", "second", serializer)
            storage.saveSingleton("settings", "unrelated-last", serializer)
            yield()
            assertEquals(4L, storage.committedRevision.value)
            assertEquals(3L, relevant.first())
            assertEquals(listOf(0L, 1L, 3L), observed)
            assertEquals(0L, storage.committedRevisionFor(emptySet()).first())
        } finally { watcher.cancelAndJoin() }
    }

    private fun withRoot(work: suspend kotlinx.coroutines.CoroutineScope.(Path) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("ccb-storage-commit-revision-").toRealPath()
        try { work(root) } finally { root.toFile().deleteRecursively() }
    }
}
