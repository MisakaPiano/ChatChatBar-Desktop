package com.example.chatbar.lanSync

import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
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
import com.example.chatbar.domain.lanSync.LanSyncGroupPanelStage
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** Fresh cloud-only app UI; no remote delivery or process-death recovery claim. */
@OptIn(ExperimentalTestApi::class)
class LanSyncGroupAppUiControlTest {
    private val compose = createEmptyComposeRule()
    private var phase = "compose-rule"
    @get:Rule val guardedCompose: TestRule = TestRule { base, description ->
        object : Statement() {
            override fun evaluate() {
                try { compose.apply(base, description).evaluate() }
                catch (_: Throwable) { throw AssertionError("SYNTHETIC_GROUP_APP_UI_PHASE_FAILED:$phase") }
            }
        }
    }

    @Test fun createSaveLeaveAndReopenUsesRealAppOwnedGroup() {
        phase = "cloud-guard"
        var scenario: ActivityScenario<LanSyncActivity>? = null
        var sanitizedFailure: AssertionError? = null
        try {
            runBlocking {
                check(InstrumentationRegistry.getArguments().getString("lanSyncGroupUiFixture") == "true")
                check(Build.VERSION.SDK_INT == 26)
                val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
                check(app.packageName == "com.example.chatbar.lansyncnext")
                phase = "startup-and-fresh-group"
                await(60_000) {
                    check(!app.lanSyncRecoveryFailed)
                    app.lanSyncStartupReady
                }
                val owner = app.lanSyncOwner
                check(!owner.hasGroupEvidence())

                phase = "launch-real-group-page"
                val launched = ActivityScenario.launch<LanSyncActivity>(Intent(app, LanSyncActivity::class.java))
                scenario = launched
                val coordinator = owner.groupCoordinator
                await {
                    coordinator.state.value.let { !it.busy && it.stage == LanSyncGroupPanelStage.EMPTY }
                }
                compose.onNodeWithText("创建新设备组").performScrollTo().assertIsDisplayed().performClick()

                phase = "recovery-save-checkbox"
                await(60_000) {
                    coordinator.state.value.let {
                        !it.busy && it.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE && it.recoveryCode != null
                    }
                }
                // This synthetic code stays in memory; never include semantics trees or causes in reports.
                val recoveryCode = checkNotNull(coordinator.state.value.recoveryCode)
                compose.onNodeWithText("完成创建").performScrollTo().assertIsNotEnabled()
                compose.onNodeWithContentDescription("已妥善保存恢复码")
                    .performScrollTo().assertIsOff().performClick().assertIsOn()
                compose.onNodeWithText("完成创建").performScrollTo().assertIsEnabled().performClick()

                phase = "created-real-app-owned-group"
                await(60_000) {
                    coordinator.state.value.let {
                        !it.busy && it.stage == LanSyncGroupPanelStage.READY &&
                            it.devices.count { device -> device.thisDevice } == 1 && coordinator.view.value != null
                    }
                }
                assertNull(coordinator.state.value.error)
                assertTrue(owner.hasGroupEvidence())
                val initialProfile = checkNotNull(owner.groupEnrollment.profile())
                val group = initialProfile.trust.group
                val checkpoint = checkNotNull(coordinator.view.value?.reconciliation?.ledgerCheckpoint)
                val device = coordinator.state.value.devices.single { it.thisDevice }.id
                assertEquals(initialProfile.device, device)
                assertFalse(coordinator.state.value.managementUnlocked)
                assertNull(coordinator.state.value.recoveryCode)

                phase = "unlock-management-through-ui"
                // READY/locked has one editable field: the secure recovery-code input.
                compose.onNode(hasSetTextAction()).performScrollTo().performTextInput(recoveryCode)
                compose.onNodeWithText("解锁管理").performScrollTo().assertIsEnabled().performClick()
                await { !coordinator.state.value.busy && coordinator.state.value.managementUnlocked }
                assertNull(coordinator.state.value.error)

                phase = "on-stop-locks-management"
                launched.moveToState(Lifecycle.State.CREATED)
                await { !coordinator.state.value.managementUnlocked }
                launched.onActivity { assertEquals(Lifecycle.State.CREATED, it.lifecycle.currentState) }
                assertNull(coordinator.state.value.recoveryCode)
                assertNull(coordinator.state.value.setupBinding)
                assertNull(coordinator.state.value.invitation)
                assertNull(coordinator.state.value.peerConfirmation)
                assertNull(coordinator.state.value.revocation)

                phase = "repository-save-while-page-stopped"
                val beforePublication = coordinator.state.value.local.lastSuccessAt
                val id = "ccb-group-app-ui-control-${UUID.randomUUID()}"
                val unit = "character_cards/$id"
                val card = CharacterCard(id = id, name = "Synthetic app-owned group UI card",
                    momentsEnabled = false, createdAt = 1L, updatedAt = 1L)
                assertNull(app.characterRepository.getById(id))
                app.characterRepository.save(card)

                phase = "automatic-local-publication-while-page-stopped"
                // No retry/commit/upload action is invoked by this test. The real app loop must advance.
                await(120_000) {
                    val view = coordinator.view.value
                    val hasAuthenticatedCard = view?.reconciliation?.authenticatedVersions?.any { actual ->
                        actual.envelope.version?.let { version ->
                            version.group == group && version.author == device && version.changes[unit]?.valueHash != null
                        } == true
                    } == true
                    val publishedAt = coordinator.state.value.local.lastSuccessAt
                    hasAuthenticatedCard && publishedAt != null &&
                        (beforePublication == null || publishedAt > beforePublication)
                }
                assertEquals(card, app.characterRepository.getById(id))
                assertFalse(coordinator.state.value.managementUnlocked)
                assertNull(coordinator.state.value.recoveryCode)
                assertNull(coordinator.state.value.invitation)
                launched.onActivity { assertEquals(Lifecycle.State.CREATED, it.lifecycle.currentState) }

                phase = "resume-and-recreate-real-page"
                launched.moveToState(Lifecycle.State.RESUMED)
                launched.recreate()
                await {
                    coordinator.state.value.let { !it.busy && it.stage == LanSyncGroupPanelStage.READY } &&
                        coordinator.view.value?.reconciliation?.ledgerCheckpoint == checkpoint
                }
                compose.onNodeWithText("设备组同步").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("立即检查").performScrollTo().assertIsDisplayed().assertIsEnabled()
                compose.onNodeWithText("邀请设备").performScrollTo().assertIsNotEnabled()
                launched.onActivity {
                    assertEquals(Lifecycle.State.RESUMED, it.lifecycle.currentState)
                    assertFalse(it.isFinishing)
                    assertFalse(it.isDestroyed)
                }
                val reopenedProfile = checkNotNull(owner.groupEnrollment.profile())
                assertEquals(group, reopenedProfile.trust.group)
                assertEquals(initialProfile.trust, reopenedProfile.trust)
                assertEquals(device, reopenedProfile.device)
                assertEquals(device, coordinator.state.value.devices.single { it.thisDevice }.id)
                assertEquals(checkpoint, coordinator.view.value?.reconciliation?.ledgerCheckpoint)
                assertFalse(coordinator.state.value.managementUnlocked)
                assertNull(coordinator.state.value.recoveryCode)
                assertNull(coordinator.state.value.setupBinding)
                assertNull(coordinator.state.value.invitation)
                assertNull(coordinator.state.value.error)
                assertNotNull(coordinator.state.value.local.lastSuccessAt)
                assertEquals(card, app.characterRepository.getById(id))
            }
        } catch (_: Throwable) {
            // Compose failure messages can contain the displayed recovery code. Do not chain the cause.
            sanitizedFailure = AssertionError("SYNTHETIC_GROUP_APP_UI_PHASE_FAILED:$phase")
        } finally {
            try { scenario?.close() }
            catch (_: Throwable) {
                if (sanitizedFailure == null) {
                    phase = "close-page"
                    sanitizedFailure = AssertionError("SYNTHETIC_GROUP_APP_UI_PHASE_FAILED:$phase")
                }
            }
        }
        sanitizedFailure?.let { throw it }
    }

    private suspend fun await(timeoutMillis: Long = 30_000, condition: () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(100)
        }
    }
}
