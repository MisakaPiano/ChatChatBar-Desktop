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
import com.sun.jna.ptr.PointerByReference
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
    private fun focus(w: Window) {
        EventQueue.invokeAndWait { w.toFront(); w.requestFocus() }
        user.SetForegroundWindow(hwnd(w))
        await("fixture must own Windows foreground") { user.GetForegroundWindow() == hwnd(w) }
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
                    setLocation(30, 30)
                    setContent { DesktopChatBackgroundPanel(controller) {} }
                    isVisible = true
                }
            }
            await("library visible") { windows().any { title(it) == "聊天背景" } }
            val dialog = windows().single { title(it) == "聊天背景" }
            EventQueue.invokeAndWait { dialog.setSize(760, 760); dialog.setLocation(80, 80) }
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
            robot.mouseMove(origin.x + (point.x / scale.scaleX).toInt(), origin.y + (point.y / scale.scaleY).toInt())
            repeat(2) { robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); robot.delay(70) }
            await("one Viewer opened by double click") { windows().count { title(it).startsWith("图片 ") } == 1 }
            val viewer = windows().single { title(it).startsWith("图片 ") }
            Files.writeString(evidence.resolve("states.txt"), describe())
            assertTrue(user.IsWindowEnabled(hwnd(viewer)), "Viewer must not be blocked by background DialogWindow")
            focus(viewer)
            await("Viewer first page") { title(viewer) == "图片 1 / 2" }
            snapshot(viewer, "viewer-first")
            val center = viewer.locationOnScreen
            robot.mouseMove(center.x + viewer.width / 2, center.y + viewer.height / 2)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            await("Viewer receives AWT focus before keyboard input") {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow == viewer &&
                    KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner != null
            }
            robot.keyPress(KeyEvent.VK_RIGHT); robot.keyRelease(KeyEvent.VK_RIGHT)
            await("real keyboard advances Viewer") { title(viewer) == "图片 2 / 2" }
            // Hover/click the actual right/left overlay location in the image area, using the
            // window client geometry. The back arrow occupies the left image edge at mid-height.
            val viewerOrigin = viewer.locationOnScreen
            robot.mouseMove(viewerOrigin.x + 60, viewerOrigin.y + viewer.height / 2)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
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
                    title = "Chat duplicate fixture"; setSize(600, 500); setLocation(60, 60)
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
            robot.mouseMove(origin.x + host.insets.left + 80, origin.y + host.insets.top + 100)
            repeat(2) { robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); robot.delay(70) }
            await("clicked third message opens third collection position") { windows().any { title(it) == "图片 3 / 3" } }
            val viewer = windows().single { title(it).startsWith("图片 ") }
            focus(viewer)
            val center = viewer.locationOnScreen
            robot.mouseMove(center.x + viewer.width / 2, center.y + viewer.height / 2)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
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
