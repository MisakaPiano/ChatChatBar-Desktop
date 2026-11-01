package com.example.chatbar.domain.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.*

enum class HttpConnectionPhase(val label: String) {
    PREPARING("排队或请求准备"), PROXY_SELECTION("选择代理"), ROUTE_READY("路由已选择"),
    DNS("域名解析"), CONNECTING("建立连接"), PROXY_CONNECTING("连接代理或协商隧道"),
    TLS("TLS 握手"), TLS_READY("TLS 已建立"), CONNECTED("连接可用"),
    SENDING("发送请求"), WAITING_HEADERS("等待响应头"), HEADERS("等待或读取响应头"), BODY("读取响应"), COMPLETE("传输完成"),
}

/** Fixed phases, timing and selected proxy port. Never stores URL, headers, credentials or bodies. */
data class HttpConnectionTraceSnapshot(
    val phase: HttpConnectionPhase,
    val elapsedMillis: Long,
    val phaseMillis: Long,
    val routeType: String?,
    val protocol: String?,
    val completedPhases: List<Pair<HttpConnectionPhase, Long>>,
    val selectedProxyPort: Int? = null,
) {
    fun summary(): String = "连接阶段：${phase.label}；总计 ${elapsedMillis}ms，当前阶段 ${phaseMillis}ms" +
        (routeType?.let { "；路由 $it" } ?: "") +
        (selectedProxyPort?.let { "；已选代理端口 $it" } ?: "") + (protocol?.let { "；协议 $it" } ?: "")
}

/** Attached explicitly to one request; ordinary requests keep the original no-op listener. */
class HttpConnectionTrace(private val clock: () -> Long = System::nanoTime) : EventListener() {
    private val started = clock()
    private var phaseStarted = started
    private var phase = HttpConnectionPhase.PREPARING
    private var observed = false
    private var routeType: String? = null
    private var protocol: String? = null
    private var selectedProxyPort: Int? = null
    private val completed = ArrayList<Pair<HttpConnectionPhase, Long>>()

    @Synchronized private fun enter(next: HttpConnectionPhase) {
        observed = true
        if (next == phase) return
        val now = clock()
        if (completed.size < 32) completed += phase to ((now - phaseStarted).coerceAtLeast(0) / 1_000_000)
        phase = next; phaseStarted = now
    }
    @Synchronized fun snapshot(): HttpConnectionTraceSnapshot? {
        if (!observed) return null
        val now = clock()
        return HttpConnectionTraceSnapshot(phase, (now - started).coerceAtLeast(0) / 1_000_000,
            (now - phaseStarted).coerceAtLeast(0) / 1_000_000, routeType, protocol, completed.toList(), selectedProxyPort)
    }
    override fun callStart(call: Call) = enter(HttpConnectionPhase.PREPARING)
    override fun proxySelectStart(call: Call, url: HttpUrl) = enter(HttpConnectionPhase.PROXY_SELECTION)
    override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) = enter(HttpConnectionPhase.ROUTE_READY)
    override fun dnsStart(call: Call, domainName: String) = enter(HttpConnectionPhase.DNS)
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = enter(HttpConnectionPhase.ROUTE_READY)
    @Synchronized override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        routeType = proxy.type().name
        selectedProxyPort = (proxy.address() as? InetSocketAddress)?.port
        enter(if (proxy.type() == Proxy.Type.DIRECT) HttpConnectionPhase.CONNECTING else HttpConnectionPhase.PROXY_CONNECTING)
    }
    override fun secureConnectStart(call: Call) = enter(HttpConnectionPhase.TLS)
    override fun secureConnectEnd(call: Call, handshake: Handshake?) = enter(HttpConnectionPhase.TLS_READY)
    @Synchronized override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        this.protocol = protocol?.toString(); enter(HttpConnectionPhase.CONNECTED)
    }
    @Synchronized override fun connectionAcquired(call: Call, connection: Connection) {
        protocol = connection.protocol().toString(); routeType = connection.route().proxy.type().name
        selectedProxyPort = (connection.route().proxy.address() as? InetSocketAddress)?.port
        enter(HttpConnectionPhase.CONNECTED)
    }
    override fun requestHeadersStart(call: Call) = enter(HttpConnectionPhase.SENDING)
    override fun requestHeadersEnd(call: Call, request: Request) = enter(HttpConnectionPhase.WAITING_HEADERS)
    override fun responseHeadersStart(call: Call) = enter(HttpConnectionPhase.HEADERS)
    override fun responseHeadersEnd(call: Call, response: Response) = enter(HttpConnectionPhase.BODY)
    override fun responseBodyStart(call: Call) = enter(HttpConnectionPhase.BODY)
    override fun callEnd(call: Call) = enter(HttpConnectionPhase.COMPLETE)
}
