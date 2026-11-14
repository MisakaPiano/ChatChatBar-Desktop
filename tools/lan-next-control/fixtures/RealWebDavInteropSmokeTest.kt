package com.example.chatbar.domain.syncStoreProof

import com.example.chatbar.domain.lanSync.LanSyncGroupChunkCloudReadRoute
import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.*
import java.io.ByteArrayOutputStream
import java.net.Proxy
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.test.*

/** Real rclone HTTP interop with synthetic opaque bytes; no AEAD/native/provider account claim. */
class RealWebDavInteropSmokeTest {
    @Test fun realServerMethodsAndColdDifferentLocalRoutePreserveCompleteOpaquePayload() {
        org.junit.Assume.assumeTrue("requires explicit isolated real-server job",
            System.getenv("CCB_REAL_DAV_PROOF") == "true")
        require(System.getenv("GITHUB_ACTIONS") == "true" && System.getenv("RUNNER_OS") == "Linux" &&
            System.getenv("GITHUB_REPOSITORY") == "MisakaPiano/ChatChatBar-Desktop")
        val endpoint = requireNotNull(System.getenv("CCB_REAL_DAV_URL")).toHttpUrl()
        require(endpoint.scheme == "http" && endpoint.host == "127.0.0.1" && endpoint.port == 18765 &&
            endpoint.encodedPath == "/" && endpoint.username.isEmpty() && endpoint.password.isEmpty() &&
            endpoint.query == null && endpoint.fragment == null)
        val user = requireNotNull(System.getenv("CCB_REAL_DAV_USER"))
        val pass = requireNotNull(System.getenv("CCB_REAL_DAV_PASS"))
        require(user == "synthetic-ccb-ci" && pass.length >= 32)
        val methodEvidence = Path.of(requireNotNull(System.getenv("CCB_REAL_DAV_METHOD_EVIDENCE")))
            .toAbsolutePath().normalize()
        require(methodEvidence.fileName.toString() == "real-webdav-method-counts.json" &&
            methodEvidence.parent.toRealPath() == methodEvidence.parent &&
            !Files.exists(methodEvidence, LinkOption.NOFOLLOW_LINKS))
        val methods = ConcurrentHashMap<String, AtomicInteger>()
        val paths = ConcurrentHashMap<String, AtomicInteger>()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val method = request.method
                val path = request.url.encodedPath
                require(method.matches(Regex("[A-Z]{1,16}")) && path.length <= 1024 &&
                    path.matches(Regex("/[a-z0-9/.-]*")))
                methods.computeIfAbsent(method) { AtomicInteger() }.incrementAndGet()
                paths.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                chain.proceed(request)
            }.build()
        val runner = Path.of(requireNotNull(System.getenv("RUNNER_TEMP"))).toRealPath()
        require(methodEvidence.startsWith(runner))
        val root = Files.createTempDirectory(runner, "ccb-rclone-client-").toRealPath()
        fun directory(name: String) = Files.createDirectory(root.resolve(name))
        fun transport() = WebDavHttpTransport(endpoint, client,
            { Credentials.basic(user, pass, Charsets.UTF_8) }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
        try {
            client.newCall(Request.Builder().url(endpoint).build()).execute().use { assertEquals(401, it.code) }
            client.newCall(Request.Builder().url(endpoint)
                .header("Authorization", Credentials.basic(user, pass + "-wrong", Charsets.UTF_8)).build())
                .execute().use { assertEquals(401, it.code) }
            val bytes = ByteArray(3 * 64 * 1024 + 123) { (it % 193).toByte() }
            val descriptor = CiphertextDescriptor(bytes.size.toLong(), CiphertextSha256.parse(
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }))
            val key = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random())
            val request = CiphertextPutRequest(key, SyncStoreId.random(), descriptor)
            val publisherRoute = SyncStoreId.random()
            val written = transport().use { http ->
                WeakWebDavChunkAssembly(http, directory("writer"), publisherRoute,
                    chunkBytes = 32L * 1024, budget = WebDavIoBudget(minimumFreeBytes = 0))
                    .append(request, bytes.inputStream())
            }
            assertEquals(7L, written.chunkCount)
            val receiverRoute = SyncStoreId.random()
            assertNotEquals(publisherRoute, receiverRoute)
            transport().use { http ->
                val cold = WeakWebDavChunkAssembly(http, directory("cold-reader"), receiverRoute,
                    chunkBytes = 32L * 1024, budget = WebDavIoBudget(minimumFreeBytes = 0))
                val listed = cold.listCommitAssemblies(pageSize = 1).entries.single()
                assertEquals(written.assembly.copy(route = receiverRoute), listed)
                val route = LanSyncGroupChunkCloudReadRoute(cold)
                val metadata = CiphertextObjectMetadata(key, descriptor)
                assertEquals(listOf(metadata), route.listCommits(null, 1).entries)
                val output = ByteArrayOutputStream()
                assertEquals(metadata, route.readTo(key, output, descriptor))
                assertContentEquals(bytes, output.toByteArray())
                assertNull(route.latestFailure.value)
            }
            for (method in listOf("PUT", "GET", "PROPFIND", "MKCOL")) {
                assertTrue((methods[method]?.get() ?: 0) > 0, "Required provider method was not used")
            }
            assertTrue(paths.keys.any { it.startsWith("/weak-chunks-v1/") })
            assertTrue(paths.keys.any { it.startsWith("/weak-assemblies-v1/") })
            // The restricted keys contain no quotes, escapes, headers, credentials or payload values.
            fun counts(values: Map<String, AtomicInteger>) = values.toSortedMap().entries.joinToString(
                separator = ",", prefix = "{", postfix = "}") { (name, count) -> "\"$name\":${count.get()}" }
            val evidence = "{\"kind\":\"CCB_REAL_WEBDAV_METHOD_COUNTS\",\"payloadBytes\":196731," +
                "\"physicalChunks\":7,\"methods\":" + counts(methods) + ",\"paths\":" + counts(paths) + "}\n"
            Files.writeString(methodEvidence, evidence, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            check(root.startsWith(runner) && root.fileName.toString().startsWith("ccb-rclone-client-") &&
                !Files.isSymbolicLink(root) && root.toRealPath() == root)
            // Files.walk does not follow symbolic links; delete only this newly created client root.
            Files.walk(root).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }
}
