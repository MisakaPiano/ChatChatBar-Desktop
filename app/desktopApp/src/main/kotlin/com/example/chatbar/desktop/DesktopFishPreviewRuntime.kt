package com.example.chatbar.desktop

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class DesktopFishPreviewKind { GENERATED, OFFICIAL_SAMPLE }

internal data class DesktopFishPreviewState(
    val busy: Boolean = false, val playing: Boolean = false, val cached: Boolean = false,
    val message: String? = null,
    val kind: DesktopFishPreviewKind = DesktopFishPreviewKind.GENERATED,
)

/** App-owned scope survives picker disposal until actual network/native I/O and cleanup finish. */
internal class DesktopFishPreviewRuntime(
    private val transport: DesktopFishPreviewTransport,
    private val output: DesktopAudioOutput = DesktopWindowsAudioOutput(),
    private val model: () -> String = { "s2.1-pro-free" },
    private val stores: () -> DesktopFishPreviewStore = { DesktopFishPreviewStore() },
    private val failureObserver: (Exception) -> Unit = {},
    private val samples: DesktopFishSampleTransport = DesktopFishSampleTransport(),
) {
    private data class CacheKey(val kind: DesktopFishPreviewKind, val reference: String, val source: String = "")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val operations = Mutex()
    private val sessions = mutableSetOf<Session>()
    private var closing = false
    private var outputOwner: Session? = null

    fun openSession(): Session = synchronized(lock) {
        check(!closing)
        // Four outstanding leases bound retained/failed-cleanup caches to 256 MiB in total.
        check(sessions.size < 4) { "预览会话尚未释放；请先完成关闭" }
        Session().also { sessions += it }
    }
    suspend fun closeAndDrain() = withContext(NonCancellable) {
        val pending = synchronized(lock) { closing = true; sessions.toList() }
        pending.forEach { it.requestClose() }
        pending.forEach { it.closeAndDrain() }
        samples.closeAndDrain()
        transport.closeAndDrain()
        output.closeAndDrain()
        scope.cancel()
    }

    inner class Session internal constructor() {
        private val mutable = MutableStateFlow(DesktopFishPreviewState())
        val state = mutable.asStateFlow()
        private val closed = AtomicBoolean()
        private val cleanup = Mutex()
        private val jobs = mutableSetOf<Job>()
        private val cache = mutableMapOf<CacheKey, Path>()
        private var store: DesktopFishPreviewStore? = null
        private var cleaned = false

        fun generate(reference: String, text: String) = submit {
            val selectedModel = model()
            if (selectedModel != "s2.1-pro-free") {
                mutable.value = DesktopFishPreviewState(message = "当前模型未获免费预览授权；请明确选择 s2.1-pro-free，不会自动替换")
                return@submit
            }
            check(text.isNotBlank() && text.codePointCount(0, text.length) <= 120) { "请输入 1–120 字的预览文本" }
            mutable.value = DesktopFishPreviewState(busy = true)
            if (outputOwner != null) { output.stopAndDrain(); outputOwner = null }
            val owned = store ?: stores().also { store = it }
            val key = CacheKey(DesktopFishPreviewKind.GENERATED, reference)
            val cached = cache[key]
            val clip = cached ?: transport.generate(reference, text, selectedModel, owned).also { cache[key] = it }
            play(owned, clip, cached != null, DesktopFishPreviewKind.GENERATED,
                if (cached != null) "复用本次音色库首次生成的音频；新输入未发送" else "免费预览已生成；不写入聊天或角色绑定")
        }

        fun officialSample(reference: String, rawUrl: String) = submit(replace = true) {
            check(reference.isNotBlank() && reference.length <= 256)
            val url = DesktopFishSamplePolicy.approve(rawUrl)
            mutable.value = DesktopFishPreviewState(busy = true, kind = DesktopFishPreviewKind.OFFICIAL_SAMPLE)
            if (outputOwner != null) { output.stopAndDrain(); outputOwner = null }
            val owned = store ?: stores().also { store = it }
            val key = CacheKey(DesktopFishPreviewKind.OFFICIAL_SAMPLE, reference, DesktopFishSamplePolicy.identity(url))
            val cached = cache[key]
            val clip = cached ?: samples.download(rawUrl, owned).also { cache[key] = it }
            play(owned, clip, cached != null, DesktopFishPreviewKind.OFFICIAL_SAMPLE,
                if (cached != null) "复用本次音色库已验证的官方样本；未调用 TTS" else "官方样本已下载；未调用 TTS，不改变音色绑定")
        }

        private suspend fun play(owned: DesktopFishPreviewStore, clip: Path, cached: Boolean,
            kind: DesktopFishPreviewKind, message: String) {
            currentCoroutineContext().ensureActive()
            check(!closed.get())
            owned.verify(clip)
            outputOwner = this
            output.play(clip)
            mutable.value = DesktopFishPreviewState(playing = true, cached = cached, message = message, kind = kind)
            while (!closed.get() && output.isPlaying()) delay(100)
            output.stopAndDrain(); outputOwner = null
        }

        fun stop() {
            val pending = synchronized(lock) { jobs.toList().also { list -> list.forEach { it.cancel() } } }
            if (pending.isNotEmpty()) return
            submit {
                if (outputOwner === this) { output.stopAndDrain(); outputOwner = null }
                mutable.value = mutable.value.copy(busy = false, playing = false, message = "预览已停止")
            }
        }

        private fun submit(replace: Boolean = false, action: suspend () -> Unit) {
            synchronized(lock) {
                if (closed.get() || closing) return
                if (!replace && (mutable.value.busy || mutable.value.playing)) return
                if (replace && jobs.size >= 4) return // Bound queued switches while prior I/O really drains.
                val previous = if (replace) jobs.toList().also { list -> list.forEach { it.cancel() } } else emptyList()
                mutable.value = mutable.value.copy(busy = true)
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    try { previous.joinAll(); operations.withLock { action() } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        failureObserver(failure)
                        mutable.value = DesktopFishPreviewState(message = if (failure is DesktopFishPreviewHttpFailure)
                            "Fish 预览失败（HTTP ${failure.status}）；未自动重试" else
                            "预览失败；请检查模型、凭据、网络或音频设备。未自动重试")
                    } finally {
                        // Keep the owner on a failed native close so app shutdown cannot release it.
                        try { withContext(NonCancellable) {
                            operations.withLock {
                                if (outputOwner === this@Session) { output.stopAndDrain(); outputOwner = null }
                            }
                        } } catch (_: Exception) {
                            mutable.value = DesktopFishPreviewState(message = "音频关闭未确认；文件已保留，应用退出将重试排水")
                        }
                    }
                }
                jobs += job
                job.invokeOnCompletion { synchronized(lock) {
                    jobs.remove(job)
                    if (jobs.isEmpty()) mutable.value = mutable.value.copy(busy = false, playing = false)
                } }
                job.start()
            }
        }

        fun requestClose() {
            if (!closed.compareAndSet(false, true)) return
            synchronized(lock) { jobs.toList().forEach { it.cancel() } }
            scope.launch {
                try { closeAndDrain() }
                catch (_: Exception) { mutable.value = DesktopFishPreviewState(message = "预览资源尚未安全关闭；文件已保留") }
            }
        }

        suspend fun closeAndDrain() = withContext(NonCancellable) {
            closed.set(true)
            val pending = synchronized(lock) { jobs.toList().also { list -> list.forEach { it.cancel() } } }
            pending.joinAll()
            cleanup.withLock {
                if (cleaned) return@withLock
                operations.withLock {
                    if (outputOwner === this@Session) { output.stopAndDrain(); outputOwner = null }
                    store?.close(); cache.clear(); cleaned = true
                    synchronized(lock) { sessions.remove(this@Session) }
                }
            }
        }
    }
}
