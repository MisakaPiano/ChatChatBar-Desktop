package com.example.chatbar.desktop

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopBootstrapScreenSemanticsTest {
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
