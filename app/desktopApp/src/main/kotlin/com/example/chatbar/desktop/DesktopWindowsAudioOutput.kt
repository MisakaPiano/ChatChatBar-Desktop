package com.example.chatbar.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Local-file output port shared by preview and future audio callers. No URL or credential input. */
internal interface DesktopAudioOutput {
    suspend fun play(path: Path)
    suspend fun playFormal(path: Path) { play(path) }
    suspend fun pause(): Long = error("音频后端不支持暂停")
    suspend fun resume() { error("音频后端不支持续播") }
    suspend fun positionMs(): Long = error("音频后端不支持位置读取")
    suspend fun stopAndDrain()
    suspend fun hasFocus(): Boolean = true
    suspend fun isPlaying(): Boolean
    suspend fun closeAndDrain()
}

internal fun interface DesktopMciCommands { fun send(command: String): String }

/** Windows supplies winmm/MPEGVideo; no decoder binary is redistributed. MCI is a legacy
 * replaceable backend, not a cross-platform codec guarantee. All native calls use one thread.
 * A native call has no physical deadline: failure to close retains the alias and owning files.
 */
internal class DesktopWindowsAudioOutput(
    private val commands: DesktopMciCommands = object : DesktopMciCommands {
        private val api by lazy {
            check(System.getProperty("os.name").startsWith("Windows"))
            Native.load("winmm", Winmm::class.java)
        }
        override fun send(command: String): String {
            val buffer = CharArray(256)
            val code = api.mciSendStringW(WString(command), buffer, buffer.size, null)
            check(code == 0) { "Windows 音频操作失败（MCI $code）" }
            return String(buffer).substringBefore('\u0000').trim()
        }
    },
) : DesktopAudioOutput {
    private interface Winmm : StdCallLibrary {
        fun mciSendStringW(command: WString, result: CharArray, size: Int, callback: Pointer?): Int
    }
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "ccb-audio-output") }.asCoroutineDispatcher()
    private val mutex = Mutex()
    private var alias: String? = null
    private var closed = false

    override suspend fun play(path: Path) = playBounded(path, 120_000)
    override suspend fun playFormal(path: Path) = playBounded(path, 1_800_000)
    private suspend fun playBounded(path: Path, maximumDurationMs: Long) = operation {
        check(!closed)
        stopNative()
        val name = "ccb_audio_${UUID.randomUUID().toString().replace("-", "") }"
        val local = path.toAbsolutePath().normalize().toString()
        require(local.none { it == '"' || it == '\n' || it == '\r' } && !local.startsWith("\\\\"))
        commands.send("open \"$local\" type mpegvideo alias $name wait")
        alias = name
        try {
            commands.send("set $name time format milliseconds wait")
            val length = commands.send("status $name length wait").toLongOrNull()
            check(length != null && length in 1..maximumDurationMs) { "MP3 时长不可用或超过限制" }
            commands.send("play $name from 0")
        } catch (failure: Throwable) {
            try { stopNative() } catch (close: Throwable) { close.addSuppressed(failure); throw close }
            throw failure
        }
        Unit
    }

    override suspend fun positionMs(): Long = operation {
        val name = checkNotNull(alias) { "没有可读取的播放会话" }
        commands.send("status $name position wait").toLongOrNull()?.also { check(it >= 0) }
            ?: error("Windows 音频位置不可用")
    }
    override suspend fun pause(): Long = operation {
        val name = checkNotNull(alias) { "没有可暂停的播放会话" }
        commands.send("pause $name wait")
        check(commands.send("status $name mode wait") == "paused") { "Windows 音频暂停未确认" }
        commands.send("status $name position wait").toLongOrNull()?.also { check(it >= 0) }
            ?: error("Windows 音频位置不可用")
    }
    override suspend fun resume() = operation {
        val name = checkNotNull(alias) { "没有可续播的播放会话" }
        check(commands.send("status $name mode wait") == "paused")
        commands.send("resume $name wait")
        check(commands.send("status $name mode wait") in setOf("playing", "stopped"))
        Unit
    }
    override suspend fun isPlaying(): Boolean = operation {
        alias?.let { commands.send("status $it mode wait") == "playing" } ?: false
    }
    override suspend fun stopAndDrain() = operation { stopNative() }
    override suspend fun closeAndDrain() {
        if (closed) return
        operation { stopNative(); closed = true }
        dispatcher.close()
    }
    private suspend fun <T> operation(action: () -> T): T = withContext(NonCancellable + dispatcher) {
        mutex.withLock { action() }
    }
    private fun stopNative() {
        val name = alias ?: return
        // close releases the device even if stop failed; never use "close all".
        var stopFailure: Throwable? = null
        try { commands.send("stop $name wait") } catch (failure: Throwable) { stopFailure = failure }
        try { commands.send("close $name wait"); alias = null }
        catch (failure: Throwable) { stopFailure?.let { failure.addSuppressed(it) }; throw failure }
        // A successful close proves release; a failed stop is still visible to the caller.
        stopFailure?.let { throw it }
    }
}
