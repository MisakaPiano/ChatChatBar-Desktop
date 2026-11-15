package com.example.chatbar.domain.syncStore.webdav

import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.lanSync.LanSyncGroupChunkCloudReadRoute
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import kotlin.test.*

/** Small synthetic HTTP resume/assembly proofs, not a real provider or large-upload acceptance. */
class WeakWebDavChunkAssemblyTest {
    @Test fun aNewDeviceRouteReadsTheSameEndpointWithoutChangingWriterManifestsOrClaimingItsRoute() = chunkFixture { f ->
        val bytes = ByteArray(91) { (it % 79).toByte() }
        val request = davRequest(bytes, CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()))
        val writer = f.layer("writer")
        val written = writer.append(request, bytes.inputStream())
        val originalFiles = f.shared.dav.files.mapValues { it.value.copyOf() }
        val originalPuts = f.shared.dav.puts.toList()
        val route = SyncStoreId.random()
        assertNotEquals(writer.routeId, route)
        val reader = f.layer("independent-reader", requestedRoute = route)
        val candidate = reader.listCommitAssemblies().entries.single()
        assertEquals(route, candidate.route)
        assertEquals(written.assembly.copy(route = route), candidate)
        val output = ByteArrayOutputStream()
        val observed = requireNotNull(reader.readExact(request.key, request.descriptor, output))
        assertContentEquals(bytes, output.toByteArray())
        assertEquals(request, observed.request)
        assertEquals(route, observed.assembly.route)
        assertNotEquals(written.assembly.route, observed.assembly.route)
        assertEquals(written.assembly.attempt, observed.assembly.attempt)
        assertEquals(written.assembly.operationId, observed.assembly.operationId)
        assertEquals(written.assembly.descriptor, observed.assembly.descriptor)
        assertEquals(originalFiles.keys, f.shared.dav.files.keys)
        originalFiles.forEach { (path, body) -> assertContentEquals(body, f.shared.dav.files.getValue(path)) }
        assertEquals(originalPuts, f.shared.dav.puts.toList(), "Reading never republishes chunks or manifests")

