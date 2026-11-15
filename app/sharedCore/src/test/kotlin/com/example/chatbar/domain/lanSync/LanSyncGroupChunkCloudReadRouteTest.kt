package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import kotlin.test.*

/** Real small HTTP assemblies and existing AEAD/HPKE/Inbox owners, never a provider acceptance test. */
class LanSyncGroupChunkCloudReadRouteTest {
    private class Fixture(val shared: WeakFixture) : AutoCloseable {
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
        private val transports = mutableListOf<WebDavHttpTransport>()
        fun directory(name: String): Path = shared.root.resolve(name).also { if (!Files.exists(it)) Files.createDirectory(it) }
        private fun transport() = WebDavHttpTransport(shared.server.url("/sync/").newBuilder().host("127.0.0.1").build(), client,
            { "Bearer synthetic-only" }, WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP).also(transports::add)
        fun layer(name: String, bytes: Long = 31) = WeakWebDavChunkAssembly(transport(), directory(name), shared.route,
            chunkBytes = bytes, budget = WebDavIoBudget(bufferBytes = 17, minimumFreeBytes = 0))
        fun manifestStore(name: String) = WeakWebDavAttemptStore(transport(), directory(name), shared.route,
            WebDavIoBudget(minimumFreeBytes = 0), WeakWebDavNamespace.ASSEMBLIES)
        override fun close() { transports.forEach { it.close() }; client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    private fun fixture(work: (Fixture) -> Unit) = weakFixture { shared -> Fixture(shared).use(work) }
    private fun request(key: CiphertextObjectKey, bytes: ByteArray) =
        CiphertextPutRequest(key, SyncStoreId.random(), CiphertextDescriptor(bytes.size.toLong(), CiphertextSha256.parse(lanSyncHash(bytes))))
    private fun path(namespace: WeakWebDavNamespace, candidate: WeakWebDavCandidate) =
        "/sync/${namespace.collection}/${if (candidate.key.kind == CiphertextObjectKind.BLOB) "objects" else "commits"}/${candidate.key.id.value}/${candidate.descriptor.sha256.hex}/${candidate.descriptor.length}-${candidate.operationId.value}-${candidate.attempt.value}.bin"
    private fun read(store: CiphertextStore, metadata: CiphertextObjectMetadata): ByteArray = ByteArrayOutputStream().use { output ->
        assertEquals(metadata, store.readTo(metadata.key, output, metadata.descriptor)); output.toByteArray()
    }

    @Test fun pagedLogicalMetadataEntersInboxQuarantineThenActualHpkeAndAeadAdmitTheOriginalVersion() = fixture { f ->
        val original = LanSyncGroupPublicationQueueTest.Fixture(f.directory("native-original"))
        val epochQueues = f.directory("root-epoch-queues")
        fun outbox(epoch: SyncStoreId): SyncEncryptedOutbox {
            require(epoch == original.cipher.epoch)
            val directory = epochQueues.resolve(epoch.value)
            if (!Files.exists(directory)) Files.createDirectory(directory)
            return SyncEncryptedOutbox(directory, original.cipher)
        }
        val root = LanSyncGroupRootPublication(f.directory("root-publication"), original.owner, { original.ledger },
            { epoch -> require(epoch == original.cipher.epoch); original.cipher }, ::outbox)
        root.upload(original.backend, SyncDeliveryTarget(SyncDeliveryKind.LOCAL_REPLICA, SyncStoreId.random()))
        val prepared = original.prepare()
        val publication = original.publisher().prepare(prepared.metadata, listOf(prepared.unit), prepared.operations, emptyList())
        original.publisher().upload(publication.metadata.operation, original.backend)
        val expected = original.backend.listCommits(null).entries.toSet()
        val writer = f.layer("assembly-writer", 257)
        expected.forEach { metadata -> writer.append(CiphertextPutRequest(metadata.key, SyncStoreId.random(), metadata.descriptor),
            read(original.backend, metadata).inputStream()) }
        val route = LanSyncGroupChunkCloudReadRoute(f.layer("assembly-reader", 257))
        val observed = linkedSetOf<CiphertextObjectMetadata>(); var cursor: CommitCursor? = null
        do {
            val page = route.listCommits(cursor, 1)
            assertTrue(page.entries.size <= 1); observed += page.entries; cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(expected, observed)
        assertFalse(route.capabilities.conditionalCreate); assertFalse(route.capabilities.resumableByteUpload)
        assertTrue(route.capabilities.untrustedCandidateVariants)
        val recipient = original.recipients.getValue(original.sender.fingerprint)
        val custody = SyncGroupCustody(f.directory("fresh-custody"), original.cipher.group, recipient,
            { it.reversedArray() }, { it.reversedArray() })
        custody.enroll(original.ledger.trustAnchor, listOf(original.genesis))
        val inboxRoot = f.directory("fresh-inbox")
        val inbox = SyncGroupInbox(inboxRoot, route, custody, recipient)
        cursor = null
        do { cursor = inbox.discover(cursor, 1).nextCursor } while (cursor != null)
        val reconciled = inbox.reconcile()
        assertFalse(reconciled.trustBlocked)
        assertTrue(requireNotNull(reconciled.history).contains(publication.reference))
        assertEquals(original.cipher.keyCommitment(), custody.key(original.cipher.epoch).keyCommitment())
        assertTrue(reconciled.observations.any { it.stage == SyncInboxStage.KEY_ADMITTED })
        assertTrue(reconciled.observations.any { it.key == prepared.metadata.context.key && it.stage == SyncInboxStage.CAUSAL_ADMITTED })
        val legacy = FileCiphertextStore(inboxRoot.resolve("ciphertext-mirror"))
        assertNull(legacy.metadata(prepared.metadata.context.key), "Retaining a provider claim does not occupy a strict canonical key")
        val decoded = original.codec.read(route, custody.key(original.cipher.epoch), prepared.metadata.context,
            prepared.metadata.request.descriptor, publication.reference)
        assertEquals(prepared.metadata.envelope, decoded.envelope)
        assertContentEquals(read(original.backend, prepared.metadata.authenticated.metadata), read(route, prepared.metadata.authenticated.metadata))
        assertNull(route.latestFailure.value)
    }

    @Test fun malformedFirstManifestIsDiagnosedButItsEmptyPageDoesNotHideTheFollowingGoodLogicalObject() = fixture { f ->
        val badKey = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.parse("0".repeat(32)))
        val badBytes = byteArrayOf(1, 7, 3)
        val bad = f.manifestStore("bad-manifest-writer").appendAttempt(request(badKey, badBytes), badBytes.inputStream())
        val saved = f.shared.dav.files.getValue(path(WeakWebDavNamespace.ASSEMBLIES, bad.candidate)).copyOf()
        val goodKey = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.parse("8".repeat(32)))
        val goodBytes = ByteArray(93) { it.toByte() }; val good = request(goodKey, goodBytes)
        f.layer("good-manifest-writer").append(good, goodBytes.inputStream())
        val reads = mutableListOf<String>()
        f.shared.dav.overrideRequest = { request -> if (request.method == "GET") reads += request.requestUrl!!.encodedPath; null }
        val route = LanSyncGroupChunkCloudReadRoute(f.layer("manifest-reader"))
        val first = route.listCommits(null, 1)
        assertTrue(first.entries.isEmpty()); assertNotNull(first.nextCursor)
        assertEquals(bad.candidate, route.latestFailure.value?.assembly)
        assertEquals(CiphertextStoreFailure.INTEGRITY, route.latestFailure.value?.failure)
        assertEquals(CiphertextStoreFailure.INTEGRITY,
            assertFailsWith<CiphertextStoreException> { route.metadata(badKey) }.failure)
        val second = route.listCommits(first.nextCursor, 1)
        assertEquals(listOf(CiphertextObjectMetadata(good.key, good.descriptor)), second.entries)
        assertNull(second.nextCursor)
        assertTrue(reads.all { "/weak-assemblies-v1/" in it }, "Listing inspects manifests, never logical chunks or attachments")
        assertContentEquals(goodBytes, read(route, CiphertextObjectMetadata(good.key, good.descriptor)))
        assertContentEquals(saved, f.shared.dav.files.getValue(path(WeakWebDavNamespace.ASSEMBLIES, bad.candidate)))
    }

