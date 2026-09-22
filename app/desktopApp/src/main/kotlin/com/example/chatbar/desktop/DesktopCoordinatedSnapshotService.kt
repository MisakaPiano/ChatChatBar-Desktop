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
import java.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop snapshot coordination boundary。
 *
 * Shared primitive 保持 synchronous、platform-neutral；Desktop caller 必须先在这里取得
 * process-local admission，再由 [ioDispatcher] 完整执行 filesystem transaction。Restore 成功或
 * rollback incomplete 会在 facade 内直接 seal 为 restart-required，不能依赖上层调用者记得补做。
 */
internal class DesktopCoordinatedSnapshotService internal constructor(
    private val delegate: DesktopSnapshotPrimitive,
    private val coordinator: DesktopDataOperationCoordinator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        delegate: AppDataSnapshotService,
        coordinator: DesktopDataOperationCoordinator,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(SharedSnapshotPrimitive(delegate), coordinator, ioDispatcher)

    suspend fun createSnapshot(
        purpose: SnapshotPurpose = SnapshotPurpose.MANUAL,
    ): AppDataSnapshot = coordinator.withExclusiveMaintenance {
        withContext(ioDispatcher) { delegate.createSnapshot(purpose) }
    }

    suspend fun restoreSnapshot(snapshotDirectory: Path): SnapshotRestoreResult =
        coordinator.withExclusiveMaintenance {
            val maintenanceScope = this
            withContext(ioDispatcher) {
                try {
                    delegate.restoreSnapshot(snapshotDirectory).also {
                        maintenanceScope.requireRestart()
                    }
                } catch (error: SnapshotRestoreException) {
                    if (error.recoveryDirectory != null) {
                        maintenanceScope.requireRestart()
                    }
                    throw error
                }
            }
        }

    suspend fun pruneAutomaticSnapshots(maximumCount: Int): AutomaticSnapshotPruneResult =
        coordinator.withExclusiveMaintenance {
            withContext(ioDispatcher) { delegate.pruneAutomaticSnapshots(maximumCount) }
        }

    suspend fun executeAutomaticBackup(
        minimumInterval: Duration,
        maximumCount: Int,
    ): AutomaticBackupExecutionResult = coordinator.withExclusiveMaintenance {
        withContext(ioDispatcher) {
            delegate.executeAutomaticBackup(minimumInterval, maximumCount)
        }
    }

    suspend fun listSnapshots(): List<AppDataSnapshot> = coordinator.withNormalOperation {
        withContext(ioDispatcher) { delegate.listSnapshots() }
    }

    suspend fun validateSnapshot(snapshotDirectory: Path): SnapshotValidation =
        coordinator.withNormalOperation {
            withContext(ioDispatcher) { delegate.validateSnapshot(snapshotDirectory) }
        }
}

internal interface DesktopSnapshotPrimitive {
    fun createSnapshot(purpose: SnapshotPurpose): AppDataSnapshot
    fun restoreSnapshot(snapshotDirectory: Path): SnapshotRestoreResult
    fun pruneAutomaticSnapshots(maximumCount: Int): AutomaticSnapshotPruneResult
    fun executeAutomaticBackup(minimumInterval: Duration, maximumCount: Int): AutomaticBackupExecutionResult
    fun listSnapshots(): List<AppDataSnapshot>
    fun validateSnapshot(snapshotDirectory: Path): SnapshotValidation
}

private class SharedSnapshotPrimitive(
    private val delegate: AppDataSnapshotService,
) : DesktopSnapshotPrimitive {
    override fun createSnapshot(purpose: SnapshotPurpose) = delegate.createSnapshot(purpose)
    override fun restoreSnapshot(snapshotDirectory: Path) = delegate.restoreSnapshot(snapshotDirectory)
    override fun pruneAutomaticSnapshots(maximumCount: Int) =
        delegate.pruneAutomaticSnapshots(maximumCount)

    override fun executeAutomaticBackup(minimumInterval: Duration, maximumCount: Int) =
        delegate.executeAutomaticBackup(minimumInterval, maximumCount)

    override fun listSnapshots() = delegate.listSnapshots()
    override fun validateSnapshot(snapshotDirectory: Path) = delegate.validateSnapshot(snapshotDirectory)
}
