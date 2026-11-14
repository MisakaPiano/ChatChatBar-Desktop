package com.example.chatbar.domain.syncStore.webdav

import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import kotlin.test.*

class WeakWebDavAttemptStoreTest {
    @Test fun oneVerifiedGetCanFeedLocalCopiesWithoutASecondRequestOrASecondObservationTime() = weakFixture { f ->
        val bytes = ByteArray(80_019) { (it % 193).toByte() }; val request = davRequest(bytes)
        val gets = AtomicInteger()
        f.dav.overrideRequest = { if (it.method == "GET") gets.incrementAndGet(); null }
        val store = f.store("readback")
        val readback = store.appendAttemptReadback(request, bytes.inputStream())
        val observation = readback.observation
        val observedAt = observation.observedAtMillis
        assertEquals(WeakWebDavObservationOrigin.NEW_ATTEMPT_READBACK, observation.origin)
        assertEquals(1, gets.get()); assertEquals(1L, f.preparations("readback"))
        val path = f.path(observation.candidate)
        val original = f.dav.files.getValue(path)
        f.dav.files[path] = original.copyOf(original.size - 1)
        try {
            val before = f.server.requestCount
            repeat(2) {
                val output = ByteArrayOutputStream(); readback.copyTo(output)
                assertContentEquals(bytes, output.toByteArray())
                assertSame(observation, readback.observation)
                assertEquals(observedAt, readback.observation.observedAtMillis)
            }
            assertEquals(before, f.server.requestCount); assertEquals(1, gets.get())
            assertEquals(1, f.observations("readback"))
            // A later independent provider read still detects mutation; the local copy claims only
            // the one earlier complete GET, never freshness of the provider's current object.
            val escaped = ByteArrayOutputStream()
            davFailure(CiphertextStoreFailure.INTEGRITY) { store.readCandidate(observation.candidate, escaped) }
            assertEquals(0, escaped.size()); assertEquals(2, gets.get())
        } finally { readback.close() }
        assertEquals(0L, f.preparations("readback"))
        assertFailsWith<IllegalStateException> { readback.copyTo(ByteArrayOutputStream()) }
        assertEquals(1, f.dav.puts.size)
    }

    @Test fun sinkAndLiveGuardFailuresCannotRetryAnotherCandidateOrConsumeAColdSource() = weakFixture { f ->
        val bytes = ByteArray(40_013) { (it % 173).toByte() }; val request = davRequest(bytes)
        f.store("writer").appendAttempt(request, bytes.inputStream())
        val unreadable = object : InputStream() { override fun read(): Int = error("Cold retry must use retained ciphertext") }
        val source = f.store("writer")
        val sinkFailure = CiphertextStoreException(CiphertextStoreFailure.INTEGRITY)
        source.appendAttemptReadback(request, unreadable).use { readback ->
            val before = f.server.requestCount
            val output = object : OutputStream() {
                var writes = 0
                override fun write(value: Int) { writes++; throw sinkFailure }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { writes++; throw sinkFailure }
            }
            assertSame(sinkFailure, assertFailsWith<CiphertextStoreException> { readback.copyTo(output) })
            assertEquals(1, output.writes); assertEquals(before, f.server.requestCount)
        }
        assertEquals(0L, f.preparations("writer"))
        val received = AtomicBoolean()
        val guardFailure = CiphertextStoreException(CiphertextStoreFailure.TRANSIENT)
        val before = f.server.requestCount
        f.dav.overrideRequest = { if (it.method == "GET") received.set(true); null }
        assertSame(guardFailure, assertFailsWith<CiphertextStoreException> {
            source.appendAttemptReadback(request, unreadable) { if (received.get()) throw guardFailure }.close()
        })
        assertEquals(before + 1, f.server.requestCount)
        assertEquals(0L, f.preparations("writer")); assertEquals(1, f.dav.puts.size)
        f.dav.overrideRequest = null
        var blocked = false
        source.appendAttemptReadback(request, unreadable) { if (blocked) throw guardFailure }.use { readback ->
            blocked = true
            val requests = f.server.requestCount; val escaped = ByteArrayOutputStream()
            assertSame(guardFailure, assertFailsWith<CiphertextStoreException> { readback.copyTo(escaped) })
            assertEquals(0, escaped.size()); assertEquals(requests, f.server.requestCount)
        }
        assertEquals(0L, f.preparations("writer")); assertEquals(1, f.dav.puts.size)
    }

