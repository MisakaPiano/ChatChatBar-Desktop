package com.example.chatbar.desktop

import com.example.chatbar.data.snapshot.AppDataSnapshot
import com.example.chatbar.data.snapshot.AppDataSnapshotService
import com.example.chatbar.data.snapshot.SnapshotPurpose
import com.example.chatbar.data.snapshot.SnapshotValidation
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject

class DesktopDataRootSwitchControllerTest {
    @Test
    fun `Portable provenance disables persistent switching`() {
        val fixture = fixture(DesktopDataRootProvenance.PORTABLE)
        val state = assertIs<DesktopDataRootSwitchState.Idle>(fixture.controller.state.value)
        assertFalse(state.supported)
        fixture.controller.chooseDestination()
        assertEquals(0, fixture.picker.calls)
        assertEquals(0, fixture.migrationCalls)
    }

    @Test
    fun `CLI override provenance disables persistent switching`() {
        val fixture = fixture(DesktopDataRootProvenance.CLI_OVERRIDE)
        val state = assertIs<DesktopDataRootSwitchState.Idle>(fixture.controller.state.value)
        assertFalse(state.supported)
        fixture.controller.chooseDestination()
        assertEquals(0, fixture.picker.calls)
    }

    @Test
    fun `all bootstrap controlled provenances enable persistent switching`() {
        listOf(
            DesktopDataRootProvenance.MISSING_BOOTSTRAP_DEFAULT,
            DesktopDataRootProvenance.BOOTSTRAP_DEFAULT,
            DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
        ).forEach { provenance ->
            assertTrue(
                assertIs<DesktopDataRootSwitchState.Idle>(fixture(provenance).controller.state.value)
                    .supported,
            )
        }
    }

    @Test
    fun `picker cancellation stays idle and never invokes migration`() {
        val fixture = fixture(pickerPath = null)
        fixture.controller.chooseDestination()
        assertIs<DesktopDataRootSwitchState.Idle>(fixture.controller.state.value)
        assertEquals(1, fixture.picker.calls)
        assertEquals(0, fixture.migrationCalls)
    }

    @Test
    fun `directory selection becomes an absolute normalized confirmation candidate`() {
        val candidate = Path.of("candidate", "..", "destination").toAbsolutePath()
        val fixture = fixture(pickerPath = candidate)
        fixture.controller.chooseDestination()
        val state = assertIs<DesktopDataRootSwitchState.CandidateSelected>(fixture.controller.state.value)
        assertEquals(candidate.normalize(), state.destinationRoot)
    }

    @Test
    fun `confirmation cancellation returns idle without migration`() {
        val fixture = fixture()
        fixture.controller.chooseDestination()
        fixture.controller.cancelCandidate()
        assertIs<DesktopDataRootSwitchState.Idle>(fixture.controller.state.value)
        assertEquals(0, fixture.migrationCalls)
    }

