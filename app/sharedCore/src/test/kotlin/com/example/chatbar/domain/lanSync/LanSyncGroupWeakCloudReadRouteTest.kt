package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.weakFixture
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class LanSyncGroupWeakCloudReadRouteTest {
    @Test fun conflictingRawCommitClaimsArePagedAndQuarantinedWithoutSelectingACanonicalObject() = weakFixture { f ->
        val key = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random())
        val bodies = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5), byteArrayOf(6))
        val requests = bodies.map { request(key, it) }
        requests.zip(bodies).forEachIndexed { index, (request, bytes) ->
            f.store("writer-$index").appendAttempt(request, bytes.inputStream())
        }
        val route = LanSyncGroupWeakCloudReadRoute(f.store("reader"))
        assertTrue(route.capabilities.untrustedCandidateVariants)
        assertFalse(route.capabilities.conditionalCreate)
        assertFalse(route.capabilities.resumableByteUpload)
        val observed = mutableSetOf<CiphertextObjectMetadata>()
        var cursor: CommitCursor? = null
        do {
            val page = route.listCommits(cursor, 1)
            assertTrue(page.entries.size <= 1)
            observed += page.entries
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(requests.map { CiphertextObjectMetadata(it.key, it.descriptor) }.toSet(), observed)
        assertEquals(CiphertextStoreFailure.OBJECT_CONFLICT,
            assertFailsWith<CiphertextStoreException> { route.metadata(key) }.failure)
        val canonical = FileCiphertextStore(f.root.resolve("canonical"))
        val candidates = SyncCiphertextCandidates(f.root.resolve("quarantine"), canonical)
        observed.forEach { candidates.retain(it, route) }
        assertEquals(observed, candidates.entries().toSet())
        assertNull(canonical.metadata(key), "A raw cloud claim must never own the canonical key")
        observed.forEach { metadata ->
            val output = ByteArrayOutputStream()
            assertEquals(metadata, candidates.exact(metadata).readTo(key, output, metadata.descriptor))
            assertContentEquals(bodies[requests.indexOfFirst { it.descriptor == metadata.descriptor }], output.toByteArray())
        }
    }

    @Test fun replicaUsesTheAuthenticatedBlobDescriptorDespiteAnotherCloudClaimAndKeepsExactBytesCold() = weakFixture { f ->
        val key = CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random())
        val bytes = ByteArray(130_019) { (it % 211).toByte() }
        val selected = request(key, bytes)
        f.store("selected").appendAttempt(selected, bytes.inputStream())
        f.store("other").appendAttempt(request(key, byteArrayOf(7)), byteArrayOf(7).inputStream())
        val route = LanSyncGroupWeakCloudReadRoute(f.store("reader"))
        assertEquals(CiphertextStoreFailure.OBJECT_CONFLICT,
            assertFailsWith<CiphertextStoreException> { route.metadata(key) }.failure)
        val localRoot = f.root.resolve("local")
        val work = f.root.resolve("replica-reader")
        val replica = LanSyncGroupReplicaReader(FileCiphertextStore(localRoot), work) { _, failure -> throw failure }
        replica.routes(listOf(LanSyncGroupReplicaReader.Route("CLOUD", route)))
        val output = ByteArrayOutputStream()
        assertEquals(CiphertextObjectMetadata(key, selected.descriptor), replica.readTo(key, output, selected.descriptor))
        assertContentEquals(bytes, output.toByteArray())
        f.dav.files.clear()
        val cold = LanSyncGroupReplicaReader(FileCiphertextStore(localRoot), work) { _, failure -> throw failure }
        val reopened = ByteArrayOutputStream()
        assertNotNull(cold.readTo(key, reopened, selected.descriptor))
        assertContentEquals(bytes, reopened.toByteArray())
    }

    @Test fun callerOutputFailureCannotProbeAnotherAttemptIntoTheAlreadyPartialSink() = weakFixture { f ->
        val bytes = ByteArray(90_013) { it.toByte() }
        val request = request(CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random()), bytes)
        f.store("first").appendAttempt(request, bytes.inputStream())
        f.store("second").appendAttempt(request, bytes.inputStream())
        val gets = AtomicInteger()
        f.dav.overrideRequest = { if (it.method == "GET") gets.incrementAndGet(); null }
        val route = LanSyncGroupWeakCloudReadRoute(f.store("reader"))
        val expected = IOException("synthetic private sink failure")
        val sink = object : OutputStream() {
            override fun write(value: Int): Unit = throw expected
            override fun write(bytes: ByteArray, offset: Int, length: Int): Unit = throw expected
        }
        assertEquals(CiphertextStoreFailure.IO,
            assertFailsWith<CiphertextStoreException> { route.readTo(request.key, sink, request.descriptor) }.failure)
        assertEquals(1, gets.get())
    }

    @Test fun closedRouteRefusesNetworkAndReadOnlyPortCannotUpload() = weakFixture { f ->
        val store = f.store("reader")
        var active = true
        val route = LanSyncGroupWeakCloudReadRoute(store) { check(active) { "SYNTHETIC_CLOSED" } }
        val request = request(CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random()), byteArrayOf(1))
        active = false
        val before = f.server.requestCount
        assertFailsWith<IllegalStateException> { route.listCommits(null, 1) }
        assertFailsWith<IllegalStateException> { route.readTo(request.key, ByteArrayOutputStream(), request.descriptor) }
        assertEquals(CiphertextStoreFailure.UNSUPPORTED,
            assertFailsWith<CiphertextStoreException> { route.put(request, byteArrayOf(1).inputStream()) }.failure)
        assertEquals(before, f.server.requestCount)
    }

    private fun request(key: CiphertextObjectKey, bytes: ByteArray) = CiphertextPutRequest(key, SyncStoreId.random(),
        CiphertextDescriptor(bytes.size.toLong(), CiphertextSha256.parse(MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })))
}
