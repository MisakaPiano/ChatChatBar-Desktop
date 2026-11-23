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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.domain.lanSync.LanSyncGroupCoordinator
import com.example.chatbar.domain.lanSync.LanSyncGroupEnrollment
import com.example.chatbar.domain.lanSync.LanSyncGroupFileRecoveryOwner
import com.example.chatbar.domain.lanSync.LanSyncGroupJoining
import com.example.chatbar.domain.lanSync.LanSyncGroupPanelStage
import com.example.chatbar.domain.lanSync.LanSyncNetwork
import com.example.chatbar.domain.lanSync.LanSyncPeer
import com.example.chatbar.domain.lanSync.LanSyncPlatform
import com.example.chatbar.domain.lanSync.LanSyncTlsIdentity
import com.example.chatbar.domain.lanSync.LanSyncWorkspace
import com.example.chatbar.ui.kit.ChatBarTheme
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
                await { coordinator.state.value.let { !it.busy && it.managementUnlocked && it.managementCanInvite } }
                val priorBinding = coordinator.state.value.invitationFileBinding
                coordinator.createInvitation(forFile = true)
                await {
                    coordinator.state.value.let {
                        !it.busy && it.managementUnlocked && it.invitationFileOnly &&
                            it.invitationFileBinding != null && it.invitationFileBinding != priorBinding
                    }
                }
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

    @Test
    fun restoredEncryptedFilePickersVerifyExportAndRequireFreshRecoveryCode() = runBlocking {
        val app = compose.activity.applicationContext
        val root = Files.createTempDirectory(app.cacheDir.canonicalFile.toPath(), "synthetic-group-file-picker-").toRealPath()
        fun coordinator(path: java.nio.file.Path): LanSyncGroupCoordinator {
            val profile = Files.createDirectory(path.resolve("profile"))
            val joining = Files.createDirectory(path.resolve("joining"))
            val data = Files.createDirectory(path.resolve("data"))
            val work = Files.createDirectory(path.resolve("workspace"))
            val workspace = LanSyncWorkspace(data, work, { data.resolve(it) })
            val identity = LanSyncTlsIdentity.create()
            val platform = object : LanSyncPlatform {
                override suspend fun exclusive(work: suspend () -> Unit) = work()
                override suspend fun reload(sessionIds: Set<String>) = Unit
            }
            val network = object : LanSyncNetwork {
                override fun addresses() = listOf("10.0.0.17")
                override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket =
                    error("SYNTHETIC_GROUP_FILE_PICKER_UNEXPECTED_NETWORK")
                override fun accepts(address: InetAddress) = address.isLoopbackAddress
                override val discoveryEnabled = false
            }
            val founder = LanSyncGroupEnrollment(profile, workspace, platform, identity,
                { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> })
            fun joiner() = LanSyncGroupJoining(joining, workspace, platform, identity,
                { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> })
            return LanSyncGroupCoordinator(hasExistingEvidence = { Files.exists(profile.resolve("profile.protected")) ||
                Files.exists(joining.resolve("joining/profile.protected")) }, enrollment = { founder }, joining = ::joiner,
                network = network, deviceName = "Synthetic encrypted file picker",
                fileRecoveryFactory = { ready ->
                    LanSyncGroupFileRecoveryOwner({ Files.createDirectories(path.resolve("recovery")) }, ::joiner,
                        "Synthetic restored device", onReady = ready)
                })
        }
        val source = coordinator(Files.createDirectory(root.resolve("source")))
        val receiver = coordinator(Files.createDirectory(root.resolve("receiver")))
        try {
            val selectionLifecycle = AtomicReference<Lifecycle?>()
            fun diagnostics(): String {
                val group = source.state.value
                val exported = source.fileExport?.state?.value
                val recovered = receiver.fileRecovery?.state?.value
                return "source=${group.stage} busy=${group.busy} issue=${group.error}; selectionLifecycle=${selectionLifecycle.get()?.currentState}; " +
                    "export visible=${exported?.visible} busy=${exported?.busy} prepared=${exported?.preparedBinding != null} " +
                    "written=${exported?.writeCompleted} readback=${exported?.readbackVerified} issue=${exported?.error}; " +
                    "recovery visible=${recovered?.visible} busy=${recovered?.busy} review=${recovered?.review != null} issue=${recovered?.error}"
            }
            suspend fun reach(stage: String, condition: () -> Boolean) {
                val reached = withTimeoutOrNull(30_000) {
                    while (!condition()) delay(25)
                    true
                }
                check(reached == true) { "$stage failed: ${diagnostics()}" }
            }
            source.createGroup()
            reach("create group") { !source.state.value.busy && source.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE }
            val code = requireNotNull(source.state.value.recoveryCode)
            source.recoverySaved(requireNotNull(source.state.value.setupBinding))
            reach("activate founder") { !source.state.value.busy && source.state.value.stage == LanSyncGroupPanelStage.READY }
            val exporter = requireNotNull(source.fileExport)
            val recovery = requireNotNull(receiver.fileRecovery)
            val picker = Picker("ChatBar.cbgroup", allowInput = true)
            val registryOwner = object : ActivityResultRegistryOwner { override val activityResultRegistry = picker }
            var shown by mutableStateOf(source)
            val restoration = StateRestorationTester(object : ComposeContentTestRule by compose {
                override fun setContent(composable: @Composable () -> Unit) {
                    compose.runOnUiThread { compose.activity.setContent(content = composable) }
                }
            })
            restoration.setContent {
                val current = shown
                key(current) { CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                    selectionLifecycle.set(LocalLifecycleOwner.current.lifecycle)
                    val state by current.state.collectAsState()
                    ChatBarTheme { LanSyncGroupFileActions(current, state) }
                } }
            }
            compose.onNodeWithText("导出加密同步文件").performClick()
            compose.onNodeWithText("准备加密同步文件").performScrollTo().performClick()
            reach("prepare encrypted file") { !exporter.state.value.busy && exporter.state.value.preparedBinding != null }
            val target = root.resolve("verified.cbgroup")
            compose.onNodeWithText("选择保存位置并写出").performScrollTo().performClick()
            compose.onNodeWithText("导出加密同步文件").assertIsNotEnabled()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("导出加密同步文件").assertIsNotEnabled()
            source.lockManagementOnVisibilityLoss()
            compose.runOnIdle { picker.deliver(Uri.fromFile(target.toFile())) }
            compose.waitForIdle()
            reach("verify restored output picker write and readback") { !exporter.state.value.busy && exporter.state.value.readbackVerified }
            val bytes = Files.readAllBytes(target)
            assertTrue(bytes.isNotEmpty())

            compose.onNodeWithText("选择保存位置并写出").performScrollTo().performClick()
            restoration.emulateSavedInstanceStateRestore()
            exporter.lock()
            compose.runOnIdle { picker.deliver(Uri.fromFile(target.toFile())) }
            compose.waitForIdle()
            compose.onNodeWithText("这次文件选择已失效，请重新准备或选择保存位置").assertExists()
            assertTrue(bytes.contentEquals(Files.readAllBytes(target)))

            compose.runOnIdle { shown = receiver }
            compose.onNodeWithText("从加密文件恢复设备组").performClick()
            compose.onNode(hasSetTextAction()).performTextInput(code)
            compose.onNodeWithText("选择加密同步文件").performScrollTo().performClick()
            restoration.emulateSavedInstanceStateRestore()
            receiver.lockManagementOnVisibilityLoss()
            compose.runOnIdle { picker.deliver(Uri.fromFile(target.toFile())) }
            compose.waitForIdle()
            compose.onNodeWithText("已选择加密同步文件；返回应用后请重新输入恢复码").assertExists()
            compose.onNodeWithText("读取并核对设备组").assertIsNotEnabled()
            assertNull(recovery.state.value.review)
            compose.onNode(hasSetTextAction()).performTextInput(code)
            compose.onNodeWithText("读取并核对设备组").performScrollTo().performClick()
            reach("review opened file with fresh recovery code") { !recovery.state.value.busy && recovery.state.value.review != null }
            assertNull(recovery.state.value.error)
            assertTrue(bytes.contentEquals(Files.readAllBytes(target)))
        } finally {
            receiver.closeAndDrain(); source.closeAndDrain()
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

    private class Picker(private val filename: String = "设备组邀请.ccbinvite", private val allowInput: Boolean = false) : ActivityResultRegistry() {
        private var pending: Int? = null

        override fun <I, O> onLaunch(
            requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?
        ) {
            check(pending == null)
            check(input == filename || allowInput && input is Array<*> && input.contentEquals(arrayOf("*/*")))
            pending = requestCode
        }

        fun deliver(uri: Uri) {
            val code = requireNotNull(pending)
            pending = null
            check(dispatchResult(code, Activity.RESULT_OK, Intent().setData(uri)))
        }
    }
}
