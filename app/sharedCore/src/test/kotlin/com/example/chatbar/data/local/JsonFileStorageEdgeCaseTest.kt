package com.example.chatbar.data.local

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonFileStorageEdgeCaseTest {
    private lateinit var appDataRoot: Path
    private val serializer = String.serializer()

    @BeforeTest
    fun setUp() {
        appDataRoot = Files.createTempDirectory("json-file-storage-edge-")
    }

    @AfterTest
    fun tearDown() {
        appDataRoot.toFile().deleteRecursively()
    }

    @Test
    fun `map raw files returns storage IDs and bytes while skipping transform failures`() = runTest {
        val storage = JsonFileStorage(appDataRoot)
        storage.saveEntityUncached("raw", "first", "alpha", serializer)
        storage.saveEntityUncached("raw", "skip", "ignored", serializer)
        val firstFile = entityDirectory("raw").resolve("first.json")

        val mapped = storage.mapRawFilesUncached("raw") { storageId, input ->
            if (storageId == "skip") error("transform failed")
            storageId to input.readBytes()
        }.toMap()

        assertEquals(setOf("first"), mapped.keys)
        assertContentEquals(Files.readAllBytes(firstFile), mapped.getValue("first"))
        assertEquals(emptyList(), storage.observeAll("raw", serializer).first())
    }

    @Test
    fun `copy raw entity preserves exact bytes flushes and keeps missing exception semantics`() = runTest {
        val storage = JsonFileStorage(appDataRoot)
        val rawBytes = "{ \"spacing\" : [1,  2], \"text\" : \"原样\" }\r\n"
            .toByteArray(StandardCharsets.UTF_8)
        writeRaw("raw-copy", "source", rawBytes)
        val output = FlushTrackingOutputStream()

        storage.copyEntityRawUncached("raw-copy", "source", output)

        assertContentEquals(rawBytes, output.toByteArray())
        assertTrue(output.flushed)
        assertEquals(emptyList(), storage.observeAll("raw-copy", serializer).first())

        val error = runCatching {
            storage.copyEntityRawUncached("raw-copy", "missing", ByteArrayOutputStream())
        }.exceptionOrNull()
        assertIs<IllegalArgumentException>(error)
    }

    @Test
    fun `query uncached filters valid entities skips corrupt JSON and leaves cache empty`() = runTest {
        val storage = JsonFileStorage(appDataRoot)
        storage.saveAllUncached(
            "query",
            mapOf("one" to "keep-one", "two" to "drop", "three" to "keep-three"),
            serializer,
        )
        writeRaw("query", "corrupt", "not-json".toByteArray())

        val result = storage.queryUncached("query", serializer) { it.startsWith("keep-") }

        assertEquals(setOf("keep-one", "keep-three"), result.toSet())
        assertEquals(emptyList(), storage.observeAll("query", serializer).first())
    }

    @Test
    fun `for each uncached processes each valid predicate match once and skips corrupt JSON`() = runTest {
        val storage = JsonFileStorage(appDataRoot)
        storage.saveAllUncached(
            "for-each",
            mapOf("one" to "keep-one", "two" to "drop", "three" to "keep-three"),
            serializer,
        )
        writeRaw("for-each", "corrupt", "not-json".toByteArray())
        val visits = mutableMapOf<String, Int>()

        storage.forEachUncached(
            entityType = "for-each",
            serializer = serializer,
            predicate = { it.startsWith("keep-") },
        ) { value ->
            visits[value] = visits.getOrDefault(value, 0) + 1
        }

        assertEquals(mapOf("keep-one" to 1, "keep-three" to 1), visits)
        assertEquals(emptyList(), storage.observeAll("for-each", serializer).first())
    }

    @Test
    fun `map by ID prefix excludes nonmatching files and skips corrupt matches`() = runTest {
        val storage = JsonFileStorage(appDataRoot)
        storage.saveAllUncached(
            "prefix-map",
            mapOf("match-one" to "one", "match-two" to "two", "other" to "other"),
            serializer,
        )
        writeRaw("prefix-map", "match-corrupt", "not-json".toByteArray())

        val result = storage.mapByIdPrefixUncached("prefix-map", "match-", serializer) { it.uppercase() }

        assertEquals(setOf("ONE", "TWO"), result.toSet())
        assertEquals(emptyList(), storage.observeAll("prefix-map", serializer).first())
    }

    @Test
    fun `load by IDs preserves requested order and skips missing and corrupt IDs`() = runTest {
        val storage = JsonFileStorage(appDataRoot)
        storage.saveAllUncached(
            "ordered",
            mapOf("first" to "one", "second" to "two", "third" to "three"),
            serializer,
        )
        writeRaw("ordered", "corrupt", "not-json".toByteArray())

        val result = storage.loadByIdsUncached(
            "ordered",
            listOf("third", "missing", "first", "corrupt", "second"),
            serializer,
        )

        assertEquals(listOf("three", "one", "two"), result)
        assertEquals(emptyList(), storage.observeAll("ordered", serializer).first())
    }

    @Test
    fun `uncached mutations change disk without updating cache until load all`() = runTest {
        val storage = JsonFileStorage(appDataRoot)
        storage.saveEntity("mutations", "cached", "cached", serializer)
        assertEquals(listOf("cached"), storage.observeAll("mutations", serializer).first())

        storage.saveEntityUncached("mutations", "single", "single", serializer)
        storage.saveAllUncached(
            "mutations",
            mapOf("remove-one" to "remove", "keep" to "keep", "prefix-one" to "prefix"),
            serializer,
        )
        assertEquals(listOf("cached"), storage.observeAll("mutations", serializer).first())

        storage.deleteEntityUncached("mutations", "cached")
        assertEquals(1, storage.deleteWhereUncached("mutations", serializer) { it == "remove" })
        assertEquals(1, storage.deleteByIdPrefixUncached("mutations", "prefix-"))
        assertEquals(listOf("cached"), storage.observeAll("mutations", serializer).first())

        assertNull(storage.loadEntity("mutations", "cached", serializer))
        assertNull(storage.loadEntity("mutations", "remove-one", serializer))
        assertNull(storage.loadEntity("mutations", "prefix-one", serializer))
        assertEquals("single", storage.loadEntity("mutations", "single", serializer))
        assertEquals("keep", storage.loadEntity("mutations", "keep", serializer))

        assertEquals(setOf("single", "keep"), storage.loadAll("mutations", serializer).toSet())
        assertEquals(
            setOf("single", "keep"),
            storage.observeAll("mutations", serializer).first().toSet(),
        )
    }

    @Test
    fun `file set signature tracks only matching prefix and changes with length and membership`() = runTest {
        val storage = JsonFileStorage(appDataRoot)
        storage.saveEntityUncached("signature", "match-one", "a", serializer)
        val initial = storage.fileSetSignatureByIdPrefix("signature", "match-")

        storage.saveEntityUncached("signature", "other", "ignored", serializer)
        val afterNonmatching = storage.fileSetSignatureByIdPrefix("signature", "match-")
        assertEquals(initial, afterNonmatching)

        storage.saveEntityUncached("signature", "match-two", "b", serializer)
        val afterAdd = storage.fileSetSignatureByIdPrefix("signature", "match-")
        assertEquals(2, afterAdd.count)
        assertNotEquals(initial, afterAdd)

        storage.saveEntityUncached("signature", "match-one", "a much longer replacement", serializer)
        val afterReplace = storage.fileSetSignatureByIdPrefix("signature", "match-")
        assertEquals(2, afterReplace.count)
        assertNotEquals(afterAdd, afterReplace)

        storage.deleteEntityUncached("signature", "match-two")
        val afterDelete = storage.fileSetSignatureByIdPrefix("signature", "match-")
        assertEquals(1, afterDelete.count)
        assertNotEquals(afterReplace, afterDelete)
    }

    @Test
    fun `replace where replaces predicate matches preserves others and does not update cache`() = runTest {
        val storage = JsonFileStorage(appDataRoot)
        storage.saveEntity("replace-where", "old-one", "replace-one", serializer)
        storage.saveEntity("replace-where", "old-two", "replace-two", serializer)
        storage.saveEntity("replace-where", "keep", "keep", serializer)
        val corrupt = writeRaw("replace-where", "corrupt", "not-json".toByteArray())
        val cachedBefore = storage.observeAll("replace-where", serializer).first().toSet()

        val count = storage.replaceWhereStreamingUncached(
            entityType = "replace-where",
            serializer = serializer,
            predicate = { it.startsWith("replace-") },
        ) { emit ->
            emit("new-one", "new-one")
            emit("new-two", "new-two")
        }

        assertEquals(2, count)
        assertNull(storage.loadEntity("replace-where", "old-one", serializer))
        assertNull(storage.loadEntity("replace-where", "old-two", serializer))
        assertEquals("keep", storage.loadEntity("replace-where", "keep", serializer))
        assertEquals("new-one", storage.loadEntity("replace-where", "new-one", serializer))
        assertEquals("new-two", storage.loadEntity("replace-where", "new-two", serializer))
        assertTrue(Files.isRegularFile(corrupt))
        assertEquals("not-json", Files.readString(corrupt))
        assertEquals(cachedBefore, storage.observeAll("replace-where", serializer).first().toSet())
        assertNoTransactionDirectories("replace-where")
    }

    @Test
    fun `blank streaming storage ID fails before switching originals and cleans staging`() = runTest {
        val storage = storageWithOriginal("blank-id")

        val error = runCatching {
            storage.replaceByIdPrefixStreamingUncached("blank-id", "session-", serializer) { emit ->
                emit(" ", "replacement")
            }
        }.exceptionOrNull()

        assertIs<IllegalArgumentException>(error)
        assertOriginalOnly(storage, "blank-id")
    }

    @Test
    fun `duplicate streaming storage ID fails before switching originals and cleans staging`() = runTest {
        val storage = storageWithOriginal("duplicate-id")

        val error = runCatching {
            storage.replaceByIdPrefixStreamingUncached("duplicate-id", "session-", serializer) { emit ->
                emit("session-new", "first")
                emit("session-new", "second")
            }
        }.exceptionOrNull()

        assertIs<IllegalStateException>(error)
        assertOriginalOnly(storage, "duplicate-id", "session-new")
    }

    @Test
    fun `streaming path traversal fails before switching originals and cleans staging`() = runTest {
        val storage = storageWithOriginal("traversal")

        val error = runCatching {
            storage.replaceByIdPrefixStreamingUncached("traversal", "session-", serializer) { emit ->
                emit("../escaped", "replacement")
            }
        }.exceptionOrNull()

        assertIs<IllegalArgumentException>(error)
        assertOriginalOnly(storage, "traversal")
        assertFalse(Files.exists(entityDirectory("traversal").resolve("escaped.json")))
    }

    @Test
    fun `installation failure restores originals and removes transaction directories`() = runTest {
        val storage = JsonFileStorage(appDataRoot)
        storage.saveEntityUncached("install-rollback", "old", "replace-me", serializer)
        val blocker = entityDirectory("install-rollback").resolve("replacement.json")
        Files.createDirectories(blocker)
        Files.writeString(blocker.resolve("blocker.txt"), "keep directory non-empty")

        val error = runCatching {
            storage.replaceWhereStreamingUncached(
                entityType = "install-rollback",
                serializer = serializer,
                predicate = { it == "replace-me" },
            ) { emit ->
                emit("replacement", "new")
            }
        }.exceptionOrNull()

        assertNotNull(error)
        assertEquals("replace-me", storage.loadEntity("install-rollback", "old", serializer))
        assertTrue(Files.isDirectory(blocker))
        assertTrue(Files.isRegularFile(blocker.resolve("blocker.txt")))
        assertNull(storage.loadEntity("install-rollback", "replacement", serializer))
        assertNoTransactionDirectories("install-rollback")
    }

    @Test
    fun `new storage instance restores entity from the same app root`() = runTest {
        JsonFileStorage(appDataRoot).saveEntity("restart", "entity", "persisted", serializer)

        val restored = JsonFileStorage(appDataRoot).loadEntity("restart", "entity", serializer)

        assertEquals("persisted", restored)
    }

    @Test
    fun `missing entity reads create their entity type directories`() = runTest {
        val entityRoot = appDataRoot.resolve("missing-entity-root")
        val allRoot = appDataRoot.resolve("missing-all-root")

        assertNull(JsonFileStorage(entityRoot).loadEntity("notes", "missing", serializer))
        assertTrue(Files.isDirectory(entityRoot.resolve("entities/notes")))

        assertEquals(emptyList(), JsonFileStorage(allRoot).loadAll("notes", serializer))
        assertTrue(Files.isDirectory(allRoot.resolve("entities/notes")))
    }

    private suspend fun storageWithOriginal(entityType: String): JsonFileStorage {
        return JsonFileStorage(appDataRoot).also { storage ->
            storage.saveEntityUncached(entityType, "session-old", "original", serializer)
        }
    }

    private suspend fun assertOriginalOnly(
        storage: JsonFileStorage,
        entityType: String,
        replacementId: String? = null,
    ) {
        assertEquals("original", storage.loadEntity(entityType, "session-old", serializer))
        replacementId?.let { assertNull(storage.loadEntity(entityType, it, serializer)) }
        assertEquals(
            listOf("session-old.json"),
            entityDirectory(entityType)
                .toFile()
                .listFiles { file -> file.isFile && file.extension == "json" }
                .orEmpty()
                .map { it.name },
        )
        assertNoTransactionDirectories(entityType)
    }

    private fun entityDirectory(entityType: String): Path =
        appDataRoot.resolve("entities").resolve(entityType)

    private fun writeRaw(entityType: String, id: String, bytes: ByteArray): Path {
        val directory = entityDirectory(entityType)
        Files.createDirectories(directory)
        return Files.write(directory.resolve("$id.json"), bytes)
    }

    private fun assertNoTransactionDirectories(entityType: String) {
        val names = entityDirectory(entityType).toFile().listFiles().orEmpty().map { it.name }
        assertTrue(names.none { it.startsWith(".replace-") }, names.toString())
        assertTrue(names.none { it.startsWith(".backup-") }, names.toString())
    }

    private class FlushTrackingOutputStream : ByteArrayOutputStream() {
        var flushed = false
            private set

        override fun flush() {
            flushed = true
            super.flush()
        }
    }
}