    @Test
    fun `double confirmation launches exactly one migration`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val resolution = resolved(DesktopDataRootProvenance.BOOTSTRAP_CUSTOM)
        val destination = Path.of("destination").toAbsolutePath()
        val controller = DesktopDataRootSwitchController(
            resolution,
            DesktopDirectoryPicker { destination },
        ) {
            calls++
            entered.complete(Unit)
            release.await()
            success(resolution.appDataRoot, destination)
        }
        controller.chooseDestination()
        val first = async { controller.confirmMigration() }
        entered.await()
        controller.confirmMigration()
        assertEquals(1, calls)
        release.complete(Unit)
        first.await()
    }

    @Test
    fun `success becomes terminal restart required`() = runTest {
        val fixture = fixture()
        fixture.controller.chooseDestination()
        fixture.controller.confirmMigration()
        val state = assertIs<DesktopDataRootSwitchState.RestartRequired>(fixture.controller.state.value)
        assertIs<DesktopDataRootMigrationResult.Success>(state.result)
    }

    @Test
    fun `success keeps startup source current and reports destination as next start`() = runTest {
        val source = Path.of("source").toAbsolutePath()
        val destination = Path.of("destination").toAbsolutePath()
        val fixture = fixture(source = source, pickerPath = destination)
        fixture.controller.chooseDestination()
        fixture.controller.confirmMigration()
        val state = assertIs<DesktopDataRootSwitchState.RestartRequired>(fixture.controller.state.value)
        assertEquals(source, state.currentRoot)
        assertEquals(destination, state.nextStartRoot)
    }

    @Test
    fun `indeterminate authority is terminal and cannot retry`() = runTest {
        val failure = failure(
            DesktopDataRootMigrationFailureKind.AUTHORITY_INDETERMINATE,
            restartRequired = true,
            sourceStillAuthoritative = null,
            authorityState = DesktopDataRootMigrationAuthorityState.INDETERMINATE,
        )
        val fixture = fixture(result = failure)
        fixture.controller.chooseDestination()
        fixture.controller.confirmMigration()
        val state = assertIs<DesktopDataRootSwitchState.RestartRequired>(fixture.controller.state.value)
        assertNull(state.nextStartRoot)
        assertNotNull(state.destinationRoot)
        fixture.controller.chooseDestination()
        assertEquals(1, fixture.picker.calls)
    }

    @Test
    fun `restart-required failure is terminal`() = runTest {
        val fixture = fixture(
            result = failure(
                DesktopDataRootMigrationFailureKind.RESTART_REQUIRED,
                restartRequired = true,
                sourceStillAuthoritative = null,
            ),
        )
        fixture.controller.chooseDestination()
        fixture.controller.confirmMigration()
        assertIs<DesktopDataRootSwitchState.RestartRequired>(fixture.controller.state.value)
    }

    @Test
    fun `runtime resume failure requires exit even when source authority is known`() = runTest {
        val fixture = fixture(
            result = failure(
                DesktopDataRootMigrationFailureKind.RUNTIME_RESUME,
                sourceStillAuthoritative = true,
                runtimeDisposition = DesktopMaintenanceResumeResult.Failed(
                    DesktopAutomaticBackupRuntimeFailure(
                        DesktopAutomaticBackupRuntimeFailureStage.START,
                        IllegalStateException("resume"),
                    ),
                ),
            ),
        )
        fixture.controller.chooseDestination()
        fixture.controller.confirmMigration()
        assertIs<DesktopDataRootSwitchState.RestartRequired>(fixture.controller.state.value)
    }

    @Test
    fun `proven precommit failure with resumed runtime is retryable`() = runTest {
        val fixture = fixture(
            result = failure(
                DesktopDataRootMigrationFailureKind.AUTHORITY_PRE_COMMIT,
                runtimeDisposition = DesktopMaintenanceResumeResult.Resumed,
            ),
        )
        fixture.controller.chooseDestination()
        fixture.controller.confirmMigration()
        val state = assertIs<DesktopDataRootSwitchState.RetryableFailure>(fixture.controller.state.value)
        fixture.controller.retryCandidate()
        assertEquals(
            state.destinationRoot,
            assertIs<DesktopDataRootSwitchState.CandidateSelected>(fixture.controller.state.value)
                .destinationRoot,
        )
    }

    @Test
    fun `materialization failure retains structured recovery evidence`() = runTest {
        val recovery = Path.of("retained-workspace").toAbsolutePath()
        val materialization = DesktopMigrationMaterializationResult.Failure(
            kind = DesktopMigrationMaterializationFailureKind.ROLLBACK_INCOMPLETE,
            message = "fixture",
            retainedWorkspace = recovery,
            rollbackIncomplete = true,
            installedEntries = listOf(Path.of("entities")),
            rollbackFailures = listOf(IllegalStateException("rollback")),
        )
        val fixture = fixture(
            result = failure(
                DesktopDataRootMigrationFailureKind.MATERIALIZATION,
                runtimeDisposition = DesktopMaintenanceResumeResult.Resumed,
                materialization = materialization,
            ),
        )
        fixture.controller.chooseDestination()
        fixture.controller.confirmMigration()
        val state = assertIs<DesktopDataRootSwitchState.RetryableFailure>(fixture.controller.state.value)
        assertEquals(materialization, state.result.materialization)
    }

    @Test
    fun `ordinary close is deferred while migration is running`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resolution = resolved(DesktopDataRootProvenance.BOOTSTRAP_DEFAULT)
        val destination = Path.of("destination").toAbsolutePath()
        val controller = DesktopDataRootSwitchController(
            resolution,
            DesktopDirectoryPicker { destination },
        ) {
            entered.complete(Unit)
            release.await()
            success(resolution.appDataRoot, destination)
        }
        controller.chooseDestination()
        val migration = async { controller.confirmMigration() }
        entered.await()
        assertFalse(controller.requestWindowClose())
        assertTrue(assertIs<DesktopDataRootSwitchState.Migrating>(controller.state.value).closeDeferred)
        release.complete(Unit)
        migration.await()
    }

    @Test
    fun `exit callback is allowed only after terminal result`() = runTest {
        val fixture = fixture()
        var exits = 0
        assertFalse(fixture.controller.requestExit { exits++ })
        fixture.controller.chooseDestination()
        fixture.controller.confirmMigration()
        assertTrue(fixture.controller.requestExit { exits++ })
        assertEquals(1, exits)
    }

    @Test
    fun `source path and provenance always come from startup resolution`() = runTest {
        val source = Path.of("startup-source").toAbsolutePath()
        val destination = Path.of("arbitrary-destination").toAbsolutePath()
        val fixture = fixture(
            provenance = DesktopDataRootProvenance.MISSING_BOOTSTRAP_DEFAULT,
            source = source,
            pickerPath = destination,
            result = failure(DesktopDataRootMigrationFailureKind.PREPARATION),
        )
        fixture.controller.chooseDestination()
        fixture.controller.confirmMigration()
        val state = assertIs<DesktopDataRootSwitchState.RetryableFailure>(fixture.controller.state.value)
        assertEquals(source, state.currentRoot)
        assertEquals(DesktopDataRootProvenance.MISSING_BOOTSTRAP_DEFAULT, state.provenance)
    }

    @Test
    fun `controller drives real migration without relabeling current root`() {
        runBlocking {
        val parent = Files.createTempDirectory("desktop-root-switch-controller-")
        val source = Files.createDirectory(parent.resolve("source"))
        val destination = parent.resolve("destination")
        val bootstrap = parent.resolve(DesktopDataDirectory.BOOTSTRAP_FILE_NAME)
        val resolution = DesktopDataRootResolution.Resolved(
            appDataRoot = source,
            provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
            bootstrapPath = bootstrap,
        )
        val fixturePath = source.resolve("entities").also(Files::createDirectory).resolve("fixture.bin")
        val fixtureBytes = byteArrayOf(0, 1, 2, 3, -1)
        Files.write(fixturePath, fixtureBytes)
        val bootstrapStore = DesktopBootstrapSettingsStore(bootstrap)
        val missing = assertIs<DesktopBootstrapLoadResult.Missing>(bootstrapStore.load())
        bootstrapStore.save(missing.document, DesktopDataRootSelection.custom(source))
        val sourceOwnership = assertIs<DesktopDataRootOwnershipResult.Acquired>(
            DesktopDataRootOwnership.acquire(resolution),
        ).ownership
        val container = DesktopAppContainer(resolution)
        try {
            container.automaticBackupRuntime.initialize()
            val controller = DesktopDataRootSwitchController(
                resolution,
                DesktopDirectoryPicker { destination },
                container.dataRootMigrationService::migrate,
            )
            controller.chooseDestination()
            controller.confirmMigration()

            val state = assertIs<DesktopDataRootSwitchState.RestartRequired>(controller.state.value)
            val result = assertIs<DesktopDataRootMigrationResult.Success>(state.result)
            assertEquals(source, state.currentRoot)
            assertEquals(destination.toAbsolutePath().normalize(), state.nextStartRoot)
            assertContentEquals(fixtureBytes, fixturePath.readBytes())
            assertContentEquals(fixtureBytes, destination.resolve("entities/fixture.bin").readBytes())
            assertEquals(SnapshotPurpose.MANUAL, result.preMigrationSnapshot.purpose)
            assertTrue(
                AppDataSnapshotService(destination)
                    .validateSnapshot(destination.resolve("backups").resolve(result.preMigrationSnapshot.name))
                    .valid,
            )
            val committed = assertIs<DesktopBootstrapLoadResult.Loaded>(bootstrapStore.load())
            assertEquals(destination.toAbsolutePath().normalize(), committed.document.selection.customRoot)
            assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(acquire(destination, bootstrap))
        } finally {
            container.close()
            sourceOwnership.close()
        }
        assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(destination, bootstrap)).ownership.close()
        assertTrue(Files.exists(source))
        parent.toFile().deleteRecursively()
        Unit
        }
    }

    private fun fixture(
        provenance: DesktopDataRootProvenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
        source: Path = Path.of("source").toAbsolutePath(),
        pickerPath: Path? = Path.of("destination").toAbsolutePath(),
        result: DesktopDataRootMigrationResult? = null,
    ): Fixture {
        val resolution = resolved(provenance, source)
        val picker = RecordingPicker(pickerPath)
        lateinit var fixture: Fixture
        val controller = DesktopDataRootSwitchController(resolution, picker) { destination ->
            fixture.migrationCalls++
            result ?: success(source, destination)
        }
        fixture = Fixture(controller, picker)
        return fixture
    }

    private fun resolved(
        provenance: DesktopDataRootProvenance,
        source: Path = Path.of("source").toAbsolutePath(),
    ) = DesktopDataRootResolution.Resolved(
        appDataRoot = source,
        provenance = provenance,
        bootstrapPath = source.resolveSibling("bootstrap.json"),
    )

    private fun success(source: Path, destination: Path) = DesktopDataRootMigrationResult.Success(
        sourceRoot = source,
        destinationRoot = destination,
        preMigrationSnapshot = snapshot(),
        materialization = materialized(destination),
        authorityCommit = DesktopBootstrapAuthorityCommitResult.Committed(
            destination = destination,
            document = DesktopBootstrapDocument(
                DesktopDataRootSelection.custom(destination),
                buildJsonObject {},
            ),
        ),
        runtimeDisposition = DesktopMaintenanceResumeResult.RestartRequired,
    )

    private fun failure(
        kind: DesktopDataRootMigrationFailureKind,
        restartRequired: Boolean = false,
        sourceStillAuthoritative: Boolean? = true,
        authorityState: DesktopDataRootMigrationAuthorityState =
            DesktopDataRootMigrationAuthorityState.SOURCE,
        runtimeDisposition: DesktopMaintenanceResumeResult? = null,
        materialization: DesktopMigrationMaterializationResult? = null,
    ) = DesktopDataRootMigrationResult.Failure(
        kind = kind,
        sourceRoot = Path.of("source").toAbsolutePath(),
        destinationRoot = Path.of("destination").toAbsolutePath(),
        restartRequired = restartRequired,
        sourceStillAuthoritative = sourceStillAuthoritative,
        authorityState = authorityState,
        runtimeDisposition = runtimeDisposition,
        materialization = materialization,
    )

    private fun snapshot() = AppDataSnapshot(
        name = "20260924T000000000Z-manual",
        directory = Path.of("snapshot"),
        createdAt = Instant.parse("2026-09-24T00:00:00Z"),
        purpose = SnapshotPurpose.MANUAL,
        validation = SnapshotValidation.VALID,
    )

    private fun materialized(destination: Path) = DesktopMigrationMaterializationResult.Materialized(
        destinationRoot = destination,
        summary = DesktopMigrationMaterializationSummary(1, 1, 5, listOf(snapshot().name)),
        warnings = emptyList(),
    )

    private fun acquire(root: Path, bootstrap: Path) = DesktopDataRootOwnership.acquire(
        DesktopDataRootResolution.Resolved(
            root,
            DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
            bootstrap,
        ),
    )

    private class RecordingPicker(private val selected: Path?) : DesktopDirectoryPicker {
        var calls = 0
        override fun pickDirectory(): Path? {
            calls++
            return selected
        }
    }

    private data class Fixture(
        val controller: DesktopDataRootSwitchController,
        val picker: RecordingPicker,
        var migrationCalls: Int = 0,
    )
}
