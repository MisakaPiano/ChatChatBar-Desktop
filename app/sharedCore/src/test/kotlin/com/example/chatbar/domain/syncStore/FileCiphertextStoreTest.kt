package com.example.chatbar.domain.syncStore

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeNoException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic opaque bytes exercise storage only; none of these tests is an E2EE claim. */
class FileCiphertextStoreTest {
    @Test
    fun `streamed ciphertext survives reopen and does not close caller streams`() = withRoot { root ->
        val bytes = ByteArray(3 * 1024 * 1024 + 17) { ((it * 31 + 7) and 255).toByte() }
        val request = request(bytes)
        var sourceClosed = false
        val source = object : ByteArrayInputStream(bytes) {
            override fun close() { sourceClosed = true; super.close() }
        }
        val created = FileCiphertextStore(root, bufferBytes = 8191).put(request, source)
        assertEquals(CiphertextPutDisposition.CREATED, created.disposition)
        assertFalse(sourceClosed)
        var sinkClosed = false
        val destination = object : ByteArrayOutputStream() {
            override fun close() { sinkClosed = true; super.close() }
        }
        val reopened = FileCiphertextStore(root)
        assertEquals(created.metadata, reopened.metadata(request.key))
        assertEquals(created.metadata, reopened.readTo(request.key, destination, request.descriptor))
        assertContentEquals(bytes, destination.toByteArray())
        assertFalse(sinkClosed)
        assertFalse(reopened.capabilities.conditionalReplace)
        assertFalse(reopened.capabilities.conditionalCreate)
        assertFalse(reopened.capabilities.changeNotifications)
        assertFalse(reopened.capabilities.resumableByteUpload)
        assertFalse(reopened.capabilities.multiObjectTransaction)
        assertTrue(reopened.capabilities.fileDataForcedBeforePublication)
    }

    @Test
    fun `same key and bytes are idempotent with original or new operation`() = withRoot { root ->
        val bytes = byteArrayOf(0x65, 0x12, 0x7f, 0x00)
        val request = request(bytes)
        val store = FileCiphertextStore(root)
        store.put(request, bytes.inputStream())
        assertEquals(CiphertextPutDisposition.ALREADY_PRESENT, store.put(request, bytes.inputStream()).disposition)
        val newOperation = request.copy(operationId = SyncStoreId.random())
        assertEquals(CiphertextPutDisposition.ALREADY_PRESENT,
            FileCiphertextStore(root).put(newOperation, bytes.inputStream()).disposition)
        val altered = byteArrayOf(0x65, 0x13, 0x7f, 0x00)
        val conflict = this.request(altered, request.key)
        assertFailure(CiphertextStoreFailure.OBJECT_CONFLICT) { store.put(conflict, altered.inputStream()) }
        val destination = ByteArrayOutputStream()
        store.readTo(request.key, destination, request.descriptor)
        assertContentEquals(bytes, destination.toByteArray())
    }

