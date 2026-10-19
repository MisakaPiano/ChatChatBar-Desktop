@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.voice.*
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.POINT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.AWTEvent
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt
import kotlin.test.*

/** Native input to exact synthetic window identities; no screen capture or global key interception. */
class DesktopFishPickerNativeUxTest {
    companion object {
        /** Two explicit opt-in commands; neither is a JUnit test or a dependency of test/check. */
        @JvmStatic fun main(args: Array<String>) {
            if (args.contentEquals(arrayOf("--r6j-handshake-check"))) {
                val output = Path.of(checkNotNull(System.getProperty("ccb.r6j.checkOutput"))).toAbsolutePath().normalize()
                require(output == Path.of("H:/ChatChatBar-Desktop/app/desktopApp/build/p8-s2b-ui-layout-001-r6j-20261019/deterministic-checks").toAbsolutePath().normalize())
                check(java.awt.GraphicsEnvironment.isHeadless())
                checkHumanHandshake(output)
                return // No test instance, Fixture, Robot, Toolkit, HWND or native library is created.
            }
            require(args.contentEquals(arrayOf("--r6j-physical-mouse-only")))
            check(System.getProperty("ccb.r6j.explicitManualStart") == "true")
            val runId = checkNotNull(System.getProperty("ccb.r6j.runId"))
            require(java.util.UUID.fromString(runId).toString() == runId)
            val control = Path.of(checkNotNull(System.getProperty("ccb.r6j.controlRoot"))).toAbsolutePath().normalize()
            require(control == Path.of("H:/ChatChatBar-Desktop/app/desktopApp/build/p8-s2b-ui-layout-001-r6j-20261019/manual-control").resolve(runId).toAbsolutePath().normalize())
            check(DesktopFishPickerNativeUxTest().runPhysicalMouseControl(control, runId)) {
                "R6J manual control did not PASS; see persisted stage and result after confirmed/bounded cleanup"
            }
        }

        private fun publishHumanArtifact(control: Path, name: String, lines: List<String>) {
            check(!Files.exists(control.resolve(name))) { "Human artifact already published" }
            val part = control.resolve("$name.part")
            val bytes = (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
            java.nio.channels.FileChannel.open(part, java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(part, control.resolve(name), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }

        private fun checkHumanHandshake(output: Path) {
            Files.createDirectory(output)
            val id = "00000000-0000-0000-0000-000000000001"
            val other = "00000000-0000-0000-0000-000000000002"
            val baseline = HumanBaseline(0f, "SYNTHETIC_FIRST", Rectangle(10, 20, 8, 80), 1, 0, 0,
                "SYNTHETIC_OWNED_NOT_A_RUNTIME_HWND", "SYNTHETIC_OWNER_NOT_A_RUNTIME_HWND", "SYNTHETIC_RECORDER", true, true, true)
            val rows = mutableListOf<String>()
            fun scenario(name: String, block: () -> Unit) {
                block()
                rows += "$name=PASS"
                println("FISH_R6J_HANDSHAKE_CHECK name=$name result=PASS syntheticOnly=true")
            }
            fun armed() = HumanProtocol(id).also { check(it.arm(baseline)) }
            scenario("no-arm-no-prompt") { check(!HumanProtocol(id).promptAllowed(id, id)) }
            scenario("nonzero-baseline-preserved-and-rejected") {
                val p = HumanProtocol(id)
                check(!p.arm(baseline.copy(range = 1545f)))
                check(p.baseline!!.range == 1545f && p.result == "PREARM_BASELINE_INVALID" && !p.promptAllowed(id, id))
            }
            scenario("listener-required") { check(!HumanProtocol(id).arm(baseline.copy(listenerRegistered = false))) }
            scenario("owner-required") { check(!HumanProtocol(id).arm(baseline.copy(ownerDisabled = false))) }
            scenario("counts-required") { check(!HumanProtocol(id).arm(baseline.copy(http = 1))) }
            scenario("matching-file-and-log-required") {
                val p = armed()
                check(!p.promptAllowed(null, id) && !p.promptAllowed(id, null))
                check(!p.promptAllowed(other, id) && !p.promptAllowed(id, other))
                check(p.promptAllowed(id, id))
            }
            scenario("duplicate-arm-rejected") { check(runCatching { armed().arm(baseline) }.isFailure) }
            scenario("completion-before-arm-rejected") {
                check(runCatching { HumanProtocol(id).capture("PHYSICAL_INPUT_PASS", true, true) }.isFailure)
            }
            scenario("release-and-stability-required") {
                check(runCatching { armed().capture("PHYSICAL_INPUT_PASS", false, true) }.isFailure)
                check(runCatching { armed().capture("PHYSICAL_INPUT_PASS", true, false) }.isFailure)
            }
            scenario("armed-cannot-finish-early") { check(armed().closeDecision(10, 100, id, null, true) == null) }
            scenario("capture-held-without-immediate-close") {
                val p = armed(); p.capture("PHYSICAL_INPUT_PASS", true, true)
                check(p.phase == HumanPhase.HELD && !p.promptAllowed(id, id))
                check(p.closeDecision(10, 100, null, null, true) == null)
                check(p.closeDecision(10, 100, other, null, true) == null)
                check(p.closeDecision(10, 100, id, null, true) == "USER_FINISH")
            }
            scenario("cancel-only-matching-run") {
                check(armed().closeDecision(10, 100, null, id, true) == "USER_CANCEL")
                check(armed().closeDecision(10, 100, null, other, true) == null)
            }
            scenario("bounded-timeout") { check(armed().closeDecision(100, 100, null, null, true) == "CONFIRMATION_TIMEOUT") }
            scenario("explicit-window-close") { check(armed().closeDecision(10, 100, null, null, false) == "WINDOW_CLOSED") }
            scenario("exception-held-then-bounded-cleanup") {
                val p = armed(); p.fail("TEST_EXCEPTION_STAGE", "INCONCLUSIVE")
                check(p.closeDecision(10, 100, null, null, true) == null)
                check(p.closeDecision(100, 100, null, null, true) == "CONFIRMATION_TIMEOUT")
            }
            scenario("forced-flush-artifact-publication") {
                val records = Files.createDirectory(output.resolve("synthetic-protocol-records-not-runtime"))
                publishHumanArtifact(records, "armed.txt", listOf("runId=$id", "status=SYNTHETIC_ARMED_NOT_RUNTIME", "initialRange=0.0"))
                check(Files.readString(records.resolve("armed.txt")).contains("runId=$id"))
                check(!Files.exists(records.resolve("armed.txt.part")))
                check(runCatching { publishHumanArtifact(records, "armed.txt", listOf("overwrite-forbidden")) }.isFailure)
                check(Files.readString(records.resolve("armed.txt")).contains("runId=$id"))
            }
            publishHumanArtifact(output, "checks.txt", listOf("mode=DETERMINISTIC_HEADLESS_NO_WINDOW", "count=${rows.size}") + rows)
            System.out.flush()
        }
    }

    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private val sequence = AtomicInteger()
    private data class ReceivedMouse(val id: Int, val screen: Point, val button: Int, val mask: Int)
    private interface MouseHitApi : StdCallLibrary {
        fun WindowFromPoint(point: POINT.ByValue): HWND?
    }
    private val mouseHitApi by lazy { Native.load("user32", MouseHitApi::class.java) }
    private data class NativeMouseTarget(val destinationVerified: Boolean, val pointerAtPlan: Boolean,
        val thumbHit: Boolean, val leftDown: Boolean, val observation: String)

    private enum class HumanPhase { PREPARING, ARMED, HELD }
    private data class HumanBaseline(val range: Float, val first: String, val thumb: Rectangle,
        val queries: Int, val http: Int, val bindings: Int, val ownedHwnd: String, val logicalOwnerHwnd: String,
        val recorderId: String, val listenerRegistered: Boolean, val ownerDisabled: Boolean, val windowShowing: Boolean) {
        fun valid() = range == 0f && first.isNotBlank() && thumb.width > 0 && thumb.height > 0 &&
            queries == 1 && http == 0 && bindings == 0 && ownedHwnd.isNotBlank() && logicalOwnerHwnd.isNotBlank() &&
            recorderId.isNotBlank() && listenerRegistered && ownerDisabled && windowShowing
    }
    private class HumanProtocol(val runId: String) {
        var phase = HumanPhase.PREPARING
            private set
        var baseline: HumanBaseline? = null
            private set
        var result = "NOT_EXECUTED"
            private set
        var status = "PREPARING"
            private set
        fun arm(value: HumanBaseline): Boolean {
            check(phase == HumanPhase.PREPARING)
            baseline = value
            if (!value.valid()) {
                fail("PREARM", if (value.range != 0f) "PREARM_BASELINE_INVALID" else "INCONCLUSIVE")
                return false
            }
            phase = HumanPhase.ARMED; status = "ARMED"
            return true
        }
        fun promptAllowed(fileRunId: String?, logRunId: String?) = phase == HumanPhase.ARMED &&
            baseline?.valid() == true && fileRunId == runId && logRunId == runId
        fun capture(classification: String, released: Boolean, stable: Boolean) {
            check(phase == HumanPhase.ARMED && released && stable)
            result = classification; status = "CAPTURE_COMPLETE"; phase = HumanPhase.HELD
        }
        fun fail(stage: String, classification: String) {
            result = classification; status = "RECORDER_ERROR:$stage"; phase = HumanPhase.HELD
        }
        fun closeDecision(now: Long, deadline: Long, finishId: String?, cancelId: String?, windowOpen: Boolean): String? = when {
            !windowOpen -> "WINDOW_CLOSED"
            cancelId == runId -> "USER_CANCEL"
            phase == HumanPhase.HELD && finishId == runId -> "USER_FINISH"
            now >= deadline -> "CONFIRMATION_TIMEOUT"
            else -> null
        }
    }
    private class HumanMouseControl(val protocol: HumanProtocol) {
        val armed get() = protocol.phase == HumanPhase.ARMED
        var started = false
        var released = false
        var classification: String? = null
        var presses = 0
        var drags = 0
        var releases = 0
        var events = 0
        var focusLossesDuringDrag = 0
        var temporalUncertainty = false
        var beforeRange = 0f
        var beforeFirst = ""
        var thumb = Rectangle()
    }

    private inner class Fixture(val width: Int, val server: MockWebServer, val output: DesktopAudioOutput = PreviewOutputFake(),
        val human: HumanMouseControl? = null,
        catalog: (FishAudioModelQuery) -> FishAudioModelPage = {
            FishAudioModelPage(items = listOf(FishAudioModel("r3-voice", "R3 synthetic voice",
                samples = listOf(FishAudioSample(audio = syntheticOfficialSample)))), total = 1)
        }) {
        val root = Files.createTempDirectory("fish-r3-native-")
        val scope = CoroutineScope(SupervisorJob() + awt)
        val credentials = DesktopFishAudioCredentials(InMemoryDesktopSecretStore()).also { it.save("synthetic-r3-only") }
        val runtime = DesktopFishPreviewRuntime(
            DesktopFishPreviewTransport(credentials, OkHttpClient(), server.url("/").toString().trimEnd('/')), output,
            stores = { DesktopFishPreviewStore(Files.createDirectory(root.resolve("cache"))) },
            samples = DesktopFishSampleTransport(OkHttpClient()) { server.url(it.encodedPath) })
        val preview = runtime.openSession()
        var queries = 0
        val queriedPages = mutableListOf<Int>()
        val picker = DesktopFishPickerController(scope) { query ->
            queries++; queriedPages += query.pageNumber; catalog(query)
        }
        val child = AtomicReference<ComposeWindow?>()
        val owner = AtomicReference<ComposeWindow?>()
        val title = if (human == null) "Fish R3 isolated native ${sequence.incrementAndGet()}"
            else "R6J 手动物理鼠标 · 合成音色库（等待记录器确认）"
        val robot = Robot().apply { isAutoWaitForIdle = false }
        var open by mutableStateOf(true)
        var closes = 0
        var bindings = 0
        var windowState: WindowState? = null
        var observeScrollbar = false
        var thumbSize = Size.Zero
        var thumbOutlineCount = 0
        val mouseEvents = mutableListOf<String>()
        val receivedMouse = mutableListOf<ReceivedMouse>()
        private var mouseDiagnosticRegion: Rectangle? = null
        private var mouseObservationSequence = 0
        private var mouseOwnershipChecked = false
        private val nativeOwnedEvents = AtomicInteger()
        private val nativeOwnedPresses = AtomicInteger()
        private val nativeWindowSelfEvents = AtomicInteger()
        private val nativeDescendantEvents = AtomicInteger()
        private val nativeOtherEvents = AtomicInteger()
        private var nativeProbeUnavailable = false
        private var nativeDestinationUnverified = false
        private fun nativeHitTest(phase: String, planned: Point, thumb: Rectangle): NativeMouseTarget? {
            try {
                val w = checkNotNull(child.get())
                val user = User32.INSTANCE
                val sampledNanos = System.nanoTime()
                val keyState = user.GetAsyncKeyState(0x01).toInt() and 0xffff
                val leftDown = keyState and 0x8000 != 0
                val cursor = POINT()
                check(user.GetCursorPos(cursor)) { "GetCursorPos unavailable" }
                val awt = MouseInfo.getPointerInfo().location
                val owned = HWND(Native.getWindowPointer(w))
                fun value(handle: HWND?): Long = handle?.pointer?.let(Pointer::nativeValue) ?: 0L
                fun same(a: HWND?, b: HWND?) = value(a) != 0L && value(a) == value(b)
                fun root(handle: HWND?): HWND? = if (value(handle) == 0L) null else user.GetAncestor(handle, 2)
                val jvmPid = ProcessHandle.current().pid()
                fun inJvm(handle: HWND?): Boolean? {
                    if (value(handle) == 0L) return null
                    val pid = IntByReference()
                    val thread = user.GetWindowThreadProcessId(handle, pid)
                    if (thread == 0 || pid.value == 0) return null
                    return Integer.toUnsignedLong(pid.value) == jvmPid
                }
                val ownedRoot = root(owned)
                val hit = mouseHitApi.WindowFromPoint(POINT.ByValue(cursor.x, cursor.y))
                val hitRoot = root(hit)
                val foreground = user.GetForegroundWindow()
                val foregroundRoot = root(foreground)
                fun identity(handle: HWND?): String = when {
                    value(handle) == 0L -> "UNKNOWN"
                    inJvm(handle) == true && same(root(handle), owned) -> "OWNED:0x${value(handle).toString(16)}"
                    inJvm(handle) == true -> "OTHER_IN_JVM"
                    inJvm(handle) == false -> "OTHER_PROCESS"
                    else -> "UNKNOWN"
                }
                val listenerRegistered = Toolkit.getDefaultToolkit().getAWTEventListeners().any { listener ->
                    if (listener is java.awt.event.AWTEventListenerProxy) listener.listener === observer &&
                        listener.eventMask and AWTEvent.MOUSE_EVENT_MASK != 0L else listener === observer
                }
                val coordinatesMatch = awt.x == cursor.x && awt.y == cursor.y
                val pointerAtPlan = awt == planned && cursor.x == planned.x && cursor.y == planned.y
                val thumbHit = thumb.contains(awt) && thumb.contains(cursor.x, cursor.y)
                val ownedRootValid = same(owned, ownedRoot) && inJvm(owned) == true
                val hitOwned = same(hitRoot, owned) && inJvm(hit) == true
                val foregroundOwned = same(foreground, owned) && same(foregroundRoot, owned)
                val destination = ownedRootValid && hitOwned && foregroundOwned && coordinatesMatch &&
                    listenerRegistered && observeScrollbar && w.isShowing
                if (!destination) nativeDestinationUnverified = true
                val observation = when {
                    value(hitRoot) != 0L && !hitOwned -> "HIT_TEST_TARGET_MISMATCH"
                    value(foreground) != 0L && !foregroundOwned -> "FOREGROUND_MISMATCH"
                    phase == "after-press" && !leftDown -> "NATIVE_BUTTON_STATE_NOT_OBSERVED"
                    phase == "after-press" && leftDown && nativeOwnedPresses.get() == 0 -> "NATIVE_DOWN_WITHOUT_AWT_EVENT"
                    else -> "UNKNOWN"
                }
                println("FISH_R6H_NATIVE_HITTEST phase=$phase sampledNanos=$sampledNanos available=true " +
                    "owned=${identity(owned)} ownedRoot=${identity(ownedRoot)} jvmPid=$jvmPid " +
                    "planned=$planned awt=$awt native=${Point(cursor.x, cursor.y)} coordinatesMatch=$coordinatesMatch " +
                    "pointerAtPlan=$pointerAtPlan thumbHit=$thumbHit hit=${identity(hit)} hitRoot=${identity(hitRoot)} " +
                    "hitRootOwned=$hitOwned hitInJvm=${inJvm(hit)} foreground=${identity(foreground)} " +
                    "foregroundRoot=${identity(foregroundRoot)} foregroundOwned=$foregroundOwned ownedRootValid=$ownedRootValid " +
                    "showing=${w.isShowing} active=${w.isActive} focused=${w.isFocused} " +
                    "listenerRegistered=$listenerRegistered observeScrollbar=$observeScrollbar " +
                    "leftKeyState=$keyState leftDown=$leftDown ownedEvents=${nativeOwnedEvents.get()} " +
                    "ownedPresses=${nativeOwnedPresses.get()} windowSelfEvents=${nativeWindowSelfEvents.get()} " +
                    "descendantEvents=${nativeDescendantEvents.get()} regionOtherEvents=${nativeOtherEvents.get()} " +
                    "destinationVerified=$destination observation=$observation scale=${w.graphicsConfiguration.defaultTransform}")
                System.out.flush()
                return NativeMouseTarget(destination, pointerAtPlan, thumbHit, leftDown, observation)
            } catch (failure: Throwable) {
                nativeProbeUnavailable = true
                println("FISH_R6H_NATIVE_HITTEST phase=$phase available=false failureType=${failure.javaClass.name} " +
                    "observation=UNKNOWN status=INPUT_DESTINATION_UNVERIFIED")
                System.out.flush()
                return null
            }
        }
        private fun mouseWindow(source: java.awt.Component): java.awt.Window? =
            if (source is java.awt.Window) source else SwingUtilities.getWindowAncestor(source)

        private fun verifyMouseOwnership() {
            if (mouseOwnershipChecked) return
            val w = assertNotNull(child.get())
            fun components(component: java.awt.Component): List<java.awt.Component> = listOf(component) +
                if (component is java.awt.Container) component.components.flatMap(::components) else emptyList()
            val skia = assertNotNull(components(w).firstOrNull { it.javaClass.name.startsWith("org.jetbrains.skiko.SkiaLayer") })
            assertNull(SwingUtilities.getWindowAncestor(w), "The old ancestor lookup omits this Frame itself")
            assertSame(w, mouseWindow(w), "A Window event source must resolve to itself")
            assertSame(w, SwingUtilities.getWindowAncestor(skia))
            assertSame(w, mouseWindow(skia), "The existing Skia descendant path must remain valid")
            assertNotSame(w, mouseWindow(assertNotNull(owner.get())), "The logical owner must never count as the picker")
            assertNull(mouseWindow(java.awt.Canvas()), "A detached component has no owned window")
            println("FISH_R6G_OWNER_CHECK requestedWidthDp=$width oldSelfNull=true newSelfOwned=true " +
                "skiaType=${skia.javaClass.name} oldSkiaOwned=true newSkiaOwned=true ownerRejected=true detachedRejected=true")
            mouseOwnershipChecked = true
        }
        private val observer = AWTEventListener { event ->
            if (event is WindowEvent && event.id == WindowEvent.WINDOW_OPENED) {
                (event.window as? ComposeWindow)?.takeIf { it.title == title }?.let(child::set)
            }
            if (human?.armed == true && event is WindowEvent && event.window === child.get()) {
                println("FISH_R6J_WINDOW runId=${human.protocol.runId} id=${event.id} active=${event.window.isActive} focused=${event.window.isFocused} " +
                    "gestureStarted=${human.started} gestureReleased=${human.released}")
                if (human.started && !human.released && event.id in listOf(WindowEvent.WINDOW_DEACTIVATED, WindowEvent.WINDOW_LOST_FOCUS)) {
                    human.focusLossesDuringDrag++
                    human.classification = "FOREGROUND_OR_OCCLUSION"
                }
                System.out.flush()
            }
            if (event is KeyEvent && event.keyCode == KeyEvent.VK_ESCAPE) {
                val window = SwingUtilities.getWindowAncestor(event.component)
                if (window === child.get() || window?.owner === child.get()) {
                    println("FISH_R3_AWT type=${event.id} consumed=${event.isConsumed} focus=${KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.javaClass?.simpleName}")
                }
            }
            if (observeScrollbar && event is MouseEvent && (human == null || mouseWindow(event.component) === child.get())) {
                val source = event.component
                val oldTarget = SwingUtilities.getWindowAncestor(source)
                val target = mouseWindow(source)
                val owned = child.get()
                val classification = when {
                    owned != null && target === owned && source === owned -> "OWNED_WINDOW_SELF"
                    owned != null && target === owned -> "OWNED_DESCENDANT"
                    else -> "OTHER_IN_JVM"
                }
                if (owned != null && (target === owned || mouseDiagnosticRegion?.contains(event.xOnScreen, event.yOnScreen) == true)) {
                    if (classification == "OTHER_IN_JVM") nativeOtherEvents.incrementAndGet()
                    println("FISH_R6G_MOUSE sequence=${++mouseObservationSequence} nanos=${System.nanoTime()} " +
                        "classification=$classification id=${event.id} sourceType=${source.javaClass.name} " +
                        "screen=${event.xOnScreen},${event.yOnScreen} button=${event.button} mask=${event.modifiersEx} " +
                        "targetOwned=${target === owned} oldTargetOwned=${oldTarget === owned} sourceSelf=${source === owned} " +
                        "targetIsLogicalOwner=${target === owner.get()} active=${owned.isActive} focused=${owned.isFocused}")
                }
                if (owned != null && target === owned) {
                    nativeOwnedEvents.incrementAndGet()
                    if (event.id == MouseEvent.MOUSE_PRESSED) nativeOwnedPresses.incrementAndGet()
                    if (classification == "OWNED_WINDOW_SELF") nativeWindowSelfEvents.incrementAndGet()
                    else nativeDescendantEvents.incrementAndGet()
                    receivedMouse += ReceivedMouse(event.id, Point(event.xOnScreen, event.yOnScreen), event.button, event.modifiersEx)
                    val row = "id=${event.id} screen=${event.xOnScreen},${event.yOnScreen} local=${event.x},${event.y} " +
                        "button=${event.button} mask=${event.modifiersEx} consumed=${event.isConsumed} " +
                        "component=${source.javaClass.name} targetOwned=true active=${owned.isActive} ownership=$classification"
                    mouseEvents += row
                    println("FISH_R4_MOUSE $row")
                    if (human != null) try { observeHumanMouse(event) } catch (failure: Throwable) {
                        human.classification = "INCONCLUSIVE"
                        println("FISH_R6J_OBSERVER_ERROR runId=${human.protocol.runId} type=${failure.javaClass.name} noRetry=true")
                        System.out.flush()
                    }
                }
            }
        }
        val scene: ImageComposeScene
        init {
            Toolkit.getDefaultToolkit().addAWTEventListener(observer, AWTEvent.WINDOW_EVENT_MASK or AWTEvent.KEY_EVENT_MASK or
                AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK or
                (if (human == null) 0L else AWTEvent.WINDOW_FOCUS_EVENT_MASK))
            picker.search()
            scene = ImageComposeScene(16, 16) {
                val state = rememberWindowState(width = width.dp, height = 720.dp)
                SideEffect { windowState = state }
                Window(onCloseRequest = {}, title = if (human == null) "R3 synthetic owner" else "R6J 合成 owner · 无真实数据",
                    state = rememberWindowState(width = 500.dp, height = 200.dp)) {
                    val parent = window
                    SideEffect { owner.set(parent) }
                    CompositionLocalProvider(LocalDesktopFishParentWindow provides parent) {
                        if (open) DesktopFishPickerWindow(onClose = { closes++; open = false; picker.close(); preview.requestClose() },
                            state = state, title = title) {
                            val originalStyle = LocalScrollbarStyle.current
                            val observedShape = remember(originalStyle.shape) {
                                object : Shape {
                                    override fun createOutline(size: Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                        density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.Outline {
                                        thumbSize = size; thumbOutlineCount++
                                        return originalStyle.shape.createOutline(size, layoutDirection, density)
                                    }
                                }
                            }
                            CompositionLocalProvider(LocalScrollbarStyle provides originalStyle.copy(shape = observedShape)) {
                                DesktopFishPickerContent(picker, true, { closes++; open = false; picker.close(); preview.requestClose() }, preview) { bindings++ }
                            }
                        }
                    }
                }
            }
        }
        suspend fun frame() { scene.render().close(); yield(); delay(20) }
        suspend fun await(check: () -> Boolean) = withTimeout(10_000) { while (!check()) frame() }
        suspend fun ready() {
            await { child.get()?.isShowing == true && nodes().any {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { label -> label.startsWith("使用音色 ·") } == true
            } }
            child.get()!!.toFront(); child.get()!!.requestFocus()
            await { child.get()!!.isActive }
            assertFalse(owner.get()!!.isEnabled)
        }
        fun nodes(): List<SemanticsNode> {
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            return child.get()?.semanticsOwners.orEmpty().flatMap { walk(it.rootSemanticsNode) }
        }
        fun has(label: String) = nodes().any { it.label(label) }
        fun node(label: String): SemanticsNode {
            val matches = nodes().filter { it.label(label) }
            return matches.firstOrNull { it.config.contains(SemanticsActions.SetText) } ?:
                matches.firstOrNull { it.config.getOrNull(SemanticsProperties.Role) == Role.Button } ?: matches.first()
        }
        suspend fun click(label: String) {
            val n = node(label)
            val point = n.positionOnScreen
            val x = (point.x + n.boundsInRoot.width / 2).roundToInt()
            val y = (point.y + n.boundsInRoot.height / 2).roundToInt()
            assertTrue(child.get()!!.bounds.contains(x, y), "Synthetic target outside owned native window")
            robot.mouseMove(x, y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            repeat(4) { frame() }
        }
        suspend fun key(code: Int) {
            robot.keyPress(code); robot.keyRelease(code)
            repeat(5) { frame() }
        }
        suspend fun text(label: String, value: String) {
            click(label)
            assertEquals(true, node(label).config.getOrNull(SemanticsProperties.Focused), "Native text field must actually have focus")
            assertTrue(node(label).config[SemanticsActions.SetText].action!!(AnnotatedString(value)))
            repeat(3) { frame() }
        }
        fun stopButton(): SemanticsNode = nodes().single { it.label("停止预览") &&
            it.config.getOrNull(SemanticsProperties.Role) == Role.Button }
        fun assertStopVisibleInTopLayer() {
            val stop = stopButton()
            assertFalse(stop.config.contains(SemanticsProperties.Disabled))
            assertTrue(stop.boundsInWindow.width >= 32 && stop.boundsInWindow.height >= 32)
            val position = stop.positionOnScreen
            val client = child.get()!!.contentPane
            val visible = java.awt.Rectangle(client.locationOnScreen, client.size)
            assertTrue(visible.contains(position.x.toDouble(), position.y.toDouble()))
            assertTrue(visible.contains((position.x + stop.boundsInRoot.width - 1).toDouble(),
                (position.y + stop.boundsInRoot.height - 1).toDouble()), "Whole Stop must be inside real native client viewport")
            if (has("Fish 设置面板")) assertSame(node("Fish 设置面板").root, stop.root, "Stop must belong to top Popup layer")
            else assertTrue(stop.boundsInWindow.top < 160, "Stop must be prominent near the top, not below results")
        }
        suspend fun stopByUi(keyboard: Boolean) {
            assertStopVisibleInTopLayer()
            if (keyboard) {
                assertTrue(stopButton().config[SemanticsActions.RequestFocus].action!!())
                repeat(3) { frame() }
                assertEquals(true, stopButton().config.getOrNull(SemanticsProperties.Focused))
                key(KeyEvent.VK_ENTER)
            } else click("停止预览")
            await { !preview.state.value.busy && !preview.state.value.playing }
            assertFalse(output.isPlaying())
            assertFalse(nodes().any { it.label("停止预览") && it.config.getOrNull(SemanticsProperties.Role) == Role.Button })
        }
        fun snapshot(phase: String) {
            val w = child.get()!!
            val stop = nodes().filter { it.label("停止预览") && it.config.getOrNull(SemanticsProperties.Role) == Role.Button }
            val fields = nodes().filter { it.config.contains(SemanticsActions.SetText) }
            println("FISH_R3_SNAPSHOT phase=$phase frame=${w.bounds} client=${w.contentPane.size} scale=${w.graphicsConfiguration.defaultTransform} " +
                "wide=${has("Fish 宽屏分栏")} compact=${has("Fish 紧凑单列")} popup=${has("Fish 折叠设置内容")} busy=${preview.state.value.busy} playing=${preview.state.value.playing} " +
                "stopCount=${stop.size} stop=${stop.map { "${it.id}:${it.boundsInWindow}:${it.positionOnScreen}:root${it.root.hashCode()}" }} " +
                "fieldRoots=${fields.map { it.root.hashCode() }.distinct()} ownerEnabled=${owner.get()?.isEnabled} closes=$closes requests=${server.requestCount}")
        }
        fun visiblePageItems(): List<String> {
            val viewport = node("Fish 音色列表")
            val top = viewport.positionOnScreen.y
            val bottom = top + viewport.boundsInRoot.height
            return nodes().filter {
                it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { label -> label.startsWith("预览目标 · Page ") } == true &&
                    it.positionOnScreen.y < bottom && it.positionOnScreen.y + it.layoutInfo.height > top
            }.map {
                "${it.config[SemanticsProperties.ContentDescription]}:top=${it.positionOnScreen.y}:height=${it.layoutInfo.height}:offsetFromViewport=${top - it.positionOnScreen.y}"
            }
        }
        fun scrollbarSnapshot(phase: String) {
            verifyMouseOwnership()
            val w = child.get()!!
            val bar = node("音色列表滚动条")
            val list = node("Fish 音色列表")
            val range = list.config[SemanticsProperties.VerticalScrollAxisRange]
            mouseDiagnosticRegion = Rectangle(bar.positionOnScreen.x.roundToInt(), bar.positionOnScreen.y.roundToInt(),
                bar.boundsInRoot.width.roundToInt(), bar.boundsInRoot.height.roundToInt())
            println("FISH_R6G_OBSERVED phase=$phase requestedWidthDp=$width " +
                "ownershipObservation=${if (receivedMouse.isEmpty()) "NO_OBSERVED_EVENT" else "OWNED_EVENT_OBSERVED"} " +
                "eventCount=${receivedMouse.size} pressCount=${receivedMouse.count { it.id == MouseEvent.MOUSE_PRESSED }} " +
                "diagnosticRegion=$mouseDiagnosticRegion")
            if (phase == "R5-real-drag-completed") println("FISH_R6H_NATIVE_HITTEST phase=verified-drag-completed " +
                "requestedWidthDp=$width observation=OWNED_INPUT_DELIVERED ownedPresses=${nativeOwnedPresses.get()}")
            println("FISH_R4_SCROLL phase=$phase requestedWidthDp=$width frame=${w.bounds} clientOrigin=${w.contentPane.locationOnScreen} " +
                "client=${w.contentPane.size} scale=${w.graphicsConfiguration.defaultTransform} active=${w.isActive} focused=${w.isFocused} " +
                "focus=${w.focusOwner?.javaClass?.name} ownerMatches=${w.owner === owner.get()} ownerEnabled=${owner.get()?.isEnabled} " +
                "range=${range.value()}/${range.maxValue()} reverse=${range.reverseScrolling} " +
                "listScreen=${list.positionOnScreen} listBounds=${list.boundsInRoot} trackScreen=${bar.positionOnScreen} trackBounds=${bar.boundsInRoot} " +
                "thumbOutline=$thumbSize outlineCount=$thumbOutlineCount items=${visiblePageItems()} queries=$queries requests=${server.requestCount} bindings=$bindings")
        }
        fun firstVisiblePageItem() = visiblePageItems().first().substringBefore(":top=")

        fun armHumanMouse(control: Path): Boolean {
            val h = checkNotNull(human)
            check(!h.armed && !h.started)
            val bar = node("音色列表滚动条")
            h.thumb = Rectangle(bar.positionOnScreen.x.roundToInt(), bar.positionOnScreen.y.roundToInt(),
                thumbSize.width.roundToInt(), thumbSize.height.roundToInt())
            h.beforeRange = node("Fish 音色列表").config[SemanticsProperties.VerticalScrollAxisRange].value()
            h.beforeFirst = firstVisiblePageItem()
            val w = checkNotNull(child.get())
            val logicalOwner = checkNotNull(owner.get())
            fun ownedIdentity(window: ComposeWindow): String {
                val handle = HWND(Native.getWindowPointer(window))
                val pid = IntByReference()
                check(User32.INSTANCE.GetWindowThreadProcessId(handle, pid) != 0)
                check(Integer.toUnsignedLong(pid.value) == ProcessHandle.current().pid())
                val root = User32.INSTANCE.GetAncestor(handle, 2)
                check(Pointer.nativeValue(root.pointer) == Pointer.nativeValue(handle.pointer))
                return "OWNED:0x${Pointer.nativeValue(handle.pointer).toString(16)}"
            }
            val listener = Toolkit.getDefaultToolkit().getAWTEventListeners().any { value ->
                if (value is java.awt.event.AWTEventListenerProxy) value.listener === observer &&
                    value.eventMask and AWTEvent.MOUSE_EVENT_MASK != 0L else value === observer
            }
            val baseline = HumanBaseline(h.beforeRange, h.beforeFirst, Rectangle(h.thumb), queries, server.requestCount,
                bindings, ownedIdentity(w), ownedIdentity(logicalOwner), "AWT:${System.identityHashCode(observer).toString(16)}",
                listener && observeScrollbar, !logicalOwner.isEnabled, w.isShowing)
            if (!h.protocol.arm(baseline)) {
                println("FISH_R6J_PREARM_REJECT runId=${h.protocol.runId} result=${h.protocol.result} actualRange=${h.beforeRange} thumb=${h.thumb}")
                System.out.flush()
                return false
            }
            publishHumanArtifact(control, "armed.txt", listOf("runId=${h.protocol.runId}", "status=ARMED",
                "initialRange=${baseline.range}", "first=${baseline.first}",
                "thumb=${h.thumb.x},${h.thumb.y},${h.thumb.width},${h.thumb.height}",
                "queries=$queries", "http=${server.requestCount}", "bindings=$bindings",
                "ownedHwnd=${baseline.ownedHwnd}", "logicalOwnerHwnd=${baseline.logicalOwnerHwnd}",
                "recorderId=${baseline.recorderId}", "listenerRegistered=${baseline.listenerRegistered}",
                "ownerDisabled=${baseline.ownerDisabled}", "windowShowing=${baseline.windowShowing}"))
            println("FISH_R6J_ARMED runId=${h.protocol.runId} status=ARMED initialRange=${baseline.range} " +
                "thumb=${h.thumb} recorderId=${baseline.recorderId} listenerRegistered=${baseline.listenerRegistered} " +
                "ownedHwnd=${baseline.ownedHwnd} activationOutsideListExcluded=true maxControlledGestures=1")
            System.out.flush()
            return true
        }

        private fun observeHumanMouse(event: MouseEvent) {
            val h = checkNotNull(human)
            if (!h.armed || h.released || event.id !in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_DRAGGED, MouseEvent.MOUSE_RELEASED)) return
            val point = Point(event.xOnScreen, event.yOnScreen)
            if (!h.started) {
                if (event.id != MouseEvent.MOUSE_PRESSED || event.button != MouseEvent.BUTTON1) return
                val list = node("Fish 音色列表")
                val bar = node("音色列表滚动条")
                fun rect(n: SemanticsNode) = Rectangle(n.positionOnScreen.x.roundToInt(), n.positionOnScreen.y.roundToInt(),
                    n.boundsInRoot.width.roundToInt(), n.boundsInRoot.height.roundToInt())
                if (!rect(list).contains(point) && !rect(bar).contains(point)) {
                    println("FISH_R6J_MOUSE runId=${h.protocol.runId} excluded=ACTIVATION_OR_NON_LIST_PRESS sourceType=${event.component.javaClass.name} screen=$point")
                    System.out.flush()
                    return
                }
                h.started = true
                if (!h.thumb.contains(point)) h.classification = "MANUAL_HIT_MISS"
            }
            h.events++
            if (h.events > 1024) {
                h.classification = "INCONCLUSIVE"
                h.released = event.id == MouseEvent.MOUSE_RELEASED
                return
            }
            when (event.id) {
                MouseEvent.MOUSE_PRESSED -> h.presses++
                MouseEvent.MOUSE_DRAGGED -> h.drags++
                MouseEvent.MOUSE_RELEASED -> { h.releases++; h.released = true }
            }
            val probe = nativeHitTest("R6J-event-${event.id}-${h.events}", point, h.thumb)
            if (probe == null) h.classification = "INCONCLUSIVE"
            else if (!probe.destinationVerified) h.classification = "FOREGROUND_OR_OCCLUSION"
            else if (event.id in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED) && !probe.pointerAtPlan) {
                h.temporalUncertainty = true
                h.classification = "INCONCLUSIVE"
            }
            if (h.presses != 1 || (event.id == MouseEvent.MOUSE_DRAGGED && event.modifiersEx and InputEvent.BUTTON1_DOWN_MASK == 0)) {
                h.classification = "INCONCLUSIVE"
            }
            println("FISH_R6J_MOUSE runId=${h.protocol.runId} id=${event.id} sourceType=${event.component.javaClass.name} targetOwned=true " +
                "screen=$point eventWhen=${event.`when`} button=${event.button} mask=${event.modifiersEx} " +
                "eventInInitialThumb=${h.thumb.contains(point)} range=${node("Fish 音色列表").config[SemanticsProperties.VerticalScrollAxisRange].value()} " +
                "first=${firstVisiblePageItem()} presses=${h.presses} drags=${h.drags} releases=${h.releases} " +
                "classification=${h.classification ?: "PENDING"}")
            System.out.flush()
        }

        fun probeHumanDrag() {
            val h = checkNotNull(human)
            if (!h.started || h.released) return
            val probe = nativeHitTest("R6J-drag-poll", MouseInfo.getPointerInfo().location, h.thumb)
            if (probe == null) h.classification = "INCONCLUSIVE"
            else if (!probe.destinationVerified) h.classification = "FOREGROUND_OR_OCCLUSION"
        }

        fun assertPagerLayout() {
            val w = child.get()!!
            val client = Rectangle(w.contentPane.locationOnScreen, w.contentPane.size)
            val page = picker.state.value.query.pageNumber
            val input = node("跳转页码")
            val jumpButton = node("跳转")
            assertTrue(input.config.contains(SemanticsActions.SetText), "Pager input must be the editable functional node")
            assertEquals(Role.Button, jumpButton.config.getOrNull(SemanticsProperties.Role))
            assertTrue(jumpButton.config.contains(SemanticsActions.OnClick))
            assertSame(input.root, jumpButton.root, "Pager controls must belong to the same native semantics owner")
            fun ancestry(layout: androidx.compose.ui.layout.LayoutInfo): List<androidx.compose.ui.layout.LayoutInfo> {
                val result = mutableListOf<androidx.compose.ui.layout.LayoutInfo>()
                var current: androidx.compose.ui.layout.LayoutInfo? = layout
                while (current != null) {
                    val value = current
                    assertFalse(result.any { it === value }, "Cycle in pager LayoutInfo ancestry")
                    result += value
                    current = value.parentInfo
                }
                return result
            }
            val inputPath = ancestry(input.layoutInfo)
            val buttonPath = ancestry(jumpButton.layoutInfo)
            val pair = assertNotNull(inputPath.firstOrNull { candidate -> buttonPath.any { it === candidate } },
                "Pager input and button must share a real layout ancestor")
            val inputItem = assertNotNull(inputPath.firstOrNull { it.parentInfo === pair },
                "Pager input must have a direct outer layout item in the pair")
            assertFalse(inputItem === input.layoutInfo, "Cannot substitute internal editable geometry for the outer input item")
            assertSame(pair, jumpButton.layoutInfo.parentInfo, "Jump button must be a direct child of the same pair Row")
            val pager = assertNotNull(node("上一页").layoutInfo.parentInfo)
            assertSame(pager, node("第 $page 页 / 共 6 页").layoutInfo.parentInfo)
            assertSame(pager, node("下一页").layoutInfo.parentInfo)
            assertSame(pager, pair.parentInfo, "The direct input/button pair must be a sibling of the other pager items")
            val coordinates = inputItem.coordinates
            assertTrue(inputItem.isAttached && inputItem.isPlaced && coordinates.isAttached)
            assertTrue(inputItem.width > 0 && inputItem.height > 0)
            assertEquals(inputItem.width, coordinates.size.width, "Outer input coordinates must cover the whole layout item")
            assertEquals(inputItem.height, coordinates.size.height, "Outer input must not expose padded inner coordinates")
            val inputOrigin = coordinates.localToScreen(androidx.compose.ui.geometry.Offset.Zero)
            val inputBounds = Rectangle(inputOrigin.x.roundToInt(), inputOrigin.y.roundToInt(), inputItem.width, inputItem.height)
            fun bounds(label: String): Rectangle {
                if (label == "跳转页码") return inputBounds
                val n = node(label)
                val root = generateSequence(n) { it.parent }.last()
                val screen = n.boundsInWindow.translate(root.positionOnScreen - root.positionInWindow)
                if (label == "跳转") {
                    assertEquals(jumpButton.layoutInfo.width.toFloat(), screen.width, "Button semantics must cover its full layout width")
                    assertEquals(jumpButton.layoutInfo.height.toFloat(), screen.height, "Button semantics must cover its full layout height")
                }
                return Rectangle(screen.left.roundToInt(), screen.top.roundToInt(), screen.width.roundToInt(), screen.height.roundToInt())
            }
            val controls = listOf("上一页", "第 $page 页 / 共 6 页", "跳转页码", "跳转", "下一页").associateWith(::bounds)
            println("FISH_R6C_PAGER_OUTER requestedWidthDp=$width page=$page inputNode=${input.id} buttonNode=${jumpButton.id} " +
                "inputItem=${System.identityHashCode(inputItem)} inputParent=${inputItem.parentInfo?.let(System::identityHashCode)} " +
                "buttonParent=${jumpButton.layoutInfo.parentInfo?.let(System::identityHashCode)} pair=${System.identityHashCode(pair)} " +
                "pager=${System.identityHashCode(pager)} inputScreen=$inputBounds buttonScreen=${controls.getValue("跳转")} " +
                "gap=${controls.getValue("跳转").x - inputBounds.x - inputBounds.width} " +
                "overlap=${minOf(inputBounds.y + inputBounds.height, controls.getValue("跳转").y + controls.getValue("跳转").height) - maxOf(inputBounds.y, controls.getValue("跳转").y)}")
            // Read-only diagnostics precede every geometry assertion, including the original adjacency predicate.
            val marker = "FISH_R6A_PAGER_GEOMETRY"
            println("$marker WINDOW requestedWidthDp=$width frame=${w.bounds} client=$client clientSize=${w.contentPane.size} " +
                "scale=${w.graphicsConfiguration.defaultTransform} active=${w.isActive} focused=${w.isFocused} " +
                "queries=$queries http=${server.requestCount} bindings=$bindings")
            controls.keys.forEach { label ->
                val candidates = nodes().filter { it.label(label) }
                val chosen = node(label)
                val reason = when {
                    candidates.any { it.config.contains(SemanticsActions.SetText) } -> "first SetText match"
                    candidates.any { it.config.getOrNull(SemanticsProperties.Role) == Role.Button } -> "first Role.Button match"
                    else -> "first label match"
                }
                println("$marker MATCH label=$label candidates=${candidates.map { it.id }} chosen=${chosen.id} reason=$reason measuredControlRect=${controls[label]}")
                candidates.forEach { n ->
                    val path = generateSequence(n) { it.parent }.take(16).map { it.id }.toList()
                    val semanticRoot = generateSequence(n) { it.parent }.last()
                    val windowOrigin = semanticRoot.positionOnScreen - semanticRoot.positionInWindow
                    val screenBounds = n.boundsInWindow.translate(windowOrigin)
                    println("$marker NODE label=$label id=${n.id} chosen=${n.id == chosen.id} parent=${n.parent?.id} " +
                        "children=${n.children.map { it.id }} pathToRoot=$path semanticRootId=${semanticRoot.id} " +
                        "description=${n.config.getOrNull(SemanticsProperties.ContentDescription)} text=${n.config.getOrNull(SemanticsProperties.Text)} " +
                        "role=${n.config.getOrNull(SemanticsProperties.Role)} keys=${n.config.map { it.key.name }} " +
                        "disabled=${n.config.contains(SemanticsProperties.Disabled)} onClick=${n.config.contains(SemanticsActions.OnClick)} " +
                        "setText=${n.config.contains(SemanticsActions.SetText)} " +
                        "positionOnScreen=${n.positionOnScreen} positionInWindow=${n.positionInWindow} positionInRoot=${n.positionInRoot} " +
                        "boundsInWindow=${n.boundsInWindow} boundsInRoot=${n.boundsInRoot} touchBoundsInRoot=${n.touchBoundsInRoot} " +
                        "semanticRootDerivedWindowOrigin=$windowOrigin screenBoundsFromWindow=$screenBounds " +
                        "layoutSize=${n.layoutInfo.width}x${n.layoutInfo.height} density=${n.layoutInfo.density.density} " +
                        "fontScale=${n.layoutInfo.density.fontScale}")
                    generateSequence(n.layoutInfo) { it.parentInfo }.take(16).forEachIndexed { depth, layout ->
                        val coordinates = layout.coordinates
                        println("$marker LAYOUT label=$label node=${n.id} depth=$depth identity=${System.identityHashCode(layout)} " +
                            "semanticsId=${layout.semanticsId} parentIdentity=${layout.parentInfo?.let(System::identityHashCode)} " +
                            "size=${layout.width}x${layout.height} coordinatesSize=${coordinates.size} placed=${layout.isPlaced} attached=${layout.isAttached} " +
                            "screenOrigin=${coordinates.localToScreen(androidx.compose.ui.geometry.Offset.Zero)} " +
                            "windowOrigin=${coordinates.localToWindow(androidx.compose.ui.geometry.Offset.Zero)} " +
                            "rootOrigin=${coordinates.localToRoot(androidx.compose.ui.geometry.Offset.Zero)}")
                    }
                }
            }
            val selectedField = node("跳转页码"); val selectedJump = node("跳转")
            val originalField = controls.getValue("跳转页码"); val originalJump = controls.getValue("跳转")
            val overlap = minOf(originalField.y + originalField.height, originalJump.y + originalJump.height) - maxOf(originalField.y, originalJump.y)
            println("$marker RELATION field=${selectedField.id} jump=${selectedJump.id} " +
                "controlScreenGap=${originalJump.x - originalField.x - originalField.width} controlVerticalOverlap=$overlap controlSameRow=${overlap > 0} " +
                "windowGap=${selectedJump.boundsInWindow.left - selectedField.boundsInWindow.right} " +
                "rootGap=${selectedJump.boundsInRoot.left - selectedField.boundsInRoot.right} " +
                "windowVerticalOverlap=${minOf(selectedField.boundsInWindow.bottom, selectedJump.boundsInWindow.bottom) - maxOf(selectedField.boundsInWindow.top, selectedJump.boundsInWindow.top)}")
            System.out.flush()
            controls.forEach { (label, rect) -> assertTrue(client.contains(rect), "$label must be inside native client viewport at $width dp") }
            val field = controls.getValue("跳转页码"); val jump = controls.getValue("跳转")
            assertTrue(jump.x >= field.x + field.width && jump.x - field.x - field.width <= 12,
                "Page input and jump must remain adjacent, including wrapped rows")
            assertTrue(minOf(field.y + field.height, jump.y + jump.height) > maxOf(field.y, jump.y))
            if (width >= 720) assertTrue(controls.values.minOf { it.y + it.height } > controls.values.maxOf { it.y },
                "All pager controls must share one row when native width permits")
            else {
                val directItems = linkedMapOf(
                    "previous" to node("上一页").layoutInfo,
                    "caption" to node("第 $page 页 / 共 6 页").layoutInfo,
                    "input-and-jump" to pair,
                    "next" to node("下一页").layoutInfo
                )
                directItems.values.forEachIndexed { index, layout ->
                    assertFalse(directItems.values.take(index).any { it === layout },
                        "Each pager item must be a distinct direct FlowRow child")
                }
                val pairCoordinates = pair.coordinates
                assertEquals(pair.width, pairCoordinates.size.width)
                assertEquals(pair.height, pairCoordinates.size.height)
                val pairOrigin = pairCoordinates.localToScreen(androidx.compose.ui.geometry.Offset.Zero)
                val pairBounds = Rectangle(pairOrigin.x.roundToInt(), pairOrigin.y.roundToInt(), pair.width, pair.height)
                val rectangles = linkedMapOf(
                    "previous" to controls.getValue("上一页"),
                    "caption" to controls.getValue("第 $page 页 / 共 6 页"),
                    "input-and-jump" to pairBounds,
                    "next" to controls.getValue("下一页")
                )
                rectangles.forEach { (name, rect) ->
                    val layout = directItems.getValue(name)
                    assertSame(pager, layout.parentInfo, "Every measured pager item must be a direct child of the same FlowRow")
                    assertTrue(layout.isAttached && layout.isPlaced)
                    assertEquals(layout.width, rect.width, "Measure the full direct item width")
                    assertEquals(layout.height, rect.height, "Measure the full direct item height")
                    assertTrue(rect.width > 0 && rect.height > 0 && client.contains(rect), "Whole pager item must remain in client viewport")
                }
                assertTrue(pairBounds.contains(field) && pairBounds.contains(jump), "Input and jump must remain inside their indivisible pair item")
                val rows = mutableListOf<MutableList<Pair<String, Rectangle>>>()
                rectangles.entries.sortedBy { it.value.y }.forEach { (name, rect) ->
                    // A row has a positive common vertical intersection, not necessarily equal item tops.
                    val matches = rows.filter { row ->
                        minOf(row.minOf { it.second.y + it.second.height }, rect.y + rect.height) >
                            maxOf(row.maxOf { it.second.y }, rect.y)
                    }
                    assertTrue(matches.size <= 1, "Pager row assignment must be unambiguous")
                    if (matches.isEmpty()) rows.add(mutableListOf(name to rect))
                    else matches.single().add(name to rect)
                }
                val envelopes = rows.map { row ->
                    val left = row.minOf { it.second.x }; val top = row.minOf { it.second.y }
                    Rectangle(left, top, row.maxOf { it.second.x + it.second.width } - left,
                        row.maxOf { it.second.y + it.second.height } - top)
                }
                println("FISH_R6D_PAGER_ROWS requestedWidthDp=$width page=$page pager=${System.identityHashCode(pager)} " +
                    "items=$rectangles identities=${directItems.mapValues { System.identityHashCode(it.value) }} " +
                    "groups=${rows.map { row -> row.map { it.first } }} envelopes=$envelopes")
                System.out.flush()
                assertTrue(rows.size >= 2, "360dp pager must have at least two actual rows")
                envelopes.zipWithNext().forEach { (above, below) ->
                    assertTrue(above.y + above.height <= below.y, "Pager row groups must be vertically separated")
                }
            }
            println("FISH_R5_PAGER requestedWidthDp=$width client=$client controls=$controls")
        }

        suspend fun wheelCurrentPage() {
            val viewport = node("Fish 音色列表")
            val before = viewport.config[SemanticsProperties.VerticalScrollAxisRange].value()
            val target = Point((viewport.positionOnScreen.x + viewport.boundsInRoot.width / 2).roundToInt(),
                (viewport.positionOnScreen.y + viewport.boundsInRoot.height / 2).roundToInt())
            val eventStart = receivedMouse.size
            withContext(Dispatchers.IO) {
                robot.mouseMove(target.x, target.y); robot.waitForIdle()
                assertEquals(target, MouseInfo.getPointerInfo().location, "Wheel pointer must be inside owned list")
                robot.mouseWheel(3); robot.waitForIdle()
            }
            await { node("Fish 音色列表").config[SemanticsProperties.VerticalScrollAxisRange].value() > before }
            assertTrue(receivedMouse.drop(eventStart).any { it.id == MouseEvent.MOUSE_WHEEL && it.screen == target })
            assertEquals(1, queries); assertEquals(0, server.requestCount); assertEquals(0, bindings)
            scrollbarSnapshot("R5-native-wheel-completed")
        }

        suspend fun dragThumbFromTop() {
            val range = node("Fish 音色列表").config[SemanticsProperties.VerticalScrollAxisRange]
            val before = range.value()
            assertEquals(0f, before)
            val beforeItems = firstVisiblePageItem()
            val bar = node("音色列表滚动条")
            val origin = bar.positionOnScreen
            // The unchanged public Shape probe measures the thumb; at zero scroll it starts at track top.
            val thumb = Rectangle(origin.x.roundToInt(), origin.y.roundToInt(), thumbSize.width.roundToInt(), thumbSize.height.roundToInt())
            val start = Point((origin.x + thumbSize.width / 2).roundToInt(), (origin.y + thumbSize.height / 2).roundToInt())
            val end = Point(start.x, (origin.y + bar.boundsInRoot.height / 2).roundToInt())
            assertTrue(thumb.contains(start)); assertTrue(child.get()!!.isActive)
            assertTrue(child.get()!!.bounds.contains(end))
            scrollbarSnapshot("R5-before-admission")
            val eventStart = receivedMouse.size
            var pressed = false
            try {
                // waitForIdle is never called on EDT: it must be able to deliver actual native events.
                for (attempt in 0..1) {
                    val admitted = withContext(Dispatchers.IO) {
                        robot.mouseMove(start.x, start.y); robot.waitForIdle()
                        var stable = true
                        repeat(3) { sample ->
                            delay(25)
                            val actual = MouseInfo.getPointerInfo().location
                            println("FISH_R5_ADMISSION attempt=$attempt sample=$sample planned=$start actual=$actual thumb=$thumb matches=${actual == start}")
                            stable = stable && actual == start
                        }
                        val actual = MouseInfo.getPointerInfo().location
                        if (stable && actual == start && child.get()!!.isActive) {
                            val before = assertNotNull(nativeHitTest("before-press", start, thumb),
                                "INPUT_DESTINATION_UNVERIFIED: native probe unavailable; no Press sent")
                            assertTrue(before.destinationVerified && before.pointerAtPlan && before.thumbHit && !before.leftDown,
                                "INPUT_DESTINATION_UNVERIFIED: ${before.observation}; no Press sent")
                            println("FISH_R5_PRESS planned=$start actual=$actual thumb=$thumb")
                            pressed = true
                            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                            val after = assertNotNull(nativeHitTest("after-press", start, thumb),
                                "INPUT_DESTINATION_UNVERIFIED: post-Press native probe unavailable")
                            assertTrue(after.destinationVerified && after.pointerAtPlan,
                                "INPUT_DESTINATION_UNVERIFIED: ${after.observation} after Press")
                            robot.waitForIdle()
                            true
                        } else false
                    }
                    if (admitted) { pressed = true; break }
                    println("FISH_R5_ADMISSION_REJECT attempt=$attempt repositionRemaining=${1 - attempt}")
                }
                assertTrue(pressed, "INPUT ADMISSION FAILURE: pointer did not stabilize inside thumb after one relocation")
                val press = receivedMouse.drop(eventStart).filter { it.id == MouseEvent.MOUSE_PRESSED }
                assertEquals(1, press.size, "Exactly one physical Press must arrive at owned window")
                assertTrue(thumb.contains(press.single().screen), "INPUT ADMISSION FAILURE: received Press missed actual thumb")
                assertEquals(InputEvent.BUTTON1_DOWN_MASK, press.single().mask and InputEvent.BUTTON1_DOWN_MASK)
                scrollbarSnapshot("R5-admitted-press")
                for (step in 1..16) {
                    val target = Point(start.x, start.y + (end.y - start.y) * step / 16)
                    withContext(Dispatchers.IO) {
                        robot.mouseMove(target.x, target.y); robot.waitForIdle(); delay(12)
                        println("FISH_R5_DRAG step=$step planned=$target actual=${MouseInfo.getPointerInfo().location}")
                    }
                    assertTrue(receivedMouse.drop(eventStart).any {
                        it.id == MouseEvent.MOUSE_DRAGGED && it.screen == target && it.mask and InputEvent.BUTTON1_DOWN_MASK != 0
                    }, "INPUT ADMISSION FAILURE: continuous Drag did not arrive at intended owned-window coordinate")
                    frame()
                }
            } finally {
                if (pressed) withContext(Dispatchers.IO) {
                    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                    nativeHitTest("after-release", start, thumb)
                    robot.waitForIdle()
                }
                scrollbarSnapshot("R5-released-or-admission-failed")
            }
            assertFalse(nativeProbeUnavailable, "Win32 probe became unavailable; stop without another input")
            assertFalse(nativeDestinationUnverified, "INPUT_DESTINATION_UNVERIFIED during native input; no further input")
            assertEquals(1, receivedMouse.drop(eventStart).count { it.id == MouseEvent.MOUSE_RELEASED })
            assertEquals(end, receivedMouse.drop(eventStart).last { it.id == MouseEvent.MOUSE_RELEASED }.screen)
            await { node("Fish 音色列表").config[SemanticsProperties.VerticalScrollAxisRange].value() > before }
            assertNotEquals(beforeItems, firstVisiblePageItem(), "Native drag must change first visible item, not just a semantic value")
            assertEquals(1, queries); assertEquals(0, server.requestCount); assertEquals(0, bindings)
            scrollbarSnapshot("R5-real-drag-completed")
        }
        suspend fun dispose() {
            open = false; picker.close(); preview.requestClose()
            repeat(4) { frame() }
            runtime.closeAndDrain(); scope.cancel(); scope.coroutineContext[Job]!!.join()
            assertTrue(owner.get()?.isEnabled != false)
            scene.close(); repeat(3) { yield(); delay(10) }
            child.get()?.dispose(); owner.get()?.dispose()
            Toolkit.getDefaultToolkit().removeAWTEventListener(observer)
            assertTrue(Files.walk(root).use { paths -> paths.noneMatch { it.toString().endsWith(".mp3") || it.toString().endsWith(".part") } })
        }
    }
    private fun SemanticsNode.label(value: String) = config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value) == true ||
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true

    private fun humanRequest(control: Path, name: String): String? {
        val path = control.resolve(name)
        return if (Files.exists(path)) Files.readString(path).trim() else null
    }

    private suspend fun awaitHumanFinish(f: Fixture, control: Path, protocol: HumanProtocol): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        println("FISH_R6J_AWAIT_FINISH runId=${protocol.runId} status=${protocol.status} confirmationSeconds=120")
        System.out.flush()
        while (true) {
            val decision = protocol.closeDecision(System.nanoTime(), deadline,
                humanRequest(control, "finish.request"), humanRequest(control, "cancel.request"), f.open)
            if (decision != null) return decision
            f.frame()
        }
    }

    /** Human-only: no ready/click/key/text/drag/wheel driver or Robot input method is called. */
    private fun runPhysicalMouseControl(control: Path, runId: String): Boolean = runBlocking(awt) {
        Files.createDirectories(control.parent)
        Files.createDirectory(control)
        Files.createFile(control.resolve("started.lock"))
        val protocol = HumanProtocol(runId)
        val h = HumanMouseControl(protocol)
        val server = MockWebServer().also {
            it.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503)
            }
        }
        var fixture: Fixture? = null
        var result = "NOT_EXECUTED"
        var stage = "CREATE_FIXTURE"
        var afterRange: Float? = null
        var afterFirst: String? = null
        var closeReason: String? = null
        var clean = false
        fun record(status: String) = listOf("runId=$runId", "status=$status", "classification=$result", "stage=$stage",
            "humanSignoff=EXTERNAL_RECORD_REQUIRED", "armed=${h.armed}", "baselineAvailable=${protocol.baseline != null}",
            "beforeRange=${h.beforeRange}", "beforeFirst=${h.beforeFirst}", "afterRange=$afterRange", "afterFirst=$afterFirst",
            "presses=${h.presses}", "drags=${h.drags}", "releases=${h.releases}", "released=${h.released}",
            "temporalUncertainty=${h.temporalUncertainty}", "focusLossesDuringDrag=${h.focusLossesDuringDrag}",
            "queries=${fixture?.queries}", "http=${server.requestCount}", "bindings=${fixture?.bindings}",
            "ownedHwnd=${protocol.baseline?.ownedHwnd}", "recorderId=${protocol.baseline?.recorderId}",
            "closeReason=$closeReason", "cleanup=$clean", "realFishGet=0", "realFishTts=0")
        try {
            stage = "START_LOOPBACK_MOCK"
            server.start(java.net.InetAddress.getLoopbackAddress(), 0)
            stage = "CREATE_FIXTURE"
            val f = Fixture(1100, server, human = h, catalog = { q -> FishAudioModelPage(
                items = (1..20).map { FishAudioModel("page-${q.pageNumber}-$it", "Page ${q.pageNumber} synthetic voice $it") }, total = 120) })
            fixture = f
            f.observeScrollbar = true // Listener collection is live before baseline acquisition.
            stage = "PREPARE_WINDOW"
            f.await { f.child.get()?.isShowing == true && !f.picker.state.value.loading && f.thumbSize.height > 0 &&
                f.has("Fish 音色列表") && f.node("Fish 音色列表").config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0 }
            publishHumanArtifact(control, "window-visible.txt", listOf("runId=$runId", "status=WINDOW_VISIBLE_NOT_ARMED"))
            println("FISH_R6J_WINDOW_VISIBLE runId=$runId status=WINDOW_VISIBLE_NOT_ARMED frame=${f.child.get()!!.bounds} " +
                "client=${f.child.get()!!.contentPane.size} scale=${f.child.get()!!.graphicsConfiguration.defaultTransform}")
            stage = "PREARM"
            if (f.armHumanMouse(control)) {
                stage = "CAPTURE"
                val gestureDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
                var nextProbe = 0L
                while (f.open && !h.released && h.classification == null &&
                    humanRequest(control, "cancel.request") != runId && System.nanoTime() < gestureDeadline) {
                    if (System.nanoTime() >= nextProbe) {
                        f.probeHumanDrag()
                        nextProbe = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250)
                    }
                    f.frame()
                }
                closeReason = when {
                    !f.open -> "WINDOW_CLOSED"
                    humanRequest(control, "cancel.request") == runId -> "USER_CANCEL"
                    else -> null
                }
                if (f.open && h.released && h.classification == null) {
                    stage = "STABILIZE_AFTER_RELEASE"
                    val stable: Pair<Float, String>? = withTimeoutOrNull(2_000) {
                        var previous: Pair<Float, String>? = null
                        var unchanged = 0
                        var observed: Pair<Float, String>? = null
                        repeat(80) {
                            f.frame()
                            val now = f.node("Fish 音色列表").config[SemanticsProperties.VerticalScrollAxisRange].value() to f.firstVisiblePageItem()
                            unchanged = if (now == previous) unchanged + 1 else 1
                            previous = now
                            if (unchanged >= 3) { observed = now; return@withTimeoutOrNull observed }
                        }
                        observed
                    }
                    if (stable != null) {
                        afterRange = stable.first; afterFirst = stable.second
                        result = when {
                            f.queries != 1 || server.requestCount != 0 || f.bindings != 0 -> "INCONCLUSIVE"
                            h.presses != 1 || h.drags == 0 || h.releases != 1 || h.temporalUncertainty -> "INCONCLUSIVE"
                            stable.first > h.beforeRange && stable.second != h.beforeFirst -> "PHYSICAL_INPUT_PASS"
                            else -> "PHYSICAL_INPUT_FAILURE"
                        }
                        protocol.capture(result, h.released, true)
                    } else {
                        result = "INCONCLUSIVE"; protocol.fail(stage, result)
                    }
                } else {
                    result = h.classification ?: if (!h.started) "NOT_EXECUTED" else "INCONCLUSIVE"
                    protocol.fail(if (closeReason != null) closeReason!! else "CAPTURE_INCOMPLETE_OR_TIMEOUT", result)
                }
            } else result = protocol.result
        } catch (failure: Throwable) {
            result = "INCONCLUSIVE"
            protocol.fail(stage, result)
            println("FISH_R6J_RECORDER_ERROR runId=$runId stage=$stage type=${failure.javaClass.name} noRetry=true")
            try {
                publishHumanArtifact(control, "exception.txt", listOf("runId=$runId", "stage=$stage", "type=${failure.javaClass.name}") +
                    failure.stackTrace.map { "frame=$it" }) // No exception messages, credentials or HTTP bodies.
            } catch (persist: Throwable) {
                println("FISH_R6J_EXCEPTION_PERSIST_ERROR runId=$runId type=${persist.javaClass.name}")
            }
        } finally {
            try {
                val complete = protocol.status == "CAPTURE_COMPLETE"
                publishHumanArtifact(control, if (complete) "capture-complete.txt" else "recorder-error.txt", record(protocol.status))
                println("FISH_R6J_${if (complete) "CAPTURE_COMPLETE" else "RECORDER_ERROR_HELD"} runId=$runId " +
                    "classification=$result stage=$stage beforeRange=${h.beforeRange} afterRange=$afterRange " +
                    "presses=${h.presses} drags=${h.drags} releases=${h.releases} waitingForFinish=true")
                System.out.flush()
            } catch (persist: Throwable) {
                result = "INCONCLUSIVE"; protocol.fail("PERSIST_CAPTURE", result)
                println("FISH_R6J_PERSIST_ERROR runId=$runId type=${persist.javaClass.name} waitingForFinish=true")
                System.out.flush()
            }
            val f = fixture
            if (f != null && f.open && closeReason == null) {
                try { closeReason = awaitHumanFinish(f, control, protocol) } catch (failure: Throwable) {
                    result = "INCONCLUSIVE"; closeReason = "CONFIRMATION_ERROR"
                    println("FISH_R6J_CONFIRMATION_ERROR runId=$runId type=${failure.javaClass.name}")
                }
            }
            if (closeReason != "USER_FINISH" && result == "PHYSICAL_INPUT_PASS") result = "INCONCLUSIVE"
            if (f != null && (f.queries != 1 || server.requestCount != 0 || f.bindings != 0)) result = "INCONCLUSIVE"
            try {
                val releaseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (h.started && User32.INSTANCE.GetAsyncKeyState(0x01).toInt() and 0x8000 != 0 && System.nanoTime() < releaseDeadline) delay(25)
                if (h.started && User32.INSTANCE.GetAsyncKeyState(0x01).toInt() and 0x8000 != 0) result = "INCONCLUSIVE"
            } catch (failure: Throwable) {
                result = "INCONCLUSIVE"
                println("FISH_R6J_RELEASE_PROBE_ERROR runId=$runId type=${failure.javaClass.name}")
            }
            println("FISH_R6J_DISPOSING runId=$runId reason=$closeReason")
            try { fixture?.dispose(); clean = fixture != null } catch (failure: Throwable) {
                result = "INCONCLUSIVE"
                println("FISH_R6J_CLEANUP_ERROR runId=$runId type=${failure.javaClass.name}")
            } finally {
                try { server.close() } catch (failure: Throwable) {
                    clean = false; result = "INCONCLUSIVE"
                    println("FISH_R6J_SERVER_CLOSE_ERROR runId=$runId type=${failure.javaClass.name}")
                }
            }
            publishHumanArtifact(control, "result.txt", record("CLOSED"))
            println("FISH_R6J_RESULT runId=$runId classification=$result closeReason=$closeReason cleanup=$clean " +
                "queries=${fixture?.queries} http=${server.requestCount} bindings=${fixture?.bindings} realFishGet=0 realFishTts=0")
            System.out.flush()
        }
        result == "PHYSICAL_INPUT_PASS" && clean && closeReason == "USER_FINISH"
    }

    @Test fun `native Escape closes exactly one panel then picker with and without field focus at both widths`() = runBlocking(awt) {
        for (width in listOf(1100, 720)) for (tool in listOf("高级筛选", "免费文本生成")) for (focusField in listOf(false, true)) {
            val server = MockWebServer().also { it.start() }
            val f = Fixture(width, server)
            try {
                f.ready(); f.click("预览目标 · R3 synthetic voice"); f.click(tool)
                val field = if (tool == "高级筛选") "标签（逗号分隔）" else "免费预览文本（1–120 字）"
                assertEquals(width == 720, f.has("Fish 设置面板"))
                if (focusField) f.text(field, "retained")
                f.snapshot("before-Escape-$tool-focus-$focusField")
                f.robot.keyPress(KeyEvent.VK_ESCAPE); repeat(3) { f.frame() }
                f.robot.keyPress(KeyEvent.VK_ESCAPE); repeat(3) { f.frame() } // KeyDown repeat must not close the next layer.
                f.robot.keyRelease(KeyEvent.VK_ESCAPE); repeat(4) { f.frame() }
                assertTrue(f.open); assertEquals(0, f.closes); assertFalse(f.has(field)); assertFalse(f.has("Fish 设置面板"))
                f.click(tool)
                if (focusField) assertEquals("retained", f.node(field).config[SemanticsProperties.EditableText].text)
                f.key(KeyEvent.VK_ESCAPE)
                assertTrue(f.open); assertEquals(0, f.closes)
                f.snapshot("panel-closed-before-second-Escape")
                f.key(KeyEvent.VK_ESCAPE)
                f.await { !f.open && f.owner.get()!!.isEnabled }
                assertEquals(1, f.closes); assertEquals(1, f.queries); assertEquals(0, server.requestCount); assertEquals(0, f.bindings)
            } finally { f.dispose(); server.close() }
        }
    }

    @Test fun `native Stop is top layer visible and operable during download generation and long Windows playback`() = runBlocking(awt) {
        for (width in listOf(1100, 720)) for (generate in listOf(false, true)) for (playing in listOf(false, true)) {
            val server = MockWebServer().also { mock ->
                mock.enqueue(if (playing) MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(fishPreviewMp3(2000)))
                    else MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                mock.start()
            }
            val output = if (playing) DesktopWindowsAudioOutput() else PreviewOutputFake()
            val f = Fixture(width, server, output)
            try {
                f.ready()
                if (generate) {
                    f.click("预览目标 · R3 synthetic voice"); f.click("免费文本生成")
                    f.text("免费预览文本（1–120 字）", "synthetic explicit preview")
                    assertEquals(0, server.requestCount)
                    f.click("生成 / 重播免费预览")
                } else f.click("试听官方样本 · R3 synthetic voice")
                f.await { server.requestCount == 1 && if (playing) f.preview.state.value.playing else f.preview.state.value.busy }
                if (playing) repeat(3) {
                    assertTrue(f.preview.state.value.playing && output.isPlaying(), "Native audio/state mismatch: STOP without runtime change")
                    f.frame()
                }
                f.assertStopVisibleInTopLayer()
                if (!generate) f.click("高级筛选")
                f.snapshot("Stop-$width-generate-$generate-playing-$playing")
                f.stopByUi(keyboard = generate)
                assertEquals(1, server.requestCount); assertEquals(1, f.queries); assertEquals(0, f.bindings)
                val request = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals(if (generate) "POST" else "GET", request.method)
                if (!generate) assertNull(request.getHeader("Authorization"))
            } finally { f.dispose(); server.close() }
        }
    }

    private class ObservedOutput : DesktopAudioOutput {
        private val output = PreviewOutputFake()
        var last: Path? = null
        override suspend fun play(path: Path) { last = path; output.play(path) }
        override suspend fun isPlaying() = output.isPlaying()
        override suspend fun stopAndDrain() = output.stopAndDrain()
        override suspend fun closeAndDrain() = output.closeAndDrain()
    }
    @Test fun `native resize retains target text and generated cache while sample identity stays distinct`() = runBlocking(awt) {
        val server = MockWebServer().also {
            for (frames in listOf(600, 700)) it.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(fishPreviewMp3(frames))))
            it.start()
        }
        val output = ObservedOutput()
        val f = Fixture(1100, server, output)
        try {
            f.ready(); f.click("预览目标 · R3 synthetic voice"); f.click("免费文本生成")
            f.text("免费预览文本（1–120 字）", "retained preview")
            f.click("生成 / 重播免费预览"); f.await { f.preview.state.value.playing }
            val generated = assertNotNull(output.last)
            val generatedHash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(generated))
            f.stopByUi(false)
            f.windowState!!.size = DpSize(720.dp, 720.dp)
            f.await { f.has("Fish 紧凑单列") && f.has("Fish 设置面板") }
            assertEquals("retained preview", f.node("免费预览文本（1–120 字）").config[SemanticsProperties.EditableText].text)
            assertTrue(f.has("预览目标 · R3 synthetic voice")); assertEquals(1, server.requestCount)
            f.snapshot("resize-generated-cache")
            f.click("生成 / 重播免费预览"); f.await { f.preview.state.value.playing }
            assertEquals(generated, output.last); assertEquals(1, server.requestCount)
            f.stopByUi(true); f.key(KeyEvent.VK_ESCAPE)
            f.click("试听官方样本 · R3 synthetic voice"); f.await { f.preview.state.value.playing }
            val sample = assertNotNull(output.last)
            assertNotEquals(generated, sample)
            assertFalse(generatedHash.contentEquals(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sample))))
            f.stopByUi(false); f.click("免费文本生成")
            assertEquals("retained preview", f.node("免费预览文本（1–120 字）").config[SemanticsProperties.EditableText].text)
            f.click("生成 / 重播免费预览"); f.await { f.preview.state.value.playing }
            assertEquals(generated, output.last); assertEquals(2, server.requestCount); assertEquals(0, f.bindings)
            f.stopByUi(true)
        } finally { f.dispose(); server.close() }
    }

    @Test fun `native persistent scrollbar drags current page and explicit page jump preserves request semantics`() = runBlocking(awt) {
        for (width in listOf(1100, 720, 360)) {
        val server = MockWebServer().also { it.start() }
        val f = Fixture(width, server, catalog = { q -> FishAudioModelPage(
            items = (1..20).map { FishAudioModel("page-${q.pageNumber}-$it", "Page ${q.pageNumber} synthetic voice $it") }, total = 120) })
        try {
            f.ready()
            f.observeScrollbar = true
            fun range() = f.node("Fish 音色列表").config[SemanticsProperties.VerticalScrollAxisRange]
            f.await { !f.picker.state.value.loading && range().maxValue() > 0 && f.child.get()!!.isActive &&
                f.node("音色列表滚动条").layoutInfo.isPlaced && f.thumbSize.height > 0 && f.visiblePageItems().isNotEmpty() }
            f.assertPagerLayout()
            f.dragThumbFromTop()
            f.wheelCurrentPage()
            assertEquals(1, f.queries)
            for (invalid in listOf("0", "-1", "7", "9999999999999999999")) {
                f.text("跳转页码", invalid); f.key(KeyEvent.VK_ENTER)
                assertTrue(f.node("跳转").config.contains(SemanticsProperties.Disabled)); assertEquals(1, f.queries)
            }
            f.text("跳转页码", "3"); assertEquals(1, f.queries); f.key(KeyEvent.VK_ENTER)
            f.await { f.has("使用音色 · Page 3 synthetic voice 1") }
            assertEquals(listOf(1, 3), f.queriedPages); assertEquals(0f, range().value())
            f.text("跳转页码", "2"); f.click("跳转"); f.await { f.has("使用音色 · Page 2 synthetic voice 1") }
            assertEquals(listOf(1, 3, 2), f.queriedPages); assertEquals(0, server.requestCount); assertEquals(0, f.bindings)
            assertEquals(0f, range().value()); f.assertPagerLayout()
            f.snapshot("page-input-and-scrollbar")
        } finally { f.dispose(); server.close() }
        }
        val unknownServer = MockWebServer().also { it.start() }
        val unknown = Fixture(720, unknownServer, catalog = { q -> FishAudioModelPage(
            items = listOf(FishAudioModel("unknown-${q.pageNumber}", "Unknown total")), total = 0, hasMore = true) })
        try {
            unknown.ready(); unknown.text("跳转页码", Int.MAX_VALUE.toString()); unknown.click("跳转")
            unknown.await { unknown.picker.state.value.query.pageNumber == Int.MAX_VALUE && !unknown.picker.state.value.loading }
            assertTrue(unknown.node("下一页").config.contains(SemanticsProperties.Disabled))
            assertEquals(listOf(1, Int.MAX_VALUE), unknown.queriedPages)
            unknown.text("跳转页码", "2147483648"); unknown.key(KeyEvent.VK_ENTER)
            assertEquals(2, unknown.queries); assertEquals(0, unknownServer.requestCount)
            unknown.snapshot("unknown-total-int-boundary")
        } finally { unknown.dispose(); unknownServer.close() }
    }
}
