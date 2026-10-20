package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.EmbeddingConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class DesktopEmbeddingGatewayTest {
    @Test fun `HTTP retry hints never replay a metered embedding request`(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        val config = EmbeddingConfig("test", "Synthetic", server.url("/v1").toString(), "synthetic", "test", 2)
        val gateway = DesktopEmbeddingGateway({ config }, { true })
        try {
            val batch = DesktopIndexEmbeddingBatch(DesktopRagEmbeddingIdentity.from(config), 0,
                listOf("synthetic" to emptyMap()))
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody("""{"data":[{"index":0,"embedding":[1,0]}]}"""))
            assertFailsWith<IllegalStateException> { gateway.embed(batch) }
            assertEquals(1, server.requestCount)
            assertEquals(listOf(listOf(1f, 0f)), gateway.embed(batch))
            assertEquals(2, server.requestCount)
        } finally { gateway.closeAndDrain(); server.shutdown() }
    }
}
