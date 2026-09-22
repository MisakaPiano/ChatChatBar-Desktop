package com.example.chatbar.desktop

import com.example.chatbar.data.snapshot.AppDataSnapshot
import com.example.chatbar.data.snapshot.AutomaticBackupExecutionResult
import com.example.chatbar.data.snapshot.AutomaticSnapshotPruneResult
import com.example.chatbar.data.snapshot.SnapshotPurpose
import com.example.chatbar.data.snapshot.SnapshotValidation
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopAutomaticBackupSchedulerTest {
    @Test
    fun `schedule validates inputs without defining product defaults`() {
        assertFailsWith<IllegalArgumentException> {
            schedule(minimumBackupInterval = Duration.ofSeconds(-1))
        }
        assertFailsWith<IllegalArgumentException> {
            schedule(maximumSnapshotCount = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            schedule(checkInterval = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            schedule(checkInterval = Duration.ofSeconds(-1))
        }

        assertEquals(Duration.ZERO, schedule(minimumBackupInterval = Duration.ZERO).minimumBackupInterval)
    }

    @Test
    fun `container construction creates no app-data or backup directories and does not start`() = runTest {
        val parent = Files.createTempDirectory("desktop-container-")
        val appDataRoot = parent.resolve("missing-app-data")
        try {
            val container = DesktopAppContainer(appDataRoot)

            assertFalse(Files.exists(appDataRoot))
            assertFalse(container.automaticBackupScheduler.isRunning)
            container.automaticBackupScheduler.close()
            assertFalse(Files.exists(appDataRoot))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `start executes immediately then waits for check interval`() = runTest {
        val calls = AtomicInteger()
        val scheduler = scheduler { _, _ ->
            calls.incrementAndGet()
            AutomaticBackupExecutionResult.SkippedNotDue
        }

        assertTrue(scheduler.start(schedule(checkInterval = Duration.ofSeconds(1))))
        assertEquals(0, calls.get())
        runCurrent()
        assertEquals(1, calls.get())
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, calls.get())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, calls.get())

        assertTrue(scheduler.stop())
        scheduler.close()
    }

    @Test
    fun `repeated start does not create a second loop`() = runTest {
        val calls = AtomicInteger()
        val scheduler = scheduler { _, _ ->
            calls.incrementAndGet()
            AutomaticBackupExecutionResult.SkippedNotDue
        }

        assertTrue(scheduler.start(schedule()))
        assertFalse(scheduler.start(schedule()))
        runCurrent()
        assertEquals(1, calls.get())

        assertTrue(scheduler.stop())
        scheduler.close()
    }

    @Test
    fun `scheduled executions are sequential and never overlap`() = runTest {
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val calls = AtomicInteger()
        val scheduler = scheduler { _, _ ->
            val current = active.incrementAndGet()
            maximumActive.updateAndGet { previous -> maxOf(previous, current) }
            calls.incrementAndGet()
            active.decrementAndGet()
            AutomaticBackupExecutionResult.SkippedNotDue
        }

        scheduler.start(schedule(checkInterval = Duration.ofSeconds(1)))
        runCurrent()
        repeat(4) {
            advanceTimeBy(1_000)
            runCurrent()
        }

        assertEquals(5, calls.get())
        assertEquals(1, maximumActive.get())
        scheduler.stop()
        scheduler.close()
    }

    @Test
    fun `events distinguish skipped created and cleanup warning results`() = runTest {
        val events = mutableListOf<DesktopAutomaticBackupEvent>()
        val calls = AtomicInteger()
        val created = createdResult(cleanupWarning = "quarantine cleanup incomplete")
        val scheduler = scheduler(events) { _, _ ->
            if (calls.getAndIncrement() == 0) AutomaticBackupExecutionResult.SkippedNotDue else created
        }

        scheduler.start(schedule(checkInterval = Duration.ofSeconds(1)))
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertIs<DesktopAutomaticBackupEvent.SkippedNotDue>(events[0])
        val createdEvent = assertIs<DesktopAutomaticBackupEvent.Created>(events[1])
        assertEquals(created, createdEvent.result)
        assertEquals(
            listOf("quarantine cleanup incomplete"),
            createdEvent.result.pruneResult.cleanupWarnings,
        )
        assertTrue(events.none { it is DesktopAutomaticBackupEvent.Failed })
        scheduler.stop()
        scheduler.close()
    }

    @Test
    fun `execution failure is reported and later tick retries`() = runTest {
        val events = mutableListOf<DesktopAutomaticBackupEvent>()
        val calls = AtomicInteger()
        val failure = IllegalStateException("fixture failure")
        val scheduler = scheduler(events) { _, _ ->
            if (calls.getAndIncrement() == 0) throw failure
            AutomaticBackupExecutionResult.SkippedNotDue
        }

        scheduler.start(schedule(checkInterval = Duration.ofSeconds(1)))
        runCurrent()
        assertEquals(failure, assertIs<DesktopAutomaticBackupEvent.Failed>(events.single()).error)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(2, calls.get())
        assertIs<DesktopAutomaticBackupEvent.SkippedNotDue>(events[1])
        scheduler.stop()
        scheduler.close()
    }

    @Test
    fun `event sink failure does not stop scheduler and next tick still runs`() = runTest {
        val executions = AtomicInteger()
        val deliveries = AtomicInteger()
        val scheduler = DesktopAutomaticBackupScheduler(
            executeAutomaticBackup = { _, _ ->
                executions.incrementAndGet()
                AutomaticBackupExecutionResult.SkippedNotDue
            },
            dispatcher = StandardTestDispatcher(testScheduler),
            eventSink = {
                if (deliveries.getAndIncrement() == 0) {
                    throw IllegalStateException("observer fixture failure")
                }
            },
        )

        scheduler.start(schedule(checkInterval = Duration.ofSeconds(1)))
        runCurrent()
        assertEquals(1, executions.get())
        assertTrue(scheduler.isRunning)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, executions.get())
        assertEquals(2, deliveries.get())
        assertTrue(scheduler.isRunning)

        scheduler.stop()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, executions.get())
        scheduler.close()
    }

    @Test
    fun `stop prevents future executions and restart works after full stop`() = runTest {
        val calls = AtomicInteger()
        val scheduler = scheduler { _, _ ->
            calls.incrementAndGet()
            AutomaticBackupExecutionResult.SkippedNotDue
        }

        scheduler.start(schedule(checkInterval = Duration.ofSeconds(1)))
        runCurrent()
        assertTrue(scheduler.stop())
        assertFalse(scheduler.isRunning)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, calls.get())

        assertTrue(scheduler.start(schedule(checkInterval = Duration.ofSeconds(1))))
        runCurrent()
        assertEquals(2, calls.get())
        scheduler.stop()
        scheduler.close()
    }

    @Test
    fun `stop during blocking execution waits without interrupting and blocks replacement loop`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val events = CopyOnWriteArrayList<DesktopAutomaticBackupEvent>()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scheduler = DesktopAutomaticBackupScheduler(
            executeAutomaticBackup = { _, _ ->
                started.countDown()
                try {
                    release.await()
                } catch (error: InterruptedException) {
                    interrupted.set(true)
                    throw error
                }
                AutomaticBackupExecutionResult.SkippedNotDue
            },
            dispatcher = dispatcher,
            eventSink = events::add,
        )

        try {
            assertTrue(scheduler.start(schedule()))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            runBlocking {
                val stopping = async(Dispatchers.Default) { scheduler.stop() }
                assertFalse(scheduler.start(schedule()))
                assertFalse(stopping.isCompleted)
                release.countDown()
                assertTrue(stopping.await())
                scheduler.close()
            }

            assertFalse(interrupted.get())
            assertEquals(
                listOf<DesktopAutomaticBackupEvent>(DesktopAutomaticBackupEvent.SkippedNotDue),
                events,
            )
            assertFalse(scheduler.isRunning)
        } finally {
            release.countDown()
            runBlocking { scheduler.close() }
            dispatcher.close()
        }
    }

    @Test
    fun `close permanently releases scheduler scope`() = runTest {
        val scheduler = scheduler { _, _ -> AutomaticBackupExecutionResult.SkippedNotDue }

        scheduler.close()

        assertFailsWith<IllegalStateException> {
            scheduler.start(schedule())
        }
        assertFalse(scheduler.isRunning)
    }

    private fun kotlinx.coroutines.test.TestScope.scheduler(
        events: MutableList<DesktopAutomaticBackupEvent> = mutableListOf(),
        execute: (Duration, Int) -> AutomaticBackupExecutionResult,
    ) = DesktopAutomaticBackupScheduler(
        executeAutomaticBackup = execute,
        dispatcher = StandardTestDispatcher(testScheduler),
        eventSink = events::add,
    )

    private fun schedule(
        minimumBackupInterval: Duration = Duration.ofHours(1),
        maximumSnapshotCount: Int = 2,
        checkInterval: Duration = Duration.ofSeconds(5),
    ) = DesktopAutomaticBackupSchedule(
        minimumBackupInterval = minimumBackupInterval,
        maximumSnapshotCount = maximumSnapshotCount,
        checkInterval = checkInterval,
    )

    private fun createdResult(cleanupWarning: String? = null): AutomaticBackupExecutionResult.Created {
        val snapshot = AppDataSnapshot(
            name = "fixture-snapshot",
            directory = Path.of("fixture-snapshot"),
            createdAt = Instant.parse("2026-09-22T12:00:00Z"),
            purpose = SnapshotPurpose.AUTOMATIC,
            validation = SnapshotValidation.VALID,
        )
        return AutomaticBackupExecutionResult.Created(
            snapshot = snapshot,
            pruneResult = AutomaticSnapshotPruneResult(
                prunedSnapshotNames = emptyList(),
                cleanupWarnings = listOfNotNull(cleanupWarning),
            ),
        )
    }
}