    @Test fun mutableChunkFailureHasZeroCallerOutputAndARepairedAssemblyRetainsAllOldEvidence() = fixture { f ->
        val bytes = ByteArray(119) { (it % 83).toByte() }
        val request = request(CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random()), bytes)
        val writer = f.layer("mutable-writer")
        val original = writer.append(request, bytes.inputStream())
        val manifestPath = path(WeakWebDavNamespace.ASSEMBLIES, original.assembly)
        val manifest = f.shared.dav.files.getValue(manifestPath).copyOf()
        val chunk = f.shared.dav.files.keys.first { "/weak-chunks-v1/" in it }
        val changed = f.shared.dav.files.getValue(chunk).let { it.copyOf(it.size - 2) }
        f.shared.dav.files[chunk] = changed
        val route = LanSyncGroupChunkCloudReadRoute(f.layer("mutable-reader"))
        val escaped = ByteArrayOutputStream()
        assertEquals(CiphertextStoreFailure.INTEGRITY,
            assertFailsWith<CiphertextStoreException> { route.readTo(request.key, escaped, request.descriptor) }.failure)
        assertEquals(0, escaped.size())
        assertEquals(request.key, route.latestFailure.value?.key)
        val repaired = writer.resume(request.operationId)
        assertNotEquals(original.assembly, repaired.assembly)
        assertContentEquals(bytes, read(route, CiphertextObjectMetadata(request.key, request.descriptor)))
        assertContentEquals(changed, f.shared.dav.files.getValue(chunk))
        assertContentEquals(manifest, f.shared.dav.files.getValue(manifestPath))
    }

