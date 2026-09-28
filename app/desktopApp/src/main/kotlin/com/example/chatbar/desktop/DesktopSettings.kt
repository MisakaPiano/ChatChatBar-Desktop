package com.example.chatbar.desktop

import java.time.Duration

const val CURRENT_DESKTOP_SETTINGS_FORMAT_VERSION = 1

enum class DesktopUiLanguage { ZH_CN, EN }

enum class DesktopColorStyle { NEUTRAL, CCB_NATIVE, CUSTOM_ACCENT }

data class DesktopSettings(
    val formatVersion: Int = CURRENT_DESKTOP_SETTINGS_FORMAT_VERSION,
    val automaticBackup: DesktopAutomaticBackupSettings = DesktopAutomaticBackupSettings(),
    val uiLanguage: DesktopUiLanguage = DesktopUiLanguage.ZH_CN,
    val colorStyle: DesktopColorStyle = DesktopColorStyle.NEUTRAL,
) {
    init {
        require(formatVersion == CURRENT_DESKTOP_SETTINGS_FORMAT_VERSION) {
            "Unsupported Desktop settings format version: $formatVersion"
        }
    }
}

data class DesktopAutomaticBackupSettings(
    val enabled: Boolean = false,
    val minimumBackupInterval: Duration = Duration.ofHours(24),
    val maximumSnapshotCount: Int = 7,
    val checkInterval: Duration = Duration.ofHours(1),
) {
    init {
        require(!minimumBackupInterval.isNegative) {
            "Minimum backup interval must not be negative"
        }
        require(maximumSnapshotCount >= 1) {
            "Maximum snapshot count must be at least one"
        }
        require(!checkInterval.isNegative && !checkInterval.isZero) {
            "Check interval must be positive"
        }
    }

    fun schedule(): DesktopAutomaticBackupSchedule = DesktopAutomaticBackupSchedule(
        minimumBackupInterval = minimumBackupInterval,
        maximumSnapshotCount = maximumSnapshotCount,
        checkInterval = checkInterval,
    )
}
