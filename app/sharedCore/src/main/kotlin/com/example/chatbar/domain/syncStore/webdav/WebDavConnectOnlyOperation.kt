package com.example.chatbar.domain.syncStore.webdav

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Proxy
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/** One logical request. Only a failed direct TCP dial may select another frozen DNS address. */
internal class WebDavConnectOnlyOperation(private val dns: Dns, private val host: String) : Dns {
    private val snapshots = ConcurrentHashMap<String, List<InetAddress>>()
    private val failedAddresses = ConcurrentHashMap.newKeySet<InetAddress>()
    private val wireIssued = AtomicBoolean()
    private val headersStarted = AtomicBoolean()

    // OkHttp includes Dns in its connection-pool Address identity. Transient candidate exclusions
    // must not turn every chunk request into a new TLS connection under the same DNS policy.
    override fun equals(other: Any?): Boolean = other is WebDavConnectOnlyOperation && dns == other.dns
    override fun hashCode(): Int = dns.hashCode()

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = snapshots.computeIfAbsent(hostname) { dns.lookup(it).distinct().toList() }
        val available = if (hostname == host) addresses.filterNot(failedAddresses::contains) else addresses
        if (available.isEmpty()) throw UnknownHostException("SYNC_DAV_DNS_EXHAUSTED")
        return available
    }

    // Network interceptors run after connection acquisition, before CallServer writes any headers.
    // retry=false alone does not suppress OkHttp 4.12's 503/421 follow-up exchanges.
    val wireFence = Interceptor { chain ->
        if (!wireIssued.compareAndSet(false, true)) throw ProtocolException("SYNC_DAV_WIRE_REPLAY_REFUSED")
        chain.proceed(chain.request())
    }

    class Attempt {
        internal data class Failed(val address: InetSocketAddress, val proxy: Proxy, val error: IOException)
        internal val failed = AtomicReference<Failed?>()
        internal val tlsStarted = AtomicBoolean()
    }

    fun mayTryNext(attempt: Attempt, call: Call, failure: IOException): Boolean {
        if (wireIssued.get() || headersStarted.get() || call.isCanceled() || attempt.tlsStarted.get()) return false
        val failed = attempt.failed.get() ?: return false
        // No proxy bypass or proxy-policy reinterpretation. SOCKS owns its origin DNS; HTTP proxy
        // CONNECT has a separate protocol and is intentionally outside this direct-dial fallback.
        if (failed.proxy.type() != Proxy.Type.DIRECT || failed.error !== failure ||
            failure !is SocketException && failure !is SocketTimeoutException) return false
        val addresses = snapshots[host] ?: return false
        val address = failed.address.address ?: return false
        if (address !in addresses || !failedAddresses.add(address)) return false
        return addresses.any { it !in failedAddresses }
    }

    /** Forward the complete pinned-4.12 listener surface, including the app's connection trace. */
    fun listener(attempt: Attempt, delegate: EventListener): EventListener = object : EventListener() {
        override fun callStart(call: Call) = delegate.callStart(call)
        override fun proxySelectStart(call: Call, url: HttpUrl) = delegate.proxySelectStart(call, url)
        override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) = delegate.proxySelectEnd(call, url, proxies)
        override fun dnsStart(call: Call, domainName: String) = delegate.dnsStart(call, domainName)
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = delegate.dnsEnd(call, domainName, inetAddressList)
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = delegate.connectStart(call, inetSocketAddress, proxy)
        override fun secureConnectStart(call: Call) { attempt.tlsStarted.set(true); delegate.secureConnectStart(call) }
        override fun secureConnectEnd(call: Call, handshake: Handshake?) = delegate.secureConnectEnd(call, handshake)
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) = delegate.connectEnd(call, inetSocketAddress, proxy, protocol)
        override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
            attempt.failed.set(Attempt.Failed(inetSocketAddress, proxy, ioe))
            delegate.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
        }
        override fun connectionAcquired(call: Call, connection: Connection) = delegate.connectionAcquired(call, connection)
        override fun connectionReleased(call: Call, connection: Connection) = delegate.connectionReleased(call, connection)
        override fun requestHeadersStart(call: Call) { headersStarted.set(true); delegate.requestHeadersStart(call) }
        override fun requestHeadersEnd(call: Call, request: Request) = delegate.requestHeadersEnd(call, request)
        override fun requestBodyStart(call: Call) = delegate.requestBodyStart(call)
        override fun requestBodyEnd(call: Call, byteCount: Long) = delegate.requestBodyEnd(call, byteCount)
        override fun requestFailed(call: Call, ioe: IOException) = delegate.requestFailed(call, ioe)
        override fun responseHeadersStart(call: Call) = delegate.responseHeadersStart(call)
        override fun responseHeadersEnd(call: Call, response: Response) = delegate.responseHeadersEnd(call, response)
        override fun responseBodyStart(call: Call) = delegate.responseBodyStart(call)
        override fun responseBodyEnd(call: Call, byteCount: Long) = delegate.responseBodyEnd(call, byteCount)
        override fun responseFailed(call: Call, ioe: IOException) = delegate.responseFailed(call, ioe)
        override fun callEnd(call: Call) = delegate.callEnd(call)
        override fun callFailed(call: Call, ioe: IOException) = delegate.callFailed(call, ioe)
        override fun canceled(call: Call) = delegate.canceled(call)
        override fun satisfactionFailure(call: Call, response: Response) = delegate.satisfactionFailure(call, response)
        override fun cacheHit(call: Call, response: Response) = delegate.cacheHit(call, response)
        override fun cacheMiss(call: Call) = delegate.cacheMiss(call)
        override fun cacheConditionalHit(call: Call, cachedResponse: Response) = delegate.cacheConditionalHit(call, cachedResponse)
    }
}
