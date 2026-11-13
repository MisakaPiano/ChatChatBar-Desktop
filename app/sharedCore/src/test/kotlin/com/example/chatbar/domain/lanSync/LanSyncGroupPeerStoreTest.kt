package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

/** Live policy over the real immutable file backend; TLS session extraction remains the transport owner. */
class LanSyncGroupPeerStoreTest {
    private class Fixture(val root: Path) {
        val f = LanSyncGroupPublicationQueueTest.Fixture(root)
        val noticeRoot = Files.createDirectory(root.resolve("notices"))
        var current = f.ledger
        var entry: SyncLedgerNoticeEntry? = null
        fun certificate(identity: LanSyncTlsIdentity) = Base64.getEncoder().encodeToString(identity.certificate.encoded)
        fun store(identity: LanSyncTlsIdentity = f.sender) = LanSyncGroupPeerStore(f.ledger.trustAnchor, certificate(f.owner), certificate(identity),
            { current }, f.backend, { device -> entry?.takeIf { it.notice.context.recipient == device } })
        fun revoke(publish: Boolean = true) {
            val key = SyncStreamingCipher.create(f.cipher.group)
            val row = f.sign(f.genesis.copy(id = SyncStoreId.random(), parent = f.genesis.reference(), sequence = 2,
                epoch = key.epoch, keyCommitment = key.keyCommitment(), members = f.members +
                    (f.sender.fingerprint to f.members.getValue(f.sender.fingerprint).copy(active = false))))
            current = SyncDeviceLedger(f.ledger.trustAnchor).also { it.offer(f.genesis); it.offer(row) }
            val queue = SyncLedgerNoticeQueue(noticeRoot, f.ledger.trustAnchor, f.owner.fingerprint, { current })
            entry = queue.prepare(SyncStoreId.random(), f.sender.fingerprint, f.owner::signSyncGroupPayload)
            if (publish) queue.upload(requireNotNull(entry).request.operationId, f.backend)
        }
        fun publishNotice() {
            val notice = requireNotNull(entry)
            f.backend.put(notice.request, SyncLedgerNoticeCodec.encode(notice.notice).inputStream())
        }
        fun request(bytes: ByteArray, kind: CiphertextObjectKind = CiphertextObjectKind.BLOB) =
            CiphertextPutRequest(CiphertextObjectKey(kind, SyncStoreId.random()), SyncStoreId.random(), SyncKeyDeliveryCodec.descriptor(bytes))
    }
    private fun withFixture(work: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("synthetic-group-peer-store-").toRealPath()
        try { work(Fixture(root)) } finally { check(root.toFile().deleteRecursively()) }
    }
    private fun denied(work: () -> Unit) = assertEquals(CiphertextStoreFailure.PERMISSION,
        assertFailsWith<CiphertextStoreException> { work() }.failure)

    @Test fun aKnownRevokedPeerCanDiscoverAndReadOnlyItsOwnAuthenticNotice() = withFixture { f ->
        val store = f.store(); val bytes = ByteArray(211) { 17 }; val request = f.request(bytes, CiphertextObjectKind.COMMIT)
        store.put(request, bytes.inputStream()); assertEquals(request.descriptor, store.metadata(request.key)?.descriptor)
        f.revoke(); val notice = requireNotNull(f.entry)
        assertEquals(listOf(CiphertextObjectMetadata(notice.request.key, notice.request.descriptor)), store.listCommits().entries)
        denied { store.metadata(request.key) }; denied { store.readTo(request.key, ByteArrayOutputStream()) }
        denied { store.put(f.request(bytes), bytes.inputStream()) }
        val output = ByteArrayOutputStream(); assertNotNull(store.readTo(notice.request.key, output, notice.request.descriptor))
        val decoded = SyncLedgerNoticeCodec.decode(output.toByteArray())
        assertEquals(f.current.current().reference(), decoded.open(f.f.ledger.trustAnchor, f.f.recipients.getValue(f.f.sender.fingerprint), notice.request.key).records.last().reference())
        val unknown = LanSyncTlsIdentity.create(); denied { f.store(unknown).listCommits() }
        assertEquals(CiphertextStoreFailure.INVALID_CURSOR, assertFailsWith<CiphertextStoreException> {
            store.listCommits(CommitCursor("previous-active-cursor"))
        }.failure)
    }

    @Test fun learningARevocationDuringAReadStopsFurtherDataWithoutDestroyingTheStoredOriginal() = withFixture { f ->
        val bytes = ByteArray(2 * 1024 * 1024) { it.toByte() }; val request = f.request(bytes); f.f.backend.put(request, bytes.inputStream())
        val store = f.store(); var received = 0L; var revoked = false
        val output = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                received += length
                if (!revoked) { revoked = true; f.revoke(publish = false) }
            }
        }
        denied { store.readTo(request.key, output, request.descriptor) }
        assertTrue(received in 1 until bytes.size.toLong()); assertEquals(request.descriptor, f.f.backend.metadata(request.key)?.descriptor)
        f.publishNotice(); assertEquals(1, store.listCommits().entries.size)
    }

    @Test fun learningARevocationDuringAnUploadNeverPublishesAPartialObjectOrConsumesCallerOwnership() = withFixture { f ->
        val bytes = ByteArray(2 * 1024 * 1024) { it.toByte() }; val request = f.request(bytes); var read = false; var closed = false
        val source = object : java.io.ByteArrayInputStream(bytes) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(bytes, offset, length)
                if (!read) { read = true; f.revoke(publish = false) }; return count
            }
            override fun close() { closed = true; super.close() }
        }
        denied { f.store().put(request, source) }
        assertFalse(closed); assertNull(f.f.backend.metadata(request.key))
        f.publishNotice(); assertTrue(f.store().listCommits().entries.isNotEmpty())
    }

    @Test fun aMissingOrSubstitutedNoticeDoesNotRestoreOrdinaryAccess() = withFixture { f ->
        val bytes = byteArrayOf(1); val request = f.request(bytes); f.f.backend.put(request, bytes.inputStream())
        f.revoke(); val valid = requireNotNull(f.entry); f.entry = null
        assertEquals("SYNC_GROUP_PEER_NOTICE_MISSING", assertFailsWith<IllegalArgumentException> { f.store().listCommits() }.message)
        f.entry = valid.copy(request = valid.request.copy(descriptor = request.descriptor))
        assertEquals("SYNC_GROUP_PEER_NOTICE_CHANGED", assertFailsWith<IllegalArgumentException> { f.store().listCommits() }.message)
        denied { f.store().put(request, bytes.inputStream()) }
        val rootRevoked = LanSyncGroupPeerStore(f.f.ledger.trustAnchor, f.certificate(f.f.sender), f.certificate(f.f.owner),
            { f.current }, f.f.backend, { valid })
        denied { rootRevoked.metadata(request.key) }
    }
}
