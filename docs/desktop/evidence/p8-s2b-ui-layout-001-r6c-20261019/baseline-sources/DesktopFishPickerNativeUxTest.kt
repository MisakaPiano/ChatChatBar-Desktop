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
    private val awt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private val sequence = AtomicInteger()
    private data class ReceivedMouse(val id: Int, val screen: Point, val button: Int, val mask: Int)

    private inner class Fixture(val width: Int, val server: MockWebServer, val output: DesktopAudioOutput = PreviewOutputFake(),
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
        val title = "Fish R3 isolated native ${sequence.incrementAndGet()}"
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
            if (observeScrollbar && event is MouseEvent) {
                val target = SwingUtilities.getWindowAncestor(event.component)
                if (target === child.get()) {
                    receivedMouse += ReceivedMouse(event.id, Point(event.xOnScreen, event.yOnScreen), event.button, event.modifiersEx)
                    val row = "id=${event.id} screen=${event.xOnScreen},${event.yOnScreen} local=${event.x},${event.y} " +
                        "button=${event.button} mask=${event.modifiersEx} consumed=${event.isConsumed} " +
                        "component=${event.component.javaClass.name} targetOwned=${target === child.get()} active=${target.isActive}"
                    mouseEvents += row
                    println("FISH_R4_MOUSE $row")
                }
            }
        }
        val scene: ImageComposeScene
        init {
            Toolkit.getDefaultToolkit().addAWTEventListener(observer, AWTEvent.WINDOW_EVENT_MASK or AWTEvent.KEY_EVENT_MASK or
                AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK)
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
            val w = child.get()!!
            val bar = node("音色列表滚动条")
            val list = node("Fish 音色列表")
            val range = list.config[SemanticsProperties.VerticalScrollAxisRange]
            println("FISH_R4_SCROLL phase=$phase requestedWidthDp=$width frame=${w.bounds} clientOrigin=${w.contentPane.locationOnScreen} " +
                "client=${w.contentPane.size} scale=${w.graphicsConfiguration.defaultTransform} active=${w.isActive} focused=${w.isFocused} " +
                "focus=${w.focusOwner?.javaClass?.name} ownerMatches=${w.owner === owner.get()} ownerEnabled=${owner.get()?.isEnabled} " +
                "range=${range.value()}/${range.maxValue()} reverse=${range.reverseScrolling} " +
                "listScreen=${list.positionOnScreen} listBounds=${list.boundsInRoot} trackScreen=${bar.positionOnScreen} trackBounds=${bar.boundsInRoot} " +
                "thumbOutline=$thumbSize outlineCount=$thumbOutlineCount items=${visiblePageItems()} queries=$queries requests=${server.requestCount} bindings=$bindings")
        }
        fun firstVisiblePageItem() = visiblePageItems().first().substringBefore(":top=")

        fun assertPagerLayout() {
            val w = child.get()!!
            val client = Rectangle(w.contentPane.locationOnScreen, w.contentPane.size)
            val page = picker.state.value.query.pageNumber
            fun bounds(label: String): Rectangle {
                val n = node(label)
                return Rectangle(n.positionOnScreen.x.roundToInt(), n.positionOnScreen.y.roundToInt(),
                    n.boundsInRoot.width.roundToInt(), n.boundsInRoot.height.roundToInt())
            }
            val controls = listOf("上一页", "第 $page 页 / 共 6 页", "跳转页码", "跳转", "下一页").associateWith(::bounds)
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
                println("$marker MATCH label=$label candidates=${candidates.map { it.id }} chosen=${chosen.id} reason=$reason originalHybridRect=${controls[label]}")
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
                "hybridScreenGap=${originalJump.x - originalField.x - originalField.width} hybridVerticalOverlap=$overlap hybridSameRow=${overlap > 0} " +
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
            else assertTrue(field.y > controls.getValue("上一页").y, "360dp pager must wrap without clipping")
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
                            println("FISH_R5_PRESS planned=$start actual=$actual thumb=$thumb")
                            pressed = true
                            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.waitForIdle()
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
                    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); robot.waitForIdle()
                }
                scrollbarSnapshot("R5-released-or-admission-failed")
            }
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
