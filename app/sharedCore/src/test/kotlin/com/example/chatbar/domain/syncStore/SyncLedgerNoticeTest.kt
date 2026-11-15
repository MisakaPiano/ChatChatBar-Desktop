package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.LanSyncTlsIdentity
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

/** Actual root signatures and bounded HPKE cells; no group secret is delivered to a revoked actor. */
class SyncLedgerNoticeTest {
    private class Group {
        val owner = LanSyncTlsIdentity.create(); val peer = LanSyncTlsIdentity.create()
        val recipient = SyncDeviceEncryptionKey.create(peer.fingerprint)
        val ownerRecipient = SyncDeviceEncryptionKey.create(owner.fingerprint)
        val key = SyncStreamingCipher.create()
        val members = listOf(owner to ownerRecipient, peer to recipient).associate { (identity, encryption) -> identity.fingerprint to
            SyncGroupMember(Base64.getEncoder().encodeToString(identity.certificate.encoded), encryption.publicKey.encoded) }
        fun sign(row: SyncDeviceLedgerRecord) = row.copy(signature = Base64.getEncoder().encodeToString(owner.signSyncGroupPayload(row.signingBytes())))
        val genesis = sign(SyncDeviceLedgerRecord(key.group, SyncStoreId.random(), null, 1, key.epoch, key.keyCommitment(),
            setOf("synthetic_records"), members, emptySet(), "unsigned"))
        val trust = SyncGroupTrust(key.group, genesis.reference(), members.getValue(owner.fingerprint).certificate)
        val ledger = SyncDeviceLedger(trust).also { it.offer(genesis) }
        fun advance(revoke: Boolean = false): SyncDeviceLedgerRecord {
            val old = ledger.current(); val nextKey = if (revoke) SyncStreamingCipher.create(key.group) else null
            return sign(old.copy(id = SyncStoreId.random(), parent = old.reference(), sequence = old.sequence + 1,
                epoch = nextKey?.epoch ?: old.epoch, keyCommitment = nextKey?.keyCommitment() ?: old.keyCommitment,
                members = old.members + (peer.fingerprint to old.members.getValue(peer.fingerprint).copy(active = !revoke && old.members.getValue(peer.fingerprint).active))))
                .also { assertEquals(SyncVersionState.ADMITTED, ledger.offer(it)) }
        }
        fun notice() = SyncLedgerNotice.create(ledger, peer.fingerprint, SyncStoreId.random(), owner::signSyncGroupPayload)
        fun objectKey(notice: SyncLedgerNotice) = CiphertextObjectKey(CiphertextObjectKind.COMMIT, notice.context.objectId)
        fun open(notice: SyncLedgerNotice) = notice.open(trust, recipient, objectKey(notice))
    }
    private fun withRoot(work: (Path) -> Unit) {
        val root = Files.createTempDirectory("synthetic-ledger-notices-").toRealPath()
        try { work(root) } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun aChunkedNoticeStopsExactlyAtTheFirstRevocationEvenAfterManyUnpublishedRevisions() {
        val g = Group()
        assertEquals("SYNC_LEDGER_NOTICE_NOT_REVOKED", assertFailsWith<IllegalArgumentException> { g.notice() }.message)
        repeat(24) { g.advance() }; val revoked = g.advance(true); g.advance(); g.advance()
        val notice = SyncLedgerNoticeCodec.decode(SyncLedgerNoticeCodec.encode(g.notice()))
        assertTrue(notice.context.plaintext.length > 32 * 1024); assertTrue(notice.cells.size > 1)
        val opened = g.open(notice)
        assertEquals(g.peer.fingerprint, opened.recipient); assertEquals(g.ledger.history(revoked.reference()), opened.records)
        assertEquals(revoked.reference(), opened.records.last().reference())
        assertFalse(opened.records.last().members.getValue(g.peer.fingerprint).active)
        // No later roster record or key is necessary to verify the revocation from pinned genesis.
        assertTrue(opened.records.none { it.reference() == g.ledger.current().reference() })
    }

    @Test fun wrongRootRecipientPhysicalKeyAndChangedOrReorderedCellsFailBeforeAnyCustodyAdmission() {
        val g = Group(); repeat(24) { g.advance() }; g.advance(true); val notice = g.notice()
        assertFails { notice.open(g.trust, g.ownerRecipient, g.objectKey(notice)) }
        assertFails { notice.open(g.trust, SyncDeviceEncryptionKey.create(g.peer.fingerprint), g.objectKey(notice)) }
        assertFails { notice.open(g.trust, g.recipient, CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random())) }
        assertFails { notice.open(g.trust, g.recipient, CiphertextObjectKey(CiphertextObjectKind.BLOB, notice.context.objectId)) }
        val foreign = notice.copy(signature = Base64.getEncoder().encodeToString(g.peer.signSyncGroupPayload(notice.signingBytes())))
        assertEquals("SYNC_LEDGER_NOTICE_SIGNATURE", assertFailsWith<IllegalArgumentException> { g.open(foreign) }.message)
        val changed = notice.copy(cells = notice.cells.reversed())
        assertEquals("SYNC_LEDGER_NOTICE_SIGNATURE", assertFailsWith<IllegalArgumentException> { g.open(changed) }.message)
        // Even a new valid outer signature cannot make HPKE cells valid at different positions.
        val resigned = changed.copy(signature = Base64.getEncoder().encodeToString(g.owner.signSyncGroupPayload(changed.signingBytes())))
        assertFails { g.open(resigned) }
        assertFails { g.open(notice.copy(context = notice.context.copy(revocation = g.genesis.reference()))) }
        val encoded = SyncLedgerNoticeCodec.encode(notice)
        assertFails { SyncLedgerNoticeCodec.decode(encoded.copyOf(encoded.size - 1)) }
        assertFails { SyncLedgerNoticeCodec.decode(ByteArray(SyncLedgerNoticeCodec.MAX_BYTES + 1)) }
    }

