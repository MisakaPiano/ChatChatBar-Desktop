package com.example.chatbar.desktop

import com.example.chatbar.domain.ApplicationProxySettings
import com.example.chatbar.domain.ProxyAwareClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient

/** Public update traffic only. Each new check/helper call captures the currently saved policy. */
internal object DesktopUpdateNetworkRouting {
    val signedHosts = listOf("raw.githubusercontent.com", "github.com",
        "release-assets.githubusercontent.com", "objects.githubusercontent.com")

    /** Quote-free argv survives Windows ProcessBuilder/CRT without changing the route document. */
    fun signedRoutesArgument(settings: ApplicationProxySettings): String {
        val bytes = signedRoutes(settings).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 8192) { "UPDATE_PROXY_ROUTES_LIMIT" }
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun client(settings: () -> ApplicationProxySettings, allowLoopbackHttp: Boolean = false): OkHttpClient =
        ProxyAwareClient.modelApiBuilder({ allowLoopbackHttp }, settings)
            .socketFactory(DesktopSelectedRouteSocketFactory)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(20, TimeUnit.SECONDS).build()

    /** Resolve each permitted HTTPS destination separately; never flatten SYSTEM routes to HTTP. */
    fun signedRoutes(settings: ApplicationProxySettings): String {
        check(ProxyAwareClient.applicationRoutingAvailable) { "UPDATE_PROXY_SETTINGS_UNAVAILABLE" }
        val client = client({ settings })
        return try { buildJsonObject {
            signedHosts.forEach { host ->
                val route = requireNotNull(client.proxySelector.select(URI("https://$host/")).firstOrNull()) {
                    "UPDATE_PROXY_ROUTE_UNAVAILABLE"
                }
                putJsonObject(host) {
                    put("mode", route.type().name)
                    if (route.type() != Proxy.Type.DIRECT) {
                        val address = route.address() as? InetSocketAddress ?: error("UPDATE_PROXY_ADDRESS_INVALID")
                        val proxyHost = address.hostString.let { if (':' in it) it else java.net.IDN.toASCII(it) }
                        require(address.port in 1..65535 && proxyHost.length in 1..253)
                        put("host", proxyHost); put("port", address.port)
                    }
                }
            }
        }.toString() } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
}
