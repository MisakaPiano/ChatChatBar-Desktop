package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.data.operation.NoOpAppDataOperationGate
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.select

/** One native output shared by formal playback and the accepted picker runtime.
 * A superseded port cannot stop or report the other port's playback.
 */
internal class DesktopAudioFocus(private val output: DesktopAudioOutput = DesktopWindowsAudioOutput()) {
    private val mutex = Mutex()
    private var owner: Any? = null
    @Volatile private var maintenance: Any? = null
    @Volatile private var sealed = false
    @Volatile private var uncertain = false
    @Volatile private var generation = 0L

    private fun requirePlaybackAdmission() {
        check(!sealed && !uncertain && maintenance == null) { "音频维护期间或排水未确认，暂不接受播放" }
    }

    /** Reservation never waits for another maintenance owner. No mutex is held across scheduler/gate waits. */
    suspend fun reserveMaintenance(interrupt: Boolean): Any {
        if (interrupt) return mutex.withLock { reserveLocked(true) }
        if (!mutex.tryLock()) throw DesktopAudioMaintenanceBusyException()
        try { return reserveLocked(false) } finally { mutex.unlock() }
    }
    private fun reserveLocked(interrupt: Boolean): Any {
        if (sealed || maintenance != null || (!interrupt && (owner != null || uncertain))) {
            throw DesktopAudioMaintenanceBusyException()
        }
        return Any().also { maintenance = it; generation++ }
    }
    suspend fun drainMaintenance(token: Any) = mutex.withLock {
        check(maintenance === token)
        try { output.stopAndDrain(); owner = null; uncertain = false }
        catch (failure: Throwable) { uncertain = true; throw failure }
    }
    suspend fun releaseMaintenance(token: Any, drained: Boolean, restartRequired: Boolean) = mutex.withLock {
        check(maintenance === token)
        if (!drained) uncertain = true
        sealed = sealed || restartRequired
        maintenance = null
    }
    fun port(formal: Boolean = false): DesktopAudioOutput = object : DesktopAudioOutput {
        private val token = Any()
        override fun playbackAdmissionToken(): Long = generation
        override fun acceptsPlayback(token: Long): Boolean =
            token == generation && maintenance == null && !sealed && !uncertain
        private suspend fun start(path: Path, expected: Long?, formalRequest: Boolean) = mutex.withLock {
            requirePlaybackAdmission()
            check(expected == null || expected == generation) { "播放请求已被音频维护撤销" }
            try {
                output.stopAndDrain(); owner = token
                if (formalRequest) output.playFormal(path) else output.play(path)
            } catch (e: Throwable) { uncertain = true; throw e }
        }
        override suspend fun play(path: Path) = start(path, null, formal)
        override suspend fun playFormal(path: Path) = start(path, null, true)
        override suspend fun playFormal(path: Path, token: Long) = start(path, token, true)
        override suspend fun pause(): Long = mutex.withLock { check(owner === token); output.pause() }
        override suspend fun resume() = mutex.withLock { requirePlaybackAdmission(); check(owner === token); output.resume() }
        override suspend fun positionMs(): Long = mutex.withLock { check(owner === token); output.positionMs() }
        override suspend fun stopAndDrain() = mutex.withLock {
            if (owner === token) {
                try { output.stopAndDrain(); owner = null }
                catch (failure: Throwable) { uncertain = true; throw failure }
            }
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
    private class DrainControl {
        val failed = AtomicBoolean()
        val firstFailure = CompletableDeferred<Throwable>()
        val retries = Channel<CompletableDeferred<Result<Unit>>>(Channel.UNLIMITED)
    }
    private var drain: DrainControl? = null
    private data class QueuedVoice(val voice: GeneratedVoiceMessage, val admission: Long)
    private val queue = mutableListOf<QueuedVoice>()
    private var pending = Channel<QueuedVoice>(Channel.UNLIMITED)
    private var closed = false

    fun admissionToken(): Long = output.playbackAdmissionToken()
    suspend fun playSequence(voices: List<GeneratedVoiceMessage>, admission: Long = admissionToken(), eligible: () -> Boolean = { true }) {
        mutex.withLock {
        if (!eligible() || !output.acceptsPlayback(admission)) return@withLock
        check(!closed); stopLocked(); append(voices, admission); startLocked()
        }
    }
    suspend fun enqueueSequence(voices: List<GeneratedVoiceMessage>, admission: Long = admissionToken(), eligible: () -> Boolean = { true }) {
        mutex.withLock {
        if (!eligible() || !output.acceptsPlayback(admission)) return@withLock
        check(drain?.failed?.get() != true) { "音频关闭未确认，请先重试停止" }
        check(!closed); append(voices, admission)
        if (job?.isActive != true) startLocked()
        }
    }
    private fun append(voices: List<GeneratedVoiceMessage>, admission: Long) {
        synchronized(queue) { voices.forEach { val item = QueuedVoice(it, admission); queue.add(item); check(pending.trySend(item).isSuccess) } }
    }
    private fun startLocked() {
        val control = DrainControl()
        drain = control
        job = scope.launch {
            try {
                for (item in pending) {
                    val voice = item.voice
                    synchronized(queue) {
                        queue.removeAt(0)
                        mutable.value = DesktopVoicePlaybackState(voice.id, listOf(voice.id) + queue.map { it.voice.id })
                    }
                    gate.withNormalOperation {
                    try {
                    if (!output.acceptsPlayback(item.admission)) return@withNormalOperation
                    val path = store.verify(voice.audioPath)
                    output.playFormal(path, item.admission)
                    mutable.value = DesktopVoicePlaybackState(voice.id,
                        synchronized(queue) { listOf(voice.id) + queue.map { it.voice.id } }, true)
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
                        drainNative(control)
                        mutable.value = DesktopVoicePlaybackState()
                    } }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(playing = false, error = "本地语音播放失败；文件已保留") }
            finally { withContext(NonCancellable) {
                try { output.stopAndDrain() }
                catch (_: Exception) { mutable.value = mutable.value.copy(playing = false, error = "音频关闭未确认；文件已保留，维护将拒绝继续") }
            } }
        }
    }
    /** Failed close keeps the original normal registration alive. Only an explicit stop/retry
     * commands another close; no timer retry and no false lease release while the alias is unknown.
     */
    private suspend fun drainNative(control: DrainControl) {
        try { output.stopAndDrain(); return }
        catch (failure: Exception) {
            control.failed.set(true)
            mutable.value = mutable.value.copy(playing = false, error = "音频关闭未确认；文件和数据登记已保留，请重试停止")
            control.firstFailure.complete(failure)
        }
        while (true) {
            val reply = control.retries.receive()
            try { output.stopAndDrain(); reply.complete(Result.success(Unit)); return }
            catch (failure: Exception) { reply.complete(Result.failure(failure)) }
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
        val queued = synchronized(queue) { mutable.value.currentVoiceId == id || queue.any { it.voice.id == id } }
        if (queued) stopLocked()
        block()
    }
    private suspend fun stopLocked() {
        val current = job
        val control = drain
        if (current != null && !current.isCompleted) {
            current.cancel()
            if (control?.failed?.get() == true) {
                val reply = CompletableDeferred<Result<Unit>>()
                control.retries.send(reply)
                reply.await().getOrThrow()
            } else if (control != null) {
                select<Unit> {
                    current.onJoin { }
                    control.firstFailure.onAwait { throw it }
                }
            }
            current.join()
        }
        job = null; drain = null
        synchronized(queue) { pending.cancel(); pending = Channel(Channel.UNLIMITED); queue.clear() }
        output.stopAndDrain(); mutable.value = DesktopVoicePlaybackState()
    }
    suspend fun closeAndDrain() = withContext(NonCancellable) { mutex.withLock {
        closed = true; stopLocked(); output.closeAndDrain(); scope.cancel()
    } }
}
