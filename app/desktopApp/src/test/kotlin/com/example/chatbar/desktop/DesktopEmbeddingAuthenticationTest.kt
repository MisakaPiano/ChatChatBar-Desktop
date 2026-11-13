package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.EmbeddingConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class DesktopEmbeddingAuthenticationTest {
    @Test fun `final boundary rejects missing remote key without dispatch or usage but permits opted in keyless HTTP`(): Unit = runBlocking {
        MockWebServer().use { server ->
            var selected = EmbeddingConfig("default", "Synthetic", server.url("/v1").toString()
                .replace("http:", "https:"), "", "synthetic", 2)
            var allow = false
            val usage = mutableListOf<DesktopUsageRecord>()
            val gateway = DesktopEmbeddingGateway({ selected }, allowCleartext = { allow }, usageSink = { usage += it })
            fun batch() = DesktopIndexEmbeddingBatch(DesktopRagEmbeddingIdentity.from(selected), 0,
                listOf("synthetic input" to emptyMap()))
            try {
                assertFailsWith<DesktopEmbeddingAuthenticationException> { gateway.embed(batch()) }
                assertEquals(0, server.requestCount); assertTrue(usage.isEmpty())
                selected = selected.copy(baseUrl = server.url("/v1").toString())
                allow = true
                server.enqueue(MockResponse().setBody("""{"data":[{"index":0,"embedding":[1,0]}]}"""))
                assertEquals(listOf(listOf(1f, 0f)), gateway.embed(batch()))
                assertNull(server.takeRequest().getHeader("Authorization"))
                assertEquals(1, server.requestCount)
                assertEquals(1, usage.map { it.id }.distinct().size)
            } finally { gateway.closeAndDrain() }
        }
    }
}
