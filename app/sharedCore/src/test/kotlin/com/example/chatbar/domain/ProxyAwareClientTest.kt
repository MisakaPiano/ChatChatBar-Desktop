package com.example.chatbar.domain

import java.io.IOException
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyAwareClientTest {
    @Test
    fun `authorization trims nonblank key and omits blank bearer`() {
        val withKey = Request.Builder().url("https://example.test").addModelApiAuthorization("  token  ").build()
        val blank = Request.Builder().url("https://example.test").addModelApiAuthorization(" \n ").build()

        assertEquals("Bearer token", withKey.header("Authorization"))
        assertNull(blank.header("Authorization"))
    }

    @Test
    fun `cleartext is blocked by default and works when opted in`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            val request = Request.Builder().url(server.url("/probe")).build()

            val blocked = assertFailsWith<IOException> {
                ProxyAwareClient.modelApiBuilder { false }.build().newCall(request).execute()
            }
            assertTrue(blocked.message.orEmpty().contains("明文 HTTP"))
            ProxyAwareClient.modelApiBuilder { true }.build().newCall(request).execute().use {
                assertEquals("ok", it.body?.string())
            }
        }
    }

    @Test
    fun `builder retains proxy selector and dns policy construction`() {
        val client = ProxyAwareClient.builder().build()
        assertNotNull(client.proxySelector)
        assertNotNull(client.dns)
    }
}
