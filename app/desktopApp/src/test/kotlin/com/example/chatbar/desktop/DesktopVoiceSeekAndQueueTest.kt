@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.*
import com.example.chatbar.data.local.entity.*
import java.nio.file.*
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopVoiceSeekAndQueueTest {
    @Test fun `dragging a paused recording seeks on release without invoking play pause`() = runBlocking(edt) {
        val seeks = mutableListOf<Long>(); var plays = 0
        val scene = ImageComposeScene(500, 120) {
            DesktopRecordingRow("clip", "Speaker", 10000, DesktopVoicePlaybackState("clip", paused = true,
                positionMs = 2000, positionKnown = true), 400f, { plays++ }, {}, onSeek = { seeks += it })
        }
        try {
            repeat(5) { scene.render().close(); yield() }
            fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
            val progress = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                .single { it.config.getOrNull(SemanticsProperties.TestTag) == "recording-progress-clip" }.boundsInRoot
            val from = Offset(progress.left + progress.width * .2f, progress.center.y)
            val to = Offset(progress.left + progress.width * .75f, progress.center.y)
            scene.sendPointerEvent(PointerEventType.Press, from, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Move, to, button = PointerButton.Primary)
            scene.render().close(); yield(); assertTrue(seeks.isEmpty())
            scene.sendPointerEvent(PointerEventType.Release, to, button = PointerButton.Primary)
            repeat(5) { scene.render().close(); yield() }
            assertEquals(0, plays); assertEquals(1, seeks.size); assertTrue(seeks.single() in 7400L..7600L)
        } finally { scene.close() }
    }
    @Test fun `paused MCI seek stays silent until resume and reads actual position`() = runBlocking {
        val calls = mutableListOf<String>(); var mode = "playing"; var position = 123L
        val output = DesktopWindowsAudioOutput { command ->
            calls += command
            when {
                command.contains(" length ") -> "12000"
                command.contains(" position ") -> position.toString()
                command.contains(" mode ") -> mode
                command.startsWith("pause ") -> { mode = "paused"; "" }
                command.startsWith("seek ") -> { position = command.substringAfter(" to ").substringBefore(' ').toLong(); mode = "stopped"; "" }
                command.startsWith("play ") -> { mode = "playing"; "" }
                else -> ""
            }
        }
        try {
            output.playFormal(Path.of("synthetic.mp3")); output.pause()
            assertEquals(5000, output.seekTo(5000)); assertEquals(11999, output.seekTo(99999))
            assertEquals(1, calls.count { it.startsWith("play ") })
            assertEquals(1, calls.count { it.startsWith("open ") })
            output.resume()
            assertTrue(calls.any { it.startsWith("play ") && it.endsWith("from 11999") })
        } finally { output.closeAndDrain() }
    }

    @Test fun `continuous off preserves current reply remainder but removes future replies`() = runBlocking {
        DesktopChatVoiceRuntimeTest.Fixture().use { f ->
            f.seed(); f.enqueue(); f.done(f.runtime.generate(f.message, "alice"))
            val voice = f.repo.placementsForMessage(f.message).single().voice
            val sameReply = voice.copy(id = "same-reply")
            val nextReply = voice.copy(id = "next-reply", messageId = "next-message")
            val player = f.runtime.playback
            assertTrue(player.continuousPlayback.value)
            player.playSequence(listOf(voice, sameReply, nextReply))
            withTimeout(3000) { while (!player.state.value.playing) delay(10) }
            player.pause(); player.setContinuousPlayback(false)
            assertEquals(listOf(voice.id, sameReply.id), player.state.value.queueVoiceIds)
            player.resume(); f.output.playing = false
            withTimeout(3000) { while (player.state.value.currentVoiceId != sameReply.id || !player.state.value.playing) delay(10) }
            assertFalse(player.state.value.queueVoiceIds.contains(nextReply.id))
            val requests = f.server.requestCount
            player.stop(); player.playSequence(listOf(voice, nextReply))
            withTimeout(3000) { while (!player.state.value.playing) delay(10) }
            assertEquals(listOf(voice.id), player.state.value.queueVoiceIds)
            assertEquals(requests, f.server.requestCount)
        }
    }

    @Test fun `continuous preference defaults enabled and survives reload without changing other fields`() = runBlocking {
        val root = Files.createTempDirectory("ccb-continuous-pref-")
        val gate = DesktopDataOperationCoordinator()
        try {
            val store = DesktopSettingsStore(root, gate)
            val first = DesktopChatUiController(store); first.load()
            assertTrue(first.state.value.continuousVoicePlayback)
            first.update { it.copy(continuousVoicePlayback = false, peopleWidthDp = 250f,
                sessionContinuousVoicePlayback = mapOf("enabled" to true, "disabled" to false)) }
            val reopened = DesktopChatUiController(store); reopened.load()
            assertFalse(reopened.state.value.continuousVoicePlayback)
            assertEquals(250f, reopened.state.value.peopleWidthDp)
            assertTrue(reopened.state.value.continuousPlaybackFor("enabled"))
            assertFalse(reopened.state.value.continuousPlaybackFor("disabled"))
            assertFalse(reopened.state.value.continuousPlaybackFor("follow"))
            reopened.update { it.copy(continuousVoicePlayback = true) }
            assertTrue(reopened.state.value.continuousPlaybackFor("follow"))
            assertFalse(reopened.state.value.continuousPlaybackFor("disabled"))
            reopened.update { it.copy(sessionContinuousVoicePlayback = it.sessionContinuousVoicePlayback - "disabled") }
            assertTrue(reopened.state.value.continuousPlaybackFor("disabled"))
        } finally {
            gate.closeAndDrain()
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `official supplied text can expand fully and collapse without binding or audio work`() = runBlocking(edt) {
        val supplied = (1..100).joinToString("\n") { "官方示例第 $it 行" }
        val scene = ImageComposeScene(600, 400) { DesktopOfficialSampleText("voice", "Voice", "https://sample.invalid/a", supplied) }
        fun nodes(): List<SemanticsNode> {
            fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
        }
        suspend fun frames() { repeat(5) { scene.render().close(); yield() } }
        try {
            frames()
            val action = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("官方示例文字 · Voice") == true }
            action.config[SemanticsActions.OnClick].action!!(); frames()
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == supplied } == true })
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "official-sample-fulltext" })
            nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("官方示例文字 · Voice") == true }
                .config[SemanticsActions.OnClick].action!!(); frames()
            assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "official-sample-fulltext" })
        } finally { scene.close() }
    }
}
