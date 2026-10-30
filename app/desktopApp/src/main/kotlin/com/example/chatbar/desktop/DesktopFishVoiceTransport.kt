package com.example.chatbar.desktop

import com.example.chatbar.domain.ProxyAwareClient
import com.example.chatbar.domain.voice.*
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okio.BufferedSink

/** Explicit formal synthesis adapter. Reuses official serialization; never accepts a sample URL.
 * This engineering slice authorizes only the time-bounded free profile, no paid fallback.
 */
internal class DesktopFishVoiceTransport(
    private val credentials: DesktopFishAudioCredentials,
    private val store: DesktopVoiceAudioStore,
    client: OkHttpClient = ProxyAwareClient.builder().build(),
    private val baseUrl: String = "https://api.fish.audio",
    private val today: () -> LocalDate = { LocalDate.now(ZoneOffset.UTC) },
) {
    private class Attempt(val exchanges: AtomicInteger = AtomicInteger(), @Volatile var status: Int? = null)
    private val client = client.newBuilder().dispatcher(Dispatcher()).connectionPool(ConnectionPool())
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val attempt = checkNotNull(chain.request().tag(Attempt::class.java))
            if (attempt.exchanges.incrementAndGet() != 1) throw IOException("禁止重复语音请求")
            chain.proceed(chain.request()).also { attempt.status = it.code }
        }.build()
    private val lock = Any()
    private val calls = mutableMapOf<Call, CompletableDeferred<Unit>>()
    private var closed = false

    suspend fun synthesize(modelId: String, referenceId: String, text: String, sessionId: String,
        voiceId: String, onProgress: (FishAudioDownloadProgress.Downloading) -> Unit = {},
    ): FishAudioStoredAudio = DesktopFishTtsAdmission.slots.withPermit {
        check(modelId == FishAudioTtsModels.S2_1_PRO_FREE) { "仅免费模型已获授权；收费或失效模型不会被替换" }
        check(!today().isAfter(LocalDate.of(2026, 11, 30))) { "免费资格需要重新核验；未发送请求" }
        require(referenceId.isNotBlank() && referenceId.length <= 256 && text.isNotBlank())
        val key = try { credentials.forCurrentCall() ?: error("missing") } catch (_: Exception) { error("Fish 安全凭据不可用") }
        val original = FishAudioRequestFactory.tts(baseUrl, key, modelId, referenceId, text.trim())
        val body = checkNotNull(original.body)
        val attempt = Attempt()
        val request = original.newBuilder().tag(Attempt::class.java, attempt).post(object : RequestBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
        }).build()
        val call = client.newCall(request); val done = CompletableDeferred<Unit>()
        synchronized(lock) { check(!closed); calls[call] = done }
        try {
            return@withPermit suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                fun finish() { synchronized(lock) { calls.remove(call) }; done.complete(Unit) }
                fun failure(): Throwable = attempt.status?.takeIf { it !in 200..299 }?.let {
                    FishAudioApiException(it, "Fish 语音请求失败（HTTP $it）；未自动重试")
                } ?: IllegalStateException("Fish 语音网络失败")
                try { call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        try { if (continuation.isActive) continuation.resumeWith(Result.failure(failure())) } finally { finish() }
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try { response.use { result ->
                            if (!continuation.isActive) return
                            val audio = runCatching {
                                check(result.isSuccessful) { "http" }
                                val content = checkNotNull(result.body)
                                check(content.contentType()?.let { it.type == "audio" && it.subtype in setOf("mpeg", "mp3") } == true)
                                // The callback owns body I/O until done. Cancellation cancels the Call and
                                // every write observes the continuation; caller cancellation still joins done.
                                runBlocking {
                                    content.byteStream().use { stream ->
                                        store.persist(sessionId, voiceId, stream, content.contentLength()) { bytes ->
                                            if (call.isCanceled() || !continuation.isActive) throw CancellationException()
                                            onProgress(FishAudioDownloadProgress.Downloading(bytes, content.contentLength().takeIf { it >= 0 }))
                                        }
                                    }
                                }
                            }.recoverCatching { if (it is CancellationException) throw it else throw failure() }
                            if (continuation.isActive) continuation.resumeWith(audio)
                            else audio.getOrNull()?.let { runBlocking { store.deleteIfOwned(it.path) } }
                        } } finally { finish() }
                    }
                }) } catch (_: Exception) { finish(); if (continuation.isActive) continuation.resumeWith(Result.failure(failure())) }
            }
        } finally { withContext(NonCancellable) { done.await() } }
    }
    suspend fun closeAndDrain() = withContext(NonCancellable + Dispatchers.IO) {
        val pending = synchronized(lock) { closed = true; calls.map { (call, done) -> call.cancel(); done } }
        pending.forEach { it.await() }
        client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        check(client.dispatcher.executorService.awaitTermination(30, TimeUnit.SECONDS))
    }
}
