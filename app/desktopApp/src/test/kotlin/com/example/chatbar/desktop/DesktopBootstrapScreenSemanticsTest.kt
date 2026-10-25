package com.example.chatbar.desktop

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopBootstrapScreenSemanticsTest {
    @Test
    fun `protected move says old snapshot history remains at source and cleanup warning is visible`() {
        val snapshot = com.example.chatbar.data.snapshot.AppDataSnapshot("synthetic-safety", Path.of("snapshot").toAbsolutePath(),
            java.time.Instant.EPOCH, com.example.chatbar.data.snapshot.SnapshotPurpose.PRE_RESTORE,
            com.example.chatbar.data.snapshot.SnapshotValidation.VALID)
        val result = DesktopDataRootMigrationResult.ProtectedSuccess(Path.of("source").toAbsolutePath(), destination,
            snapshot, DesktopBootstrapAuthorityCommitResult.Committed(destination,
                DesktopBootstrapDocument(DesktopDataRootSelection.custom(destination), kotlinx.serialization.json.buildJsonObject {})),
            DesktopMaintenanceResumeResult.RestartRequired, cleanupWarning = "Synthetic cleanup remains unconfirmed",
            retainedWorkspace = Path.of("synthetic-workspace").toAbsolutePath())
        val text = protectedRootSuccessDetails(result).joinToString("\n")
        kotlin.test.assertTrue(text.contains("已登记数据"))
        kotlin.test.assertTrue(text.contains("原目录和旧凭据保留"))
        kotlin.test.assertTrue(text.contains("未复制到新目录"))
        kotlin.test.assertTrue(text.contains(snapshot.name))
        kotlin.test.assertTrue(text.contains("Synthetic cleanup remains unconfirmed"))
        kotlin.test.assertTrue(text.contains(result.retainedWorkspace.toString()))
    }

    @Test
    fun `retryable destination label requires proven materialized copy`() {
        assertEquals(
            "Attempted destination",
            retryableDestinationLabel(
                failure(DesktopDataRootMigrationFailureKind.PREPARATION),
            ),
        )
        assertEquals(
            "Attempted destination",
            retryableDestinationLabel(
                failure(
                    kind = DesktopDataRootMigrationFailureKind.MATERIALIZATION,
                    materialization = DesktopMigrationMaterializationResult.Failure(
                        kind = DesktopMigrationMaterializationFailureKind.COPY_FAILED,
                        message = "fixture",
                    ),
                ),
            ),
        )
        assertEquals(
            "Destination copy",
            retryableDestinationLabel(
                failure(
                    kind = DesktopDataRootMigrationFailureKind.AUTHORITY_PRE_COMMIT,
                    materialization = DesktopMigrationMaterializationResult.Materialized(
                        destinationRoot = destination,
                        summary = DesktopMigrationMaterializationSummary(
                            activeFileCount = 1,
                            activeDirectoryCount = 0,
                            totalBytes = 7,
                            migratedSnapshotNames = emptyList(),
                        ),
                        warnings = emptyList(),
                    ),
                ),
            ),
        )
    }

    private fun failure(
        kind: DesktopDataRootMigrationFailureKind,
        materialization: DesktopMigrationMaterializationResult? = null,
    ) = DesktopDataRootMigrationResult.Failure(
        kind = kind,
        sourceRoot = Path.of("source").toAbsolutePath(),
        destinationRoot = destination,
        materialization = materialization,
    )

    private companion object {
        val destination: Path = Path.of("destination").toAbsolutePath()
    }
}
