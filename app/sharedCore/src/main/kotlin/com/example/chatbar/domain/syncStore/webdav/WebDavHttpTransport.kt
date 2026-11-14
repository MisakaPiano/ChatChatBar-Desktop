package com.example.chatbar.domain.syncStore.webdav

import com.example.chatbar.domain.ProxyAwareClient
import com.example.chatbar.domain.syncStore.CiphertextStoreException
import com.example.chatbar.domain.syncStore.CiphertextStoreFailure
import java.io.IOException
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource

enum class WebDavEndpointPolicy { HTTPS_ONLY, SYNTHETIC_LOOPBACK_HTTP }

/**
 * No account registration, token persistence, logger or global routing mutation. The injected client
 * must not log credentials/bodies; the default retains the application's existing routing policy.
 * TLS trust and hostname verification remain those of the normal client, never a trust-all override.
 * This transport disables redirects, transparent retries, response caching and authenticator retries.
 */
class WebDavHttpTransport(
    private val base: HttpUrl,
    client: OkHttpClient = ProxyAwareClient.builder().build(),
    private val authorization: () -> String? = { null },
    policy: WebDavEndpointPolicy = WebDavEndpointPolicy.HTTPS_ONLY,
) : AutoCloseable {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).cache(null).build()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val callAdmission = Any()
    @Volatile private var closed = false

    internal val endpointBinding: String = MessageDigest.getInstance("SHA-256")
        .digest(base.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    init {
        val synthetic = policy == WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP &&
            base.scheme == "http" && base.host in setOf("127.0.0.1", "::1", "[::1]")
        require((base.isHttps || synthetic) && base.username.isEmpty() && base.password.isEmpty() &&
            base.query == null && base.fragment == null && base.encodedPath.endsWith('/')) { "SYNC_DAV_ENDPOINT" }
    }

    internal fun url(path: String): HttpUrl {
        val segments = path.split('/').filter(String::isNotEmpty)
        require(segments.isNotEmpty() && segments.all {
            it != "." && it != ".." && it.matches(Regex("[a-z0-9.-]{1,96}"))
        }) { "SYNC_DAV_PATH" }
        return base.newBuilder().apply {
            segments.forEach(::addPathSegment)
            if (path.endsWith('/')) addPathSegment("")
        }.build()
    }

    internal fun execute(method: String, path: String, body: RequestBody? = null,
        headers: Map<String, String> = emptyMap()): Response {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SYNC_DAV_INTERRUPTED")
        val auth = try { authorization() }
            catch (failure: CancellationException) { throw failure }
            catch (failure: InterruptedIOException) { throw failure }
            catch (_: Exception) { fail(CiphertextStoreFailure.AUTHENTICATION) }
        if (auth != null && (auth.length > 16 * 1024 || auth.any { it.code !in 32..126 })) {
            fail(CiphertextStoreFailure.AUTHENTICATION)
        }
        val request = Request.Builder().url(url(path)).method(method, body)
            .header("Accept-Encoding", "identity").header("Cache-Control", "no-cache, no-store")
            .apply { if (!auth.isNullOrEmpty()) header("Authorization", auth); headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val call = synchronized(callAdmission) {
            if (closed) throw InterruptedIOException("SYNC_DAV_CLOSED")
            client.newCall(request).also { calls += it }
        }
        return try {
            val response = call.execute()
            synchronized(callAdmission) {
                if (closed) {
                    response.close()
                    throw InterruptedIOException("SYNC_DAV_CLOSED")
                }
            }
            val body = response.body
            if (body == null) {
                calls -= call
                response
            } else response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source(): BufferedSource = body.source()
                override fun close() { try { body.close() } finally { calls -= call } }
            }).build()
        } catch (failure: CiphertextStoreException) {
            calls -= call
            throw failure
        } catch (_: SSLException) {
            calls -= call
            fail(CiphertextStoreFailure.PERMISSION)
        } catch (_: IOException) {
            calls -= call
            if (closed) throw InterruptedIOException("SYNC_DAV_CLOSED")
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SYNC_DAV_INTERRUPTED")
            fail(CiphertextStoreFailure.TRANSIENT)
        } catch (failure: Throwable) {
            calls -= call
            throw failure
        }
    }

    /** Cancel only calls owned by this route, including response streams awaiting their full GET. */
    override fun close() {
        val active = synchronized(callAdmission) {
            closed = true
            calls.toList()
        }
        active.forEach { it.cancel() }
    }

    /** Classify response-stream network failures without turning a private disk sink error into one. */
    internal fun input(body: ResponseBody): InputStream = object : FilterInputStream(body.byteStream()) {
        private fun <T> network(work: () -> T): T = try { work() }
            catch (failure: CiphertextStoreException) { throw failure }
            catch (_: IOException) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SYNC_DAV_INTERRUPTED")
                fail(CiphertextStoreFailure.TRANSIENT)
            }
        override fun read(): Int = network { `in`.read() }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = network { `in`.read(bytes, offset, length) }
    }

    internal fun requireStatus(status: Int, accepted: Set<Int>) {
        if (status in accepted) return
        fail(when (status) {
            401 -> CiphertextStoreFailure.AUTHENTICATION
            403, in 300..399 -> CiphertextStoreFailure.PERMISSION
            507 -> CiphertextStoreFailure.QUOTA
            405, 409, 413, 501 -> CiphertextStoreFailure.UNSUPPORTED
            408, 423, 425, 429, in 500..599 -> CiphertextStoreFailure.TRANSIENT
            else -> CiphertextStoreFailure.IO
        })
    }

    internal fun requireConditionalCreate(status: Int) {
        if (status in 200..299 && status != 201) fail(CiphertextStoreFailure.UNSUPPORTED)
        requireStatus(status, setOf(201, 412))
    }

    internal companion object {
        fun fail(failure: CiphertextStoreFailure): Nothing = throw CiphertextStoreException(failure)
    }
}
