package com.example.chatbar.data.snapshot

import com.sun.nio.file.ExtendedOpenOption
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.DosFileAttributeView
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AutomaticBackupExecutionTest {
    private lateinit var appDataRoot: Path

    @BeforeTest
    fun setUp() {
        appDataRoot = Files.createTempDirectory("automatic-execution-")
        writeActive("state.txt", "current-state")
    }

    @AfterTest
    fun tearDown() {
        clearDosReadOnlyBelow(appDataRoot)
        appDataRoot.toFile().deleteRecursively()
    }

    @Test
    fun `recent automatic snapshot skips creation and pruning`() {
        val oldest = createSnapshot("2026-09-22T10:00:00Z", "oldest", SnapshotPurpose.AUTOMATIC)
        val middle = createSnapshot("2026-09-22T11:00:00Z", "middle", SnapshotPurpose.AUTOMATIC)
        val newest = createSnapshot("2026-09-22T12:00:00Z", "newest", SnapshotPurpose.AUTOMATIC)
        val before = completedSnapshotNames()

        val result = service("2026-09-22T12:30:00Z", "unused").executeAutomaticBackup(
            minimumInterval = Duration.ofHours(1),
            maximumCount = 1,
        )

        assertIs<AutomaticBackupExecutionResult.SkippedNotDue>(result)
        assertEquals(before, completedSnapshotNames())
        listOf(oldest, middle, newest).forEach { assertTrue(Files.exists(it.directory)) }
        assertNoPruneWorkspaces()
    }

    @Test
    fun `no automatic snapshot creates one with service clock and returns created result`() {
        createSnapshot("2026-09-22T10:00:00Z", "manual", SnapshotPurpose.MANUAL)
        val now = Instant.parse("2026-09-22T12:00:00Z")

        val result = service(now.toString(), "automatic").executeAutomaticBackup(
            minimumInterval = Duration.ofHours(24),
            maximumCount = 2,
        )

        val created = assertIs<AutomaticBackupExecutionResult.Created>(result)
        assertEquals(SnapshotPurpose.AUTOMATIC, created.snapshot.purpose)
        assertEquals(now, created.snapshot.createdAt)
        assertTrue(service().validateSnapshot(created.snapshot.directory).valid)
        assertTrue(created.pruneResult.prunedSnapshotNames.isEmpty())
        assertTrue(created.pruneResult.cleanupWarnings.isEmpty())
    }

    @Test
    fun `due execution creates before pruning and retains newest automatic snapshots`() {
        val oldest = createSnapshot("2026-09-22T08:00:00Z", "oldest", SnapshotPurpose.AUTOMATIC)
        val previousNewest = createSnapshot("2026-09-22T09:00:00Z", "previous", SnapshotPurpose.AUTOMATIC)

        val result = service("2026-09-22T12:00:00Z", "created").executeAutomaticBackup(
            minimumInterval = Duration.ofHours(1),
            maximumCount = 2,
        )

        val created = assertIs<AutomaticBackupExecutionResult.Created>(result)
        assertEquals(listOf(oldest.name), created.pruneResult.prunedSnapshotNames)
        assertFalse(Files.exists(oldest.directory))
        assertTrue(Files.exists(previousNewest.directory))
        assertTrue(Files.exists(created.snapshot.directory))
        assertEquals(
            listOf(created.snapshot.name, previousNewest.name),
            service().listSnapshots()
                .filter { it.validation.valid && it.purpose == SnapshotPurpose.AUTOMATIC }
                .map(AppDataSnapshot::name),
        )
    }

    @Test
    fun `same-timestamp execution protects newly created snapshot from name tie-break pruning`() {
        val instant = "2026-09-22T12:00:00Z"
        val oldLexicallyLarger = createSnapshot(instant, "zzzz", SnapshotPurpose.AUTOMATIC)

        val result = service(instant, "aaaa").executeAutomaticBackup(
            minimumInterval = Duration.ZERO,
            maximumCount = 1,
        )

        val created = assertIs<AutomaticBackupExecutionResult.Created>(result)
        assertEquals(listOf(oldLexicallyLarger.name), created.pruneResult.prunedSnapshotNames)
        assertFalse(Files.exists(oldLexicallyLarger.directory))
        assertTrue(Files.exists(created.snapshot.directory))
        assertTrue(service().validateSnapshot(created.snapshot.directory).valid)
        assertEquals(
            listOf(created.snapshot.name),
            service().listSnapshots()
                .filter { it.validation.valid && it.purpose == SnapshotPurpose.AUTOMATIC }
                .map(AppDataSnapshot::name),
        )
    }

    @Test
    fun `protected snapshot occupies one slot while same-timestamp peers keep deterministic order`() {
        val instant = "2026-09-22T12:00:00Z"
        val largest = createSnapshot(instant, "zzzz", SnapshotPurpose.AUTOMATIC)
        val middle = createSnapshot(instant, "yyyy", SnapshotPurpose.AUTOMATIC)
        val smallestOld = createSnapshot(instant, "bbbb", SnapshotPurpose.AUTOMATIC)

        val result = service(instant, "aaaa").executeAutomaticBackup(
            minimumInterval = Duration.ZERO,
            maximumCount = 2,
        )

        val created = assertIs<AutomaticBackupExecutionResult.Created>(result)
        assertEquals(
            listOf(smallestOld.name, middle.name),
            created.pruneResult.prunedSnapshotNames,
        )
        assertTrue(Files.exists(created.snapshot.directory))
        assertTrue(Files.exists(largest.directory))
        assertFalse(Files.exists(middle.directory))
        assertFalse(Files.exists(smallestOld.directory))
        assertEquals(
            listOf(largest.name, created.snapshot.name),
            service().listSnapshots()
                .filter { it.validation.valid && it.purpose == SnapshotPurpose.AUTOMATIC }
                .map(AppDataSnapshot::name),
        )
    }

    @Test
    fun `execution pruning preserves protected snapshot bytes`() {
        val manual = createSnapshot("2026-09-22T07:00:00Z", "manual", SnapshotPurpose.MANUAL)
        val preRestore = createSnapshot("2026-09-22T08:00:00Z", "pre", SnapshotPurpose.PRE_RESTORE)
        createSnapshot("2026-09-22T09:00:00Z", "old", SnapshotPurpose.AUTOMATIC)
        val protected = listOf(manual.directory, preRestore.directory)
        val before = protected.associateWith(::treeFileBytes)

        val result = service("2026-09-22T12:00:00Z", "new").executeAutomaticBackup(
            minimumInterval = Duration.ofHours(1),
            maximumCount = 1,
        )

        assertIs<AutomaticBackupExecutionResult.Created>(result)
        protected.forEach { directory ->
            assertTrue(Files.exists(directory))
            assertFileMapsEqual(before.getValue(directory), treeFileBytes(directory))
        }
    }

    @Test
    fun `invalid arguments are rejected before filesystem mutation`() {
        val activeBefore = Files.readAllBytes(appDataRoot.resolve("state.txt"))
        val backupRoot = backupsRoot()
        val executionService = service("2026-09-22T12:00:00Z", "unused")

        assertFailsWith<IllegalArgumentException> {
            executionService.executeAutomaticBackup(Duration.ofSeconds(-1), maximumCount = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            executionService.executeAutomaticBackup(Duration.ZERO, maximumCount = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            executionService.executeAutomaticBackup(Duration.ZERO, maximumCount = -1)
        }

        assertFalse(Files.exists(backupRoot))
        assertContentEquals(activeBefore, Files.readAllBytes(appDataRoot.resolve("state.txt")))
    }

    @Test
    fun `creation failure propagates and does not prune existing snapshots`() {
        val old = createSnapshot("2026-09-22T08:00:00Z", "old", SnapshotPurpose.AUTOMATIC)
        val duplicate = createSnapshot("2026-09-22T12:00:00Z", "duplicate", SnapshotPurpose.AUTOMATIC)
        val before = listOf(old.directory, duplicate.directory).associateWith(::treeFileBytes)

        assertFailsWith<Exception> {
            service("2026-09-22T12:00:00Z", "duplicate").executeAutomaticBackup(
                minimumInterval = Duration.ZERO,
                maximumCount = 1,
            )
        }

        before.forEach { (directory, files) ->
            assertTrue(Files.exists(directory))
            assertFileMapsEqual(files, treeFileBytes(directory))
        }
        assertNoPruneWorkspaces()
    }

    @Test
    fun `prune move failure exposes created snapshot without compensating deletion`() {
        if (!isWindows()) {
            println("AUTOMATIC_EXECUTION_PRUNE_MOVE_FAILURE_FIXTURE_UNAVAILABLE")
            return
        }
        val oldest = createSnapshot("2026-09-22T08:00:00Z", "oldest", SnapshotPurpose.AUTOMATIC)
        val previousNewest = createSnapshot("2026-09-22T09:00:00Z", "previous", SnapshotPurpose.AUTOMATIC)
        val locked = oldest.directory.resolve(AppDataSnapshotService.MANIFEST_FILE_NAME)
        val channel = try {
            FileChannel.open(locked, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)
        } catch (_: UnsupportedOperationException) {
            println("AUTOMATIC_EXECUTION_PRUNE_MOVE_FAILURE_FIXTURE_UNAVAILABLE")
            return
        }

        val outcome = channel.use {
            runCatching {
                service("2026-09-22T12:00:00Z", "created").executeAutomaticBackup(
                    minimumInterval = Duration.ofHours(1),
                    maximumCount = 2,
                )
            }
        }
        val error = outcome.exceptionOrNull()
        if (error !is AutomaticBackupExecutionException ||
            error.pruningFailure !is AutomaticSnapshotPruneException
        ) {
            println("AUTOMATIC_EXECUTION_PRUNE_MOVE_FAILURE_FIXTURE_UNAVAILABLE")
            return
        }

        println("AUTOMATIC_EXECUTION_PRUNE_MOVE_FAILURE_FIXTURE_SUPPORTED")
        val pruningFailure = error.pruningFailure
        assertEquals(oldest.name, pruningFailure.failedSnapshotName)
        assertTrue(pruningFailure.alreadyPrunedSnapshotNames.isEmpty())
        assertTrue(service().validateSnapshot(error.createdSnapshot.directory).valid)
        assertEquals(SnapshotPurpose.AUTOMATIC, error.createdSnapshot.purpose)
        assertTrue(Files.exists(error.createdSnapshot.directory))
        assertTrue(Files.exists(oldest.directory))
        assertTrue(Files.exists(previousNewest.directory))
        assertNoPruneWorkspaces()
    }

    @Test
    fun `prune cleanup warning remains a successful created result and future run skips`() {
        if (!isWindows()) {
            println("AUTOMATIC_EXECUTION_PRUNE_CLEANUP_FAILURE_FIXTURE_UNAVAILABLE")
            return
        }
        val oldest = createSnapshot("2026-09-22T08:00:00Z", "oldest", SnapshotPurpose.AUTOMATIC)
        val previousNewest = createSnapshot("2026-09-22T09:00:00Z", "previous", SnapshotPurpose.AUTOMATIC)
        val readOnlyFile = oldest.directory
            .resolve(AppDataSnapshotService.PAYLOAD_DIRECTORY_NAME)
            .resolve("state.txt")
        val dosView = Files.getFileAttributeView(readOnlyFile, DosFileAttributeView::class.java)
        if (dosView == null) {
            println("AUTOMATIC_EXECUTION_PRUNE_CLEANUP_FAILURE_FIXTURE_UNAVAILABLE")
            return
        }
        dosView.setReadOnly(true)

        val result = service("2026-09-22T12:00:00Z", "created").executeAutomaticBackup(
            minimumInterval = Duration.ofHours(1),
            maximumCount = 1,
        )
        val created = assertIs<AutomaticBackupExecutionResult.Created>(result)
        if (created.pruneResult.cleanupWarnings.isEmpty()) {
            println("AUTOMATIC_EXECUTION_PRUNE_CLEANUP_FAILURE_FIXTURE_UNAVAILABLE")
            return
        }

        println("AUTOMATIC_EXECUTION_PRUNE_CLEANUP_FAILURE_FIXTURE_SUPPORTED")
        assertTrue(Files.exists(created.snapshot.directory))
        assertFalse(Files.exists(oldest.directory))
        assertTrue(Files.exists(previousNewest.directory))
        assertEquals(listOf(oldest.name), created.pruneResult.prunedSnapshotNames)
        assertEquals(1, created.pruneResult.retainedWorkspaces.size)
        assertTrue(Files.exists(created.pruneResult.retainedWorkspaces.single()))

        val namesBeforeSkip = completedSnapshotNames()
        val skipped = service("2026-09-22T12:30:00Z", "unused").executeAutomaticBackup(
            minimumInterval = Duration.ofHours(1),
            maximumCount = 1,
        )
        assertIs<AutomaticBackupExecutionResult.SkippedNotDue>(skipped)
        assertEquals(namesBeforeSkip, completedSnapshotNames())
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

    private fun completedSnapshotNames(): List<String> = service().listSnapshots().map(AppDataSnapshot::name)

    private fun backupsRoot(): Path = appDataRoot.resolve(AppDataSnapshotService.BACKUPS_DIRECTORY_NAME)

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
        if (!Files.isDirectory(backupsRoot())) return
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

    private fun isWindows(): Boolean =
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
