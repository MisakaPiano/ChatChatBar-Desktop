package com.example.chatbar.domain.search

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.*

class ResearchHttpCancellationTest {
    @Test fun `cancelling owner cancels an actual blocked HTTP call and drains without waiting for socket timeout`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
            val call = client.newCall(okhttp3.Request.Builder().url(server.url("/synthetic")).build())
            try {
                val worker = launch(Dispatchers.IO) { call.withResearchOwner(currentCoroutineContext()[Job]) { it.body!!.string() } }
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                withTimeout(5_000) { worker.cancelAndJoin() }
                assertTrue(worker.isCancelled); assertTrue(call.isCanceled()); assertEquals(1, server.requestCount)
            } finally { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun `normal completion removes cancellation binding and pre cancelled owner never starts request`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("synthetic-body"))
            val client = OkHttpClient(); val owner = Job()
            val call = client.newCall(okhttp3.Request.Builder().url(server.url("/complete")).build())
            try {
                val result = withContext(Dispatchers.IO) { call.withResearchOwner(owner) { it.body!!.string() } }
                assertEquals("synthetic-body", result); owner.cancel(); assertFalse(call.isCanceled())
                val cancelled = Job().apply { cancel() }
                val refused = client.newCall(okhttp3.Request.Builder().url(server.url("/never")).build())
                assertFailsWith<CancellationException> { refused.withResearchOwner(cancelled) { it.body!!.string() } }
                assertEquals(1, server.requestCount)
            } finally { owner.cancel(); client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }
}
