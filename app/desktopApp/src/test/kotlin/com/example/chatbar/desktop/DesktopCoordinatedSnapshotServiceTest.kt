package com.example.chatbar.desktop

import com.example.chatbar.data.snapshot.AppDataSnapshot
import com.example.chatbar.data.snapshot.AppDataSnapshotService
import com.example.chatbar.data.snapshot.AutomaticBackupExecutionResult
import com.example.chatbar.data.snapshot.AutomaticSnapshotPruneResult
import com.example.chatbar.data.snapshot.SnapshotPurpose
import com.example.chatbar.data.snapshot.SnapshotRestoreException
import com.example.chatbar.data.snapshot.SnapshotRestoreResult
import com.example.chatbar.data.snapshot.SnapshotValidation
import java.nio.file.Path
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class DesktopCoordinatedSnapshotServiceTest {
    @Test
    fun `raw restore internal safety snapshot stays inside one Desktop exclusive scope`() = runTest {
        val root = Files.createTempDirectory("coordinated-restore-")
        try {
            Files.writeString(root.resolve("data.txt"), "A")
            val raw = AppDataSnapshotService(root)
            val selected = raw.createSnapshot()
            Files.writeString(root.resolve("data.txt"), "B")
            val coordinator = DesktopDataOperationCoordinator()
            val service = DesktopCoordinatedSnapshotService(
                raw,
                coordinator,
                StandardTestDispatcher(testScheduler),
            )

            service.restoreSnapshot(selected.directory)

            assertEquals("A", Files.readString(root.resolve("data.txt")))
            assertTrue(coordinator.isRestartRequired)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mutation and read APIs use their required admission modes`() = runTest {
        val coordinator = DesktopDataOperationCoordinator()
        val primitive = RecordingSnapshotPrimitive(coordinator)
        val service = DesktopCoordinatedSnapshotService(
            primitive,
            coordinator,
            StandardTestDispatcher(testScheduler),
        )

        service.createSnapshot()
        service.pruneAutomaticSnapshots(1)
        service.executeAutomaticBackup(Duration.ZERO, 1)
        service.listSnapshots()
        service.validateSnapshot(Path.of("snapshot"))

        assertEquals(
            listOf(
                DesktopDataOperationCoordinatorState.EXCLUSIVE,
                DesktopDataOperationCoordinatorState.EXCLUSIVE,
                DesktopDataOperationCoordinatorState.EXCLUSIVE,
                DesktopDataOperationCoordinatorState.OPEN,
                DesktopDataOperationCoordinatorState.OPEN,
            ),
            primitive.observedStates,
        )
        assertEquals(DesktopDataOperationCoordinatorState.OPEN, coordinator.state)
    }

    @Test
    fun `successful restore including cleanup warning seals restart`() = runTest {
        val coordinator = DesktopDataOperationCoordinator()
        val primitive = RecordingSnapshotPrimitive(coordinator).apply {
            restoreResult = restoreResult(cleanupWarning = "retained workspace")
        }
        val service = DesktopCoordinatedSnapshotService(
            primitive,
            coordinator,
            StandardTestDispatcher(testScheduler),
        )

        val result = service.restoreSnapshot(Path.of("snapshot"))

        assertEquals("retained workspace", result.cleanupWarning)
        assertTrue(coordinator.isRestartRequired)
        val rejected = assertFailsWith<DesktopDataOperationRejectedException> {
            service.listSnapshots()
        }
        assertEquals(DesktopDataOperationRejectionReason.RESTART_REQUIRED, rejected.reason)
    }

    @Test
    fun `incomplete rollback failure seals restart and rethrows`() = runTest {
        val coordinator = DesktopDataOperationCoordinator()
        val failure = SnapshotRestoreException(
            message = "rollback incomplete",
            recoveryDirectory = Path.of("recovery"),
        )
        val primitive = RecordingSnapshotPrimitive(coordinator).apply { restoreFailure = failure }
        val service = DesktopCoordinatedSnapshotService(
            primitive,
            coordinator,
            StandardTestDispatcher(testScheduler),
        )

        val thrown = assertFailsWith<SnapshotRestoreException> {
            service.restoreSnapshot(Path.of("snapshot"))
        }
        assertEquals(failure, thrown)
        assertTrue(coordinator.isRestartRequired)
    }

    @Test
    fun `pre mutation and rollback complete restore failures do not seal`() = runTest {
        listOf(
            SnapshotRestoreException("pre-mutation"),
            SnapshotRestoreException("rollback complete", recoveryDirectory = null),
        ).forEach { failure ->
            val coordinator = DesktopDataOperationCoordinator()
            val primitive = RecordingSnapshotPrimitive(coordinator).apply { restoreFailure = failure }
            val service = DesktopCoordinatedSnapshotService(
                primitive,
                coordinator,
                StandardTestDispatcher(testScheduler),
            )

            assertFailsWith<SnapshotRestoreException> {
                service.restoreSnapshot(Path.of("snapshot"))
            }
            assertFalse(coordinator.isRestartRequired)
            assertEquals(DesktopDataOperationCoordinatorState.OPEN, coordinator.state)
        }
    }

    @Test
    fun `delegate failure releases exclusive maintenance`() = runTest {
        val coordinator = DesktopDataOperationCoordinator()
        val primitive = RecordingSnapshotPrimitive(coordinator).apply {
            createFailure = IllegalStateException("fixture")
        }
        val service = DesktopCoordinatedSnapshotService(
            primitive,
            coordinator,
            StandardTestDispatcher(testScheduler),
        )

        assertFailsWith<IllegalStateException> { service.createSnapshot() }
        assertEquals(DesktopDataOperationCoordinatorState.OPEN, coordinator.state)
        assertIs<SnapshotValidation>(service.validateSnapshot(Path.of("snapshot")))
    }

    @Test
    fun `cancellation after synchronous restore return cannot undo restart seal`() = runTest {
        val coordinator = DesktopDataOperationCoordinator()
        lateinit var restoreJob: Job
        val primitive = RecordingSnapshotPrimitive(coordinator).apply {
            restoreAction = {
                restoreJob.cancel()
                restoreResult()
            }
        }
        val service = DesktopCoordinatedSnapshotService(
            primitive,
            coordinator,
            StandardTestDispatcher(testScheduler),
        )

        restoreJob = launch { service.restoreSnapshot(Path.of("snapshot")) }
        restoreJob.join()

        assertTrue(restoreJob.isCancelled)
        assertTrue(coordinator.isRestartRequired)
    }

    private class RecordingSnapshotPrimitive(
        private val coordinator: DesktopDataOperationCoordinator,
    ) : DesktopSnapshotPrimitive {
        val observedStates = mutableListOf<DesktopDataOperationCoordinatorState>()
        var createFailure: RuntimeException? = null
        var restoreFailure: SnapshotRestoreException? = null
        var restoreResult: SnapshotRestoreResult = restoreResult()
        var restoreAction: (() -> SnapshotRestoreResult)? = null

        override fun createSnapshot(purpose: SnapshotPurpose): AppDataSnapshot {
            observedStates += coordinator.state
            createFailure?.let { throw it }
            return snapshot("created", purpose)
        }

        override fun restoreSnapshot(snapshotDirectory: Path): SnapshotRestoreResult {
            observedStates += coordinator.state
            restoreFailure?.let { throw it }
            restoreAction?.let { return it() }
            return restoreResult
        }

        override fun pruneAutomaticSnapshots(maximumCount: Int): AutomaticSnapshotPruneResult {
            observedStates += coordinator.state
            return AutomaticSnapshotPruneResult(emptyList())
        }

        override fun executeAutomaticBackup(
            minimumInterval: Duration,
            maximumCount: Int,
        ): AutomaticBackupExecutionResult {
            observedStates += coordinator.state
            return AutomaticBackupExecutionResult.SkippedNotDue
        }

        override fun listSnapshots(): List<AppDataSnapshot> {
            observedStates += coordinator.state
            return emptyList()
        }

        override fun validateSnapshot(snapshotDirectory: Path): SnapshotValidation {
            observedStates += coordinator.state
            return SnapshotValidation.VALID
        }
    }

    companion object {
        private fun snapshot(name: String, purpose: SnapshotPurpose) = AppDataSnapshot(
            name = name,
            directory = Path.of(name),
            createdAt = Instant.EPOCH,
            purpose = purpose,
            validation = SnapshotValidation.VALID,
        )

        private fun restoreResult(cleanupWarning: String? = null) = SnapshotRestoreResult(
            restoredSnapshot = snapshot("restored", SnapshotPurpose.MANUAL),
            preRestoreSnapshot = snapshot("safety", SnapshotPurpose.PRE_RESTORE),
            cleanupWarning = cleanupWarning,
        )
    }
}
