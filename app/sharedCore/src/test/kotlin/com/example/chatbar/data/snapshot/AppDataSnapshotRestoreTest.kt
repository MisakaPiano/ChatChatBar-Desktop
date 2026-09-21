package com.example.chatbar.data.snapshot

import com.example.chatbar.data.local.JsonFileStorage
import com.sun.nio.file.ExtendedOpenOption
import java.nio.channels.FileChannel
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppDataSnapshotRestoreTest {
    private lateinit var appDataRoot: Path
    private val json = Json { prettyPrint = true }

    @BeforeTest
    fun setUp() {
        appDataRoot = Files.createTempDirectory("app-data-restore-")
    }

    @AfterTest
    fun tearDown() {
        appDataRoot.toFile().deleteRecursively()
    }

    @Test
    fun `restore replaces active payload preserves backups and creates safety snapshot`() {
        val service = service("older", "selected", "safety")
        writeActive("older-only.txt", "older")
        val older = service.createSnapshot()
        clearActivePayload()
        writeActive("common.txt", "state-a")
        writeActive("a-only.txt", "a-only")
        Files.createDirectories(appDataRoot.resolve("empty/nested"))
        val selected = service.createSnapshot()
        clearActivePayload()
        writeActive("common.txt", "state-b")
        writeActive("b-only.txt", "b-only")
        val beforeRestore = activeFileBytes()

        val result = service.restoreSnapshot(selected.directory)

        assertEquals(selected.name, result.restoredSnapshot.name)
        assertContentEquals("state-a".toByteArray(), Files.readAllBytes(appDataRoot.resolve("common.txt")))
        assertContentEquals("a-only".toByteArray(), Files.readAllBytes(appDataRoot.resolve("a-only.txt")))
        assertFalse(Files.exists(appDataRoot.resolve("b-only.txt")))
        assertTrue(Files.isDirectory(appDataRoot.resolve("empty/nested")))
        assertTrue(service.validateSnapshot(result.preRestoreSnapshot.directory).valid)
        assertFileMapsEqual(beforeRestore, payloadFileBytes(result.preRestoreSnapshot))
        assertEquals(
            setOf(older.name, selected.name, result.preRestoreSnapshot.name),
            service.listSnapshots().map { it.name }.toSet(),
        )
        assertNoRestoreWorkspace()
    }

    @Test
    fun `empty snapshot restore removes all active payload and preserves backup history`() {
        val service = service("empty", "safety")
        val selected = service.createSnapshot()
        writeActive("entities/item.json", "current")
        writeActive("images/current.bin", "image")

        val result = service.restoreSnapshot(selected.directory)

        assertEquals(emptyMap(), activeFileBytes())
        assertTrue(Files.isDirectory(appDataRoot.resolve(AppDataSnapshotService.BACKUPS_DIRECTORY_NAME)))
        assertEquals(
            setOf(selected.name, result.preRestoreSnapshot.name),
            service.listSnapshots().map { it.name }.toSet(),
        )
        assertNoRestoreWorkspace()
    }

    @Test
    fun `tampered snapshot hash is rejected without active mutation or transaction residue`() {
        val service = service("selected", "unused-safety")
        writeActive("selected.txt", "same-size-a")
        val selected = service.createSnapshot()
        clearActivePayload()
        writeActive("current.txt", "current")
        val before = activeFileBytes()
        Files.writeString(payload(selected).resolve("selected.txt"), "same-size-b")

        val error = runCatching { service.restoreSnapshot(selected.directory) }.exceptionOrNull()

        assertIs<SnapshotRestoreException>(error)
        assertFileMapsEqual(before, activeFileBytes())
        assertEquals(1, service.listSnapshots().size)
        assertNoRestoreWorkspace()
    }

    @Test
    fun `missing snapshot payload file is rejected without active mutation`() {
        val service = service("selected", "unused-safety")
        writeActive("selected.txt", "selected")
        val selected = service.createSnapshot()
        clearActivePayload()
        writeActive("current.txt", "current")
        val before = activeFileBytes()
        Files.delete(payload(selected).resolve("selected.txt"))

        val error = runCatching { service.restoreSnapshot(selected.directory) }.exceptionOrNull()

        assertIs<SnapshotRestoreException>(error)
        assertFileMapsEqual(before, activeFileBytes())
        assertNoRestoreWorkspace()
    }

    @Test
    fun `malformed snapshot manifest is rejected without active mutation`() {
        val service = service("selected", "unused-safety")
        writeActive("selected.txt", "selected")
        val selected = service.createSnapshot()
        clearActivePayload()
        writeActive("current.txt", "current")
        val before = activeFileBytes()
        Files.writeString(selected.directory.resolve(AppDataSnapshotService.MANIFEST_FILE_NAME), "not-json")

        val error = runCatching { service.restoreSnapshot(selected.directory) }.exceptionOrNull()

        assertIs<SnapshotRestoreException>(error)
        assertFileMapsEqual(before, activeFileBytes())
        assertNoRestoreWorkspace()
    }

    @Test
    fun `unsafe snapshot path is rejected without active mutation`() {
        val service = service("selected", "unused-safety")
        writeActive("selected.txt", "selected")
        val selected = service.createSnapshot()
        rewriteFirstManifestEntry(selected.directory, "path", JsonPrimitive("../escape.txt"))
        clearActivePayload()
        writeActive("current.txt", "current")
        val before = activeFileBytes()

        val validation = service.validateSnapshot(selected.directory)
        val error = runCatching { service.restoreSnapshot(selected.directory) }.exceptionOrNull()

        assertEquals(SnapshotValidationIssue.UNSAFE_ENTRY_PATH, validation.issue)
        assertIs<SnapshotRestoreException>(error)
        assertFileMapsEqual(before, activeFileBytes())
        assertNoRestoreWorkspace()
    }

    @Test
    fun `unsupported snapshot format is rejected without active mutation`() {
        val service = service("selected", "unused-safety")
        writeActive("selected.txt", "selected")
        val selected = service.createSnapshot()
        rewriteManifestField(selected.directory, "formatVersion", JsonPrimitive(99))
        clearActivePayload()
        writeActive("current.txt", "current")
        val before = activeFileBytes()

        val validation = service.validateSnapshot(selected.directory)
        val error = runCatching { service.restoreSnapshot(selected.directory) }.exceptionOrNull()

        assertEquals(SnapshotValidationIssue.UNSUPPORTED_FORMAT_VERSION, validation.issue)
        assertIs<SnapshotRestoreException>(error)
        assertFileMapsEqual(before, activeFileBytes())
        assertNoRestoreWorkspace()
    }

    @Test
    fun `reserved backups payload is rejected before active mutation`() {
        val service = service("selected", "unused-safety")
        writeActive("forbidden.txt", "selected")
        val selected = service.createSnapshot()
        val originalPayload = payload(selected).resolve("forbidden.txt")
        val reservedPayload = payload(selected).resolve("backups/forbidden.txt")
        Files.createDirectories(reservedPayload.parent)
        Files.move(originalPayload, reservedPayload)
        rewriteFirstManifestEntry(selected.directory, "path", JsonPrimitive("backups/forbidden.txt"))
        clearActivePayload()
        writeActive("current.txt", "current")
        val before = activeFileBytes()

        val validation = service.validateSnapshot(selected.directory)
        val error = runCatching { service.restoreSnapshot(selected.directory) }.exceptionOrNull()

        assertFalse(validation.valid)
        assertEquals(SnapshotValidationIssue.RESERVED_ENTRY_PATH, validation.issue)
        assertIs<SnapshotRestoreException>(error)
        assertFileMapsEqual(before, activeFileBytes())
        assertNoRestoreWorkspace()
    }

    @Test
    fun `restore rejects external staging and restore workspace directories`() {
        val service = service("selected")
        writeActive("selected.txt", "selected")
        val selected = service.createSnapshot()
        val backups = appDataRoot.resolve(AppDataSnapshotService.BACKUPS_DIRECTORY_NAME)
        val staging = Files.createDirectories(backups.resolve(".snapshot-manual.tmp"))
        val restoreWorkspace = Files.createDirectories(backups.resolve(".restore-manual.tmp"))
        val external = Files.createTempDirectory("external-snapshot-")
        val before = activeFileBytes()

        try {
            listOf(staging, restoreWorkspace, external).forEach { rejected ->
                assertIs<SnapshotRestoreException>(
                    runCatching { service.restoreSnapshot(rejected) }.exceptionOrNull(),
                )
                assertFileMapsEqual(before, activeFileBytes())
            }
            assertTrue(service.validateSnapshot(selected.directory).valid)
        } finally {
            external.toFile().deleteRecursively()
        }
    }

    @Test
    fun `symbolic link snapshot directory is rejected when fixture is supported`() {
        val service = service("selected")
        writeActive("selected.txt", "selected")
        val selected = service.createSnapshot()
        val link = selected.directory.parent.resolve("linked-snapshot")
        try {
            Files.createSymbolicLink(link, selected.directory.fileName)
            println("RESTORE_SYMLINK_FIXTURE_SUPPORTED")
        } catch (_: UnsupportedOperationException) {
            println("RESTORE_SYMLINK_FIXTURE_UNAVAILABLE")
            return
        } catch (_: FileSystemException) {
            println("RESTORE_SYMLINK_FIXTURE_UNAVAILABLE")
            return
        }

        assertIs<SnapshotRestoreException>(
            runCatching { service.restoreSnapshot(link) }.exceptionOrNull(),
        )
    }

    @Test
    fun `restore workspace is excluded from snapshot listing`() {
        val service = service("selected")
        val selected = service.createSnapshot()
        val restoreWorkspace = appDataRoot
            .resolve(AppDataSnapshotService.BACKUPS_DIRECTORY_NAME)
            .resolve(".restore-leftover.tmp")
        Files.createDirectories(restoreWorkspace.resolve("recovery"))
        Files.writeString(restoreWorkspace.resolve(AppDataSnapshotService.MANIFEST_FILE_NAME), "not-json")

        val listed = service.listSnapshots()

        assertEquals(listOf(selected.name), listed.map { it.name })
    }

    @Test
    fun `pre-restore safety snapshot failure leaves active payload unchanged`() {
        val service = service("duplicate", "duplicate")
        writeActive("selected.txt", "selected")
        val selected = service.createSnapshot()
        clearActivePayload()
        writeActive("current.txt", "current")
        val before = activeFileBytes()

        val error = runCatching { service.restoreSnapshot(selected.directory) }.exceptionOrNull()

        assertNotNull(error)
        assertFileMapsEqual(before, activeFileBytes())
        assertNoRestoreWorkspace()
    }

    @Test
    fun `partial active switch failure rolls back entries already moved to recovery`() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            println("POST_MUTATION_ROLLBACK_FIXTURE_UNAVAILABLE")
            return
        }
        val service = service("selected", "safety")
        writeActive("selected.txt", "selected")
        val selected = service.createSnapshot()
        clearActivePayload()
        writeActive("a-movable/original.txt", "movable")
        val locked = writeActive("z-locked/original.txt", "locked")
        val before = activeFileBytes()

        val channel = try {
            FileChannel.open(
                locked,
                StandardOpenOption.READ,
                ExtendedOpenOption.NOSHARE_DELETE,
            )
        } catch (_: UnsupportedOperationException) {
            println("POST_MUTATION_ROLLBACK_FIXTURE_UNAVAILABLE")
            return
        }
        println("POST_MUTATION_ROLLBACK_FIXTURE_SUPPORTED")
        val error = channel.use {
            runCatching { service.restoreSnapshot(selected.directory) }.exceptionOrNull()
        }

        assertNotNull(error)
        assertFileMapsEqual(before, activeFileBytes())
        assertEquals(2, service.listSnapshots().size)
        assertTrue(service.listSnapshots().all { it.validation.valid })
        assertNoRestoreWorkspace()
    }

    @Test
    fun `restored entity is readable by a new JsonFileStorage instance`() = runTest {
        val service = service("selected", "safety")
        JsonFileStorage(appDataRoot).saveEntity("notes", "item", "state-a", String.serializer())
        val selected = service.createSnapshot()
        JsonFileStorage(appDataRoot).saveEntity("notes", "item", "state-b", String.serializer())

        service.restoreSnapshot(selected.directory)

        val restored = JsonFileStorage(appDataRoot).loadEntity("notes", "item", String.serializer())
        assertEquals("state-a", restored)
    }

    private fun service(vararg ids: String): AppDataSnapshotService {
        val iterator = ids.iterator()
        var fallbackId = 0
        return AppDataSnapshotService(
            appDataRoot = appDataRoot,
            clock = Clock.fixed(Instant.parse("2026-09-22T12:00:00Z"), ZoneOffset.UTC),
            idSupplier = {
                if (iterator.hasNext()) iterator.next() else "fallback${fallbackId++}"
            },
        )
    }

    private fun writeActive(relative: String, value: String): Path {
        val path = appDataRoot.resolve(relative)
        Files.createDirectories(path.parent)
        return Files.writeString(path, value)
    }

    private fun clearActivePayload() {
        Files.list(appDataRoot).use { entries ->
            entries
                .filter { it.name != AppDataSnapshotService.BACKUPS_DIRECTORY_NAME }
                .toList()
                .forEach { it.toFile().deleteRecursively() }
        }
    }

    private fun activeFileBytes(): Map<String, ByteArray> =
        fileBytesBelow(appDataRoot) { path ->
            path.startsWith(appDataRoot.resolve(AppDataSnapshotService.BACKUPS_DIRECTORY_NAME))
        }

    private fun payloadFileBytes(snapshot: AppDataSnapshot): Map<String, ByteArray> =
        fileBytesBelow(payload(snapshot)) { false }

    private fun fileBytesBelow(root: Path, exclude: (Path) -> Boolean): Map<String, ByteArray> {
        if (!Files.exists(root)) return emptyMap()
        return Files.walk(root).use { paths ->
            paths
                .filter { path -> !exclude(path) && Files.isRegularFile(path) }
                .toList()
                .associate { path ->
                    root.relativize(path).joinToString("/") to Files.readAllBytes(path)
                }
        }
    }

    private fun assertFileMapsEqual(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (path, bytes) -> assertContentEquals(bytes, actual.getValue(path), path) }
    }

    private fun payload(snapshot: AppDataSnapshot): Path =
        snapshot.directory.resolve(AppDataSnapshotService.PAYLOAD_DIRECTORY_NAME)

    private fun assertNoRestoreWorkspace() {
        val backups = appDataRoot.resolve(AppDataSnapshotService.BACKUPS_DIRECTORY_NAME)
        if (!Files.isDirectory(backups)) return
        Files.list(backups).use { entries ->
            assertTrue(entries.noneMatch { it.fileName.toString().startsWith(".restore-") })
        }
    }

    private fun rewriteFirstManifestEntry(snapshotDirectory: Path, key: String, value: JsonPrimitive) {
        val manifestPath = snapshotDirectory.resolve(AppDataSnapshotService.MANIFEST_FILE_NAME)
        val root = json.parseToJsonElement(Files.readString(manifestPath)).jsonObject
        val entries = root.getValue("files").jsonArray
        val first = entries.first().jsonObject.toMutableMap().apply { put(key, value) }
        val changed = root.toMutableMap().apply {
            put("files", JsonArray(listOf(JsonObject(first)) + entries.drop(1)))
        }
        Files.writeString(manifestPath, json.encodeToString(JsonObject(changed)))
    }

    private fun rewriteManifestField(snapshotDirectory: Path, key: String, value: JsonPrimitive) {
        val manifestPath = snapshotDirectory.resolve(AppDataSnapshotService.MANIFEST_FILE_NAME)
        val root = json.parseToJsonElement(Files.readString(manifestPath)).jsonObject
        val changed = root.toMutableMap().apply { put(key, value) }
        Files.writeString(manifestPath, json.encodeToString(JsonObject(changed)))
    }
}
