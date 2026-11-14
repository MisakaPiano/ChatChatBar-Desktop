package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.LanSyncGroupPublicationQueueTest
import com.example.chatbar.domain.lanSync.lanSyncJson
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Actual root signatures/streaming keys/custody with synthetic protection; peer proof/UI are not simulated as accepted. */
class SyncRootLedgerJournalTest {
    private class Fixture(val root: Path) {
        val f = LanSyncGroupPublicationQueueTest.Fixture(root)
        val journalRoot = Files.createDirectory(root.resolve("root-journal")); val custodyRoot = Files.createDirectory(root.resolve("root-custody"))
        val recipient = f.recipients.getValue(f.owner.fingerprint)
        fun custody(protect: (ByteArray) -> ByteArray = { it.reversedArray() }, observe: (String) -> Unit = {}) =
            SyncGroupCustody(custodyRoot, f.cipher.group, recipient, protect, { it.reversedArray() }, observe = observe)
        fun journal(observe: (String) -> Unit = {}) = SyncRootLedgerJournal(journalRoot, f.ledger.trustAnchor,
            f.members.getValue(f.owner.fingerprint).certificate, { it.reversedArray() }, { it.reversedArray() }, observe)
        init {
            val ledger = custody().enroll(f.ledger.trustAnchor, listOf(f.genesis))
            val capsule = SyncGroupKeyDelivery.create(ledger, f.cipher, f.owner.fingerprint, SyncStoreId.random(), f.owner::signSyncGroupPayload)
            custody().rememberKey(capsule.openTentative(f.ledger.trustAnchor, recipient,
                CiphertextObjectKey(CiphertextObjectKind.COMMIT, capsule.context.objectId)).admit(ledger))
            journal().initializeNewEnrollment(ledger)
        }
        fun request(revoke: Boolean = true, rotate: Boolean = revoke): SyncRootLedgerRequest {
            val current = custody().ledger().current()
            return SyncRootLedgerRequest(SyncStoreId.random(), current.reference(), if (revoke) current.members +
                (f.sender.fingerprint to current.members.getValue(f.sender.fingerprint).copy(active = false)) else current.members,
                current.coveredTypes, current.acceptedHeads, rotate, "a".repeat(64))
        }
        fun commit(request: SyncRootLedgerRequest) = journal().commit(request, { custody().ledger() }, SyncRootLedgerAdmission {}, f.owner::signSyncGroupPayload)
    }
    private fun withFixture(work: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("synthetic-root-ledger-journal-").toRealPath()
        try { work(Fixture(root)) } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun anApprovedRevocationRetainsItsExactNewKeyAndUpdatesCustodyWithoutGivingItToTheRemovedPeer() = withFixture { f ->
        val request = f.request(); val commit = f.commit(request)
        assertEquals(f.f.genesis.reference(), f.custody().ledger().current().reference())
        assertEquals(request, f.journal().nextRecoveryRequest(f.custody()))
        val updated = f.journal().applyToCustody(commit, f.custody())
        assertEquals(commit.entry.record.reference(), updated.current().reference()); assertFalse(updated.current().members.getValue(f.f.sender.fingerprint).active)
        assertNotEquals(f.f.cipher.epoch, updated.current().epoch)
        assertEquals(updated.current().keyCommitment, f.custody().key(updated.current().epoch).keyCommitment())
        assertEquals(f.f.cipher.keyCommitment(), f.custody().key(f.f.cipher.epoch).keyCommitment())
        assertNull(f.journal().nextRecoveryRequest(f.custody()))
        assertEquals(commit.entry, f.journal().commit(request, { f.custody().ledger() },
            SyncRootLedgerAdmission { error("SYNTHETIC_ALREADY_APPROVED") }, { error("SYNTHETIC_NO_SECOND_SIGNATURE") }).entry)
        assertEquals(1L, f.journal().state().completedSequence)
        assertEquals("SYNC_KEY_DELIVERY_RECIPIENT_REVOKED", assertFailsWith<IllegalArgumentException> {
            SyncGroupKeyDelivery.create(updated, requireNotNull(commit.newKey), f.f.sender.fingerprint, SyncStoreId.random(), f.f.owner::signSyncGroupPayload)
        }.message)
    }

    @Test fun refusalOrInvalidMembershipNeverReservesASignatureOrSilentlyAdmitsTheProposedRow() = withFixture { f ->
        var signed = 0
        assertFailsWith<IllegalStateException> { f.journal().commit(f.request(), { f.custody().ledger() },
            SyncRootLedgerAdmission { error("SYNTHETIC_NOT_APPROVED") }, { signed++; f.f.owner.signSyncGroupPayload(it) }) }
        assertEquals("SYNC_GROUP_REVOCATION_EPOCH", assertFailsWith<IllegalArgumentException> {
            f.journal().commit(f.request(rotate = false), { f.custody().ledger() }, SyncRootLedgerAdmission { error("SYNTHETIC_INVALID_MUST_NOT_REACH_ADMISSION") },
                { signed++; f.f.owner.signSyncGroupPayload(it) })
        }.message)
        assertEquals(0, signed); assertEquals(0L, f.journal().state().completedSequence); assertNull(f.journal().state().pendingOperation)
        assertEquals(f.f.genesis.reference(), f.custody().ledger().current().reference())
        assertFailsWith<IllegalArgumentException> { SyncRootLedgerJournal(f.journalRoot, f.f.ledger.trustAnchor,
            f.f.members.getValue(f.f.sender.fingerprint).certificate, { it }, { it }) }
    }

    @Test fun aLostApprovedIntentReplyResumesTheSameActionAndAnotherActionCannotSkipIt() = withFixture { f ->
        val request = f.request()
        assertFailsWith<IOException> { f.journal { if (it == "APPROVED_ROOT_INTENT_RETAINED") throw IOException("SYNTHETIC_ROOT_INTENT_REPLY_LOST") }
            .commit(request, { f.custody().ledger() }, SyncRootLedgerAdmission {}, { error("SYNTHETIC_SIGNER_NOT_REACHED") }) }
        assertEquals(request.operation, f.journal().state().pendingOperation)
        assertEquals(request, f.journal().nextRecoveryRequest(f.custody()))
        assertEquals("SYNC_ROOT_PENDING_APPROVED_ACTION", assertFailsWith<IllegalArgumentException> { f.commit(f.request()) }.message)
        var signed = 0
        val resumed = f.journal().commit(request, { f.custody().ledger() }, SyncRootLedgerAdmission { error("SYNTHETIC_NO_REAPPROVAL") },
            { signed++; f.f.owner.signSyncGroupPayload(it) })
        assertEquals(1, signed); assertEquals(resumed.entry, f.journal().entry(request.operation))
        f.journal().applyToCustody(resumed, f.custody())
        val again = f.journal().commit(request, { f.custody().ledger() }, SyncRootLedgerAdmission { error("SYNTHETIC_NO_REAPPROVAL") },
            { error("SYNTHETIC_NO_RESIGN") })
        assertEquals(resumed.entry.record, again.entry.record)
        assertEquals(resumed.newKey?.keyCommitment(), again.newKey?.keyCommitment())
    }

    @Test fun aLostSignedReplyRecoversItsHeadThenMissingHistoryAndMemberReactivationRefuse() = withFixture { f ->
        val request = f.request()
        assertFailsWith<IOException> { f.journal { if (it == "ROOT_SIGNED_ENTRY_RETAINED") throw IOException("SYNTHETIC_SIGNED_REPLY_LOST") }
            .commit(request, { f.custody().ledger() }, SyncRootLedgerAdmission {}, f.f.owner::signSyncGroupPayload) }
        assertEquals(1L, f.journal().state().completedSequence)
        val retained = f.journal().commit(request, { f.custody().ledger() }, SyncRootLedgerAdmission { error("SYNTHETIC_NO_REAPPROVAL") },
            { error("SYNTHETIC_NO_RESIGN") })
        f.journal().applyToCustody(retained, f.custody())
        val reactivation = f.request(revoke = false).let { it.copy(members = it.members +
            (f.f.sender.fingerprint to it.members.getValue(f.f.sender.fingerprint).copy(active = true))) }
        assertEquals("SYNC_GROUP_MEMBER_REACTIVATION", assertFailsWith<IllegalArgumentException> { f.commit(reactivation) }.message)
        Files.delete(f.journalRoot.resolve("intent-0000000000000000001.protected"))
        assertEquals("SYNC_ROOT_HISTORY_MISSING", assertFailsWith<IllegalArgumentException> { f.journal().state() }.message)
        assertEquals(retained.entry.record.reference(), f.custody().ledger().current().reference())
    }

    @Test fun checkpointNotificationLossReopensBothTheNewCheckpointAndItsKeyTogether() = withFixture { f ->
        val request = f.request(); val commit = f.commit(request)
        val interrupted = f.custody(observe = { if (it == "GROUP_CHECKPOINT_RETAINED") throw IOException("SYNTHETIC_CHECKPOINT_REPLY_LOST") })
        assertFailsWith<IOException> { f.journal().applyToCustody(commit, interrupted) }
        val reopened = f.custody()
        assertEquals(commit.entry.record.reference(), reopened.ledger().current().reference())
        assertEquals(commit.entry.record.keyCommitment, reopened.key(commit.entry.record.epoch).keyCommitment())
        assertEquals(f.f.cipher.keyCommitment(), reopened.key(f.f.cipher.epoch).keyCommitment())
        assertNull(f.journal().nextRecoveryRequest(reopened))
    }

    @Test fun keyOrWholeStateProtectionFailureLeavesThePreviousCheckpointAndAutomaticallyReplaysTheOwnedAction() {
        for (failAt in 1..2) withFixture { f ->
            val request = f.request(); val commit = f.commit(request); var calls = 0
            val interrupted = f.custody(protect = { bytes ->
                calls++; if (calls == failAt) throw IOException("SYNTHETIC_ATOMIC_KEY_PROTECTION_FAILED"); bytes.reversedArray()
            })
            assertFailsWith<IOException> { f.journal().applyToCustody(commit, interrupted) }
            val reopened = f.custody()
            assertEquals(f.f.genesis.reference(), reopened.ledger().current().reference())
            assertEquals(f.f.cipher.keyCommitment(), reopened.key(f.f.cipher.epoch).keyCommitment())
            assertEquals(request, f.journal().nextRecoveryRequest(reopened))
            val retained = f.journal().commit(request, reopened::ledger, SyncRootLedgerAdmission { error("SYNTHETIC_NO_REAPPROVAL") },
                { error("SYNTHETIC_NO_RESIGN") })
            f.journal().applyToCustody(retained, reopened)
            assertEquals(commit.entry.record.keyCommitment, reopened.key(commit.entry.record.epoch).keyCommitment())
            assertNull(f.journal().nextRecoveryRequest(reopened))
        }
    }

    @Test fun anOlderSplitWriteWindowAcrossSeveralRotationsRepairsAllOwnedKeysWithoutAnotherSignature() = withFixture { f ->
        val first = f.commit(f.request()); f.custody().update(first.records) // Reproduce the previous non-atomic implementation.
        val second = f.commit(f.request()); f.custody().update(second.records)
        assertEquals(second.entry.record.reference(), f.custody().ledger().current().reference())
        assertEquals("SYNC_GROUP_CUSTODY_KEY_MISSING", assertFailsWith<IllegalArgumentException> { f.custody().key(second.entry.record.epoch) }.message)
        val recovery = requireNotNull(f.journal().nextRecoveryRequest(f.custody()))
        val retained = f.journal().commit(recovery, { f.custody().ledger() }, SyncRootLedgerAdmission { error("SYNTHETIC_NO_REAPPROVAL") },
            { error("SYNTHETIC_NO_RESIGN") })
        f.journal().applyToCustody(retained, f.custody())
        assertEquals(first.entry.record.keyCommitment, f.custody().key(first.entry.record.epoch).keyCommitment())
        assertEquals(second.entry.record.keyCommitment, f.custody().key(second.entry.record.epoch).keyCommitment())
        assertEquals(f.f.cipher.keyCommitment(), f.custody().key(f.f.cipher.epoch).keyCommitment())
        assertNull(f.journal().nextRecoveryRequest(f.custody())); assertEquals(2L, f.journal().state().completedSequence)
    }
    @Test fun aSignedForkRetainsEvidenceWithoutAdvancingTheCheckpointOrAdmittingTheNewKey() = withFixture { f ->
        val owned = f.commit(f.request())
        val otherKey = SyncStreamingCipher.create(f.f.cipher.group)
        val other = f.f.sign(f.f.genesis.copy(id = SyncStoreId.random(), parent = f.f.genesis.reference(), sequence = 2,
            epoch = otherKey.epoch, keyCommitment = otherKey.keyCommitment()))
        f.custody().update(listOf(other))
        assertEquals("SYNC_GROUP_LEDGER_FORK_OR_MISSING", assertFailsWith<IllegalArgumentException> {
            f.journal().applyToCustody(owned, f.custody())
        }.message)
        val reopened = f.custody()
        assertEquals(other.reference(), reopened.trust().checkpoint)
        assertEquals("SYNC_GROUP_LEDGER_FORK_OR_MISSING", assertFailsWith<IllegalArgumentException> { reopened.ledger() }.message)
        assertEquals("SYNC_GROUP_CUSTODY_KEY_MISSING", assertFailsWith<IllegalArgumentException> { reopened.key(owned.entry.record.epoch) }.message)
        assertEquals(f.f.cipher.keyCommitment(), reopened.key(f.f.cipher.epoch).keyCommitment())
    }
    @Test fun aMissingOriginEpochKeyReportsItsEnrollmentOwnerInsteadOfPretendingRecoveryIsComplete() = withFixture { f ->
        val path = f.custodyRoot.resolve("group.protected")
        val state = lanSyncJson.parseToJsonElement(Files.readAllBytes(path).reversedArray().toString(Charsets.UTF_8)).jsonObject
        Files.write(path, JsonObject(state + ("keys" to JsonObject(emptyMap()))).toString().toByteArray(Charsets.UTF_8).reversedArray())
        assertEquals("SYNC_ROOT_ORIGIN_KEY_RECOVERY_REQUIRED", assertFailsWith<IllegalArgumentException> {
            f.journal().nextRecoveryRequest(f.custody())
        }.message)
        assertEquals(0L, f.journal().state().completedSequence)
        assertEquals("SYNC_GROUP_CUSTODY_KEY_MISSING", assertFailsWith<IllegalArgumentException> { f.custody().key(f.f.cipher.epoch) }.message)
    }
}
