package com.example.chatbar.domain.syncStore

import java.nio.file.Files
import kotlin.test.*

/** Real HPKE/AEAD/signed-prefix evidence, explicit enrollment only and synthetic local protection. */
class SyncGroupBootstrapTest {
    private fun withGroup(work: (SyncGroupInboxTest.Group, SyncGroupCustody, SyncGroupInbox) -> Unit) {
        val root = Files.createTempDirectory("synthetic-explicit-group-bootstrap-").toRealPath()
        try {
            val g = SyncGroupInboxTest.Group(root)
            val custody = SyncGroupCustody(g.dir("joining-custody"), g.trust.group, g.recipient, { it.reversedArray() }, { it.reversedArray() })
            val inbox = SyncGroupInbox(g.dir("joining-inbox"), g.backend, custody, g.recipient)
            work(g, custody, inbox)
        } finally { check(root.toFile().deleteRecursively()) }
    }
    private fun scan(inbox: SyncGroupInbox) {
        var cursor: CommitCursor? = null
        do { cursor = inbox.discover(cursor, 1).nextCursor } while (cursor != null)
    }

    @Test fun externalTrustAndCompleteRecipientEvidenceStillWriteNoCustodyUntilExplicitAtomicEnrollment() = withGroup { g, custody, inbox ->
        assertEquals(SyncGroupBootstrapStage.WAITING_FOR_LEDGER, inbox.prepareEnrollment(g.trust).stage)
        g.publishLedger(); scan(inbox)
        val prepared = inbox.prepareEnrollment(g.trust); assertEquals(SyncGroupBootstrapStage.READY, prepared.stage)
        assertFalse(Files.exists(g.root.resolve("joining-custody/group.protected")))
        assertFails { custody.ledger() }
        assertTrue(prepared.observations.any { it.stage == SyncInboxStage.ENROLLMENT_KEY_VERIFIED })
        val evidence = requireNotNull(prepared.evidence)
        custody.enrollWithKeys(evidence.trust, evidence.records, evidence.keys)
        assertEquals(g.genesis.reference(), custody.ledger().current().reference())
        assertEquals(g.initialKey.keyCommitment(), custody.key(g.initialKey.epoch).keyCommitment())
        assertFalse(inbox.reconcile().trustBlocked)
    }

    @Test fun aMissingHistoricalRecipientKeyHoldsEnrollmentUntilAFullRescanFindsIt() = withGroup { g, custody, _ ->
        val next = SyncStreamingCipher.create(g.trust.group); g.keys[next.epoch] = next
        g.ledger.offer(g.sign(g.genesis.copy(id = SyncStoreId.random(), parent = g.genesis.reference(), sequence = 2,
            epoch = next.epoch, keyCommitment = next.keyCommitment())))
        g.publishLedger()
        // Find the one old-epoch capsule addressed to this joining recipient; keep all other objects visible.
        val missing = g.backend.listCommits(null, 1000).entries.first { metadata ->
            val bytes = java.io.ByteArrayOutputStream().also { g.backend.readTo(metadata.key, it, metadata.descriptor) }.toByteArray()
            if (!SyncKeyDeliveryCodec.hasPrefix(bytes)) false else SyncKeyDeliveryCodec.decode(bytes).context.let {
                it.recipient == g.recipient.device && it.epoch == g.initialKey.epoch
            }
        }.key
        val filtered = object : CiphertextStore by g.backend {
            override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage {
                val page = g.backend.listCommits(cursor, pageSize); return CommitPage(page.entries.filter { it.key != missing }, page.nextCursor)
            }
        }
        val inboxRoot = g.dir("missing-history-inbox")
        val partial = SyncGroupInbox(inboxRoot, filtered, custody, g.recipient); scan(partial)
        val pending = partial.prepareEnrollment(g.trust)
        assertEquals(SyncGroupBootstrapStage.WAITING_FOR_KEYS, pending.stage); assertNull(pending.evidence)
        assertFalse(Files.exists(g.root.resolve("joining-custody/group.protected")))
        val complete = SyncGroupInbox(inboxRoot, g.backend, custody, g.recipient); scan(complete)
        val evidence = requireNotNull(complete.prepareEnrollment(g.trust).evidence)
        custody.enrollWithKeys(evidence.trust, evidence.records, evidence.keys)
        assertEquals(g.initialKey.keyCommitment(), custody.key(g.initialKey.epoch).keyCommitment())
        assertEquals(next.keyCommitment(), custody.key(next.epoch).keyCommitment())
    }

