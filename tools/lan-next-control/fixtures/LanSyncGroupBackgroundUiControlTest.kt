package com.example.chatbar.lanSync

import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.lanSync.LanSyncGroupCoordinator
import com.example.chatbar.domain.lanSync.LanSyncGroupPanelStage
import com.example.chatbar.domain.lanSync.LanSyncGroupRuntimeAccess
import com.example.chatbar.domain.syncStore.SyncAuthenticatedGroupObject
import com.example.chatbar.domain.syncStore.SyncStoreId
import com.example.chatbar.domain.syncStore.SyncVersionState
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** Fresh API35+ cloud app only: real UI/service and live-process pause, not kill/Doze/remote proof. */
@OptIn(ExperimentalTestApi::class)
class LanSyncGroupBackgroundUiControlTest {
    private val compose = createEmptyComposeRule()
    private var phase = "compose-rule"

    @get:Rule val guardedCompose: TestRule = TestRule { base, description ->
        object : Statement() {
            override fun evaluate() {
                try { compose.apply(base, description).evaluate() }
                catch (_: Throwable) {
                    // Compose diagnostics may contain the temporary recovery code. Never retain a cause.
                    throw AssertionError("SYNTHETIC_GROUP_BACKGROUND_UI_PHASE_FAILED:$phase")
                }
            }
        }
    }

