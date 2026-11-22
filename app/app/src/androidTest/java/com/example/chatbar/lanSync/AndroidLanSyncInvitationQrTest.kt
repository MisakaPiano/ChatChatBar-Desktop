package com.example.chatbar.lanSync

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
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

        // Render the same production QR element with synthetic text, outside the protected group page.
        setContent {
            val width = with(LocalDensity.current) { widthPx.intValue.toDp() }
            ChatBarTheme { Box(Modifier.width(width)) { LanSyncGroupInvitationQr(code) } }
        }

        val qr = compose.onNodeWithContentDescription("新设备扫描此邀请")
        val image = qr.assertIsDisplayed().captureToImage()
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
        qr.assertDoesNotExist()
        compose.onNodeWithText("请复制邀请，或生成邀请文件。", substring = true).assertIsDisplayed()
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
        val network = object : LanSyncNetwork {
            override fun addresses() = listOf("10.0.0.17")
            override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket =
                error("SYNTHETIC_INVITATION_QR_UNEXPECTED_OUTBOUND_CONNECTION")
            override fun accepts(address: InetAddress) = address.isLoopbackAddress
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

            setContent {
                val state by coordinator.state.collectAsState()
                val panelWidth = with(LocalDensity.current) { (panelBodyWidthPx + 2 * 20.dp.roundToPx()).toDp() }
                ChatBarTheme {
                    Column(Modifier.width(panelWidth)) {
                        LanSyncInvitationFiles(coordinator, state)
                        Box(Modifier.weight(1f)) { LanSyncGroupPanel(state, coordinator, scanInvitation = {}) }
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
            compose.onNodeWithText("保存邀请文件").assertIsDisplayed().assertIsEnabled()
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
