package com.example.chatbar.domain.syncStore.webdav

import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import kotlin.test.*

class WebDavCiphertextStoreTest {
    @Test fun fullStreamThenReopenedSecondAndThirdMetadataRoundsUseFreshHeadsAndZeroGetBytes() = davFixture { f ->
        val bytes = ByteArray(4 * 1024 * 1024 + 19) { (it % 241).toByte() }
        val request = davRequest(bytes)
        assertEquals(CiphertextPutDisposition.CREATED, f.store().put(request, bytes.inputStream()).disposition)
        val baseline = f.receivedGetBytes.get()
        assertTrue(baseline >= bytes.size.toLong() + WebDavStoreRecord.HEADER_BYTES)
        repeat(2) {
            val before = f.receivedGetBytes.get()
            val reopened = f.store()
            assertEquals(request.descriptor, reopened.metadata(request.key)?.descriptor)
            assertEquals(before, f.receivedGetBytes.get(), "A fresh HEAD/cache hit must not download an existing large payload")
        }
        assertTrue(f.dav.heads >= 3)
        val beforeRead = f.receivedGetBytes.get()
        val output = ByteArrayOutputStream()
        f.store().readTo(request.key, output, request.descriptor)
        assertContentEquals(bytes, output.toByteArray())
        assertEquals(bytes.size.toLong() + WebDavStoreRecord.HEADER_BYTES, f.receivedGetBytes.get() - beforeRead)
        assertEquals(CiphertextPutDisposition.ALREADY_PRESENT, f.store().put(request, bytes.inputStream()).disposition)
        assertEquals(1, f.dav.objectCreates)
    }

    @Test fun weakOrAbsentEtagsConservativelyDownloadAndVerifyEveryMetadataRound() {
        for (etag in listOf<String?>("W/\"weak\"", null)) davFixture { f ->
            f.dav.etagOverride = etag
            f.dav.overrideEtags = true
            val bytes = ByteArray(130_001) { (it % 193).toByte() }
            val request = davRequest(bytes)
            f.store().put(request, bytes.inputStream())
            repeat(2) {
                val before = f.receivedGetBytes.get()
                assertEquals(request.descriptor, f.store().metadata(request.key)?.descriptor)
                assertEquals(bytes.size.toLong() + WebDavStoreRecord.HEADER_BYTES, f.receivedGetBytes.get() - before)
            }
        }
    }

    @Test fun cachedObservationCannotBypassAccessFailureMissingObjectOrChangedRepresentation() = davFixture { f ->
        val bytes = byteArrayOf(0x64, 0x22, 0x31)
        val request = davRequest(bytes)
        f.store().put(request, bytes.inputStream())
        val objectPath = davPath(request.key)
        f.dav.overrideRequest = { if (it.method == "HEAD") MockResponse().setResponseCode(403) else null }
        davFailure(CiphertextStoreFailure.PERMISSION) { f.store().metadata(request.key) }
        f.dav.overrideRequest = null
        val original = f.dav.files.remove(objectPath)!!
        assertNull(f.store().metadata(request.key))
        val replacement = byteArrayOf(0x64, 0x23, 0x31)
        f.dav.files[objectPath] = davRecord(request.key, replacement)
        davFailure(CiphertextStoreFailure.OBJECT_CONFLICT) { f.store().metadata(request.key) }
        f.dav.files[objectPath] = original
        assertEquals(request.descriptor, f.store().metadata(request.key)?.descriptor)
    }

    @Test fun changedEtagWithSameBytesIsFullyVerifiedOnceThenCached() = davFixture { f ->
        val bytes = ByteArray(30_003) { (it % 127).toByte() }; val request = davRequest(bytes)
        f.store().put(request, bytes.inputStream())
        f.dav.overrideEtags = true; f.dav.etagOverride = "\"fresh-validator\""
        val before = f.receivedGetBytes.get()
        assertEquals(request.descriptor, f.store().metadata(request.key)?.descriptor)
        assertEquals(bytes.size.toLong() + WebDavStoreRecord.HEADER_BYTES, f.receivedGetBytes.get() - before)
        val second = f.receivedGetBytes.get()
        assertEquals(request.descriptor, f.store().metadata(request.key)?.descriptor)
        assertEquals(second, f.receivedGetBytes.get())
    }