    @Test fun backgroundServicePauseSurvivesForegroundAndResumesExplicitly() {
        phase = "cloud-guard"
        var scenario: ActivityScenario<LanSyncActivity>? = null
        var sanitizedFailure: AssertionError? = null
        try {
            runBlocking {
                check(InstrumentationRegistry.getArguments().getString("lanSyncGroupBackgroundFixture") == "true")
                check(Build.VERSION.SDK_INT >= 35)
                val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
                check(app.packageName == "com.example.chatbar.lansyncnext")
                check(app.applicationInfo.targetSdkVersion >= 35)
                @Suppress("DEPRECATION")
                val installed = app.packageManager.getPackageInfo(app.packageName, 0)
                check(installed.longVersionCode == 83L)

                phase = "startup-and-fresh-group"
                await(60_000) {
                    check(!app.lanSyncRecoveryFailed)
                    app.lanSyncStartupReady
                }
                val owner = app.lanSyncOwner
                check(!owner.hasGroupEvidence())
                check(runningGroupService(app) == null)

                phase = "launch-and-create-through-real-ui"
                val launched = ActivityScenario.launch<LanSyncActivity>(Intent(app, LanSyncActivity::class.java))
                scenario = launched
                val coordinator = owner.groupCoordinator
                await { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.EMPTY }
                compose.onNodeWithText("创建新设备组").performScrollTo().assertIsDisplayed().performClick()

                phase = "save-recovery-code-checkbox"
                await {
                    coordinator.state.value.let {
                        !it.busy && it.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE && it.recoveryCode != null
                    }
                }
                // Synthetic secret stays in memory. No screenshots, trees, println or cause chaining.
                val recoveryCode = checkNotNull(coordinator.state.value.recoveryCode)
                compose.onNodeWithText("完成创建").performScrollTo().assertIsNotEnabled()
                compose.onNodeWithContentDescription("已妥善保存恢复码")
                    .performScrollTo().assertIsOff().performClick().assertIsOn()
                compose.onNodeWithText("完成创建").performScrollTo().assertIsEnabled().performClick()

                phase = "real-foreground-service-after-ui-creation"
                await {
                    coordinator.state.value.let {
                        !it.busy && it.stage == LanSyncGroupPanelStage.READY && !it.paused &&
                            it.devices.count { device -> device.thisDevice } == 1
                    } && coordinator.view.value != null && foregroundProtected(app)
                }
                check(coordinator.state.value.error == null)
                val originalProfile = checkNotNull(owner.groupEnrollment.profile())
                val group = originalProfile.trust.group
                val device = originalProfile.device
                val checkpoint = checkNotNull(coordinator.view.value?.reconciliation?.ledgerCheckpoint)
                check(device == coordinator.state.value.devices.single { it.thisDevice }.id)
                val access = checkNotNull(owner.groupEnrollment.runtimeAccess())
                check(access.trust.group == group && access.device == device)
                check(!coordinator.state.value.managementUnlocked)

                phase = "temporary-management-unlock-through-ui"
                compose.onNode(hasSetTextAction()).performScrollTo().performTextInput(recoveryCode)
                compose.onNodeWithText("解锁管理").performScrollTo().assertIsEnabled().performClick()
                await { !coordinator.state.value.busy && coordinator.state.value.managementUnlocked }
                check(coordinator.state.value.error == null)

                phase = "page-created-real-on-stop"
                launched.moveToState(Lifecycle.State.CREATED)
                await { !coordinator.state.value.managementUnlocked }
                launched.onActivity { check(it.lifecycle.currentState == Lifecycle.State.CREATED) }
                requireSecretsCleared(coordinator)
                check(foregroundProtected(app))

                phase = "first-real-repository-save-with-page-stopped"
                val first = CharacterCard(id = UUID.randomUUID().toString(),
                    name = "Synthetic foreground group background card", momentsEnabled = false,
                    createdAt = 1L, updatedAt = 1L)
                val firstUnit = "character_cards/" + first.id
                val firstFile = app.filesDir.toPath().resolve("entities/character_cards/" + first.id + ".json")
                check(app.characterRepository.getById(first.id) == null)
                val firstBeforePublication = coordinator.state.value.local.lastSuccessAt
                app.characterRepository.save(first)
                val firstRaw = Files.readAllBytes(firstFile)
                check(firstRaw.isNotEmpty())

                phase = "first-automatic-authentication-and-exact-local-receipt"
                val firstAuthenticated = awaitOwnPublication(coordinator, group, device, firstUnit)
                awaitExactLocalReceipt(access, firstAuthenticated)
                await {
                    val publishedAt = coordinator.state.value.local.lastSuccessAt
                    publishedAt != null && (firstBeforePublication == null || publishedAt > firstBeforePublication)
                }
                check(Files.readAllBytes(firstFile).contentEquals(firstRaw))
                check(app.characterRepository.getById(first.id) == first)
                check(foregroundProtected(app))
                launched.onActivity { check(it.lifecycle.currentState == Lifecycle.State.CREATED) }
                requireSecretsCleared(coordinator)

                phase = "stop-service-through-real-ui"
                launched.moveToState(Lifecycle.State.RESUMED)
                await { foregroundProtected(app) && !coordinator.state.value.busy }
                // The pinned background controls are outside the scrolling group body.
                // Match a clickable exact-text node, not another text label with the same wording.
                compose.onNode(hasText("停止设备组同步") and hasClickAction())
                    .assertIsDisplayed().assertIsEnabled().performClick()
                await {
                    owner.backgroundState.value.phase == AndroidLanSyncBackgroundPhase.PAUSED &&
                        !owner.backgroundState.value.foregroundProtected && coordinator.state.value.paused &&
                        !coordinator.state.value.busy && runningGroupService(app) == null
                }
                check(owner.backgroundState.value.error == null)
                requireSecretsCleared(coordinator)
                val pausedOperations = access.node.sourceOperations()

                phase = "second-save-while-user-paused-and-page-created"
                launched.moveToState(Lifecycle.State.CREATED)
                launched.onActivity { check(it.lifecycle.currentState == Lifecycle.State.CREATED) }
                val second = CharacterCard(id = UUID.randomUUID().toString(),
                    name = "Synthetic user-paused group background card", momentsEnabled = false,
                    createdAt = 2L, updatedAt = 2L)
                val secondUnit = "character_cards/" + second.id
                val secondFile = app.filesDir.toPath().resolve("entities/character_cards/" + second.id + ".json")
                check(app.characterRepository.getById(second.id) == null)
                app.characterRepository.save(second)
                val secondRaw = Files.readAllBytes(secondFile)
                check(secondRaw.isNotEmpty())

                phase = "paused-created-observation-includes-ten-second-poll"
                holdPausedFor(12_000) {
                    requirePaused(app, coordinator, secondUnit)
                    check(Files.readAllBytes(firstFile).contentEquals(firstRaw))
                    check(Files.readAllBytes(secondFile).contentEquals(secondRaw))
                }
                check(access.node.sourceOperations() == pausedOperations)

                phase = "real-foreground-and-recreate-cannot-clear-user-pause"
                launched.moveToState(Lifecycle.State.RESUMED)
                await { !coordinator.state.value.busy }
                launched.onActivity { check(it.lifecycle.currentState == Lifecycle.State.RESUMED) }
                requirePaused(app, coordinator, secondUnit)
                launched.recreate()
                await {
                    !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.READY
                }
                launched.onActivity {
                    check(it.lifecycle.currentState == Lifecycle.State.RESUMED)
                    check(!it.isFinishing && !it.isDestroyed)
                }
                compose.onNode(hasText("恢复设备组后台运行") and hasClickAction())
                    .assertIsDisplayed().assertIsEnabled()
                holdPausedFor(12_000) {
                    requirePaused(app, coordinator, secondUnit)
                    check(Files.readAllBytes(firstFile).contentEquals(firstRaw))
                    check(Files.readAllBytes(secondFile).contentEquals(secondRaw))
                }
                check(access.node.sourceOperations() == pausedOperations)
                check(owner.groupEnrollment.profile() == originalProfile)
                check(coordinator.state.value.devices.single { it.thisDevice }.id == device)
                check(coordinator.view.value?.reconciliation?.ledgerCheckpoint == checkpoint)
                check(app.characterRepository.getById(first.id) == first)
                check(app.characterRepository.getById(second.id) == second)

                phase = "explicit-resume-through-real-ui"
                compose.onNode(hasText("恢复设备组后台运行") and hasClickAction())
                    .assertIsDisplayed().assertIsEnabled().performClick()
                await {
                    foregroundProtected(app) && !coordinator.state.value.paused &&
                        !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.READY
                }

                phase = "second-automatic-authentication-and-exact-local-receipt"
                val secondAuthenticated = awaitOwnPublication(coordinator, group, device, secondUnit)
                awaitExactLocalReceipt(access, secondAuthenticated)
                check(owner.groupEnrollment.profile() == originalProfile)
                check(coordinator.state.value.devices.single { it.thisDevice }.id == device)
                check(coordinator.view.value?.reconciliation?.ledgerCheckpoint == checkpoint)
                check(coordinator.state.value.error == null)
                check(owner.backgroundState.value.error == null)
                check(foregroundProtected(app))
                check(Files.readAllBytes(firstFile).contentEquals(firstRaw))
                check(Files.readAllBytes(secondFile).contentEquals(secondRaw))
                check(app.characterRepository.getById(first.id) == first)
                check(app.characterRepository.getById(second.id) == second)
                requireSecretsCleared(coordinator)
            }
        } catch (_: Throwable) {
            sanitizedFailure = AssertionError("SYNTHETIC_GROUP_BACKGROUND_UI_PHASE_FAILED:$phase")
        } finally {
            try { scenario?.close() }
            catch (_: Throwable) {
                if (sanitizedFailure == null) {
                    phase = "close-page"
                    sanitizedFailure = AssertionError("SYNTHETIC_GROUP_BACKGROUND_UI_PHASE_FAILED:$phase")
                }
            }
        }
        sanitizedFailure?.let { throw it }
    }

