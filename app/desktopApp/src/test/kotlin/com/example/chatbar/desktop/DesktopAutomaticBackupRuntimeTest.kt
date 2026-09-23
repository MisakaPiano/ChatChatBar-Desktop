package com.example.chatbar.desktop

import com.example.chatbar.data.snapshot.AppDataSnapshot
import com.example.chatbar.data.snapshot.AutomaticBackupExecutionResult
import com.example.chatbar.data.snapshot.AutomaticSnapshotPruneResult
import com.example.chatbar.data.snapshot.SnapshotPurpose
import com.example.chatbar.data.snapshot.SnapshotValidation
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopAutomaticBackupRuntimeTest {
    @Test
    fun `construction performs no writes and does not start scheduler`() = runTest {
        withTemporaryParent { root ->
            val executions = AtomicInteger()
            val fixture = runtimeFixture(root) { _, _ ->
                executions.incrementAndGet()
                AutomaticBackupExecutionResult.SkippedNotDue
            }

            assertFalse(Files.exists(root))
            assertFalse(fixture.runtime.state.value.schedulerRunning)
            assertFalse(fixture.scheduler.isRunning)
            assertEquals(0, executions.get())
            fixture.runtime.close()
            assertFalse(Files.exists(root))
        }
    }

    @Test
    fun `missing settings use defaults without writing or scheduling`() = runTest {
        withTemporaryParent { root ->
            val fixture = runtimeFixture(root)

            val state = fixture.runtime.initialize()

            assertIs<DesktopSettingsLoadResult.Missing>(state.settingsLoadResult)
            assertEquals(DesktopSettings(), state.effectiveSettings)
            assertFalse(state.schedulerRunning)
            assertFalse(Files.exists(root))
            fixture.runtime.close()
        }
    }

    @Test
    fun `valid disabled settings do not start scheduler`() = runTest {
        withTemporaryParent { root ->
            saveSettings(root, DesktopSettings())
            val fixture = runtimeFixture(root)

            val state = fixture.runtime.initialize()

            assertIs<DesktopSettingsLoadResult.Loaded>(state.settingsLoadResult)
            assertFalse(state.schedulerRunning)
            fixture.runtime.close()
        }
    }

    @Test
    fun `valid enabled settings start exact schedule once`() = runTest {
        withTemporaryParent { root ->
            val settings = enabledSettings(
                minimumInterval = Duration.ZERO,
                maximumCount = 4,
                checkInterval = Duration.ofMinutes(30),
            )
            saveSettings(root, settings)
            val calls = mutableListOf<Pair<Duration, Int>>()
            val fixture = runtimeFixture(root) { interval, count ->
                calls += interval to count
                AutomaticBackupExecutionResult.SkippedNotDue
            }

            fixture.runtime.initialize()
            assertTrue(fixture.runtime.state.value.schedulerRunning)
            runCurrent()
            assertEquals(listOf(Duration.ZERO to 4), calls)
            advanceTimeBy(Duration.ofMinutes(30).toMillis() - 1)
            runCurrent()
            assertEquals(1, calls.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, calls.size)
            fixture.runtime.close()
        }
    }

    @Test
    fun `corrupt invalid and unsupported settings stop scheduling and expose failure`() = runTest {
        val documents = listOf(
            "{ malformed" to DesktopSettingsLoadResult.Corrupt::class,
            validJson(minimumInterval = "-PT1H") to DesktopSettingsLoadResult.Invalid::class,
            validJson(formatVersion = 2) to DesktopSettingsLoadResult.UnsupportedFormatVersion::class,
        )
        documents.forEach { (text, expectedType) ->
            withTemporaryParent { root ->
                Files.createDirectories(root)
                val path = root.resolve(DesktopSettingsStore.SETTINGS_FILE_NAME)
                path.writeText(text)
                val original = path.readBytes()
                val fixture = runtimeFixture(root)

                val state = fixture.runtime.initialize()

                assertTrue(expectedType.isInstance(state.settingsLoadResult))
                assertNull(state.effectiveSettings)
                assertFalse(state.schedulerRunning)
                assertContentEquals(original, path.readBytes())
                fixture.runtime.close()
            }
        }
    }

    @Test
    fun `scheduler skipped event updates runtime state`() = runTest {
        withTemporaryParent { root ->
            saveSettings(root, enabledSettings())
            val fixture = runtimeFixture(root)

            fixture.runtime.initialize()
            runCurrent()

            assertIs<DesktopAutomaticBackupEvent.SkippedNotDue>(fixture.runtime.state.value.latestEvent)
            assertNull(fixture.runtime.state.value.latestExecutionFailure)
            fixture.runtime.close()
        }
    }

    @Test
    fun `created event preserves cleanup warnings in runtime state`() = runTest {
        withTemporaryParent { root ->
            saveSettings(root, enabledSettings())
            val fixture = runtimeFixture(root) { _, _ ->
                createdResult("retained quarantine fixture")
            }

            fixture.runtime.initialize()
            runCurrent()

            assertIs<DesktopAutomaticBackupEvent.Created>(fixture.runtime.state.value.latestEvent)
            assertEquals(
                listOf("retained quarantine fixture"),
                fixture.runtime.state.value.latestCleanupWarnings,
            )
            fixture.runtime.close()
        }
    }

    @Test
    fun `failed event exposes execution failure`() = runTest {
        withTemporaryParent { root ->
            saveSettings(root, enabledSettings())
            val failure = IOException("execution fixture")
            val fixture = runtimeFixture(root) { _, _ -> throw failure }

            fixture.runtime.initialize()
            runCurrent()

            val event = assertIs<DesktopAutomaticBackupEvent.Failed>(
                fixture.runtime.state.value.latestEvent,
            )
            assertEquals(failure, event.error)
            assertEquals(failure, fixture.runtime.state.value.latestExecutionFailure)
            fixture.runtime.close()
        }
    }

    @Test
    fun `apply stops scheduler before save and disabled settings remain stopped`() = runTest {
        withTemporaryParent { root ->
            val initial = enabledSettings()
            saveSettings(root, initial)
            lateinit var scheduler: DesktopAutomaticBackupScheduler
            val coordinator = DesktopDataOperationCoordinator()
            val store = DesktopSettingsStore(
                appDataRoot = root,
                operationGate = coordinator,
                temporaryId = { "ordered" },
                replaceFile = { temporary, target ->
                    assertFalse(scheduler.isRunning)
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                },
            )
            val runtime = DesktopAutomaticBackupRuntime(store, coordinator) { sink ->
                DesktopAutomaticBackupScheduler(
                    executeAutomaticBackup = { _, _ -> AutomaticBackupExecutionResult.SkippedNotDue },
                    dispatcher = StandardTestDispatcher(testScheduler),
                    eventSink = sink,
                ).also { scheduler = it }
            }
            runtime.initialize()
            runCurrent()

            val result = runtime.applySettings(DesktopSettings())

            assertIs<DesktopSettingsApplyResult.Applied>(result)
            assertFalse(runtime.state.value.schedulerRunning)
            assertFalse(scheduler.isRunning)
            val loaded = assertIs<DesktopSettingsLoadResult.Loaded>(
                DesktopSettingsStore(root, DesktopDataOperationCoordinator()).load(),
            )
            assertFalse(loaded.document.settings.automaticBackup.enabled)
            runtime.close()
        }
    }

    @Test
    fun `apply enabled settings restarts with exact values`() = runTest {
        withTemporaryParent { root ->
            val calls = mutableListOf<Pair<Duration, Int>>()
            val fixture = runtimeFixture(root) { interval, count ->
                calls += interval to count
                AutomaticBackupExecutionResult.SkippedNotDue
            }
            fixture.runtime.initialize()
            val settings = enabledSettings(
                minimumInterval = Duration.ofHours(6),
                maximumCount = 11,
                checkInterval = Duration.ofMinutes(20),
            )

            assertIs<DesktopSettingsApplyResult.Applied>(fixture.runtime.applySettings(settings))
            runCurrent()

            assertEquals(listOf(Duration.ofHours(6) to 11), calls)
            assertEquals(settings, fixture.runtime.state.value.effectiveSettings)
            assertTrue(fixture.runtime.state.value.schedulerRunning)
            fixture.runtime.close()
        }
    }

    @Test
    fun `save failure preserves file and restarts previous working schedule`() = runTest {
        withTemporaryParent { root ->
            val previous = enabledSettings(
                minimumInterval = Duration.ofHours(8),
                maximumCount = 5,
            )
            saveSettings(root, previous)
            val path = root.resolve(DesktopSettingsStore.SETTINGS_FILE_NAME)
            val originalBytes = path.readBytes()
            val coordinator = DesktopDataOperationCoordinator()
            val failingStore = DesktopSettingsStore(
                appDataRoot = root,
                operationGate = coordinator,
                temporaryId = { "failure" },
                replaceFile = { _, _ -> throw IOException("save fixture failure") },
            )
            val calls = mutableListOf<Pair<Duration, Int>>()
            val runtime = DesktopAutomaticBackupRuntime(failingStore, coordinator) { sink ->
                DesktopAutomaticBackupScheduler(
                    executeAutomaticBackup = { interval, count ->
                        calls += interval to count
                        AutomaticBackupExecutionResult.SkippedNotDue
                    },
                    dispatcher = StandardTestDispatcher(testScheduler),
                    eventSink = sink,
                )
            }
            runtime.initialize()
            runCurrent()

            val result = runtime.applySettings(DesktopSettings())
            runCurrent()

            val failure = assertIs<DesktopSettingsApplyResult.Failed>(result).failure
            assertEquals(DesktopAutomaticBackupRuntimeFailureStage.SAVE, failure.stage)
            assertNull(failure.schedulerRestartError)
            assertContentEquals(originalBytes, path.readBytes())
            assertEquals(previous, runtime.state.value.effectiveSettings)
            assertTrue(runtime.state.value.schedulerRunning)
            assertEquals(
                listOf(Duration.ofHours(8) to 5, Duration.ofHours(8) to 5),
                calls,
            )
            runtime.close()
        }
    }

    @Test
    fun `concurrent initialize calls create only one scheduler loop`() = runTest {
        withTemporaryParent { root ->
            saveSettings(root, enabledSettings())
            val executions = AtomicInteger()
            val fixture = runtimeFixture(root) { _, _ ->
                executions.incrementAndGet()
                AutomaticBackupExecutionResult.SkippedNotDue
            }

            listOf(
                async { fixture.runtime.initialize() },
                async { fixture.runtime.initialize() },
            ).awaitAll()
            runCurrent()

            assertEquals(1, executions.get())
            assertTrue(fixture.runtime.state.value.schedulerRunning)
            fixture.runtime.close()
        }
    }

    @Test
    fun `concurrent settings applications are serialized`() = runTest {
        withTemporaryParent { root ->
            val fixture = runtimeFixture(root)
            fixture.runtime.initialize()
            val first = enabledSettings(
                minimumInterval = Duration.ofHours(2),
                maximumCount = 2,
            )
            val second = DesktopSettings(
                automaticBackup = DesktopAutomaticBackupSettings(
                    enabled = false,
                    minimumBackupInterval = Duration.ofHours(3),
                    maximumSnapshotCount = 3,
                    checkInterval = Duration.ofMinutes(30),
                ),
            )

            listOf(
                async { fixture.runtime.applySettings(first) },
                async { fixture.runtime.applySettings(second) },
            ).awaitAll()
            runCurrent()

            assertEquals(second, fixture.runtime.state.value.effectiveSettings)
            assertFalse(fixture.runtime.state.value.schedulerRunning)
            val loaded = assertIs<DesktopSettingsLoadResult.Loaded>(
                DesktopSettingsStore(root, DesktopDataOperationCoordinator()).load(),
            )
            assertEquals(second, loaded.document.settings)
            fixture.runtime.close()
        }
    }

    @Test
    fun `maintenance pause waits for running backup and blocks settings restart`() = runTest {
        withTemporaryParent { root ->
            saveSettings(root, enabledSettings())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture = runtimeFixture(root) { _, _ ->
                entered.complete(Unit)
                release.await()
                AutomaticBackupExecutionResult.SkippedNotDue
            }
            fixture.runtime.initialize()
            runCurrent()
            entered.await()

            val pause = async { fixture.runtime.pauseForMaintenance() }
            runCurrent()
            assertFalse(pause.isCompleted)
            release.complete(Unit)
            assertIs<DesktopMaintenancePauseResult.Paused>(pause.await())
            assertEquals(
                DesktopAutomaticBackupRuntimeMode.MAINTENANCE_PAUSED,
                fixture.runtime.state.value.mode,
            )
            assertFalse(fixture.scheduler.isRunning)

            val before = root.resolve(DesktopSettingsStore.SETTINGS_FILE_NAME).readBytes()
            val apply = fixture.runtime.applySettings(DesktopSettings())
            assertEquals(
                DesktopAutomaticBackupRuntimeFailureStage.MAINTENANCE_PAUSED,
                assertIs<DesktopSettingsApplyResult.Failed>(apply).failure.stage,
            )
            assertContentEquals(before, root.resolve(DesktopSettingsStore.SETTINGS_FILE_NAME).readBytes())
            assertFalse(fixture.scheduler.isRunning)
            fixture.runtime.close()
        }
    }

    @Test
    fun `cancelled maintenance pause stabilizes runtime and restores prior scheduler`() = runTest {
        withTemporaryParent { root ->
            saveSettings(root, enabledSettings())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture = runtimeFixture(root) { _, _ ->
                entered.complete(Unit)
                release.await()
                AutomaticBackupExecutionResult.SkippedNotDue
            }
            fixture.runtime.initialize()
            runCurrent()
            entered.await()

            val pause = async { fixture.runtime.pauseForMaintenance() }
            runCurrent()
            assertEquals(
                DesktopAutomaticBackupRuntimeMode.MAINTENANCE_PAUSED,
                fixture.runtime.state.value.mode,
            )
            pause.cancel()
            runCurrent()
            assertFalse(pause.isCompleted)

            release.complete(Unit)
            runCurrent()
            assertFailsWith<CancellationException> { pause.await() }
            runCurrent()

            assertEquals(DesktopAutomaticBackupRuntimeMode.ACTIVE, fixture.runtime.state.value.mode)
            assertTrue(fixture.scheduler.isRunning)
            assertTrue(fixture.runtime.state.value.schedulerRunning)
            assertEquals(DesktopDataOperationCoordinatorState.OPEN, fixture.coordinator.state)
            fixture.runtime.close()
        }
    }

    @Test
    fun `pause joins coordinated scheduler work before caller requests exclusive maintenance`() = runTest {
        withTemporaryParent { root ->
            saveSettings(root, enabledSettings())
            val coordinator = DesktopDataOperationCoordinator()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store = DesktopSettingsStore(root, coordinator)
            val runtime = DesktopAutomaticBackupRuntime(store, coordinator) { sink ->
                DesktopAutomaticBackupScheduler(
                    executeAutomaticBackup = { _, _ ->
                        coordinator.withExclusiveMaintenance {
                            entered.complete(Unit)
                            release.await()
                            AutomaticBackupExecutionResult.SkippedNotDue
                        }
                    },
                    dispatcher = StandardTestDispatcher(testScheduler),
                    eventSink = sink,
                )
            }
            runtime.initialize()
            runCurrent()
            entered.await()

            val pause = async { runtime.pauseForMaintenance() }
            runCurrent()
            assertFalse(pause.isCompleted)
            release.complete(Unit)
            assertIs<DesktopMaintenancePauseResult.Paused>(pause.await())

            var exclusiveAcquired = false
            coordinator.withExclusiveMaintenance { exclusiveAcquired = true }
            assertTrue(exclusiveAcquired)
            runtime.close()
        }
    }

    @Test
    fun `failed maintenance can resume prior schedule while disabled runtime stays stopped`() = runTest {
        withTemporaryParent { enabledRoot ->
            saveSettings(enabledRoot, enabledSettings())
            val enabled = runtimeFixture(enabledRoot)
            enabled.runtime.initialize()
            runCurrent()
            assertIs<DesktopMaintenancePauseResult.Paused>(enabled.runtime.pauseForMaintenance())
            assertIs<DesktopMaintenanceResumeResult.Resumed>(enabled.runtime.resumeAfterMaintenance())
            runCurrent()
            assertTrue(enabled.scheduler.isRunning)
            enabled.runtime.close()
        }
        withTemporaryParent { disabledRoot ->
            val disabled = runtimeFixture(disabledRoot)
            disabled.runtime.initialize()
            assertIs<DesktopMaintenancePauseResult.Paused>(disabled.runtime.pauseForMaintenance())
            assertIs<DesktopMaintenanceResumeResult.Resumed>(disabled.runtime.resumeAfterMaintenance())
            assertFalse(disabled.scheduler.isRunning)
            disabled.runtime.close()
        }
    }

    @Test
    fun `restart seal prevents maintenance resume and close remains safe`() = runTest {
        withTemporaryParent { root ->
            saveSettings(root, enabledSettings())
            val fixture = runtimeFixture(root)
            fixture.runtime.initialize()
            runCurrent()
            fixture.runtime.pauseForMaintenance()
            fixture.coordinator.withExclusiveMaintenance { requireRestart() }

            assertIs<DesktopMaintenanceResumeResult.RestartRequired>(
                fixture.runtime.resumeAfterMaintenance(),
            )
            assertEquals(
                DesktopAutomaticBackupRuntimeMode.RESTART_REQUIRED,
                fixture.runtime.state.value.mode,
            )
            assertFalse(fixture.scheduler.isRunning)
            fixture.runtime.close()
            assertEquals(DesktopAutomaticBackupRuntimeMode.CLOSED, fixture.runtime.state.value.mode)
        }
    }

    private fun TestScope.runtimeFixture(
        root: Path,
        execute: suspend (Duration, Int) -> AutomaticBackupExecutionResult = { _, _ ->
            AutomaticBackupExecutionResult.SkippedNotDue
        },
    ): RuntimeFixture {
        lateinit var scheduler: DesktopAutomaticBackupScheduler
        val coordinator = DesktopDataOperationCoordinator()
        val runtime = DesktopAutomaticBackupRuntime(
            DesktopSettingsStore(root, coordinator),
            coordinator,
        ) { sink ->
            DesktopAutomaticBackupScheduler(
                executeAutomaticBackup = execute,
                dispatcher = StandardTestDispatcher(testScheduler),
                eventSink = sink,
            ).also { scheduler = it }
        }
        return RuntimeFixture(runtime, scheduler, coordinator)
    }

    private suspend fun saveSettings(root: Path, settings: DesktopSettings): DesktopSettingsDocument {
        val store = DesktopSettingsStore(root, DesktopDataOperationCoordinator())
        val missing = assertIs<DesktopSettingsLoadResult.Missing>(store.load())
        return store.save(missing.document, settings)
    }

    private fun enabledSettings(
        minimumInterval: Duration = Duration.ofHours(24),
        maximumCount: Int = 7,
        checkInterval: Duration = Duration.ofHours(1),
    ) = DesktopSettings(
        automaticBackup = DesktopAutomaticBackupSettings(
            enabled = true,
            minimumBackupInterval = minimumInterval,
            maximumSnapshotCount = maximumCount,
            checkInterval = checkInterval,
        ),
    )

    private fun createdResult(cleanupWarning: String): AutomaticBackupExecutionResult.Created {
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
                cleanupWarnings = listOf(cleanupWarning),
            ),
        )
    }

    private suspend fun withTemporaryParent(block: suspend (Path) -> Unit) {
        val parent = Files.createTempDirectory("desktop-backup-runtime-")
        try {
            block(parent.resolve("app-data"))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    private fun validJson(
        formatVersion: Int = 1,
        minimumInterval: String = "PT24H",
    ): String =
        """
        {
          "formatVersion": $formatVersion,
          "automaticBackup": {
            "enabled": true,
            "minimumBackupInterval": "$minimumInterval",
            "maximumSnapshotCount": 7,
            "checkInterval": "PT1H"
          }
        }
        """.trimIndent()

    private data class RuntimeFixture(
        val runtime: DesktopAutomaticBackupRuntime,
        val scheduler: DesktopAutomaticBackupScheduler,
        val coordinator: DesktopDataOperationCoordinator,
    )
}
