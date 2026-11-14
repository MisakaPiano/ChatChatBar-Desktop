package com.example.chatbar.domain.syncStore.webdav

import com.example.chatbar.domain.syncStore.*
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
        assertEquals(request, f.layer("reader").inspectAssembly(fake.candidate)?.originalRequest,
            "A readable untrusted header does not establish that its chunks reconstruct the object")
        val output = ByteArrayOutputStream()
        davFailure(CiphertextStoreFailure.INTEGRITY) {
            f.layer("reader").readAssembly(fake.candidate, CiphertextObjectMetadata(request.key, request.descriptor), output)
        }
        assertEquals(0, output.size())
        assertNotNull(f.layer("reader").readExact(request.key, request.descriptor, output))
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
    private fun transport() = WebDavHttpTransport(shared.server.url("/sync/").newBuilder().host("127.0.0.1").build(), client,
        { "Bearer synthetic-only" }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
    fun layer(name: String) = WeakWebDavChunkAssembly(transport(), root(name), shared.route, chunkBytes = 17,
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
