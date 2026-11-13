package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.LanSyncTlsIdentity
import java.util.Base64
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlin.test.*

/** Real root signatures and HPKE; platform custody and live enrollment remain separate. */
class SyncGroupKeyDeliveryTest {
    private class Group {
        val owner = LanSyncTlsIdentity.create()
        val peer = LanSyncTlsIdentity.create()
        val recipient = SyncDeviceEncryptionKey.create(peer.fingerprint)
        val ownerRecipient = SyncDeviceEncryptionKey.create(owner.fingerprint)
        val key = SyncStreamingCipher.create()
        private fun member(identity: LanSyncTlsIdentity, key: SyncDeviceEncryptionKey) = SyncGroupMember(
            Base64.getEncoder().encodeToString(identity.certificate.encoded), key.publicKey.encoded)
        fun sign(row: SyncDeviceLedgerRecord) = row.copy(signature = Base64.getEncoder().encodeToString(owner.signSyncGroupPayload(row.signingBytes())))
        val genesis = sign(SyncDeviceLedgerRecord(key.group, SyncStoreId.random(), null, 1, key.epoch, key.keyCommitment(),
            setOf("synthetic_records"), mapOf(owner.fingerprint to member(owner, ownerRecipient), peer.fingerprint to member(peer, recipient)), emptySet(), "unsigned"))
        val trust = SyncGroupTrust(key.group, genesis.reference(), genesis.members.getValue(owner.fingerprint).certificate)
        fun ledger() = SyncDeviceLedger(trust).also { assertEquals(SyncVersionState.ADMITTED, it.offer(genesis)) }
        fun rotate(parent: SyncDeviceLedgerRecord = genesis, revoke: Boolean = false): Pair<SyncDeviceLedgerRecord, SyncStreamingCipher> {
            val nextKey = SyncStreamingCipher.create(key.group, SyncStoreId.random())
            val members = parent.members.mapValues { (id, member) -> if (id == peer.fingerprint && revoke) member.copy(active = false) else member }
            return sign(SyncDeviceLedgerRecord(key.group, SyncStoreId.random(), parent.reference(), parent.sequence + 1,
                nextKey.epoch, nextKey.keyCommitment(), parent.coveredTypes, members, parent.acceptedHeads, "unsigned")) to nextKey
        }
        fun delivery(ledger: SyncDeviceLedger, cipher: SyncStreamingCipher = key) = SyncGroupKeyDelivery.create(
            ledger, cipher, peer.fingerprint, SyncStoreId.random(), owner::signSyncGroupPayload)
        fun objectKey(delivery: SyncGroupKeyDelivery) = CiphertextObjectKey(CiphertextObjectKind.COMMIT, delivery.context.objectId)
        fun tentative(delivery: SyncGroupKeyDelivery) = delivery.openTentative(trust, recipient, objectKey(delivery))
    }

    @Test fun rootAuthenticatedDeliveryBootstrapsANewEpochOnlyAfterItsLedgerAndCanRetainHistoricalKeys() {
        val group = Group(); val sender = group.ledger(); val receiver = group.ledger()
        val (rotated, key) = group.rotate(); assertEquals(SyncVersionState.ADMITTED, sender.offer(rotated))
        val delivery = group.delivery(sender, key); val tentative = group.tentative(delivery)
        assertEquals(key.keyCommitment(), tentative.cipher.keyCommitment())
        assertEquals("SYNC_KEY_DELIVERY_LEDGER_MISSING", assertFailsWith<IllegalArgumentException> { tentative.admit(receiver) }.message)
        assertEquals(SyncVersionState.ADMITTED, receiver.offer(rotated))
        val admitted = tentative.admit(receiver)
        assertEquals(key.keyCommitment(), admitted.cipher.keyCommitment())
        assertEquals(group.peer.fingerprint, admitted.recipient)
        val historical = group.delivery(sender)
        assertEquals(group.key.keyCommitment(), group.tentative(historical).admit(receiver).cipher.keyCommitment())
    }

    @Test fun knownRevocationRefusesBothNewDeliveryAndPreviouslyValidTentativeKeysWithoutDiscardingThem() {
        val group = Group(); val state = group.ledger(); val delivery = group.delivery(state)
        val tentative = group.tentative(delivery)
        val (revocation, replacement) = group.rotate(revoke = true)
        assertEquals(SyncVersionState.ADMITTED, state.offer(revocation))
        assertEquals("SYNC_KEY_DELIVERY_RECIPIENT_REVOKED", assertFailsWith<IllegalArgumentException> { group.delivery(state, replacement) }.message)
        assertEquals("SYNC_KEY_DELIVERY_RECIPIENT_REVOKED", assertFailsWith<IllegalArgumentException> { group.delivery(state) }.message)
        assertEquals("SYNC_KEY_DELIVERY_RECIPIENT_REVOKED", assertFailsWith<IllegalArgumentException> { tentative.admit(state) }.message)
        assertEquals(group.key.keyCommitment(), tentative.cipher.keyCommitment())
    }

