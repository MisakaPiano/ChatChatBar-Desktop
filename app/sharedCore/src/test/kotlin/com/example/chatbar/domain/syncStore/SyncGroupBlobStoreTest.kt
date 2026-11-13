package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.LanSyncTlsIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

class SyncGroupBlobStoreTest {
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun withRoot(work: (Path) -> Unit) {
        val root = Files.createTempDirectory("sync-blob-synthetic-").toRealPath()
        try { work(root) } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun reencryptingOfflineContentPreservesTheSignedLogicalVersionAndAuthorSequence() = withRoot { root ->
        val work = Files.createDirectory(root.resolve("work")); val oldQueue = Files.createDirectory(root.resolve("old-queue"))
        val newQueue = Files.createDirectory(root.resolve("new-queue")); val backend = FileCiphertextStore(root.resolve("backend"))
        val oldCipher = SyncStreamingCipher.create(); val newCipher = SyncStreamingCipher.create(oldCipher.group)
        val oldOutbox = SyncEncryptedOutbox(oldQueue, oldCipher); val newOutbox = SyncEncryptedOutbox(newQueue, newCipher)
        val blobs = SyncGroupBlobStore(oldCipher.group, work); val codec = SyncGroupObjectCodec(work)
        val bytes = ByteArray(2 * 1024 * 1024 + 117) { (it % 251).toByte() }
        val source = root.resolve("unit.bin"); Files.write(source, bytes)
        val expected = SyncPlaintextIdentity(bytes.size.toLong(), hash(bytes))
        val oldBlobOperation = SyncStoreId.random(); val oldBlobKey = CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random())
        val oldBinding = blobs.enqueue(oldOutbox, oldBlobOperation, oldBlobKey, oldCipher, source, expected)
        val device = LanSyncTlsIdentity.create(); val member = SyncGroupMember(Base64.getEncoder().encodeToString(device.certificate.encoded))
        fun sign(row: SyncDeviceLedgerRecord) = row.copy(signature = Base64.getEncoder().encodeToString(device.signSyncGroupPayload(row.signingBytes())))
        val genesis = sign(SyncDeviceLedgerRecord(oldCipher.group, SyncStoreId.random(), null, 1, oldCipher.epoch, oldCipher.keyCommitment(),
            mapOf(device.fingerprint to member), emptySet(), "unsigned"))
        val latest = sign(genesis.copy(id = SyncStoreId.random(), parent = genesis.reference(), sequence = 2, epoch = newCipher.epoch, keyCommitment = newCipher.keyCommitment()))
        val version = SyncCausalVersion(oldCipher.group, SyncStoreId.random(), genesis.reference(), device.fingerprint, 1,
            null, emptySet(), mapOf("conversation/a" to SyncUnitChange(expected.sha256, expected, emptySet())), "unsigned").let {
            it.copy(signature = Base64.getEncoder().encodeToString(device.signSyncGroupPayload(it.signingBytes())))
        }
        val oldMetadataOperation = SyncStoreId.random(); val oldMetadataKey = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random())
        codec.enqueue(oldOutbox, oldMetadataOperation, oldMetadataKey, oldCipher,
            SyncGroupEnvelope(2, SyncGroupObjectKind.VERSION, version = version, blobs = mapOf(expected.sha256 to oldBinding)))
        // Learn a key rotation before either old offline object reaches the backend.
        val newBlobOperation = SyncStoreId.random(); val newBlobKey = CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random())
        val newBinding = blobs.enqueue(newOutbox, newBlobOperation, newBlobKey, newCipher, source, expected)
        newOutbox.upload(newBlobOperation, backend)
        val newMetadataOperation = SyncStoreId.random(); val newMetadataKey = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random())
        val metadata = codec.enqueue(newOutbox, newMetadataOperation, newMetadataKey, newCipher,
            SyncGroupEnvelope(2, SyncGroupObjectKind.VERSION, version = version, blobs = mapOf(expected.sha256 to newBinding)))
        newOutbox.upload(newMetadataOperation, backend)
        val read = codec.read(backend, newCipher, metadata.context, metadata.request.descriptor, version.reference())
        assertEquals(version, read.envelope.version); assertEquals(1L, read.envelope.version?.authorSequence)
        assertNotEquals(oldBinding.key, newBinding.key); assertNotEquals(oldBinding.descriptor.sha256, newBinding.descriptor.sha256)
        assertNull(backend.metadata(oldBlobKey)); assertNull(backend.metadata(oldMetadataKey))
        assertFalse(requireNotNull(oldOutbox.entry(oldBlobOperation)).uploaded)
        assertFalse(requireNotNull(oldOutbox.entry(oldMetadataOperation)).uploaded)
        val ledger = SyncDeviceLedger(SyncGroupTrust(oldCipher.group, genesis.reference(), member.certificate))
        ledger.offer(latest); ledger.offer(genesis)
        val history = SyncCausalHistory(oldCipher.group, ledger.admission(listOf(version), listOf(read)))
        assertEquals(SyncVersionState.ADMITTED, history.offer(requireNotNull(read.envelope.version)))
        blobs.read(backend, newCipher, read.envelope.blobs.getValue(expected.sha256), expected).use {
            assertEquals(expected, it.identity); assertContentEquals(bytes, Files.readAllBytes(it.path))
        }
        assertEquals("SYNC_GROUP_BLOB_CONTEXT", assertFailsWith<IllegalArgumentException> { blobs.read(backend, oldCipher, newBinding, expected) }.message)
        assertTrue(Files.newDirectoryStream(work).use { !it.iterator().hasNext() })
    }

    @Test fun missingContentAndWrongPlaintextIdentitiesNeverReturnAReadyBlob() = withRoot { root ->
        val work = Files.createDirectory(root.resolve("work")); val queue = Files.createDirectory(root.resolve("queue"))
        val cipher = SyncStreamingCipher.create(); val outbox = SyncEncryptedOutbox(queue, cipher)
        val backend = FileCiphertextStore(root.resolve("backend")); val blobs = SyncGroupBlobStore(cipher.group, work)
        val bytes = byteArrayOf(7, 1, 8, 2, 8); val source = root.resolve("source.bin"); Files.write(source, bytes)
        val expected = SyncPlaintextIdentity(bytes.size.toLong(), hash(bytes))
        val operation = SyncStoreId.random(); val key = CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random())
        val wrong = expected.copy(sha256 = hash(byteArrayOf(9)))
        assertEquals("SYNC_OUTBOX_SOURCE_CHANGED", assertFailsWith<IllegalArgumentException> { blobs.enqueue(outbox, operation, key, cipher, source, wrong) }.message)
        val binding = blobs.enqueue(outbox, operation, key, cipher, source, expected)
        assertEquals("SYNC_GROUP_BLOB_MISSING", assertFailsWith<IllegalArgumentException> { blobs.read(backend, cipher, binding, expected) }.message)
        outbox.upload(operation, backend)
        assertEquals("SYNC_GROUP_BLOB_PLAINTEXT_IDENTITY", assertFailsWith<IllegalArgumentException> { blobs.read(backend, cipher, binding, wrong) }.message)
        // Even a tampered encrypted mapping cannot substitute different plaintext for the signed hash.
        assertEquals("SYNC_GROUP_BLOB_PLAINTEXT_IDENTITY", assertFailsWith<IllegalArgumentException> {
            blobs.read(backend, cipher, binding.copy(plaintext = wrong), wrong)
        }.message)
        assertTrue(Files.newDirectoryStream(work).use { !it.iterator().hasNext() })
    }
}
