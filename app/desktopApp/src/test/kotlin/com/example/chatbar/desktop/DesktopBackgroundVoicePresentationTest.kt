@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.operation.NoOpAppDataOperationGate
import kotlinx.coroutines.*
import java.nio.file.*
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopBackgroundVoicePresentationTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private suspend fun ImageComposeScene.frames() { repeat(5) { render().close(); yield(); delay(15) } }
    private fun ImageComposeScene.shot(name: String) {
        val target = System.getenv("CCB_SELECTED_FIX_REVIEW_DIR")?.let { Path.of(it).resolve(name) } ?: return
        Files.createDirectories(target.parent)
        render().use { image -> image.encodeToData()!!.use { Files.write(target, it.bytes) } }
    }
    private suspend fun ImageComposeScene.clickText(text: String) {
        fun matches(n: SemanticsNode): Boolean = n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true || n.children.any(::matches)
        nodes().first { matches(it) && it.config.contains(SemanticsActions.OnClick) }.config[SemanticsActions.OnClick].action!!()
        frames()
    }

    @Test fun `floating player exposes working controls and per-session choice in compact and wide layouts`(): Unit = runBlocking(edt) {
        DesktopChatVoiceRuntimeTest.Fixture().use { f ->
            f.seed(); f.enqueue(); f.done(f.runtime.generate(f.message, "alice"))
            val voice = f.repo.listForSession(f.session.id).single()
            val preferences = DesktopChatUiController(DesktopSettingsStore(f.root, NoOpAppDataOperationGate))
            preferences.load()
            f.runtime.foreground(f.session.id).join(); f.runtime.play(voice, f.message)?.join()
            withTimeout(5000) { while (!f.runtime.playback.state.value.playing) delay(10) }
            f.runtime.foreground(null).join()
            for (width in listOf(320, 900)) {
                val position = DesktopFloatingVoicePosition()
                val scene = ImageComposeScene(width, 400, coroutineContext = edt) {
                    Box(Modifier.fillMaxSize()) { DesktopFloatingVoicePlayerHost(f.runtime, preferences, position) }
                }
                try {
                    scene.frames()
                    fun button(label: String) = scene.nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
                    val labels = listOf("上一句语音", "暂停并保留位置", "下一句语音", "停止并关闭播放器", "播放设置")
                    labels.forEach { label ->
                        val node = button(label)
                        assertTrue(node.boundsInRoot.left >= 0 && node.boundsInRoot.right <= width, label)
                        assertTrue(node.boundsInRoot.width >= 28f && node.boundsInRoot.height >= 28f, label)
                    }
                    fun window() = scene.nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "floating-voice-player-window" }.boundsInRoot
                    val original = window()
                    val controls = scene.nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "voice-floating-player" }.boundsInRoot
                    assertTrue(original.width <= controls.width + 18f, "A short title must not stretch the floating window beyond its controls and padding")
                    val handle = scene.nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "floating-voice-player-drag-handle" }.boundsInRoot.center
                    scene.sendPointerEvent(PointerEventType.Press, handle, button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Move, handle + Offset(-20f, -20f), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Move, handle + Offset(-70f, -70f), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, handle + Offset(-70f, -70f), button = PointerButton.Primary)
                    scene.frames()
                    assertNotNull(position.preferred)
                    assertTrue(window().top < original.top - 20f, "Title drag moves the player")
                    val savedPosition = position.preferred
                    scene.constraints = Constraints.fixed(width, 150); scene.frames()
                    assertTrue(window().left >= 0 && window().right <= width && window().top >= 0 && window().bottom <= 150)
                    assertEquals(savedPosition, position.preferred, "Temporary shrink retains the chosen position")
                    scene.constraints = Constraints.fixed(width, 400); scene.frames()
                    scene.shot("voice-floating-$width.png")
                    button("播放设置").config[SemanticsActions.OnClick].action!!(); scene.frames()
                    scene.clickText("会话连续播放 · 跟随全局（开启） ▾")
                    scene.clickText("关闭")
                    withTimeout(5000) { while (preferences.state.value.sessionContinuousVoicePlayback[f.session.id] != false) scene.frames() }
                    scene.shot("voice-settings-$width.png")
                    val close = button("关闭播放设置")
                    assertTrue(close.boundsInRoot.width <= 30f && close.boundsInRoot.height <= 30f)
                    close.config[SemanticsActions.OnClick].action!!(); scene.frames()
                    assertFalse(scene.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("关闭播放设置") == true })
                    assertTrue(f.runtime.playback.state.value.playing, "Closing settings must keep playback running")
                    button("暂停并保留位置").config[SemanticsActions.OnClick].action!!()
                    withTimeout(5000) { while (!f.runtime.playback.state.value.paused) scene.frames() }
                    scene.frames() // Runtime state can publish before Compose has consumed the flow.
                    button("继续播放").config[SemanticsActions.OnClick].action!!()
                    withTimeout(5000) { while (!f.runtime.playback.state.value.playing) scene.frames() }
                    preferences.update { it.copy(sessionContinuousVoicePlayback = emptyMap()) }
                } finally { scene.close() }
            }
            assertEquals(1, f.server.requestCount)
            f.runtime.stopForMaintenance()
            assertFalse(f.output.playing)
        }
    }
}
