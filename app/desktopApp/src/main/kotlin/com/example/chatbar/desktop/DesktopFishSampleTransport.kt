package com.example.chatbar.desktop

import com.example.chatbar.domain.ProxyAwareClient
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Anonymous profile independently evidenced by Fish's public voice-page data and audio element.
 * No authenticated sample origin is approved. Never call shared preview(url, key) here.
 */
internal object DesktopFishSamplePolicy {
    const val ORIGIN = "https://platform.r2.fish.audio"
    private val path = Regex("/task/[0-9a-f]{32}\\.mp3|/tasks/[0-9]{4}-[0-9]{2}-[0-9]{2}/[0-9a-f]{32}\\.mp3")
    fun approve(raw: String): HttpUrl {
        check(raw.length <= 2048 && raw.startsWith("$ORIGIN/")) { "官方样本来源不受支持" }
        val url = try { raw.toHttpUrl() } catch (_: Exception) { error("官方样本来源不受支持") }
        check(url.isHttps && url.host == "platform.r2.fish.audio" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
            path.matches(url.encodedPath) && raw == url.toString()) { "官方样本来源不受支持" }
        return url
    }
    fun identity(url: HttpUrl): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(url.toString().toByteArray(Charsets.UTF_8)))

    fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 16) return (b[0] and 0xe0) == 0x20 // Global unicast only; reject mapped/local forms.
        return b.size == 4 && b[0] !in setOf(0, 10, 127) && b[0] < 224 &&
            !(b[0] == 100 && b[1] in 64..127) && !(b[0] == 169 && b[1] == 254) &&
            !(b[0] == 172 && b[1] in 16..31) && !(b[0] == 192 && b[1] == 168) &&
            !(b[0] == 192 && b[1] == 0 && b[2] in setOf(0, 2)) &&
            !(b[0] == 198 && b[1] in 18..19) && !(b[0] == 198 && b[1] == 51 && b[2] == 100) &&
            !(b[0] == 203 && b[1] == 0 && b[2] == 113)
    }
    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname != "platform.r2.fish.audio") throw IOException("样本来源被拒绝")
            return Dns.SYSTEM.lookup(hostname).also { addresses ->
                if (addresses.isEmpty() || addresses.any { !publicAddress(it) }) throw IOException("样本网络目标被拒绝")
            }
        }
    }
}

internal class DesktopFishSampleTransport(
    client: OkHttpClient = ProxyAwareClient.builder().dns(DesktopFishSamplePolicy.dns).build(),
    // Trusted test-only connection seam: metadata is still approved before any request is built.
    private val connection: (HttpUrl) -> HttpUrl = { it },
) {
    private class Attempt {
        val count = AtomicInteger()
        val firstStatus = AtomicInteger()
        val followUpBlocked = java.util.concurrent.atomic.AtomicBoolean()
    }
    private val client = client.newBuilder().dispatcher(Dispatcher()).connectionPool(ConnectionPool())
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).cookieJar(CookieJar.NO_COOKIES)
        .addNetworkInterceptor { chain ->
            val attempt = requireNotNull(chain.request().tag(Attempt::class.java))
            if (attempt.count.incrementAndGet() != 1) {
                attempt.followUpBlocked.set(true)
                throw IOException("禁止重复样本请求")
            }
            // Final wire boundary also strips headers contributed by an injected client.
            val request = chain.request().newBuilder().removeHeader("Authorization").removeHeader("Cookie")
                .removeHeader("Proxy-Authorization").removeHeader("Referer").build()
            chain.proceed(request).also { attempt.firstStatus.compareAndSet(0, it.code) }
        }.build()
    private val lock = Any()
    private val calls = mutableMapOf<Call, CompletableDeferred<Unit>>()
    private var closed = false

    suspend fun download(raw: String, store: DesktopFishPreviewStore): Path {
        val source = DesktopFishSamplePolicy.approve(raw)
        val request = Request.Builder().url(connection(source)).tag(Attempt::class.java, Attempt())
            .header("Accept", "audio/mpeg").get().build()
        val call = client.newCall(request)
        val done = CompletableDeferred<Unit>()
        synchronized(lock) { check(!closed) { "样本连接已关闭" }; calls[call] = done }
        try {
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                fun finish() { synchronized(lock) { calls.remove(call) }; done.complete(Unit) }
                try {
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            try {
                                val attempt = requireNotNull(request.tag(Attempt::class.java))
                                val status = attempt.firstStatus.get()
                                // A blocked follow-up is not another send. Preserve only an actually
                                // received error status from this call, never an IOException's text.
                                val failure = if (attempt.followUpBlocked.get() && status in 300..599)
                                    DesktopFishPreviewHttpFailure(status)
                                else IllegalStateException("官方样本网络失败；未自动重试")
                                if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
                            }
                            finally { finish() }
                        }
                        override fun onResponse(call: Call, response: Response) {
                            try { response.use {
                                if (!continuation.isActive) return
                                val result = runCatching {
                                    if (!it.isSuccessful) throw DesktopFishPreviewHttpFailure(it.code)
                                    val body = requireNotNull(it.body)
                                    check(body.contentType()?.let { type -> type.type == "audio" && type.subtype in setOf("mpeg", "mp3") } == true) { "官方样本不是受支持的 MP3" }
                                    body.byteStream().use { input -> store.write(input, body.contentLength()) {
                                        if (call.isCanceled() || !continuation.isActive) throw CancellationException("样本已取消")
                                    } }
                                }
                                if (continuation.isActive) continuation.resumeWith(result)
                            } } finally { finish() }
                        }
                    })
                } catch (_: Exception) {
                    finish()
                    if (continuation.isActive) continuation.resumeWith(Result.failure(IllegalStateException("官方样本连接不可用")))
                }
            }
        } finally { withContext(NonCancellable) { done.await() } }
    }

    suspend fun closeAndDrain() = withContext(NonCancellable + Dispatchers.IO) {
        val pending = synchronized(lock) { closed = true; calls.map { (call, done) -> call.cancel(); done } }
        pending.forEach { it.await() }
        client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        check(client.dispatcher.executorService.awaitTermination(30, TimeUnit.SECONDS)) { "样本连接尚未排水" }
    }
}
