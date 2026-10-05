package com.example.chatbar.desktop

import com.example.chatbar.data.snapshot.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.*

class DesktopAutomaticBackupSettingsPresentationTest {
    private val draft = DesktopBackupSettingsDraft.from(DesktopAutomaticBackupSettings())

    @Test fun `short forms and ISO are exact durations`() {
        for ((text, expected) in listOf("30m" to Duration.ofMinutes(30), "1h" to Duration.ofHours(1),
            "6h" to Duration.ofHours(6), "24h" to Duration.ofHours(24), "3d" to Duration.ofDays(3),
            "7d" to Duration.ofDays(7), "PT30M" to Duration.ofMinutes(30), "P3D" to Duration.ofDays(3))) {
            assertEquals(expected, desktopBackupDuration(text))
            assertEquals(expected, draft.copy(minimumInterval = text).settings()?.minimumBackupInterval)
        }
    }

    @Test fun `duration parsing is lossless at precision and range boundaries`() {
        for (duration in listOf(Duration.ZERO, Duration.ofNanos(1), Duration.ofSeconds(11, 987654321),
            Duration.ofSeconds(Long.MAX_VALUE, 999999999))) {
            assertEquals(duration, desktopBackupDuration(duration.toString()))
            assertEquals(duration, draft.copy(minimumInterval = duration.toString()).settings()?.minimumBackupInterval)
        }
    }

    @Test fun `invalid syntax overflow negative and zero validation follow existing contract`() {
        for (text in listOf("", "wat", "99999999999999999999h", "1.1h", "P1Y"))
            assertNull(desktopBackupDuration(text))
        assertNotNull(draft.copy(minimumInterval = "PT0S").settings())
        assertNull(draft.copy(minimumInterval = "-PT0.000000001S").settings())
        for (text in listOf("0m", "-1h", "bad")) assertNull(draft.copy(checkInterval = text).settings())
        assertNotNull(draft.copy(checkInterval = "PT0.000000001S").settings())
    }

    @Test fun `retention keeps full existing positive Int range`() {
        for (count in listOf("1", "7", "101", Int.MAX_VALUE.toString()))
            assertEquals(count.toInt(), draft.copy(maximumCount = count).settings()?.maximumSnapshotCount)
        for (count in listOf("0", "-1", "1.5", "2147483648", ""))
            assertNull(draft.copy(maximumCount = count).settings())
    }

    @Test fun `duration summaries localize whole units without losing fractional values`() {
        val zh = DesktopUiStrings(DesktopUiLanguage.ZH_CN)
        val en = DesktopUiStrings(DesktopUiLanguage.EN)
        assertEquals("3 天", desktopBackupDurationSummary(Duration.ofDays(3), zh))
        assertEquals("6 hours", desktopBackupDurationSummary(Duration.ofHours(6), en))
        assertEquals("PT1.000000001S", desktopBackupDurationSummary(Duration.ofSeconds(1, 1), zh))
    }

    @Test fun `not due is neutral and execution failure is explicit and safe`() {
        val neutral = desktopBackupStatus(DesktopAutomaticBackupRuntimeState(latestEvent = DesktopAutomaticBackupEvent.SkippedNotDue))
        assertEquals(DesktopUiText.BACKUP_NOT_DUE, neutral.event)
        assertFalse(neutral.executionFailed)
        val error = IllegalStateException("fake-private-token")
        val failed = desktopBackupStatus(DesktopAutomaticBackupRuntimeState(latestEvent = DesktopAutomaticBackupEvent.Failed(error), latestExecutionFailure = error))
        assertEquals(DesktopUiText.BACKUP_EXECUTION_FAILED, failed.event)
        assertTrue(failed.executionFailed)
        assertFalse(failed.toString().contains(error.message!!))
    }

