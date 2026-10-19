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
    val queueVoiceIds: List<String> = emptyList(), val playing: Boolean = false, val error: String? = null)

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

    suspend fun playSequence(voices: List<GeneratedVoiceMessage>) = mutex.withLock {
        check(!closed); stopLocked(); append(voices); startLocked()
    }
    suspend fun enqueueSequence(voices: List<GeneratedVoiceMessage>) = mutex.withLock {
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
                    synchronized(queue) { queue.removeAt(0) }
                    gate.withNormalOperation {
                    try {
                    val path = store.verify(voice.audioPath)
                    output.playFormal(path)
                    mutable.value = DesktopVoicePlaybackState(voice.id,
                        synchronized(queue) { listOf(voice.id) + queue.map { it.id } }, true)
                    while (output.isPlaying()) delay(100)
                    if (!output.hasFocus()) {
                        synchronized(queue) { while (pending.tryReceive().isSuccess) { }; queue.clear() }
                    }
                    mutable.value = DesktopVoicePlaybackState()
                    } finally { withContext(NonCancellable) { output.stopAndDrain() } }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = DesktopVoicePlaybackState(error = "本地语音播放失败；文件已保留") }
            finally { withContext(NonCancellable) { output.stopAndDrain() } }
        }
    }
    suspend fun stop() = mutex.withLock { stopLocked() }
    private suspend fun stopLocked() {
        job?.cancelAndJoin(); job = null; synchronized(queue) { pending.cancel(); pending = Channel(Channel.UNLIMITED); queue.clear() }
        output.stopAndDrain(); mutable.value = DesktopVoicePlaybackState()
    }
    suspend fun closeAndDrain() = withContext(NonCancellable) { mutex.withLock {
        closed = true; stopLocked(); output.closeAndDrain(); scope.cancel()
    } }
}
