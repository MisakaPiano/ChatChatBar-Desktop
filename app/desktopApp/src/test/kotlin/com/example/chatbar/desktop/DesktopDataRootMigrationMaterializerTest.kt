package com.example.chatbar.desktop

import com.example.chatbar.data.root.AppDataRootInfrastructure
import com.example.chatbar.data.snapshot.AppDataSnapshotService
import com.example.chatbar.data.snapshot.SnapshotPurpose
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class DesktopDataRootMigrationMaterializerTest {
    @Test
    fun `active payload preserves raw bytes unknown entries and empty directories`() = runBlocking {
        withFixture {
            val nested = Files.createDirectories(sourceRoot.resolve("entities/nested"))
            val bytes = byteArrayOf(0, 1, 2, 3, -1, 10)
            Files.write(nested.resolve("binary.bin"), bytes)
            Files.writeString(sourceRoot.resolve("unknown.txt"), "unknown-root")
            Files.createDirectories(sourceRoot.resolve("future/empty"))
            val corrupt = "{ definitely-not-json".toByteArray(StandardCharsets.UTF_8)
            Files.write(sourceRoot.resolve("desktop-settings.json"), corrupt)
            Files.write(sourceRoot.resolve(AppDataRootInfrastructure.OWNERSHIP_LOCK_FILE_NAME), byteArrayOf())
            val oldWorkspace = Files.createDirectory(sourceRoot.resolve(".migration-old.tmp"))
            Files.writeString(oldWorkspace.resolve("evidence.txt"), "retain")

            val before = treeFingerprint(sourceRoot)
            val result = assertIs<DesktopMigrationMaterializationResult.Materialized>(materialize())

            assertContentEquals(bytes, Files.readAllBytes(destinationRoot.resolve("entities/nested/binary.bin")))
            assertEquals("unknown-root", Files.readString(destinationRoot.resolve("unknown.txt")))
            assertTrue(Files.isDirectory(destinationRoot.resolve("future/empty")))
            assertContentEquals(corrupt, Files.readAllBytes(destinationRoot.resolve("desktop-settings.json")))
            assertFalse(Files.exists(destinationRoot.resolve(".migration-old.tmp")))
            assertTrue(result.warnings.any { it.kind == DesktopMigrationWarningKind.SOURCE_MIGRATION_WORKSPACE })
            assertEquals(before, treeFingerprint(sourceRoot))
            assertNull(result.retainedWorkspace)
            assertNull(result.cleanupWarning)
            assertEquals(3, result.summary.activeFileCount)
            assertTrue(result.summary.activeDirectoryCount >= 4)
        }
    }

    @Test
    fun `missing backups migrates zero history without creating source backups`() = runBlocking {
        withFixture {
            Files.writeString(sourceRoot.resolve("data.txt"), "data")

            val result = assertIs<DesktopMigrationMaterializationResult.Materialized>(materialize())

            assertTrue(result.summary.migratedSnapshotNames.isEmpty())
            assertFalse(Files.exists(sourceRoot.resolve("backups")))
            assertFalse(Files.exists(destinationRoot.resolve("backups")))
        }
    }

    @Test
    fun `valid snapshot history of every purpose migrates and skipped evidence is warned`() = runBlocking {
        withFixture {
            Files.writeString(sourceRoot.resolve("data.txt"), "version-one")
            val names = listOf(
                createSnapshot("manual", SnapshotPurpose.MANUAL),
                createSnapshot("automatic", SnapshotPurpose.AUTOMATIC),
                createSnapshot("restore", SnapshotPurpose.PRE_RESTORE),
            )
            val backups = sourceRoot.resolve("backups")
            Files.createDirectory(backups.resolve("invalid-completed"))
            Files.createDirectory(backups.resolve(".snapshot-stale.tmp"))
            Files.createDirectory(backups.resolve(".restore-recovery.tmp"))
            Files.createDirectory(backups.resolve(".prune-stale.tmp"))
            Files.createDirectory(backups.resolve(".migration-stale.tmp"))
            Files.writeString(backups.resolve("unknown.bin"), "unknown")
            val before = treeFingerprint(sourceRoot)

            val result = assertIs<DesktopMigrationMaterializationResult.Materialized>(materialize())

            assertEquals(names.sorted(), result.summary.migratedSnapshotNames.sorted())
            val destinationService = AppDataSnapshotService(destinationRoot)
            names.forEach { name ->
                assertTrue(destinationService.validateSnapshot(destinationRoot.resolve("backups/$name")).valid)
            }
            assertEquals(
                setOf(
                    DesktopMigrationWarningKind.INVALID_COMPLETED_SNAPSHOT,
                    DesktopMigrationWarningKind.INTERNAL_SNAPSHOT_WORKSPACE,
                    DesktopMigrationWarningKind.RESTORE_RECOVERY_WORKSPACE,
                    DesktopMigrationWarningKind.PRUNE_WORKSPACE,
                    DesktopMigrationWarningKind.MIGRATION_WORKSPACE,
                    DesktopMigrationWarningKind.UNKNOWN_BACKUP_ENTRY,
                ),
                result.warnings.map { it.kind }.toSet(),
            )
            assertTrue(Files.exists(backups.resolve("invalid-completed")))
            assertTrue(Files.exists(backups.resolve(".restore-recovery.tmp")))
            assertEquals(before, treeFingerprint(sourceRoot))
        }
    }

    @Test
    fun `unexpected destination entry after preparation is rejected without overwrite`() = runBlocking {
        withFixture {
            Files.writeString(sourceRoot.resolve("data.txt"), "source")
            val unexpected = destinationRoot.resolve("unexpected.txt")
            Files.writeString(unexpected, "foreign")

            val result = assertIs<DesktopMigrationMaterializationResult.Failure>(materialize())

            assertEquals(DesktopMigrationMaterializationFailureKind.DESTINATION_CHANGED, result.kind)
            assertEquals("foreign", Files.readString(unexpected))
            assertFalse(Files.exists(destinationRoot.resolve("data.txt")))
        }
    }

    @Test
    fun `closed prepared destination handle is rejected before staging`() = runBlocking {
        withFixture {
            Files.writeString(sourceRoot.resolve("data.txt"), "source")
            preparedDestination.close()

            val result = assertIs<DesktopMigrationMaterializationResult.Failure>(materialize())

            assertEquals(DesktopMigrationMaterializationFailureKind.PRECONDITION_FAILED, result.kind)
            assertFalse(Files.exists(destinationRoot.resolve("data.txt")))
            assertTrue(children(destinationRoot).none { it.startsWith(".migration-") })
        }
    }

    @Test
    fun `staging tamper is detected before destination installation`() = runBlocking {
        withFixture {
            Files.writeString(sourceRoot.resolve("data.txt"), "original")
            val before = treeFingerprint(sourceRoot)
            val hooks = DesktopMigrationMaterializationHooks(
                beforeStagingValidation = { workspace ->
                    Files.writeString(workspace.resolve("active/data.txt"), "tampered")
                },
            )

            val result = assertIs<DesktopMigrationMaterializationResult.Failure>(materialize(hooks))

            assertEquals(DesktopMigrationMaterializationFailureKind.STAGING_VALIDATION_FAILED, result.kind)
            assertLockOnly(destinationRoot)
            assertEquals(before, treeFingerprint(sourceRoot))
        }
    }

    @Test
    fun `detectable active symlink is rejected without installation`() = runBlocking {
        withFixture {
            val target = Files.writeString(parent.resolve("outside.txt"), "outside")
            val link = sourceRoot.resolve("link.txt")
            if (runCatching { Files.createSymbolicLink(link, target) }.isFailure) {
                println("D2_ACTIVE_SYMLINK_FIXTURE_SKIPPED")
                return@withFixture
            }
            println("D2_ACTIVE_SYMLINK_FIXTURE_EXECUTED")

            val result = assertIs<DesktopMigrationMaterializationResult.Failure>(materialize())

            assertEquals(DesktopMigrationMaterializationFailureKind.SOURCE_ENTRY_UNSAFE, result.kind)
            assertLockOnly(destinationRoot)
            assertTrue(Files.isSymbolicLink(link))
        }
    }

    @Test
    fun `unsafe backups root fails rather than becoming active payload`() = runBlocking {
        withFixture {
            val actual = Files.createDirectory(parent.resolve("external-backups"))
            val link = sourceRoot.resolve("backups")
            if (runCatching { Files.createSymbolicLink(link, actual) }.isFailure) {
                println("D2_BACKUPS_SYMLINK_FIXTURE_SKIPPED")
                return@withFixture
            }
            println("D2_BACKUPS_SYMLINK_FIXTURE_EXECUTED")

            val result = assertIs<DesktopMigrationMaterializationResult.Failure>(materialize())

            assertEquals(DesktopMigrationMaterializationFailureKind.BACKUPS_ROOT_UNSAFE, result.kind)
            assertLockOnly(destinationRoot)
        }
    }

    @Test
    fun `install collision never overwrites and earlier installed entry rolls back`() = runBlocking {
        withFixture {
            Files.writeString(sourceRoot.resolve("a.txt"), "a")
            Files.writeString(sourceRoot.resolve("b.txt"), "b")
            val before = treeFingerprint(sourceRoot)
            val hooks = DesktopMigrationMaterializationHooks(
                beforeInstallMove = { index, _, target ->
                    if (index == 1) Files.writeString(target, "foreign")
                },
            )

            val result = assertIs<DesktopMigrationMaterializationResult.Failure>(materialize(hooks))

            assertEquals(DesktopMigrationMaterializationFailureKind.INSTALL_FAILED, result.kind)
            assertEquals("foreign", Files.readString(destinationRoot.resolve("b.txt")))
            assertFalse(Files.exists(destinationRoot.resolve("a.txt")))
            assertFalse(result.rollbackIncomplete)
            assertEquals(before, treeFingerprint(sourceRoot))
        }
    }

    @Test
    fun `injected failure after one install rolls destination back to lock only`() = runBlocking {
        withFixture {
            Files.writeString(sourceRoot.resolve("a.txt"), "a")
            Files.writeString(sourceRoot.resolve("b.txt"), "b")
            val hooks = DesktopMigrationMaterializationHooks(
                beforeInstallMove = { index, _, _ ->
                    if (index == 1) throw IOException("injected install failure")
                },
            )

            val result = assertIs<DesktopMigrationMaterializationResult.Failure>(materialize(hooks))

            assertEquals(DesktopMigrationMaterializationFailureKind.INSTALL_FAILED, result.kind)
            assertFalse(result.rollbackIncomplete)
            assertLockOnly(destinationRoot)
        }
    }

    @Test
    fun `installed validation failure triggers complete rollback`() = runBlocking {
        withFixture {
            Files.writeString(sourceRoot.resolve("data.txt"), "original")
            val before = treeFingerprint(sourceRoot)
            val hooks = DesktopMigrationMaterializationHooks(
                beforeInstalledValidation = { destination ->
                    Files.writeString(destination.resolve("data.txt"), "tampered-installed")
                },
            )

            val result = assertIs<DesktopMigrationMaterializationResult.Failure>(materialize(hooks))

            assertEquals(DesktopMigrationMaterializationFailureKind.INSTALLED_VALIDATION_FAILED, result.kind)
            assertFalse(result.rollbackIncomplete)
            assertLockOnly(destinationRoot)
            assertEquals(before, treeFingerprint(sourceRoot))
        }
    }

    @Test
    fun `rollback failure reports incomplete state and retains recovery evidence`() = runBlocking {
        withFixture {
            Files.writeString(sourceRoot.resolve("a.txt"), "a")
            Files.writeString(sourceRoot.resolve("b.txt"), "b")
            val hooks = DesktopMigrationMaterializationHooks(
                beforeInstallMove = { index, _, _ ->
                    if (index == 1) throw IOException("injected install failure")
                },
                moveRollbackEntry = { _, _ -> throw IOException("injected rollback failure") },
            )

            val result = assertIs<DesktopMigrationMaterializationResult.Failure>(materialize(hooks))

            assertEquals(DesktopMigrationMaterializationFailureKind.ROLLBACK_INCOMPLETE, result.kind)
            assertTrue(result.rollbackIncomplete)
            assertTrue(result.installedEntries.any { it.fileName.toString() == "a.txt" })
            assertTrue(result.rollbackFailures.isNotEmpty())
            assertNotNull(result.retainedWorkspace)
            assertTrue(Files.exists(destinationRoot.resolve("a.txt")))
        }
    }

    @Test
    fun `post-commit cleanup failure preserves valid destination and reports warning`() = runBlocking {
        withFixture {
            Files.writeString(sourceRoot.resolve("data.txt"), "data")
            val hooks = DesktopMigrationMaterializationHooks(
                cleanupWorkspace = { throw IOException("injected cleanup failure") },
            )

            val result = assertIs<DesktopMigrationMaterializationResult.Materialized>(materialize(hooks))

            assertEquals("data", Files.readString(destinationRoot.resolve("data.txt")))
            assertNotNull(result.cleanupWarning)
            assertNotNull(result.retainedWorkspace)
            assertTrue(Files.exists(result.retainedWorkspace))
            val manifest = Files.readString(
                result.retainedWorkspace.resolve(DesktopDataRootMigrationMaterializer.MANIFEST_FILE_NAME),
            )
            assertTrue(manifest.contains("\"formatVersion\": 1"))
            assertTrue(manifest.contains("\"sha256\""))
        }
    }

    @Test
    fun `copy phase cancellation cleans workspace and installs nothing`() = runBlocking {
        withFixture {
            Files.writeString(sourceRoot.resolve("data.txt"), "data")
            val hooks = DesktopMigrationMaterializationHooks(
                afterStagedEntry = { throw CancellationException("cancel copy") },
            )

            assertFailsWith<CancellationException> { materialize(hooks) }

            assertLockOnly(destinationRoot)
            assertTrue(children(destinationRoot).none { it.startsWith(".migration-") })
        }
    }

    @Test
    fun `destination ownership remains held through installed validation`() = runBlocking {
        withFixture {
            Files.writeString(sourceRoot.resolve("data.txt"), "data")
            var observedSameJvmOwnership = false
            val hooks = DesktopMigrationMaterializationHooks(
                beforeInstalledValidation = { destination ->
                    val result = acquire(destination)
                    val inUse = assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(result)
                    observedSameJvmOwnership = inUse.sameJvm
                },
            )

            assertIs<DesktopMigrationMaterializationResult.Materialized>(materialize(hooks))

            assertTrue(observedSameJvmOwnership)
        }
    }

    private suspend fun Fixture.materialize(
        hooks: DesktopMigrationMaterializationHooks = DesktopMigrationMaterializationHooks(),
    ): DesktopMigrationMaterializationResult = DesktopDataRootMigrationMaterializer(
        ioDispatcher = Dispatchers.Unconfined,
        workspaceIdSupplier = { "fixture" },
        hooks = hooks,
    ).materialize(sourceRoot, preparedDestination)

    private fun Fixture.createSnapshot(id: String, purpose: SnapshotPurpose): String {
        val snapshot = AppDataSnapshotService(
            appDataRoot = sourceRoot,
            clock = Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), ZoneOffset.UTC),
            idSupplier = { id },
        ).createSnapshot(purpose)
        return snapshot.name
    }

    private suspend fun withFixture(block: suspend Fixture.() -> Unit) {
        val parent = Files.createTempDirectory("desktop-migration-materializer-")
        val source = Files.createDirectory(parent.resolve("source"))
        val destination = parent.resolve("destination")
        val sourceResolution = DesktopDataRootResolution.Resolved(
            appDataRoot = source,
            provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
            bootstrapPath = parent.resolve("ChatChatBarDesktop.bootstrap.json"),
        )
        val prepared = assertIs<DesktopMigrationDestinationResult.Prepared>(
            DesktopMigrationDestinationPreparer(
                applicationHomeResolver = {
                    DesktopApplicationHomeResult.Unavailable(
                        DesktopApplicationHomeUnavailableReason.PROPERTY_ABSENT,
                    )
                },
                ownershipAcquire = DesktopDataRootOwnership::acquire,
            ).prepare(sourceResolution, destination),
        ).destination
        try {
            Fixture(parent, source, destination, prepared).block()
        } finally {
            prepared.close()
            parent.toFile().deleteRecursively()
        }
    }

    private fun acquire(root: Path): DesktopDataRootOwnershipResult =
        DesktopDataRootOwnership.acquire(
            DesktopDataRootResolution.Resolved(
                appDataRoot = root,
                provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
                bootstrapPath = root.resolveSibling("bootstrap.json"),
            ),
        )

    private fun assertLockOnly(root: Path) {
        assertEquals(listOf(AppDataRootInfrastructure.OWNERSHIP_LOCK_FILE_NAME), children(root))
    }

    private fun children(root: Path): List<String> = Files.list(root).use { stream ->
        stream.map { it.fileName.toString() }.sorted().toList()
    }

    private fun treeFingerprint(root: Path): List<String> {
        val values = mutableListOf<String>()
        Files.walk(root).use { paths ->
            paths.sorted().forEach { path ->
                if (path == root) return@forEach
                val relative = root.relativize(path).iterator().asSequence().joinToString("/")
                when {
                    Files.isSymbolicLink(path) -> values += "L:$relative:${Files.readSymbolicLink(path)}"
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> values += "D:$relative"
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ->
                        values += "F:$relative:${Files.size(path)}:${sha256(path)}"
                    else -> values += "O:$relative"
                }
            }
        }
        return values
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(Files.readAllBytes(path))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class Fixture(
        val parent: Path,
        val sourceRoot: Path,
        val destinationRoot: Path,
        val preparedDestination: DesktopPreparedMigrationDestination,
    )
}
