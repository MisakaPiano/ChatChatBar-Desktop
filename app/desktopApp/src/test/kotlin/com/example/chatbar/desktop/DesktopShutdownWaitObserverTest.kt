package com.example.chatbar.desktop

import com.example.chatbar.data.snapshot.AutomaticBackupExecutionResult
import java.nio.file.Files
import java.time.Duration
import kotlin.test.*
import kotlinx.coroutines.*

class DesktopShutdownWaitObserverTest {
    private class Presentation {
        lateinit var deadline: () -> Unit
        var cancelled = false
        var visible = false
        var shows = 0
        val queued = mutableListOf<() -> Unit>()
        fun observer(queuedDispatch: Boolean = false) = DesktopShutdownWaitObserver(
            schedule = { delay, action ->
                assertEquals(5_000L, delay)
                deadline = action
                AutoCloseable { cancelled = true }
            },
            dispatch = { if (queuedDispatch) queued += it else it() },
            dispatchAndWait = { it() },
            show = { shows++; visible = true; AutoCloseable { visible = false } },
        )
    }

    @Test fun `fast close cancels deadline without creating a status window`() {
        val ui = Presentation()
        val observer = ui.observer()
        observer.close()
        ui.deadline()
        observer.close()
        assertTrue(ui.cancelled)
        assertEquals(0, ui.shows)
    }

    @Test fun `queued presentation cannot resurrect after close`() {
        val ui = Presentation()
        val observer = ui.observer(queuedDispatch = true)
        ui.deadline()
        observer.close()
        ui.queued.single().invoke()
        assertFalse(ui.visible)
        assertEquals(0, ui.shows)
    }

    @Test fun `backup and coordinator waits stay live with ownership until actual completion`() = runBlocking {
        val path = Files.createTempDirectory("ccb-wait-test-")
        val root = DesktopDataRootResolution.Resolved(path, DesktopDataRootProvenance.CLI_OVERRIDE, path.resolve("unused.json"))
        val ownership = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(root)).ownership
        val backupEntered = CompletableDeferred<Unit>()
        val backupRelease = CompletableDeferred<Unit>()
        val backupWritten = CompletableDeferred<Unit>()
        val operationEntered = CompletableDeferred<Unit>()
        val operationRelease = CompletableDeferred<Unit>()
        val coordinatorClosing = CompletableDeferred<Unit>()
        val coordinator = DesktopDataOperationCoordinator()
        val ui = Presentation()
        var exited = false
        val backup = DesktopAutomaticBackupScheduler({ _, _ ->
            backupEntered.complete(Unit)
            backupRelease.await()
            Files.writeString(path.resolve("backup.txt"), "completed backup")
            backupWritten.complete(Unit)
            AutomaticBackupExecutionResult.SkippedNotDue
        }, Dispatchers.Default, {})
        backup.start(DesktopAutomaticBackupSchedule(Duration.ZERO, 1, Duration.ofDays(1)))
        val operation = async {
            coordinator.withNormalOperation {
                operationEntered.complete(Unit)
                operationRelease.await()
                Files.writeString(path.resolve("operation.txt"), "completed operation")
            }
        }
        try {
            withTimeout(5_000) { backupEntered.await(); operationEntered.await() }
            val closing = async(start = CoroutineStart.UNDISPATCHED) {
                closeDesktopWithRecovery(close = {
                    backup.close()
                    coordinatorClosing.complete(Unit)
                    coordinator.closeAndDrain()
                }, observeWait = { ui.observer() })
                assertFalse(ui.visible)
                ownership.close()
                runDesktopProcessLifecycle({}, terminate = { exited = true })
            }
            ui.deadline()
            assertTrue(ui.visible)
            assertFalse(closing.isCompleted)
            assertFalse(backupWritten.isCompleted)
            assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(root))
            backupRelease.complete(Unit)
            withTimeout(5_000) { coordinatorClosing.await() }
            assertEquals(DesktopDataOperationCoordinatorState.CLOSING, coordinator.state)
            assertTrue(ui.visible)
            assertFalse(operation.isCompleted)
            assertFalse(exited)
            assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(root))
            operationRelease.complete(Unit)
            withTimeout(5_000) { operation.await(); closing.await() }
            assertEquals("completed backup", Files.readString(path.resolve("backup.txt")))
            assertEquals("completed operation", Files.readString(path.resolve("operation.txt")))
            assertTrue(exited)
            assertTrue(ui.cancelled)
            assertEquals(DesktopDataOperationCoordinatorState.CLOSED, coordinator.state)
            assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(root)).ownership.close()
        } finally {
            backupRelease.complete(Unit); operationRelease.complete(Unit)
            operation.await(); backup.close(); coordinator.closeAndDrain(); ownership.close()
            path.toFile().deleteRecursively()
        }
    }

    @Test fun `waiting window is withdrawn before resumable recovery and new attempt`() = runBlocking {
        val presentations = mutableListOf<Presentation>()
        var attempts = 0
        var recoveries = 0
        closeDesktopWithRecovery(close = {
            presentations.last().deadline()
            if (attempts++ == 0) throw DesktopDraftDrainTimeoutException()
        }, awaitRetry = {
            assertFalse(presentations.last().visible)
            assertTrue(presentations.last().cancelled)
            recoveries++
        }, observeWait = { Presentation().also { presentations += it }.observer() })
        assertEquals(2, attempts)
        assertEquals(1, recoveries)
        assertTrue(presentations.all { !it.visible && it.cancelled })
    }

    @Test fun `non recoverable failure withdraws waiting status and cannot terminate`() {
        val ui = Presentation()
        var failed = false
        runDesktopProcessLifecycle(application = { runBlocking {
            closeDesktopWithRecovery(close = {
                ui.deadline()
                assertTrue(ui.visible)
                error("synthetic transaction failure")
            }, observeWait = { ui.observer() })
        } }, terminate = { fail("unsafe termination") }, reportFailure = {
            assertFalse(ui.visible)
            failed = true
        })
        assertTrue(failed)
    }
}