    @Test fun `created and cleanup warning remain distinct from execution failure`() {
        val result = AutomaticBackupExecutionResult.Created(
            AppDataSnapshot("inline", Path.of("inline"), Instant.EPOCH, SnapshotPurpose.AUTOMATIC, SnapshotValidation.VALID),
            AutomaticSnapshotPruneResult(prunedSnapshotNames = emptyList(), cleanupWarnings = listOf("fake-private-path")))
        val created = desktopBackupStatus(DesktopAutomaticBackupRuntimeState(latestEvent = DesktopAutomaticBackupEvent.Created(result)))
        assertEquals(DesktopUiText.BACKUP_CREATED, created.event)
        assertFalse(created.executionFailed)
        val warning = desktopBackupStatus(DesktopAutomaticBackupRuntimeState(latestEvent = DesktopAutomaticBackupEvent.Created(result),
            latestCleanupWarnings = result.pruneResult.cleanupWarnings))
        assertEquals(1, warning.cleanupWarningCount)
        assertFalse(warning.executionFailed)
        assertEquals(DesktopUiText.BACKUP_CREATED, warning.event)
        assertFalse(warning.toString().contains("fake-private"))
    }

    @Test fun `non ACTIVE modes and load failures never offer Apply even with a valid draft`() {
        val document = DesktopSettingsDocument(DesktopSettings(), kotlinx.serialization.json.JsonObject(emptyMap()))
        val active = DesktopAutomaticBackupRuntimeState(mode = DesktopAutomaticBackupRuntimeMode.ACTIVE,
            settingsLoadResult = DesktopSettingsLoadResult.Missing(document), effectiveSettings = document.settings)
        val valid = DesktopBackupSettingsUiState(active, draft.copy(maximumCount = "8"), draft)
        assertTrue(valid.canApply)
        for (mode in DesktopAutomaticBackupRuntimeMode.entries.filter { it != DesktopAutomaticBackupRuntimeMode.ACTIVE })
            assertFalse(valid.copy(runtime = active.copy(mode = mode)).canApply)
        for (failure in listOf(DesktopSettingsLoadResult.Corrupt("secret"), DesktopSettingsLoadResult.Invalid("secret"),
            DesktopSettingsLoadResult.UnsupportedFormatVersion(9))) {
            assertFalse(valid.copy(runtime = active.copy(settingsLoadResult = failure)).canApply)
            assertNotNull(desktopBackupStatus(active.copy(settingsLoadResult = failure)).loadFailure)
        }
    }

    @Test fun `UI wiring exposes backup section Advanced and explicit Apply without runtime bypass`() {
        fun source(name: String) = Files.readString(Path.of("src/main/kotlin/com/example/chatbar/desktop", name))
        val manage = source("DesktopManagePanel.kt").substringAfter("ManageSection.SETTINGS -> {")
        assertTrue(manage.contains("DesktopAutomaticBackupSettingsPanel(backupSettingsController)"))
        val panel = source("DesktopAutomaticBackupSettingsPanel.kt")
        val controller = source("DesktopAutomaticBackupSettingsController.kt")
        assertTrue(panel.contains("if (advanced)"))
        assertTrue(panel.contains("BACKUP_CHECK_HINT"))
        assertTrue(panel.contains("enabled = state.canApply"))
        assertTrue(panel.contains("controller::requestApply"))
        assertTrue(controller.contains("runtime.applySettings(settings)"))
        assertTrue(controller.contains("runtime.state.collect"))
        for (text in listOf(panel, controller)) {
            for (forbidden in listOf("scheduler.start(", "scheduler.stop(", "DesktopSettingsStore(",
                "executeAutomaticBackup(", "createSnapshot(", "prune(", "Backup Now", "立即备份"))
                assertFalse(text.contains(forbidden), forbidden)
        }
        val main = source("Main.kt")
        assertTrue(main.contains("LaunchedEffect(backupSettingsController) { backupSettingsController.observe() }"))
        assertTrue(main.contains("DesktopAutomaticBackupSettingsController(appContainer.automaticBackupRuntime, applicationScope)"))
    }
}
