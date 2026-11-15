package com.example.chatbar.domain.syncStore.webdav

import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.*

class WebDavHttpTransportOwnershipTest {
    @Test fun closingOneRouteCancelsItsBlockedCallWithoutCancellingAnotherRouteOrChangingTheSharedClient() {
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(30, TimeUnit.SECONDS).build()
        val pool = Executors.newSingleThreadExecutor()
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val base = server.url("/sync/").newBuilder().host("127.0.0.1").build()
            val owner = WebDavHttpTransport(base, client, policy = WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
            val neighbor = WebDavHttpTransport(base, client, policy = WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
            try {
                val pending = pool.submit<Throwable?> {
                    try { owner.execute("GET", "blocked.bin").use { }; null } catch (failure: Throwable) { failure }
                }
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                owner.close()
                assertIs<InterruptedIOException>(pending.get(5, TimeUnit.SECONDS))
                server.enqueue(MockResponse().setBody("synthetic neighbor bytes"))
                neighbor.execute("GET", "neighbor.bin").use { assertEquals("synthetic neighbor bytes", it.body!!.string()) }
                val before = server.requestCount
                assertFailsWith<InterruptedIOException> { owner.execute("GET", "late.bin") }
                assertEquals(before, server.requestCount)
            } finally {
                owner.close(); neighbor.close()
                pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
                client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
            }
        }
    }

    @Test fun aResponseBodyStillReadingIsOwnedUntilCloseAndCanBeCancelled() {
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(30, TimeUnit.SECONDS).build()
        val pool = Executors.newSingleThreadExecutor()
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setBody("x".repeat(4096)).throttleBody(1, 200, TimeUnit.MILLISECONDS))
            val transport = WebDavHttpTransport(server.url("/sync/").newBuilder().host("127.0.0.1").build(), client,
                policy = WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
            try {
                val response = transport.execute("GET", "stream.bin")
                response.use {
                    val input = transport.input(it.body!!)
                    assertEquals('x'.code, input.read())
                    val pending = pool.submit<Throwable?> {
                        try { while (input.read() >= 0) { }; null } catch (failure: Throwable) { failure }
                    }
                    transport.close()
                    assertNotNull(pending.get(5, TimeUnit.SECONDS))
                }
            } finally {
                transport.close(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
                client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
            }
        }
    }
}