    @Test fun twoIndividuallyValidRootBranchesBlockBootstrapInsteadOfSelectingTheFirstEncryptedEnvelope() = withGroup { g, _, inbox ->
        g.publishLedger()
        repeat(2) {
            val fork = g.sign(g.genesis.copy(id = SyncStoreId.random(), parent = g.genesis.reference(), sequence = 2))
            val queue = g.outbox(g.initialKey)
            val prepared = g.codec.enqueue(queue, SyncStoreId.random(), CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()), g.initialKey,
                SyncGroupEnvelope(2, SyncGroupObjectKind.DEVICE_LEDGER, deviceLedger = fork, ledgerHistory = listOf(g.genesis)))
            queue.upload(prepared.request.operationId, g.backend)
        }
        scan(inbox); val result = inbox.prepareEnrollment(g.trust)
        assertEquals(SyncGroupBootstrapStage.TRUST_BLOCKED, result.stage); assertNull(result.evidence)
        assertFalse(Files.exists(g.root.resolve("joining-custody/group.protected")))
    }

    @Test fun missingLaterJoinPinAfterRecoveredMembershipWaitsButAnActualLateBranchBlocks() = withGroup { g, custody, inbox ->
        val left = g.sign(g.genesis.copy(id = SyncStoreId.random(), parent = g.genesis.reference(), sequence = 2))
        val right = g.sign(left.copy(id = SyncStoreId.random()))
        val fresh = SyncStreamingCipher.create(g.trust.group)
        g.keys[fresh.epoch] = fresh
        val recovered = g.sign(left.copy(id = SyncStoreId.random(), parent = null, sequence = 3, epoch = fresh.epoch,
            keyCommitment = fresh.keyCommitment(), recovery = SyncGroupLedgerRecovery(setOf(left.reference(), right.reference()), left.reference())))
        listOf(left, right, recovered).forEach { assertEquals(SyncVersionState.ADMITTED, g.ledger.offer(it)) }
        g.publishLedger(); scan(inbox)

        val later = g.sign(recovered.copy(id = SyncStoreId.random(), parent = recovered.reference(), sequence = 4, recovery = null))
        val pinned = g.trust.copy(checkpoint = later.reference())
        val checkpoints = setOf(recovered.reference(), later.reference())
        val cold = SyncGroupInbox(g.root.resolve("joining-inbox"), g.backend, custody, g.recipient)
        listOf(inbox, cold).forEach { owner ->
            val waiting = owner.prepareEnrollment(pinned, checkpoints)
            assertEquals(SyncGroupBootstrapStage.WAITING_FOR_LEDGER, waiting.stage)
            assertEquals("SYNC_GROUP_KNOWN_CHECKPOINT_MISSING", waiting.code)
            assertNull(waiting.evidence)
        }
        assertFalse(Files.exists(g.root.resolve("joining-custody/group.protected")))

        assertEquals(SyncVersionState.ADMITTED, g.ledger.offer(later))
        g.publishLedger(); scan(cold)
        val ready = cold.prepareEnrollment(pinned, checkpoints)
        assertEquals(SyncGroupBootstrapStage.READY, ready.stage, ready.code)
        val proof = requireNotNull(ready.evidence)
        val verified = SyncDeviceLedger(proof.trust).also { ledger -> proof.records.forEach { ledger.offer(it) } }
        assertEquals(later.reference(), verified.current().reference())
        assertTrue(proof.records.any { it.reference() == recovered.reference() })

        val late = g.sign(left.copy(id = SyncStoreId.random(), parent = left.reference(), sequence = 3))
        val queue = g.outbox(g.initialKey)
        val candidate = g.codec.enqueue(queue, SyncStoreId.random(), CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()),
            g.initialKey, SyncGroupEnvelope(2, SyncGroupObjectKind.DEVICE_LEDGER, deviceLedger = late, ledgerHistory = listOf(g.genesis, left)))
        queue.upload(candidate.request.operationId, g.backend)
        scan(cold)
        val fork = cold.prepareEnrollment(pinned, checkpoints)
        assertEquals(SyncGroupBootstrapStage.TRUST_BLOCKED, fork.stage)
        assertEquals("SYNC_GROUP_BOOTSTRAP_LEDGER_CONFLICT", fork.code)
        assertNull(fork.evidence)
        assertFalse(Files.exists(g.root.resolve("joining-custody/group.protected")))
    }

    @Test fun aVerifiedRecipientRevocationNoticeOverridesPreviouslyAvailableOldKeyCapsules() = withGroup { g, _, inbox ->
        g.publishLedger()
        val key = SyncStreamingCipher.create(g.trust.group); g.keys[key.epoch] = key
        val revoked = g.sign(g.genesis.copy(id = SyncStoreId.random(), parent = g.genesis.reference(), sequence = 2,
            epoch = key.epoch, keyCommitment = key.keyCommitment(),
            members = g.members + (g.peer.fingerprint to g.members.getValue(g.peer.fingerprint).copy(active = false))))
        g.ledger.offer(revoked); g.publishLedger(); scan(inbox)
        val result = inbox.prepareEnrollment(g.trust)
        assertEquals(SyncGroupBootstrapStage.DEVICE_REVOKED, result.stage); assertNull(result.evidence)
        assertFalse(Files.exists(g.root.resolve("joining-custody/group.protected")))
    }
}
