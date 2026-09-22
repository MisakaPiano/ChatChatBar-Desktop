package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.snapshot.AppDataSnapshotService
import java.nio.file.Path

class DesktopAppContainer(val appDataRoot: Path) {
    val jsonFileStorage = JsonFileStorage(appDataRoot)
    val appDataSnapshotService = AppDataSnapshotService(appDataRoot)
    val desktopSettingsStore = DesktopSettingsStore(appDataRoot)
    val automaticBackupRuntime = DesktopAutomaticBackupRuntime(
        settingsStore = desktopSettingsStore,
        snapshotService = appDataSnapshotService,
    )
    val automaticBackupScheduler: DesktopAutomaticBackupScheduler
        get() = automaticBackupRuntime.scheduler
}