        val calls = f.shared.server.requestCount
        davFailure(CiphertextStoreFailure.OPERATION_CONFLICT) { f.layer("writer", requestedRoute = route).resume(request.operationId) }
        davFailure(CiphertextStoreFailure.OPERATION_CONFLICT) { f.layer("writer", basePath = "/other/").resume(request.operationId) }
        assertEquals(calls, f.shared.server.requestCount, "Local writer custody cannot be rebound to another route or endpoint")
    }

    @Test fun aFreshRouteDeliversActualEncryptedLedgerCapsulesAndVersionsToTheExistingInbox() = chunkFixture { f ->
        val group = SyncGroupInboxTest.Group(Files.createDirectory(f.shared.root.resolve("actual-group")))
        group.publishLedger()
        val version = group.version("synthetic/one")
        group.publish(version)
        val writer = f.layer("group-writer", physicalChunkBytes = 1024)
        var cursor: CommitCursor? = null
        do {
            val page = group.backend.listCommits(cursor, 1)
            page.entries.forEach { metadata ->
                val bytes = ByteArrayOutputStream().also { group.backend.readTo(metadata.key, it, metadata.descriptor) }.toByteArray()
                writer.append(CiphertextPutRequest(metadata.key, SyncStoreId.random(), metadata.descriptor), bytes.inputStream())
            }
            cursor = page.nextCursor
        } while (cursor != null)
        val reader = f.layer("group-reader", requestedRoute = SyncStoreId.random(), physicalChunkBytes = 1024)
        assertNotEquals(writer.routeId, reader.routeId)
        assertFails { group.custody().key(group.initialKey.epoch) }
        val readPort = LanSyncGroupChunkCloudReadRoute(reader)
        val result = group.scan(group.inbox(readPort))
        assertFalse(result.trustBlocked)
        assertEquals(SyncVersionState.ADMITTED, result.history?.state(version.id))
        assertEquals(setOf(version.reference()), result.history?.heads())
        assertEquals(group.genesis.reference(), result.ledgerCheckpoint)
        assertTrue(result.observations.none { it.stage == SyncInboxStage.INVALID_OBJECT })
        assertEquals(group.initialKey.keyCommitment(), group.custody().key(group.initialKey.epoch).keyCommitment())
    }

    @Test fun anotherEndpointCannotRedirectWriterLocatorsAndItsTruncatedPartsNeverReachCallerOutput() = chunkFixture { f ->
        val bytes = ByteArray(74) { (it % 61).toByte() }; val request = davRequest(bytes)
        val written = f.layer("writer").append(request, bytes.inputStream())
        val wrong = f.layer("different-endpoint", requestedRoute = SyncStoreId.random(), basePath = "/other/")
        val candidate = written.assembly.copy(route = wrong.routeId)
        val gets = Collections.synchronizedList(mutableListOf<String>())
        f.shared.dav.overrideRequest = { incoming -> if (incoming.method == "GET") gets += incoming.requestUrl!!.encodedPath; null }
        val escaped = ByteArrayOutputStream()
        assertNull(wrong.readAssembly(candidate, CiphertextObjectMetadata(request.key, request.descriptor), escaped))
        assertEquals(0, escaped.size())
        // A real manifest on that other endpoint still cannot fetch parts from the writer endpoint.
        val manifestPath = f.path(WeakWebDavNamespace.ASSEMBLIES, written.assembly)
        f.shared.dav.files[manifestPath.replaceFirst("/sync/", "/other/")] = f.shared.dav.files.getValue(manifestPath).copyOf()
        davFailure(CiphertextStoreFailure.TRANSIENT) {
            wrong.readAssembly(candidate, CiphertextObjectMetadata(request.key, request.descriptor), escaped)
        }
        assertEquals(0, escaped.size())
        val chunks = f.shared.dav.files.keys.filter { it.startsWith("/sync/weak-chunks-v1/") }.sorted()
        chunks.forEach { path -> f.shared.dav.files[path.replaceFirst("/sync/", "/other/")] = f.shared.dav.files.getValue(path).copyOf() }
        val damaged = chunks.last().replaceFirst("/sync/", "/other/")
        f.shared.dav.files[damaged] = f.shared.dav.files.getValue(damaged).let { it.copyOf(it.size - 1) }
        davFailure(CiphertextStoreFailure.INTEGRITY) {
            wrong.readAssembly(candidate, CiphertextObjectMetadata(request.key, request.descriptor), escaped)
        }
        assertEquals(0, escaped.size())
        assertTrue(gets.isNotEmpty()); assertTrue(gets.all { it.startsWith("/other/") })
    }

    @Test fun aFreshRouteStillRejectsAChangedManifestHashBeforeEmittingAnyCiphertext() = chunkFixture { f ->
        val bytes = ByteArray(68) { it.toByte() }; val request = davRequest(bytes)
        val written = f.layer("writer").append(request, bytes.inputStream())
        val path = f.path(WeakWebDavNamespace.ASSEMBLIES, written.assembly)
        val original = f.shared.dav.files.getValue(path).copyOf()
        f.shared.dav.files[path] = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val reader = f.layer("hash-reader", requestedRoute = SyncStoreId.random())
        val candidate = reader.listAssemblies(request.key).entries.single()
        val output = ByteArrayOutputStream()
        davFailure(CiphertextStoreFailure.INTEGRITY) { reader.readAssembly(candidate, CiphertextObjectMetadata(request.key, request.descriptor), output) }
        assertEquals(0, output.size())
        f.shared.dav.files[path] = original
        assertNotNull(reader.readExact(request.key, request.descriptor, output))
        assertContentEquals(bytes, output.toByteArray())
    }

    @Test fun freshAndColdResumeEachFetchACompletedChunkOnceWhileIndependentReadbackStillFetchesItAgain() = chunkFixture { f ->
        val bytes = ByteArray(91) { (it % 67).toByte() }; val request = davRequest(bytes)
        val gets = Collections.synchronizedList(mutableListOf<String>())
        f.shared.dav.overrideRequest = { incoming ->
            if (incoming.method == "GET") gets += incoming.requestUrl!!.encodedPath
            null
        }
        val first = f.layer("writer").append(request, bytes.inputStream())
        val chunks = f.shared.dav.files.keys.filter { "/weak-chunks-v1/" in it }.toSet()
        assertEquals(6, chunks.size)
        chunks.forEach { assertEquals(1, gets.count { path -> path == it }, "Each new chunk feeds the whole hash from its first verified GET") }
        val originalPuts = f.shared.dav.puts.toList()
        val originalFiles = f.shared.dav.files.mapValues { it.value.copyOf() }
        gets.clear()
        val cold = f.layer("writer").resume(request.operationId)
        assertEquals(first.assembly, cold.assembly)
        chunks.forEach { assertEquals(1, gets.count { path -> path == it }, "Cold resume must reuse its one verified GET") }
        assertEquals(originalPuts, f.shared.dav.puts.toList())
        originalFiles.forEach { (path, body) -> assertContentEquals(body, f.shared.dav.files.getValue(path)) }
        gets.clear()
        val output = ByteArrayOutputStream()
        assertNotNull(f.layer("reader").readAssembly(cold.assembly, CiphertextObjectMetadata(request.key, request.descriptor), output))
        assertContentEquals(bytes, output.toByteArray())
        chunks.forEach { assertEquals(1, gets.count { path -> path == it }, "An independent assembly read still verifies every remote chunk") }
        assertEquals(originalPuts, f.shared.dav.puts.toList())
    }

    @Test fun interruptedUploadColdReopenReusesConfirmedChunksAndPublishesAssemblyLast() = chunkFixture { f ->
        val bytes = ByteArray(103) { (it % 71).toByte() }
        val request = davRequest(bytes, CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()))
        val writer = f.layer("writer")
        val plan = writer.prepare(request, bytes.inputStream())
        assertEquals(7L, plan.chunkCount)
        val local = f.retainedChunks("writer")
        val attempted = Collections.synchronizedList(mutableListOf<String>())
        val chunkPuts = AtomicInteger()
        f.shared.dav.overrideRequest = { incoming ->
            if (incoming.method == "PUT") {
                val path = incoming.requestUrl!!.encodedPath; attempted += path
                if (path.contains("/weak-chunks-v1/") && chunkPuts.incrementAndGet() == 3)
                    MockResponse().setResponseCode(503) else null
            } else null
        }
        davFailure(CiphertextStoreFailure.TRANSIENT) { writer.resume(request.operationId) }
        val completed = f.shared.dav.files.mapValues { it.value.copyOf() }
        assertEquals(2, completed.size)
        assertTrue(completed.keys.all { "/weak-chunks-v1/" in it })
        f.shared.dav.overrideRequest = { incoming ->
            if (incoming.method == "PUT") attempted += incoming.requestUrl!!.encodedPath
            null
        }
        val observed = f.layer("writer").resume(request.operationId)
        assertEquals(request, observed.request)
        assertEquals(7L, observed.chunkCount)
        completed.forEach { (path, body) ->
            assertContentEquals(body, f.shared.dav.files.getValue(path))
            assertEquals(1, attempted.count { it == path }, "Complete chunks must not be PUT again")
        }
        assertEquals(8, attempted.count { "/weak-chunks-v1/" in it }, "Seven chunks plus one failed unique attempt")
        assertTrue(attempted.last().contains("/weak-assemblies-v1/"))
        assertEquals(attempted.size, attempted.toSet().size)
        local.forEach { (path, body) -> assertContentEquals(body, Files.readAllBytes(path)) }
        val reader = f.layer("reader")
        assertEquals(observed.assembly, reader.listCommitAssemblies().entries.single())
        val header = reader.inspectAssembly(observed.assembly)!!
        assertEquals(request, header.originalRequest); assertEquals(7L, header.chunkCount); assertEquals(17L, header.chunkBytes)
        val output = ByteArrayOutputStream()
        val read = reader.readExact(request.key, request.descriptor, output)
        assertEquals(request, read?.request)
        assertContentEquals(bytes, output.toByteArray())
        assertTrue(f.shared.store("ordinary-reader").listCandidates(request.key).entries.isEmpty(), "Assemblies do not enter the full-ciphertext namespace")
    }

    @Test fun damagedRemoteChunkAloneGetsANewAttemptAndTheOldAssemblyCannotEmitBadBytes() = chunkFixture { f ->
        val bytes = ByteArray(91) { (it % 83).toByte() }; val request = davRequest(bytes)
        val first = f.layer("writer").append(request, bytes.inputStream())
        val chunkPaths = f.shared.dav.files.keys.filter { "/weak-chunks-v1/" in it }.sorted()
        val damagedPath = chunkPaths.first()
        val bad = f.shared.dav.files.getValue(damagedPath).let { it.copyOf(it.size - 3) }
        f.shared.dav.files[damagedPath] = bad
        val oldAssemblyPath = f.path(WeakWebDavNamespace.ASSEMBLIES, first.assembly)
        val oldManifest = f.shared.dav.files.getValue(oldAssemblyPath).copyOf()
        val puts = Collections.synchronizedList(mutableListOf<String>())
        f.shared.dav.overrideRequest = { incoming -> if (incoming.method == "PUT") puts += incoming.requestUrl!!.encodedPath; null }
        val repaired = f.layer("writer").resume(request.operationId)
        assertEquals(1, puts.count { "/weak-chunks-v1/" in it })
        assertNotEquals(first.assembly, repaired.assembly)
        assertContentEquals(bad, f.shared.dav.files.getValue(damagedPath), "A damaged prior attempt is retained")
        assertContentEquals(oldManifest, f.shared.dav.files.getValue(oldAssemblyPath))
        assertTrue(chunkPaths.none { it in puts })
        val escaped = ByteArrayOutputStream()
        davFailure(CiphertextStoreFailure.INTEGRITY) {
            f.layer("reader").readAssembly(first.assembly, CiphertextObjectMetadata(request.key, request.descriptor), escaped)
        }
        assertEquals(0, escaped.size())
        val restored = ByteArrayOutputStream()
        assertNotNull(f.layer("reader").readAssembly(repaired.assembly,
            CiphertextObjectMetadata(request.key, request.descriptor), restored))
        assertContentEquals(bytes, restored.toByteArray())
    }

    @Test fun validIndividualChunksInTheWrongOrderFailTheWholeDescriptorBeforeOutput() = chunkFixture { f ->
        val bytes = ByteArray(75) { it.toByte() }; val request = davRequest(bytes)
        val good = f.layer("writer").append(request, bytes.inputStream())
        val wire = f.shared.dav.files.getValue(f.path(WeakWebDavNamespace.ASSEMBLIES, good.assembly))
        val manifest = wire.copyOfRange(WebDavStoreRecord.HEADER_BYTES.toInt(), wire.size)
        val header = "CCB-WEAK-DAV-ASSEMBLY/1\n".toByteArray(Charsets.US_ASCII).size + 126
        val entry = 182 // index8 + route32 + framed request110 + nonce32
        val first = manifest.copyOfRange(header + 8, header + entry)
        val second = manifest.copyOfRange(header + entry + 8, header + 2 * entry)
        second.copyInto(manifest, header + 8); first.copyInto(manifest, header + entry + 8)
        val changed = CiphertextPutRequest(request.key, SyncStoreId.random(), WebDavStoreRecord.checksum(manifest))
        val fake = f.assemblyStore("alternate").appendAttempt(changed, manifest.inputStream())
        val reader = f.layer("reader", requestedRoute = SyncStoreId.random())
        val candidate = reader.listAssemblies(request.key).entries.single { it.attempt == fake.candidate.attempt }
        assertEquals(request, reader.inspectAssembly(candidate)?.originalRequest,
            "A readable untrusted header does not establish that its chunks reconstruct the object")
        val output = ByteArrayOutputStream()
        davFailure(CiphertextStoreFailure.INTEGRITY) {
            reader.readAssembly(candidate, CiphertextObjectMetadata(request.key, request.descriptor), output)
        }
        assertEquals(0, output.size())
        assertNotNull(reader.readExact(request.key, request.descriptor, output))
        assertContentEquals(bytes, output.toByteArray())
    }

    @Test fun interruptedLocalPreparationKeepsCompletedPartsAndReopenCanFinishWithoutChangingTheRequest() = chunkFixture { f ->
        val bytes = ByteArray(91) { (it % 59).toByte() }; val request = davRequest(bytes)
        var offset = 0
        val interrupted = object : InputStream() {
            override fun read(): Int {
                if (offset >= 40) throw InterruptedIOException("synthetic preparation interrupted")
                return if (offset == bytes.size) -1 else bytes[offset++].toInt() and 255
            }
            override fun read(target: ByteArray, start: Int, count: Int): Int {
                if (offset >= 40) throw InterruptedIOException("synthetic preparation interrupted")
                val length = minOf(count, 40 - offset, bytes.size - offset)
                bytes.copyInto(target, start, offset, offset + length); offset += length; return length
            }
        }
        assertFailsWith<InterruptedIOException> { f.layer("writer").prepare(request, interrupted) }
        assertTrue(f.shared.dav.files.isEmpty())
        val retained = f.retainedChunks("writer"); assertEquals(2, retained.size)
        val reopened = f.layer("writer")
        reopened.prepare(request, bytes.inputStream())
        retained.forEach { (path, body) -> assertContentEquals(body, Files.readAllBytes(path)) }
        val receipt = reopened.resume(request.operationId)
        assertEquals(request, receipt.request)
        val before = f.shared.server.requestCount
        val inactive = IllegalStateException("SYNC_SYNTHETIC_ROUTE_DISABLED")
        assertSame(inactive, assertFailsWith<IllegalStateException> { reopened.resume(request.operationId) { throw inactive } })
        assertEquals(before, f.shared.server.requestCount)
    }
}

