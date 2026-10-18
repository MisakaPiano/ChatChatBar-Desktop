@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.voice.*
import java.awt.AWTEvent
import java.awt.KeyboardFocusManager
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.nio.file.Files
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
    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private val sequence = AtomicInteger()

    private inner class Fixture(width: Int, val server: MockWebServer, val output: DesktopAudioOutput = PreviewOutputFake()) {
        val root = Files.createTempDirectory("fish-r3-native-")
        val scope = CoroutineScope(SupervisorJob() + awt)
        val credentials = DesktopFishAudioCredentials(InMemoryDesktopSecretStore()).also { it.save("synthetic-r3-only") }
        val runtime = DesktopFishPreviewRuntime(
            DesktopFishPreviewTransport(credentials, OkHttpClient(), server.url("/").toString().trimEnd('/')), output,
            stores = { DesktopFishPreviewStore(Files.createDirectory(root.resolve("cache"))) },
            samples = DesktopFishSampleTransport(OkHttpClient()) { server.url(it.encodedPath) })
        val preview = runtime.openSession()
        var queries = 0
        val picker = DesktopFishPickerController(scope) {
            queries++; FishAudioModelPage(items = listOf(FishAudioModel("r3-voice", "R3 synthetic voice",
                samples = listOf(FishAudioSample(audio = syntheticOfficialSample)))), total = 1)
        }
        val child = AtomicReference<ComposeWindow?>()
        val owner = AtomicReference<ComposeWindow?>()
        val title = "Fish R3 isolated native ${sequence.incrementAndGet()}"
        val robot = Robot().apply { isAutoWaitForIdle = false }
        var open by mutableStateOf(true)
        var closes = 0
        var bindings = 0
        var windowState: WindowState? = null
        private val observer = AWTEventListener { event ->
            if (event is WindowEvent && event.id == WindowEvent.WINDOW_OPENED) {
                (event.window as? ComposeWindow)?.takeIf { it.title == title }?.let(child::set)
            }
            if (event is KeyEvent && event.keyCode == KeyEvent.VK_ESCAPE) {
                val window = SwingUtilities.getWindowAncestor(event.component)
                if (window === child.get() || window?.owner === child.get()) {
                    println("FISH_R3_AWT type=${event.id} consumed=${event.isConsumed} focus=${KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.javaClass?.simpleName}")
                }
            }
        }
        val scene: ImageComposeScene
        init {
            Toolkit.getDefaultToolkit().addAWTEventListener(observer, AWTEvent.WINDOW_EVENT_MASK or AWTEvent.KEY_EVENT_MASK)
            picker.search()
            scene = ImageComposeScene(16, 16) {
                val state = rememberWindowState(width = width.dp, height = 720.dp)
                SideEffect { windowState = state }
                Window(onCloseRequest = {}, title = "R3 synthetic owner", state = rememberWindowState(width = 500.dp, height = 200.dp)) {
                    val parent = window
                    SideEffect { owner.set(parent) }
                    CompositionLocalProvider(LocalDesktopFishParentWindow provides parent) {
                        if (open) DesktopFishPickerWindow(onClose = { closes++; open = false; picker.close(); preview.requestClose() },
                            state = state, title = title) {
                            DesktopFishPickerContent(picker, true, { closes++; open = false; picker.close(); preview.requestClose() }, preview) { bindings++ }
                        }
                    }
                }
            }
        }
        suspend fun frame() { scene.render().close(); yield(); delay(20) }
        suspend fun await(check: () -> Boolean) = withTimeout(10_000) { while (!check()) frame() }
        suspend fun ready() {
            await { child.get()?.isShowing == true && nodes().any { it.label("使用音色 · R3 synthetic voice") } }
            child.get()!!.toFront(); child.get()!!.requestFocus()
            await { child.get()!!.isActive }
            assertFalse(owner.get()!!.isEnabled)
        }
        fun nodes(): List<SemanticsNode> {
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            return child.get()?.semanticsOwners.orEmpty().flatMap { walk(it.rootSemanticsNode) }
        }
        fun has(label: String) = nodes().any { it.label(label) }
        fun node(label: String) = nodes().first { it.label(label) }
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
        fun snapshot(phase: String) {
            val w = child.get()!!
            val stop = nodes().filter { it.label("停止预览") && it.config.getOrNull(SemanticsProperties.Role) == Role.Button }
            val fields = nodes().filter { it.config.contains(SemanticsActions.SetText) }
            println("FISH_R3_SNAPSHOT phase=$phase frame=${w.bounds} client=${w.contentPane.size} scale=${w.graphicsConfiguration.defaultTransform} " +
                "wide=${has("Fish 宽屏分栏")} compact=${has("Fish 紧凑单列")} popup=${has("Fish 折叠设置内容")} busy=${preview.state.value.busy} playing=${preview.state.value.playing} " +
                "stopCount=${stop.size} stop=${stop.map { "${it.id}:${it.boundsInWindow}:${it.positionOnScreen}:root${it.root.hashCode()}" }} " +
                "fieldRoots=${fields.map { it.root.hashCode() }.distinct()} ownerEnabled=${owner.get()?.isEnabled} closes=$closes requests=${server.requestCount}")
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

    @Test fun `bounded pre repair native event and long audio visibility observation`() = runBlocking(awt) {
        // Observation only: preserves old UX behavior without calling its known outcome a PASS.
        for (width in listOf(1100, 720)) for (focusField in listOf(false, true)) {
            val server = MockWebServer().also { it.start() }
            val f = Fixture(width, server)
            try {
                f.ready(); f.click("高级筛选")
                if (focusField) f.click("标签（逗号分隔）")
                f.snapshot("baseline-before-Escape-focus-$focusField")
                f.key(KeyEvent.VK_ESCAPE)
                println("FISH_R3_BASELINE_ESCAPE width=$width focusField=$focusField open=${f.open} closes=${f.closes}")
                if (f.open) f.snapshot("baseline-after-Escape")
            } finally { f.dispose(); server.close() }
        }
        for (width in listOf(1100, 720)) {
            val server = MockWebServer().also {
                it.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(fishPreviewMp3(2000)))); it.start()
            }
            val output = DesktopWindowsAudioOutput()
            val f = Fixture(width, server, output)
            try {
                f.ready(); f.click("试听官方样本 · R3 synthetic voice")
                f.await { f.preview.state.value.playing }
                assertTrue(output.isPlaying(), "Runtime playing must match native backend")
                f.snapshot("baseline-native-playing")
                f.click("免费文本生成")
                f.snapshot("baseline-native-playing-panel")
                assertTrue(f.preview.state.value.playing && output.isPlaying(), "Long native playback/state mismatch; STOP runtime scope")
                f.preview.stop(); f.await { !f.preview.state.value.busy && !f.preview.state.value.playing }
                assertFalse(output.isPlaying()); assertEquals(1, server.requestCount); assertEquals(0, f.bindings)
            } finally { f.dispose(); server.close() }
        }
    }
}
