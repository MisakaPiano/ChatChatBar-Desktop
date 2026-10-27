package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopVoiceClickReliabilityTest {
    private class Native : DesktopAudioOutput {
        @Volatile var alias = false
        @Volatile var paused = false
        @Volatile var position = 4321L
        @Volatile var failClose = false
        var startRelease: CompletableDeferred<Unit>? = null
        var closeRelease: CompletableDeferred<Unit>? = null
        val started = Channel<Unit>(Channel.UNLIMITED)
        val closing = Channel<Unit>(Channel.UNLIMITED)
        val opens = AtomicInteger()
        val pauses = AtomicInteger()
        val resumes = AtomicInteger()
        override suspend fun play(path: Path) {
            check(!alias) { "second native alias" }
            alias = true; paused = false; opens.incrementAndGet(); started.send(Unit)
            startRelease?.await()
        }
        override suspend fun pause(): Long {
            check(alias && !paused); paused = true; pauses.incrementAndGet(); return position
        }
        override suspend fun resume() { check(alias && paused); paused = false; resumes.incrementAndGet() }
        override suspend fun isPlaying() = alias && !paused
        override suspend fun stopAndDrain() {
            if (!alias) return
            closing.send(Unit); closeRelease?.await()
            check(!failClose) { "synthetic close failure" }
            alias = false; paused = false
        }
        override suspend fun closeAndDrain() = stopAndDrain()
    }

    private class Fixture : AutoCloseable {
        val gate = DesktopDataOperationCoordinator()
        val native = Native()
        val focus = DesktopAudioFocus(native)
        val f = DesktopChatVoiceRuntimeTest.Fixture(gate) { focus.port(formal = true) }
        lateinit var voice: GeneratedVoiceMessage
        suspend fun seed() {
            f.seed(); f.enqueue(); f.done(f.runtime.generate(f.message, "alice"))
            voice = f.repo.listForSession(f.session.id).single()
            f.runtime.foreground(f.session.id).join()
        }
        suspend fun click(selected: GeneratedVoiceMessage = voice) = withTimeout(5000) {
            f.runtime.play(selected, f.message)?.join()
        }
        suspend fun playing() = withTimeout(5000) { f.runtime.playback.state.first { it.playing } }
        override fun close() {
            native.failClose = false; native.startRelease?.complete(Unit); native.closeRelease?.complete(Unit)
            f.close()
            runBlocking { focus.closeAndDrain(); gate.closeAndDrain() }
            assertFalse(native.alias); assertEquals(DesktopDataOperationCoordinatorState.CLOSED, gate.state)
            check(f.root.fileName.toString().startsWith("s5-chat-"))
            Files.walk(f.root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun `same recording click pauses and resumes same alias at its position without requests`(): Unit = runBlocking {
        Fixture().use { x ->
            x.seed(); val requests = x.f.server.requestCount
            x.click(); x.native.started.receive(); x.playing()
            val queue = x.f.runtime.playback.state.value.queueVoiceIds
            x.click()
            assertTrue(x.f.runtime.playback.state.value.paused, "same active voice must pause instead of replacing queue")
            assertEquals(4321L, x.f.runtime.playback.state.value.positionMs)
            assertEquals(queue, x.f.runtime.playback.state.value.queueVoiceIds)
            assertEquals(1, x.native.opens.get())
            x.click()
            assertTrue(x.f.runtime.playback.state.value.playing)
            assertEquals(1, x.native.resumes.get()); assertEquals(1, x.native.opens.get())
            assertEquals(4321L, x.native.position)
            assertEquals(requests, x.f.server.requestCount)
        }
    }

    @Test fun `rapid same recording clicks are bounded and never reopen native alias`(): Unit = runBlocking {
        Fixture().use { x ->
            x.seed(); val requests = x.f.server.requestCount
            x.click(); x.native.started.receive(); x.playing()
            withTimeout(5000) { repeat(40) { x.click() } }
            assertTrue(x.f.runtime.playback.state.value.playing)
            assertEquals(20, x.native.pauses.get()); assertEquals(20, x.native.resumes.get())
            assertEquals(1, x.native.opens.get()); assertEquals(requests, x.f.server.requestCount)
            x.f.runtime.stopForMaintenance(); assertFalse(x.native.alias)
            x.gate.withExclusiveMaintenance { assertEquals(DesktopDataOperationCoordinatorState.EXCLUSIVE, x.gate.state) }
        }
    }

    @Test fun `click another recording releases prior alias and leaving invalidates delayed click`(): Unit = runBlocking {
        Fixture().use { x ->
            x.seed(); x.f.enqueue(); x.f.done(x.f.runtime.generate(x.f.message, "alice", auxiliaryAuthorized = true, additional = x.voice))
            val other = x.f.repo.listForSession(x.f.session.id).single { it.id != x.voice.id }
            val requests = x.f.server.requestCount
            x.click(); x.native.started.receive(); x.playing()
            x.click(other); x.native.started.receive(); x.playing()
            assertEquals(other.id, x.f.runtime.playback.state.value.currentVoiceId)
            assertEquals(2, x.native.opens.get())
            val intent = x.f.runtime.playbackIntent()
            x.f.runtime.foreground(null).join()
            x.f.runtime.play(other, x.f.message, intent)?.join()
            assertFalse(x.native.alias); assertEquals(2, x.native.opens.get())
            assertEquals(requests, x.f.server.requestCount)
        }
    }

    @Test fun `maintenance reservation rejects same voice resume and autoplay without new open`(): Unit = runBlocking {
        Fixture().use { x ->
            x.seed(); x.click(); x.native.started.receive(); x.playing(); x.click()
            assertTrue(x.f.runtime.playback.state.value.paused)
            val requests = x.f.server.requestCount
            val token = x.focus.reserveMaintenance(interrupt = true)
            try {
                x.click()
                x.f.runtime.playback.enqueueSequence(listOf(x.voice))
                assertEquals(1, x.native.opens.get()); assertTrue(x.native.paused)
                x.f.runtime.stopForMaintenance(); x.focus.drainMaintenance(token)
            } finally { x.focus.releaseMaintenance(token, drained = !x.native.alias, restartRequired = false) }
            assertFalse(x.native.alias); assertEquals(requests, x.f.server.requestCount)
        }
    }

    @Test fun `failed close retains data registration and rejects new recording until explicit recovery`(): Unit = runBlocking {
        Fixture().use { x ->
            x.seed(); x.click(); x.native.started.receive(); x.playing()
            x.native.failClose = true
            assertFailsWith<IllegalStateException> { x.f.runtime.stopForMaintenance() }
            assertTrue(x.native.alias)
            val entered = CompletableDeferred<Unit>()
            val exclusive = async(Dispatchers.IO) { x.gate.withExclusiveMaintenance { entered.complete(Unit) } }
            withTimeout(5000) { while (x.gate.state != DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING) yield() }
            assertFalse(entered.isCompleted)
            assertEquals(1, x.native.opens.get())
            x.native.failClose = false
            x.f.runtime.stopForMaintenance()
            withTimeout(5000) { exclusive.await() }
            assertFalse(x.native.alias)
        }
    }
}
