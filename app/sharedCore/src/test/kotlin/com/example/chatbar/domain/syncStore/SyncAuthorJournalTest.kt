package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.LanSyncTlsIdentity
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Actual signatures and protected-journal faults; approval is synthetic, not native UI acceptance. */
class SyncAuthorJournalTest {
    private class Group(val root: Path) {
        val owner = LanSyncTlsIdentity.create(); val author = LanSyncTlsIdentity.create(); val key = SyncStreamingCipher.create()
        val members = listOf(owner, author).associate { it.fingerprint to SyncGroupMember(Base64.getEncoder().encodeToString(it.certificate.encoded),
            SyncDeviceEncryptionKey.create(it.fingerprint).publicKey.encoded) }
        fun signLedger(row: SyncDeviceLedgerRecord) = row.copy(signature = Base64.getEncoder().encodeToString(owner.signSyncGroupPayload(row.signingBytes())))
        val genesis = signLedger(SyncDeviceLedgerRecord(key.group, SyncStoreId.random(), null, 1, key.epoch, key.keyCommitment(),
            setOf("synthetic_records"), members, emptySet(), "unsigned"))
        val ledger = SyncDeviceLedger(SyncGroupTrust(key.group, genesis.reference(), members.getValue(owner.fingerprint).certificate)).also { it.offer(genesis) }
        val journalRoot = Files.createDirectory(root.resolve("author"))
        val signerCalls = AtomicInteger()
        fun signer(bytes: ByteArray): ByteArray { signerCalls.incrementAndGet(); return author.signSyncGroupPayload(bytes) }
        fun journal(observe: (String) -> Unit = {}) = SyncAuthorJournal(journalRoot, ledger.trustAnchor, members.getValue(author.fingerprint).certificate,
            { it.reversedArray() }, { it.reversedArray() }, observe)
        fun request(unit: String = "synthetic/one") = SyncAuthorCommitRequest(SyncStoreId.random(), genesis.reference(), emptySet(),
            mapOf(unit to SyncUnitChange("a".repeat(64), SyncPlaintextIdentity(1, "b".repeat(64)), emptySet())),
            UUID.randomUUID().toString(), "c".repeat(64), "d".repeat(64))
        val approved = SyncAuthorCommitAdmission { require(it.approvalBinding == "d".repeat(64)) { "SYNC_SYNTHETIC_NOT_APPROVED" } }
        fun revoke() {
            val replacement = SyncStreamingCipher.create(key.group, SyncStoreId.random())
            ledger.offer(signLedger(genesis.copy(id = SyncStoreId.random(), sequence = 2, parent = genesis.reference(),
                epoch = replacement.epoch, keyCommitment = replacement.keyCommitment(), members = members +
                    (author.fingerprint to members.getValue(author.fingerprint).copy(active = false)))))
        }
    }
    private fun withGroup(work: (Group) -> Unit) {
        val root = Files.createTempDirectory("synthetic-author-journal-").toRealPath()
        try { work(Group(root)) } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun unapprovedWorkDoesNotAllocateOrSignAndApprovedVersionsMaintainExactAuthorContinuity() = withGroup { g ->
        g.journal().initializeNewEnrollment(); val request = g.request()
        assertEquals("SYNC_SYNTHETIC_REVIEW_REQUIRED", assertFailsWith<IllegalArgumentException> {
            g.journal().commitApproved(request, g.ledger, SyncAuthorCommitAdmission { throw IllegalArgumentException("SYNC_SYNTHETIC_REVIEW_REQUIRED") }, g::signer)
        }.message)
        assertEquals(0L, g.journal().state().committedSequence); assertNull(g.journal().state().pendingApproved); assertEquals(0, g.signerCalls.get())
        val first = g.journal().commitApproved(request, g.ledger, g.approved, g::signer)
        val second = g.journal().commitApproved(g.request("synthetic/two"), g.ledger, g.approved, g::signer)
        assertEquals(1L, first.version.authorSequence); assertEquals(2L, second.version.authorSequence)
        assertEquals(first.version.reference(), second.version.previousByAuthor); assertEquals(setOf(first.version.reference()), second.version.parents)
        assertEquals(first, g.journal().commitApproved(request, g.ledger, SyncAuthorCommitAdmission { error("SYNTHETIC_MUST_NOT_REAPPROVE") }) { error("SYNTHETIC_MUST_NOT_RESIGN") })
        assertEquals(2, g.signerCalls.get()); assertEquals(2L, g.journal().state().committedSequence)
        assertEquals("SYNC_AUTHOR_OPERATION_CHANGED", assertFailsWith<IllegalArgumentException> {
            g.journal().commitApproved(request.copy(approvalBinding = "e".repeat(64)), g.ledger, g.approved, g::signer)
        }.message)
        assertTrue(SyncDeviceSignatures.decode(g.members.getValue(g.author.fingerprint).certificate).verifies(second.version.signingBytes(), second.version.signature))
    }

    @Test fun lostIntentAndSignedRepliesResumeTheSameBodyAndRolledBackHeadsCannotReuseACounter() = withGroup { g ->
        g.journal().initializeNewEnrollment(); val request = g.request()
        val interrupted = g.journal { if (it == "APPROVED_AUTHOR_INTENT_RETAINED") throw IOException("SYNTHETIC_LOST_INTENT_REPLY") }
        assertFailsWith<IOException> { interrupted.commitApproved(request, g.ledger, g.approved, g::signer) }
        val pending = requireNotNull(g.journal().state().pendingApproved)
        assertEquals(0L, g.journal().state().committedSequence); assertEquals(0, g.signerCalls.get())
        assertEquals("SYNC_AUTHOR_PENDING_APPROVED_COMMIT", assertFailsWith<IllegalArgumentException> {
            g.journal().commitApproved(g.request("synthetic/two"), g.ledger, g.approved, g::signer)
        }.message)
        val lostSigned = g.journal { if (it == "SIGNED_AUTHOR_ENTRY_RETAINED") throw IOException("SYNTHETIC_LOST_SIGNED_REPLY") }
        assertFailsWith<IOException> { lostSigned.commitApproved(request, g.ledger, SyncAuthorCommitAdmission { error("SYNTHETIC_ALREADY_APPROVED") }, g::signer) }
        val recovered = g.journal().state(); assertEquals(1L, recovered.committedSequence)
        assertEquals(pending.version.reference(), recovered.last?.version?.reference()); assertEquals(1, g.signerCalls.get())
        val originalHead = Files.readAllBytes(g.journalRoot.resolve("head.protected"))
        val next = g.journal().commitApproved(g.request("synthetic/two"), g.ledger, g.approved, g::signer)
        Files.write(g.journalRoot.resolve("head.protected"), originalHead)
        assertEquals(next, g.journal().state().last); assertEquals(2L, g.journal().state().committedSequence); assertEquals(2, g.signerCalls.get())
    }

    @Test fun wrongSignerAndKnownRevocationKeepApprovedEvidenceWithoutCreatingAFreshAuthorVersion() = withGroup { g ->
        g.journal().initializeNewEnrollment(); val request = g.request()
        assertEquals("SYNC_AUTHOR_SIGNATURE_OR_BODY_CHANGED", assertFailsWith<IllegalArgumentException> {
            g.journal().commitApproved(request, g.ledger, g.approved, g.owner::signSyncGroupPayload)
        }.message)
        val before = requireNotNull(g.journal().state().pendingApproved)
        assertEquals(0L, g.journal().state().committedSequence)
        g.revoke()
        assertEquals("SYNC_AUTHOR_REVOKED_OR_CHANGED", assertFailsWith<IllegalArgumentException> {
            g.journal().commitApproved(request, g.ledger, g.approved, g::signer)
        }.message)
        assertEquals(before, g.journal().state().pendingApproved); assertEquals(0, g.signerCalls.get())
        assertNull(g.journal().entry(request.operation))
    }

    @Test fun lostOriginOrHistoricalSignedEvidenceRefusesResetRatherThanGuessingTheHighestBackendCounter() = withGroup { g ->
        assertEquals("SYNC_AUTHOR_ORIGIN_MISSING", assertFailsWith<IllegalArgumentException> { g.journal().state() }.message)
        g.journal().initializeNewEnrollment()
        g.journal().commitApproved(g.request(), g.ledger, g.approved, g::signer)
        g.journal().commitApproved(g.request("synthetic/two"), g.ledger, g.approved, g::signer)
        Files.delete(g.journalRoot.resolve("signed-0000000000000000001.protected"))
        assertEquals("SYNC_AUTHOR_SIGNED_ENTRY_MISSING", assertFailsWith<IllegalArgumentException> { g.journal().state() }.message)
        Files.delete(g.journalRoot.resolve("origin.protected"))
        assertEquals("SYNC_AUTHOR_ORIGIN_MISSING", assertFailsWith<IllegalArgumentException> { g.journal().state() }.message)
        assertEquals("SYNC_AUTHOR_EXISTING_EVIDENCE", assertFailsWith<IllegalArgumentException> { g.journal().initializeNewEnrollment() }.message)
    }

    @Test fun cooperatingOwnersSignOnlyOnceForOneApprovedOperationAndCallbackReentryRefuses() = withGroup { g ->
        g.journal().initializeNewEnrollment(); val request = g.request(); val pool = Executors.newFixedThreadPool(2)
        try {
            val results = List(2) { pool.submit<SyncAuthorJournalEntry> { g.journal().commitApproved(request, g.ledger, g.approved, g::signer) } }
                .map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(results[0], results[1]); assertEquals(1, g.signerCalls.get()); assertEquals(1L, g.journal().state().committedSequence)
            val journal = g.journal()
            assertEquals("SYNC_AUTHOR_REENTRY", assertFailsWith<IllegalStateException> {
                journal.commitApproved(g.request("synthetic/two"), g.ledger, SyncAuthorCommitAdmission { journal.state() }, g::signer)
            }.message)
            assertEquals(1L, journal.state().committedSequence)
        } finally { pool.shutdownNow(); check(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun aDifferentEnrollmentOriginCannotBeGraftedOntoAnExistingAuthorHistory() = withGroup { g ->
        g.journal().initializeNewEnrollment(); g.journal().commitApproved(g.request(), g.ledger, g.approved, g::signer)
        val other = Files.createDirectory(g.root.resolve("different-enrollment"))
        SyncAuthorJournal(other, g.ledger.trustAnchor, g.members.getValue(g.author.fingerprint).certificate,
            { it.reversedArray() }, { it.reversedArray() }).initializeNewEnrollment()
        Files.copy(other.resolve("origin.protected"), g.journalRoot.resolve("origin.protected"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertEquals("SYNC_AUTHOR_PRIVATE_CONTEXT", assertFailsWith<IllegalArgumentException> { g.journal().state() }.message)
        assertEquals(1, g.signerCalls.get())
    }

    @Test fun aMissingHistoricalOperationIndexCannotTurnAnOldRequestIntoANewCommit() = withGroup { g ->
        g.journal().initializeNewEnrollment(); val request = g.request()
        g.journal().commitApproved(request, g.ledger, g.approved, g::signer)
        Files.delete(g.journalRoot.resolve("operation-${request.operation.value}.protected"))
        assertEquals("SYNC_AUTHOR_INDEX_MISSING_OR_EXTRA", assertFailsWith<IllegalArgumentException> {
            g.journal().commitApproved(request, g.ledger, g.approved, g::signer)
        }.message)
        assertEquals(1, g.signerCalls.get())
    }

    @Test fun aLedgerWithTheSameGroupLabelCannotReplaceTheEnrolledTrustAnchorForAuthoring() = withGroup { g ->
        g.journal().initializeNewEnrollment()
        val foreign = SyncDeviceLedger(g.ledger.trustAnchor.copy(genesis = SyncVersionRef(SyncStoreId.random(), "f".repeat(64))))
        assertEquals("SYNC_AUTHOR_TRUST_CHANGED", assertFailsWith<IllegalArgumentException> {
            g.journal().commitApproved(g.request(), foreign, g.approved, g::signer)
        }.message)
        assertEquals(0, g.signerCalls.get()); assertEquals(0L, g.journal().state().committedSequence); assertNull(g.journal().state().pendingApproved)
    }
}
