package com.example.chatbar.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.snapshot.AutomaticBackupExecutionResult
import kotlinx.coroutines.Dispatchers
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

    @Test fun activeFinalSealingTimeoutSkipsFollowingCloseRetainsOwnershipAndCannotReportOrdinaryExit() {
        val pending = DesktopLanSyncFinalFlushTimeoutException()
        val root = Files.createTempDirectory("synthetic-desktop-lan-final-seal-").toRealPath()
        val resolved = DesktopDataRootResolution.Resolved(appDataRoot = root,
            provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
            bootstrapPath = root.resolveSibling("synthetic-bootstrap.json"))
        val events = mutableListOf<String>()
        var held: DesktopDataRootOwnership? = null
        var released = false
        try {
            assertSame(pending, assertFailsWith<DesktopLanSyncFinalFlushException> {
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
            var ordinaryExit = false
            var diagnosticShown = false
            runDesktopProcessLifecycle(application = { throw pending }, terminate = { ordinaryExit = true },
                reportFailure = { diagnosticShown = true })
            assertFalse(ordinaryExit)
            assertTrue(diagnosticShown, "A still active seal must enter the visible fixed shutdown diagnostic path")
            assertTrue(requireNotNull(pending.message).contains("CCB_LAN_SYNC_FINAL_LOCAL_FLUSH_"))
            assertNotNull(held)
            assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(resolved))
        } finally {
            held?.close()
            check(root.toFile().deleteRecursively())
        }
    }

    @Test fun completedPendingSyncClosesStorageShowsTruthfulNoticeAndReleasesRoot() {
        val root = Files.createTempDirectory("synthetic-desktop-lan-completed-seal-").toRealPath()
        val resolved = DesktopDataRootResolution.Resolved(appDataRoot = root,
            provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
            bootstrapPath = root.resolveSibling("synthetic-bootstrap.json"))
        val gate = DesktopDataOperationCoordinator()
        val pending = DesktopLanSyncFinalFlushIncompleteException()
        val events = mutableListOf<String>()
        var released = false
        var ordinaryExit = false
        try {
            runDesktopProcessLifecycle(application = {
                runDesktopApplicationWithDataRootOwnership(resolved, closeOwnership = {
                    released = true; events += "root"; it.close()
                }) {
                    runDesktopApplicationLifecycle(initialize = {}, applicationBody = {}, close = {
                        closeDesktopWithRecovery(close = {
                            closeDesktopWithLanSyncCompletionNotice(close = {
                                closeDesktopDataRuntimes(
                                    taskRuntimeClose = { events += "tasks" },
                                    draftRuntimeClose = {
                                        JsonFileStorage(root, gate).saveEntity("character_cards", "card",
                                            CharacterCard("card", "saved before pending sync", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
                                        events += "drafts"
                                    },
                                    finalLocalFlush = { events += "final-local"; throw pending },
                                    runtimeClose = { events += "backup" },
                                    coordinatorClose = { gate.closeAndDrain(); events += "gate" },
                                    migrationServiceClose = { events += "migration" },
                                )
                            }, showNotice = { message ->
                                assertTrue(pending.teardownCompleted)
                                assertEquals(DesktopDataOperationCoordinatorState.CLOSED, gate.state)
                                assertTrue(message.contains("CCB_LAN_SYNC_FINAL_LOCAL_FLUSH_INCOMPLETE"))
                                assertFalse(message.contains("资料目录锁继续保留"))
                                events += "notice"
                            })
                        }, observeWait = { AutoCloseable {} })
                    })
                }
            }, terminate = { ordinaryExit = true }, reportFailure = { error("Completed pending sync is safe to close") })
            assertEquals(listOf("tasks", "drafts", "final-local", "backup", "gate", "migration", "notice", "root"), events)
            assertTrue(released)
            assertTrue(ordinaryExit)
            assertEquals("saved before pending sync",
                runBlocking { JsonFileStorage(root).loadEntity("character_cards", "card", CharacterCard.serializer())?.name })
            assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolved)).ownership.close()
        } finally {
            runBlocking { gate.closeAndDrain() }
            check(root.toFile().deleteRecursively())
        }
    }

    @Test fun pendingSyncCannotHideASeparateTeardownFailure() = runBlocking {
        val pending = DesktopLanSyncFinalFlushIncompleteException()
        val writerFailure = DesktopDraftDrainTimeoutException()
        var noticeShown = false
        assertSame(pending, assertFailsWith<DesktopLanSyncFinalFlushIncompleteException> {
            closeDesktopWithLanSyncCompletionNotice(close = {
                closeDesktopDataRuntimes(finalLocalFlush = { throw pending },
                    runtimeClose = { throw writerFailure }, coordinatorClose = {}, migrationServiceClose = {})
            }, showNotice = { noticeShown = true })
        })
        assertFalse(pending.teardownCompleted)
        assertFalse(noticeShown)
        assertTrue(pending.suppressed.any { it === writerFailure })
    }

    @Test fun shutdownSealingUsesLiveBackupAndRealExclusiveStorageAfterAudioOwnerHasClosed() = runBlocking {
        val root = Files.createTempDirectory("synthetic-lan-shutdown-gate-").toRealPath()
        val gate = DesktopDataOperationCoordinator()
        val outputClosed = AtomicBoolean()
        val audioCalls = AtomicInteger()
        val output = object : DesktopAudioOutput {
            override suspend fun play(path: Path) { error("SYNTHETIC_NO_AUDIO_PLAYBACK") }
            override suspend fun isPlaying() = false
            override suspend fun stopAndDrain() {
                audioCalls.incrementAndGet()
                check(!outputClosed.get()) { "SYNTHETIC_CLOSED_AUDIO_OUTPUT" }
            }
            override suspend fun closeAndDrain() { outputClosed.set(true) }
        }
        val focus = DesktopAudioFocus(output)
        val ordinaryAudio = DesktopAudioMaintenance({ focus }) { }
        val backup = DesktopAutomaticBackupRuntime(DesktopSettingsStore(root, gate), gate) { sink ->
            DesktopAutomaticBackupScheduler({ _, _ -> AutomaticBackupExecutionResult.SkippedNotDue },
                dispatcher = Dispatchers.Unconfined, eventSink = sink)
        }
        try {
            backup.initialize()
            focus.closeAndDrain()
            assertTrue(outputClosed.get())
            // The former final-seal route really cannot use this already-closed owner.
            assertFailsWith<DesktopAudioDrainException> {
                ordinaryAudio.withMaintenance(true, { gate.isRestartRequired }) { error("must not reach storage") }
            }
            val closedAudioAttempts = audioCalls.get()
            assertEquals(1, closedAudioAttempts)
            val shutdown = DesktopLanSyncShutdownMaintenance(backup, gate)
            val storage = JsonFileStorage(root, gate)
            shutdown.withExclusive {
                assertEquals(DesktopDataOperationCoordinatorState.EXCLUSIVE, gate.state)
                assertEquals(DesktopAutomaticBackupRuntimeMode.MAINTENANCE_PAUSED, backup.state.value.mode)
                storage.saveEntity("character_cards", "card", CharacterCard("card", "first final source", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
            }
            // Multiple native admissions during one seal share the one paused backup owner.
            shutdown.withExclusive {
                assertEquals(DesktopDataOperationCoordinatorState.EXCLUSIVE, gate.state)
                storage.saveEntity("character_cards", "card", CharacterCard("card", "final sealed source", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
            }
            assertEquals("final sealed source", storage.loadEntity("character_cards", "card", CharacterCard.serializer())?.name)
            assertEquals(DesktopDataOperationCoordinatorState.OPEN, gate.state)
            assertEquals(DesktopAutomaticBackupRuntimeMode.MAINTENANCE_PAUSED, backup.state.value.mode)
            assertFalse(backup.state.value.schedulerRunning)
            assertEquals(closedAudioAttempts, audioCalls.get(), "Final source sealing must not revisit closed audio or its dispatcher")
        } finally {
            backup.close()
            gate.closeAndDrain()
            check(root.toFile().deleteRecursively())
        }
    }
}
