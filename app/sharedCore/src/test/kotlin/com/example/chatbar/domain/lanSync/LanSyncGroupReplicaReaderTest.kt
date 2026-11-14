package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Actual immutable file stores; transport shims inject failures after the remote file verifies. */
class LanSyncGroupReplicaReaderTest {
    @Test fun verifiedRemoteCiphertextPersistsAndReopensWithoutAnyRoute() = withRoot { root ->
        val bytes = ciphertext()
        val request = request(bytes, CiphertextObjectKind.COMMIT)
        val source = FileCiphertextStore(root.resolve("remote"), bufferBytes = 7919)
        source.put(request, bytes.inputStream())
        val localPath = root.resolve("local")
        val local = FileCiphertextStore(localPath, bufferBytes = 4093)
        val remote = ObservedStore(source)
        val failures = mutableListOf<Pair<String, Exception>>()
        val reader = LanSyncGroupReplicaReader(local, root.resolve("reads")) { route, failure -> failures += route to failure }
        reader.routes(listOf(LanSyncGroupReplicaReader.Route("first-device", remote)))
        val output = ByteArrayOutputStream()
        val metadata = CiphertextObjectMetadata(request.key, request.descriptor)

        assertEquals(metadata, reader.readTo(request.key, output, request.descriptor))
        assertContentEquals(bytes, output.toByteArray())
        assertEquals(1, remote.reads)
        assertEquals(metadata, reader.metadata(request.key))
        assertEquals(listOf(metadata), reader.listCommits().entries)
        assertNull(local.metadata(request.key), "Untrusted COMMIT candidates do not acquire the primary immutable slot")
        assertTrue(local.listCommits().entries.isEmpty())
        assertTrue(failures.isEmpty())

        reader.routes(emptyList())
        val reopened = LanSyncGroupReplicaReader(FileCiphertextStore(localPath), root.resolve("reads")) { _, _ ->
            fail("A retained local object must not depend on its former remote route")
        }
        val afterReopen = ByteArrayOutputStream()
        assertEquals(metadata, reopened.readTo(request.key, afterReopen, request.descriptor))
        assertContentEquals(bytes, afterReopen.toByteArray())
        assertEquals(1, remote.reads)
    }

    @Test fun sameKeyCommitPutsKeepIndependentDescriptorsAndStableOperationBindingsAcrossReopen() = withRoot { root ->
        val key = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random())
        val firstBytes = byteArrayOf(1, 3, 5, 7)
        val secondBytes = byteArrayOf(2, 4, 6, 8)
        val first = request(firstBytes, CiphertextObjectKind.COMMIT).copy(key = key)
        val second = request(secondBytes, CiphertextObjectKind.COMMIT).copy(key = key)
        val localPath = root.resolve("local")
        val local = FileCiphertextStore(localPath)
        val work = root.resolve("reads")
        val reader = LanSyncGroupReplicaReader(local, work) { _, failure -> throw failure }
        assertEquals(CiphertextPutDisposition.CREATED, reader.put(first, firstBytes.inputStream()).disposition)
        assertEquals(CiphertextPutDisposition.CREATED, reader.put(second, secondBytes.inputStream()).disposition)
        assertEquals(CiphertextPutDisposition.ALREADY_PRESENT, reader.put(first, firstBytes.inputStream()).disposition)
        val conflict = assertFailsWith<CiphertextStoreException> {
            reader.put(second.copy(operationId = first.operationId), secondBytes.inputStream())
        }
        assertEquals(CiphertextStoreFailure.OPERATION_CONFLICT, conflict.failure)
        assertNull(local.metadata(key))

