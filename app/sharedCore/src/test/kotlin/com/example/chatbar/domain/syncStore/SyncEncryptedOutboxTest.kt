package com.example.chatbar.domain.syncStore

import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SyncEncryptedOutboxTest {
    private fun <T> privateRoot(work: (Path, Path, Path) -> T): T {
        val root = Files.createTempDirectory("sync-outbox-synthetic-").toRealPath()
        try {
            val outbox = Files.createDirectory(root.resolve("outbox"))
            val source = root.resolve("source.bin")
            Files.write(source, ByteArray(1024 * 1024 + 117) { (it % 251).toByte() })
            return work(root, outbox, source)
        } finally { check(root.toFile().deleteRecursively()) }
    }
    private fun context(cipher: SyncStreamingCipher) = SyncEncryptionContext(cipher.group, cipher.epoch,
        CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random()))

    @Test fun concurrentOwnersReuseOneReadyRequestAndCallbackReentryCannotAcquireQueueAuthority() = privateRoot { root, local, source ->
        val cipher = SyncStreamingCipher.create(); val binding = context(cipher); val operation = SyncStoreId.random()
        val ready = CountDownLatch(1); val release = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val first = SyncEncryptedOutbox(local, cipher) { if (it == "REQUEST_READY") { ready.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
            val second = SyncEncryptedOutbox(local, cipher)
            val a = pool.submit<SyncOutboxEntry> { first.prepare(operation, binding, source) }
            check(ready.await(10, TimeUnit.SECONDS))
            val b = pool.submit<SyncOutboxEntry> { second.prepare(operation, binding, source) }
            release.countDown(); assertEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))
            val backend = FileCiphertextStore(root.resolve("backend"))
            lateinit var reentrant: SyncEncryptedOutbox
            reentrant = SyncEncryptedOutbox(local, cipher) { if (it == "BACKEND_RETURNED") reentrant.entry(operation) }
            assertEquals("SYNC_OUTBOX_REENTRY", assertFailsWith<IllegalStateException> { reentrant.upload(operation, backend) }.message)
            assertFalse(requireNotNull(second.entry(operation)).uploaded)
            assertEquals(CiphertextPutDisposition.ALREADY_PRESENT, second.upload(operation, backend).disposition)
            assertTrue(requireNotNull(second.entry(operation)).uploaded)
        } finally { release.countDown(); pool.shutdownNow(); check(pool.awaitTermination(20, TimeUnit.SECONDS)) }
    }

    @Test fun aLostBackendResponseRetriesIdenticalCiphertextAfterReopenWithoutItsSource() = privateRoot { root, local, source ->
        val cipher = SyncStreamingCipher.create(); val binding = context(cipher); val operation = SyncStoreId.random()
        val outbox = SyncEncryptedOutbox(local, cipher); val ready = outbox.prepare(operation, binding, source)
        assertEquals(ready, outbox.prepare(operation, binding, source)); assertFalse(ready.uploaded)
        val backend = FileCiphertextStore(root.resolve("backend"))
        var fail = true
        val unreliable = object : CiphertextStore by backend {
            override fun put(request: CiphertextPutRequest, input: InputStream): CiphertextPutResult {
                val result = backend.put(request, input)
                if (fail) { fail = false; throw IOException("synthetic lost response after actual publication") }
                return result
            }
        }
        assertFailsWith<IOException> { outbox.upload(operation, unreliable) }
        assertFalse(requireNotNull(outbox.entry(operation)).uploaded)
        val frozen = Files.readAllBytes(local.resolve("${operation.value}/ciphertext.bin"))
        Files.delete(source)
        val wrapped = cipher.protectedKeyset { it.reversedArray() }
        val reopenedCipher = SyncStreamingCipher.open(cipher.group, cipher.epoch, wrapped) { it.reversedArray() }
        val reopened = SyncEncryptedOutbox(local, reopenedCipher)
        assertEquals(ready, reopened.entry(operation))
        assertEquals(CiphertextPutDisposition.ALREADY_PRESENT, reopened.upload(operation, unreliable).disposition)
        assertTrue(requireNotNull(reopened.entry(operation)).uploaded)
        assertContentEquals(frozen, Files.readAllBytes(local.resolve("${operation.value}/ciphertext.bin")))
        assertEquals(ready.request.descriptor, backend.metadata(binding.key)?.descriptor)
    }

    @Test fun crashesBeforeCiphertextOrRequestSealRetainBindingsAndRecoverTheExactReadyBytes() = privateRoot { _, local, source ->
        val cipher = SyncStreamingCipher.create(); val binding = context(cipher)
        for (stage in listOf("BINDING_READY", "CIPHERTEXT_READY", "REQUEST_READY")) {
            val operation = SyncStoreId.random()
            val fault = SyncEncryptedOutbox(local, cipher) { if (it == stage) throw IOException("synthetic power loss") }
            assertFailsWith<IOException> { fault.prepare(operation, binding, source) }
            val file = local.resolve("${operation.value}/ciphertext.bin")
            val frozen = if (Files.exists(file)) Files.readAllBytes(file) else null
            val healthy = SyncEncryptedOutbox(local, cipher)
            val ready = healthy.prepare(operation, binding, source)
            assertEquals(operation, ready.request.operationId); assertFalse(ready.uploaded)
            if (frozen != null) assertContentEquals(frozen, Files.readAllBytes(file))
            assertEquals(ready, healthy.entry(operation))
        }
    }

    @Test fun changedSourceOrObjectCannotReuseAnOperationAndDamagedCiphertextNeverReachesTheBackend() = privateRoot { root, local, source ->
        val cipher = SyncStreamingCipher.create(); val binding = context(cipher); val operation = SyncStoreId.random()
        val outbox = SyncEncryptedOutbox(local, cipher); val ready = outbox.prepare(operation, binding, source)
        Files.write(source, byteArrayOf(9, 9, 9))
        assertEquals("SYNC_OUTBOX_OPERATION_CHANGED", assertFailsWith<IllegalArgumentException> { outbox.prepare(operation, binding, source) }.message)
        assertFails { outbox.prepare(operation, binding.copy(key = binding.key.copy(id = SyncStoreId.random())), source) }
        val file = local.resolve("${operation.value}/ciphertext.bin")
        val damaged = Files.readAllBytes(file).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }; Files.write(file, damaged)
        val backend = FileCiphertextStore(root.resolve("backend")); var called = false
        val watched = object : CiphertextStore by backend {
            override fun put(request: CiphertextPutRequest, input: InputStream): CiphertextPutResult { called = true; return backend.put(request, input) }
        }
        assertEquals("SYNC_OUTBOX_CIPHERTEXT_CHANGED", assertFailsWith<IllegalArgumentException> { outbox.upload(operation, watched) }.message)
        assertFalse(called); assertNull(backend.metadata(ready.request.key))
    }

    @Test fun aLostLocalReceiptRetriesThePublishedObjectAndUnknownFilesNeverBecomeQueueAuthority() = privateRoot { root, local, source ->
        val cipher = SyncStreamingCipher.create(); val binding = context(cipher); val operation = SyncStoreId.random()
        val backend = FileCiphertextStore(root.resolve("backend"))
        val outbox = SyncEncryptedOutbox(local, cipher) { if (it == "BACKEND_RETURNED") throw IOException("synthetic receipt failure") }
        val ready = outbox.prepare(operation, binding, source)
        assertFailsWith<IOException> { outbox.upload(operation, backend) }
        assertEquals(ready.request.descriptor, backend.metadata(binding.key)?.descriptor)
        val recovered = SyncEncryptedOutbox(local, cipher)
        assertFalse(requireNotNull(recovered.entry(operation)).uploaded)
        assertEquals(CiphertextPutDisposition.ALREADY_PRESENT, recovered.upload(operation, backend).disposition)
        assertTrue(requireNotNull(recovered.entry(operation)).uploaded)
        val other = SyncStoreId.random(); val directory = Files.createDirectory(local.resolve(other.value))
        val unknown = directory.resolve("unknown.bin"); Files.write(unknown, byteArrayOf(1, 2, 3))
        assertEquals("SYNC_OUTBOX_UNKNOWN_PREPARATION", assertFailsWith<IllegalArgumentException> { recovered.prepare(other, binding, source) }.message)
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(unknown))
    }

    @Test fun orphanPreparationIsOwnedButChangedKeyOrDamagedReceiptRefusesWithoutPublication() = privateRoot { root, local, source ->
        val cipher = SyncStreamingCipher.create(); val binding = context(cipher); val operation = SyncStoreId.random()
        val directory = Files.createDirectory(local.resolve(operation.value))
        val orphan = Files.createTempFile(directory, ".sync-aead-", ".preparing"); Files.write(orphan, byteArrayOf(1))
        val outbox = SyncEncryptedOutbox(local, cipher); outbox.prepare(operation, binding, source)
        assertFalse(Files.exists(orphan))
        val changedKey = SyncEncryptedOutbox(local, SyncStreamingCipher.create(cipher.group, cipher.epoch))
        assertFails { changedKey.entry(operation) }
        val backend = FileCiphertextStore(root.resolve("backend")); outbox.upload(operation, backend)
        val receipt = directory.resolve("uploaded.bin"); Files.write(receipt, byteArrayOf(1))
        assertEquals("SYNC_OUTBOX_RECEIPT", assertFailsWith<IllegalArgumentException> { outbox.entry(operation) }.message)
    }
}
