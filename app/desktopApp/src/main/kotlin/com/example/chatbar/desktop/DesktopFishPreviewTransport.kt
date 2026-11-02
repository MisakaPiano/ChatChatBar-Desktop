package com.example.chatbar.desktop

import com.example.chatbar.domain.ProxyAwareClient
import com.example.chatbar.domain.voice.FishAudioRequestFactory
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okio.BufferedSink

internal class DesktopFishPreviewHttpFailure(val status: Int) : IllegalStateException("Fish 预览失败（HTTP $status）；未自动重试")

/** Fixed-origin TTS only. Catalog sample URLs never enter this transport. */
internal class DesktopFishPreviewTransport(
    private val credentials: DesktopFishAudioCredentials,
    client: OkHttpClient = ProxyAwareClient.builder().build(),
    private val baseUrl: String = "https://api.fish.audio",
    private val diagnostics: DesktopTransportDiagnosticsOwner? = null,
) {
    private class Attempts(val count: AtomicInteger = AtomicInteger())
    private val client = client.newBuilder().dispatcher(Dispatcher()).connectionPool(ConnectionPool())
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .addNetworkInterceptor { chain ->
            // Also stops HTTP follow-ups (e.g. 503 Retry-After:0), before a second exchange sends headers.
            if (chain.request().tag(Attempts::class.java)!!.count.incrementAndGet() != 1) throw IOException("禁止重复 TTS 请求")
            chain.proceed(chain.request())
        }.build()
    private val lock = Any()
    private val calls = mutableMapOf<Call, CompletableDeferred<Unit>>()
    private var closed = false
    internal val diagnosticOwner get() = diagnostics
    fun captureCall() = credentials.captureCall()

    suspend fun generate(reference: String, text: String, model: String, store: DesktopFishPreviewStore,
        diagnosticSessionId: String = "voice-preview"): Path = DesktopFishTtsAdmission.slots.withPermit {
        require(model == "s2.1-pro-free") { "本切片仅授权 s2.1-pro-free；不会替换其他模型" }
        require(text.isNotBlank() && text.codePointCount(0, text.length) <= 120 && reference.isNotBlank() && reference.length <= 256)
        val credential = try { credentials.currentCall() } catch (_: Exception) { error("Fish 安全凭据不可用") }
        val key = credential.read()?.takeIf(String::isNotBlank) ?: error("Fish 安全凭据不可用")
        val sent = text.trim()
        val original = FishAudioRequestFactory.tts(baseUrl, key, model, reference, sent)
        val body = requireNotNull(original.body)
        val request = original.newBuilder().tag(Attempts::class.java, Attempts()).method("POST", object : RequestBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
        }).build()
        val context = currentCoroutineContext()[DesktopUsageParent]?.value
            ?: DesktopUsageContext(DesktopUsageSource.VOICE_PREVIEW)
        val media = diagnostics?.beginFishMedia(diagnosticSessionId, model, key, credential.accountId, context,
            sent.codePointCount(0, sent.length).toLong())
        val call = client.newCall(request)
        val done = CompletableDeferred<Unit>()
        synchronized(lock) { check(!closed) { "预览连接已关闭" }; calls[call] = done }
        try {
            val clip = suspendCancellableCoroutine<Path> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                fun finish() { synchronized(lock) { calls.remove(call) }; done.complete(Unit) }
                if (!continuation.isActive) { finish(); return@suspendCancellableCoroutine }
                try {
                    media?.attemptStarted(request)
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            try { if (continuation.isActive) continuation.resumeWith(Result.failure(IllegalStateException("Fish 预览网络失败"))) }
                            finally { finish() }
                        }
                        override fun onResponse(call: Call, response: Response) {
                            try {
                                response.use {
                                    media?.response(it.code)
                                    if (!continuation.isActive) return
                                    val result = runCatching {
                                        if (!it.isSuccessful) throw DesktopFishPreviewHttpFailure(it.code)
                                        val audio = requireNotNull(it.body)
                                        check(audio.contentType()?.let { type -> type.type == "audio" && type.subtype in setOf("mpeg", "mp3") } == true) { "预览响应不是 MP3" }
                                        audio.byteStream().use { stream -> store.write(stream, audio.contentLength()) {
                                            if (call.isCanceled() || !continuation.isActive) throw CancellationException("预览已取消")
                                        } }
                                    }
                                    if (continuation.isActive) continuation.resumeWith(result)
                                }
                            } finally { finish() }
                        }
                    })
                } catch (_: Exception) {
                    finish()
                    if (continuation.isActive) continuation.resumeWith(Result.failure(IllegalStateException("Fish 预览连接不可用")))
                }
            }
            // Keep the existing callback/body owner alive until completion before closing evidence.
            withContext(NonCancellable) { done.await() }
            media?.let {
                val (durationMs, bytes) = store.measurements(clip)
                it.audio(durationMs, bytes)
            }
            media?.finish(DesktopTaskStatus.COMPLETED)
            clip
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { done.await() }
            media?.finish(DesktopTaskStatus.CANCELLED)
            throw cancelled
        } catch (failure: Throwable) {
            withContext(NonCancellable) { done.await() }
            media?.finish(DesktopTaskStatus.FAILED)
            throw failure
        } finally {
            // Cancellation of the coroutine is not proof that callback/body/file I/O has ended.
            withContext(NonCancellable) { done.await() }
        }
    }

    suspend fun closeAndDrain() = withContext(NonCancellable + Dispatchers.IO) {
        val pending = synchronized(lock) { closed = true; calls.map { (call, done) -> call.cancel(); done } }
        pending.forEach { it.await() }
        client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        check(client.dispatcher.executorService.awaitTermination(30, TimeUnit.SECONDS)) { "预览连接尚未排水" }
    }
}