    @Test fun fullyVerifiedChunkedGetCanSeedTheStrongEtagObservation() = davFixture { f ->
        val bytes = ByteArray(18_003) { (it % 191).toByte() }; val request = davRequest(bytes)
        f.dav.overrideRequest = { recorded ->
            if (recorded.method == "GET" && recorded.requestUrl!!.encodedPath == davPath(request.key)) {
                f.dav.files[davPath(request.key)]?.let { wire -> MockResponse().setResponseCode(200)
                    .setChunkedBody(Buffer().write(wire), 1024)
                    .setHeader("ETag", "\"${WebDavStoreRecord.checksum(wire).sha256.hex}\"") }
            } else null
        }
        f.store().put(request, bytes.inputStream())
        val before = f.receivedGetBytes.get()
        assertEquals(request.descriptor, f.store().metadata(request.key)?.descriptor)
        assertEquals(before, f.receivedGetBytes.get())
    }

    @Test fun lostSuccessfulPutReplyRetriesExactOperationWithoutOverwritingRemoteBytes() = davFixture { f ->
        val bytes = ByteArray(64_001) { (it % 197).toByte() }; val request = davRequest(bytes)
        f.dav.loseObjectReplyOnce = true
        davFailure(CiphertextStoreFailure.TRANSIENT) { f.store().put(request, bytes.inputStream()) }
        val retained = f.dav.files.getValue(davPath(request.key)).copyOf()
        assertEquals(CiphertextPutDisposition.ALREADY_PRESENT, f.store().put(request, bytes.inputStream()).disposition)
        assertContentEquals(retained, f.dav.files.getValue(davPath(request.key)))
        assertEquals(1, f.dav.objectCreates)
        val changed = byteArrayOf(0x71)
        davFailure(CiphertextStoreFailure.OPERATION_CONFLICT) {
            f.store().put(request.copy(descriptor = WebDavStoreRecord.checksum(changed)), changed.inputStream())
        }
        davFailure(CiphertextStoreFailure.OBJECT_CONFLICT) { f.store().put(davRequest(changed, request.key), changed.inputStream()) }
    }

