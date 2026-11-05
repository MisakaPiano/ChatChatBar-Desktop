package com.example.chatbar.domain.image

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import kotlin.test.*

class NovelAiAccountFailureTest {
    @Test fun `HTTP failures retain status and category in both entry points without response details`() = runBlocking {
        for ((status, kind) in listOf(401 to NovelAiAccountFailureKind.AUTHENTICATION,
            403 to NovelAiAccountFailureKind.AUTHENTICATION, 429 to NovelAiAccountFailureKind.RATE_LIMIT,
            503 to NovelAiAccountFailureKind.SERVER, 404 to NovelAiAccountFailureKind.HTTP)) {
            withService(status = status) { service, calls ->
                val sync = assertFailsWith<NovelAiAccountException> { service.fetch("fake-account-only") }
                val async = assertFailsWith<NovelAiAccountException> { service.fetchCancellable("fake-account-only") }
                for (failure in listOf(sync, async)) {
                    assertEquals(kind, failure.kind); assertEquals(status, failure.httpStatus)
                    assertTrue(failure.message.orEmpty().contains("HTTP $status"))
                    assertSanitized(failure)
                }
                assertEquals(2, calls.get())
            }
        }
    }

    @Test fun `DNS timeout TLS connection and network remain distinct without raw cause messages`() = runBlocking {
        for ((factory, kind) in listOf(
            ({ UnknownHostException(PRIVATE_DETAIL) }) to NovelAiAccountFailureKind.DNS,
            ({ SocketTimeoutException(PRIVATE_DETAIL) }) to NovelAiAccountFailureKind.TIMEOUT,
            ({ SSLHandshakeException(PRIVATE_DETAIL) }) to NovelAiAccountFailureKind.TLS,
            ({ ConnectException(PRIVATE_DETAIL) }) to NovelAiAccountFailureKind.CONNECTION,
            ({ IOException(PRIVATE_DETAIL) }) to NovelAiAccountFailureKind.NETWORK,
        )) {
            withService(failure = factory) { service, calls ->
                for (failure in listOf(assertFailsWith<NovelAiAccountException> { service.fetch("fake-account-only") },
                    assertFailsWith<NovelAiAccountException> { service.fetchCancellable("fake-account-only") })) {
                    assertEquals(kind, failure.kind); assertNull(failure.httpStatus); assertSanitized(failure)
                }
                assertEquals(2, calls.get())
            }
        }
    }

    @Test fun `malformed successful response is not an authentication or network failure`() = runBlocking {
        withService(status = 200) { service, calls ->
            val failure = assertFailsWith<NovelAiAccountException> { service.fetchCancellable("fake-account-only") }
            assertEquals(NovelAiAccountFailureKind.INVALID_RESPONSE, failure.kind)
            assertNull(failure.httpStatus); assertSanitized(failure); assertEquals(1, calls.get())
        }
    }

    @Test fun `successful sync and cancellable reads retain account usage`() = runBlocking {
        withService(body = """{"trainingStepsLeft":{"fixedTrainingStepsLeft":100,"purchasedTrainingSteps":23},"tier":3,"active":true,"usage":{"percent":25,"isNegative":false}}""") { service, calls ->
            val expected = NovelAiAccountUsage(123, 3, true, 25.0, false)
            assertEquals(expected, service.fetch("fake-account-only"))
            assertEquals(expected, service.fetchCancellable("fake-account-only"))
            assertEquals(2, calls.get())
        }
    }

    @Test fun `response cleanup failure cannot attach private suppressed details to HTTP classification`() = runBlocking {
        withService(status = 401, bodyFactory = { object : ResponseBody() {
            private val bytes = object : ForwardingSource(Buffer().writeUtf8(PRIVATE_DETAIL)) {
                override fun close() { throw IllegalStateException(PRIVATE_DETAIL) }
            }.buffer()
            override fun source() = bytes
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength() = -1L
        } }) { service, calls ->
            for (failure in listOf(assertFailsWith<NovelAiAccountException> { service.fetch("fake-account-only") },
                assertFailsWith<NovelAiAccountException> { service.fetchCancellable("fake-account-only") })) {
                assertEquals(NovelAiAccountFailureKind.AUTHENTICATION, failure.kind)
                assertEquals(401, failure.httpStatus); assertSanitized(failure)
            }
            assertEquals(2, calls.get())
        }
    }

    private fun assertSanitized(failure: NovelAiAccountException) {
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertFalse(failure.toString().contains(PRIVATE_DETAIL))
        assertFalse(failure.toString().contains("fake-account-only"))
    }

    private suspend fun withService(status: Int = 200, failure: (() -> IOException)? = null,
        body: String = PRIVATE_DETAIL, bodyFactory: (() -> ResponseBody)? = null,
        block: suspend (NovelAiAccountService, AtomicInteger) -> Unit) {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> = error("Unexpected DNS")
            }).addInterceptor { chain ->
                calls.incrementAndGet()
                assertEquals("Bearer fake-account-only", chain.request().header("Authorization"))
                failure?.let { throw it() }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("synthetic").body(bodyFactory?.invoke() ?: body.toResponseBody()).build()
            }.build()
        try { block(NovelAiAccountService(client), calls) }
        finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    private companion object { const val PRIVATE_DETAIL = "synthetic-private-host-token-response" }
}
