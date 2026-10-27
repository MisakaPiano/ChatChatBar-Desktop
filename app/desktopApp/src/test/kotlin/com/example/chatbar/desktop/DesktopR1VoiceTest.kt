@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopR1VoiceTest {
    @Test fun `actual output position advances freezes on pause resumes and unknown is visible`() = runBlocking {
        var position = 100L
        var broken = false
        DesktopChatVoiceRuntimeTest.Fixture(outputFactory = { delegate -> object : DesktopAudioOutput by delegate {
            override suspend fun positionMs(): Long { check(!broken); return position }
            override suspend fun pause(): Long { delegate.pause(); return position }
        } }).use { f ->
            f.seed(); f.enqueue(); f.done(f.runtime.generate(f.message, "alice"))
            val voice = f.repo.listForSession(f.session.id).single()
            val player = f.runtime.playback
            player.playSequence(listOf(voice))
            suspend fun at(value: Long) = withTimeout(5000) { player.state.first { it.positionKnown && it.positionMs == value } }
            at(100); position = 700; at(700)
            player.pause(); val paused = player.state.value
            position = 900; delay(240)
            assertEquals(700, player.state.value.positionMs)
            assertTrue(paused.paused)
            val plays = f.output.plays
            player.resume(); at(900); assertEquals(plays, f.output.plays)
            broken = true
            withTimeout(5000) { player.state.first { it.playing && !it.positionKnown && it.error != null } }
            assertNull(desktopRecordingProgress(voice.id, voice.durationMs, player.state.value))
            broken = false; position = 1200; at(1200)
            assertEquals((1200.0 / voice.durationMs).coerceIn(0.0, 1.0).toFloat(), desktopRecordingProgress(voice.id, voice.durationMs, player.state.value))
            player.stop(); assertNull(player.state.value.currentVoiceId)
        }
    }
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    @Test fun `recording widths and player controls stay usable and stable across states`() = runBlocking(edt) {
        for (viewport in listOf(280, 360, 1100)) for (textWidth in listOf(30f, 240f, 880f)) {
            var playback by mutableStateOf(DesktopVoicePlaybackState("v", playing = true, positionMs = 2500, positionKnown = true))
            var clicks = 0; var menus = 0
            val scene = ImageComposeScene(viewport, 200) {
                Column(Modifier.background(DesktopBootstrapColors.background)) {
                    DesktopRecordingRow("v", "Alice", 10000, playback, textWidth, { clicks++ }, { menus++ })
                    DesktopVoicePlayerRow(playback, { clicks++ }, { clicks++ })
                }
            }
            suspend fun frame() { repeat(4) { scene.render(System.nanoTime()).close(); yield() } }
            fun tag(t: String) = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == t }
            try {
                frame()
                val bounds = tag("recording-row-v").boundsInRoot
                assertEquals(minOf(viewport.toFloat(), maxOf(200f, textWidth)), bounds.width)
                assertEquals(.25f, nodes(scene).single { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }.config[SemanticsProperties.ProgressBarRangeInfo].current)
                val player = tag("voice-floating-player").boundsInRoot
                playback = playback.copy(playing = false, paused = true)
                frame(); assertEquals(bounds, tag("recording-row-v").boundsInRoot)
                assertEquals(player, tag("voice-floating-player").boundsInRoot)
                for (label in listOf("继续播放", "停止并关闭播放器", "录音操作 · Alice")) {
                    val node = nodes(scene).single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
                    assertTrue(node.boundsInRoot.right <= viewport)
                    node.config[SemanticsActions.OnClick].action!!()
                }
                assertEquals(2, clicks); assertEquals(1, menus)
                playback = playback.copy(positionKnown = false)
                frame(); assertTrue(nodes(scene).none { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
                assertEquals(bounds, tag("recording-row-v").boundsInRoot)
                println("R1_VOICE viewport=$viewport textWidth=$textWidth row=$bounds player=$player")
                saveR1Scene(scene, "voice-$viewport-${textWidth.toInt()}")
            } finally { scene.close() }
        }
    }
}