    @Test fun remoteOperationBindingAlsoRejectsReuseFromAnIndependentPrivateRoot() = davFixture { f ->
        val bytes = byteArrayOf(0x32, 0x63); val request = davRequest(bytes)
        f.store().put(request, bytes.inputStream())
        val independent = Files.createDirectory(f.root.resolve("independent"))
        val changed = request.copy(key = CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random()))
        davFailure(CiphertextStoreFailure.OPERATION_CONFLICT) {
            f.store(independent).put(changed, bytes.inputStream())
        }
        assertFalse(f.dav.files.containsKey(davPath(changed.key)))
    }

    @Test fun concurrentSameIdDifferentContentHasExactlyOneWinnerAcrossIndependentLocalOwners() = davFixture { f ->
        val stores = listOf(f.store(), f.store(Files.createDirectory(f.root.resolve("second"))))
        val key = CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random())
        val payloads = listOf(ByteArray(16_001) { 0x31 }, ByteArray(16_001) { 0x61 })
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = stores.mapIndexed { index, store -> pool.submit<Boolean> {
                check(start.await(10, TimeUnit.SECONDS))
                try { store.put(davRequest(payloads[index], key), payloads[index].inputStream()); true }
                catch (failure: CiphertextStoreException) {
                    assertEquals(CiphertextStoreFailure.OBJECT_CONFLICT, failure.failure); false
                }
            } }
            start.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it }); assertEquals(1, f.dav.objectCreates)
            val output = ByteArrayOutputStream(); f.store().readTo(key, output)
            assertContentEquals(payloads[results.indexOf(true)], output.toByteArray())
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun badSourceNeverContactsRemoteAndBadDownloadNeverEscapesToCaller() = davFixture { f ->
        val bytes = byteArrayOf(0x45, 0x29, 0x11); val request = davRequest(bytes)
        for (bad in listOf(bytes.copyOf(2), bytes + byteArrayOf(0x72), byteArrayOf(0x45, 0x29, 0x12))) {
            davFailure(CiphertextStoreFailure.INTEGRITY) { f.store().put(request, bad.inputStream()) }
        }
        assertEquals(0, f.server.requestCount)
        f.store().put(request, bytes.inputStream())
        val path = davPath(request.key)
        val record = f.dav.files.getValue(path)
        for (bad in listOf(record.copyOf(record.size - 1), record + byteArrayOf(0), record.copyOf().also { it[it.lastIndex] = 0 })) {
            f.dav.files[path] = bad
            val output = ByteArrayOutputStream()
            davFailure(CiphertextStoreFailure.INTEGRITY) { f.store().readTo(request.key, output, request.descriptor) }
            assertEquals(0, output.size())
        }
    }

    @Test fun weakWriteConfigurationAndLocalDiskBudgetRefuseWithoutRemoteMutation() = davFixture { f ->
        f.dav.honorConditions = false
        val bytes = byteArrayOf(0x29); val request = davRequest(bytes)
        val readOnly = f.store(support = WebDavWriteSupport.UNVERIFIED_READ_ONLY)
        assertFalse(readOnly.capabilities.conditionalCreate)
        davFailure(CiphertextStoreFailure.UNSUPPORTED) { readOnly.put(request, bytes.inputStream()) }
        assertEquals(0, f.server.requestCount)
        val budgeted = f.store(budget = WebDavIoBudget(minimumFreeBytes = Long.MAX_VALUE / 2))
        davFailure(CiphertextStoreFailure.QUOTA) { budgeted.put(request, bytes.inputStream()) }
        assertEquals(0, f.server.requestCount)
    }

    @Test fun sourceFailureAndInterruptionKeepOperationRetryableWithoutBorrowedStreamClose() = davFixture { f ->
        val bytes = byteArrayOf(0x31, 0x72); val request = davRequest(bytes); val store = f.store()
        var closed = false
        val failed = object : InputStream() {
            override fun read(): Int = throw IOException("synthetic-source-private-marker")
            override fun close() { closed = true }
        }
        val failure = assertFailsWith<CiphertextStoreException> { store.put(request, failed) }
        assertEquals(CiphertextStoreFailure.IO, failure.failure)
        assertFalse(failure.toString().contains("private-marker")); assertFalse(closed)
        assertEquals(0, f.server.requestCount)
        val interrupted = object : InputStream() {
            override fun read(): Int { Thread.currentThread().interrupt(); return 0x31 }
        }
        try { assertFailsWith<InterruptedIOException> { store.put(request, interrupted) } }
        finally { Thread.interrupted() }
        assertEquals(0, f.server.requestCount)
        assertEquals(CiphertextPutDisposition.CREATED, f.store().put(request, bytes.inputStream()).disposition)
        assertEquals(0L, Files.list(f.work).use { entries -> entries.filter { it.fileName.toString().endsWith(".preparing") }.count() })
    }

    @Test fun mismatchedHeadWireLengthCannotReuseAStrongEtagObservation() = davFixture { f ->
        val bytes = ByteArray(20_003) { (it % 151).toByte() }; val request = davRequest(bytes)
        f.store().put(request, bytes.inputStream())
        val wire = f.dav.files.getValue(davPath(request.key))
        f.dav.overrideRequest = { recorded -> if (recorded.method == "HEAD") MockResponse().setResponseCode(200)
            .setHeader("ETag", "\"${WebDavStoreRecord.checksum(wire).sha256.hex}\"")
            .setHeader("Content-Length", wire.size.toLong() + 1) else null }
        val before = f.receivedGetBytes.get()
        assertEquals(request.descriptor, f.store().metadata(request.key)?.descriptor)
        assertEquals(wire.size.toLong(), f.receivedGetBytes.get() - before)
    }

    @Test fun emptyCiphertextAndUnsupportedSuccessfulPutStatusHaveExplicitBehavior() = davFixture { f ->
        val request = davRequest(byteArrayOf())
        f.store().put(request, byteArrayOf().inputStream())
        val output = ByteArrayOutputStream()
        assertEquals(request.descriptor, f.store().readTo(request.key, output)?.descriptor)
        assertEquals(0, output.size())
        f.dav.overrideRequest = { recorded -> if (recorded.method == "PUT") MockResponse().setResponseCode(204) else null }
        val other = davRequest(byteArrayOf(0x33))
        davFailure(CiphertextStoreFailure.UNSUPPORTED) { f.store().put(other, byteArrayOf(0x33).inputStream()) }
        assertFalse(f.dav.files.containsKey(davPath(other.key)))
    }

    @Test fun longFramingBoundaryIsCheckedWithoutClaimingAnActualTwoGiBNetworkTransfer() {
        val length = Int.MAX_VALUE.toLong() + 257
        assertEquals(length + WebDavStoreRecord.HEADER_BYTES, WebDavStoreRecord.requireLength(length))
        davFailure(CiphertextStoreFailure.UNSUPPORTED) { WebDavStoreRecord.requireLength(Long.MAX_VALUE) }
        val key = CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random())
        val header = WebDavStoreRecord.header(CiphertextObjectMetadata(key,
            CiphertextDescriptor(length, WebDavStoreRecord.checksum(byteArrayOf()).sha256)))
        assertEquals(length, WebDavStoreRecord.readHeader(header.inputStream(), key).descriptor.length)
    }

    @Test fun httpIsExplicitlySyntheticAndRedirectsNeverForwardCredentials() = davFixture { f ->
        assertFailsWith<IllegalArgumentException> { WebDavHttpTransport(f.url("/sync/"), f.client) }
        MockWebServer().use { other ->
            other.start()
            f.dav.overrideRequest = { MockResponse().setResponseCode(302).setHeader("Location", other.url("/capture")) }
            val marker = "Bearer synthetic-private-marker"
            val transport = WebDavHttpTransport(f.url("/sync/"), f.client.newBuilder().followRedirects(true).build(),
                { marker }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
            val store = WebDavCiphertextStore(transport, f.work, f.route)
            val failure = assertFailsWith<CiphertextStoreException> {
                store.metadata(CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random()))
            }
            assertEquals(CiphertextStoreFailure.PERMISSION, failure.failure)
            assertFalse(failure.toString().contains(marker)); assertFalse(failure.toString().contains(f.url("/").toString()))
            assertEquals(0, other.requestCount)
        }
    }

    @Test fun statusAndCredentialFailuresStayTypedAndDoNotExposeRemoteText() = davFixture { f ->
        val key = CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random())
        for ((code, expected) in listOf(401 to CiphertextStoreFailure.AUTHENTICATION, 403 to CiphertextStoreFailure.PERMISSION,
            423 to CiphertextStoreFailure.TRANSIENT, 429 to CiphertextStoreFailure.TRANSIENT, 507 to CiphertextStoreFailure.QUOTA,
            503 to CiphertextStoreFailure.TRANSIENT)) {
            f.dav.overrideRequest = { MockResponse().setResponseCode(code).setBody("private-body-and-URL") }
            val output = ByteArrayOutputStream()
            val failure = assertFailsWith<CiphertextStoreException> { f.store().readTo(key, output, null) }
            assertEquals(expected, failure.failure); assertFalse(failure.toString().contains("private-body"))
            assertEquals(0, output.size(), "An HTTP error body must never reach the caller output")
        }
        val before = f.server.requestCount
        val transport = WebDavHttpTransport(f.url("/sync/"), f.client, { "Bearer private\r\nheader" }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
        davFailure(CiphertextStoreFailure.AUTHENTICATION) { WebDavCiphertextStore(transport, f.work, f.route).metadata(key) }
        assertEquals(before, f.server.requestCount)
    }
}

