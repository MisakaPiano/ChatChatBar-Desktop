package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.AppBackupSummary
import java.nio.file.Path
import kotlin.test.*

class DesktopRecoveryTest {
    private val path = Path.of("synthetic-private-root").toAbsolutePath()
    private val provenance = DesktopDataRootProvenance.CLI_OVERRIDE
    private val idle = DesktopDataRootSwitchState.Idle(path, provenance, true)
    private fun phases(root: DesktopDataRootSwitchState = idle, backup: DesktopFullBackupState = DesktopFullBackupState(),
        merge: DesktopMergeImportState? = null) = desktopRecoveryStatuses(root, backup, merge).associate { it.area to it.phase }

    @Test fun `root guidance preserves actual confirmation migration retry and restart distinctions without inspecting failures`() {
        val failure = DesktopDataRootMigrationResult.Failure(DesktopDataRootMigrationFailureKind.entries.first(), path,
            cause = IllegalStateException("synthetic-private-error"))
        val cases = listOf(
            idle to DesktopRecoveryPhase.IDLE,
            idle.copy(supported = false, unsupportedReason = "synthetic-private-error") to DesktopRecoveryPhase.UNSUPPORTED,
            DesktopDataRootSwitchState.CandidateSelected(path, provenance, path.resolve("target")) to DesktopRecoveryPhase.CONFIRMATION,
            DesktopDataRootSwitchState.Migrating(path, provenance, path.resolve("target")) to DesktopRecoveryPhase.RUNNING,
            DesktopDataRootSwitchState.RetryableFailure(path, provenance, path.resolve("target"), failure) to DesktopRecoveryPhase.RETRYABLE,
            DesktopDataRootSwitchState.RestartRequired(path, provenance, null, null, null,
                unexpectedFailure = IllegalStateException("synthetic-private-error")) to DesktopRecoveryPhase.RESTART,
        )
        for ((root, expected) in cases) assertEquals(expected, phases(root)[DesktopRecoveryArea.DIRECTORY])
    }

    @Test fun `backup check is not import and prepared restart outranks stale success or raw error`() {
        val checked = DesktopFullBackupState(summary = AppBackupSummary(appVersionCode = 1, appVersionName = "synthetic-private-version",
            createdAt = 1, sourceFilesRoot = "synthetic-private-root", counts = emptyMap(), fileCount = 1, totalBytes = 1, preferencesSha256 = "0".repeat(64)))
        assertEquals(DesktopRecoveryPhase.IDLE, phases()[DesktopRecoveryArea.BACKUP])
        assertEquals(DesktopRecoveryPhase.CHECKED, phases(backup = checked)[DesktopRecoveryArea.BACKUP])
        assertEquals(DesktopRecoveryPhase.ATTENTION, phases(backup = checked.copy(error = "synthetic-private-error"))[DesktopRecoveryArea.BACKUP])
        assertEquals(DesktopRecoveryPhase.RUNNING, phases(backup = checked.copy(busy = true))[DesktopRecoveryArea.BACKUP])
        assertEquals(DesktopRecoveryPhase.RESTART, phases(backup = checked.copy(busy = true, restartRequired = true, error = "synthetic-private-error"))[DesktopRecoveryArea.BACKUP])
        assertEquals(DesktopRecoveryPhase.UNAVAILABLE, phases(backup = checked)[DesktopRecoveryArea.MERGE])
        val labels = desktopRecoveryStatuses(idle, checked, null).flatMap { status ->
            val text = desktopRecoveryText(status); listOf(text.zhCn, text.en)
        }.joinToString("\n")
        assertFalse(labels.contains("synthetic-private"))
    }

    @Test fun `merge preview running error and uncertain restart remain distinct without exposing source details`() {
        val preview = DesktopMergeImportState(ready = true, versionName = "synthetic-private-version",
            destination = path, failureDetails = "synthetic-private-stack")
        assertEquals(DesktopRecoveryPhase.PREVIEW, phases(merge = preview)[DesktopRecoveryArea.MERGE])
        assertEquals(DesktopRecoveryPhase.ATTENTION, phases(merge = preview.copy(error = "synthetic-private-error"))[DesktopRecoveryArea.MERGE])
        assertEquals(DesktopRecoveryPhase.RUNNING, phases(merge = preview.copy(busy = true))[DesktopRecoveryArea.MERGE])
        assertEquals(DesktopRecoveryPhase.RESTART, phases(merge = preview.copy(busy = true, restartRequired = true))[DesktopRecoveryArea.MERGE])
        val statuses = desktopRecoveryStatuses(idle, DesktopFullBackupState(), preview)
        assertFalse(statuses.toString().contains("synthetic-private"))
        assertFalse(statuses.any { it.area == DesktopRecoveryArea.MERGE && it.phase == DesktopRecoveryPhase.CHECKED })
    }

    @Test fun `startup guide uses fixed typed categories and diagnostic copy never includes guidance or private state`() {
        val values = DesktopIndexPreflightCode.entries.map { DesktopStartupDiagnostic.preflight(it) } +
            DesktopDataRootResolutionFailureKind.entries.map { DesktopStartupDiagnostic.selection(it) } +
            DesktopPortableActivationFailureKind.entries.map { DesktopStartupDiagnostic.activation(it, true) } +
            DesktopDataRootOwnershipFailureKind.entries.map { DesktopStartupDiagnostic.ownership(it) } +
            listOf(DesktopStartupDiagnostic.cancelled(), DesktopStartupDiagnostic.externalActivation())
        assertEquals(DesktopStartupRecoveryGuide.entries.toSet(), values.map { it.guide }.toSet())
        for (diagnostic in values) {
            val view = DesktopStartupBlockedView(diagnostic)
            var copied: String? = null; var closed = 0
            view.copyDiagnostic { copied = it }; view.close { closed++ }
            assertEquals("CCB STARTUP_BLOCKED ${diagnostic.code}", copied)
            assertEquals(1, closed)
            assertTrue(view.guidance.isNotBlank()); assertFalse(view.guidance.contains("synthetic-private"))
            assertEquals(1, copied!!.lines().size)
        }
    }
}
