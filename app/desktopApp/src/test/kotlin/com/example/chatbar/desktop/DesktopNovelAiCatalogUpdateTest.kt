package com.example.chatbar.desktop

import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.update.*
import com.example.chatbar.domain.ApplicationProxyMode
import com.example.chatbar.domain.ApplicationProxySettings
import com.example.chatbar.domain.ProxyAwareClient
import java.io.IOException
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class DesktopNovelAiCatalogUpdateTest {
    @Test fun `ordinary checker and installer defaults honor live application routing and refuse unavailable settings`(): Unit = runBlocking {
        val root = Files.createTempDirectory("catalog-default-routing-")
        val oldProxy = ProxyAwareClient.applicationProxy
        val oldAvailable = ProxyAwareClient.applicationRoutingAvailable
        val oldSocketFactory = ProxyAwareClient.platformSocketFactory
        try {
            val (source, metadata) = database(root, "routing")
            Files.move(source, root.resolve("${metadata.sourceSha}.sqlite"))
            DesktopNovelAiSqlDatabase(root.resolve("${metadata.sourceSha}.sqlite")).use { db ->
                DesktopNovelAiIndexWriter(root.resolve("index-${metadata.sourceSha}.sqlite")).use { writer ->
                    buildNovelAiRankedIndex(writer, db, "tags", metadata.sourceSha, "danbooru")
                }
            }
            val pointer = root.resolve("active-catalog.json")
            val original = Json.encodeToString(DanbooruCatalogMetadata.serializer(), metadata)
            Files.writeString(pointer, original)
            val catalog = DesktopNovelAiTagCatalog(DesktopNovelAiCatalogAssets(root) { error("no bundled fallback") })
            val sockets = object : javax.net.SocketFactory() {
                override fun createSocket(): java.net.Socket = error("network forbidden")
                override fun createSocket(h: String, p: Int): java.net.Socket = error("network forbidden")
                override fun createSocket(h: String, p: Int, l: java.net.InetAddress, lp: Int): java.net.Socket = error("network forbidden")
                override fun createSocket(h: java.net.InetAddress, p: Int): java.net.Socket = error("network forbidden")
                override fun createSocket(h: java.net.InetAddress, p: Int, l: java.net.InetAddress, lp: Int): java.net.Socket = error("network forbidden")
            }
            ProxyAwareClient.platformSocketFactory = sockets
            val checker = SharedDanbooruCatalogUpdateChecker(catalog, "synthetic-routing")
            val installer = DesktopNovelAiCatalogUpdate(catalog)
            // Inspect the actual no-override instances used by the Studio controller.
            val clients = listOf(checker, installer).map { owner ->
                owner.javaClass.getDeclaredField("client").apply { isAccessible = true }.get(owner) as OkHttpClient
            }
            for (client in clients) assertSame(sockets, client.socketFactory)
            val destination = URI("https://raw.githubusercontent.com/synthetic/tag.sqlite")
            for ((mode, type) in listOf(ApplicationProxyMode.HTTP to Proxy.Type.HTTP, ApplicationProxyMode.SOCKS to Proxy.Type.SOCKS)) {
                ProxyAwareClient.applicationProxy = ApplicationProxySettings(mode = mode, host = "proxy.invalid", port = 8123)
                for (client in clients) {
                    val route = client.proxySelector.select(destination).single()
                    assertEquals(type, route.type())
                    assertEquals("proxy.invalid", (route.address() as java.net.InetSocketAddress).hostString)
                    assertEquals(8123, (route.address() as java.net.InetSocketAddress).port)
                }
            }
            ProxyAwareClient.applicationProxy = ApplicationProxySettings(mode = ApplicationProxyMode.DIRECT)
            for (client in clients) assertEquals(listOf(Proxy.NO_PROXY), client.proxySelector.select(destination))
            ProxyAwareClient.applicationRoutingAvailable = false
            assertFailsWith<IOException> { checker.checkLatestCatalog() }
            assertFailsWith<IOException> { installer.install(DanbooruCatalogUpdateInfo(metadata, "f".repeat(40), "", destination.toString(), "", 1)) {} }
            assertEquals(original, Files.readString(pointer))
            assertFalse(Files.list(root).use { paths -> paths.anyMatch { it.fileName.toString().endsWith(".part") } })
        } finally {
            ProxyAwareClient.applicationProxy = oldProxy
            ProxyAwareClient.applicationRoutingAvailable = oldAvailable
            ProxyAwareClient.platformSocketFactory = oldSocketFactory
            root.toFile().deleteRecursively()
        }
    }

    private fun database(root: Path, prefix: String): Pair<Path, DanbooruCatalogMetadata> {
        val file = root.resolve("$prefix-fixture.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().use { it.execute("CREATE TABLE tags(name TEXT, cn_name TEXT, category INTEGER, post_count INTEGER)") }
            db.autoCommit = false
            db.prepareStatement("INSERT INTO tags VALUES (?, '蓝色', 0, ?)").use { statement ->
                repeat(10000) { i -> statement.setString(1, "${prefix}_$i"); statement.setInt(2, i); statement.executeUpdate() }
            }
            db.commit()
        }
        val bytes = Files.size(file)
        val digest = MessageDigest.getInstance("SHA-1").apply { update("blob $bytes\u0000".toByteArray()); update(Files.readAllBytes(file)) }
        val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        return file to DanbooruCatalogMetadata(sha, "inline", bytes, 10000, "tags")
    }

    @Test fun `catalog update builds shared ranking before durable switch and corrupt authority is retained`(): Unit = runBlocking {
        val root = Files.createTempDirectory("p7-catalog-")
        try {
            val (old, metadata) = database(root, "old")
            Files.move(old, root.resolve("${metadata.sourceSha}.sqlite"))
            DesktopNovelAiSqlDatabase(root.resolve("${metadata.sourceSha}.sqlite")).use { source ->
                DesktopNovelAiIndexWriter(root.resolve("index-${metadata.sourceSha}.sqlite")).use {
                    buildNovelAiRankedIndex(it, source, "tags", metadata.sourceSha, "danbooru")
                }
            }
            val pointer = root.resolve("active-catalog.json")
            Files.writeString(pointer, Json.encodeToString(DanbooruCatalogMetadata.serializer(), metadata))
            val catalog = DesktopNovelAiTagCatalog(DesktopNovelAiCatalogAssets(root) { error("fixture must not access assets") })
            assertEquals(metadata, catalog.catalogMetadata())
            val (fresh, next) = database(root, "blue")
            val payload = Files.readAllBytes(fresh)
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(payload.toResponseBody()).build()
            }.build()
            val info = DanbooruCatalogUpdateInfo(metadata, next.sourceSha, "inline", "https://raw.githubusercontent.com/fixture", "", next.sourceSizeBytes)
            val installer = DesktopNovelAiCatalogUpdate(catalog, client)
            assertFails { installer.install(info.copy(latestSourceSha = "f".repeat(40))) {} }
            assertEquals(metadata, catalog.catalogMetadata())
            installer.install(info) {}
            assertEquals(next, catalog.catalogMetadata())
            assertEquals(next.sourceSha, catalog.completionVersion.value)
            val found = mutableListOf<String>()
            catalog.streamCompletion("blue_999", { found += it.name }, {})
            assertEquals("blue_9999", found.first())
            assertTrue(Files.exists(root.resolve("${metadata.sourceSha}.sqlite")))
            val restarted = DesktopNovelAiTagCatalog(DesktopNovelAiCatalogAssets(root) { error("no bundled fallback") })
            assertEquals(next, restarted.catalogMetadata())
            Files.writeString(pointer, "corrupt")
            val corrupt = DesktopNovelAiTagCatalog(DesktopNovelAiCatalogAssets(root) { error("no destructive fallback") })
            assertFails { DesktopNovelAiCatalogUpdate(corrupt, client).install(info) {} }
            assertEquals("corrupt", Files.readString(pointer))
            assertEquals(2, calls)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `official shared checker pins download to commit and compares blob rather than commit sha`(): Unit = runBlocking {
        val metadata = DanbooruCatalogMetadata("a".repeat(40), "inline", 1)
        val lookup = object : NovelAiTagLookup {
            override suspend fun catalogMetadata() = metadata
            override suspend fun exactChineseTranslations(names: Collection<String>) = emptyMap<String, String>()
            override suspend fun search(query: String): NovelAiTagSearchOutcome = error("not used")
        }
        val commitSha = "c".repeat(40)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath.endsWith("/commits")) """[{"sha":"$commitSha","commit":{"committer":{"date":"inline"}}}]"""
            else { assertEquals(commitSha, request.url.queryParameter("ref")); """{"sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","size":25,"download_url":"https://raw.githubusercontent.com/mutable"}""" }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody()).build()
        }.build()
        val update = assertNotNull(SharedDanbooruCatalogUpdateChecker(lookup, "fixture", client).checkLatestCatalog())
        assertTrue(update.downloadUrl.endsWith("/$commitSha/tag.sqlite"))
        assertEquals("b".repeat(40), update.latestSourceSha)
    }

    @Test fun `contents retries a transient response exactly once at the same pinned commit`(): Unit = runBlocking {
        val commitSha = "c".repeat(40)
        val requestedRefs = mutableListOf<String>()
        val checker = checker { request ->
            if (request.url.encodedPath.endsWith("/commits")) {
                response(request, 200, """[{"sha":"$commitSha"}]""")
            } else {
                requestedRefs += requireNotNull(request.url.queryParameter("ref"))
                if (requestedRefs.size == 1) response(request, 503)
                else response(request, 200, """{"sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","size":25,"download_url":"https://raw.githubusercontent.com/mutable"}""")
            }
        }
        assertTrue(assertNotNull(checker.checkLatestCatalog()).downloadUrl.endsWith("/$commitSha/tag.sqlite"))
        assertEquals(listOf(commitSha, commitSha), requestedRefs)
    }

    @Test fun `contents preserves HTTP evidence and does not retry throttled or permanent responses`(): Unit = runBlocking {
        for ((status, remaining, limited) in listOf(Triple(403, "0", true), Triple(403, "8", false), Triple(429, "8", true), Triple(404, "8", false))) {
            var requests = 0
            val checker = checker { request ->
                requests++
                if (request.url.encodedPath.endsWith("/commits")) response(request, 200, "[]")
                else response(request, status, "SYNTHETIC_PRIVATE_RESPONSE_BODY",
                    Headers.Builder().add("X-RateLimit-Remaining", remaining).add("Retry-After", "15").build())
            }
            val error = assertFailsWith<DanbooruCatalogCheckFailure> { checker.checkLatestCatalog() }
            assertEquals(DanbooruCatalogCheckStage.CONTENTS, error.stage)
            assertEquals(DanbooruCatalogCheckKind.HTTP, error.kind)
            assertEquals(status, error.httpStatus)
            assertEquals(limited, error.rateLimited)
            assertEquals(15L, error.retryAfterSeconds)
            assertFalse(error.message.orEmpty().contains("SYNTHETIC_PRIVATE_RESPONSE_BODY"))
            assertEquals(2, requests)
        }
    }

    @Test fun `request cancellation interrupts a scheduled retry without falling back to another ref`(): Unit = runBlocking {
        val transient = CompletableDeferred<Unit>()
        val commitSha = "c".repeat(40)
        val requestedRefs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val checker = checker { request ->
            if (request.url.encodedPath.endsWith("/commits")) response(request, 200, """[{"sha":"$commitSha"}]""")
            else {
                requestedRefs += requireNotNull(request.url.queryParameter("ref"))
                transient.complete(Unit)
                response(request, 503)
            }
        }
        val job = launch { checker.checkLatestCatalog() }
        try {
            withTimeout(5_000) { transient.await() }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(listOf(commitSha), requestedRefs)
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun checker(respond: (Request) -> Response): SharedDanbooruCatalogUpdateChecker {
        val lookup = object : NovelAiTagLookup {
            override suspend fun catalogMetadata() = DanbooruCatalogMetadata("a".repeat(40), "inline", 1)
            override suspend fun exactChineseTranslations(names: Collection<String>) = emptyMap<String, String>()
            override suspend fun search(query: String): NovelAiTagSearchOutcome = error("not used")
        }
        val client = OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build()
        return SharedDanbooruCatalogUpdateChecker(lookup, "fixture", client, retryTransientHttp = true)
    }

    private fun response(request: Request, status: Int, body: String = "", headers: Headers? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .apply { if (headers != null) headers(headers) }.body(body.toResponseBody()).build()
}
