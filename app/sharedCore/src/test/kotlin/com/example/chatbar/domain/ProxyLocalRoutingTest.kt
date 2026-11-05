package com.example.chatbar.domain

import java.net.InetAddress
import java.net.Proxy
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.*

class ProxyLocalRoutingTest {
    @Test fun `application override survives JVM proxy priority and always bypasses local models`() = withSettings {
        val previous = ProxyAwareClient.applicationProxy
        val client = ProxyAwareClient.builder().build()
        try {
            configureProxy(true, 19090)
            for (mode in listOf(ApplicationProxyMode.HTTP, ApplicationProxyMode.SOCKS, ApplicationProxyMode.DIRECT)) {
                ProxyAwareClient.applicationProxy = ApplicationProxySettings(mode, "127.0.0.1", 19095)
                val actual = client.proxySelector.select(URI("https://external.invalid/")).single()
                assertEquals(when (mode) {
                    ApplicationProxyMode.HTTP -> Proxy.Type.HTTP
                    ApplicationProxyMode.SOCKS -> Proxy.Type.SOCKS
                    else -> Proxy.Type.DIRECT
                }, actual.type())
                if (mode != ApplicationProxyMode.DIRECT) assertEquals(19095, (actual.address() as InetSocketAddress).port)
                assertEquals(Proxy.NO_PROXY, client.proxySelector.select(URI("http://127.0.0.1:8200/v1/models")).single())
            }
        } finally { ProxyAwareClient.applicationProxy = previous; close(client) }
    }

