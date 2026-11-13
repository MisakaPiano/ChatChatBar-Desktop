package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.LanSyncTlsIdentity
import java.util.Base64
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
}