    @Test fun logicalDescriptorVariantsStayPagedAndAmbiguousUntilTheCallerPinsTheExpectedDescriptor() = fixture { f ->
        val key = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random())
        val bodies = listOf(ByteArray(79) { it.toByte() }, byteArrayOf(9, 2, 6))
        val requests = bodies.map { request(key, it) }
        requests.zip(bodies).forEachIndexed { index, (request, bytes) -> f.layer("variant-writer-$index").append(request, bytes.inputStream()) }
        val route = LanSyncGroupChunkCloudReadRoute(f.layer("variant-reader"))
        val pages = linkedSetOf<CiphertextObjectMetadata>(); var cursor: CommitCursor? = null
        do {
            val page = route.listCommits(cursor, 1)
            assertTrue(page.entries.size <= 1); pages += page.entries; cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(requests.map { CiphertextObjectMetadata(it.key, it.descriptor) }.toSet(), pages)
        assertEquals(CiphertextStoreFailure.OBJECT_CONFLICT,
            assertFailsWith<CiphertextStoreException> { route.metadata(key) }.failure)
        assertEquals(CiphertextStoreFailure.OBJECT_CONFLICT,
            assertFailsWith<CiphertextStoreException> { route.readTo(key, ByteArrayOutputStream()) }.failure)
        requests.zip(bodies).forEach { (request, bytes) ->
            assertContentEquals(bytes, read(route, CiphertextObjectMetadata(key, request.descriptor)))
        }
        assertEquals(CiphertextStoreFailure.INVALID_CURSOR,
            assertFailsWith<CiphertextStoreException> { route.listCommits(CommitCursor("weak-cloud1:wrong"), 1) }.failure)
    }

    @Test fun callerSinkFailureAndActiveFailureNeverBecomeCandidateRetriesOrLoseTheirOriginalException() = fixture { f ->
        val key = CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random())
        val bytes = ByteArray(101) { it.toByte() }; val request = request(key, bytes)
        repeat(2) { index -> f.layer("sink-writer-$index").append(request.copy(operationId = SyncStoreId.random()), bytes.inputStream()) }
        val gets = AtomicInteger()
        f.shared.dav.overrideRequest = { incoming ->
            if (incoming.method == "GET" && "/weak-assemblies-v1/" in incoming.requestUrl!!.encodedPath) gets.incrementAndGet()
            null
        }
        val expected = IOException("synthetic caller output failed")
        val writes = AtomicInteger()
        val sink = object : OutputStream() {
            override fun write(value: Int) { writes.incrementAndGet(); throw expected }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { writes.incrementAndGet(); throw expected }
        }
        val route = LanSyncGroupChunkCloudReadRoute(f.layer("sink-reader"))
        assertSame(expected, assertFailsWith<IOException> { route.readTo(key, sink, request.descriptor) })
        assertEquals(1, writes.get()); assertEquals(1, gets.get())
        assertNull(route.latestFailure.value)
        // Even a store-shaped active error must escape the provider's retryable-integrity catch.
        val stopped = CiphertextStoreException(CiphertextStoreFailure.INTEGRITY)
        val checks = AtomicInteger()
        val inactive = LanSyncGroupChunkCloudReadRoute(f.layer("active-reader")) {
            if (checks.incrementAndGet() == 5) throw stopped
        }
        assertSame(stopped, assertFailsWith<CiphertextStoreException> { inactive.readTo(key, ByteArrayOutputStream(), request.descriptor) })
        assertEquals(5, checks.get()); assertNull(inactive.latestFailure.value)
        assertEquals(CiphertextStoreFailure.UNSUPPORTED,
            assertFailsWith<CiphertextStoreException> { route.put(request, bytes.inputStream()) }.failure)
    }
}
