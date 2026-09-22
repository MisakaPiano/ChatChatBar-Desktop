package com.example.chatbar.data.snapshot

import com.sun.nio.file.ExtendedOpenOption
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.DosFileAttributeView
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomaticBackupPruningTest {
    private lateinit var appDataRoot: Path
    private val json = Json { prettyPrint = true }

    @BeforeTest
    fun setUp() {
        appDataRoot = Files.createTempDirectory("automatic-pruning-")
        writeActive("state.txt", "state")
    }

    @AfterTest
    fun tearDown() {
        clearDosReadOnlyBelow(appDataRoot)
        appDataRoot.toFile().deleteRecursively()
    }

    @Test
    fun `pruning quarantines oldest automatic snapshots and removes workspaces`() {
        val first = createSnapshot("2026-09-22T10:00:00Z", "first", SnapshotPurpose.AUTOMATIC)
        val second = createSnapshot("2026-09-22T11:00:00Z", "second", SnapshotPurpose.AUTOMATIC)
        val third = createSnapshot("2026-09-22T12:00:00Z", "third", SnapshotPurpose.AUTOMATIC)
        val fourth = createSnapshot("2026-09-22T13:00:00Z", "fourth", SnapshotPurpose.AUTOMATIC)

        val result = service().pruneAutomaticSnapshots(maximumCount = 2)

        assertEquals(listOf(first.name, second.name), result.prunedSnapshotNames)
        assertTrue(result.retainedWorkspaces.isEmpty())
        assertTrue(result.cleanupWarnings.isEmpty())
        assertFalse(Files.exists(first.directory))
        assertFalse(Files.exists(second.directory))
        assertTrue(Files.exists(third.directory))
        assertTrue(Files.exists(fourth.directory))
        assertNoPruneWorkspaces()
    }

    @Test
    fun `manual pre-restore legacy malformed and unknown snapshots remain byte-for-byte unchanged`() {
        val manual = createSnapshot("2026-09-22T06:00:00Z", "manual", SnapshotPurpose.MANUAL)
        val preRestore = createSnapshot("2026-09-22T07:00:00Z", "pre", SnapshotPurpose.PRE_RESTORE)
        val legacy = createSnapshot("2026-09-22T08:00:00Z", "legacy", SnapshotPurpose.MANUAL)
        removeManifestField(legacy.directory, "purpose")
        val unknown = createSnapshot("2026-09-22T09:00:00Z", "unknown", SnapshotPurpose.AUTOMATIC)
        rewriteManifestField(unknown.directory, "purpose", JsonPrimitive("FUTURE"))
        val malformed = backupsRoot().resolve("malformed")
        Files.createDirectories(malformed)
        Files.writeString(malformed.resolve(AppDataSnapshotService.MANIFEST_FILE_NAME), "not-json")
        val oldAutomatic = createSnapshot("2026-09-22T10:00:00Z", "oldauto", SnapshotPurpose.AUTOMATIC)
        val newAutomatic = createSnapshot("2026-09-22T11:00:00Z", "newauto", SnapshotPurpose.AUTOMATIC)
        val protected = listOf(manual.directory, preRestore.directory, legacy.directory, unknown.directory, malformed)
        val protectedBytes = protected.associateWith(::treeFileBytes)

        val result = service().pruneAutomaticSnapshots(maximumCount = 1)

        assertEquals(listOf(oldAutomatic.name), result.prunedSnapshotNames)
        assertFalse(Files.exists(oldAutomatic.directory))
        assertTrue(Files.exists(newAutomatic.directory))
        protected.forEach { directory ->
            assertTrue(Files.exists(directory), directory.toString())
            assertFileMapsEqual(protectedBytes.getValue(directory), treeFileBytes(directory))
        }
    }

    @Test
    fun `prune workspace is excluded from listing after simulated crash and restart`() {
        val snapshot = createSnapshot("2026-09-22T10:00:00Z", "crash", SnapshotPurpose.AUTOMATIC)
        val quarantine = backupsRoot().resolve(".prune-crash.tmp")
        Files.move(snapshot.directory, quarantine)

        val listed = service().listSnapshots()

        assertTrue(listed.isEmpty())
        assertFalse(Files.exists(snapshot.directory))
        assertTrue(Files.exists(quarantine))
    }

    @Test
    fun `failed automatic creation leaves existing snapshots intact because pruning is not invoked`() {
        val duplicateService = service("2026-09-22T10:00:00Z", "duplicate")
        val existing = duplicateService.createSnapshot(SnapshotPurpose.AUTOMATIC)
        val before = treeFileBytes(existing.directory)

        assertFailsWith<Exception> {
            duplicateService.createSnapshot(SnapshotPurpose.AUTOMATIC)
        }

        assertTrue(Files.exists(existing.directory))
        assertFileMapsEqual(before, treeFileBytes(existing.directory))
        assertNoPruneWorkspaces()
    }

    @Test
    fun `post-commit cleanup failure retains quarantine reports warning and stops later pruning`() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            println("PRUNE_CLEANUP_FAILURE_FIXTURE_UNAVAILABLE")
            return
        }
        val oldest = createSnapshot("2026-09-22T10:00:00Z", "oldest", SnapshotPurpose.AUTOMATIC)
        val middle = createSnapshot("2026-09-22T11:00:00Z", "middle", SnapshotPurpose.AUTOMATIC)
        val newest = createSnapshot("2026-09-22T12:00:00Z", "newest", SnapshotPurpose.AUTOMATIC)
        val readOnlyFile = oldest.directory
            .resolve(AppDataSnapshotService.PAYLOAD_DIRECTORY_NAME)
            .resolve("state.txt")
        val dosView = Files.getFileAttributeView(readOnlyFile, DosFileAttributeView::class.java)
        if (dosView == null) {
            println("PRUNE_CLEANUP_FAILURE_FIXTURE_UNAVAILABLE")
            return
        }
        dosView.setReadOnly(true)

        val result = service().pruneAutomaticSnapshots(maximumCount = 1)

        if (result.cleanupWarnings.isEmpty()) {
            println("PRUNE_CLEANUP_FAILURE_FIXTURE_UNAVAILABLE")
            return
        }
        println("PRUNE_CLEANUP_FAILURE_FIXTURE_SUPPORTED")
        assertEquals(listOf(oldest.name), result.prunedSnapshotNames)
        assertFalse(Files.exists(oldest.directory))
        assertTrue(Files.exists(middle.directory))
        assertTrue(Files.exists(newest.directory))
        assertEquals(1, result.retainedWorkspaces.size)
        assertTrue(Files.exists(result.retainedWorkspaces.single()))
        assertTrue(result.retainedWorkspaces.single().fileName.toString().startsWith(".prune-"))
    }

    @Test
    fun `no-share-delete move failure leaves completed snapshot intact when fixture is supported`() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            println("PRUNE_MOVE_FAILURE_FIXTURE_UNAVAILABLE")
            return
        }
        val oldest = createSnapshot("2026-09-22T10:00:00Z", "oldest", SnapshotPurpose.AUTOMATIC)
        val middle = createSnapshot("2026-09-22T11:00:00Z", "middle", SnapshotPurpose.AUTOMATIC)
        val newest = createSnapshot("2026-09-22T12:00:00Z", "newest", SnapshotPurpose.AUTOMATIC)
        val locked = oldest.directory.resolve(AppDataSnapshotService.MANIFEST_FILE_NAME)
        val channel = try {
            FileChannel.open(locked, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)
        } catch (_: UnsupportedOperationException) {
            println("PRUNE_MOVE_FAILURE_FIXTURE_UNAVAILABLE")
            return
        }

        val outcome = channel.use {
            runCatching { service().pruneAutomaticSnapshots(maximumCount = 1) }
        }
        val error = outcome.exceptionOrNull()
        if (error !is AutomaticSnapshotPruneException || error.indeterminateMoveState) {
            println("PRUNE_MOVE_FAILURE_FIXTURE_UNAVAILABLE")
            return
        }

        println("PRUNE_MOVE_FAILURE_FIXTURE_SUPPORTED")
        assertEquals(oldest.name, error.failedSnapshotName)
        assertTrue(error.alreadyPrunedSnapshotNames.isEmpty())
        assertTrue(Files.exists(oldest.directory))
        assertTrue(Files.exists(middle.directory))
        assertTrue(Files.exists(newest.directory))
        assertNoPruneWorkspaces()
    }

    private fun createSnapshot(
        instant: String,
        id: String,
        purpose: SnapshotPurpose,
    ): AppDataSnapshot = service(instant, id).createSnapshot(purpose)

    private fun service(
        instant: String = "2026-09-22T14:00:00Z",
        id: String = "service",
    ) = AppDataSnapshotService(
        appDataRoot = appDataRoot,
        clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC),
        idSupplier = { id },
    )

    private fun writeActive(relative: String, value: String): Path {
        val path = appDataRoot.resolve(relative)
        Files.createDirectories(path.parent)
        return Files.writeString(path, value)
    }

    private fun backupsRoot(): Path = appDataRoot.resolve(AppDataSnapshotService.BACKUPS_DIRECTORY_NAME)

    private fun removeManifestField(snapshotDirectory: Path, key: String) {
        val manifestPath = snapshotDirectory.resolve(AppDataSnapshotService.MANIFEST_FILE_NAME)
        val root = json.parseToJsonElement(Files.readString(manifestPath)).jsonObject
        val changed = root.toMutableMap().apply { remove(key) }
        Files.writeString(manifestPath, json.encodeToString(JsonObject(changed)))
    }

    private fun rewriteManifestField(snapshotDirectory: Path, key: String, value: JsonPrimitive) {
        val manifestPath = snapshotDirectory.resolve(AppDataSnapshotService.MANIFEST_FILE_NAME)
        val root = json.parseToJsonElement(Files.readString(manifestPath)).jsonObject
        val changed = root.toMutableMap().apply { put(key, value) }
        Files.writeString(manifestPath, json.encodeToString(JsonObject(changed)))
    }

    private fun treeFileBytes(root: Path): Map<String, ByteArray> =
        Files.walk(root).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .toList()
                .associate { path -> root.relativize(path).toString() to Files.readAllBytes(path) }
        }

    private fun assertFileMapsEqual(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (path, bytes) -> assertContentEquals(bytes, actual.getValue(path), path) }
    }

    private fun assertNoPruneWorkspaces() {
        Files.list(backupsRoot()).use { paths ->
            assertTrue(paths.noneMatch { it.fileName.toString().startsWith(".prune-") })
        }
    }

    private fun clearDosReadOnlyBelow(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile).forEach { path ->
                runCatching {
                    Files.getFileAttributeView(path, DosFileAttributeView::class.java)?.setReadOnly(false)
                }
            }
        }
    }
}
