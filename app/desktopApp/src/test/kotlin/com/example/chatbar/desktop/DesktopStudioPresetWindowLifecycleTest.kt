@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.awt.event.WindowEvent
import java.awt.Robot
import java.awt.event.InputEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlin.math.roundToInt

/** Actual Studio entry and actual exchange window; Content-only callbacks cannot prove reopening. */
class DesktopStudioPresetWindowLifecycleTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    @Test fun `same Studio page reopens actual preset window after close chooser cancel invalid import export and apply`() = runBlocking(edt) {
        requireDesktopGraphicsForNativeTest()
        val parent = Files.createTempDirectory("preset-window-lifecycle-")
        val root = Files.createDirectory(parent.resolve("data"))
        var input: Path? = null
        var output: Path? = null
        val picker = object : DesktopFilePicker {
            override fun pickOpenFile(type: DesktopFileType) = input
            override fun pickSaveFile(type: DesktopFileType, suggestedName: String) = output
        }
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() },
            filePicker = picker, bundledAssets = { path ->
                if (path.startsWith("presets/")) """{"entries":[]}""".toByteArray()
                else DesktopBundledAssetReader()(path)
            })
        val controller = app.novelAiStudioController
        val source = parent.resolve("source.ccbpreset")
        val bad = Files.write(parent.resolve("invalid.ccbpreset"), byteArrayOf(1, 2, 3))
        var scene: ImageComposeScene? = null
        var opening = 0
        var hostOpen by mutableStateOf(true)
        val hostTitle = "CCB synthetic preset lifecycle Studio"
        val robot = Robot().apply { isAutoWaitForIdle = false }
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        fun matches(node: SemanticsNode, label: String) =
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
        fun nodes(window: ComposeWindow) = window.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
        fun action(window: ComposeWindow, label: String) = nodes(window).firstOrNull {
            matches(it, label) && it.config.contains(SemanticsActions.OnClick)
        }
        suspend fun settle() {
            repeat(3) { scene?.render()?.close(); yield(); delay(20) }
        }
        suspend fun await(description: String, condition: () -> Boolean) {
            val completed = withTimeoutOrNull(15_000) { while (!condition()) settle(); true } == true
            assertTrue(completed, description)
        }
        fun windows() = java.awt.Window.getWindows().filterIsInstance<ComposeWindow>()
            .filter { it.title == "生成预设" && it.isDisplayable }
        fun host() = java.awt.Window.getWindows().filterIsInstance<ComposeWindow>()
            .single { it.title == hostTitle && it.isDisplayable }
        suspend fun pointer(window: ComposeWindow, node: SemanticsNode) {
            window.toFront(); window.requestFocus()
            val position = node.positionOnScreen
            val x = (position.x + node.boundsInRoot.width / 2).roundToInt()
            val y = (position.y + node.boundsInRoot.height / 2).roundToInt()
            assertTrue(window.bounds.contains(x, y), "Synthetic native target outside its own window")
            robot.mouseMove(x, y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            settle()
        }
        suspend fun open(): ComposeWindow {
            opening++
            println("PRESET_WINDOW_OPEN attempt=$opening")
            settle()
            val entry = nodes(host()).single {
                matches(it, "生成预设…") && it.config.contains(SemanticsActions.OnClick)
            }
            assertFalse(entry.config.contains(SemanticsProperties.Disabled), "Same-page preset entry disabled")
            pointer(host(), entry)
            await("Actual preset window did not reopen on the same Studio page, attempt=$opening") {
                windows().size == 1 && windows().single().isShowing && action(windows().single(), "导出当前生成预设") != null
            }
            return windows().single()
        }
        suspend fun click(window: ComposeWindow, label: String) {
            val button = assertNotNull(action(window, label), label)
            assertFalse(button.config.contains(SemanticsProperties.Disabled), label)
            pointer(window, button)
        }
        suspend fun idle(window: ComposeWindow) = await("Exchange working flag did not reset") {
            action(window, "导出当前生成预设")?.config?.contains(SemanticsProperties.Disabled) == false &&
                action(window, "导入生成预设…")?.config?.contains(SemanticsProperties.Disabled) == false
        }
        suspend fun close(window: ComposeWindow, native: Boolean = false) {
            if (native) window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING)) else click(window, "关闭")
            await("Actual preset window did not dispose after close") { !window.isDisplayable && windows().isEmpty() }
        }
        try {
            controller.load()
            assertTrue(controller.edit { it.copy(basePrompt = "Synthetic preset source", followDefaultNovelAiImageModel = false) })
            assertTrue(controller.exportGenerationPreset(source))
            assertTrue(controller.edit { it.copy(basePrompt = "Retained target draft") })
            val original = Files.readAllBytes(root.resolve("entities/novelai_studio_draft.json"))
            scene = ImageComposeScene(1, 1, coroutineContext = edt) {
                if (hostOpen) Window(onCloseRequest = { hostOpen = false }, title = hostTitle,
                    state = rememberWindowState(width = 1300.dp, height = 850.dp)) { DesktopNovelAiStudioPanel(controller) }
            }
            await("Actual native Studio host did not initialize") {
                java.awt.Window.getWindows().filterIsInstance<ComposeWindow>().any {
                    it.title == hostTitle && it.isShowing && nodes(it).any { node -> matches(node, "生成预设…") }
                }
            }

            close(open())
            close(open(), native = true)
            var window = open()
            window.extendedState = java.awt.Frame.ICONIFIED
            await("Synthetic preset child did not minimize") { window.extendedState and java.awt.Frame.ICONIFIED != 0 }
            val existing = window
            window = open()
            assertSame(existing, window, "Opening an existing child must not discard preview or operation ownership")
            await("Repeated Studio entry did not restore its existing minimized preset window") {
                window.extendedState and java.awt.Frame.ICONIFIED == 0 && window.isActive
            }
            click(window, "导入生成预设…"); idle(window) // File chooser cancelled.
            click(window, "导出当前生成预设"); idle(window)
            input = bad
            click(window, "导入生成预设…"); idle(window)
            assertNull(controller.state.value.presetPreview)
            input = source
            click(window, "导入生成预设…"); idle(window)
            assertNotNull(controller.state.value.presetPreview)
            click(window, "取消导入"); idle(window)
            assertNull(controller.state.value.presetPreview)
            assertContentEquals(original, Files.readAllBytes(root.resolve("entities/novelai_studio_draft.json")))
            close(window)

            window = open()
            output = parent.resolve("export.ccbpreset")
            click(window, "导出当前生成预设"); idle(window)
            assertTrue(Files.size(assertNotNull(output)) > 0)
            click(window, "导入生成预设…"); idle(window)
            click(window, "确认替换生成草稿"); idle(window)
            assertEquals("Synthetic preset source", controller.draft.value!!.basePrompt)
            assertTrue(controller.undo())
            assertEquals("Retained target draft", controller.draft.value!!.basePrompt)
            close(window)
            close(open())
            assertTrue(app.taskRuntime.tasks.value.isEmpty())
        } finally {
            hostOpen = false
            scene?.close()
            repeat(3) { yield(); delay(20) }
            app.close()
            check(parent.toFile().deleteRecursively())
        }
    }
}
