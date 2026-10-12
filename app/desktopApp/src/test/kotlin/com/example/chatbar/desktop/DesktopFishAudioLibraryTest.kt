package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.DesktopCredentialKey
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.desktop.security.WindowsDpapiSecretProtector
import com.example.chatbar.desktop.security.WindowsSecretStore
import com.example.chatbar.domain.voice.FishAudioApiException
import com.example.chatbar.domain.voice.FishAudioLibrary
import com.example.chatbar.domain.voice.FishAudioModelQuery
import com.example.chatbar.domain.voice.FishAudioModelSort
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopFishAudioLibraryTest {
    @Test fun `credentials trim only Fish key and leave unrelated credentials intact`() {
        val store = InMemoryDesktopSecretStore()
        store.save(DesktopCredentialKey.NovelAiToken, "other-fixture")
        val credentials = DesktopFishAudioCredentials(store)
        credentials.save("  fish-fixture  ")
        assertEquals("fish-fixture", credentials.load())
        assertFailsWith<IllegalArgumentException> { credentials.save("  ") }
        assertEquals("fish-fixture", credentials.load())
        credentials.clear()
        assertNull(credentials.load())
        assertEquals("other-fixture", store.load(DesktopCredentialKey.NovelAiToken))
    }

    @Test fun `isolated DPAPI Fish profile survives store recreation without plaintext`() {
        check(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("fish-credential-profile-")
        try {
            val store = WindowsSecretStore(root, WindowsDpapiSecretProtector())
            DesktopFishAudioCredentials(store).save("synthetic-fish-fixture")
            val bytes = Files.readAllBytes(store.secretPath(DesktopCredentialKey.FishAudioApiKey))
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("synthetic-fish-fixture"))
            val reopened = DesktopFishAudioCredentials(WindowsSecretStore(root, WindowsDpapiSecretProtector()))
            assertEquals("synthetic-fish-fixture", reopened.load())
            reopened.clear()
            assertNull(reopened.load())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun `missing credential makes no catalog or paid preview request`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val library = library(server, InMemoryDesktopSecretStore())
            assertFailsWith<FishAudioApiException> { library.listModels(FishAudioModelQuery()) }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `mine filters pagination and binding snapshot use exact shared request authority`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"total":1,"has_more":false,"items":[{"_id":"private-id","title":"Fixture","visibility":"private","samples":[{"audio_url":"https://sample.invalid/a.mp3","text":"sample"}]}]}"""))
            val result = library(server).listModels(FishAudioModelQuery(library = FishAudioLibrary.MINE,
                pageNumber = 3, pageSize = 10, title = "fixture", tags = listOf("test"), languages = listOf("zh"),
                sort = FishAudioModelSort.LATEST))
            val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("GET", request.method)
            assertEquals("/model", request.requestUrl!!.encodedPath)
            assertEquals("true", request.requestUrl!!.queryParameter("self"))
            assertEquals("3", request.requestUrl!!.queryParameter("page_number"))
            assertEquals("10", request.requestUrl!!.queryParameter("page_size"))
            assertEquals("created_at", request.requestUrl!!.queryParameter("sort_by"))
            assertEquals("test", request.requestUrl!!.queryParameter("tag"))
            assertEquals("zh", request.requestUrl!!.queryParameter("language"))
            assertEquals("Bearer fixture-only", request.getHeader("Authorization"))
            val binding = result.items.single().toBinding()
            assertEquals("private-id", binding.referenceId)
            assertEquals("sample", binding.sampleText)
            assertEquals(1, server.requestCount) // Neither reading nor converting a result generates a preview.
        }
    }

    @Test fun `private voice and authentication failures remain explicit and sanitized`() = runBlocking {
        MockWebServer().use { server ->
            for (status in listOf(401, 403, 429, 500)) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("fixture-only private provider detail"))
                val error = assertFailsWith<FishAudioApiException> { library(server).listModels(FishAudioModelQuery()) }
                assertEquals(status, error.statusCode)
                assertFalse(error.toString().contains("fixture-only"))
                assertFalse(error.toString().contains("private provider"))
                assertNull(error.cause)
            }
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun `malformed and oversized responses fail without exposing remote content`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("fixture-only malformed"))
            assertFalse(assertFailsWith<FishAudioApiException> { library(server).listModels(FishAudioModelQuery()) }
                .toString().contains("fixture-only"))
            server.enqueue(MockResponse().setBody("x".repeat(4 * 1024 * 1024 + 1)))
            assertFailsWith<FishAudioApiException> { library(server).listModels(FishAudioModelQuery()) }
        }
    }

    @Test fun `credential failure remains failure with no network or secret-bearing cause`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val store = InMemoryDesktopSecretStore().also { it.loadFailure = IllegalStateException("fixture-only") }
            val error = assertFailsWith<FishAudioApiException> { library(server, store).listModels(FishAudioModelQuery()) }
            assertFalse(error.toString().contains("fixture-only"))
            assertNull(error.cause)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `cancellation after response headers cancels body read and preserves cancellation`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"items":[]}""").throttleBody(1, 1, TimeUnit.SECONDS))
            val headers = CountDownLatch(1)
            val client = OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
                override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) { headers.countDown() }
            }).build()
            val work = async(kotlinx.coroutines.Dispatchers.Default) { library(server, client = client).listModels(FishAudioModelQuery()) }
            assertTrue(headers.await(5, TimeUnit.SECONDS))
            withTimeout(5_000) { work.cancelAndJoin() }
            assertTrue(work.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `app catalog close cancels and drains response body and refuses later requests`() = runBlocking {
        MockWebServer().use { server ->
            val headers = CountDownLatch(1)
            val client = OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
                override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) { headers.countDown() }
            }).build()
            server.enqueue(MockResponse().setBody("""{"items":[]}""").throttleBody(1, 1, TimeUnit.SECONDS))
            val catalog = library(server, client = client)
            val work = async(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { catalog.listModels(FishAudioModelQuery()) }
            }
            assertTrue(headers.await(5, TimeUnit.SECONDS))
            withTimeout(5_000) { catalog.closeAndDrain() }
            assertTrue(work.await().isFailure)
            assertTrue(client.dispatcher.executorService.isTerminated)
            assertFailsWith<FishAudioApiException> { catalog.listModels(FishAudioModelQuery()) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `community query preserves explicit filters without a sample fetch`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"total":30,"has_more":true,"items":[]}"""))
            val catalog = library(server)
            val page = catalog.listModels(FishAudioModelQuery(title = "Community", sort = FishAudioModelSort.POPULAR))
            assertEquals(true, page.hasMore)
            val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("false", request.requestUrl!!.queryParameter("self"))
            assertEquals("task_count", request.requestUrl!!.queryParameter("sort_by"))
            assertEquals("Community", request.requestUrl!!.queryParameter("title"))
            assertEquals(1, server.requestCount)
            catalog.closeAndDrain()
        }
    }

    private fun library(server: MockWebServer,
        store: InMemoryDesktopSecretStore = InMemoryDesktopSecretStore().also { it.save(DesktopCredentialKey.FishAudioApiKey, "fixture-only") },
        client: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
    ) = DesktopFishAudioLibrary(DesktopFishAudioCredentials(store), client, server.url("/").toString())
}