    @Test fun partialGetCannotReturnAReadbackHandleOrEmitAnyCallerBytes() = weakFixture { f ->
        val bytes = ByteArray(20_017) { (it % 181).toByte() }; val request = davRequest(bytes)
        val output = ByteArrayOutputStream(); var returned = false
        f.dav.partialNextPut = true
        davFailure(CiphertextStoreFailure.INTEGRITY) {
            f.store("partial-handle").appendAttemptReadback(request, bytes.inputStream()).use {
                returned = true; it.copyTo(output)
            }
        }
        assertFalse(returned); assertEquals(0, output.size()); assertEquals(0, f.observations("partial-handle"))
        assertEquals(0L, f.preparations("partial-handle")); assertEquals(1, f.dav.files.size)
    }

    @Test fun concurrentClientsAppendDistinctAttemptsAndRetainAllConflictingClaims() = weakFixture { f ->
        val key = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random())
        val bytes = ByteArray(200_003) { (it % 197).toByte() }
        val request = davRequest(bytes, key)
        val first = f.store("first"); val second = f.store("second")
        val ready = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<WeakWebDavObservation> { check(ready.await(5, TimeUnit.SECONDS)); first.appendAttempt(request, bytes.inputStream()) }
            val b = pool.submit<WeakWebDavObservation> { check(ready.await(5, TimeUnit.SECONDS)); second.appendAttempt(request, bytes.inputStream()) }
            ready.countDown()
            val one = a.get(15, TimeUnit.SECONDS); val two = b.get(15, TimeUnit.SECONDS)
            assertNotEquals(one.candidate.attempt, two.candidate.attempt)
            assertEquals(request.descriptor, one.candidate.descriptor)
            assertEquals(2, f.dav.files.size)
            assertEquals(2, first.listCandidates(key).entries.size)
            val alternate = byteArrayOf(3, 7, 9)
            // A separate weak client can make a conflicting claim; it cannot overwrite either attempt.
            f.store("third").appendAttempt(request.copy(descriptor = WebDavStoreRecord.checksum(alternate)), alternate.inputStream())
            val claims = first.listCommitCandidates()
            assertEquals(3, claims.entries.size)
            assertEquals(setOf(key), claims.claimedDescriptorConflicts)
            assertEquals(setOf(request.operationId), claims.claimedOperationConflicts)
            assertEquals(3, f.dav.puts.toSet().size)
            assertEquals(3, f.dav.files.size)
        } finally { ready.countDown(); pool.shutdownNow(); check(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun completeAttemptSurvivesPartialPutAndRetryNeverPutsAnOldLocator() = weakFixture { f ->
        val bytes = ByteArray(80_003) { (it % 173).toByte() }; val request = davRequest(bytes)
        val good = f.store("good").appendAttempt(request, bytes.inputStream())
        val original = f.dav.files.mapValues { it.value.copyOf() }
        val retry = request.copy(operationId = SyncStoreId.random())
        f.dav.partialNextPut = true
        davFailure(CiphertextStoreFailure.INTEGRITY) { f.store("partial").appendAttempt(retry, bytes.inputStream()) }
        val failedFiles = f.dav.files.mapValues { it.value.copyOf() }
        assertEquals(2, failedFiles.size)
        assertEquals(0, f.observations("partial"), "A partial candidate creates no readback observation")
        val recovered = f.store("partial").appendAttempt(retry, bytes.inputStream())
        assertNotEquals(good.candidate.attempt, recovered.candidate.attempt)
        assertEquals(3, f.dav.files.size)
        original.forEach { (path, wire) -> assertContentEquals(wire, f.dav.files.getValue(path)) }
        failedFiles.forEach { (path, wire) -> assertContentEquals(wire, f.dav.files.getValue(path)) }
        assertEquals(f.dav.puts.size, f.dav.puts.toSet().size)
        val output = ByteArrayOutputStream()
        assertNotNull(f.store("reader").readExact(request.key, request.descriptor, output))
        assertContentEquals(bytes, output.toByteArray())
    }

    @Test fun lostReplyAndColdRetryReadBackTheOldLocatorWithoutReadingOrReencryptingSource() = weakFixture { f ->
        val bytes = ByteArray(64_019) { (it % 239).toByte() }; val request = davRequest(bytes)
        f.dav.loseNextReply = true
        davFailure(CiphertextStoreFailure.TRANSIENT) { f.store("writer").appendAttempt(request, bytes.inputStream()) }
        val retained = f.dav.files.mapValues { it.value.copyOf() }
        val unreadable = object : InputStream() { override fun read(): Int = error("Retained remote attempt needs no source read") }
        val observed = f.store("writer").appendAttempt(request, unreadable)
        assertEquals(WeakWebDavObservationOrigin.EXISTING_ATTEMPT_READBACK, observed.origin)
        assertEquals(1, f.dav.puts.size)
        assertEquals(retained.keys, f.dav.files.keys)
        retained.forEach { (path, wire) -> assertContentEquals(wire, f.dav.files.getValue(path)) }
        val before = f.server.requestCount
        davFailure(CiphertextStoreFailure.OPERATION_CONFLICT) {
            f.store("writer").appendAttempt(request.copy(key = request.key.copy(kind = CiphertextObjectKind.COMMIT)), unreadable)
        }
        assertEquals(before, f.server.requestCount)
    }

    @Test fun badFirstMutableOrPartialCandidateCannotOccupyTheSignedBlobDescriptor() = weakFixture { f ->
        val bytes = ByteArray(30_011) { (it % 131).toByte() }; val request = davRequest(bytes)
        val good = f.store("writer").appendAttempt(request, bytes.inputStream())
        val folder = f.path(good.candidate).substringBeforeLast('/') + "/"
        val badPath = folder + "${bytes.size}-${request.operationId.value}-${"0".repeat(32)}.bin"
        val broken = davRecord(request.key, bytes).also { it[it.lastIndex] = (it.last() + 1).toByte() }
        f.dav.files[badPath] = broken
        f.dav.collections += folder
        val reader = f.store("reader")
        val page = reader.listCandidates(request.key)
        assertEquals(2, page.entries.size)
        repeat(2) {
            val output = ByteArrayOutputStream()
            assertNotNull(reader.readExact(request.key, request.descriptor, output))
            assertContentEquals(bytes, output.toByteArray())
        }
        val original = f.dav.files.getValue(f.path(good.candidate))
        f.dav.files[f.path(good.candidate)] = original.copyOf(original.size - 1)
        val escaped = ByteArrayOutputStream()
        assertFailsWith<CiphertextStoreException> { reader.readExact(request.key, request.descriptor, escaped) }
        assertEquals(0, escaped.size())
        assertContentEquals(broken, f.dav.files.getValue(badPath))
        f.dav.files[f.path(good.candidate)] = original
        assertNotNull(reader.readExact(request.key, request.descriptor, ByteArrayOutputStream()))
    }

    @Test fun listingRetainsVariantsRescansLowerIdsAndBindsCursorsToRouteAndLogicalKey() = weakFixture { f ->
        val key = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.parse("8".repeat(32)))
        val store = f.store("writer")
        val bytes = byteArrayOf(1, 2, 3)
        repeat(3) { store.appendAttempt(davRequest(bytes, key), bytes.inputStream()) }
        val first = store.listCommitCandidates(pageSize = 1)
        assertEquals(1, first.entries.size); assertNotNull(first.nextCursor)
        val seen = first.entries.toMutableList(); var cursor = first.nextCursor
        while (cursor != null) { val next = store.listCommitCandidates(cursor, 1); seen += next.entries; cursor = next.nextCursor }
        assertEquals(3, seen.size); assertEquals(3, seen.map { it.attempt }.toSet().size)
        val lower = key.copy(id = SyncStoreId.parse("1".repeat(32)))
        store.appendAttempt(davRequest(bytes, lower), bytes.inputStream())
        assertEquals(lower, store.listCommitCandidates(pageSize = 1).entries.single().key)
        davFailure(CiphertextStoreFailure.INVALID_CURSOR) { store.listCandidates(key, first.nextCursor, 1) }
        val byKey = store.listCandidates(key, pageSize = 1)
        davFailure(CiphertextStoreFailure.INVALID_CURSOR) { store.listCandidates(lower, byKey.nextCursor, 1) }
        f.dav.overrideRequest = { if (it.method == "PROPFIND") MockResponse().setResponseCode(403) else null }
        davFailure(CiphertextStoreFailure.PERMISSION) { store.listCommitCandidates() }
    }

    @Test fun httpFailuresAndWrongLocalEndpointOrOperationNeverClaimAnObservation() = weakFixture { f ->
        val bytes = byteArrayOf(5, 6); val request = davRequest(bytes)
        for ((status, failure) in listOf(401 to CiphertextStoreFailure.AUTHENTICATION, 403 to CiphertextStoreFailure.PERMISSION,
            507 to CiphertextStoreFailure.QUOTA, 500 to CiphertextStoreFailure.TRANSIENT)) {
            f.dav.overrideRequest = { if (it.method == "PUT") MockResponse().setResponseCode(status) else null }
            davFailure(failure) { f.store("status-$status").appendAttempt(request, bytes.inputStream()) }
            assertTrue(f.dav.files.isEmpty())
        }
        f.dav.overrideRequest = null
        val store = f.store("bound")
        store.appendAttempt(request, bytes.inputStream())
        val before = f.server.requestCount
        davFailure(CiphertextStoreFailure.OPERATION_CONFLICT) { f.store("bound", SyncStoreId.random()) }
        davFailure(CiphertextStoreFailure.OPERATION_CONFLICT) { f.store("bound", basePath = "/other/") }
        assertEquals(before, f.server.requestCount)
        val noBytes = object : InputStream() { override fun read(): Int = error("Unsupported Long length must refuse first") }
        davFailure(CiphertextStoreFailure.UNSUPPORTED) {
            store.appendAttempt(request.copy(operationId = SyncStoreId.random(), descriptor = request.descriptor.copy(length = Long.MAX_VALUE)), noBytes)
        }
        davFailure(CiphertextStoreFailure.QUOTA) {
            f.store("space-budget", budget = WebDavIoBudget(minimumFreeBytes = Long.MAX_VALUE / 2)).appendAttempt(request, noBytes)
        }
        val interrupted = f.store("interrupted")
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<java.io.InterruptedIOException> { interrupted.appendAttempt(request, noBytes) }
        } finally { Thread.interrupted() }
        assertEquals(before, f.server.requestCount)
    }

    @Test fun actualMultiSegmentAeadRoundTripsButWeakObservationDoesNotMarkAnOutboxUploaded() = weakFixture { f ->
        val cipher = SyncStreamingCipher.create()
        val context = SyncEncryptionContext(cipher.group, cipher.epoch, CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random()))
        val plain = ByteArray(2 * 1024 * 1024 + 33) { (it % 211).toByte() }
        val source = f.root.resolve("plain.bin"); Files.write(source, plain)
        val queueRoot = Files.createDirectory(f.root.resolve("queue"))
        val queue = SyncEncryptedOutbox(queueRoot, cipher)
        val entry = queue.prepare(SyncStoreId.random(), context, source)
        val retainedPath = queueRoot.resolve(entry.request.operationId.value).resolve("ciphertext.bin")
        val retained = Files.readAllBytes(retainedPath)
        Files.newInputStream(retainedPath).use { f.store("writer").appendAttempt(entry.request, it) }
        val encrypted = ByteArrayOutputStream()
        f.store("reader").readExact(entry.request.key, entry.request.descriptor, encrypted)
        assertContentEquals(retained, encrypted.toByteArray())
        val decode = Files.createDirectory(f.root.resolve("decode"))
        cipher.decrypt(encrypted.toByteArray().inputStream(), context, decode).use {
            assertContentEquals(plain, Files.readAllBytes(it.path))
        }
        assertFalse(queue.entry(entry.request.operationId)!!.uploaded)
        assertContentEquals(retained, Files.readAllBytes(retainedPath))
    }
}

