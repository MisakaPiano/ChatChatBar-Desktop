package com.example.chatbar.desktop

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.foundation.layout.Column
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinDef.POINT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.ptr.IntByReference
import java.awt.*
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Real modal-library entry, with disposable local images and no network or user profile. */
class WindowsBackgroundViewerTest {
    private lateinit var evidence: Path
    private fun startEvidence(label: String) {
        val root = Path.of("build/phase7-d-r1-native")
        Files.createDirectories(root)
        evidence = Files.createTempDirectory(root, "$label-")
        println("Native Viewer evidence: ${evidence.toAbsolutePath()}")
    }
    private val user = User32.INSTANCE
    private fun title(w: Window) = when (w) { is Frame -> w.title; is Dialog -> w.title; else -> "" }
    private fun hwnd(w: Window) = HWND(Native.getWindowPointer(w))
    private fun windows() = mutableListOf<Window>().also { out -> EventQueue.invokeAndWait {
        out.addAll(Window.getWindows().filter { it.isShowing })
    } }
    private fun await(reason: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!check() && System.nanoTime() < deadline) Thread.sleep(25)
        assertTrue(check(), reason)
    }
    private interface ForegroundApi : com.sun.jna.win32.StdCallLibrary {
        fun WindowFromPoint(point: POINT.ByValue): HWND?
        fun GetAncestor(hwnd: HWND, flags: Int): HWND?
        fun ClientToScreen(hwnd: HWND, point: POINT): Boolean
    }
    private val foregroundApi = Native.load("user32", ForegroundApi::class.java)
    private fun workArea(w: Window): Rectangle {
        val config = w.graphicsConfiguration
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        return Rectangle(config.bounds).apply {
            x += insets.left; y += insets.top
            width -= insets.left + insets.right; height -= insets.top + insets.bottom
        }
    }
    /** AWT bounds/insets are logical units on this monitor, including negative origins.
     * Keep a proportional edge gutter beyond the OS taskbar exclusion. EDT only. */
    private fun placeSafely(w: Window) {
        check(EventQueue.isDispatchThread())
        val work = workArea(w)
        val safe = Rectangle(work).apply { grow(-(width / 10), -(height / 12)) }
        check(safe.width > 0 && safe.height > 0)
        val width = w.width.coerceAtMost(safe.width)
        val height = w.height.coerceAtMost(safe.height)
        w.setBounds(safe.x + (safe.width - width) / 2, safe.y + (safe.height - height) / 2, width, height)
        Files.writeString(evidence.resolve("placement.txt"),
            "workAreaLogical=$work safeLogical=$safe fixtureLogical=${w.bounds} scale=${w.graphicsConfiguration.defaultTransform}\n",
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    }
    private fun processIdentity(handle: HWND?): String {
        if (handle == null) return "unresolved"
        val pid = IntByReference()
        user.GetWindowThreadProcessId(handle, pid)
        val id = Integer.toUnsignedLong(pid.value)
        if (id == 0L) return "unresolved"
        val executable = runCatching {
            ProcessHandle.of(id).flatMap { it.info().command() }.orElse(null)?.let {
                Path.of(it).fileName.toString().replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
            }
        }.getOrNull()
        return "pid=$id owner=${if (id == ProcessHandle.current().pid()) "test-jvm" else "external-process"} executable=${executable ?: "unresolved"}"
    }
    private fun verifyOwnership(w: Window, point: POINT) {
        val intended = hwnd(w)
        val hit = foregroundApi.WindowFromPoint(POINT.ByValue(point.x, point.y))
        val root = hit?.let { foregroundApi.GetAncestor(it, 2) }
        val rect = RECT().also { assertTrue(user.GetWindowRect(intended, it)) }
        val foreground = user.GetForegroundWindow()
        Files.writeString(evidence.resolve("ownership.txt"),
            "fixture=$intended foreground=$foreground hit=$hit root=$root targetPhysical=${point.x},${point.y} " +
                "fixturePhysical=${rect.toRectangle()} workAreaLogical=${workArea(w)} scale=${w.graphicsConfiguration.defaultTransform} " +
                "targetProcess=[${processIdentity(root)}] foregroundProcess=[${processIdentity(foreground)}]\n",
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        assertEquals(intended, root, "Pointer target must belong to fixture; never click another application")
    }
    /** All product clicks use the same ownership guard, including each double-click press. */
    private fun clickFixture(robot: Robot, w: Window, logicalX: Int, logicalY: Int, count: Int = 1) {
        lateinit var location: Point
        lateinit var scale: java.awt.geom.AffineTransform
        EventQueue.invokeAndWait { location = w.locationOnScreen; scale = w.graphicsConfiguration.defaultTransform }
        val rect = RECT().also { assertTrue(user.GetWindowRect(hwnd(w), it)) }
        val target = POINT(rect.left + kotlin.math.round((logicalX - location.x) * scale.scaleX).toInt(),
            rect.top + kotlin.math.round((logicalY - location.y) * scale.scaleY).toInt())
        verifyOwnership(w, target)
        robot.mouseMove(logicalX, logicalY)
        repeat(count) {
            val cursor = POINT().also { assertTrue(user.GetCursorPos(it)) }
            assertTrue(kotlin.math.abs(cursor.x - target.x) <= 2 && kotlin.math.abs(cursor.y - target.y) <= 2,
                "Robot must reach the intended physical click point")
            verifyOwnership(w, cursor)
            assertEquals(hwnd(w), user.GetForegroundWindow(), "Fixture must own foreground before product click")
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            if (count > 1) robot.delay(70) // Preserve the existing double-click input cadence.
        }
    }
    private fun focus(w: Window) {
        val intended = hwnd(w)
        val log = mutableListOf<String>()
        try {
            EventQueue.invokeAndWait { w.toFront(); w.requestFocus() }
            user.SetForegroundWindow(intended)
            log.add("request intended=$intended foreground=${user.GetForegroundWindow()} awtFocused=${w.isFocused}")
            if (user.GetForegroundWindow() != intended) {
                // Native-decorated fixtures: one caption click activates without touching
                // application controls. Require HTCAPTION, excluding icon/buttons/borders.
                val rect = RECT().also { assertTrue(user.GetWindowRect(intended, it)) }
                val origin = POINT().also { assertTrue(foregroundApi.ClientToScreen(intended, it)) }
                val target = POINT((rect.left + rect.right) / 2, (rect.top + origin.y) / 2)
                lateinit var location: Point
                lateinit var scale: java.awt.geom.AffineTransform
                EventQueue.invokeAndWait {
                    location = w.locationOnScreen
                    scale = w.graphicsConfiguration.defaultTransform
                }
                fun verifyTarget(point: POINT) {
                    verifyOwnership(w, point)
                    val hitWindow = foregroundApi.WindowFromPoint(POINT.ByValue(point.x, point.y))
                    val root = hitWindow?.let { foregroundApi.GetAncestor(it, 2) }
                    val packed = ((point.y.toLong() and 0xffff) shl 16) or (point.x.toLong() and 0xffff)
                    val hit = user.SendMessage(intended, 0x84, WPARAM(0), LPARAM(packed)).toInt()
                    log.add("target=${point.x},${point.y} hitWindow=$hitWindow root=$root intended=$intended hit=$hit " +
                        "foreground=${user.GetForegroundWindow()} scale=$scale awtLocation=$location awtFocused=${w.isFocused}")
                    assertEquals(intended, root, "Activation point must belong to fixture; never click another application")
                    assertEquals(2, hit, "Activation point must be inert caption, not buttons or resize edges")
                }
                verifyTarget(target)
                val robot = Robot().apply { isAutoWaitForIdle = true }
                // Native physical pixels to AWT logical screen coordinates, per monitor.
                robot.mouseMove(location.x + kotlin.math.round((target.x - rect.left) / scale.scaleX).toInt(),
                    location.y + kotlin.math.round((target.y - rect.top) / scale.scaleY).toInt())
                val cursor = POINT().also { assertTrue(user.GetCursorPos(it)) }
                log.add("actualCursor=${cursor.x},${cursor.y} requested=${target.x},${target.y}")
                assertTrue(kotlin.math.abs(cursor.x - target.x) <= 2 && kotlin.math.abs(cursor.y - target.y) <= 2,
                    "Robot must reach verified physical activation point")
                verifyTarget(cursor)
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                log.add("click foreground=${user.GetForegroundWindow()} intended=$intended")
            }
            await("fixture must own Windows foreground") { user.GetForegroundWindow() == intended }
            await("fixture must retain AWT focus") { w.isFocused }
            log.add("acquired intended=$intended foreground=${user.GetForegroundWindow()} awtFocused=${w.isFocused}")
        } finally {
            Files.write(evidence.resolve("foreground.txt"), log, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND)
        }
    }
    private fun snapshot(w: Window, name: String): BufferedImage {
        // Only this fixture HWND, never an overlapping private desktop window.
        val h = hwnd(w); val rect = RECT(); assertTrue(user.GetWindowRect(h, rect))
        val width = rect.right - rect.left; val height = rect.bottom - rect.top
        val gdi = GDI32.INSTANCE; val dc = user.GetDC(h); val memory = gdi.CreateCompatibleDC(dc)
        val info = WinGDI.BITMAPINFO().apply {
            bmiHeader.biWidth = width; bmiHeader.biHeight = -height
            bmiHeader.biPlanes = 1; bmiHeader.biBitCount = 32; bmiHeader.biCompression = WinGDI.BI_RGB
        }
        val bits = PointerByReference()
        val bitmap = gdi.CreateDIBSection(memory, info, WinGDI.DIB_RGB_COLORS, bits, null, 0)
        val previous = gdi.SelectObject(memory, bitmap)
        try {
            assertTrue(user.PrintWindow(h, memory, 2), "fixture HWND capture")
            return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
                setRGB(0, 0, width, height, bits.value.getIntArray(0, width * height), 0, width)
                ImageIO.write(this, "png", evidence.resolve("$name.png").toFile())
            }
        } finally {
            gdi.SelectObject(memory, previous); gdi.DeleteObject(bitmap)
            gdi.DeleteDC(memory); user.ReleaseDC(h, dc)
        }
    }
    private fun describe() = windows().joinToString("\n") {
        "${title(it)} hwnd=${hwnd(it)} owner=${it.owner?.let(::title)} enabled=${user.IsWindowEnabled(hwnd(it))} " +
            "foreground=${user.GetForegroundWindow()} focused=${it.isFocused} bounds=${it.bounds} scale=${it.graphicsConfiguration.defaultTransform}"
    } + "\ncursor=${MouseInfo.getPointerInfo().location} focusOwner=${KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner}\n"

    /** Diagnostic-only transparent interception of the existing listener for this AWT event.
     * No event synthesis, destination changes, state writes or additional readiness waits. */
    private fun observeViewerCallback(event: KeyEvent, log: MutableList<String>) {
        fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        fun viewerCallback(value: Any, depth: Int = 0): Any? {
            if (value.javaClass.declaredFields.any { it.name == "\$selection" }) return value
            if (depth >= 5) return null
            return value.javaClass.declaredFields.asSequence().filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .mapNotNull { runCatching { it.isAccessible = true; it.get(value) }.getOrNull() }
                .filter { it is Function<*> }.mapNotNull { viewerCallback(it, depth + 1) }.firstOrNull()
        }
        try {
            val window = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow as? ComposeWindow ?: return
            if (!title(window).startsWith("图片 ")) return
            val panel = requireNotNull(field(window, "composePanel"))
            val container = requireNotNull(field(panel, "_composeContainer"))
            val mediator = requireNotNull(field(container, "mediator"))
            val slot = mediator.javaClass.getDeclaredField("onPreviewKeyEvent").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val original = slot.get(mediator) as (androidx.compose.ui.input.key.KeyEvent) -> Boolean
            val callback = viewerCallback(original)
            val selection = callback?.let { field(it, "\$selection") as? DesktopViewerSelection }
            val captured = callback?.let { field(it, "\$index") as? Int }
            lateinit var observed: (androidx.compose.ui.input.key.KeyEvent) -> Boolean
            observed = { incoming ->
                val target = captured?.let { when (event.keyCode) { KeyEvent.VK_LEFT -> it - 1; KeyEvent.VK_RIGHT -> it + 1; else -> null } }
                log.add("CALLBACK_ENTER nano=${System.nanoTime()} awtWhen=${event.`when`} id=${event.id} key=${event.keyCode} " +
                    "callback=${callback?.javaClass?.name} capturedIndex=$captured currentIndex=${selection?.index} " +
                    "computedDestination=$target visible=${title(window)} foreground=${user.GetForegroundWindow()} viewer=${hwnd(window)} " +
                    "awtFocused=${window.isFocused} focusOwner=${KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner}")
                var handled: Boolean? = null
                try { original(incoming).also { handled = it } }
                finally {
                    log.add("CALLBACK_EXIT nano=${System.nanoTime()} awtWhen=${event.`when`} id=${event.id} key=${event.keyCode} " +
                        "handled=$handled stateIndex=${selection?.index} visible=${title(window)}")
                    if (slot.get(mediator) === observed) slot.set(mediator, original)
                }
            }
            slot.set(mediator, observed)
        } catch (failure: Exception) {
            log.add("OBSERVATION_UNAVAILABLE ${failure.javaClass.simpleName}: ${failure.message}")
        }
    }

    @Test fun `background library double click viewer accepts native input and returns to unchanged library`() = runBlocking {
        requireDesktopGraphicsForNativeTest()
        assertTrue(DesktopWindowChrome.isWindows, "Windows verification requires an interactive Windows desktop")
        startEvidence("background")
        val initialWindows = windows().toSet()
        val fixture = FinalProductDesignFixture()
        val robot = Robot().apply { isAutoWaitForIdle = true }
        try {
            val c = fixture.container
            val card = CharacterCard.create("Local background fixture").copy(id = "background-fixture")
            c.characterRepository.save(card)
            c.chatRepository.createSession(ChatSession("background-session", card.id, "Local fixture", createdAt = 1, updatedAt = 1))
            val controller = c.primaryChatController
            val library = assertNotNull(controller.backgroundLibrary)
            for (color in listOf(Color.MAGENTA, Color.CYAN)) {
                val raster = BufferedImage(300, 150, BufferedImage.TYPE_INT_RGB)
                raster.createGraphics().let { it.color = color; it.fillRect(0, 0, 300, 150); it.dispose() }
                library.import(card.id, DesktopImageEditing.png(raster))
            }
            controller.refresh()
            val before = library.load(card.id)
            EventQueue.invokeAndWait {
                ComposeWindow().apply {
                    title = "Background viewer test host"
                    setSize(1000, 900)
                    placeSafely(this)
                    setContent { DesktopChatBackgroundPanel(controller) {} }
                    isVisible = true
                }
            }
            await("library visible") { windows().any { title(it) == "聊天背景" } }
            val dialog = windows().single { title(it) == "聊天背景" }
            EventQueue.invokeAndWait { dialog.setSize(760, 760); placeSafely(dialog) }
            focus(dialog)
            var imagePoint: Point? = null
            await("real background thumbnail rendered") {
                val shot = snapshot(dialog, "library-before")
                val pixels = mutableListOf<Point>()
                for (y in 0 until shot.height step 4) for (x in 0 until shot.width step 4)
                    if (shot.getRGB(x, y) and 0xffffff == 0xff00ff) pixels.add(Point(x, y))
                if (pixels.size > 100) imagePoint = Point(pixels.map { it.x }.average().toInt(), pixels.map { it.y }.average().toInt())
                imagePoint != null
            }
            val point = assertNotNull(imagePoint)
            val origin = dialog.locationOnScreen
            val scale = dialog.graphicsConfiguration.defaultTransform
            clickFixture(robot, dialog, origin.x + (point.x / scale.scaleX).toInt(), origin.y + (point.y / scale.scaleY).toInt(), 2)
            await("one Viewer opened by double click") { windows().count { title(it).startsWith("图片 ") } == 1 }
            val viewer = windows().single { title(it).startsWith("图片 ") }
            EventQueue.invokeAndWait { placeSafely(viewer) }
            Files.writeString(evidence.resolve("states.txt"), describe())
            assertTrue(user.IsWindowEnabled(hwnd(viewer)), "Viewer must not be blocked by background DialogWindow")
            focus(viewer)
            await("Viewer first page") { title(viewer) == "图片 1 / 2" }
            snapshot(viewer, "viewer-first")
            val center = viewer.locationOnScreen
            clickFixture(robot, viewer, center.x + viewer.width / 2, center.y + viewer.height / 2)
            await("Viewer receives AWT focus before keyboard input") {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow == viewer &&
                    KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner != null
            }
            robot.keyPress(KeyEvent.VK_RIGHT); robot.keyRelease(KeyEvent.VK_RIGHT)
            await("real keyboard advances Viewer") { title(viewer) == "图片 2 / 2" }
            // Hover/click the actual right/left overlay location in the image area, using the
            // window client geometry. The back arrow occupies the left image edge at mid-height.
            val viewerOrigin = viewer.locationOnScreen
            clickFixture(robot, viewer, viewerOrigin.x + 60, viewerOrigin.y + viewer.height / 2)
            await("real mouse goes back to first image") { title(viewer) == "图片 1 / 2" }
            snapshot(viewer, "viewer-interactive")
            robot.keyPress(KeyEvent.VK_ESCAPE); robot.keyRelease(KeyEvent.VK_ESCAPE)
            await("Viewer closes and original library survives") { !viewer.isShowing && dialog.isShowing && user.IsWindowEnabled(hwnd(dialog)) }
            focus(dialog)
            snapshot(dialog, "library-after")
            assertEquals(before, library.load(card.id), "viewing never changes background preferences or resources")
            assertEquals(1, windows().count { title(it) == "聊天背景" })
            Files.writeString(evidence.resolve("result.txt"), "PASS: real double click, keyboard, mouse, Escape, original library retained\n" + describe())
            Unit
        } catch (failure: Throwable) {
            Files.writeString(evidence.resolve("failure.txt"), failure.stackTraceToString() + "\n" + describe())
            throw failure
        } finally {
            EventQueue.invokeAndWait { Window.getWindows().filter { it !in initialWindows }.forEach { it.dispose() } }
            fixture.close()
        }
    }

    @Test fun `real chat click opens repeated path in clicked message and navigates all occurrences`() = runBlocking {
        requireDesktopGraphicsForNativeTest()
        assertTrue(DesktopWindowChrome.isWindows)
        startEvidence("chat-duplicates")
        val initialWindows = windows().toSet()
        val fixture = FinalProductDesignFixture()
        val robot = Robot().apply { isAutoWaitForIdle = true }
        val keyEvents = java.util.concurrent.CopyOnWriteArrayList<String>()
        val listener = java.awt.event.AWTEventListener { event ->
            if (event is KeyEvent) {
                keyEvents.add("AWT_DELIVERED nano=${System.nanoTime()} id=${event.id} key=${event.keyCode} when=${event.`when`} " +
                    "focus=${KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow?.let(::title)}")
                observeViewerCallback(event, keyEvents)
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.KEY_EVENT_MASK)
        try {
            fixture.seedLocalImages()
            val controller = fixture.container.primaryChatController
            val a = "images/local-fixture-1.png"; val b = "images/local-fixture-2.png"
            val messages = listOf(a, b, a).mapIndexed { index, path ->
                ChatMessage.create("session", MessageRole.USER, "").copy(id = "message-$index", images = listOf(path))
            }
            lateinit var host: ComposeWindow
            EventQueue.invokeAndWait {
                host = ComposeWindow().apply {
                    title = "Chat duplicate fixture"; setSize(600, 500); placeSafely(this)
                    setContent { Column { DesktopMessageImages(messages.last(), controller.state.value.copy(messages = messages), controller) } }
                    isVisible = true
                }
            }
            focus(host)
            // The fixture image's color proves asynchronous image decode/layout is ready.
            await("chat thumbnail drawn") {
                val shot = snapshot(host, "chat-before")
                (0 until shot.height step 4).any { y -> (0 until shot.width step 4).any { x ->
                    (shot.getRGB(x, y) and 0xffffff) == (Color(70, 118, 180).rgb and 0xffffff)
                } }
            }
            val origin = host.locationOnScreen
            clickFixture(robot, host, origin.x + host.insets.left + 80, origin.y + host.insets.top + 100, 2)
            await("clicked third message opens third collection position") { windows().any { title(it) == "图片 3 / 3" } }
            val viewer = windows().single { title(it).startsWith("图片 ") }
            EventQueue.invokeAndWait { placeSafely(viewer) }
            focus(viewer)
            val center = viewer.locationOnScreen
            clickFixture(robot, viewer, center.x + viewer.width / 2, center.y + viewer.height / 2)
            await("chat Viewer AWT input ready") { KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow == viewer &&
                KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner != null }
            snapshot(viewer, "third-occurrence")
            for ((key, page) in listOf(KeyEvent.VK_LEFT to 2, KeyEvent.VK_LEFT to 1, KeyEvent.VK_RIGHT to 2, KeyEvent.VK_RIGHT to 3)) {
                keyEvents.add("ROBOT_REQUEST nano=${System.nanoTime()} key=$key expectedPage=$page visible=${title(viewer)} cursor=${MouseInfo.getPointerInfo().location}")
                robot.keyPress(key); robot.keyRelease(key)
                await("Viewer page $page") {
                    if (title(viewer) != "图片 $page / 3") false else {
                        val shot = snapshot(viewer, "page-$page")
                        val color = if (page == 2) Color(80, 126, 180) else Color(70, 118, 180)
                        (0 until shot.height step 8).any { y -> (0 until shot.width step 8).any { x ->
                            (shot.getRGB(x, y) and 0xffffff) == (color.rgb and 0xffffff)
                        } }
                    }
                }
                keyEvents.add("UI_PROCESSED nano=${System.nanoTime()} expectedPage=$page visible=${title(viewer)}")
            }
            robot.keyPress(KeyEvent.VK_ESCAPE); robot.keyRelease(KeyEvent.VK_ESCAPE)
            await("one Viewer closed") { !viewer.isShowing }
            Files.writeString(evidence.resolve("result.txt"), "PASS: clicked message-2 opens 3/3; keyboard 2,1,2,3; one Viewer; no writes\n" + describe())
            Unit
        } catch (failure: Throwable) {
            Files.writeString(evidence.resolve("failure.txt"), failure.stackTraceToString() + "\n" + describe())
            throw failure
        } finally {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
            Files.write(evidence.resolve("key-events.txt"), keyEvents)
            EventQueue.invokeAndWait { Window.getWindows().filter { it !in initialWindows }.forEach { it.dispose() } }
            fixture.close()
        }
    }
}