    @Test
    fun `operation binding survives failed stream and restart without publishing partial object`() = withRoot { root ->
        val bytes = ByteArray(400) { (it % 239).toByte() }
        val request = request(bytes, kind = CiphertextObjectKind.COMMIT)
        val store = FileCiphertextStore(root, bufferBytes = 31)
        assertFailsWith<IOException> { store.put(request, FailingSource(bytes, 53)) }
        assertNull(store.metadata(request.key))
        assertTrue(store.listCommits().entries.isEmpty())
        assertEquals(0L, Files.list(root.resolve("staging")).use { it.count() })
        val reopened = FileCiphertextStore(root)
        val other = request.copy(key = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()))
        assertFailure(CiphertextStoreFailure.OPERATION_CONFLICT) { reopened.put(other, bytes.inputStream()) }
        assertEquals(CiphertextPutDisposition.CREATED, reopened.put(request, bytes.inputStream()).disposition)
        assertEquals(listOf(request.key), reopened.listCommits().entries.map { it.key })
    }

    @Test
    fun `exact length and checksum reject truncated extra and changed bytes`() = withRoot { root ->
        val store = FileCiphertextStore(root, bufferBytes = 2)
        val expected = byteArrayOf(0x31, 0x62, 0x23)
        for (actual in listOf(expected.copyOf(2), expected + byteArrayOf(0x55), byteArrayOf(0x31, 0x61, 0x23))) {
            val request = request(expected)
            assertFailure(CiphertextStoreFailure.INTEGRITY) { store.put(request, actual.inputStream()) }
            assertNull(store.metadata(request.key))
        }
        val empty = request(byteArrayOf())
        store.put(empty, byteArrayOf().inputStream())
        assertEquals(0L, store.metadata(empty.key)!!.descriptor.length)
        assertNull(store.metadata(CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random())))
    }

    @Test
    fun `zero return source makes progress without truncating or spinning`() = withRoot { root ->
        val bytes = byteArrayOf(0x01, 0x7e, 0x42, 0x53)
        val source = object : ByteArrayInputStream(bytes) {
            private var zeroNext = true
            override fun read(destination: ByteArray, offset: Int, length: Int): Int {
                zeroNext = !zeroNext
                return if (!zeroNext) 0 else super.read(destination, offset, length)
            }
        }
        val request = request(bytes)
        val store = FileCiphertextStore(root, bufferBytes = 2)
        store.put(request, source)
        assertEquals(request.descriptor, store.metadata(request.key)!!.descriptor)
    }

    @Test
    fun `Long lengths are not narrowed and impossible container length refuses before input`() = withRoot { root ->
        val store = FileCiphertextStore(root, bufferBytes = 1024)
        val digest = descriptor(byteArrayOf()).sha256
        val large = CiphertextPutRequest(
            CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random()),
            SyncStoreId.random(),
            CiphertextDescriptor(Int.MAX_VALUE.toLong() + 81, digest),
        )
        var readBudget = 0
        val unavailableSource = object : InputStream() {
            override fun read(): Int = throw IOException("synthetic unavailable source")
            override fun read(destination: ByteArray, offset: Int, length: Int): Int {
                readBudget = length
                throw IOException("synthetic unavailable source")
            }
        }
        assertFailsWith<IOException> { store.put(large, unavailableSource) }
        assertEquals(1024, readBudget)
        readBudget = 0
        val overflowing = large.copy(operationId = SyncStoreId.random(),
            descriptor = CiphertextDescriptor(Long.MAX_VALUE, digest))
        assertFailure(CiphertextStoreFailure.UNSUPPORTED) { store.put(overflowing, unavailableSource) }
        assertEquals(0, readBudget)
        assertNull(store.metadata(large.key))
    }

    @Test
    fun `interrupted upload remains invisible and can retry same intent`() = withRoot { root ->
        val bytes = ByteArray(400) { (it % 251).toByte() }
        val request = request(bytes)
        val store = FileCiphertextStore(root, bufferBytes = 31)
        val source = object : ByteArrayInputStream(bytes) {
            override fun read(destination: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(destination, offset, length)
                Thread.currentThread().interrupt()
                return count
            }
        }
        try {
            assertFailsWith<InterruptedIOException> { store.put(request, source) }
        } finally {
            Thread.interrupted()
        }
        assertNull(store.metadata(request.key))
        assertEquals(CiphertextPutDisposition.CREATED, store.put(request, bytes.inputStream()).disposition)
    }

    @Test
    fun `cooperating store instances serialize same ID writers`() = withRoot { root ->
        val first = FileCiphertextStore(root)
        val second = FileCiphertextStore(root)
        val bytes = ByteArray(130_003) { (it % 181).toByte() }
        val key = CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random())
        val ready = CountDownLatch(6)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(6)
        try {
            val results = (0..5).map { index -> pool.submit<CiphertextPutDisposition> {
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                (if (index % 2 == 0) first else second).put(request(bytes, key), bytes.inputStream()).disposition
            } }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val dispositions = results.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, dispositions.count { it == CiphertextPutDisposition.CREATED })
            assertEquals(5, dispositions.count { it == CiphertextPutDisposition.ALREADY_PRESENT })
        } finally {
            start.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `concurrent different content cannot replace the winner`() = withRoot { root ->
        val stores = listOf(FileCiphertextStore(root), FileCiphertextStore(root))
        val payloads = listOf(ByteArray(100_001) { 0x35 }, ByteArray(100_001) { 0x65 })
        val key = CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random())
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = payloads.mapIndexed { index, bytes -> pool.submit<Boolean> {
                check(start.await(10, TimeUnit.SECONDS))
                try {
                    stores[index].put(request(bytes, key), bytes.inputStream())
                    true
                } catch (error: CiphertextStoreException) {
                    assertEquals(CiphertextStoreFailure.OBJECT_CONFLICT, error.failure)
                    false
                }
            } }
            start.countDown()
            val outcomes = results.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it })
            val winner = outcomes.indexOf(true)
            val output = ByteArrayOutputStream()
            stores.first().readTo(key, output)
            assertContentEquals(payloads[winner], output.toByteArray())
        } finally {
            start.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `commit pages are bounded ordered provider bound and recoverable after reopen`() = withRoot { root ->
        val store = FileCiphertextStore(root)
        for (value in listOf(5, 1, 3, 2, 4)) putCommit(store, value)
        val unrelated = byteArrayOf(0x76)
        store.put(request(unrelated), unrelated.inputStream())
        val page1 = store.listCommits(pageSize = 2)
        assertEquals(listOf(id(1), id(2)), page1.entries.map { it.key.id })
        val page2 = FileCiphertextStore(root).listCommits(assertNotNull(page1.nextCursor), 2)
        assertEquals(listOf(id(3), id(4)), page2.entries.map { it.key.id })
        val page3 = store.listCommits(assertNotNull(page2.nextCursor), 2)
        assertEquals(listOf(id(5)), page3.entries.map { it.key.id })
        assertNull(page3.nextCursor)
        withRoot { other ->
            assertFailure(CiphertextStoreFailure.INVALID_CURSOR) {
                FileCiphertextStore(other).listCommits(page1.nextCursor, 2)
            }
        }
        assertFailure(CiphertextStoreFailure.INVALID_CURSOR) { store.listCommits(CommitCursor("../outside")) }
    }

    @Test
    fun `keyset scan explicitly needs restart to discover additions before cursor`() = withRoot { root ->
        val store = FileCiphertextStore(root)
        putCommit(store, 2)
        putCommit(store, 4)
        val first = store.listCommits(pageSize = 1)
        putCommit(store, 1)
        val continuation = store.listCommits(assertNotNull(first.nextCursor), 10)
        assertEquals(listOf(id(4)), continuation.entries.map { it.key.id })
        assertEquals(listOf(id(1), id(2), id(4)), store.listCommits().entries.map { it.key.id })
        assertEquals(CommitListingSemantics.KEYSET_RESCAN_REQUIRED, store.capabilities.commitListing)
    }

    @Test
    fun `corrupt stored bytes fail before emitting any content`() = withRoot { root ->
        val bytes = ByteArray(102) { (it * 3).toByte() }
        val request = request(bytes)
        val store = FileCiphertextStore(root)
        store.put(request, bytes.inputStream())
        val file = root.resolve("objects/${request.key.id.value}.obj")
        val corrupt = Files.readAllBytes(file).also { it[it.lastIndex] = (it.last() + 1).toByte() }
        Files.write(file, corrupt, WRITE, TRUNCATE_EXISTING)
        val output = ByteArrayOutputStream()
        assertFailure(CiphertextStoreFailure.INTEGRITY) { store.metadata(request.key) }
        assertFailure(CiphertextStoreFailure.INTEGRITY) { store.readTo(request.key, output, request.descriptor) }
        assertEquals(0, output.size())
        Files.write(file, corrupt.copyOf(19), WRITE, TRUNCATE_EXISTING)
        assertFailure(CiphertextStoreFailure.INTEGRITY) { FileCiphertextStore(root).metadata(request.key) }
    }

    @Test
    fun `authenticated caller descriptor mismatch refuses before output`() = withRoot { root ->
        val bytes = byteArrayOf(0x6a, 0x19, 0x28)
        val request = request(bytes)
        val store = FileCiphertextStore(root)
        store.put(request, bytes.inputStream())
        val output = ByteArrayOutputStream()
        assertFailure(CiphertextStoreFailure.INTEGRITY) {
            store.readTo(request.key, output, descriptor(byteArrayOf(0x7a, 0x19, 0x28)))
        }
        assertEquals(0, output.size())
    }

    @Test
    fun `abandoned private staging is removed at reopen and is never a discovered commit`() = withRoot { root ->
        FileCiphertextStore(root)
        val partial = root.resolve("staging/${SyncStoreId.random().value}.part")
        Files.write(partial, byteArrayOf(0x7a, 0x2e))
        val reopened = FileCiphertextStore(root)
        assertFalse(Files.exists(partial))
        assertTrue(reopened.listCommits().entries.isEmpty())
        assertEquals(0L, Files.list(root.resolve("objects")).use { it.count() })
    }

    @Test
    fun `path occupants cannot be treated as absent or overwritten`() = withRoot { root ->
        val store = FileCiphertextStore(root)
        val bytes = byteArrayOf(0x74)
        val request = request(bytes)
        val occupied = Files.createDirectory(root.resolve("objects/${request.key.id.value}.obj"))
        assertFailure(CiphertextStoreFailure.UNSAFE_PATH) { store.metadata(request.key) }
        assertFailure(CiphertextStoreFailure.UNSAFE_PATH) { store.put(request, bytes.inputStream()) }
        assertTrue(Files.isDirectory(occupied))
        Files.write(root.resolve("commits/unknown.txt"), byteArrayOf(0x76))
        assertFailure(CiphertextStoreFailure.UNSAFE_PATH) { store.listCommits() }
    }

    @Test
    fun `symbolic object link is refused without reading or replacing its target`() = withRoot { root ->
        val store = FileCiphertextStore(root)
        val bytes = byteArrayOf(0x65, 0x31, 0x54)
        val request = request(bytes)
        val outside = Files.createTempFile("ccb-store-outside-", ".bin")
        val link = root.resolve("objects/${request.key.id.value}.obj")
        try {
            Files.write(outside, bytes)
            try {
                Files.createSymbolicLink(link, outside)
            } catch (failure: Exception) {
                assumeNoException("This path probe requires symbolic-link support", failure)
            }
            assertFailure(CiphertextStoreFailure.UNSAFE_PATH) { store.metadata(request.key) }
            assertFailure(CiphertextStoreFailure.UNSAFE_PATH) { store.put(request, bytes.inputStream()) }
            assertContentEquals(bytes, Files.readAllBytes(outside))
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `symbolic ancestor root is refused without populating the target`() = withRoot { root ->
        val outside = Files.createTempDirectory("ccb-store-outside-root-")
        val link = root.resolve("linked-root")
        try {
            try {
                Files.createSymbolicLink(link, outside)
            } catch (failure: Exception) {
                assumeNoException("This path probe requires symbolic-link support", failure)
            }
            assertFailure(CiphertextStoreFailure.UNSAFE_PATH) { FileCiphertextStore(link.resolve("store")) }
            assertEquals(0L, Files.list(outside).use { it.count() })
        } finally {
            // Unlink explicitly before recursive synthetic-root cleanup; never traverse the link.
            Files.deleteIfExists(link)
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `parent traversal cannot erase a symbolic ancestor and silently select another root`() = withRoot { root ->
        val outside = Files.createTempDirectory("ccb-store-parent-outside-")
        val link = root.resolve("linked")
        try {
            try { Files.createSymbolicLink(link, outside) }
            catch (failure: Exception) { assumeNoException("This path probe requires symbolic-link support", failure) }
            assertFailure(CiphertextStoreFailure.UNSAFE_PATH) { FileCiphertextStore(link.resolve("../must-not-be-created")) }
            assertFalse(Files.exists(root.resolve("must-not-be-created")))
            assertFalse(Files.exists(outside.parent.resolve("must-not-be-created")))
            assertEquals(0L, Files.list(outside).use { it.count() })
        } finally { Files.deleteIfExists(link); Files.deleteIfExists(outside) }
    }

    @Test
    fun `reentrant caller source is rejected instead of taking overlapping process locks`() = withRoot { root ->
        val store = FileCiphertextStore(root)
        val bytes = byteArrayOf(0x56)
        val request = request(bytes)
        val source = object : InputStream() {
            override fun read(): Int {
                store.metadata(request.key)
                return -1
            }
        }
        assertFailure(CiphertextStoreFailure.UNSUPPORTED) { store.put(request, source) }
        assertNull(store.metadata(request.key))
        assertEquals(CiphertextPutDisposition.CREATED, store.put(request, bytes.inputStream()).disposition)
    }

    @Test
    fun `identifiers and request budgets reject paths titles and unbounded allocations`() = withRoot { root ->
        for (unsafe in listOf("../outside", "private chat title", "a".repeat(64), "A".repeat(32), "")) {
            assertFailsWith<IllegalArgumentException> { SyncStoreId.parse(unsafe) }
        }
        assertFailsWith<IllegalArgumentException> { CiphertextDescriptor(-1, descriptor(byteArrayOf()).sha256) }
        assertFailsWith<IllegalArgumentException> { FileCiphertextStore(root, bufferBytes = Int.MAX_VALUE) }
        val store = FileCiphertextStore(root)
        assertFailsWith<IllegalArgumentException> { store.listCommits(pageSize = 0) }
        assertFailsWith<IllegalArgumentException> { store.listCommits(pageSize = Int.MAX_VALUE) }
    }

    private fun putCommit(store: CiphertextStore, value: Int) {
        val bytes = byteArrayOf(value.toByte(), (value * 17).toByte())
        val key = CiphertextObjectKey(CiphertextObjectKind.COMMIT, id(value))
        store.put(request(bytes, key), bytes.inputStream())
    }

    private fun id(value: Int): SyncStoreId = SyncStoreId.parse(value.toString(16).padStart(32, '0'))

    private fun request(
        bytes: ByteArray,
        key: CiphertextObjectKey? = null,
        kind: CiphertextObjectKind = CiphertextObjectKind.BLOB,
    ) = CiphertextPutRequest(key ?: CiphertextObjectKey(kind, SyncStoreId.random()), SyncStoreId.random(), descriptor(bytes))

    private fun descriptor(bytes: ByteArray): CiphertextDescriptor = CiphertextDescriptor(
        bytes.size.toLong(),
        CiphertextSha256.parse(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }),
    )

    private fun assertFailure(expected: CiphertextStoreFailure, block: () -> Unit) {
        assertEquals(expected, assertFailsWith<CiphertextStoreException>(block = block).failure)
    }

    private fun withRoot(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("ccb-ciphertext-store-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private class FailingSource(bytes: ByteArray, private var availableBeforeFailure: Int) : InputStream() {
        private val source = bytes.inputStream()
        override fun read(): Int {
            if (availableBeforeFailure == 0) throw IOException("synthetic interrupted transport")
            availableBeforeFailure--
            return source.read()
        }
        override fun read(destination: ByteArray, offset: Int, length: Int): Int {
            if (availableBeforeFailure == 0) throw IOException("synthetic interrupted transport")
            val count = source.read(destination, offset, minOf(length, availableBeforeFailure))
            if (count > 0) availableBeforeFailure -= count
            return count
        }
    }
}