    @Suppress("DEPRECATION")
    private fun runningGroupService(app: ChatBarApp): ActivityManager.RunningServiceInfo? =
        checkNotNull(app.getSystemService(ActivityManager::class.java)).getRunningServices(Int.MAX_VALUE)
            .filter { it.service.packageName == app.packageName &&
                it.service.className == LanSyncGroupForegroundService::class.java.name }
            .singleOrNull()

    private fun foregroundProtected(app: ChatBarApp): Boolean {
        val state = app.lanSyncOwner.backgroundState.value
        val service = runningGroupService(app)
        return state.phase == AndroidLanSyncBackgroundPhase.RUNNING && state.foregroundProtected &&
            service != null && service.foreground && service.started &&
            service.uid == app.applicationInfo.uid && service.pid == android.os.Process.myPid()
    }

    private fun requirePaused(app: ChatBarApp, coordinator: LanSyncGroupCoordinator, unit: String) {
        val state = app.lanSyncOwner.backgroundState.value
        check(state.phase == AndroidLanSyncBackgroundPhase.PAUSED && !state.foregroundProtected && state.error == null)
        check(coordinator.state.value.paused)
        check(runningGroupService(app) == null)
        check(coordinator.view.value?.reconciliation?.authenticatedVersions?.none {
            it.envelope.version?.changes?.containsKey(unit) == true
        } != false)
        requireSecretsCleared(coordinator)
    }

    private fun requireSecretsCleared(coordinator: LanSyncGroupCoordinator) {
        val state = coordinator.state.value
        check(!state.managementUnlocked && state.recoveryCode == null && state.setupBinding == null &&
            state.invitation == null && state.peerConfirmation == null && state.revocation == null)
    }

    private suspend fun awaitOwnPublication(coordinator: LanSyncGroupCoordinator, group: SyncStoreId,
        device: String, unit: String): SyncAuthenticatedGroupObject {
        var authenticated: SyncAuthenticatedGroupObject? = null
        await {
            val view = coordinator.view.value
            val candidate = if (view == null) null else view.reconciliation.authenticatedVersions.firstOrNull { actual ->
                val version = actual.envelope.version
                version != null && version.group == group && version.author == device &&
                    version.changes[unit]?.content != null && version.changes[unit]?.valueHash != null &&
                    view.reconciliation.history?.state(version.id) == SyncVersionState.ADMITTED &&
                    view.nativeState.units[unit]?.frontiers?.contains(version.reference()) == true
            }
            if (candidate != null && coordinator.state.value.local.lastSuccessAt != null) {
                authenticated = candidate
                true
            } else false
        }
        return checkNotNull(authenticated)
    }

    private suspend fun awaitExactLocalReceipt(access: LanSyncGroupRuntimeAccess, actual: SyncAuthenticatedGroupObject) {
        check(actual.envelope.version?.group == access.trust.group)
        check(actual.envelope.version?.author == access.device)
        await(30_000) {
            var found = false
            for (operation in access.node.sourceOperations()) {
                val receipt = access.node.receipt(operation, access.localTarget)
                if (receipt != null && receipt.target == access.localTarget && receipt.metadata == actual.metadata.descriptor) found = true
            }
            found
        }
    }

    private suspend fun holdPausedFor(durationMillis: Long, condition: () -> Unit) {
        val deadline = android.os.SystemClock.elapsedRealtime() + durationMillis
        do {
            condition()
            delay(200)
        } while (android.os.SystemClock.elapsedRealtime() < deadline)
        condition()
    }

    private suspend fun await(timeoutMillis: Long = 120_000, condition: suspend () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(100)
        }
    }
}
