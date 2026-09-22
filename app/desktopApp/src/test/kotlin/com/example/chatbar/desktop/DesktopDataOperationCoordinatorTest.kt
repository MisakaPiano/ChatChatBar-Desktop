package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopDataOperationCoordinatorTest {
    @Test
    fun `independent normal operations overlap`() = runTest {
        val coordinator = DesktopDataOperationCoordinator()
        val release = CompletableDeferred<Unit>()
        val entered = mutableListOf<Int>()
        val jobs = (1..2).map { id ->
            launch {
                coordinator.withNormalOperation {
                    entered += id
                    release.await()
                }
            }
        }

        runCurrent()
        assertEquals(setOf(1, 2), entered.toSet())
        release.complete(Unit)
        jobs.forEach { it.join() }
    }

    @Test
    fun `pending maintenance drains existing operations and blocks new admission`() = runTest {
        val coordinator = DesktopDataOperationCoordinator()
        val releaseNormal = CompletableDeferred<Unit>()
        val releaseExclusive = CompletableDeferred<Unit>()
        val firstEntered = CompletableDeferred<Unit>()
        val exclusiveEntered = CompletableDeferred<Unit>()
        var lateNormalEntered = false

        val first = launch {
            coordinator.withNormalOperation {
                firstEntered.complete(Unit)
                releaseNormal.await()
            }
        }
        firstEntered.await()
        val exclusive = launch {
            coordinator.withExclusiveMaintenance {
                exclusiveEntered.complete(Unit)
                releaseExclusive.await()
            }
        }
        runCurrent()
        assertEquals(DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING, coordinator.state)
        val late = launch {
            coordinator.withNormalOperation { lateNormalEntered = true }
        }
        runCurrent()
        assertFalse(lateNormalEntered)

        releaseNormal.complete(Unit)
        exclusiveEntered.await()
        assertFalse(lateNormalEntered)
        releaseExclusive.complete(Unit)
        first.join()
        exclusive.join()
        late.join()
        assertTrue(lateNormalEntered)
        assertEquals(DesktopDataOperationCoordinatorState.OPEN, coordinator.state)
    }

    @Test
    fun `exclusive waiters run FIFO without starvation`() = runTest {
        val coordinator = DesktopDataOperationCoordinator()
        val releaseNormal = CompletableDeferred<Unit>()
        val enteredNormal = CompletableDeferred<Unit>()
        val order = mutableListOf<Int>()
        val normal = launch {
            coordinator.withNormalOperation {
                enteredNormal.complete(Unit)
                releaseNormal.await()
            }
        }
        enteredNormal.await()
        val exclusives = (1..3).map { id ->
            launch { coordinator.withExclusiveMaintenance { order += id } }
        }
        runCurrent()
        releaseNormal.complete(Unit)
        normal.join()
        exclusives.forEach { it.join() }
        assertEquals(listOf(1, 2, 3), order)
    }

    @Test
    fun `cancelled waiters do not leak admission state`() = runTest {
        val coordinator = DesktopDataOperationCoordinator()
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val normal = launch {
            coordinator.withNormalOperation {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val cancelledExclusive = launch { coordinator.withExclusiveMaintenance {} }
        runCurrent()
        cancelledExclusive.cancelAndJoin()
        assertEquals(DesktopDataOperationCoordinatorState.OPEN, coordinator.state)

        release.complete(Unit)
        normal.join()
        val completed = coordinator.withNormalOperation { true }
        assertTrue(completed)
    }

    @Test
    fun `nested normal entry survives pending maintenance and IO context`() = runTest {
        val coordinator = DesktopDataOperationCoordinator()
        val outerEntered = CompletableDeferred<Unit>()
        val allowNested = CompletableDeferred<Unit>()
        val nestedDone = CompletableDeferred<Unit>()
        val outer = launch {
            coordinator.withNormalOperation {
                outerEntered.complete(Unit)
                allowNested.await()
                withContext(Dispatchers.IO) {
                    coordinator.withNormalOperation { nestedDone.complete(Unit) }
                }
            }
        }
        outerEntered.await()
        val exclusive = launch { coordinator.withExclusiveMaintenance {} }
        runCurrent()
        assertEquals(DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING, coordinator.state)
        allowNested.complete(Unit)
        nestedDone.await()
        outer.join()
        exclusive.join()
    }

    @Test
    fun `identity marker supports A B A and does not admit unrelated coroutine`() = runTest {
        val first = DesktopDataOperationCoordinator()
        val second = DesktopDataOperationCoordinator()
        val outerEntered = CompletableDeferred<Unit>()
        val allowNested = CompletableDeferred<Unit>()
        var unrelatedEntered = false
        val outer = launch {
            first.withNormalOperation {
                outerEntered.complete(Unit)
                allowNested.await()
                second.withNormalOperation {
                    first.withNormalOperation { }
                }
            }
        }
        outerEntered.await()
        val maintenance = launch { first.withExclusiveMaintenance {} }
        runCurrent()
        val unrelated = launch {
            first.withNormalOperation { unrelatedEntered = true }
        }
        runCurrent()
        assertFalse(unrelatedEntered)
        allowNested.complete(Unit)
        outer.join()
        maintenance.join()
        unrelated.join()
        assertTrue(unrelatedEntered)
    }

    @Test
    fun `structured child remains covered until outer registration completes`() = runTest {
        val coordinator = DesktopDataOperationCoordinator()
        val childEntered = CompletableDeferred<Unit>()
        val releaseChild = CompletableDeferred<Unit>()
        val exclusiveEntered = CompletableDeferred<Unit>()
        val normal = launch {
            coordinator.withNormalOperation {
                coroutineScope {
                    launch {
                        coordinator.withNormalOperation {
                            childEntered.complete(Unit)
                            releaseChild.await()
                        }
                    }
                }
            }
        }
        childEntered.await()
        val exclusive = launch {
            coordinator.withExclusiveMaintenance { exclusiveEntered.complete(Unit) }
        }
        runCurrent()
        assertFalse(exclusiveEntered.isCompleted)
        releaseChild.complete(Unit)
        normal.join()
        exclusive.join()
        assertTrue(exclusiveEntered.isCompleted)
    }

    @Test
    fun `normal upgrade and nested exclusive are rejected`() = runTest {
        val coordinator = DesktopDataOperationCoordinator()
        val upgrade = assertFailsWith<DesktopDataOperationRejectedException> {
            coordinator.withNormalOperation { coordinator.withExclusiveMaintenance {} }
        }
        assertEquals(DesktopDataOperationRejectionReason.NORMAL_TO_EXCLUSIVE_UPGRADE, upgrade.reason)

        val nested = assertFailsWith<DesktopDataOperationRejectedException> {
            coordinator.withExclusiveMaintenance { coordinator.withExclusiveMaintenance {} }
        }
        assertEquals(DesktopDataOperationRejectionReason.NESTED_EXCLUSIVE, nested.reason)
    }

    @Test
    fun `exceptions release normal and exclusive registrations`() = runTest {
        val coordinator = DesktopDataOperationCoordinator()
        assertFailsWith<IllegalArgumentException> {
            coordinator.withNormalOperation { throw IllegalArgumentException("normal") }
        }
        assertFailsWith<IllegalStateException> {
            coordinator.withExclusiveMaintenance { throw IllegalStateException("exclusive") }
        }
        assertEquals(DesktopDataOperationCoordinatorState.OPEN, coordinator.state)
        assertTrue(coordinator.withNormalOperation { true })
    }

    @Test
    fun `restart seal permanently rejects queued and future operations`() = runTest {
        val coordinator = DesktopDataOperationCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val exclusive = launch {
            coordinator.withExclusiveMaintenance {
                entered.complete(Unit)
                release.await()
                requireRestart()
            }
        }
        entered.await()
        val queuedFailure = CompletableDeferred<Throwable>()
        val queued = launch {
            try {
                coordinator.withNormalOperation { Unit }
            } catch (error: Throwable) {
                queuedFailure.complete(error)
            }
        }
        runCurrent()
        release.complete(Unit)
        exclusive.join()
        queued.join()
        assertEquals(
            DesktopDataOperationRejectionReason.RESTART_REQUIRED,
            assertIs<DesktopDataOperationRejectedException>(queuedFailure.await()).reason,
        )
        assertEquals(DesktopDataOperationCoordinatorState.RESTART_REQUIRED, coordinator.state)
        val futureFailure = assertFailsWith<DesktopDataOperationRejectedException> {
            coordinator.withExclusiveMaintenance {}
        }
        assertEquals(DesktopDataOperationRejectionReason.RESTART_REQUIRED, futureFailure.reason)
    }

    @Test
    fun `close drains in flight work and permits its nested calls`() = runTest {
        val coordinator = DesktopDataOperationCoordinator()
        val entered = CompletableDeferred<Unit>()
        val allowNested = CompletableDeferred<Unit>()
        val nestedDone = CompletableDeferred<Unit>()
        val normal = launch {
            coordinator.withNormalOperation {
                entered.complete(Unit)
                allowNested.await()
                coordinator.withNormalOperation { nestedDone.complete(Unit) }
            }
        }
        entered.await()
        val close = launch { coordinator.closeAndDrain() }
        runCurrent()
        assertEquals(DesktopDataOperationCoordinatorState.CLOSING, coordinator.state)
        val rejected = assertFailsWith<DesktopDataOperationRejectedException> {
            coordinator.withNormalOperation {}
        }
        assertEquals(DesktopDataOperationRejectionReason.CLOSING, rejected.reason)
        allowNested.complete(Unit)
        nestedDone.await()
        normal.join()
        close.join()
        assertEquals(DesktopDataOperationCoordinatorState.CLOSED, coordinator.state)
    }

    @Test
    fun `cancelled close waiter does not prevent later close`() = runTest {
        val coordinator = DesktopDataOperationCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val normal = launch {
            coordinator.withNormalOperation {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val firstClose = launch { coordinator.closeAndDrain() }
        runCurrent()
        firstClose.cancelAndJoin()
        release.complete(Unit)
        normal.join()
        coordinator.closeAndDrain()
        assertEquals(DesktopDataOperationCoordinatorState.CLOSED, coordinator.state)
    }

    @Test
    fun `JsonFileStorage callback can make nested cross entity access while maintenance waits`() = runTest {
        val root = Files.createTempDirectory("coordinated-storage-nested-")
        try {
            val coordinator = DesktopDataOperationCoordinator()
            val storage = JsonFileStorage(root, coordinator)
            storage.saveEntity("first", "one", "one", String.serializer())
            val actionEntered = CompletableDeferred<Unit>()
            val allowNested = CompletableDeferred<Unit>()
            val operation = launch {
                storage.forEachUncached("first", String.serializer(), action = {
                    actionEntered.complete(Unit)
                    allowNested.await()
                    storage.saveEntity(
                        "second",
                        "two",
                        "two",
                        String.serializer(),
                    )
                })
            }
            actionEntered.await()
            val maintenance = launch { coordinator.withExclusiveMaintenance {} }
            runCurrent()
            assertEquals(DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING, coordinator.state)
            allowNested.complete(Unit)
            operation.join()
            maintenance.join()
            assertTrue(storage.exists("second", "two"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `JsonFileStorage preserves per entity concurrency and serialization`() = runTest {
        val root = Files.createTempDirectory("coordinated-storage-concurrency-")
        try {
            val coordinator = DesktopDataOperationCoordinator()
            val storage = JsonFileStorage(root, coordinator)
            storage.saveEntity("first", "one", "one", String.serializer())
            storage.saveEntity("second", "two", "two", String.serializer())
            val release = CompletableDeferred<Unit>()
            val firstEntered = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val first = launch {
                storage.forEachUncached("first", String.serializer(), action = {
                    firstEntered.complete(Unit)
                    release.await()
                })
            }
            firstEntered.await()
            val differentType = launch {
                storage.forEachUncached("second", String.serializer(), action = {
                    secondEntered.complete(Unit)
                })
            }
            secondEntered.await()

            var sameTypeEntered = false
            val sameType = launch {
                storage.forEachUncached("first", String.serializer(), action = {
                    sameTypeEntered = true
                })
            }
            runCurrent()
            assertFalse(sameTypeEntered)
            release.complete(Unit)
            first.join()
            differentType.join()
            sameType.join()
            assertTrue(sameTypeEntered)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
