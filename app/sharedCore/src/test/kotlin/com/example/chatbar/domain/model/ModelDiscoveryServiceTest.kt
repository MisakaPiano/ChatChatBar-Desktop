package com.example.chatbar.domain.model

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelDiscoveryServiceTest {
    @Test
    fun `fetch uses models endpoint optional auth and sorted distinct IDs`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"zeta"},{"id":"Alpha"},{"id":"alpha"},{"id":" beta "}]}"""))
            val result = ModelDiscoveryService().fetch(server.url("/v1/").toString(), " key ", true)
            val request = server.takeRequest()

            assertEquals("/v1/models", request.path)
            assertEquals("Bearer key", request.getHeader("Authorization"))
            assertEquals(listOf("Alpha", "alpha", "beta", "zeta"), result)
        }

        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"one"}]}"""))
            ModelDiscoveryService().fetch(server.url("/").toString(), "", true)
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test
    fun `incompatible and empty payloads are explicit failures`() = runBlocking {
        listOf("{}", """{"data":[]}""").forEach { payload ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(payload))
                assertFailsWith<IOException> {
                    ModelDiscoveryService().fetch(server.url("/v1").toString(), "", true)
                }
            }
        }
    }

    @Test
    fun `status mapping and redirect policy remain explicit`() = runBlocking {
        listOf(
            401 to "API Key",
            404 to "未提供",
            500 to "服务商状态",
        ).forEach { (code, expected) ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(code))
                val error = assertFailsWith<IOException> {
                    ModelDiscoveryService().fetch(server.url("/v1").toString(), "", true)
                }
                assertTrue(error.message.orEmpty().contains(expected), error.message)
            }
        }

        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/redirected")))
            assertFailsWith<IOException> {
                ModelDiscoveryService().fetch(server.url("/v1").toString(), "", true)
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `url api key validation and cleartext policy fail before hidden fallback`() = runBlocking {
        val service = ModelDiscoveryService()
        assertFailsWith<IOException> { service.fetch("not-a-url", "", true) }
        assertFailsWith<IOException> { service.fetch("https://user@example.test/v1", "", true) }
        assertFailsWith<IOException> { service.fetch("https://example.test/v1", "bad key", true) }

        MockWebServer().use { server ->
            val error = assertFailsWith<IOException> {
                service.fetch(server.url("/v1").toString(), "", false)
            }
            assertTrue(error.message.orEmpty().contains("明文 HTTP"))
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `cancellation cancels an in flight discovery call`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.IO) {
                ModelDiscoveryService().fetch(server.url("/v1").toString(), "", true)
            }
            server.takeRequest(2, TimeUnit.SECONDS)
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }
}
