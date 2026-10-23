package com.example.chatbar.desktop

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import kotlin.test.*

class DesktopUpdateCheckerTest {
    @Test fun `B1 string flags and non string notes are invalid metadata`() {
        val valid = release("0.2.0", false)
        for ((key, value) in listOf("draft" to JsonPrimitive("false"), "prerelease" to JsonPrimitive("false"), "body" to JsonPrimitive(123))) {
            assertFails { DesktopUpdatePolicy.select(body(JsonObject(valid + (key to value))), "0.1.0", true) }
        }
    }
    @Test fun `B1 malformed UTF8 cannot become valid release notes`() {
        val valid = body(release("0.2.0", false)).toString(Charsets.UTF_8).replace("Synthetic release notes", "X")
        val bytes = valid.toByteArray(); bytes[valid.indexOf("X")] = 0xff.toByte()
        assertFails { DesktopUpdatePolicy.select(bytes, "0.1.0", true) }
    }
    @Test fun `B1 timeout offline and cancellation remain failures`() = runBlocking {
        val client = okhttp3.OkHttpClient.Builder().callTimeout(250, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            assertFails { DesktopUpdateChecker(client, server.url("/").toString()).check("0.1.0", true) }
            assertEquals(1, server.requestCount)
        }
        MockWebServer().use { server ->
            server.start(); val url = server.url("/").toString(); server.shutdown()
            assertFails { DesktopUpdateChecker(client, url).check("0.1.0", true) }
        }
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val worker = async { DesktopUpdateChecker(endpoint = server.url("/").toString()).check("0.1.0", true) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS)) }
            withTimeout(3000) { worker.cancelAndJoin() }
            assertTrue(worker.isCancelled); assertEquals(1, server.requestCount)
        }
    }
    @Test fun `B1 chunked oversized response and retry followups stay bounded`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setChunkedBody("x".repeat(DesktopUpdatePolicy.MAX_BYTES + 1), 4096))
            val checker = DesktopUpdateChecker(endpoint = server.url("/").toString())
            assertFails { checker.check("0.1.0", true) }
            server.enqueue(MockResponse().setResponseCode(408))
            assertFails { checker.check("0.1.0", true) }
            server.enqueue(MockResponse().setBody("[]"))
            val next = checker.check("0.1.0", true); assertNull(next.latest); assertFalse(next.newer)
            assertEquals(3, server.requestCount)
            repeat(3) {
                val request = server.takeRequest(); assertEquals("GET", request.method)
                assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie")); assertEquals(0, request.bodySize)
            }
        }
    }
    @Test fun `B1 exact trusted browser URL excludes query fragment encoding ports and schemes`() {
        for (url in listOf("javascript:alert(1)", "file:///C:/test", "https://github.com:443/${DesktopUpdatePolicy.REPOSITORY}/releases/tag/desktop-v0.2.0",
            "https://github.com/${DesktopUpdatePolicy.REPOSITORY}/releases/tag/desktop-v0.2.0?x=1", "https://github.com/${DesktopUpdatePolicy.REPOSITORY}/releases/tag/desktop-v0.2.0#x",
            "https://github.com/${DesktopUpdatePolicy.REPOSITORY}/releases/tag/desktop-v0%2e2%2e0")) assertFalse(DesktopUpdatePolicy.trustedPage(url, "0.2.0"))
        val noNotes = JsonObject(release("0.2.0", false) - "body")
        assertEquals("", DesktopUpdatePolicy.select(body(noNotes), "0.1.0", false).latest!!.notes)
        assertFails { DesktopUpdatePolicy.select(body(JsonObject(release("0.2.0", false) + ("published_at" to JsonPrimitive("bad")))), "0.1.0", true) }
    }
    private fun release(version: String, preview: Boolean, url: String = "https://github.com/${DesktopUpdatePolicy.REPOSITORY}/releases/tag/desktop-v$version",
        tag: String = "desktop-v$version", draft: Boolean = false) = buildJsonObject {
        put("tag_name", tag); put("draft", draft); put("prerelease", preview); put("html_url", url)
        put("published_at", "2026-10-21T00:00:00Z"); put("body", "Synthetic release notes")
    }
    private fun body(vararg entries: JsonObject) = JsonArray(entries.toList()).toString().toByteArray()

    @Test fun `Desktop tags and channels are distinct from Android versions`() {
        val bytes = body(release("9.0.0", false, tag = "v9.0.0"), release("0.1.0", false),
            release("0.2.0-preview.2", true), release("8.0.0", false, draft = true))
        val stable = DesktopUpdatePolicy.select(bytes, "0.1.0", false)
        assertEquals("0.1.0", stable.latest!!.version); assertFalse(stable.newer)
        val preview = DesktopUpdatePolicy.select(bytes, "0.1.0", true)
        assertEquals("0.2.0-preview.2", preview.latest!!.version); assertTrue(preview.newer)
        assertEquals("Synthetic release notes", preview.latest!!.notes)
        assertNull(DesktopUpdatePolicy.select(body(release("9.0.0", false, tag = "v9.0.0")), "0.1.0", true).latest)
    }
    @Test fun `version comparison respects numeric prerelease stable and arbitrary length components`() {
        fun v(value: String) = DesktopReleaseVersion.parse(value)
        assertTrue(v("0.1.0-preview.10") > v("0.1.0-preview.2"))
        assertTrue(v("0.1.0") > v("0.1.0-rc.9"))
        assertTrue(v("0.1.0-preview.1") > v("0.1.0-preview"))
        assertFails { v("0.1.0-preview.01") }; assertFails { v("1.2") }; assertFails { v("-1.2.3") }
    }
    @Test fun `untrusted pages channel mismatches duplicate versions and malformed metadata fail visibly`() {
        for (url in listOf("https://github.com.evil.invalid/${DesktopUpdatePolicy.REPOSITORY}/releases/tag/desktop-v0.2.0",
            "https://github.com/SaltyFishOTL/ChatChatBar/releases/tag/desktop-v0.2.0", "http://github.com/${DesktopUpdatePolicy.REPOSITORY}/releases/tag/desktop-v0.2.0",
            "https://user@github.com/${DesktopUpdatePolicy.REPOSITORY}/releases/tag/desktop-v0.2.0")) {
            assertFails { DesktopUpdatePolicy.select(body(release("0.2.0", false, url)), "0.1.0", true) }
        }
        assertFails { DesktopUpdatePolicy.select(body(release("0.2.0-preview.1", false)), "0.1.0", true) }
        assertFails { DesktopUpdatePolicy.select(body(release("0.2.0", false), release("0.2.0", false)), "0.1.0", true) }
        assertFails { DesktopUpdatePolicy.select("{}".toByteArray(), "0.1.0", true) }
    }
    @Test fun `one explicit check uses no credentials redirects hidden retries or downloads`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(body(release("0.2.0", false)).toString(Charsets.UTF_8)))
            val checker = DesktopUpdateChecker(endpoint = server.url("/releases").toString())
            assertTrue(checker.check("0.1.0", true).newer)
            val request = server.takeRequest()
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
            assertFails { checker.check("0.1.0", true) }
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            assertFails { checker.check("0.1.0", true) }
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun `oversized metadata and unknown source cannot trigger a browser or success`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("x".repeat(DesktopUpdatePolicy.MAX_BYTES + 1)))
            assertFails { DesktopUpdateChecker(endpoint = server.url("/").toString()).check("0.1.0", true) }
            assertEquals(1, server.requestCount)
        }
        assertFails { DesktopUpdateChecker(endpoint = "https://untrusted.invalid/").check("0.1.0", true) }
    }
}
