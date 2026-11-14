package com.example.chatbar.domain.syncStore.webdav

import com.example.chatbar.domain.ProxyAwareClient
import com.example.chatbar.domain.syncStore.CiphertextStoreException
import com.example.chatbar.domain.syncStore.CiphertextStoreFailure
import java.io.IOException
import java.io.InterruptedIOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.BufferedSink
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import kotlin.test.*

/** Real loopback sockets and normal TLS hostname verification; no account or provider endpoint. */
class WebDavHttpTransportDualStackTest {
    private class Events : EventListener() {
        val calls = AtomicInteger()
        val connects = CopyOnWriteArrayList<InetSocketAddress>()
        val failures = CopyOnWriteArrayList<InetSocketAddress>()
        val dns = AtomicInteger()
        val headers = AtomicInteger()
        val responses = AtomicInteger()
        val bodies = AtomicInteger()
        val ends = AtomicInteger()
        val tls = CountDownLatch(1)
        override fun callStart(call: Call) { calls.incrementAndGet() }
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) { dns.incrementAndGet() }
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { connects += inetSocketAddress }
        override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) { failures += inetSocketAddress }
        override fun secureConnectStart(call: Call) { tls.countDown() }
        override fun requestHeadersStart(call: Call) { headers.incrementAndGet() }
        override fun responseHeadersEnd(call: Call, response: okhttp3.Response) { responses.incrementAndGet() }
        override fun responseBodyEnd(call: Call, byteCount: Long) { bodies.incrementAndGet() }
        override fun callEnd(call: Call) { ends.incrementAndGet() }
    }

    private class TlsFixture(certificateHost: String = HOST) : AutoCloseable {
        val server = MockWebServer()
        private val trust: X509TrustManager
        private val clientContext: SSLContext
        val ipv4: InetAddress = InetAddress.getByName("127.0.0.1")
        val ipv6: InetAddress = InetAddress.getByName("::1")
        val base: HttpUrl
        private val clients = mutableListOf<OkHttpClient>()
        init {
            val provider = BouncyCastleProvider()
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val name = X500Name("CN=$certificateHost")
            val now = System.currentTimeMillis()
            val certificate = JcaX509CertificateConverter().setProvider(provider).getCertificate(
                JcaX509v3CertificateBuilder(name, BigInteger(160, SecureRandom()).abs(), Date(now - 60_000),
                    Date(now + TimeUnit.DAYS.toMillis(1)), name, pair.public)
                    .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
                    .addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
                    .addExtension(Extension.subjectAlternativeName, false,
                        GeneralNames(GeneralName(GeneralName.dNSName, certificateHost)))
                    .build(JcaContentSignerBuilder("SHA256withECDSA").setProvider(provider).build(pair.private)))
            val password = "synthetic-tls-only".toCharArray()
            val keys = KeyStore.getInstance("PKCS12").apply {
                load(null, password); setKeyEntry("server", pair.private, password, arrayOf(certificate))
            }
            val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
            val serverContext = SSLContext.getInstance("TLS").apply { init(km.keyManagers, null, SecureRandom()) }
            val roots = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("server", certificate) }
            val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(roots) }
            trust = tm.trustManagers.filterIsInstance<X509TrustManager>().single()
            clientContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            password.fill('\u0000')
            server.protocols = listOf(Protocol.HTTP_1_1)
            server.useHttps(serverContext.socketFactory, false)
            server.start(ipv6, 0)
            base = server.url("/sync/").newBuilder().host(HOST).build()
        }
        fun client(events: Events, addresses: List<InetAddress> = listOf(ipv4, ipv6), trusted: Boolean = true,
            configure: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY).protocols(listOf(Protocol.HTTP_1_1))
            .dns(syntheticDns { hostname -> assertEquals(HOST, hostname); addresses })
            .eventListenerFactory { events }
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
            .apply { if (trusted) sslSocketFactory(clientContext.socketFactory, trust); configure() }
            .build().also { clients += it }
        override fun close() {
            clients.forEach { it.dispatcher.executorService.shutdown(); it.connectionPool.evictAll() }
            server.close()
        }
    }

    private class CountedBody(private val bytes: ByteArray) : RequestBody() {
        val writes = AtomicInteger()
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = bytes.size.toLong()
        override fun writeTo(sink: BufferedSink) { writes.incrementAndGet(); sink.write(bytes) }
    }

    @Test fun aFailedIpv4TcpDialFallsThroughToActualIpv6TlsBeforeSendingExactlyOnePut() {
        TlsFixture().use { f ->
            f.server.enqueue(MockResponse().setResponseCode(201).setBody("stored"))
            val events = Events()
            val lookups = AtomicInteger()
            val client = f.client(events).newBuilder().dns(syntheticDns { host ->
                assertEquals(HOST, host); lookups.incrementAndGet(); listOf(f.ipv4, f.ipv6)
            }).build()
            val body = CountedBody(ByteArray(1027) { (it * 17).toByte() })
            WebDavHttpTransport(f.base, client).use { transport ->
                transport.execute("PUT", NONCE_PATH, body).use { assertEquals(201, it.code); assertEquals("stored", it.body!!.string()) }
            }
            assertEquals(1, lookups.get())
            assertEquals(listOf(f.ipv4, f.ipv6), events.connects.map { it.address })
            assertEquals(listOf(f.ipv4), events.failures.map { it.address })
            assertEquals(2, events.calls.get()); assertEquals(1, events.headers.get())
            assertEquals(1, events.responses.get()); assertEquals(1, events.bodies.get()); assertEquals(1, events.ends.get())
            assertEquals(1, body.writes.get()); assertEquals(1, f.server.requestCount)
            val request = assertNotNull(f.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("PUT", request.method); assertEquals("/sync/$NONCE_PATH", request.path)
            assertEquals("$HOST:${f.base.port}", request.getHeader("Host"))
            assertEquals(1027L, request.bodySize)
            assertTrue(client.retryOnConnectionFailure, "The supplied shared client's retry policy stays unchanged")
        }
    }

    @Test fun anIpv6OnlyDnsAnswerUsesNormalTlsWithoutAnIpv4Requirement() {
        TlsFixture().use { f ->
            val events = Events(); f.server.enqueue(MockResponse().setBody("aaaa"))
            WebDavHttpTransport(f.base, f.client(events, listOf(f.ipv6))).use { transport ->
                transport.execute("GET", "only.bin").use { assertEquals("aaaa", it.body!!.string()) }
            }
            assertEquals(listOf(f.ipv6), events.connects.map { it.address }); assertEquals(1, events.calls.get())
        }
    }

    @Test fun separateOperationsRetainTheExistingTlsConnectionPoolWithoutSharingTheirWireFence() {
        TlsFixture().use { f ->
            val events = Events()
            f.server.enqueue(MockResponse().setBody("first")); f.server.enqueue(MockResponse().setBody("second"))
            WebDavHttpTransport(f.base, f.client(events)).use { transport ->
                transport.execute("GET", "first.bin").use { assertEquals("first", it.body!!.string()) }
                transport.execute("GET", "second.bin").use { assertEquals("second", it.body!!.string()) }
            }
            assertEquals(listOf(f.ipv4, f.ipv6), events.connects.map { it.address })
            assertEquals(3, events.calls.get()); assertEquals(2, events.headers.get())
            assertEquals(listOf(0, 1), List(2) { assertNotNull(f.server.takeRequest(2, TimeUnit.SECONDS)).sequenceNumber })
        }
    }

    @Test fun aLostPutReplyAfterFallbackNeverResendsTheSamePhysicalNonce() {
        TlsFixture().use { f ->
            val events = Events(); val body = CountedBody("synthetic ciphertext".toByteArray())
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            WebDavHttpTransport(f.base, f.client(events)).use { transport ->
                assertEquals(CiphertextStoreFailure.TRANSIENT,
                    assertFailsWith<CiphertextStoreException> { transport.execute("PUT", NONCE_PATH, body) }.failure)
            }
            assertEquals(2, events.calls.get()); assertEquals(1, events.headers.get())
            assertEquals(1, body.writes.get()); assertEquals(1, f.server.requestCount)
            assertEquals("/sync/$NONCE_PATH", assertNotNull(f.server.takeRequest(2, TimeUnit.SECONDS)).path)
        }
    }

    @Test fun retryAfterZeroDoesNotRepeatAPutOrABodylessGetExchange() {
        TlsFixture().use { f ->
            val events = Events(); val body = CountedBody("synthetic ciphertext".toByteArray())
            f.server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            f.server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            WebDavHttpTransport(f.base, f.client(events, listOf(f.ipv6))).use { transport ->
                transport.execute("PUT", NONCE_PATH, body).use { assertEquals(503, it.code) }
                assertEquals(1, f.server.requestCount)
                assertEquals(CiphertextStoreFailure.TRANSIENT,
                    assertFailsWith<CiphertextStoreException> { transport.execute("GET", "busy.bin") }.failure)
            }
            assertEquals(2, f.server.requestCount); assertEquals(2, events.headers.get()); assertEquals(1, body.writes.get())
            assertEquals(listOf("PUT", "GET"), List(2) { assertNotNull(f.server.takeRequest(2, TimeUnit.SECONDS)).method })
        }
    }

    @Test fun closingDuringTheFirstTlsHandshakeCancelsOwnershipAndNeverIssuesTheNextAddressCall() {
        TlsFixture().use { f ->
            ServerSocket(f.base.port, 1, f.ipv4).use { stalled ->
                val pool = Executors.newFixedThreadPool(2)
                val events = Events(); val transport = WebDavHttpTransport(f.base, f.client(events))
                val accepted = pool.submit<java.net.Socket> { stalled.accept() }
                var socket: java.net.Socket? = null
                try {
                    val pending = pool.submit<Throwable?> {
                        try { transport.execute("GET", "cancel.bin").use { }; null } catch (failure: Throwable) { failure }
                    }
                    socket = accepted.get(5, TimeUnit.SECONDS)
                    assertTrue(events.tls.await(5, TimeUnit.SECONDS))
                    transport.close()
                    assertIs<InterruptedIOException>(pending.get(5, TimeUnit.SECONDS))
                    assertEquals(1, events.calls.get()); assertEquals(listOf(f.ipv4), events.connects.map { it.address })
                    assertEquals(0, events.headers.get()); assertEquals(0, f.server.requestCount)
                    assertFailsWith<InterruptedIOException> { transport.execute("GET", "late.bin") }
                    assertEquals(1, events.calls.get())
                } finally {
                    transport.close(); socket?.close(); stalled.close()
                    pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test fun hostnameAndTrustFailuresCannotSelectAnotherAddressOrSendAnHttpRequest() {
        for (wrongHostname in listOf(true, false)) TlsFixture(if (wrongHostname) "different.synthetic.test" else HOST).use { f ->
            val events = Events()
            WebDavHttpTransport(f.base, f.client(events, listOf(f.ipv6, f.ipv4), trusted = wrongHostname)).use { transport ->
                assertEquals(CiphertextStoreFailure.PERMISSION,
                    assertFailsWith<CiphertextStoreException> { transport.execute("GET", "refused.bin") }.failure)
            }
            assertEquals(1, events.calls.get()); assertEquals(listOf(f.ipv6), events.connects.map { it.address })
            assertEquals(0, events.headers.get()); assertEquals(0, f.server.requestCount)
        }
    }

    @Test fun injectedRedirectAndAuthenticatorPoliciesStayDisabledWithoutAnotherWireRequest() {
        TlsFixture().use { f ->
            val auth = AtomicInteger(); val proxyAuth = AtomicInteger(); val events = Events()
            val client = f.client(events, listOf(f.ipv6)) {
                followRedirects(true); followSslRedirects(true)
                authenticator { _, response -> auth.incrementAndGet(); response.request.newBuilder().header("Authorization", "synthetic").build() }
                proxyAuthenticator { _, response -> proxyAuth.incrementAndGet(); response.request }
            }
            f.server.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=synthetic"))
            f.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", f.base.resolve("redirect.bin")!!))
            WebDavHttpTransport(f.base, client).use { transport ->
                transport.execute("GET", "auth.bin").use { assertEquals(401, it.code) }
                transport.execute("GET", "redirect-source.bin").use { assertEquals(302, it.code) }
            }
            assertEquals(0, auth.get()); assertEquals(0, proxyAuth.get()); assertEquals(2, f.server.requestCount)
            assertEquals(listOf("/sync/auth.bin", "/sync/redirect-source.bin"),
                List(2) { assertNotNull(f.server.takeRequest(2, TimeUnit.SECONDS)).path })
        }
    }

    @Test fun anExplicitHttpProxyRefusalNeverFallsBackToTheDirectOrigin() {
        TlsFixture().use { f ->
            MockWebServer().use { proxy ->
                proxy.start(f.ipv4, 0)
                proxy.enqueue(MockResponse().setResponseCode(403).setHeader("Connection", "close"))
                val events = Events(); val proxyAuth = AtomicInteger()
                val client = f.client(events).newBuilder()
                    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(f.ipv4, proxy.port)))
                    .proxyAuthenticator { _, response -> proxyAuth.incrementAndGet(); response.request }
                    .build()
                WebDavHttpTransport(f.base, client).use { transport ->
                    assertEquals(CiphertextStoreFailure.TRANSIENT,
                        assertFailsWith<CiphertextStoreException> { transport.execute("GET", "through-proxy.bin") }.failure)
                }
                val request = assertNotNull(proxy.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("CONNECT", request.method)
                assertEquals("CONNECT $HOST:${f.base.port} HTTP/1.1", request.requestLine)
                assertEquals(1, events.calls.get()); assertEquals(1, proxy.requestCount)
                assertEquals(0, proxyAuth.get()); assertEquals(0, f.server.requestCount)
                assertEquals(listOf(proxy.port), events.connects.map { it.port })
            }
        }
    }

    @Test fun cloudDnsIsSeparateFromTheExistingApplicationDnsAndRetainsItsClientPolicies() {
        val app = ProxyAwareClient.builder().build()
        val cloud = WebDavCloudClient.create()
        try {
            assertSame(Dns.SYSTEM, cloud.dns); assertNotSame(Dns.SYSTEM, app.dns)
            assertEquals(app.proxySelector.javaClass, cloud.proxySelector.javaClass)
            assertSame(app.socketFactory, cloud.socketFactory)
            assertSame(app.hostnameVerifier, cloud.hostnameVerifier)
            assertEquals(app.certificatePinner, cloud.certificatePinner)
            assertEquals(app.interceptors.size, cloud.interceptors.size)
        } finally {
            listOf(app, cloud).forEach { it.dispatcher.executorService.shutdown(); it.connectionPool.evictAll() }
        }
    }

    private companion object {
        const val HOST = "cloud.synthetic.test"
        const val NONCE_PATH = "attempt-0123456789abcdef0123456789abcdef.bin"
        fun syntheticDns(resolve: (String) -> List<InetAddress>): Dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = resolve(hostname)
        }
    }
}
