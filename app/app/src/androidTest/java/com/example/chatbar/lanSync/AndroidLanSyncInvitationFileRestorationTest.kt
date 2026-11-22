package com.example.chatbar.lanSync

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.domain.lanSync.LanSyncGroupCoordinator
import com.example.chatbar.domain.lanSync.LanSyncGroupEnrollment
import com.example.chatbar.domain.lanSync.LanSyncGroupPanelStage
import com.example.chatbar.domain.lanSync.LanSyncNetwork
import com.example.chatbar.domain.lanSync.LanSyncPeer
import com.example.chatbar.domain.lanSync.LanSyncPlatform
import com.example.chatbar.domain.lanSync.LanSyncTlsIdentity
import com.example.chatbar.domain.lanSync.LanSyncWorkspace
import com.example.chatbar.ui.kit.ChatBarTheme
import java.net.InetAddress
import java.nio.file.Files
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** Real invitation owner and Compose result callback, with a controlled document-picker response. */
class AndroidLanSyncInvitationFileRestorationTest {
    private val compose = createAndroidComposeRule<LanSyncActivity>()
    @get:Rule val isolatedRule: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                requireIsolatedApplication()
                base.evaluate()
            }
        }
    }).around(compose)

    @Test
    fun restoredPickerSavesTheSameInvitationAndARevokedSelectionCannotWrite() = runBlocking {
        val app = compose.activity.applicationContext
        val root = Files.createTempDirectory(app.cacheDir.canonicalFile.toPath(), "synthetic-invitation-picker-").toRealPath()
        val data = Files.createDirectory(root.resolve("data"))
        val profile = Files.createDirectory(root.resolve("profile"))
        val workspace = LanSyncWorkspace(data, Files.createDirectory(root.resolve("workspace")), { data.resolve(it) })
        val platform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) = work()
            override suspend fun reload(sessionIds: Set<String>) = Unit
        }
        val network = object : LanSyncNetwork {
            override fun addresses() = listOf("10.0.0.17")
            override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket =
                error("SYNTHETIC_INVITATION_PICKER_UNEXPECTED_OUTBOUND_CONNECTION")
            override fun accepts(address: InetAddress) = address.isLoopbackAddress
            override val discoveryEnabled = false
        }
        val founder = LanSyncGroupEnrollment(profile, workspace, platform, LanSyncTlsIdentity.create(),
            { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> })
        val coordinator = LanSyncGroupCoordinator(
            hasExistingEvidence = { Files.exists(profile.resolve("profile.protected")) },
            enrollment = { founder },
            joining = { error("SYNTHETIC_INVITATION_PICKER_UNEXPECTED_JOIN") },
            network = network, deviceName = "Synthetic invitation sender"
        )
        try {
            coordinator.createGroup()
            await { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE }
            val code = requireNotNull(coordinator.state.value.recoveryCode)
            coordinator.recoverySaved(requireNotNull(coordinator.state.value.setupBinding))
            await { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.READY }

            suspend fun issueFile() {
                coordinator.unlockManagement(code)
                await { !coordinator.state.value.busy && coordinator.state.value.managementCanInvite }
                coordinator.createInvitation(forFile = true)
                await { !coordinator.state.value.busy && coordinator.state.value.invitationFileBinding != null }
            }
            issueFile()
            val originalCode = requireNotNull(coordinator.state.value.invitation).code()
            val originalBytes = originalCode.toByteArray(Charsets.US_ASCII)
            val originalBinding = requireNotNull(coordinator.state.value.invitationFileBinding)
            val picker = Picker()
            val registryOwner = object : ActivityResultRegistryOwner {
                override val activityResultRegistry = picker
            }
            val restoration = StateRestorationTester(object : ComposeContentTestRule by compose {
                override fun setContent(composable: @Composable () -> Unit) {
                    // Use the registered Activity's existing Compose host for the tester's saveable state wrapper.
                    compose.runOnUiThread { compose.activity.setContent(content = composable) }
                }
            })
            restoration.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                    val state by coordinator.state.collectAsState()
                    ChatBarTheme { LanSyncInvitationFiles(coordinator, state) }
                }
            }

            compose.onNodeWithText("保存邀请文件").performClick()
            compose.onNodeWithText("保存邀请文件").assertIsNotEnabled()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("保存邀请文件").assertIsNotEnabled()
            coordinator.lockManagementOnVisibilityLoss()
            assertFalse(coordinator.state.value.managementUnlocked)
            assertTrue(coordinator.state.value.invitationRetainedWhileLocked)
            val target = root.resolve("restored.ccbinvite")
            compose.runOnIdle { picker.deliver(Uri.fromFile(target.toFile())) }
            await { !coordinator.state.value.busy && Files.isRegularFile(target) && Files.size(target) > 0 }
            assertTrue(originalBytes.contentEquals(Files.readAllBytes(target)))
            assertNull(coordinator.state.value.error)

            issueFile()
            assertTrue(coordinator.state.value.invitationFileBinding != originalBinding)
            assertTrue(requireNotNull(coordinator.state.value.invitation).code() != originalCode)
            compose.onNodeWithText("保存邀请文件").performClick()
            restoration.emulateSavedInstanceStateRestore()
            coordinator.closeInvitation()
            compose.runOnIdle { picker.deliver(Uri.fromFile(target.toFile())) }
            await { !coordinator.state.value.busy && coordinator.state.value.error?.contains("SYNC_GROUP_INVITE_FILE_SELECTION_EXPIRED") == true }
            assertTrue(originalBytes.contentEquals(Files.readAllBytes(target)))
        } finally {
            coordinator.closeAndDrain()
            check(root.toFile().deleteRecursively())
        }
    }

    private fun requireIsolatedApplication() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lanSyncInvitationFileRestoreFixture") == "true")
        val app = ApplicationProvider.getApplicationContext<Context>()
        check(app.packageName in setOf("com.example.chatbar.lansync", "com.example.chatbar.lansyncnext"))
        check((app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(30_000) {
        while (!condition()) delay(25)
    }

    private class Picker : ActivityResultRegistry() {
        private var pending: Int? = null

        override fun <I, O> onLaunch(
            requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?
        ) {
            check(pending == null)
            check(input == "设备组邀请.ccbinvite")
            pending = requestCode
        }

        fun deliver(uri: Uri) {
            val code = requireNotNull(pending)
            pending = null
            check(dispatchResult(code, Activity.RESULT_OK, Intent().setData(uri)))
        }
    }
}