    @Test fun wrongRecipientObjectRootSignatureAndClaimedCommitmentRefuse() {
        val group = Group(); val state = group.ledger(); val delivery = group.delivery(state)
        assertFails { delivery.openTentative(group.trust, group.ownerRecipient, group.objectKey(delivery)) }
        assertFails { delivery.openTentative(group.trust, group.recipient, CiphertextObjectKey(CiphertextObjectKind.BLOB, delivery.context.objectId)) }
        assertFails { delivery.openTentative(group.trust, group.recipient, CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random())) }
        val wrongSignature = delivery.copy(signature = Base64.getEncoder().encodeToString(group.peer.signSyncGroupPayload(delivery.signingBytes())))
        assertEquals("SYNC_KEY_DELIVERY_SIGNATURE", assertFailsWith<IllegalArgumentException> { group.tentative(wrongSignature) }.message)
        assertFails { group.delivery(state, SyncStreamingCipher.create(group.key.group, group.key.epoch)) }
        assertFails { SyncGroupKeyDelivery.create(state, group.key, group.peer.fingerprint, SyncStoreId.random(), group.peer::signSyncGroupPayload) }
        val changed = delivery.copy(context = delivery.context.copy(keyCommitment = "a".repeat(64)))
        assertEquals("SYNC_KEY_DELIVERY_SIGNATURE", assertFailsWith<IllegalArgumentException> { group.tentative(changed) }.message)
    }

    @Test fun admittedKeyRequiresTheSameTrustAnchorAndAuthorizingAncestry() {
        val group = Group(); val state = group.ledger(); val delivery = group.delivery(state); val tentative = group.tentative(delivery)
        val otherTrust = group.trust.copy(genesis = SyncVersionRef(SyncStoreId.random(), "b".repeat(64)))
        val other = SyncDeviceLedger(otherTrust)
        assertFails { tentative.admit(other) }
        val (rotated, key) = group.rotate(); state.offer(rotated)
        assertTrue(state.hasKey(key.epoch, key.keyCommitment()))
        assertFalse(state.hasKey(key.epoch, key.keyCommitment(), group.genesis.reference()))
        assertEquals(group.key.keyCommitment(), tentative.admit(state).cipher.keyCommitment())
    }

    @Test fun durableCapsuleSurvivesLostLocalAndRemoteRepliesAndReopensWithoutSigningAgain() {
        val group = Group(); val state = group.ledger()
        val root = Files.createTempDirectory("synthetic-key-queue").toRealPath()
        val backend = FileCiphertextStore(Files.createTempDirectory("synthetic-key-backend").toRealPath())
        val operation = SyncStoreId.random()
        val interrupted = SyncKeyDeliveryQueue(root, group.trust, group.owner.fingerprint, { state }) { stage ->
            if (stage == "KEY_CAPSULE_RETAINED") throw IOException("SYNTHETIC_LOST_LOCAL_REPLY")
        }
        assertFailsWith<IOException> { interrupted.prepare(operation, group.key, group.peer.fingerprint, group.owner::signSyncGroupPayload) }
        fun reopen() = SyncKeyDeliveryQueue(root, group.trust, group.owner.fingerprint, { state })
        val retained = requireNotNull(reopen().entry(operation)); assertFalse(retained.uploaded)
        assertEquals(retained, reopen().prepare(operation, group.key, group.peer.fingerprint) { error("SYNTHETIC_SIGNER_MUST_NOT_RUN") })
        val lostReply = object : CiphertextStore by backend {
            override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult {
                backend.put(request, source); throw IOException("SYNTHETIC_LOST_REMOTE_REPLY")
            }
        }
        assertFailsWith<IOException> { reopen().upload(operation, lostReply) }
        assertEquals(retained, reopen().entry(operation))
        val metadata = requireNotNull(backend.metadata(retained.request.key))
        assertEquals(group.key.keyCommitment(), SyncKeyDeliveryCodec.read(backend, metadata, group.trust, group.recipient).admit(state).cipher.keyCommitment())
        assertEquals(CiphertextPutDisposition.ALREADY_PRESENT, reopen().upload(operation, backend).disposition)
        assertEquals(retained.copy(uploaded = true), reopen().entry(operation))
        Files.writeString(root.resolve("key-${operation.value}.uploaded"), "a".repeat(63))
        assertEquals("SYNC_KEY_QUEUE_RECEIPT", assertFailsWith<IllegalArgumentException> { reopen().upload(operation, backend) }.message)
    }

    @Test fun rotationAndRevocationRefuseOldQueuedUploadsAndLeaveTheirEvidenceInspectable() {
        val group = Group(); val state = group.ledger()
        val root = Files.createTempDirectory("synthetic-revoked-key-queue").toRealPath()
        val backend = FileCiphertextStore(Files.createTempDirectory("synthetic-revoked-key-backend").toRealPath())
        val queue = SyncKeyDeliveryQueue(root, group.trust, group.owner.fingerprint, { state })
        val operation = SyncStoreId.random(); val entry = queue.prepare(operation, group.key, group.peer.fingerprint, group.owner::signSyncGroupPayload)
        val (rotated, key) = group.rotate(); state.offer(rotated)
        assertEquals("SYNC_KEY_QUEUE_AUTHORITY_CHANGED", assertFailsWith<IllegalArgumentException> { queue.upload(operation, backend) }.message)
        assertEquals(entry, queue.entry(operation)); assertTrue(backend.listCommits().entries.isEmpty())
        assertFails { queue.prepare(operation, key, group.peer.fingerprint, group.owner::signSyncGroupPayload) }
        val nextOperation = SyncStoreId.random(); queue.prepare(nextOperation, key, group.peer.fingerprint, group.owner::signSyncGroupPayload)
        val (revocation, _) = group.rotate(rotated, revoke = true); state.offer(revocation)
        assertFails { queue.upload(nextOperation, backend) }; assertNotNull(queue.entry(nextOperation))
        assertTrue(backend.listCommits().entries.isEmpty())
        val peerQueue = SyncKeyDeliveryQueue(root, group.trust, group.peer.fingerprint, { state })
        assertNotNull(peerQueue.entry(operation))
        assertEquals("SYNC_KEY_QUEUE_LOCAL_REVOKED", assertFailsWith<IllegalArgumentException> { peerQueue.upload(operation, backend) }.message)
    }

    @Test fun malformedOversizedAndSubstitutedCapsulesRefuseBeforeAnyKeyIsAdmitted() {
        val group = Group(); val delivery = group.delivery(group.ledger())
        val bytes = SyncKeyDeliveryCodec.encode(delivery)
        assertEquals(delivery, SyncKeyDeliveryCodec.decode(bytes))
        assertFails { SyncKeyDeliveryCodec.decode(bytes.copyOfRange(1, bytes.size)) }
        assertFails { SyncKeyDeliveryCodec.decode(ByteArray(SyncKeyDeliveryCodec.MAX_BYTES + 1)) }
        val prefix = "CCB-SYNC-KEY-CAPSULE/1\n".toByteArray()
        assertFails { SyncKeyDeliveryCodec.decode(prefix + byteArrayOf(0xc3.toByte(), 0x28)) }
        assertFails { SyncKeyDeliveryCodec.decode(prefix + ("[".repeat(9) + "]".repeat(9)).toByteArray()) }
        val backend = FileCiphertextStore(Files.createTempDirectory("synthetic-capsule-budget").toRealPath())
        val key = group.objectKey(delivery)
        val metadata = CiphertextObjectMetadata(key, SyncKeyDeliveryCodec.descriptor(bytes))
        val substituted = object : CiphertextStore by backend {
            override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata {
                destination.write(ByteArray(SyncKeyDeliveryCodec.MAX_BYTES + 1)); return metadata
            }
        }
        assertEquals("SYNC_KEY_DELIVERY_BUDGET", assertFailsWith<IllegalArgumentException> {
            SyncKeyDeliveryCodec.read(substituted, metadata, group.trust, group.recipient)
        }.message)
        val root = Files.createTempDirectory("synthetic-capsule-local-damage").toRealPath()
        val state = group.ledger(); lateinit var queue: SyncKeyDeliveryQueue
        queue = SyncKeyDeliveryQueue(root, group.trust, group.owner.fingerprint, { state }) {
            if (it == "KEY_CAPSULE_RETAINED") assertFailsWith<IllegalStateException> { queue.entry(key.id) }
        }
        queue.prepare(key.id, group.key, group.peer.fingerprint, group.owner::signSyncGroupPayload)
        val path = root.resolve("key-${key.id.value}.capsule")
        val changed = SyncKeyDeliveryCodec.decode(Files.readAllBytes(path)).copy(signature = "invalid")
        Files.write(path, SyncKeyDeliveryCodec.encode(changed))
        assertEquals("SYNC_KEY_DELIVERY_SIGNATURE", assertFailsWith<IllegalArgumentException> { queue.entry(key.id) }.message)
    }
}
