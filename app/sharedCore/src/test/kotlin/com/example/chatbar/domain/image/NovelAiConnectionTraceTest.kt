package com.example.chatbar.domain.image

import com.example.chatbar.domain.ProxyAwareClient
import com.example.chatbar.domain.network.HttpConnectionPhase
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class NovelAiConnectionTraceTest {
    @Test fun `account timeout distinguishes proxy negotiation from TLS after successful CONNECT without exposing request data`() = runBlocking {
        for (openTunnel in listOf(false, true)) {
            Tunnel(openTunnel).use { proxy ->
                val client = ProxyAwareClient.builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)))
                    .connectTimeout(2, TimeUnit.SECONDS).readTimeout(800, TimeUnit.MILLISECONDS)
                    .callTimeout(4, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
                try {
                    val failure = assertFailsWith<NovelAiAccountException> { NovelAiAccountService(client).fetchCancellable("synthetic-not-a-real-account") }
                    assertEquals(NovelAiAccountFailureKind.TIMEOUT, failure.kind)
                    val trace = assertNotNull(failure.connectionTrace)
                    assertEquals(if (openTunnel) HttpConnectionPhase.TLS else HttpConnectionPhase.PROXY_CONNECTING, trace.phase)
                    assertEquals("HTTP", trace.routeType); assertNull(trace.protocol)
                    assertEquals(proxy.port, trace.selectedProxyPort)
                    assertTrue(trace.elapsedMillis >= trace.phaseMillis); assertTrue(trace.phaseMillis > 0)
                    assertTrue(trace.completedPhases.size <= 32)
                    assertNull(failure.cause); assertTrue(failure.suppressed.isEmpty())
                    assertFalse(failure.toString().contains("synthetic-not-a-real-account"))
                    assertFalse(failure.toString().contains("image.novelai.net"))
                    assertTrue(proxy.headers.get().startsWith("CONNECT image.novelai.net:443 HTTP/1.1"))
                    assertFalse(proxy.headers.get().contains("Authorization", ignoreCase = true))
                    if (openTunnel) assertEquals(0x16, proxy.firstTlsByte.get())
                } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
            }
        }
    }

    /** A loopback proxy that never connects to any remote host. */
    private class Tunnel(private val openTunnel: Boolean) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        val headers = AtomicReference("")
        val firstTlsByte = AtomicReference<Int>()
        private val socket = AtomicReference<Socket?>()
        private val release = CountDownLatch(1)
        private val worker = thread(name = "synthetic-account-tunnel", isDaemon = true) {
            runCatching { server.accept().use { connection ->
                socket.set(connection); connection.soTimeout = 5000
                val input = connection.getInputStream(); val bytes = ArrayList<Byte>()
                while (bytes.size < 16_384) {
                    val next = input.read(); if (next < 0) break
                    bytes += next.toByte()
                    if (bytes.takeLast(4) == listOf(13.toByte(), 10.toByte(), 13.toByte(), 10.toByte())) break
                }
                headers.set(bytes.toByteArray().toString(Charsets.ISO_8859_1))
                if (openTunnel) {
                    connection.getOutputStream().apply { write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); flush() }
                    firstTlsByte.set(input.read())
                }
                release.await(6, TimeUnit.SECONDS)
            } }
        }
        override fun close() { release.countDown(); socket.get()?.close(); server.close(); worker.join(7000); check(!worker.isAlive) }
    }
}