internal class WeakFixture(val root: Path) : AutoCloseable {
    val dav = WeakSyntheticDav()
    val server = MockWebServer().apply { dispatcher = dav; start(InetAddress.getByName("127.0.0.1"), 0) }
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(5, TimeUnit.SECONDS).build()
    val route = SyncStoreId.random()
    fun store(name: String, requestedRoute: SyncStoreId = route,
        budget: WebDavIoBudget = WebDavIoBudget(minimumFreeBytes = 0), basePath: String = "/sync/"): WeakWebDavAttemptStore {
        val local = root.resolve(name)
        if (!Files.exists(local)) Files.createDirectory(local)
        return WeakWebDavAttemptStore(WebDavHttpTransport(server.url(basePath).newBuilder().host("127.0.0.1").build(), client,
            { "Bearer synthetic-only" }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP), local, requestedRoute, budget)
    }
    fun observations(name: String): Int = Files.walk(root.resolve(name).resolve("weak-observations")).use { stream ->
        stream.filter { it.fileName.toString().endsWith(".obj") }.count().toInt()
    }
    fun preparations(name: String): Long = Files.list(root.resolve(name)).use { stream ->
        stream.filter { it.fileName.toString().endsWith(".preparing") }.count()
    }
    fun path(c: WeakWebDavCandidate) = "/sync/weak-attempts-v1/${if (c.key.kind == CiphertextObjectKind.BLOB) "objects" else "commits"}/${c.key.id.value}/${c.descriptor.sha256.hex}/${c.descriptor.length}-${c.operationId.value}-${c.attempt.value}.bin"
    override fun close() { server.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
}

internal class WeakSyntheticDav : Dispatcher() {
    val files = ConcurrentHashMap<String, ByteArray>()
    val collections = ConcurrentHashMap.newKeySet<String>().apply { add("/sync/") }
    val puts = Collections.synchronizedList(mutableListOf<String>())
    @Volatile var partialNextPut = false
    @Volatile var loseNextReply = false
    @Volatile var overrideRequest: ((RecordedRequest) -> MockResponse?)? = null
    override fun dispatch(request: RecordedRequest): MockResponse {
        overrideRequest?.invoke(request)?.let { return it }
        val path = request.requestUrl!!.encodedPath
        return when (request.method) {
            "MKCOL" -> MockResponse().setResponseCode(if (collections.add(path)) 201 else 405)
            "PUT" -> {
                // This fixture intentionally ignores If-None-Match and exposes partial writes.
                val full = request.body.readByteArray()
                val stored = if (partialNextPut) { partialNextPut = false; full.copyOf(full.size / 2) } else full
                files[path] = stored; puts += path
                MockResponse().setResponseCode(201).apply {
                    if (loseNextReply) { loseNextReply = false; setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
                }
            }
            "GET" -> files[path]?.let { MockResponse().setResponseCode(200).setBody(Buffer().write(it)) }
                ?: MockResponse().setResponseCode(404)
            "PROPFIND" -> {
                if (path !in collections) MockResponse().setResponseCode(404) else {
                    val children = if (request.getHeader("Depth") == "1") {
                        (collections.filter { it != path && it.startsWith(path) && '/' !in it.removePrefix(path).trimEnd('/') }.map { it to true } +
                            files.keys.filter { it.startsWith(path) && '/' !in it.removePrefix(path) }.map { it to false }).sortedByDescending { it.first }
                    } else emptyList()
                    MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\">" + davXmlEntry(path, true) +
                        children.joinToString("") { davXmlEntry(it.first, it.second) } + "</d:multistatus>")
                }
            }
            else -> MockResponse().setResponseCode(405)
        }
    }
}

internal fun weakFixture(work: (WeakFixture) -> Unit) {
    val root = Files.createTempDirectory("ccb-weak-dav-synthetic-").toRealPath()
    try { WeakFixture(root).use(work) } finally { check(root.toFile().deleteRecursively()) }
}
