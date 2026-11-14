package com.example.chatbar.desktop

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Shared close orchestration and private synthetic root ownership; no app or native UI launch. */
class DesktopLanSyncShutdownFlushTest {
    @Test fun producersAndDraftsFinishBeforeFinalSealingAndFailedDraftsSkipSealing() = runBlocking {
        val events = mutableListOf<String>()
        closeDesktopDataRuntimes(
            taskRuntimeClose = { events += "tasks" },
            draftRuntimeClose = { events += "drafts" },
            finalLocalFlush = { events += "final-local" },
            runtimeClose = { events += "backup" },
            coordinatorClose = { events += "gate" },
            migrationServiceClose = { events += "migration" },
        )
        assertEquals(listOf("tasks", "drafts", "final-local", "backup", "gate", "migration"), events)
        events.clear()
        val failure = IllegalStateException("synthetic joined draft failure")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            closeDesktopDataRuntimes(
                taskRuntimeClose = { events += "tasks" },
                draftRuntimeClose = { events += "drafts"; throw failure },
                finalLocalFlush = { events += "must-not-seal" },
                runtimeClose = { events += "backup" },
                coordinatorClose = { events += "gate" },
                migrationServiceClose = { events += "migration" },
            )
        })
        assertEquals(listOf("tasks", "drafts", "backup", "gate", "migration"), events)
    }

    @Test fun pendingFinalSealingSkipsEveryFollowingCloseAndRetainsActualRootOwnership() {
        val root = Files.createTempDirectory("synthetic-desktop-lan-final-seal-").toRealPath()
        val resolved = DesktopDataRootResolution.Resolved(appDataRoot = root,
            provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
            bootstrapPath = root.resolveSibling("synthetic-bootstrap.json"))
        val events = mutableListOf<String>()
        var held: DesktopDataRootOwnership? = null
        var released = false
        val pending = DesktopLanSyncFinalFlushTimeoutException()
        try {
            assertSame(pending, assertFailsWith<DesktopLanSyncFinalFlushTimeoutException> {
                runDesktopApplicationWithDataRootOwnership(resolved,
                    acquireOwnership = { selected -> DesktopDataRootOwnership.acquire(selected).also {
                        held = (it as? DesktopDataRootOwnershipResult.Acquired)?.ownership
                    } },
                    closeOwnership = { released = true; it.close() },
                    applicationBody = { runBlocking {
                        closeDesktopDataRuntimes(
                            taskRuntimeClose = { events += "tasks" },
                            draftRuntimeClose = { events += "drafts" },
                            finalLocalFlush = { events += "final-local"; throw pending },
                            runtimeClose = { events += "must-not-close-backup" },
                            coordinatorClose = { events += "must-not-close-gate" },
                            migrationServiceClose = { events += "must-not-close-migration" },
                        )
                    } })
            })
            assertEquals(listOf("tasks", "drafts", "final-local"), events)
            assertFalse(released)
            assertNotNull(held)
            assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(resolved))
        } finally {
            held?.close()
            check(root.toFile().deleteRecursively())
        }
    }
}
