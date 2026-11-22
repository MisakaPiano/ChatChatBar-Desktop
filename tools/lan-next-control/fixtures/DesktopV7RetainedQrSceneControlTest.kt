package com.example.chatbar.desktop.lanSync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.awt.ComposeWindow
import com.example.chatbar.desktop.*
import com.example.chatbar.desktop.security.WindowsDpapiSecretProtector
import com.example.chatbar.domain.lanSync.LanSyncGroupCoordinator
import com.example.chatbar.domain.lanSync.LanSyncGroupPanelStage
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.skia.EncodedImageFormat

/** One real Compose scene backed by the existing group of the old Windows AppContainer. */
class DesktopV7RetainedQrSceneControlTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    private var phase = "GUARD"
    private var observation: (() -> Observation)? = null

    @Test
    fun originalRecoveryCodeRendersWholeQrAndCopiesFromNarrowScene() {
        var evidence: Path? = null
        try {
            check(env("GITHUB_ACTIONS") == "true" && env("RUNNER_OS") == "Windows")
            check(env("CCB_V7_QR_SCENE_CONTROL") == "true" && System.getProperty("os.name").startsWith("Windows"))
            val runner = Path.of(env("RUNNER_TEMP")).toRealPath()
            val root = isolated(env("CCB_V7_QR_ROOT"), runner)
            val privateRoot = isolated(env("CCB_V7_QR_PRIVATE"), runner)
            check(!privateRoot.startsWith(root) && !root.startsWith(privateRoot))
            val userData = root.resolve("UserData")
            val local = isolated(userData.resolve("Local").toString(), runner)
            check(Path.of(env("LOCALAPPDATA")).toRealPath() == local)
            check(Path.of(env("APPDATA")).toRealPath() == userData.resolve("Roaming").toRealPath())
            check(Path.of(env("TEMP")).toRealPath() == userData.resolve("Temp").toRealPath())
            check(Path.of(env("TMP")).toRealPath() == userData.resolve("Temp").toRealPath())
            val data = isolated(local.resolve("ChatChatBarDesktop").toString(), runner)
            evidence = privateRoot.resolve("qr-scene-observation.json")
            val savedOracle = privateRoot.resolve("group-oracle.private.json")
            check(Files.isRegularFile(savedOracle, NOFOLLOW_LINKS) && Files.size(savedOracle) in 1L..262_144L)
            val oracle = Json.parseToJsonElement(Files.readString(savedOracle)).jsonObject
            check(oracle.keys == setOf("logical", "protected"))
            val logical = oracle.getValue("logical").jsonObject
            val protected = oracle.getValue("protected").jsonObject
            fun verifyOldProtectedFiles() {
                check(protected.isNotEmpty())
                for ((relative, digest) in protected) {
                    val file = isolated(local.resolve(relative).toString(), local)
                    check(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= 16L * 1024 * 1024)
                    check(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
                        .joinToString("") { "%02x".format(it) } == digest.jsonPrimitive.content)
                }
            }

            phase = "RETAINED_PROTECTED_FILES"
            verifyOldProtectedFiles()
            val saved = privateRoot.resolve("recovery-custody.private.protected")
            check(Files.isRegularFile(saved, NOFOLLOW_LINKS) && Files.size(saved) in 1L..262_144L)
            val recovery = WindowsDpapiSecretProtector().unprotect(Files.readAllBytes(saved))
            val result = try {
                runBlocking(edt) {
                    phase = "ACQUIRE_RETAINED_ROOT"
                    val resolved = DesktopDataDirectory.resolveRoot(explicitOverride = data)
                        as DesktopDataRootResolution.Resolved
                    check(resolved.appDataRoot.toRealPath() == data)
                    val acquired = DesktopDataRootOwnership.acquire(resolved) as DesktopDataRootOwnershipResult.Acquired
                    val app = DesktopAppContainer(resolved)
                    var primaryFailure = false
                    try {
                        phase = "OPEN_RETAINED_GROUP"
                        app.initializePersistentState()
                        check(app.hasLanSyncGroupEvidence())
                        val coordinator = app.lanSyncGroupCoordinator
                        observation = { Observation(coordinator) }
                        coordinator.open()
                        await { coordinator.state.value.stage == LanSyncGroupPanelStage.READY && !coordinator.state.value.busy }
                        val profile = checkNotNull(app.lanSyncGroupEnrollment.profile())
                        val access = checkNotNull(app.lanSyncGroupEnrollment.runtimeAccess())
                        check(profile.trust.group.value == logical.getValue("group").jsonPrimitive.content)
                        check(profile.device == logical.getValue("dailyDevice").jsonPrimitive.content)
                        check(access.ledger().current().bodyHash() == logical.getValue("ledgerHash").jsonPrimitive.content)
                        check(!coordinator.state.value.managementUnlocked)
                        coordinator.resumeIfConfigured()
                        verifyScene(app, coordinator, recovery.toString(Charsets.UTF_8))
                    } catch (failure: Throwable) {
                        primaryFailure = true
                        throw failure
                    } finally {
                        if (!primaryFailure) phase = "CLOSE_RETAINED_APP"
                        val closed = runCatching {
                            withContext(NonCancellable) {
                                withTimeout(85_000) {
                                    app.lanSyncGroupCoordinator.pauseAndDrain()
                                    closeDesktopWithLanSyncCompletionNotice(close = { app.close() }, showNotice = {})
                                }
                            }
                            acquired.ownership.close()
                        }
                        if (!primaryFailure) closed.getOrThrow()
                    }
                }
            } finally {
                recovery.fill(0)
            }
            phase = "VERIFY_RETAINED_AFTER_CLOSE"
            verifyOldProtectedFiles()
            write(evidence, passed = true, proof = result)
        } catch (failure: Throwable) {
            evidence?.let { runCatching { write(it, passed = false, proof = null) } }
            // Never put the original clipboard/code/renderer exception in a JUnit report.
            throw AssertionError("V7_QR_SCENE:" + phase).also { it.stackTrace = emptyArray() }
        }
    }

    private data class Proof(val side: Int, val modules: Int, val pixelsPerModule: Int, val narrowWidth: Int)
    private data class Observation(val stage: String, val busy: Boolean, val unlocked: Boolean, val invitation: Boolean) {
        constructor(coordinator: LanSyncGroupCoordinator) : this(
            coordinator.state.value.stage.name, coordinator.state.value.busy,
            coordinator.state.value.managementUnlocked, coordinator.state.value.invitation != null,
        )
    }

    private suspend fun verifyScene(app: DesktopAppContainer, coordinator: LanSyncGroupCoordinator, originalCode: String): Proof {
        check(SwingUtilities.isEventDispatchThread())
        phase = "OPEN_NATIVE_CLIPBOARD_OWNER"
        val owner = ComposeWindow()
        try {
            owner.isUndecorated = true
            owner.setSize(1, 1)
            owner.addNotify()
            check(owner.isDisplayable && !owner.isVisible)
            val window = Native.getComponentPointer(owner)
            check(Pointer.nativeValue(window) != 0L)
            val width = mutableStateOf(WIDE.dp)
            var measuredPanelPixels = 0
            val scene = ImageComposeScene(WIDE, HEIGHT, coroutineContext = edt) {
                CompositionLocalProvider(
                    LocalDesktopUiStrings provides DesktopUiStrings(DesktopUiLanguage.ZH_CN),
                    LocalDesktopFishParentWindow provides owner,
                    LocalDensity provides Density(1f),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(width.value).onSizeChanged { measuredPanelPixels = it.width }) {
                            DesktopLanSyncEntry(coordinator, null, app::hasLanSyncGroupEvidence)
                        }
                    }
                }
            }
            try {
                fun nodes(): List<SemanticsNode> {
                    fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
                    return scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
                }
                fun label(text: String) = nodes().singleOrNull { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
                }
                fun button(text: String): SemanticsNode? {
                    var node = label(text)
                    while (node != null && !node.config.contains(SemanticsActions.OnClick)) node = node.parent
                    return node
                }
                fun enabled(node: SemanticsNode?) = node != null && !node.config.contains(SemanticsProperties.Disabled)
                fun qrNodes() = nodes().filter {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("新设备扫描此邀请") == true
                }
                suspend fun renderUntil(waitMillis: Long = 180_000L, predicate: () -> Boolean) = withTimeout(waitMillis) {
                    while (true) {
                        scene.render(System.nanoTime()).close()
                        if (predicate()) break
                        delay(16)
                    }
                }
                suspend fun fullyVisible(sceneWidth: Int, select: () -> SemanticsNode?): SemanticsNode {
                    repeat(24) {
                        scene.render(System.nanoTime()).close()
                        val node = select()
                        if (node != null) {
                            val position = node.positionInRoot
                            val size = node.size
                            val bottom = position.y + size.height
                            val bounds = node.boundsInRoot
                            check(size.width > 0 && size.height > 0)
                            if (position.x >= 0 && position.y >= 0 && position.x + size.width <= sceneWidth &&
                                bottom <= HEIGHT && abs(bounds.left - position.x) < .05f &&
                                abs(bounds.top - position.y) < .05f && abs(bounds.width - size.width) < .05f &&
                                abs(bounds.height - size.height) < .05f) return node
                            var scroll: SemanticsNode? = node.parent
                            while (scroll != null && !scroll.config.contains(SemanticsActions.ScrollBy)) scroll = scroll.parent
                            val delta = if (position.y < 0) position.y - 12f else bottom - HEIGHT + 12f
                            check(checkNotNull(scroll).config[SemanticsActions.ScrollBy].action!!.invoke(0f, delta.coerceIn(-1000f, 1000f)))
                        }
                        delay(16)
                    }
                    error("TARGET_NOT_FULLY_VISIBLE")
                }
                suspend fun click(text: String, sceneWidth: Int) {
                    renderUntil { enabled(button(text)) }
                    val node = fullyVisible(sceneWidth) { button(text) }
                    check(enabled(node) && checkNotNull(node.config[SemanticsActions.OnClick].action).invoke())
                }
                phase = "WAIT_LOCKED_PANEL"
                renderUntil {
                    measuredPanelPixels == WIDE && !coordinator.state.value.paused && !coordinator.state.value.managementUnlocked &&
                        nodes().any { enabled(it) && it.config.contains(SemanticsActions.SetText) }
                }
                check(qrNodes().isEmpty())
                val editor = fullyVisible(WIDE) { nodes().singleOrNull { it.config.contains(SemanticsActions.SetText) } }
                phase = "UNLOCK_WITH_ORIGINAL_CODE"
                check(checkNotNull(editor.config[SemanticsActions.SetText].action).invoke(AnnotatedString(originalCode)))
                click("解锁管理", WIDE)
                renderUntil { coordinator.state.value.managementUnlocked && !coordinator.state.value.busy }
                check(coordinator.state.value.error == null)

                phase = "CLICK_INVITE"
                click("邀请设备", WIDE)
                phase = "WAIT_INVITATION_PUBLISHED"
                renderUntil { !coordinator.state.value.busy && coordinator.state.value.invitation != null }
                val offer = checkNotNull(coordinator.state.value.invitation)
                check(coordinator.state.value.error == null)
                val code = offer.code()
                phase = "WAIT_QR_NODE"
                renderUntil(20_000) { qrNodes().size == 1 }
                val node = fullyVisible(WIDE) { qrNodes().singleOrNull() }
                phase = "CHECK_WIDE_QR_PIXELS"
                val matrix = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 0, 0, mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 4))
                val maximum = minOf(WIDE - 40, maxOf(280, matrix.width * 2))
                val side = maximum / matrix.width * matrix.width
                readPixels(scene, node, code, matrix, side)

                phase = "CHECK_NARROW_COPY_ONLY"
                val narrow = matrix.width * 2 - 1
                width.value = narrow.dp
                renderUntil(20_000) {
                    measuredPanelPixels == narrow && qrNodes().isEmpty() &&
                        label("窗口过窄，无法清晰显示完整二维码。请加宽窗口，或复制邀请并在新设备粘贴。") != null
                }
                check(coordinator.state.value.invitation == offer)
                phase = "COPY_INVITE_THROUGH_NATIVE_PATH"
                click("复制邀请", narrow)
                compareClipboard(window, code)
                check(coordinator.state.value.invitation == offer)

                phase = "CLOSE_INVITATION_AND_LOCK"
                width.value = WIDE.dp
                renderUntil(20_000) { measuredPanelPixels == WIDE }
                click("关闭邀请", WIDE)
                renderUntil { coordinator.state.value.invitation == null && qrNodes().isEmpty() }
                click("锁定管理", WIDE)
                renderUntil { !coordinator.state.value.managementUnlocked }
                return Proof(side, matrix.width, side / matrix.width, narrow)
            } finally {
                try { scene.close() } finally { clearOwnedClipboard(window) }
            }
        } finally {
            owner.dispose()
        }
    }

    private fun readPixels(
        scene: ImageComposeScene, node: SemanticsNode, code: String,
        matrix: com.google.zxing.common.BitMatrix, side: Int,
    ) {
        val bounds = node.boundsInRoot
        val left = bounds.left.roundToInt()
        val top = bounds.top.roundToInt()
        check(abs(bounds.left - left) < .05f && abs(bounds.top - top) < .05f)
        check(node.size.width == side && node.size.height == side && left + side <= WIDE && top + side <= HEIGHT)
        check(matrix.width == matrix.height && matrix.width > 8 && side % matrix.width == 0)
        val scale = side / matrix.width
        check(scale >= 2)
        val png = scene.render(System.nanoTime()).use { image ->
            checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
        }
        try {
            val image = checkNotNull(ImageIO.read(ByteArrayInputStream(png)))
            try {
                check(image.width == WIDE && image.height == HEIGHT)
                val pixels = image.getRGB(left, top, side, side, null, 0, side)
                try {
                    val decoded = QRCodeReader().decode(
                        BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels))),
                        mapOf(DecodeHintType.TRY_HARDER to true),
                    )
                    check(decoded.barcodeFormat == BarcodeFormat.QR_CODE && decoded.text == code)
                    for (y in 0 until side) for (x in 0 until side) {
                        val moduleX = x / scale
                        val moduleY = y / scale
                        val dark = matrix[moduleX, moduleY]
                        if (moduleX < 4 || moduleY < 4 || moduleX >= matrix.width - 4 || moduleY >= matrix.height - 4) check(!dark)
                        check(pixels[y * side + x] == if (dark) 0xff000000.toInt() else 0xffffffff.toInt())
                    }
                } finally {
                    pixels.fill(0)
                }
            } finally {
                image.flush()
            }
        } finally {
            png.fill(0)
        }
    }

    private interface Clip : StdCallLibrary {
        fun OpenClipboard(owner: Pointer): Boolean
        fun CloseClipboard(): Boolean
        fun EmptyClipboard(): Boolean
        fun GetClipboardData(format: Int): Pointer?
        fun GetClipboardOwner(): Pointer?
        fun RegisterClipboardFormatW(name: WString): Int
        fun IsClipboardFormatAvailable(format: Int): Boolean
    }

    private interface Heap : StdCallLibrary {
        fun GlobalLock(memory: Pointer): Pointer?
        fun GlobalUnlock(memory: Pointer): Boolean
    }

    private val clip by lazy { Native.load("user32", Clip::class.java) }
    private val heap by lazy { Native.load("kernel32", Heap::class.java) }
    private fun ownsClipboard(window: Pointer) = clip.GetClipboardOwner()?.let { Pointer.nativeValue(it) } == Pointer.nativeValue(window)

    private fun compareClipboard(window: Pointer, expected: String) {
        check(clip.OpenClipboard(window))
        try {
            check(ownsClipboard(window))
            val marker = clip.RegisterClipboardFormatW(WString(DesktopLanSyncSensitiveClipboard.EXCLUSION_FORMAT))
            check(marker != 0 && clip.IsClipboardFormatAvailable(marker) && clip.GetClipboardData(marker) != null)
            val handle = checkNotNull(clip.GetClipboardData(13))
            val pointer = checkNotNull(heap.GlobalLock(handle))
            try { check(pointer.getWideString(0) == expected) } finally { heap.GlobalUnlock(handle) }
            check(clip.EmptyClipboard())
        } finally {
            check(clip.CloseClipboard())
        }
    }

    private fun clearOwnedClipboard(window: Pointer) {
        if (!ownsClipboard(window)) return
        check(clip.OpenClipboard(window))
        try { if (ownsClipboard(window)) check(clip.EmptyClipboard()) } finally { check(clip.CloseClipboard()) }
    }

    private fun write(path: Path, passed: Boolean, proof: Proof?) {
        val state = observation?.invoke()
        val value = buildJsonObject {
            put("kind", "CCB_V7_RETAINED_QR_SCENE_V1")
            put("phase", if (passed) "COMPLETE" else phase)
            put("passed", passed)
            state?.let {
                put("groupStage", it.stage); put("busy", it.busy)
                put("managementUnlocked", it.unlocked); put("invitationPresent", it.invitation)
            }
            proof?.let {
                put("imagePixels", it.side); put("gridModules", it.modules)
                put("pixelsPerModule", it.pixelsPerModule); put("narrowPanelPixels", it.narrowWidth)
            }
        }
        Files.writeString(path, value.toString(), CREATE_NEW, WRITE)
    }

    private fun env(key: String) = checkNotNull(System.getenv(key)).also { check(it.isNotBlank()) }
    private fun isolated(raw: String, parent: Path): Path {
        val input = Path.of(raw).toAbsolutePath().normalize()
        val path = input.toRealPath()
        check(path == input && path.startsWith(parent) && path != parent && !Files.isSymbolicLink(input))
        return path
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(180_000) {
        while (!predicate()) delay(50)
    }

    private companion object {
        const val WIDE = 1000
        const val HEIGHT = 1600
    }
}
