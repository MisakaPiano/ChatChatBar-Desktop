package com.example.chatbar.domain.syncStore.webdav

import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import kotlin.test.*

/** Actual small synthetic HTTP; cached reconstruction is never target readback or AEAD admission. */
class WeakWebDavIncomingChunkCacheTest {
    private class Fixture(val shared: WeakFixture) : AutoCloseable {
        private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(5, TimeUnit.SECONDS).build()
        private val transports = mutableListOf<WebDavHttpTransport>()
        val scope = "4".repeat(64)
        val cacheRoot = directory("incoming")
        fun directory(name: String): Path = shared.root.resolve(name).also { if (!Files.exists(it)) Files.createDirectory(it) }
        private fun transport(base: String) = WebDavHttpTransport(shared.server.url(base).newBuilder().host("127.0.0.1").build(), client,
            { "Bearer synthetic-only" }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP).also(transports::add)
        fun layer(name: String, route: SyncStoreId = SyncStoreId.random(), base: String = "/sync/") =
            WeakWebDavChunkAssembly(transport(base), directory(name), route, 17, WebDavIoBudget(bufferBytes = 7, minimumFreeBytes = 0))
        fun cache(scopeHash: String = scope) = WeakWebDavIncomingChunkCache(cacheRoot, scopeHash, WebDavIoBudget(bufferBytes = 7, minimumFreeBytes = 0))
        fun assemblyStore(name: String) = WeakWebDavAttemptStore(transport("/sync/"), directory(name), SyncStoreId.random(),
            WebDavIoBudget(minimumFreeBytes = 0), WeakWebDavNamespace.ASSEMBLIES)
        fun path(candidate: WeakWebDavCandidate) = "/sync/weak-assemblies-v1/${if (candidate.key.kind == CiphertextObjectKind.BLOB) "objects" else "commits"}/${candidate.key.id.value}/${candidate.descriptor.sha256.hex}/${candidate.descriptor.length}-${candidate.operationId.value}-${candidate.attempt.value}.bin"
        fun cachedParts() = Files.walk(cacheRoot).use { files -> files.filter { it.fileName.toString().endsWith(".part") }.toList() }
        fun references() = Files.walk(cacheRoot).use { files -> files.filter { it.fileName.toString() == "manifest.ref" }.toList() }
        override fun close() { transports.forEach { it.close() }; client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    private fun fixture(work: (Fixture) -> Unit) = weakFixture { shared -> Fixture(shared).use(work) }
    private fun failure(expected: CiphertextStoreFailure, work: () -> Unit) {
        assertEquals(expected, assertFailsWith<CiphertextStoreException>(block = work).failure)
    }

    @Test fun interruptedGetColdNewRouteFetchesOnlyMissingPartsAndTargetReadbackStillFetchesAllParts() = fixture { f ->
        val bytes = ByteArray(113) { (it % 89).toByte() }; val request = davRequest(bytes)
        val written = f.layer("writer").append(request, bytes.inputStream())
        val total = written.chunkCount
        val gets = Collections.synchronizedList(mutableListOf<String>())
        var parts = 0
        f.shared.dav.overrideRequest = { incoming ->
            if (incoming.method == "GET") {
                val path = incoming.requestUrl!!.encodedPath; gets += path
                if ("/weak-chunks-v1/" in path && ++parts == 3) MockResponse().setResponseCode(503) else null
            } else null
        }
        val first = ByteArrayOutputStream()
        failure(CiphertextStoreFailure.TRANSIENT) { f.layer("first-reader").readIncomingExact(request.key, request.descriptor, first, f.cache()) }
        assertEquals(0, first.size()); assertEquals(2, f.cachedParts().size)
        val retained = f.cachedParts().associateWith(Files::readAllBytes)
        gets.clear()
        f.shared.dav.overrideRequest = { incoming -> if (incoming.method == "GET") gets += incoming.requestUrl!!.encodedPath; null }
        val reader = f.layer("cold-reader")
        val output = ByteArrayOutputStream()
        val resumed = requireNotNull(reader.readIncomingExact(request.key, request.descriptor, output, f.cache()))
        assertContentEquals(bytes, output.toByteArray())
        assertEquals(2L, resumed.cachedChunks); assertEquals(total - 2, resumed.fetchedChunks)
        assertTrue(resumed.cachedManifest); assertEquals(reader.routeId, resumed.manifest.route)
        assertEquals(0, gets.count { "/weak-assemblies-v1/" in it })
        assertEquals((total - 2).toInt(), gets.count { "/weak-chunks-v1/" in it })
        retained.forEach { (path, value) -> assertContentEquals(value, Files.readAllBytes(path)) }

        gets.clear()
        assertNotNull(reader.readExact(request.key, request.descriptor, ByteArrayOutputStream()))
        assertEquals(total.toInt(), gets.count { "/weak-chunks-v1/" in it }, "Target readback cannot borrow incoming-cache observations")
        assertEquals(1, gets.count { "/weak-assemblies-v1/" in it })
        gets.clear(); f.shared.dav.files.clear()
        val localOnly = ByteArrayOutputStream()
        val cached = requireNotNull(f.layer("offline-reader").readIncomingExact(request.key, request.descriptor, localOnly, f.cache()))
        assertContentEquals(bytes, localOnly.toByteArray()); assertEquals(total, cached.cachedChunks); assertEquals(0L, cached.fetchedChunks)
        assertTrue(gets.isEmpty(), "Cache reconstruction is permitted without claiming the provider still has the bytes")
    }

    @Test fun guardCancellationRetainsVerifiedPartsAndCallerSinkFailureNeverTriesAnotherManifest() = fixture { f ->
        val bytes = ByteArray(91) { it.toByte() }; val request = davRequest(bytes)
        f.layer("writer-one").append(request, bytes.inputStream())
        f.layer("writer-two").append(request.copy(operationId = SyncStoreId.random()), bytes.inputStream())
        val refusal = CiphertextStoreException(CiphertextStoreFailure.TRANSIENT)
        val partial = ByteArrayOutputStream()
        assertSame(refusal, assertFailsWith<CiphertextStoreException> {
            f.layer("cancel-reader").readIncomingExact(request.key, request.descriptor, partial, f.cache()) {
                if (f.cachedParts().size >= 2) throw refusal
            }
        })
        assertEquals(0, partial.size()); assertEquals(2, f.cachedParts().size)
        val reader = f.layer("resume-after-cancel")
        val output = ByteArrayOutputStream()
        val resumed = requireNotNull(reader.readIncomingExact(request.key, request.descriptor, output, f.cache()))
        assertEquals(2L, resumed.cachedChunks); assertContentEquals(bytes, output.toByteArray())
        val calls = f.shared.server.requestCount
        var writes = 0
        val escaped = ByteArrayOutputStream(); val broken = IOException("SYNTHETIC_SINK_FAILED")
        val sink = object : OutputStream() {
            override fun write(value: Int) { writes++; escaped.write(value); throw broken }
            override fun write(value: ByteArray, offset: Int, length: Int) { writes++; escaped.write(value, offset, 1); throw broken }
        }
        assertSame(broken, assertFailsWith<IOException> { reader.readIncomingExact(request.key, request.descriptor, sink, f.cache()) })
        assertEquals(1, writes); assertEquals(1, escaped.size()); assertEquals(calls, f.shared.server.requestCount)
    }

    @Test fun aBadFrozenManifestNeverOwnsTheLogicalDescriptorAndAValidLaterClaimStillReconstructs() = fixture { f ->
        val bytes = ByteArray(75) { it.toByte() }; val request = davRequest(bytes)
        val good = f.layer("writer").append(request, bytes.inputStream())
        val goodPath = f.path(good.assembly); val goodWire = f.shared.dav.files.remove(goodPath)!!
        val manifest = goodWire.copyOfRange(WebDavStoreRecord.HEADER_BYTES.toInt(), goodWire.size)
        val header = "CCB-WEAK-DAV-ASSEMBLY/1\n".toByteArray(Charsets.US_ASCII).size + 126
        val entry = 182
        val one = manifest.copyOfRange(header + 8, header + entry)
        val two = manifest.copyOfRange(header + entry + 8, header + 2 * entry)
        two.copyInto(manifest, header + 8); one.copyInto(manifest, header + entry + 8)
        f.assemblyStore("bad-manifest-writer").appendAttempt(
            CiphertextPutRequest(request.key, SyncStoreId.random(), WebDavStoreRecord.checksum(manifest)), manifest.inputStream())
        val first = ByteArrayOutputStream()
        failure(CiphertextStoreFailure.INTEGRITY) { f.layer("bad-first-reader").readIncomingExact(request.key, request.descriptor, first, f.cache()) }
        assertEquals(0, first.size()); assertEquals(1, f.references().size)
        val frozenBad = f.references().associateWith(Files::readAllBytes)
        f.shared.dav.files[goodPath] = goodWire
        val output = ByteArrayOutputStream()
        assertNotNull(f.layer("valid-next-reader").readIncomingExact(request.key, request.descriptor, output, f.cache()))
        assertContentEquals(bytes, output.toByteArray()); assertEquals(2, f.references().size)
        frozenBad.forEach { (path, value) -> assertContentEquals(value, Files.readAllBytes(path), "A new provider claim cannot replace an old frozen plan") }
    }

    @Test fun anActualMissingPartCanYieldToAnotherClaimButNetworkFailureCannot() = fixture { f ->
        val bytes = ByteArray(74) { (it % 59).toByte() }; val request = davRequest(bytes)
        f.layer("writer-one").append(request, bytes.inputStream())
        f.layer("writer-two").append(request.copy(operationId = SyncStoreId.random()), bytes.inputStream())
        var gets = 0
        f.shared.dav.overrideRequest = { incoming ->
            if (incoming.method == "GET" && "/weak-chunks-v1/" in incoming.requestUrl!!.encodedPath) {
                gets++; MockResponse().setResponseCode(503)
            } else null
        }
        val output = ByteArrayOutputStream()
        failure(CiphertextStoreFailure.TRANSIENT) { f.layer("network-reader").readIncomingExact(request.key, request.descriptor, output, f.cache()) }
        assertEquals(1, gets); assertEquals(0, output.size()); assertTrue(f.cachedParts().isEmpty())
        gets = 0
        f.shared.dav.overrideRequest = { incoming ->
            if (incoming.method == "GET" && "/weak-chunks-v1/" in incoming.requestUrl!!.encodedPath && ++gets == 1)
                MockResponse().setResponseCode(404) else null
        }
        assertNotNull(f.layer("missing-reader").readIncomingExact(request.key, request.descriptor, output, f.cache()))
        assertContentEquals(bytes, output.toByteArray()); assertEquals(2, f.references().size)
    }

    @Test fun scopeEndpointAndLogicalDescriptorCannotReuseAnotherBindingAndCorruptCacheIsALocalFailure() = fixture { f ->
        val bytes = ByteArray(58) { (it % 53).toByte() }; val request = davRequest(bytes)
        f.layer("writer").append(request, bytes.inputStream())
        assertNotNull(f.layer("reader").readIncomingExact(request.key, request.descriptor, ByteArrayOutputStream(), f.cache()))
        val before = f.shared.server.requestCount
        failure(CiphertextStoreFailure.OPERATION_CONFLICT) { f.cache("5".repeat(64)) }
        failure(CiphertextStoreFailure.OPERATION_CONFLICT) {
            f.layer("wrong-endpoint", base = "/other/").readIncomingExact(request.key, request.descriptor, ByteArrayOutputStream(), f.cache())
        }
        assertEquals(before, f.shared.server.requestCount)
        val refs = f.references().associateWith(Files::readAllBytes)
        val wrong = request.descriptor.copy(sha256 = CiphertextSha256.parse(if (request.descriptor.sha256.hex == "f".repeat(64)) "e".repeat(64) else "f".repeat(64)))
        val output = ByteArrayOutputStream()
        failure(CiphertextStoreFailure.INTEGRITY) { f.layer("wrong-descriptor").readIncomingExact(request.key, wrong, output, f.cache()) }
        assertEquals(0, output.size()); assertEquals(refs.keys, f.references().toSet())
        refs.forEach { (path, value) -> assertContentEquals(value, Files.readAllBytes(path)) }
        val part = f.cachedParts().first()
        val damaged = Files.readAllBytes(part).also { it[0] = (it[0].toInt() xor 1).toByte() }; Files.write(part, damaged)
        val calls = f.shared.server.requestCount
        assertEquals("SYNC_WEAK_DAV_INCOMING_CACHE_INTEGRITY", assertFailsWith<WeakWebDavIncomingCacheException> {
            f.layer("corrupt-cache-reader").readIncomingExact(request.key, request.descriptor, output, f.cache())
        }.message)
        assertEquals(0, output.size()); assertEquals(calls, f.shared.server.requestCount)
        assertContentEquals(damaged, Files.readAllBytes(part))
    }

    @Test fun evenASelfConsistentChangedManifestLocatorCannotReplaceAnExistingFrozenSlot() = fixture { f ->
        val bytes = ByteArray(39) { it.toByte() }; val request = davRequest(bytes)
        f.layer("writer").append(request, bytes.inputStream())
        assertNotNull(f.layer("reader").readIncomingExact(request.key, request.descriptor, ByteArrayOutputStream(), f.cache()))
        val reference = f.references().single(); val encoded = Files.readAllBytes(reference)
        val body = encoded.copyOfRange(0, encoded.size - 32)
        body[body.lastIndex] = if (body.last() == 'a'.code.toByte()) 'b'.code.toByte() else 'a'.code.toByte()
        Files.write(reference, body + MessageDigest.getInstance("SHA-256").digest(body))
        val calls = f.shared.server.requestCount; val output = ByteArrayOutputStream()
        assertFailsWith<WeakWebDavIncomingCacheException> {
            f.layer("locator-changed-reader").readIncomingExact(request.key, request.descriptor, output, f.cache())
        }
        assertEquals(0, output.size()); assertEquals(calls, f.shared.server.requestCount)
    }
}
