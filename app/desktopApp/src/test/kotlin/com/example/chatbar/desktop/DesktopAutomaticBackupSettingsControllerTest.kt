package com.example.chatbar.desktop

import com.example.chatbar.data.snapshot.AutomaticBackupExecutionResult
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopAutomaticBackupSettingsControllerTest {
    @Test fun `default disabled stopped and read-only construction observation`() = runTest {
        fixture { f ->
            val status = desktopBackupStatus(f.controller.state.value.runtime)
            assertEquals(DesktopUiText.BACKUP_DISABLED, status.enabled)
            assertEquals(DesktopUiText.BACKUP_STOPPED, status.scheduler)
            assertEquals(DesktopUiText.BACKUP_NO_EVENT, status.event)
            assertFalse(f.controller.state.value.dirty)
            assertFalse(f.controller.state.value.canApply)
            assertFalse(Files.exists(f.root))
        }
    }

    @Test fun `editing and reopening observation keep draft without writes or scheduler changes`() = runTest {
        fixture { f ->
            f.controller.edit { it.copy(enabled = true, maximumCount = "31", minimumInterval = "30m") }
            val draft = f.controller.state.value.draft
            f.observer.cancelAndJoin()
            val reopened = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { f.controller.observe() }
            assertEquals(draft, f.controller.state.value.draft)
            assertTrue(f.controller.state.value.dirty)
            assertFalse(Files.exists(f.root))
            assertFalse(f.runtime.state.value.schedulerRunning)
            assertEquals(0, f.executions)
            reopened.cancelAndJoin()
        }
    }

    @Test fun `enable Apply starts real runtime and disable Apply stops it`() = runTest {
        fixture { f ->
            f.controller.edit { it.copy(enabled = true, minimumInterval = "0m", maximumCount = "101") }
            f.controller.apply()
            runCurrent()
            val state = f.controller.state.value
            assertFalse(state.dirty)
            assertEquals(DesktopUiText.BACKUP_APPLIED, state.notice)
            assertEquals(DesktopUiText.BACKUP_ENABLED, desktopBackupStatus(state.runtime).enabled)
            assertEquals(DesktopUiText.BACKUP_RUNNING, desktopBackupStatus(state.runtime).scheduler)
            assertEquals(1, f.executions)
            assertEquals(101, f.loaded().automaticBackup.maximumSnapshotCount)
            assertEquals(Duration.ZERO, f.loaded().automaticBackup.minimumBackupInterval)
            f.controller.edit { it.copy(enabled = false) }
            f.controller.apply()
            runCurrent()
            assertFalse(f.runtime.state.value.schedulerRunning)
            assertFalse(f.loaded().automaticBackup.enabled)
            assertFalse(f.controller.state.value.dirty)
        }
    }

    @Test fun `failed atomic save retains exact draft and old durable settings`() = runTest {
        fixture { f ->
            f.store.updateLatest { it }
            val before = Files.readAllBytes(f.store.settingsPath)
            f.failSave = true
            f.controller.edit { it.copy(enabled = true, minimumInterval = "3d") }
            val draft = f.controller.state.value.draft
            f.controller.apply()
            runCurrent()
            assertEquals(draft, f.controller.state.value.draft)
            assertTrue(f.controller.state.value.dirty)
            assertFalse(f.controller.state.value.busy)
            assertEquals(DesktopUiText.BACKUP_APPLY_FAILED, f.controller.state.value.notice)
            assertEquals(DesktopUiText.BACKUP_SAVE_FAILED, desktopBackupStatus(f.runtime.state.value).operationFailure)
            assertContentEquals(before, Files.readAllBytes(f.store.settingsPath))
        }
    }

    @Test fun `saved but scheduler start failure is not presented as Apply success`() = runTest {
        fixture { f ->
            f.scheduler.close()
            f.controller.edit { it.copy(enabled = true) }
            val draft = f.controller.state.value.draft
            f.controller.apply()
            runCurrent()
            assertTrue(f.loaded().automaticBackup.enabled)
            assertFalse(f.runtime.state.value.schedulerRunning)
            assertEquals(draft, f.controller.state.value.draft)
            assertTrue(f.controller.state.value.dirty)
            assertEquals(DesktopUiText.BACKUP_APPLY_FAILED, f.controller.state.value.notice)
            val status = desktopBackupStatus(f.controller.state.value.runtime)
            assertEquals(DesktopUiText.BACKUP_ENABLED, status.enabled)
            assertEquals(DesktopUiText.BACKUP_STOPPED, status.scheduler)
            assertEquals(DesktopUiText.BACKUP_START_FAILED, status.operationFailure)
        }
    }

    @Test fun `invalid inputs cannot bypass controller Apply guard`() = runTest {
        fixture { f ->
            for (bad in listOf<(DesktopBackupSettingsDraft) -> DesktopBackupSettingsDraft>(
                { it.copy(minimumInterval = "-1h") }, { it.copy(checkInterval = "0m") },
                { it.copy(checkInterval = "-PT1S") }, { it.copy(maximumCount = "0") },
                { it.copy(maximumCount = "bad") }, { it.copy(minimumInterval = "bad") },
            )) {
                f.controller.discard()
                f.controller.edit(bad)
                assertFalse(f.controller.state.value.canApply)
                f.controller.apply()
                assertFalse(Files.exists(f.root))
            }
        }
    }

    @Test fun `maintenance mode rejected in controller even without UI observation update`() = runTest {
        fixture { f ->
            f.controller.edit { it.copy(maximumCount = "9") }
            f.runtime.pauseForMaintenance()
            f.controller.apply()
            runCurrent()
            assertFalse(f.controller.state.value.canApply)
            assertTrue(f.controller.state.value.dirty)
            assertFalse(Files.exists(f.root))
            assertEquals(DesktopUiText.BACKUP_MAINTENANCE, desktopBackupStatus(f.runtime.state.value).mode)
        }
    }

    @Test fun `restart required rejects Apply and preserves draft`() = runTest {
        fixture { f ->
            f.controller.edit { it.copy(maximumCount = "9") }
            f.runtime.pauseForMaintenance()
            f.coordinator.withExclusiveMaintenance { requireRestart() }
            f.runtime.resumeAfterMaintenance()
            f.controller.apply()
            runCurrent()
            assertFalse(f.controller.state.value.canApply)
            assertTrue(f.controller.state.value.dirty)
            assertFalse(Files.exists(f.root))
            assertEquals(DesktopUiText.BACKUP_RESTART, desktopBackupStatus(f.runtime.state.value).mode)
        }
    }

    @Test fun `NEW and CLOSED neither initialize nor write on controller actions`() = runTest {
        fixture(initialize = false) { f ->
            assertNull(f.controller.state.value.draft)
            f.controller.edit { it.copy(enabled = true) }
            f.controller.apply()
            assertEquals(DesktopAutomaticBackupRuntimeMode.NEW, f.runtime.state.value.mode)
            assertFalse(Files.exists(f.root))
            f.runtime.initialize(); runCurrent()
            f.controller.edit { it.copy(enabled = true) }
            f.runtime.close(); runCurrent()
            f.controller.apply()
            assertFalse(f.controller.state.value.canApply)
            assertEquals(DesktopUiText.BACKUP_CLOSED, desktopBackupStatus(f.runtime.state.value).mode)
            assertFalse(Files.exists(f.root))
        }
    }

    @Test fun `corrupt invalid unsupported settings cannot be overwritten`() = runTest {
        for (seed in listOf("{broken", json(minimum = "-PT1H"), json(version = 2))) {
            fixture(seed = seed) { f ->
                val before = Files.readAllBytes(f.store.settingsPath)
                assertNull(f.controller.state.value.draft)
                assertNotNull(desktopBackupStatus(f.runtime.state.value).loadFailure)
                f.controller.edit { it.copy(enabled = true) }
                f.controller.apply()
                f.controller.discard()
                assertFalse(f.controller.state.value.canApply)
                assertFalse(f.runtime.state.value.schedulerRunning)
                assertContentEquals(before, Files.readAllBytes(f.store.settingsPath))
            }
        }
    }

    @Test fun `unusual durations round trip through Apply without quantization`() = runTest {
        fixture(seed = json(minimum = "PT0.000000001S", check = "PT1.234567891S")) { f ->
            val original = f.loaded().automaticBackup
            val before = Files.readAllBytes(f.store.settingsPath)
            assertEquals(original, f.controller.state.value.draft?.settings())
            assertContentEquals(before, Files.readAllBytes(f.store.settingsPath))
            f.controller.edit { it.copy(maximumCount = "2147483647") }
            f.controller.apply()
            assertEquals(original.copy(maximumSnapshotCount = Int.MAX_VALUE), f.loaded().automaticBackup)
        }
    }

    @Test fun `backup controller Apply preserves language and color latest owner updates`() = runTest {
        val parent = Files.createTempDirectory("backup-ui-owner-")
        val root = parent.resolve("data")
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(root,
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() })
        try {
            container.automaticBackupRuntime.initialize()
            val controller = DesktopAutomaticBackupSettingsController(container.automaticBackupRuntime, backgroundScope)
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { controller.observe() }
            controller.edit { it.copy(maximumCount = "33") }
            container.uiLanguageController.select(DesktopUiLanguage.EN)
            container.appearanceController.setColorStyle(DesktopColorStyle.CCB_NATIVE)
            controller.apply()
            val loaded = assertIs<DesktopSettingsLoadResult.Loaded>(
                DesktopSettingsStore(root, DesktopDataOperationCoordinator()).load()).document.settings
            assertEquals(33, loaded.automaticBackup.maximumSnapshotCount)
            assertEquals(DesktopUiLanguage.EN, loaded.uiLanguage)
            assertEquals(DesktopColorStyle.CCB_NATIVE, loaded.colorStyle)
            assertEquals(loaded, controller.state.value.runtime.effectiveSettings)
        } finally { container.close(); parent.toFile().deleteRecursively() }
    }

    @Test fun `busy rejects duplicate Apply edits and discard while real runtime drains execution`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture(execute = { entered.complete(Unit); release.await(); AutomaticBackupExecutionResult.SkippedNotDue }) { f ->
            f.controller.edit { it.copy(enabled = true) }; f.controller.apply()
            runCurrent(); entered.await()
            f.controller.edit { it.copy(enabled = false) }
            val apply = launch { f.controller.apply() }; runCurrent()
            assertTrue(f.controller.state.value.busy)
            f.controller.edit { it.copy(maximumCount = "80") }; f.controller.discard(); f.controller.apply()
            assertEquals("7", f.controller.state.value.draft?.maximumCount)
            release.complete(Unit); apply.join(); runCurrent()
            assertFalse(f.controller.state.value.busy)
            assertFalse(f.runtime.state.value.schedulerRunning)
            assertEquals(2, f.writes)
        }
    }

    @Test fun `discard uses actual runtime values without writes`() = runTest {
        fixture { f ->
            f.controller.edit { it.copy(minimumInterval = "6h") }
            f.controller.discard()
            assertFalse(f.controller.state.value.dirty)
            assertEquals("PT24H", f.controller.state.value.draft?.minimumInterval)
            assertFalse(Files.exists(f.root))
        }
    }

    @Test fun `Apply request outlives settings composition scope without replay on reopen`() = runTest {
        fixture { f ->
            f.controller.edit { it.copy(maximumCount = "17") }
            val viewScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            viewScope.launch { f.controller.requestApply() }.join()
            viewScope.cancel()
            val applied = f.controller.state.first { !it.busy && it.notice == DesktopUiText.BACKUP_APPLIED }
            assertFalse(applied.dirty)
            assertEquals(17, f.loaded().automaticBackup.maximumSnapshotCount)
            assertEquals(1, f.writes)
            f.controller.requestApply()
            runCurrent()
            assertEquals(1, f.writes)
        }
    }

    private inner class Fixture(val root: Path, scope: TestScope,
        execute: suspend () -> AutomaticBackupExecutionResult) {
        val coordinator = DesktopDataOperationCoordinator()
        var failSave = false
        var writes = 0
        var executions = 0
        val store = DesktopSettingsStore(root, coordinator, { "ui-test" }) { temporary, target ->
            if (failSave) throw IOException("fake-private-token")
            writes++
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
        lateinit var scheduler: DesktopAutomaticBackupScheduler
        val runtime = DesktopAutomaticBackupRuntime(store, coordinator) { sink ->
            DesktopAutomaticBackupScheduler({ _, _ -> executions++; execute() },
                StandardTestDispatcher(scope.testScheduler), sink).also { scheduler = it }
        }
        val controller = DesktopAutomaticBackupSettingsController(runtime, scope.backgroundScope)
        lateinit var observer: Job
        suspend fun loaded() = assertIs<DesktopSettingsLoadResult.Loaded>(store.load()).document.settings
    }

    private suspend fun TestScope.fixture(seed: String? = null, initialize: Boolean = true,
        execute: suspend () -> AutomaticBackupExecutionResult = { AutomaticBackupExecutionResult.SkippedNotDue },
        block: suspend (Fixture) -> Unit) {
        val parent = Files.createTempDirectory("backup-ui-")
        val root = parent.resolve("data")
        if (seed != null) { Files.createDirectories(root); Files.writeString(root.resolve("desktop-settings.json"), seed) }
        val f = Fixture(root, this, execute)
        f.observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { f.controller.observe() }
        try {
            if (initialize) { f.runtime.initialize(); runCurrent() }
            block(f)
        } finally { f.observer.cancelAndJoin(); f.runtime.close(); parent.toFile().deleteRecursively() }
    }

    private fun json(version: Int = 1, minimum: String = "PT24H", check: String = "PT1H") =
        """{"formatVersion":$version,"automaticBackup":{"enabled":false,"minimumBackupInterval":"$minimum","maximumSnapshotCount":7,"checkInterval":"$check"}}"""
}