internal class DavFixture(val root: Path) : AutoCloseable {
    val dav = SyntheticDav()
    val server = MockWebServer().apply { dispatcher = dav; start(InetAddress.getByName("127.0.0.1"), 0) }
    val receivedGetBytes = AtomicLong()
    val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(5, TimeUnit.SECONDS)
        .eventListener(object : EventListener() {
            override fun responseBodyEnd(call: Call, byteCount: Long) {
                if (call.request().method == "GET") receivedGetBytes.addAndGet(byteCount)
            }
        }).build()
    val route = SyncStoreId.random()
    val work = Files.createDirectory(root.resolve("dav-work"))
    fun url(path: String): HttpUrl = server.url(path).newBuilder().host("127.0.0.1").build()
    fun store(privateRoot: Path = work, support: WebDavWriteSupport = WebDavWriteSupport.VERIFIED_CONDITIONAL_CREATE,
        budget: WebDavIoBudget = WebDavIoBudget()): WebDavCiphertextStore = WebDavCiphertextStore(
        WebDavHttpTransport(url("/sync/"), client, { "Bearer synthetic-only" }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP),
        privateRoot, route, support, budget)
    override fun close() { server.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
}

internal class SyntheticDav : Dispatcher() {
    val files = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    val collections = linkedSetOf("/sync/")
    val putPaths = mutableListOf<String>()
    @Volatile var objectCreates = 0
    @Volatile var heads = 0
    @Volatile var loseObjectReplyOnce = false
    @Volatile var honorConditions = true
    @Volatile var overrideEtags = false
    @Volatile var etagOverride: String? = null
    @Volatile var overrideRequest: ((RecordedRequest) -> MockResponse?)? = null

