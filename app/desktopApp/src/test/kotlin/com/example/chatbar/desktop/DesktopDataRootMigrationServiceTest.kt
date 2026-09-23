package com.example.chatbar.desktop

import com.example.chatbar.data.snapshot.AppDataSnapshot
import com.example.chatbar.data.snapshot.AppDataSnapshotService
import com.example.chatbar.data.snapshot.AutomaticBackupExecutionResult
import com.example.chatbar.data.snapshot.SnapshotPurpose
import com.example.chatbar.data.snapshot.SnapshotValidation
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class DesktopDataRootMigrationServiceTest {
    @Test
    fun `successful transaction follows locked order and retains destination ownership`() = withFixture {
        val events = mutableListOf<String>()
        val destination = parent.resolve("destination")
        val prepared = prepare(destination)
        val snapshot = snapshot("safety")
        val materialized = materialized(destination, listOf(snapshot.name))
        val service = service(
            dependencies = dependencies(
                prepare = { events += "prepare"; prepared },
                preflight = {
                    assertEquals(DesktopAutomaticBackupRuntimeMode.MAINTENANCE_PAUSED, runtime.state.value.mode)
                    assertFalse(runtime.state.value.schedulerRunning)
                    assertEquals(DesktopDataOperationCoordinatorState.EXCLUSIVE, coordinator.state)
                    events += "preflight"
                    DesktopMigrationMaterializationPreflightResult.Ready
                },
                snapshot = { purpose ->
                    assertEquals(SnapshotPurpose.MANUAL, purpose)
                    events += "snapshot"
                    snapshot
                },
                materialize = { events += "materialize"; materialized },
                authority = {
                    events += "authority"
                    committed(destination)
                },
            ),
        )

        val result = assertIs<DesktopDataRootMigrationResult.Success>(service.migrate(destination))

        assertEquals(listOf("prepare", "preflight", "snapshot", "materialize", "authority"), events)
        assertSame(snapshot, result.preMigrationSnapshot)
        assertSame(materialized, result.materialization)
        assertEquals(DesktopDataOperationCoordinatorState.RESTART_REQUIRED, coordinator.state)
        assertEquals(DesktopAutomaticBackupRuntimeMode.RESTART_REQUIRED, runtime.state.value.mode)
        assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(acquire(destination))
        service.close()
        assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(destination)).ownership.close()
    }

    @Test
    fun `real filesystem success creates MANUAL snapshot copies it and commits bootstrap`() = withFixture(
        initializeRuntime = false,
        writeBootstrap = true,
        holdSourceOwnership = true,
    ) {
        val payload = source.resolve("entities").also(Files::createDirectory).resolve("item.json")
        val bytes = byteArrayOf(0, 1, 2, 3, -1)
        Files.write(payload, bytes)
        val destination = parent.resolve("destination")
        val container = DesktopAppContainer(resolution)
        container.automaticBackupRuntime.initialize()

        val result = assertIs<DesktopDataRootMigrationResult.Success>(
            container.dataRootMigrationService.migrate(destination),
        )

        assertEquals(SnapshotPurpose.MANUAL, result.preMigrationSnapshot.purpose)
        assertTrue(result.preMigrationSnapshot.validation.valid)
        assertTrue(result.preMigrationSnapshot.name in result.materialization.summary.migratedSnapshotNames)
        assertContentEquals(bytes, Files.readAllBytes(destination.resolve("entities/item.json")))
        assertContentEquals(bytes, Files.readAllBytes(payload))
        assertTrue(
            AppDataSnapshotService(destination)
                .validateSnapshot(destination.resolve("backups").resolve(result.preMigrationSnapshot.name))
                .valid,
        )
        val bootstrap = assertIs<DesktopBootstrapLoadResult.Loaded>(
            DesktopBootstrapSettingsStore(resolution.bootstrapPath).load(),
        )
        assertEquals(destination.toAbsolutePath().normalize(), bootstrap.document.selection.customRoot)
        assertEquals(
            DesktopDataOperationCoordinatorState.RESTART_REQUIRED,
            container.dataOperationCoordinator.state,
        )
        assertEquals(
            DesktopAutomaticBackupRuntimeMode.RESTART_REQUIRED,
            container.automaticBackupRuntime.state.value.mode,
        )
        assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(acquire(destination))

        container.close()
        assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(destination)).ownership.close()
    }

    @Test
    fun `preparation and pause failures never enter exclusive`() = withFixture(initializeRuntime = false) {
        val destination = parent.resolve("destination")
        var preflightCalls = 0
        val prepared = prepare(destination)
        val pauseFailureService = service(
            dependencies(
                prepare = { prepared },
                preflight = { preflightCalls++; DesktopMigrationMaterializationPreflightResult.Ready },
            ),
        )

        val pauseFailure = assertIs<DesktopDataRootMigrationResult.Failure>(
            pauseFailureService.migrate(destination),
        )
        assertEquals(DesktopDataRootMigrationFailureKind.RUNTIME_PAUSE, pauseFailure.kind)
        assertEquals(0, preflightCalls)
        assertEquals(DesktopDataOperationCoordinatorState.OPEN, coordinator.state)
        assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(destination)).ownership.close()

        val preparationFailureService = service(
            dependencies(
                prepareFailure = DesktopMigrationDestinationResult.Failure(
                    kind = DesktopMigrationDestinationFailureKind.DESTINATION_NOT_EMPTY,
                    sourceRoot = source,
                    destinationRoot = destination,
                    message = "fixture",
                ),
                preflight = { preflightCalls++; DesktopMigrationMaterializationPreflightResult.Ready },
            ),
        )
        val preparationFailure = assertIs<DesktopDataRootMigrationResult.Failure>(
            preparationFailureService.migrate(destination),
        )
        assertEquals(DesktopDataRootMigrationFailureKind.PREPARATION, preparationFailure.kind)
        assertEquals(0, preflightCalls)
    }

    @Test
    fun `snapshot failure skips materialization and authority then resumes source`() = withFixture {
        val destination = parent.resolve("destination")
        val prepared = prepare(destination)
        var materializeCalled = false
        var authorityCalled = false
        val snapshotError = IllegalStateException("snapshot")
        val service = service(
            dependencies(
                prepare = { prepared },
                snapshot = { throw snapshotError },
                materialize = { materializeCalled = true; materialized(destination) },
                authority = { authorityCalled = true; committed(destination) },
            ),
        )

        val result = assertIs<DesktopDataRootMigrationResult.Failure>(service.migrate(destination))

        assertEquals(DesktopDataRootMigrationFailureKind.SAFETY_SNAPSHOT, result.kind)
        assertEquals(snapshotError.message, result.cause?.message)
        assertFalse(materializeCalled)
        assertFalse(authorityCalled)
        assertResumedAndReleased(destination)
    }

    @Test
    fun `preflight failure occurs before safety snapshot and resumes source`() = withFixture {
        val destination = parent.resolve("destination")
        val prepared = prepare(destination)
        val preflightFailure = DesktopMigrationMaterializationResult.Failure(
            kind = DesktopMigrationMaterializationFailureKind.DESTINATION_CHANGED,
            message = "destination changed",
        )
        var snapshotCalled = false
        val service = service(
            dependencies(
                prepare = { prepared },
                preflight = { DesktopMigrationMaterializationPreflightResult.Failure(preflightFailure) },
                snapshot = {
                    snapshotCalled = true
                    snapshot("unexpected")
                },
            ),
        )

        val result = assertIs<DesktopDataRootMigrationResult.Failure>(service.migrate(destination))

        assertEquals(DesktopDataRootMigrationFailureKind.PREFLIGHT, result.kind)
        assertSame(preflightFailure, result.preflightFailure)
        assertFalse(snapshotCalled)
        assertResumedAndReleased(destination)
    }

    @Test
    fun `materialization failure evidence is preserved and authority is not called`() = withFixture {
        val destination = parent.resolve("destination")
        val prepared = prepare(destination)
        val retainedWorkspace = destination.resolve(".migration-fixture.tmp")
        val installed = destination.resolve("entities")
        val rollback = IllegalStateException("rollback")
        val d2Failure = DesktopMigrationMaterializationResult.Failure(
            kind = DesktopMigrationMaterializationFailureKind.ROLLBACK_INCOMPLETE,
            message = "fixture",
            warnings = listOf(DesktopMigrationWarning(DesktopMigrationWarningKind.UNKNOWN_BACKUP_ENTRY, source, "warning")),
            retainedWorkspace = retainedWorkspace,
            rollbackIncomplete = true,
            installedEntries = listOf(installed),
            rollbackFailures = listOf(rollback),
        )
        var authorityCalled = false
        val service = service(
            dependencies(
                prepare = { prepared },
                materialize = { d2Failure },
                authority = { authorityCalled = true; committed(destination) },
            ),
        )

        val result = assertIs<DesktopDataRootMigrationResult.Failure>(service.migrate(destination))

        assertEquals(DesktopDataRootMigrationFailureKind.MATERIALIZATION, result.kind)
        assertSame(d2Failure, result.materialization)
        assertFalse(authorityCalled)
        assertResumedAndReleased(destination)
    }

    @Test
    fun `proven precommit authority outcomes retain materialized bytes and resume source`() = runBlocking {
        val authorityResults: List<(Fixture, Path) -> DesktopBootstrapAuthorityCommitResult> = listOf(
            { fixture, _ -> DesktopBootstrapAuthorityCommitResult.Busy(fixture.parent.resolve("authority.lock"), false) },
            { _, _ -> DesktopBootstrapAuthorityCommitResult.AuthorityChanged("changed") },
            { _, _ -> DesktopBootstrapAuthorityCommitResult.CommitFailedPreCommit(IllegalStateException("save")) },
        )
        authorityResults.forEachIndexed { index, authorityResult ->
            withFixtureSuspend {
                val destination = parent.resolve("destination-$index")
                val prepared = prepare(destination)
                val copied = destination.resolve("copied.bin")
                val service = service(
                    dependencies(
                        prepare = { prepared },
                        materialize = {
                            Files.writeString(copied, "retained")
                            materialized(destination)
                        },
                        authority = { authorityResult(this, destination) },
                    ),
                )

                val result = assertIs<DesktopDataRootMigrationResult.Failure>(service.migrate(destination))

                assertEquals(DesktopDataRootMigrationFailureKind.AUTHORITY_PRE_COMMIT, result.kind)
                assertTrue(Files.exists(copied))
                assertTrue(result.sourceStillAuthoritative == true)
                assertFalse(result.restartRequired)
                assertResumedAndReleased(destination)
            }
        }
    }

    @Test
    fun `indeterminate authority seals restart rejects retry and retains ownership until close`() = withFixture {
        val firstDestination = parent.resolve("destination")
        val prepared = prepare(firstDestination)
        val service = service(
            dependencies(
                prepare = { prepared },
                materialize = { materialized(firstDestination) },
                authority = { DesktopBootstrapAuthorityCommitResult.CommitIndeterminate("ambiguous") },
            ),
        )

        val result = assertIs<DesktopDataRootMigrationResult.Failure>(service.migrate(firstDestination))

        assertEquals(DesktopDataRootMigrationFailureKind.AUTHORITY_INDETERMINATE, result.kind)
        assertEquals(DesktopDataRootMigrationAuthorityState.INDETERMINATE, result.authorityState)
        assertTrue(result.restartRequired)
        assertTrue(result.destinationOwnershipRetained)
        assertEquals(DesktopDataOperationCoordinatorState.RESTART_REQUIRED, coordinator.state)
        assertEquals(DesktopAutomaticBackupRuntimeMode.RESTART_REQUIRED, runtime.state.value.mode)
        assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(acquire(firstDestination))
        val retry = assertIs<DesktopDataRootMigrationResult.Failure>(
            service.migrate(parent.resolve("second")),
        )
        assertEquals(DesktopDataRootMigrationFailureKind.RESTART_REQUIRED, retry.kind)
        service.close()
        assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(firstDestination)).ownership.close()
    }

    @Test
    fun `cancellation during materialization releases destination and resumes source`() = withFixture {
        val destination = parent.resolve("destination")
        val prepared = prepare(destination)
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val service = service(
            dependencies(
                prepare = { prepared },
                materialize = {
                    entered.complete(Unit)
                    never.await()
                    materialized(destination)
                },
            ),
        )

        coroutineScope {
            val migration = async { service.migrate(destination) }
            entered.await()
            migration.cancelAndJoin()
            assertTrue(migration.isCancelled)
        }
        assertResumedAndReleased(destination)
    }

    @Test
    fun `cancellation during runtime pause never enters migration and releases destination`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-migration-pause-cancel-")
        val source = Files.createDirectory(parent.resolve("source"))
        val destination = parent.resolve("destination")
        val resolution = DesktopDataRootResolution.Resolved(
            appDataRoot = source,
            provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
            bootstrapPath = parent.resolve(DesktopDataDirectory.BOOTSTRAP_FILE_NAME),
        )
        val coordinator = DesktopDataOperationCoordinator()
        val store = DesktopSettingsStore(source, coordinator)
        val missing = assertIs<DesktopSettingsLoadResult.Missing>(store.load())
        store.save(
            missing.document,
            DesktopSettings(
                automaticBackup = DesktopAutomaticBackupSettings(
                    enabled = true,
                    minimumBackupInterval = Duration.ofHours(6),
                    maximumSnapshotCount = 4,
                    checkInterval = Duration.ofHours(1),
                ),
            ),
        )
        val backupEntered = CompletableDeferred<Unit>()
        val releaseBackup = CompletableDeferred<Unit>()
        lateinit var scheduler: DesktopAutomaticBackupScheduler
        val runtime = DesktopAutomaticBackupRuntime(store, coordinator) { sink ->
            DesktopAutomaticBackupScheduler(
                executeAutomaticBackup = { _, _ ->
                    backupEntered.complete(Unit)
                    releaseBackup.await()
                    AutomaticBackupExecutionResult.SkippedNotDue
                },
                dispatcher = Dispatchers.Default,
                eventSink = sink,
            ).also { scheduler = it }
        }
        val preparer = DesktopMigrationDestinationPreparer(
            applicationHomeResolver = {
                DesktopApplicationHomeResult.Unavailable(
                    DesktopApplicationHomeUnavailableReason.PROPERTY_ABSENT,
                )
            },
            ownershipAcquire = DesktopDataRootOwnership::acquire,
        )
        var preflightCalls = 0
        var snapshotCalls = 0
        var materializationCalls = 0
        var authorityCalls = 0
        val service = DesktopDataRootMigrationService(
            sourceResolution = resolution,
            runtime = runtime,
            coordinator = coordinator,
            dependencies = DesktopDataRootMigrationDependencies(
                prepareDestination = preparer::prepare,
                preflight = { _, _ ->
                    preflightCalls++
                    DesktopMigrationMaterializationPreflightResult.Ready
                },
                createSafetySnapshot = {
                    snapshotCalls++
                    error("snapshot must not run")
                },
                materialize = { _, _ ->
                    materializationCalls++
                    error("materialization must not run")
                },
                commitAuthority = { _, _ ->
                    authorityCalls++
                    error("authority must not run")
                },
            ),
        )
        try {
            runtime.initialize()
            backupEntered.await()
            val migration = async { service.migrate(destination) }
            while (runtime.state.value.mode != DesktopAutomaticBackupRuntimeMode.MAINTENANCE_PAUSED) {
                yield()
            }
            migration.cancel()
            yield()
            releaseBackup.complete(Unit)
            assertFailsWith<CancellationException> { migration.await() }

            assertEquals(0, preflightCalls)
            assertEquals(0, snapshotCalls)
            assertEquals(0, materializationCalls)
            assertEquals(0, authorityCalls)
            assertEquals(DesktopAutomaticBackupRuntimeMode.ACTIVE, runtime.state.value.mode)
            assertTrue(scheduler.isRunning)
            assertEquals(DesktopDataOperationCoordinatorState.OPEN, coordinator.state)
            assertIs<DesktopDataRootOwnershipResult.Acquired>(
                DesktopDataRootOwnership.acquire(
                    resolution.copy(appDataRoot = destination),
                ),
            ).ownership.close()
        } finally {
            runCatching { service.close() }
            runCatching { runtime.close() }
            runCatching { coordinator.closeAndDrain() }
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cancellation around authority commit cannot skip restart seal`() = withFixture {
        val destination = parent.resolve("destination")
        val prepared = prepare(destination)
        val commitEntered = CompletableDeferred<Unit>()
        val allowCommit = CompletableDeferred<Unit>()
        val service = service(
            dependencies(
                prepare = { prepared },
                materialize = { materialized(destination) },
                authority = {
                    commitEntered.complete(Unit)
                    allowCommit.await()
                    committed(destination)
                },
            ),
        )

        coroutineScope {
            val migration = async { service.migrate(destination) }
            commitEntered.await()
            migration.cancel()
            allowCommit.complete(Unit)
            assertFailsWith<CancellationException> { migration.await() }
        }

        assertEquals(DesktopDataOperationCoordinatorState.RESTART_REQUIRED, coordinator.state)
        assertEquals(DesktopAutomaticBackupRuntimeMode.RESTART_REQUIRED, runtime.state.value.mode)
        assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(acquire(destination))
        service.close()
        assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(destination)).ownership.close()
    }

    @Test
    fun `destination close failure remains structured on authority failure`() = withFixture {
        val destination = parent.resolve("destination")
        val prepared = prepare(destination)
        val closeFailure = IllegalStateException("close fixture")
        val service = service(
            dependencies(
                prepare = { prepared },
                materialize = { materialized(destination) },
                authority = { DesktopBootstrapAuthorityCommitResult.AuthorityChanged("changed") },
                close = {
                    it.close()
                    throw closeFailure
                },
            ),
        )

        val result = assertIs<DesktopDataRootMigrationResult.Failure>(service.migrate(destination))

        assertEquals(DesktopDataRootMigrationFailureKind.AUTHORITY_PRE_COMMIT, result.kind)
        assertSame(closeFailure, result.destinationCloseFailure)
        assertEquals(DesktopAutomaticBackupRuntimeMode.ACTIVE, runtime.state.value.mode)
        assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(destination)).ownership.close()
    }

    @Test
    fun `runtime resume failure is surfaced after a proven precommit path`() = withFixture {
        val destination = parent.resolve("destination")
        val prepared = prepare(destination)
        val service = service(
            dependencies(
                prepare = { prepared },
                preflight = {
                    runtime.close()
                    DesktopMigrationMaterializationPreflightResult.Failure(
                        DesktopMigrationMaterializationResult.Failure(
                            DesktopMigrationMaterializationFailureKind.PRECONDITION_FAILED,
                            "fixture",
                        ),
                    )
                },
            ),
        )

        val result = assertIs<DesktopDataRootMigrationResult.Failure>(service.migrate(destination))

        assertEquals(DesktopDataRootMigrationFailureKind.RUNTIME_RESUME, result.kind)
        assertIs<DesktopMaintenanceResumeResult.Failed>(result.runtimeDisposition)
        assertEquals(DesktopAutomaticBackupRuntimeMode.CLOSED, runtime.state.value.mode)
        assertEquals(DesktopDataOperationCoordinatorState.OPEN, coordinator.state)
        assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(destination)).ownership.close()
    }

    @Test
    fun `precommit authority failure restarts a previously running scheduler`() = withFixture(
        enabledAutomaticBackup = true,
    ) {
        assertTrue(runtime.state.value.schedulerRunning)
        val destination = parent.resolve("destination")
        val prepared = prepare(destination)
        val service = service(
            dependencies(
                prepare = { prepared },
                materialize = { materialized(destination) },
                authority = { DesktopBootstrapAuthorityCommitResult.AuthorityChanged("changed") },
            ),
        )

        val result = assertIs<DesktopDataRootMigrationResult.Failure>(service.migrate(destination))

        assertEquals(DesktopDataRootMigrationFailureKind.AUTHORITY_PRE_COMMIT, result.kind)
        assertEquals(DesktopAutomaticBackupRuntimeMode.ACTIVE, runtime.state.value.mode)
        assertTrue(runtime.state.value.schedulerRunning)
        assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(destination)).ownership.close()
    }

    @Test
    fun `service close failure releases retained destination and is idempotently closed`() = withFixture {
        val destination = parent.resolve("destination")
        val prepared = prepare(destination)
        val closeFailure = IllegalStateException("service close fixture")
        var closeCalls = 0
        val service = service(
            dependencies(
                prepare = { prepared },
                materialize = { materialized(destination) },
                authority = { committed(destination) },
                close = {
                    closeCalls++
                    it.close()
                    throw closeFailure
                },
            ),
        )
        assertIs<DesktopDataRootMigrationResult.Success>(service.migrate(destination))

        assertSame(closeFailure, assertFailsWith<IllegalStateException> { service.close() })
        service.close()
        assertEquals(1, closeCalls)
        assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(destination)).ownership.close()
    }

    @Test
    fun `concurrent migration calls serialize destination preparation`() = withFixture {
        val firstDestination = parent.resolve("destination-first")
        val secondDestination = parent.resolve("destination-second")
        val firstMaterializationEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var prepareCalls = 0
        var materializeCalls = 0
        val service = service(
            DesktopDataRootMigrationDependencies(
                prepareDestination = { sourceResolution, destination ->
                    prepareCalls++
                    preparer.prepare(sourceResolution, destination)
                },
                preflight = { _, _ -> DesktopMigrationMaterializationPreflightResult.Ready },
                createSafetySnapshot = { snapshot("safety-$prepareCalls") },
                materialize = { _, prepared ->
                    materializeCalls++
                    if (materializeCalls == 1) {
                        firstMaterializationEntered.complete(Unit)
                        releaseFirst.await()
                    }
                    materialized(prepared.destinationRoot)
                },
                commitAuthority = { _, _ -> DesktopBootstrapAuthorityCommitResult.AuthorityChanged("fixture") },
            ),
        )

        coroutineScope {
            val first = async { service.migrate(firstDestination) }
            firstMaterializationEntered.await()
            val secondStarted = CompletableDeferred<Unit>()
            val second = async {
                secondStarted.complete(Unit)
                service.migrate(secondDestination)
            }
            secondStarted.await()
            kotlinx.coroutines.yield()
            assertEquals(1, prepareCalls)
            releaseFirst.complete(Unit)
            assertIs<DesktopDataRootMigrationResult.Failure>(first.await())
            assertIs<DesktopDataRootMigrationResult.Failure>(second.await())
        }
        assertEquals(2, prepareCalls)
        assertEquals(DesktopAutomaticBackupRuntimeMode.ACTIVE, runtime.state.value.mode)
        assertEquals(DesktopDataOperationCoordinatorState.OPEN, coordinator.state)
    }

    private fun Fixture.assertResumedAndReleased(destination: Path) {
        assertEquals(DesktopDataOperationCoordinatorState.OPEN, coordinator.state)
        assertEquals(DesktopAutomaticBackupRuntimeMode.ACTIVE, runtime.state.value.mode)
        assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(destination)).ownership.close()
    }

    private fun Fixture.service(
        dependencies: DesktopDataRootMigrationDependencies,
    ) = DesktopDataRootMigrationService(
        sourceResolution = resolution,
        runtime = runtime,
        coordinator = coordinator,
        dependencies = dependencies,
    )

    private fun Fixture.dependencies(
        prepare: (() -> DesktopPreparedMigrationDestination)? = null,
        prepareFailure: DesktopMigrationDestinationResult.Failure? = null,
        preflight: suspend () -> DesktopMigrationMaterializationPreflightResult = {
            DesktopMigrationMaterializationPreflightResult.Ready
        },
        snapshot: (SnapshotPurpose) -> AppDataSnapshot = { snapshot("safety") },
        materialize: suspend () -> DesktopMigrationMaterializationResult = {
            materialized(parent.resolve("destination"))
        },
        authority: suspend () -> DesktopBootstrapAuthorityCommitResult = {
            DesktopBootstrapAuthorityCommitResult.AuthorityChanged("fixture")
        },
        close: (DesktopPreparedMigrationDestination) -> Unit = DesktopPreparedMigrationDestination::close,
    ) = DesktopDataRootMigrationDependencies(
        prepareDestination = { _, _ ->
            prepareFailure ?: DesktopMigrationDestinationResult.Prepared(checkNotNull(prepare).invoke())
        },
        preflight = { _, _ -> preflight() },
        createSafetySnapshot = snapshot,
        materialize = { _, _ -> materialize() },
        commitAuthority = { _, _ -> authority() },
        closeDestination = close,
    )

    private fun Fixture.prepare(destination: Path): DesktopPreparedMigrationDestination =
        assertIs<DesktopMigrationDestinationResult.Prepared>(preparer.prepare(resolution, destination)).destination

    private fun Fixture.snapshot(name: String) = AppDataSnapshot(
        name = name,
        directory = source.resolve("backups").resolve(name),
        createdAt = Instant.parse("2026-09-24T00:00:00Z"),
        purpose = SnapshotPurpose.MANUAL,
        validation = SnapshotValidation.VALID,
    )

    private fun materialized(
        destination: Path,
        snapshots: List<String> = emptyList(),
    ) = DesktopMigrationMaterializationResult.Materialized(
        destinationRoot = destination,
        summary = DesktopMigrationMaterializationSummary(1, 0, 7, snapshots),
        warnings = emptyList(),
    )

    private fun committed(destination: Path) = DesktopBootstrapAuthorityCommitResult.Committed(
        destination = destination,
        document = DesktopBootstrapDocument(
            DesktopDataRootSelection.custom(destination),
            kotlinx.serialization.json.buildJsonObject {},
        ),
    )

    private fun Fixture.acquire(root: Path): DesktopDataRootOwnershipResult =
        DesktopDataRootOwnership.acquire(
            DesktopDataRootResolution.Resolved(
                appDataRoot = root,
                provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
                bootstrapPath = resolution.bootstrapPath,
            ),
        )

    private fun withFixture(
        initializeRuntime: Boolean = true,
        writeBootstrap: Boolean = false,
        holdSourceOwnership: Boolean = false,
        enabledAutomaticBackup: Boolean = false,
        block: suspend Fixture.() -> Unit,
    ) = runBlocking {
        withFixtureSuspend(
            initializeRuntime,
            writeBootstrap,
            holdSourceOwnership,
            enabledAutomaticBackup,
            block,
        )
    }

    private suspend fun withFixtureSuspend(
        initializeRuntime: Boolean = true,
        writeBootstrap: Boolean = false,
        holdSourceOwnership: Boolean = false,
        enabledAutomaticBackup: Boolean = false,
        block: suspend Fixture.() -> Unit,
    ) {
        val parent = Files.createTempDirectory("desktop-migration-service-")
        val source = Files.createDirectory(parent.resolve("source"))
        val resolution = DesktopDataRootResolution.Resolved(
            appDataRoot = source,
            provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
            bootstrapPath = parent.resolve(DesktopDataDirectory.BOOTSTRAP_FILE_NAME),
        )
        val coordinator = DesktopDataOperationCoordinator()
        val snapshotService = AppDataSnapshotService(source)
        val runtime = DesktopAutomaticBackupRuntime(
            settingsStore = DesktopSettingsStore(source, coordinator),
            snapshotService = DesktopCoordinatedSnapshotService(snapshotService, coordinator),
            coordinator = coordinator,
        )
        if (enabledAutomaticBackup) {
            val store = DesktopSettingsStore(source, coordinator)
            val missing = assertIs<DesktopSettingsLoadResult.Missing>(store.load())
            store.save(
                missing.document,
                DesktopSettings(
                    automaticBackup = DesktopAutomaticBackupSettings(
                        enabled = true,
                        minimumBackupInterval = Duration.ofHours(6),
                        maximumSnapshotCount = 4,
                        checkInterval = Duration.ofHours(1),
                    ),
                ),
            )
        }
        if (writeBootstrap) {
            val store = DesktopBootstrapSettingsStore(resolution.bootstrapPath)
            val missing = assertIs<DesktopBootstrapLoadResult.Missing>(store.load())
            store.save(missing.document, DesktopDataRootSelection.custom(source))
        }
        val sourceOwnership = if (holdSourceOwnership) {
            assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolution)).ownership
        } else {
            null
        }
        if (initializeRuntime) runtime.initialize()
        val fixture = Fixture(
            parent = parent,
            source = source,
            resolution = resolution,
            coordinator = coordinator,
            snapshotService = snapshotService,
            runtime = runtime,
            preparer = DesktopMigrationDestinationPreparer(
                applicationHomeResolver = {
                    DesktopApplicationHomeResult.Unavailable(
                        DesktopApplicationHomeUnavailableReason.PROPERTY_ABSENT,
                    )
                },
                ownershipAcquire = DesktopDataRootOwnership::acquire,
            ),
        )
        try {
            fixture.block()
        } finally {
            runCatching { runtime.close() }
            runCatching { coordinator.closeAndDrain() }
            sourceOwnership?.let { runCatching { it.close() } }
            parent.toFile().deleteRecursively()
        }
    }

    private data class Fixture(
        val parent: Path,
        val source: Path,
        val resolution: DesktopDataRootResolution.Resolved,
        val coordinator: DesktopDataOperationCoordinator,
        val snapshotService: AppDataSnapshotService,
        val runtime: DesktopAutomaticBackupRuntime,
        val preparer: DesktopMigrationDestinationPreparer,
    )
}
