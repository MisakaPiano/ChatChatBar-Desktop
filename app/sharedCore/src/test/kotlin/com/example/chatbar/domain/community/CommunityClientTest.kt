package com.example.chatbar.domain.community

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.*

class CommunityClientTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val format = """{"schemaVersion":2,"name":"Synthetic format","content":"Synthetic content"}"""
    private fun item(id: String = "item", type: String = "format", author: String = "owner") = CommunityItemDto(
        id = id, type = type, title = "Synthetic format", sourceLocalName = "Synthetic format", authorUserId = author,
        filePath = "owner/packages/format/synthetic.json", sha256 = CommunityPackagePolicy.sha256(format.toByteArray()),
        sizeBytes = format.toByteArray().size.toLong(), schemaVersion = 2, updatedAt = "2026-01-01T00:00:00Z")
    private class Store(var saved: CommunitySession? = null) : CommunitySessionStore {
        var writes = 0
        override fun load() = saved
        override fun save(session: CommunitySession) { saved = session; writes++ }
        override fun clear() { saved = null; writes++ }
    }
    private suspend fun fixture(block: suspend (MockWebServer, CommunityClient, Store, MutableList<String>) -> Unit) {
        MockWebServer().use { server ->
            val store = Store()
            val warnings = mutableListOf<String>()
            val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
            val local = object : CommunityLocalPackages {
                override suspend fun candidates(type: CommunityItemType) = listOf(CommunityUploadCandidate("local", type, "Synthetic format"))
                override suspend fun export(type: CommunityItemType, localId: String) = CommunityLocalPackage("Synthetic format", format)
            }
            val owner = CommunityClient(CommunityConfiguration(server.url("/").toString(), "synthetic-public-key"),
                local, store, json, client) { message, _ -> warnings += message }
            try { block(server, owner, store, warnings) }
            finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }
    private fun MockWebServer.enabled() { enqueue(MockResponse().setBody("""[{"enabled":true}]""")) }
    private fun MockWebServer.request() = assertNotNull(takeRequest(5, TimeUnit.SECONDS))

    @Test fun `disabled service blocks browse auth and mutation without changing session custody`() = runBlocking {
        fixture { server, owner, store, _ ->
            server.enqueue(MockResponse().setBody("""[{"enabled":false,"message":"Synthetic disabled"}]"""))
            assertFalse(owner.refreshEnabledStatus(true))
            assertFailsWith<IllegalStateException> { owner.listItemsPage(0, 20) }
            assertFailsWith<IllegalStateException> { owner.discordLoginUrl() }
            assertFailsWith<IllegalStateException> { owner.submitDraft(owner.buildDraft(CommunityItemType.FORMAT, "local", "", "", emptyList())) }
            assertEquals(1, server.requestCount); assertEquals(0, store.writes)
            assertTrue(server.request().path!!.startsWith("/rest/v1/community_runtime_config?"))
        }
    }

    @Test fun `only missing runtime table enters visible official legacy mode`() = runBlocking {
        fixture { server, owner, _, warnings ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":"PGRST205","message":"community_runtime_config missing"}"""))
            assertTrue(owner.refreshEnabledStatus(true)); assertEquals(1, warnings.size)
            server.enqueue(MockResponse().setResponseCode(401).setBody("denied"))
            assertFailsWith<IllegalStateException> { owner.refreshEnabledStatus(true) }
            assertEquals(2, server.requestCount); assertEquals(1, warnings.size)
        }
    }

    @Test fun `metadata pagination stays separate from detail preview and counted download`() = runBlocking {
        fixture { server, owner, _, warnings ->
            server.enabled(); server.enqueue(MockResponse().setBody(json.encodeToString(listOf(item("one"), item("two")))))
            val page = owner.listItemsPage(0, 1)
            assertEquals(listOf("one"), page.items.map { it.id }); assertEquals(1, page.nextOffset); assertTrue(page.hasMore)
            server.request(); assertTrue(server.request().path!!.contains("limit=2"))
            server.enqueue(MockResponse().setBody(format))
            assertEquals(format, owner.readPackage(page.items.single()))
            assertTrue(server.request().path!!.startsWith("/storage/v1/object/public/community-packages/"))
            assertEquals(3, server.requestCount)
            server.enqueue(MockResponse().setBody(format)); server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(format, owner.downloadPackage(page.items.single()))
            server.request(); assertEquals("/rest/v1/rpc/increment_community_download_count", server.request().path)
            assertEquals(1, warnings.size)
        }
    }

    @Test fun `official callback validates target fetches identity persists and refreshes the same protected session`() = runBlocking {
        fixture { server, owner, store, _ ->
            server.enabled(); assertTrue(owner.refreshEnabledStatus(true)); server.request()
            assertTrue(owner.discordLoginUrl().contains("provider=discord&redirect_to=chatbar%3A%2F%2Fauth%2Fcallback"))
            assertFailsWith<IllegalArgumentException> { owner.handleAuthCallback("chatbar://other/callback#access_token=synthetic") }
            assertEquals(1, server.requestCount); assertNull(store.saved)
            server.enqueue(MockResponse().setBody("""{"id":"owner","user_metadata":{"preferred_username":"Synthetic user"}}"""))
            val session = owner.handleAuthCallback("chatbar://auth/callback#access_token=synthetic-access&refresh_token=synthetic-refresh&expires_in=1")
            assertEquals("Synthetic user", session.displayName); assertEquals(session, store.saved)
            assertEquals("Bearer synthetic-access", server.request().getHeader("Authorization"))
            server.enqueue(MockResponse().setBody("""{"access_token":"synthetic-next","expires_in":3600,"user":{"id":"owner"}}"""))
            server.enqueue(MockResponse().setBody("[]")); owner.listMyItemsPage(0, 20)
            assertEquals("/auth/v1/token?grant_type=refresh_token", server.request().path)
            assertEquals("Bearer synthetic-next", server.request().getHeader("Authorization"))
            assertEquals("synthetic-refresh", store.saved!!.refreshToken)
            owner.signOut(); assertNull(store.saved); assertNull(owner.session.value)
        }
    }

    @Test fun `snapshot publishing uses original storage edge contracts with ownership type and name guards`() = runBlocking {
        fixture { server, owner, store, _ ->
            server.enabled(); assertTrue(owner.refreshEnabledStatus(true)); server.request()
            server.enqueue(MockResponse().setBody("""{"id":"owner"}"""))
            owner.handleAuthCallback("chatbar://auth/callback#access_token=synthetic&expires_in=3600"); server.request()
            val draft = owner.buildDraft(CommunityItemType.FORMAT, "local", "", " description ", listOf(" x ", "x"))
            assertEquals(listOf("x"), draft.tags); assertEquals("description", draft.description)
            assertEquals(format.toByteArray().size.toLong(), draft.sizeBytes)
            assertEquals(CommunityPackagePolicy.sha256(format.toByteArray()), draft.sha256)
            assertFailsWith<IllegalArgumentException> { owner.updateDraft(item(author = "other").toDomain(), draft) }
            assertFailsWith<IllegalArgumentException> { owner.updateDraft(item().toDomain(), draft.copy(sourceLocalName = "different")) }
            server.enqueue(MockResponse().setBody("{}")); server.enqueue(MockResponse().setBody(json.encodeToString(item())))
            assertEquals("item", owner.submitDraft(draft).id)
            val storage = server.request(); assertTrue(storage.path!!.startsWith("/storage/v1/object/community-packages/owner/packages/format/"))
            assertEquals("false", storage.getHeader("x-upsert")); assertEquals(format, storage.body.readUtf8())
            val edge = server.request(); assertEquals("/functions/v1/submit-community-item", edge.path)
            val payload = json.parseToJsonElement(edge.body.readUtf8()).jsonObject
            assertEquals(draft.sha256, payload.getValue("sha256").jsonPrimitive.content)
            assertEquals(draft.sizeBytes, payload.getValue("size_bytes").jsonPrimitive.long)
            server.enqueue(MockResponse().setBody("{}")); owner.deleteItem(item().toDomain())
            val deleted = json.parseToJsonElement(server.request().body.readUtf8()).jsonObject
            assertEquals("delete", deleted.getValue("action").jsonPrimitive.content)
            assertEquals("item", deleted.getValue("item_id").jsonPrimitive.content)
            assertEquals(1, store.writes)
        }
    }

    @Test fun `declared oversized response is refused before consuming its body`() = runBlocking {
        fixture { server, owner, _, _ ->
            server.enabled(); owner.refreshEnabledStatus(true)
            server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", CommunityPackagePolicy.MAX_PACKAGE_BYTES + 1))
            assertFailsWith<IllegalArgumentException> { owner.readPackage(item().toDomain()) }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `preview bytes remain binary and never use package or download-count endpoints`() = runBlocking {
        fixture { server, owner, _, _ ->
            server.enabled(); owner.refreshEnabledStatus(true); server.request()
            val bytes = byteArrayOf(0, -1, 1, -128, 42)
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            assertContentEquals(bytes, owner.readPreview(item().copy(previewPath = "owner/previews/avatar.jpg").toDomain()))
            assertTrue(server.request().path!!.startsWith("/storage/v1/object/public/community-previews/"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `cancelling an in-flight session refresh cancels its call and retains the original session`() = runBlocking {
        fixture { server, owner, store, _ ->
            server.enabled(); owner.refreshEnabledStatus(true); server.request()
            server.enqueue(MockResponse().setBody("""{"id":"owner"}"""))
            val before = owner.handleAuthCallback("chatbar://auth/callback#access_token=synthetic-access&refresh_token=synthetic-refresh&expires_in=1")
            server.request()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val work = launch(Dispatchers.Default) { owner.listMyItemsPage(0, 20) }
            assertEquals("/auth/v1/token?grant_type=refresh_token", server.request().path)
            withTimeout(5_000) { work.cancelAndJoin() }
            assertEquals(before, store.saved); assertEquals(before, owner.session.value)
            assertEquals(1, store.writes); assertEquals(3, server.requestCount)
        }
    }

    @Test fun `transient refresh failure preserves credentials fails the action and retries without using expired access`() = runBlocking {
        fixture { server, owner, store, _ ->
            server.enabled(); owner.refreshEnabledStatus(true); server.request()
            server.enqueue(MockResponse().setBody("""{"id":"owner"}"""))
            val before = owner.handleAuthCallback("chatbar://auth/callback#access_token=synthetic-old&refresh_token=syntheticRefreshToken123&expires_in=1")
            server.request()
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"syntheticRefreshToken123","message":"synthetic-private-response-body"}"""))
            val failure = assertFailsWith<IllegalStateException> { owner.listMyItemsPage(0, 20) }
            assertFalse(failure.toString().contains("synthetic-private-response-body"))
            assertFalse(failure.cause.toString().contains("synthetic-private-response-body"))
            assertFalse(failure.toString().contains("syntheticRefreshToken123"))
            assertFalse(failure.cause.toString().contains("syntheticRefreshToken123"))
            assertEquals(before, store.saved); assertEquals(before, owner.session.value); assertEquals(1, store.writes)
            assertEquals("/auth/v1/token?grant_type=refresh_token", server.request().path)
            assertEquals(3, server.requestCount)
            server.enqueue(MockResponse().setBody("""{"access_token":"synthetic-new","expires_in":3600,"user":{"id":"owner"}}"""))
            server.enqueue(MockResponse().setBody("[]")); owner.listMyItemsPage(0, 20)
            assertEquals("/auth/v1/token?grant_type=refresh_token", server.request().path)
            assertEquals("Bearer synthetic-new", server.request().getHeader("Authorization"))
            assertEquals(2, store.writes); assertEquals("syntheticRefreshToken123", store.saved!!.refreshToken)
        }
    }

    @Test fun `only explicit refresh credential rejection clears the unusable saved session`() = runBlocking {
        fixture { server, owner, store, _ ->
            server.enabled(); owner.refreshEnabledStatus(true); server.request()
            server.enqueue(MockResponse().setBody("""{"id":"owner"}"""))
            val before = owner.handleAuthCallback("chatbar://auth/callback#access_token=synthetic-old&refresh_token=synthetic-refresh&expires_in=1")
            server.request()
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"unknown_gateway_failure"}"""))
            assertFailsWith<IllegalStateException> { owner.listMyItemsPage(0, 20) }
            assertEquals(before, store.saved); assertEquals(1, store.writes); server.request()
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error_code":"refresh_token_not_found","message":"synthetic-private"}"""))
            assertFailsWith<IllegalStateException> { owner.listMyItemsPage(0, 20) }
            assertNull(store.saved); assertNull(owner.session.value); assertEquals(2, store.writes)
            server.request(); assertEquals(4, server.requestCount)
        }
    }
}
