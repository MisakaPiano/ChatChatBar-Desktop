package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.snapshot.AppDataSnapshotService
import java.nio.file.Path

class DesktopAppContainer(val appDataRoot: Path) {
    internal val dataOperationCoordinator = DesktopDataOperationCoordinator()
    val jsonFileStorage = JsonFileStorage(appDataRoot, dataOperationCoordinator)
    private val appDataSnapshotService = AppDataSnapshotService(appDataRoot)
    internal val coordinatedSnapshotService = DesktopCoordinatedSnapshotService(
        delegate = appDataSnapshotService,
        coordinator = dataOperationCoordinator,
    )
    val desktopSettingsStore = DesktopSettingsStore(appDataRoot, dataOperationCoordinator)
    val automaticBackupRuntime = DesktopAutomaticBackupRuntime(
        settingsStore = desktopSettingsStore,
        snapshotService = coordinatedSnapshotService,
        coordinator = dataOperationCoordinator,
    )
    val automaticBackupScheduler: DesktopAutomaticBackupScheduler
        get() = automaticBackupRuntime.scheduler

    suspend fun close() {
        closeDesktopDataRuntimes(
            runtimeClose = { automaticBackupRuntime.close() },
            coordinatorClose = { dataOperationCoordinator.closeAndDrain() },
        )
    }
}

internal suspend fun closeDesktopDataRuntimes(
    runtimeClose: suspend () -> Unit,
    coordinatorClose: suspend () -> Unit,
) {
    var primaryFailure: Throwable? = null
    try {
        runtimeClose()
    } catch (error: Throwable) {
        primaryFailure = error
    }
    try {
        coordinatorClose()
    } catch (error: Throwable) {
        if (primaryFailure == null) {
            primaryFailure = error
        } else {
            primaryFailure.addSuppressed(error)
        }
    }
    primaryFailure?.let { throw it }
}