private class ChunkFixture(val shared: WeakFixture) : AutoCloseable {
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
    private fun root(name: String): Path = shared.root.resolve("chunk-$name").also { if (!Files.exists(it)) Files.createDirectory(it) }
    private fun transport(basePath: String = "/sync/") = WebDavHttpTransport(shared.server.url(basePath).newBuilder().host("127.0.0.1").build(), client,
        { "Bearer synthetic-only" }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
    fun layer(name: String, requestedRoute: SyncStoreId = shared.route, basePath: String = "/sync/",
        physicalChunkBytes: Long = 17) = WeakWebDavChunkAssembly(transport(basePath), root(name), requestedRoute, chunkBytes = physicalChunkBytes,
        budget = WebDavIoBudget(bufferBytes = 7, minimumFreeBytes = 0))
    fun assemblyStore(name: String) = WeakWebDavAttemptStore(transport(), root(name), shared.route,
        WebDavIoBudget(minimumFreeBytes = 0), WeakWebDavNamespace.ASSEMBLIES)
    fun path(namespace: WeakWebDavNamespace, candidate: WeakWebDavCandidate) =
        "/sync/${namespace.collection}/${if (candidate.key.kind == CiphertextObjectKind.BLOB) "objects" else "commits"}/${candidate.key.id.value}/${candidate.descriptor.sha256.hex}/${candidate.descriptor.length}-${candidate.operationId.value}-${candidate.attempt.value}.bin"
    fun retainedChunks(name: String): Map<Path, ByteArray> = Files.walk(root(name)).use { stream ->
        stream.filter { it.fileName.toString().endsWith(".part") }.toList().associateWith(Files::readAllBytes)
    }
    override fun close() { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
}

private fun chunkFixture(work: (ChunkFixture) -> Unit) = weakFixture { shared -> ChunkFixture(shared).use(work) }