        val reopened = LanSyncGroupReplicaReader(FileCiphertextStore(localPath), work) { _, failure -> throw failure }
        assertEquals(CiphertextPutDisposition.ALREADY_PRESENT, reopened.put(first, firstBytes.inputStream()).disposition)
        assertEquals(CiphertextStoreFailure.OPERATION_CONFLICT, assertFailsWith<CiphertextStoreException> {
            reopened.put(second.copy(operationId = first.operationId), secondBytes.inputStream())
        }.failure)
        val page = reopened.listCommits(null, 1)
        assertEquals(1, page.entries.size)
        val next = reopened.listCommits(requireNotNull(page.nextCursor), 1)
        assertNull(next.nextCursor)
        assertEquals(setOf(CiphertextObjectMetadata(key, first.descriptor), CiphertextObjectMetadata(key, second.descriptor)),
            (page.entries + next.entries).toSet())
        for ((expected, bytes) in listOf(first.descriptor to firstBytes, second.descriptor to secondBytes)) {
            assertEquals(CiphertextObjectMetadata(key, expected), reopened.metadataExact(key, expected))
            val sink = ByteArrayOutputStream()
            assertEquals(CiphertextObjectMetadata(key, expected), reopened.readTo(key, sink, expected))
            assertContentEquals(bytes, sink.toByteArray())
        }
    }

    @Test fun callerSinkFailureEscapesOnceWithoutTryingAnotherRouteOrDuplicatingOutput() = withRoot { root ->
        val bytes = ciphertext(); val request = request(bytes)
        val source = FileCiphertextStore(root.resolve("remote"))
        source.put(request, bytes.inputStream())
        val first = ObservedStore(source); val second = ObservedStore(source)
        val local = FileCiphertextStore(root.resolve("local"))
        val failures = mutableListOf<Pair<String, Exception>>()
        val reader = LanSyncGroupReplicaReader(local, root.resolve("reads")) { route, failure -> failures += route to failure }
        reader.routes(listOf(LanSyncGroupReplicaReader.Route("first", first), LanSyncGroupReplicaReader.Route("second", second)))
        val sinkFailure = IOException("synthetic caller sink failure")
        val emitted = ByteArrayOutputStream()
        var failed = false
        var sinkClosed = false
        val sink = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (!failed && length > 0) {
                    emitted.write(bytes, offset, minOf(length, 17)); failed = true
                    throw sinkFailure
                }
                // If a route retry incorrectly reuses this already-partial sink, retain that evidence.
                emitted.write(bytes, offset, length)
            }
            override fun close() { sinkClosed = true }
        }

        assertSame(sinkFailure, assertFailsWith<IOException> { reader.readTo(request.key, sink, request.descriptor) })
        assertContentEquals(bytes.copyOf(17), emitted.toByteArray())
        assertEquals(1, first.reads); assertEquals(0, second.reads)
        assertTrue(failures.isEmpty(), "Caller sink failures must not be attributed to a remote route")
        assertFalse(sinkClosed)
        assertEquals(request.descriptor, local.metadata(request.key)?.descriptor)

        reader.routes(emptyList())
        val retrySink = ByteArrayOutputStream()
        reader.readTo(request.key, retrySink, request.descriptor)
        assertContentEquals(bytes, retrySink.toByteArray())
    }

    @Test fun corruptTruncatedAndOverlongRemoteStreamsStayQuarantinedBeforeLocalPublication() = withRoot { root ->
        val bytes = ciphertext()
        val malformed = listOf(bytes.copyOf().also { it[1234] = (it[1234].toInt() xor 1).toByte() },
            bytes.copyOf(bytes.size - 9), bytes + byteArrayOf(0x55))
        malformed.forEachIndexed { index, altered ->
            val request = request(bytes, CiphertextObjectKind.COMMIT)
            val source = FileCiphertextStore(root.resolve("remote-$index"), bufferBytes = 7919)
            source.put(request, bytes.inputStream())
            var reads = 0
            val remote = object : CiphertextStore by source {
                override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
                    reads++
                    val checked = ByteArrayOutputStream()
                    val metadata = source.readTo(key, checked, expected)
                    assertContentEquals(bytes, checked.toByteArray())
                    destination.write(altered)
                    return metadata
                }
            }
            val local = FileCiphertextStore(root.resolve("local-$index"))
            val failures = mutableListOf<Pair<String, Exception>>()
            val reader = LanSyncGroupReplicaReader(local, root.resolve("reads-$index")) { route, failure -> failures += route to failure }
            reader.routes(listOf(LanSyncGroupReplicaReader.Route("damaged-transport", remote)))
            val caller = ByteArrayOutputStream()

            assertEquals("SYNC_CANDIDATE_SOURCE_CHANGED", assertFailsWith<IllegalArgumentException> {
                reader.readTo(request.key, caller, request.descriptor)
            }.message)
            assertEquals(1, reads)
            assertEquals(0, caller.size())
            assertNull(local.metadata(request.key))
            assertTrue(local.listCommits().entries.isEmpty())
            assertTrue(reader.listCommits().entries.isEmpty())
            assertEquals(listOf("damaged-transport"), failures.map { it.first })
            assertEquals(request.descriptor, source.metadata(request.key)?.descriptor)
        }
    }

    @Test fun substitutedDiscoveryDescriptorRefusesBeforeReadingOrWritingAnyPayload() = withRoot { root ->
        val bytes = ciphertext(); val request = request(bytes)
        val source = FileCiphertextStore(root.resolve("remote")); source.put(request, bytes.inputStream())
        val substituted = request.descriptor.copy(length = request.descriptor.length + 1)
        var reads = 0
        val remote = object : CiphertextStore by source {
            override fun metadata(key: CiphertextObjectKey) = source.metadata(key)?.copy(descriptor = substituted)
            override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
                reads++; return source.readTo(key, destination, expected)
            }
        }
        val local = FileCiphertextStore(root.resolve("local"))
        val failures = mutableListOf<Pair<String, Exception>>()
        val reader = LanSyncGroupReplicaReader(local, root.resolve("reads")) { route, failure -> failures += route to failure }
        reader.routes(listOf(LanSyncGroupReplicaReader.Route("substituted-metadata", remote)))
        val caller = ByteArrayOutputStream()

        assertEquals("SYNC_GROUP_REPLICA_OBJECT_CHANGED", assertFailsWith<IllegalArgumentException> {
            reader.readTo(request.key, caller, request.descriptor)
        }.message)
        assertEquals(0, reads); assertEquals(0, caller.size())
        assertNull(local.metadata(request.key))
        assertTrue(failures.isEmpty())
    }

    @Test fun substitutedReadResultRefusesEvenWhenEmittedBytesMatchExpectedCiphertext() = withRoot { root ->
        val bytes = ciphertext(); val request = request(bytes)
        val source = FileCiphertextStore(root.resolve("remote")); source.put(request, bytes.inputStream())
        val remote = object : CiphertextStore by source {
            override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? =
                source.readTo(key, destination, expected)?.copy(key = CiphertextObjectKey(key.kind, SyncStoreId.random()))
        }
        val local = FileCiphertextStore(root.resolve("local"))
        val failures = mutableListOf<Pair<String, Exception>>()
        val reader = LanSyncGroupReplicaReader(local, root.resolve("reads")) { route, failure -> failures += route to failure }
        reader.routes(listOf(LanSyncGroupReplicaReader.Route("changed-result", remote)))
        val caller = ByteArrayOutputStream()

        assertEquals("SYNC_GROUP_REPLICA_OBJECT_CHANGED", assertFailsWith<IllegalArgumentException> {
            reader.readTo(request.key, caller, request.descriptor)
        }.message)
        assertEquals(0, caller.size()); assertNull(local.metadata(request.key))
        assertEquals(listOf("changed-result"), failures.map { it.first })
    }

    private class ObservedStore(private val delegate: CiphertextStore) : CiphertextStore by delegate {
        var reads = 0
        override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
            reads++
            return delegate.readTo(key, destination, expected)
        }
    }
    private fun ciphertext() = ByteArray(128 * 1024 + 19) { ((it * 37 + 11) and 255).toByte() }
    private fun request(bytes: ByteArray, kind: CiphertextObjectKind = CiphertextObjectKind.BLOB) = CiphertextPutRequest(
        CiphertextObjectKey(kind, SyncStoreId.random()), SyncStoreId.random(),
        CiphertextDescriptor(bytes.size.toLong(), CiphertextSha256.parse(lanSyncHash(bytes))))
    private fun withRoot(work: (Path) -> Unit) {
        val root = Files.createTempDirectory("ccb-group-replica-reader-").toRealPath()
        try { work(root) } finally { root.toFile().deleteRecursively() }
    }
}
