package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Actual root delivery owner, retained caps/notices and offline receiver; no app invitation acceptance. */
class LanSyncGroupRootPublicationTest {
    private class Fixture(root: Path) {
        val f = LanSyncGroupPublicationQueueTest.Fixture(root)
        val root = Files.createDirectory(root.resolve("root-owner")); val queues = Files.createDirectory(root.resolve("epoch-queues"))
        val keys = linkedMapOf(f.cipher.epoch to f.cipher)
        fun outbox(epoch: SyncStoreId): SyncEncryptedOutbox {
            val path = queues.resolve(epoch.value); if (!Files.exists(path)) Files.createDirectory(path)
            return SyncEncryptedOutbox(path, keys.getValue(epoch))
        }
        fun owner(observe: (String) -> Unit = {}) = LanSyncGroupRootPublication(root, f.owner, { f.ledger }, { keys.getValue(it) }, ::outbox, observe)
        fun scan(inbox: SyncGroupInbox): SyncInboxReconciliation {
            var cursor: CommitCursor? = null
            do { cursor = inbox.discover(cursor, 1).nextCursor } while (cursor != null)
            return inbox.reconcile()
        }
    }
    private fun withFixture(work: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("synthetic-root-publication-").toRealPath()
        try { work(Fixture(root)) } finally { check(root.toFile().deleteRecursively()) }
    }
    @Test fun aLostPreparationOrUploadReplyReusesExactObjectsAndEveryTargetHasItsOwnCompletion() = withFixture { f ->
        val local = SyncDeliveryTarget(SyncDeliveryKind.LOCAL_REPLICA, SyncStoreId.random())
        assertNull(f.owner().receipt(local))
        assertFailsWith<IOException> { f.owner { if (it == "ROOT_DELIVERY_SPEC_RETAINED") throw IOException("SYNTHETIC_PREPARATION_REPLY_LOST") }.prepare() }
        val plan = f.owner().prepare(); assertEquals(plan, f.owner().prepare())
        var lost = false
        val store = object : CiphertextStore by f.f.backend {
            override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult {
                val result = f.f.backend.put(request, source)
                if (!lost) { lost = true; throw IOException("SYNTHETIC_ROOT_REPLY_LOST") }; return result
            }
        }
        assertFailsWith<IOException> { f.owner().upload(store, local) }; assertNull(f.owner().receipt(local))
        val receipt = f.owner().upload(f.f.backend, local); assertEquals(2L, receipt.recipientCount)
        assertEquals(receipt, f.owner().receipt(local)); assertEquals(plan, f.owner().prepare())
        val peer = SyncDeliveryTarget(SyncDeliveryKind.NEARBY_DEVICE, SyncStoreId.random())
        val peerStore = FileCiphertextStore(f.root.resolve("peer-backend")); assertNull(f.owner().receipt(peer))
        f.owner().upload(peerStore, peer)
        assertTrue(plan.keyDeliveries.values.all { peerStore.metadata(it.key)?.descriptor == it.descriptor })
        assertEquals(receipt, f.owner().upload(f.f.backend, local))
    }
    @Test fun currentRootStatePublishesHistoricalKeysAndTheRemovedRecipientOwnNoticeBeforeMetadata() = withFixture { f ->
        val target = SyncDeliveryTarget(SyncDeliveryKind.LOCAL_REPLICA, SyncStoreId.random())
        f.owner().upload(f.f.backend, target)
        val recipient = f.f.recipients.getValue(f.f.sender.fingerprint)
        val custody = SyncGroupCustody(Files.createDirectory(f.root.resolve("receiver-custody")), f.f.cipher.group, recipient, { it.reversedArray() }, { it.reversedArray() })
        custody.enroll(f.f.ledger.trustAnchor, listOf(f.f.genesis))
        val inbox = SyncGroupInbox(Files.createDirectory(f.root.resolve("receiver-inbox")), f.f.backend, custody, recipient)
        assertFalse(f.scan(inbox).trustBlocked)
        assertEquals(f.f.cipher.keyCommitment(), custody.key(f.f.cipher.epoch).keyCommitment())
        val next = SyncStreamingCipher.create(f.f.cipher.group); f.keys[next.epoch] = next
        val revoked = f.f.sign(f.f.genesis.copy(id = SyncStoreId.random(), parent = f.f.genesis.reference(), sequence = 2,
            epoch = next.epoch, keyCommitment = next.keyCommitment(), members = f.f.members +
                (f.f.sender.fingerprint to f.f.members.getValue(f.f.sender.fingerprint).copy(active = false))))
        f.f.ledger.offer(revoked)
        assertNull(f.owner().receipt(target))
        val plan = f.owner().prepare(); val receipt = f.owner().upload(f.f.backend, target)
        assertEquals(2L, receipt.keyDeliveryCount); assertEquals(1L, receipt.recipientCount); assertEquals(1L, receipt.revocationNoticeCount)
        assertEquals(setOf(f.f.sender.fingerprint), plan.revocationNotices.keys)
        val received = f.scan(inbox)
        assertEquals(revoked.reference(), received.ledgerCheckpoint)
        assertEquals(SyncInboxStage.DEVICE_REVOKED, received.observations.single { it.key == plan.revocationNotices.getValue(f.f.sender.fingerprint).key }.stage)
        assertEquals("SYNC_GROUP_CUSTODY_KEY_MISSING", assertFailsWith<IllegalArgumentException> { custody.key(next.epoch) }.message)
        assertEquals(f.f.cipher.keyCommitment(), custody.key(f.f.cipher.epoch).keyCommitment())
    }
}