    @Test fun retainedNoticeSurvivesLostLocalAndRemoteRepliesWithoutResigningAndUploadsIndependently() = withRoot { root ->
        val g = Group(); val revoked = g.advance(true); val directory = Files.createDirectory(root.resolve("queue"))
        val operation = SyncStoreId.random()
        val interrupted = SyncLedgerNoticeQueue(directory, g.trust, g.owner.fingerprint, { g.ledger }) {
            if (it == "REVOCATION_NOTICE_RETAINED") throw IOException("SYNTHETIC_LOCAL_REPLY_LOST")
        }
        assertFailsWith<IOException> { interrupted.prepare(operation, g.peer.fingerprint, g.owner::signSyncGroupPayload) }
        fun reopen() = SyncLedgerNoticeQueue(directory, g.trust, g.owner.fingerprint, { g.ledger })
        val retained = requireNotNull(reopen().entry(operation)); g.advance()
        assertEquals(retained, reopen().prepare(operation, g.peer.fingerprint) { error("SYNTHETIC_SIGNER_MUST_NOT_RUN") })
        val first = FileCiphertextStore(root.resolve("first")); val second = FileCiphertextStore(root.resolve("second"))
        val lost = object : CiphertextStore by first {
            override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult {
                first.put(request, source); throw IOException("SYNTHETIC_REMOTE_REPLY_LOST")
            }
        }
        assertFailsWith<IOException> { reopen().upload(operation, lost) }
        assertEquals(CiphertextPutDisposition.ALREADY_PRESENT, reopen().upload(operation, first).disposition)
        assertNull(second.metadata(retained.request.key)); reopen().upload(operation, second)
        assertEquals(retained.request.descriptor, second.metadata(retained.request.key)?.descriptor)
        assertEquals(revoked.reference(), g.open(retained.notice).records.last().reference())
        assertEquals(retained, reopen().entry(operation))
        assertFails { SyncLedgerNoticeQueue(directory, g.trust, g.peer.fingerprint, { g.ledger }).upload(operation, first) }
    }
}
