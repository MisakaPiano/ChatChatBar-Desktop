package com.example.chatbar.lanSync

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.domain.lanSync.LanSyncGroupCoordinator
import com.example.chatbar.domain.lanSync.LanSyncGroupEnrollment
import com.example.chatbar.domain.lanSync.LanSyncGroupInviteClient
import com.example.chatbar.domain.lanSync.LanSyncGroupPanelStage
import com.example.chatbar.domain.lanSync.LanSyncNetwork
import com.example.chatbar.domain.lanSync.LanSyncPeer
import com.example.chatbar.domain.lanSync.LanSyncPlatform
import com.example.chatbar.domain.lanSync.LanSyncTls
import com.example.chatbar.domain.lanSync.LanSyncTlsIdentity
import com.example.chatbar.domain.lanSync.LanSyncWorkspace
import com.example.chatbar.domain.syncStore.SyncDeviceEncryptionKey
import com.example.chatbar.domain.syncStore.SyncStoreId
import com.example.chatbar.ui.kit.ChatBarTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** Bounded physical QR pixels and the production fallback actions on a disposable Android profile. */
class AndroidLanSyncInvitationQrTest {
    private val compose = createAndroidComposeRule<LanSyncActivity>()
    @get:Rule val isolatedRule: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                requireIsolatedApplication()
                base.evaluate()
            }
        }
    }).around(compose)

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun denseCodeFitsAt330PixelsAndFallsBackAt329() {
        val prefix = "CCB-GROUP-JOIN1:"
        val code = prefix + "a".repeat(2200 - prefix.length)
        val matrix = referenceMatrix(code)
        assertEquals(165, matrix.width)
        assertEquals(165, matrix.height)
        val widthPx = mutableIntStateOf(330)
        val measuredHost = AtomicReference(IntSize.Zero)
        val hostTag = "InvitationQrMeasuredHost"

        // Render the same production QR element with synthetic text, outside the protected group page.
        setContent {
            ChatBarTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
                    Layout(
                        content = { LanSyncGroupInvitationQr(code) },
                        modifier = Modifier.testTag(hostTag).onSizeChanged(measuredHost::set)
                    ) { measurables, constraints ->
                        val width = widthPx.intValue
                        check(constraints.maxWidth >= width && constraints.maxHeight >= 330) {
                            "QR proof viewport cannot fit the fixture: $constraints"
                        }
                        val child = measurables.single().measure(constraints.copy(minWidth = width, maxWidth = width))
                        layout(child.width, child.height) { child.placeRelative(0, 0) }
                    }
                }
            }
        }

        val host = compose.onNodeWithTag(hostTag, useUnmergedTree = true)
        val qr = compose.onNodeWithContentDescription("新设备扫描此邀请", useUnmergedTree = true)
        val narrowFallback = compose.onNodeWithText("请复制邀请，或生成邀请文件。", substring = true)
        fun awaitHostWidth(width: Int) {
            compose.waitUntil(10_000) { measuredHost.get().width == width }
            val bounds = host.fetchSemanticsNode().boundsInRoot
            assertEquals("Measured visible host width at $width px: $bounds", width.toFloat(), bounds.width, 0f)
        }
        awaitHostWidth(330)
        val image = try {
            qr.assertIsDisplayed().captureToImage()
        } catch (failure: AssertionError) {
            val display = compose.activity.resources.displayMetrics
            val qrBounds = runCatching { qr.fetchSemanticsNode().boundsInRoot }.getOrNull()
            val fallbackBounds = runCatching { narrowFallback.fetchSemanticsNode().boundsInRoot }.getOrNull()
            throw AssertionError(
                "QR at exact width $measuredHost; host=${host.fetchSemanticsNode().boundsInRoot}; " +
                    "QR=$qrBounds; fallback=$fallbackBounds; " +
                    "display=${display.widthPixels}x${display.heightPixels} at ${display.densityDpi} dpi",
                failure
            )
        }
        assertEquals(330, image.width)
        assertEquals(330, image.height)
        val pixelMap = image.toPixelMap()
        val pixels = IntArray(image.width * image.height)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val argb = pixelMap[x, y].toArgb()
            pixels[y * image.width + x] = argb
            if ((x < 8 || y < 8 || x >= image.width - 8 || y >= image.height - 8) && argb != -1) {
                throw AssertionError("QR quiet zone contains a non-white pixel at ($x,$y)")
            }
            val expected = if (matrix[x / 2, y / 2]) 0xff000000.toInt() else -1
            if (argb != expected) throw AssertionError("QR pixel at ($x,$y) differs from its integer module")
        }
        val source = RGBLuminanceSource(image.width, image.height, pixels)
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
        assertTrue("The decoded QR payload differs from the unwrapped invitation text", decoded == code)

        compose.runOnIdle { widthPx.intValue = 329 }
        awaitHostWidth(329)
        qr.assertDoesNotExist()
        narrowFallback.assertIsDisplayed()
    }

    @Test
    fun narrowGroupPageCopiesExactInvitationAndCanGenerateAFile(): Unit = runBlocking {
        val app = compose.activity.applicationContext
        val root = Files.createTempDirectory(app.cacheDir.canonicalFile.toPath(), "synthetic-android-invite-qr-").toRealPath()
        val data = Files.createDirectory(root.resolve("data"))
        val profile = Files.createDirectory(root.resolve("profile"))
        val workspace = LanSyncWorkspace(data, Files.createDirectory(root.resolve("workspace")), { data.resolve(it) })
        val platform = object : LanSyncPlatform {
            override suspend fun exclusive(work: suspend () -> Unit) = work()
            override suspend fun reload(sessionIds: Set<String>) = Unit
        }
        val ownHost = LanSyncTls.localAddresses().firstOrNull() ?: error("The isolated emulator needs its own private IPv4")
        val network = object : LanSyncNetwork {
            override fun addresses() = listOf(ownHost, "10.0.0.18").distinct()
            override fun preferredInvitationAddress(eligible: List<String>) = ownHost
            override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket =
                error("SYNTHETIC_INVITATION_QR_UNEXPECTED_OUTBOUND_CONNECTION")
            override fun accepts(address: InetAddress) = address.hostAddress == ownHost || address.isLoopbackAddress
            override fun discoverGroup(group: SyncStoreId, local: String, fingerprint: String): LanSyncPeer? = null
            override fun discoverGroupLedger(group: SyncStoreId, local: String, known: Set<String>) = emptyList<LanSyncPeer>()
            override val discoveryEnabled = false
        }
        val founder = LanSyncGroupEnrollment(profile, workspace, platform, LanSyncTlsIdentity.create(),
            { it.reversedArray() }, { it.reversedArray() }, { _, _, _ -> })
        val coordinator = LanSyncGroupCoordinator(
            hasExistingEvidence = { Files.exists(profile.resolve("profile.protected")) },
            enrollment = { founder },
            joining = { error("SYNTHETIC_INVITATION_QR_UNEXPECTED_JOIN") },
            network = network,
            deviceName = "Synthetic invitation sender"
        )
        try {
            coordinator.createGroup()
            await { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE }
            val recovery = requireNotNull(coordinator.state.value.recoveryCode)
            coordinator.recoverySaved(requireNotNull(coordinator.state.value.setupBinding))
            await { !coordinator.state.value.busy && coordinator.state.value.stage == LanSyncGroupPanelStage.READY }
            coordinator.unlockManagement(recovery)
            await { !coordinator.state.value.busy && coordinator.state.value.managementCanInvite }
            coordinator.createInvitation()
            await { !coordinator.state.value.busy && coordinator.state.value.invitation != null }
            val invitation = requireNotNull(coordinator.state.value.invitation).code()
            val panelBodyWidthPx = referenceMatrix(invitation).width * 2 - 1
            val clipboard = requireNotNull(compose.activity.getSystemService(ClipboardManager::class.java))
            val height = mutableIntStateOf(0)

            setContent {
                val state by coordinator.state.collectAsState()
                val narrowPanelWidth = with(LocalDensity.current) { (panelBodyWidthPx + 2 * 20.dp.roundToPx()).toDp() }
                val compactHeight = height.intValue
                ChatBarTheme {
                    val viewport = if (compactHeight == 0) Modifier.width(narrowPanelWidth).fillMaxSize()
                    else Modifier.width(360.dp).height(compactHeight.dp)
                    Box(viewport.testTag("synthetic-group-viewport")) {
                        LanSyncGroupPanel(state, coordinator, pageControls = {
                            LanSyncGroupPageControls(coordinator, state, hasCloudRecovery = false, close = {})
                            if (compactHeight > 0) LanSyncBackgroundControls(
                                AndroidLanSyncBackgroundState(AndroidLanSyncBackgroundPhase.RUNNING,
                                    notificationsVisible = false, foregroundProtected = true), {}, {}, {})
                        }, scanInvitation = {})
                    }
                }
            }

            compose.onNodeWithContentDescription("新设备扫描此邀请").assertDoesNotExist()
            compose.onNodeWithText("请复制邀请，或生成邀请文件。", substring = true).performScrollTo().assertIsDisplayed()
            compose.runOnIdle {
                val flags = compose.activity.window.attributes.flags
                assertTrue((flags and WindowManager.LayoutParams.FLAG_SECURE) != 0)
            }
            compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
            try {
                compose.onNodeWithText("复制邀请").performScrollTo().assertIsEnabled().performClick()
                compose.runOnIdle {
                    val clip = requireNotNull(clipboard.primaryClip)
                    assertEquals(1, clip.itemCount)
                    assertTrue("The clipboard payload differs", clip.getItemAt(0).text?.toString() == invitation)
                    assertTrue(clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true)
                }
            } finally {
                compose.runOnIdle { clipboard.clearPrimaryClip() }
            }
            compose.onNodeWithText("生成邀请文件").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(30_000) { !coordinator.state.value.busy && coordinator.state.value.invitationFileBinding != null }
            compose.onNodeWithText("保存邀请文件").performScrollTo().assertIsDisplayed().assertIsEnabled()
            val candidate = LanSyncTlsIdentity.create()
            val fileOffer = requireNotNull(coordinator.state.value.invitation)
            assertEquals(ownHost, fileOffer.host)
            LanSyncGroupInviteClient.exchange(fileOffer, candidate, SyncDeviceEncryptionKey.create(candidate.fingerprint), "合成待批准手机")
            compose.waitUntil(30_000) { coordinator.state.value.peerConfirmation?.fingerprint == candidate.fingerprint }
            val confirmation = requireNotNull(coordinator.state.value.peerConfirmation)
            for (windowHeight in listOf(360, 320, 280)) {
                compose.runOnIdle { height.intValue = windowHeight }
                compose.onNodeWithTag("synthetic-group-viewport").assertHeightIsEqualTo(windowHeight.dp)
                listOf("返回", "保存邀请文件", "生成新邀请文件", "停止设备组同步", "允许设备组通知", "立即检查",
                    confirmation.name, confirmation.fingerprint).forEach { label ->
                    compose.onNodeWithText(label, substring = true).performScrollTo().assertIsDisplayed()
                }
                val approve = compose.onNodeWithText("确认加入此设备").performScrollTo().assertIsDisplayed().assertIsEnabled()
                if (windowHeight == 280) approve.performTouchInput { click() }
            }
            await {
                val state = coordinator.state.value
                !state.busy && state.peerConfirmation == null && state.devices.any { it.id == candidate.fingerprint }
            }
            val enrolled = requireNotNull(founder.runtimeAccess()).ledger().current().members[candidate.fingerprint]
            assertTrue(enrolled?.active == true)
        } finally {
            coordinator.closeAndDrain()
            check(root.toFile().deleteRecursively())
        }
    }

    private fun requireIsolatedApplication() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lanSyncInvitationQrFixture") == "true")
        val app = ApplicationProvider.getApplicationContext<Context>()
        check(app.packageName in setOf("com.example.chatbar.lansync", "com.example.chatbar.lansyncnext"))
        check((app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        check(Build.VERSION.SDK_INT >= 28) { "The isolated QR proof requires Android 9 or newer for clipboard cleanup" }
    }

    private fun setContent(content: @Composable () -> Unit) {
        // The preview package has a real group Activity but no generic Compose test Activity.
        compose.runOnUiThread { compose.activity.setContent(content = content) }
    }

    private fun referenceMatrix(code: String) = QRCodeWriter().encode(
        code, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 4)
    )

    private suspend fun await(condition: () -> Boolean) = withTimeout(30_000) {
        while (!condition()) delay(25)
    }
}
