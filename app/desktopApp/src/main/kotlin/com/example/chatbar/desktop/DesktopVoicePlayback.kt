package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.data.operation.NoOpAppDataOperationGate
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One native output shared by formal playback and the accepted picker runtime.
 * A superseded port cannot stop or report the other port's playback.
 */
internal class DesktopAudioFocus(private val output: DesktopAudioOutput = DesktopWindowsAudioOutput()) {
    private val mutex = Mutex()
    private var owner: Any? = null
    fun port(formal: Boolean = false): DesktopAudioOutput = object : DesktopAudioOutput {
        private val token = Any()
        override suspend fun play(path: Path) = mutex.withLock {
            output.stopAndDrain(); owner = token
            try { if (formal) output.playFormal(path) else output.play(path) } catch (e: Throwable) { owner = null; throw e }
        }
        override suspend fun pause(): Long = mutex.withLock { check(owner === token); output.pause() }
        override suspend fun resume() = mutex.withLock { check(owner === token); output.resume() }
        override suspend fun positionMs(): Long = mutex.withLock { check(owner === token); output.positionMs() }
        override suspend fun stopAndDrain() = mutex.withLock {
            if (owner === token) { output.stopAndDrain(); owner = null }
        }
        override suspend fun hasFocus(): Boolean = mutex.withLock { owner === token }
        override suspend fun isPlaying(): Boolean = mutex.withLock { owner === token && output.isPlaying() }
        override suspend fun closeAndDrain() { stopAndDrain() }
    }
    suspend fun closeAndDrain() = mutex.withLock { output.closeAndDrain(); owner = null }
}

internal data class DesktopVoicePlaybackState(val currentVoiceId: String? = null,
    val queueVoiceIds: List<String> = emptyList(), val playing: Boolean = false, val error: String? = null,
    val paused: Boolean = false, val positionMs: Long = 0)

/** Explicit playback only; never owns a network credential. Manual play replaces the queue.
 * Stop joins the polling job before returning, releasing native file handles before deletion.
 */
internal class DesktopVoicePlayback(private val store: DesktopVoiceAudioStore,
    private val output: DesktopAudioOutput = DesktopWindowsAudioOutput(),
    private val gate: AppDataOperationGate = NoOpAppDataOperationGate,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(DesktopVoicePlaybackState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private val queue = mutableListOf<GeneratedVoiceMessage>()
    private var pending = Channel<GeneratedVoiceMessage>(Channel.UNLIMITED)
    private var closed = false

    suspend fun playSequence(voices: List<GeneratedVoiceMessage>, eligible: () -> Boolean = { true }) = mutex.withLock {
        if (!eligible()) return@withLock
        check(!closed); stopLocked(); append(voices); startLocked()
    }
    suspend fun enqueueSequence(voices: List<GeneratedVoiceMessage>, eligible: () -> Boolean = { true }) = mutex.withLock {
        if (!eligible()) return@withLock
        check(!closed); append(voices)
        if (job?.isActive != true) startLocked()
    }
    private fun append(voices: List<GeneratedVoiceMessage>) {
        synchronized(queue) { voices.forEach { queue.add(it); check(pending.trySend(it).isSuccess) } }
    }
    private fun startLocked() {
        job = scope.launch {
            try {
                for (voice in pending) {
                    synchronized(queue) {
                        queue.removeAt(0)
                        mutable.value = DesktopVoicePlaybackState(voice.id, listOf(voice.id) + queue.map { it.id })
                    }
                    gate.withNormalOperation {
                    try {
                    val path = store.verify(voice.audioPath)
                    output.playFormal(path)
                    mutable.value = DesktopVoicePlaybackState(voice.id,
                        synchronized(queue) { listOf(voice.id) + queue.map { it.id } }, true)
                    while (true) {
                        // A paused native alias retains its handle and the current data-operation lease.
                        // The same mutex serializes pause/resume with completion observation.
                        val active = mutex.withLock {
                            if (mutable.value.paused) output.hasFocus() else output.isPlaying()
                        }
                        if (!active) break
                        delay(100)
                    }
                    if (!output.hasFocus()) {
                        synchronized(queue) { while (pending.tryReceive().isSuccess) { }; queue.clear() }
                    }
                    } finally { withContext(NonCancellable) {
                        output.stopAndDrain(); mutable.value = DesktopVoicePlaybackState()
                    } }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(playing = false, error = "本地语音播放失败；文件已保留") }
            finally { withContext(NonCancellable) { output.stopAndDrain() } }
        }
    }
    fun dismissError(expected: String) { synchronized(queue) { if (mutable.value.error == expected) mutable.value = mutable.value.copy(error = null) } }
    suspend fun pause() = mutex.withLock {
        if (!mutable.value.playing) return@withLock
        try {
            val position = output.pause()
            mutable.value = mutable.value.copy(playing = false, paused = true, positionMs = position)
        } catch (e: Exception) {
            stopLocked(); mutable.value = DesktopVoicePlaybackState(error = "音频暂停失败；已停止播放，文件已保留")
        }
    }
    suspend fun resume(eligible: () -> Boolean = { true }) = mutex.withLock {
        if (!eligible()) { stopLocked(); return@withLock }
        if (!mutable.value.paused) return@withLock
        try {
            output.resume()
            mutable.value = mutable.value.copy(playing = true, paused = false)
        } catch (e: Exception) {
            stopLocked(); mutable.value = DesktopVoicePlaybackState(error = "音频续播失败；文件已保留")
        }
    }
    suspend fun stop() = mutex.withLock { stopLocked() }
    /** Serialize target removal against manual/automatic queue admission and native alias release. */
    suspend fun <T> withVoiceReleased(id: String, block: suspend () -> T): T = mutex.withLock {
        val queued = synchronized(queue) { mutable.value.currentVoiceId == id || queue.any { it.id == id } }
        if (queued) stopLocked()
        block()
    }
    private suspend fun stopLocked() {
        job?.cancelAndJoin(); job = null; synchronized(queue) { pending.cancel(); pending = Channel(Channel.UNLIMITED); queue.clear() }
        output.stopAndDrain(); mutable.value = DesktopVoicePlaybackState()
    }
    suspend fun closeAndDrain() = withContext(NonCancellable) { mutex.withLock {
        closed = true; stopLocked(); output.closeAndDrain(); scope.cancel()
    } }
}