    @Synchronized override fun dispatch(request: RecordedRequest): MockResponse {
        overrideRequest?.invoke(request)?.let { return it }
        val path = request.requestUrl!!.encodedPath
        return when (request.method) {
            "MKCOL" -> if (path in collections || files.containsKey(path)) response(405) else { collections.add(path); response(201) }
            "HEAD" -> {
                heads++
                val bytes = files[path] ?: return response(404)
                response(200).setHeader("Content-Length", bytes.size).apply { etag(bytes)?.let { setHeader("ETag", it) } }
            }
            "GET" -> {
                val bytes = files[path] ?: return response(404)
                response(200).setBody(Buffer().write(bytes)).apply { etag(bytes)?.let { setHeader("ETag", it) } }
            }
            "PUT" -> {
                if (request.getHeader("If-None-Match") != "*") return response(400)
                if (honorConditions && files.containsKey(path)) return response(412)
                val bytes = request.body.readByteArray()
                files[path] = bytes; putPaths += path
                if (!path.contains("/operations/")) {
                    objectCreates++
                    if (loseObjectReplyOnce) { loseObjectReplyOnce = false; return response(201).setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
                }
                response(201)
            }
            "PROPFIND" -> {
                if (path !in collections) return response(404)
                val children = if (request.getHeader("Depth") == "1") files.keys.filter {
                    it.startsWith(path) && '/' !in it.removePrefix(path)
                }.sortedDescending() else emptyList()
                response(207).setBody(davListing(path, children))
            }
            else -> response(405)
        }
    }
    private fun etag(bytes: ByteArray): String? = if (overrideEtags) etagOverride else "\"${WebDavStoreRecord.checksum(bytes).sha256.hex}\""
    private fun response(status: Int) = MockResponse().setResponseCode(status)
}

internal fun davListing(collection: String, children: List<String>, status: Int = 200): String =
    "<d:multistatus xmlns:d=\"DAV:\">" + davXmlEntry(collection, true) +
        children.joinToString("") { davXmlEntry(it, false, status) } + "</d:multistatus>"

internal fun davXmlEntry(href: String, collection: Boolean, status: Int = 200): String =
    "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype>" +
        (if (collection) "<d:collection/>" else "") + "</d:resourcetype></d:prop><d:status>HTTP/1.1 $status Result</d:status></d:propstat></d:response>"

internal fun davPath(key: CiphertextObjectKey) = "/sync/${if (key.kind == CiphertextObjectKind.BLOB) "objects" else "commits"}/${key.id.value}.bin"
internal fun davRecord(key: CiphertextObjectKey, bytes: ByteArray) =
    WebDavStoreRecord.header(CiphertextObjectMetadata(key, WebDavStoreRecord.checksum(bytes))) + bytes
internal fun davRequest(bytes: ByteArray, key: CiphertextObjectKey = CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random())) =
    CiphertextPutRequest(key, SyncStoreId.random(), WebDavStoreRecord.checksum(bytes))
internal fun davFailure(failure: CiphertextStoreFailure, work: () -> Unit) {
    assertEquals(failure, assertFailsWith<CiphertextStoreException>(block = work).failure)
}
internal fun davFixture(work: (DavFixture) -> Unit) {
    val root = Files.createTempDirectory("ccb-dav-synthetic-").toRealPath()
    try { DavFixture(root).use(work) } finally { check(root.toFile().deleteRecursively()) }
}