    @Test fun `HTTPS JVM settings take precedence only for HTTPS and failing system selector stays visible`() = withSettings {
        val originalHost = System.getProperty("https.proxyHost"); val originalPort = System.getProperty("https.proxyPort")
        val selector = ProxySelector.getDefault(); val client = ProxyAwareClient.builder().build()
        try {
            configureProxy(false, 19092)
            System.setProperty("https.proxyHost", "127.0.0.1"); System.setProperty("https.proxyPort", "19096")
            assertEquals(19096, (client.proxySelector.select(URI("https://external.invalid/")).single().address() as InetSocketAddress).port)
            assertEquals(19092, (client.proxySelector.select(URI("http://external.invalid/")).single().address() as InetSocketAddress).port)
            System.clearProperty("http.proxyHost"); System.clearProperty("https.proxyHost")
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> = error("synthetic selector failure")
                override fun connectFailed(uri: URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) = Unit
            })
            assertFailsWith<IllegalStateException> { client.proxySelector.select(URI("https://external.invalid/")) }
        } finally {
            ProxySelector.setDefault(selector)
            if (originalHost == null) System.clearProperty("https.proxyHost") else System.setProperty("https.proxyHost", originalHost)
            if (originalPort == null) System.clearProperty("https.proxyPort") else System.setProperty("https.proxyPort", originalPort)
            close(client)
        }
    }
    @Test fun `custom bypass patterns match whole hosts for HTTP and HTTPS without resolving names`() = withSettings {
        configureProxy(true, 19090)
        System.setProperty("http.nonProxyHosts", " *.internal.invalid | EXACT.INVALID |[::1]")
        val client = ProxyAwareClient.modelApiBuilder { true }.build()
        try {
            for (scheme in listOf("http", "https")) {
                for (host in listOf("api.internal.invalid", "EXACT.INVALID", "exact.invalid."))
                    assertEquals(Proxy.NO_PROXY, client.proxySelector.select(URI("$scheme://$host/")).single())
                for (host in listOf("internal.invalid.evil", "exact.invalid.evil", "notexact.invalid", "192.168.1.1"))
                    assertEquals(Proxy.Type.HTTP, client.proxySelector.select(URI("$scheme://$host/")).single().type())
            }
            System.setProperty("http.nonProxyHosts", "api.example.invalid")
            assertEquals(Proxy.Type.HTTP, client.proxySelector.select(URI("http://apiXexampleXinvalid/")).single().type())
        } finally { close(client) }
    }

    @Test fun `loopback bypass cannot be disabled and similar external hosts still use proxy`() = withSettings {
        configureProxy(true, 19090)
        System.setProperty("http.nonProxyHosts", "")
        val client = ProxyAwareClient.builder().build()
        try {
            for (host in listOf("LOCALHOST.", "127.255.255.254", "[::1]", "[::ffff:127.0.0.1]"))
                assertEquals(Proxy.NO_PROXY, client.proxySelector.select(URI("https://$host/")).single(), host)
            for (host in listOf("localhost.evil.invalid", "127.0.0.1.evil.invalid", "128.0.0.1", "[::2]"))
                assertEquals(Proxy.Type.HTTP, client.proxySelector.select(URI("https://$host/")).single().type(), host)
        } finally { close(client) }
    }

    @Test fun `external proxy precedence and default delegation remain unchanged`() = withSettings {
        val external = URI("https://external.invalid/")
        val client = ProxyAwareClient.builder().build()
        try {
            assertEquals(ProxySelector.getDefault()?.select(external) ?: listOf(Proxy.NO_PROXY), client.proxySelector.select(external))
            System.setProperty("socksProxyHost", "127.0.0.1"); System.setProperty("socksProxyPort", "19091")
            assertEquals(Proxy.Type.SOCKS, client.proxySelector.select(external).single().type())
            assertEquals(Proxy.NO_PROXY, client.proxySelector.select(URI("http://[::1]/")).single())
            System.setProperty("http.proxyHost", "127.0.0.1"); System.setProperty("http.proxyPort", "19092")
            assertEquals(InetSocketAddress("127.0.0.1", 19092), client.proxySelector.select(external).single().address())
            ProxyAwareClient.manualProxyHost = "127.0.0.1"; ProxyAwareClient.manualProxyPort = 19093
            assertEquals(InetSocketAddress("127.0.0.1", 19093), client.proxySelector.select(external).single().address())
            ProxyAwareClient.manualProxyPort = -1
            assertEquals(InetSocketAddress("127.0.0.1", 19092), client.proxySelector.select(external).single().address())
        } finally { close(client) }
    }

    @Test fun `real IPv6 HTTP loopback bypasses configured proxy`() = withSettings {
        MockWebServer().use { origin -> MockWebServer().use { proxy ->
            origin.dispatcher = reply("ipv6-origin"); proxy.dispatcher = reply("unexpected-proxy")
            origin.start(InetAddress.getByName("::1"), 0)
            proxy.start(InetAddress.getByName("127.0.0.1"), 0)
            for (manual in listOf(true, false)) {
                configureProxy(manual, proxy.port)
                val client = ProxyAwareClient.modelApiBuilder { true }.callTimeout(5, TimeUnit.SECONDS).build()
                try { assertEquals("ipv6-origin", get(client, "http://[::1]:${origin.port}/v1/models")) }
                finally { close(client) }
            }
            assertEquals(2, origin.requestCount); assertEquals(0, proxy.requestCount)
        } }
    }

    @Test fun `actual selector bypasses IPv4 IPv6 and localhost while retaining external proxy`() = withSettings {
        for (manual in listOf(true, false)) {
            configureProxy(manual, 19090)
            System.setProperty("http.nonProxyHosts", "localhost|127.*|[::1]")
            val client = ProxyAwareClient.modelApiBuilder { true }.build()
            try {
                for (address in listOf("127.0.0.1", "localhost", "[::1]", "[0:0:0:0:0:0:0:1]", "127.0.0.2"))
                    assertEquals(listOf(Proxy.NO_PROXY), client.proxySelector.select(URI("http://$address:8200/v1/models")), "$manual $address")
                assertEquals(Proxy.Type.HTTP, client.proxySelector.select(URI("https://external.invalid/v1/models")).single().type())
            } finally { close(client) }
        }
    }

    @Test fun `real HTTP local origin and external proxy remain independent in parallel`() = withSettings {
        for (manual in listOf(true, false)) {
            MockWebServer().use { origin -> MockWebServer().use { proxy ->
                origin.dispatcher = reply("direct-origin")
                proxy.dispatcher = reply("external-proxy")
                origin.start(InetAddress.getByName("127.0.0.1"), 0); proxy.start(InetAddress.getByName("127.0.0.1"), 0)
                configureProxy(manual, proxy.port)
                System.setProperty("http.nonProxyHosts", "localhost|127.*|[::1]")
                val client = ProxyAwareClient.modelApiBuilder { true }
                    .dns(object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> =
                            if (hostname.endsWith(".invalid")) listOf(InetAddress.getByName("127.0.0.1"))
                            else Dns.SYSTEM.lookup(hostname)
                    })
                    .callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
                val executor = Executors.newFixedThreadPool(3)
                try {
                    val direct = executor.submit<String> { get(client, "http://127.0.0.1:${origin.port}/v1/models") }
                    val named = executor.submit<String> { get(client, "http://localhost:${origin.port}/v1/models") }
                    val external = executor.submit<String> { get(client, "http://external.invalid:${origin.port}/v1/models") }
                    assertEquals("direct-origin", direct.get(8, TimeUnit.SECONDS))
                    assertEquals("direct-origin", named.get(8, TimeUnit.SECONDS))
                    assertEquals("external-proxy", external.get(8, TimeUnit.SECONDS))
                    assertEquals(2, origin.requestCount); assertEquals(1, proxy.requestCount)
                    assertEquals("/v1/models", origin.takeRequest(1, TimeUnit.SECONDS)!!.path)
                    assertTrue(proxy.takeRequest(1, TimeUnit.SECONDS)!!.requestLine.startsWith("GET http://external.invalid:"))
                } finally { executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS); close(client) }
            } }
        }
    }

    private fun get(client: OkHttpClient, url: String): String = client.newCall(Request.Builder().url(url)
        .addModelApiAuthorization("").build()).execute().use { check(it.isSuccessful); it.body!!.string() }
    private fun reply(body: String) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            assertNull(request.getHeader("Authorization"))
            return MockResponse().setBody(body)
        }
    }
    private fun configureProxy(manual: Boolean, port: Int) {
        ProxyAwareClient.manualProxyHost = if (manual) "127.0.0.1" else null
        ProxyAwareClient.manualProxyPort = if (manual) port else null
        if (manual) { System.clearProperty("http.proxyHost"); System.clearProperty("http.proxyPort") }
        else { System.setProperty("http.proxyHost", "127.0.0.1"); System.setProperty("http.proxyPort", port.toString()) }
    }
    private fun close(client: OkHttpClient) { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    private fun withSettings(body: () -> Unit) = synchronized(ProxyAwareClient) {
        val properties = listOf("http.proxyHost", "http.proxyPort", "http.nonProxyHosts", "socksProxyHost", "socksProxyPort")
        val original = properties.associateWith(System::getProperty)
        val manualHost = ProxyAwareClient.manualProxyHost; val manualPort = ProxyAwareClient.manualProxyPort
        try { properties.forEach(System::clearProperty); body() }
        finally {
            original.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
            ProxyAwareClient.manualProxyHost = manualHost; ProxyAwareClient.manualProxyPort = manualPort
        }
    }
}
