package com.example.chatbar.lanSync

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.MomentTask
import com.example.chatbar.data.local.entity.MomentTaskStatus
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.lanSync.LanSyncOperationGate
import com.example.chatbar.domain.moment.MomentScheduler
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.moments.SyncedMomentPlanAction
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Only isolated cloud emulator roots, synthetic records and never a provider request. */
class LanSyncMomentsTest {
    @get:Rule val compose = createAndroidComposeRule<LanSyncActivity>()

    private class Fixture(val app: ChatBarApp) {
        val gate = LanSyncOperationGate()
        val root = Files.createTempDirectory(app.cacheDir.canonicalFile.toPath(), "moment-lan-").toRealPath()
        val storage = JsonFileStorage(root, gate)
        val settings = SettingsRepository(storage)
        val repository = MomentRepository(storage)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val scheduler = MomentScheduler(app, scope, settings, CharacterRepository(storage), ChatRepository(storage),
            repository, app.effectiveModelResolver, app.momentGenerationService, gate)
        fun task(id: String, status: MomentTaskStatus = MomentTaskStatus.PENDING) =
            MomentTask(id, "synthetic-missing-card", "synthetic-missing-session", 1, status = status,
                requiresLocalScheduleApproval = true, createdAt = 1, updatedAt = 1)
    }
    private suspend fun fixture(): Fixture {
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        withTimeout(60000) { while (!app.lanSyncStartupReady) {
            check(!app.lanSyncRecoveryFailed); delay(100)
        } }
        return Fixture(app).also { it.settings.initialize() }
    }

    @Test fun markedDuePlansStayDormantAndExactReviewRejectsLaterChanges() = runBlocking {
        val f = fixture()
        try {
            f.settings.updateAppSettings { it.copy(momentsEnabled = true) }
            val pending = f.task("synthetic-pending")
            val running = f.task("synthetic-running", MomentTaskStatus.RUNNING)
            val native = f.task("synthetic-native").copy(requiresLocalScheduleApproval = false)
            listOf(pending, running, native).forEach { f.repository.saveTask(it) }
            f.scheduler.runOnce("synthetic-dormant", 2)
            val stored = f.repository.getAllTasks().associateBy { it.id }
            assertEquals(pending, stored[pending.id]); assertEquals(running, stored[running.id])
            assertEquals(MomentTaskStatus.SKIPPED, stored.getValue(native.id).status)
            val reviewed = f.scheduler.syncedPendingPlans()
            assertEquals(listOf(pending), reviewed)
            val changed = pending.copy(scheduledAt = 3)
            f.repository.saveTask(changed)
            assertTrue(runCatching { f.scheduler.approveSyncedPlans(reviewed) }.isFailure)
            assertEquals(changed, f.repository.getAllTasks().single { it.id == pending.id })
            assertEquals(1, f.scheduler.approveSyncedPlans(listOf(changed)))
            assertFalse(f.repository.getAllTasks().single { it.id == pending.id }.requiresLocalScheduleApproval)
            assertEquals(running, f.repository.getAllTasks().single { it.id == running.id })
        } finally { f.scope.cancel(); f.scope.coroutineContext[Job]!!.join() }
    }

    @Test fun actualSchedulerReservesBeforeStorageAndRejectsInverseLockOrder() = runBlocking {
        withTimeout(10000) {
            val f = fixture()
            try {
                val normalEntered = CompletableDeferred<Unit>()
                val maintenanceEntered = CompletableDeferred<Unit>()
                val releaseNormal = CompletableDeferred<Unit>()
                val reader = async {
                    f.gate.withNormalOperation {
                        normalEntered.complete(Unit); maintenanceEntered.await()
                        assertEquals("SYNC_GATE_UPGRADE", runCatching { f.scheduler.syncedPendingPlans() }.exceptionOrNull()?.message)
                        releaseNormal.await()
                    }
                }
                normalEntered.await()
                val applied = CompletableDeferred<Unit>()
                val maintenance = async(start = CoroutineStart.UNDISPATCHED) {
                    f.scheduler.withLanSyncMaintenance {
                        maintenanceEntered.complete(Unit)
                        f.gate.exclusive { applied.complete(Unit) }
                    }
                }
                assertFalse(applied.isCompleted)
                releaseNormal.complete(Unit); reader.await(); maintenance.await()
                assertTrue(applied.isCompleted)
                val pending = f.task("synthetic-reviewed")
                f.repository.saveTask(pending)
                val reviewing = CompletableDeferred<Unit>(); val releaseApply = CompletableDeferred<Unit>()
                val sync = async(start = CoroutineStart.UNDISPATCHED) {
                    f.scheduler.withLanSyncMaintenance {
                        f.gate.exclusive {
                            reviewing.complete(Unit); releaseApply.await()
                            f.repository.saveTask(pending.copy(scheduledAt = 99))
                        }
                    }
                }
                reviewing.await()
                val approval = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.scheduler.approveSyncedPlans(listOf(pending)) } }
                assertFalse(approval.isCompleted)
                releaseApply.complete(Unit); sync.await()
                assertTrue(approval.await().isFailure)
                assertTrue(f.repository.getAllTasks().single().requiresLocalScheduleApproval)
            } finally { f.scope.cancel(); f.scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test fun productionApprovalActionRequiresConfirmationAndPreservesInterruptedRecords() {
        val f = runBlocking { fixture() }
        try {
            val pending = f.task("synthetic-ui-pending")
            val running = f.task("synthetic-ui-running", MomentTaskStatus.RUNNING)
            runBlocking { f.repository.saveTask(pending); f.repository.saveTask(running) }
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent { ChatBarTheme { SyncedMomentPlanAction(f.scheduler) } }
            }
            compose.onNodeWithText("同步计划").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("仅本机恢复 1 项").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("仅本机恢复 1 项").assertIsDisplayed()
            compose.onNodeWithText("关闭").performClick()
            assertTrue(runBlocking { f.repository.getAllTasks().single { it.id == pending.id }.requiresLocalScheduleApproval })
            compose.onNodeWithText("同步计划").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("仅本机恢复 1 项").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("仅本机恢复 1 项").performClick()
            compose.waitUntil(10000) { runBlocking { !f.repository.getAllTasks().single { it.id == pending.id }.requiresLocalScheduleApproval } }
            assertEquals(running, runBlocking { f.repository.getAllTasks().single { it.id == running.id } })
        } finally { runBlocking { f.scope.cancel(); f.scope.coroutineContext[Job]!!.join() } }
    }
}
