package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.LanSyncTlsIdentity
import com.example.chatbar.domain.lanSync.lanSyncJson
import java.util.Base64
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyncGroupLedgerRecoveryTest {
    private class Group {
        val devices = List(6) { LanSyncTlsIdentity.create() }
        val ids = devices.map { it.fingerprint }
        val members = devices.associate { identity -> identity.fingerprint to SyncGroupMember(
            Base64.getEncoder().encodeToString(identity.certificate.encoded),
            SyncDeviceEncryptionKey.create(identity.fingerprint).publicKey.encoded) }
        val group = SyncStoreId.random()
        val firstKey = SyncStreamingCipher.create(group)
        val genesis = sign(SyncDeviceLedgerRecord(group, SyncStoreId.random(), null, 1, firstKey.epoch,
            firstKey.keyCommitment(), setOf("chat_sessions"), members.filterKeys { it in ids.take(3) }, emptySet(), "unsigned"))
        val trust = SyncGroupTrust(group, genesis.reference(), members.getValue(ids[0]).certificate)

        fun sign(row: SyncDeviceLedgerRecord): SyncDeviceLedgerRecord = row.copy(signature =
            Base64.getEncoder().encodeToString(devices.first().signSyncGroupPayload(row.signingBytes())))

        fun child(parent: SyncDeviceLedgerRecord, roster: Map<String, SyncGroupMember> = parent.members,
            key: SyncStreamingCipher? = null, fence: Set<SyncVersionRef> = parent.acceptedHeads) = sign(
            SyncDeviceLedgerRecord(group, SyncStoreId.random(), parent.reference(), parent.sequence + 1,
                key?.epoch ?: parent.epoch, key?.keyCommitment() ?: parent.keyCommitment, parent.coveredTypes, roster, fence, "unsigned"))

        fun added(index: Int) = child(genesis, genesis.members + (ids[index] to members.getValue(ids[index])))

        fun merge(parents: List<SyncDeviceLedgerRecord>, primary: SyncDeviceLedgerRecord,
            roster: Map<String, SyncGroupMember> = parents.flatMap { it.members.entries }.associate { (id, row) -> id to row },
            key: SyncStreamingCipher = SyncStreamingCipher.create(group),
            fence: Set<SyncVersionRef> = parents.flatMap { it.acceptedHeads }.toSet()) = sign(
            SyncDeviceLedgerRecord(group, SyncStoreId.random(), null, parents.maxOf { it.sequence } + 1,
                key.epoch, key.keyCommitment(), parents.flatMap { it.coveredTypes }.toSet(), roster, fence, "unsigned",
                SyncGroupLedgerRecovery(parents.map { it.reference() }.toSet(), primary.reference())))

        fun fact(actor: Int, ledger: SyncDeviceLedgerRecord, predecessor: SyncCausalVersion? = null): SyncCausalVersion {
            val row = SyncCausalVersion(group, SyncStoreId.random(), ledger.reference(), ids[actor],
                predecessor?.authorSequence?.plus(1) ?: 1, predecessor?.reference(), setOfNotNull(predecessor?.reference()), emptyMap(), "unsigned")
            return row.copy(signature = Base64.getEncoder().encodeToString(devices[actor].signSyncGroupPayload(row.signingBytes())))
        }
    }

    @Test fun legacyLedgerHashSignatureDomainAndWireShapeStayUnchanged() {
        fun id(char: Char) = SyncStoreId.parse(char.toString().repeat(32))
        val row = SyncDeviceLedgerRecord(id('1'), id('2'), SyncVersionRef(id('3'), "a".repeat(64)), 2,
            id('4'), "b".repeat(64), setOf("chat_sessions"), mapOf("c".repeat(64) to SyncGroupMember("QQ==", "Qg==")),
            setOf(SyncVersionRef(id('5'), "d".repeat(64))), "unsigned")
        val oldHash = "925ca047b06e6f7f8e34da87e5b5ca620555312ed91106b5dc86f4461c63eb9e"
        assertEquals(oldHash, row.bodyHash())
        assertEquals("CCB-SYNC-DEVICE-LEDGER-SIGNATURE/1\n$oldHash", row.signingBytes().toString(Charsets.US_ASCII))
        val bytes = lanSyncJson.encodeToString(SyncDeviceLedgerRecord.serializer(), row)
        assertFalse("recovery" in lanSyncJson.parseToJsonElement(bytes).jsonObject)
        assertEquals(row, lanSyncJson.decodeFromString(SyncDeviceLedgerRecord.serializer(), bytes))
    }

    @Test fun exactSignedRecoveryPreservesBothParentsAndHoldsAgainForAnUnreviewedLateSibling() {
        val f = Group()
        val left = f.added(3)
        val right = f.added(4)
        val late = f.added(5)
        val recovery = f.merge(listOf(left, right), right)
        val graph = SyncDeviceLedger(f.trust.copy(checkpoint = left.reference()))
        assertEquals(SyncVersionState.WAITING_FOR_PARENTS, graph.offer(recovery))
        graph.offer(f.genesis)
        graph.offer(right)
        assertEquals(SyncVersionState.WAITING_FOR_PARENTS, graph.state(recovery.id))
        assertEquals(SyncVersionState.ADMITTED, graph.offer(left))
        assertEquals(recovery, graph.current())
        assertEquals(setOf(recovery.reference()), graph.admittedTips())
        assertTrue(graph.isAncestorOrSelf(left.reference(), recovery.reference()))
        assertEquals(setOf(f.genesis, left, right, recovery), graph.ancestry().toSet())
        assertEquals(listOf(f.genesis, right, recovery), graph.authorityHistory())
        assertTrue(graph.hasKey(f.firstKey.epoch, f.firstKey.keyCommitment()))

        val reordered = recovery.copy(recovery = SyncGroupLedgerRecovery(linkedSetOf(right.reference(), left.reference()), right.reference()))
        assertEquals(recovery.reference(), reordered.reference())
        val switched = recovery.copy(recovery = requireNotNull(recovery.recovery).copy(primary = left.reference()))
        assertNotEquals(recovery.reference(), switched.reference())
        assertEquals("SYNC_GROUP_OWNER_SIGNATURE", assertFailsWith<IllegalArgumentException> { graph.offer(switched) }.message)

        assertEquals(SyncVersionState.ADMITTED, graph.offer(late))
        assertEquals(setOf(recovery.reference(), late.reference()), graph.admittedTips())
        assertEquals(2, assertNotNull(graph.verifiedFork()).headCount)
        assertEquals("SYNC_GROUP_LEDGER_FORK_OR_MISSING", assertFailsWith<IllegalArgumentException> { graph.current() }.message)
        val reopened = SyncDeviceLedger(f.trust.copy(checkpoint = recovery.reference()))
        listOf(recovery, right, late, left, f.genesis).forEach(reopened::offer)
        assertEquals(graph.admittedTips(), reopened.admittedTips())
        assertFailsWith<IllegalArgumentException> { reopened.current() }
        val second = f.merge(listOf(late, recovery), recovery)
        reopened.validateProposedRecovery(second)
        assertEquals(SyncVersionState.ADMITTED, reopened.offer(second))
        assertEquals(second, reopened.current())
        assertEquals(listOf(f.genesis, right, recovery, second), reopened.authorityHistory())
    }

    @Test fun recoveryRejectsIncompleteReviewAncestorParentsRosterSubstitutionAndEpochReuse() {
        val f = Group()
        val left = f.added(3)
        val right = f.added(4)
        val third = f.added(5)
        val graph = SyncDeviceLedger(f.trust)
        listOf(f.genesis, left, right, third).forEach(graph::offer)
        val incomplete = f.merge(listOf(left, right), left)
        assertEquals("SYNC_GROUP_RECOVERY_PROPOSAL", assertFailsWith<IllegalArgumentException> {
            graph.validateProposedRecovery(incomplete)
        }.message)
        val ancestor = f.merge(listOf(f.genesis, left), left)
        assertEquals(SyncVersionState.REJECTED, graph.offer(ancestor))
        assertEquals("SYNC_GROUP_RECOVERY_PARENTS", graph.failure(ancestor.id))
        val reused = f.merge(listOf(left, right, third), left, key = f.firstKey)
        assertEquals(SyncVersionState.REJECTED, graph.offer(reused))
        assertEquals("SYNC_GROUP_EPOCH_REUSED", graph.failure(reused.id))
        val omitted = f.merge(listOf(left, right, third), left, roster = left.members + right.members)
        assertEquals(SyncVersionState.REJECTED, graph.offer(omitted))
        assertEquals("SYNC_GROUP_RECOVERY_MEMBERS", graph.failure(omitted.id))
        val ledgerAsData = f.merge(listOf(left, right, third), left, fence = setOf(left.reference()))
        assertEquals(SyncVersionState.REJECTED, graph.offer(ledgerAsData))
        assertEquals("SYNC_GROUP_RECOVERY_CONTENT_FENCE", graph.failure(ledgerAsData.id))

        val chosen = f.ids[3]
        val incompatible = f.child(f.genesis, f.genesis.members + (chosen to f.members.getValue(chosen).copy(
            recipientKey = SyncDeviceEncryptionKey.create(chosen).publicKey.encoded)))
        assertEquals(SyncVersionState.ADMITTED, graph.offer(incompatible))
        val replaced = f.merge(listOf(left, right, third, incompatible), left)
        assertEquals(SyncVersionState.REJECTED, graph.offer(replaced))
        assertEquals("SYNC_GROUP_RECOVERY_MEMBER_KEY_CONFLICT", graph.failure(replaced.id))
    }

    @Test fun concurrentRecoveriesNeverPromoteAnIncomparableParentToTheSharedBaseline() {
        val f = Group()
        val left = f.added(3)
        val right = f.added(4)
        val a = f.merge(listOf(left, right), left)
        val b = f.merge(listOf(left, right), right)
        val graph = SyncDeviceLedger(f.trust)
        listOf(f.genesis, left, right, a, b).forEach { assertEquals(SyncVersionState.ADMITTED, graph.offer(it)) }
        val fork = assertNotNull(graph.verifiedFork())
        assertEquals(f.genesis, fork.common)
        assertEquals(setOf(a, b), fork.branches.toSet())
        assertFailsWith<IllegalArgumentException> { graph.current() }
    }

    @Test fun mergedHistoryHonorsEachOriginalRevocationAndTheNewExplicitCutAfterLaterLinearChanges() {
        val f = Group()
        val before = f.fact(1, f.genesis)
        val later = f.fact(1, f.genesis, before)
        val keptByDecision = f.fact(2, f.genesis)
        val outsideDecision = f.fact(2, f.genesis, keptByDecision)
        val revoked = f.genesis.members + (f.ids[1] to f.members.getValue(f.ids[1]).copy(active = false))
        val first = f.child(f.genesis, revoked, SyncStreamingCipher.create(f.group), setOf(before.reference()))
        val second = f.child(f.genesis, revoked, SyncStreamingCipher.create(f.group), setOf(later.reference()))
        val roster = revoked + (f.ids[2] to f.members.getValue(f.ids[2]).copy(active = false))
        val merge = f.merge(listOf(first, second), first, roster,
            fence = setOf(before.reference(), later.reference(), keptByDecision.reference()))
        val graph = SyncDeviceLedger(f.trust)
        listOf(f.genesis, first, second).forEach(graph::offer)
        val revived = f.merge(listOf(first, second), first, f.genesis.members)
        assertEquals(SyncVersionState.REJECTED, graph.offer(revived))
        assertEquals("SYNC_GROUP_MEMBER_REACTIVATION", graph.failure(revived.id))
        val lost = f.merge(listOf(first, second), first, roster, fence = setOf(before.reference()))
        assertEquals(SyncVersionState.REJECTED, graph.offer(lost))
        assertEquals("SYNC_GROUP_FENCE_LOST", graph.failure(lost.id))
        graph.validateProposedRecovery(merge)
        assertEquals(SyncVersionState.ADMITTED, graph.offer(merge))
        assertTrue(graph.hasKey(first.epoch, first.keyCommitment))
        assertTrue(graph.hasKey(second.epoch, second.keyCommitment))
        assertEquals(setOf(first, second), graph.revocationCuts(f.ids[1]).toSet())
        assertEquals(listOf(merge), graph.revocationCuts(f.ids[2]))

        val newest = f.child(merge, key = SyncStreamingCipher.create(f.group), fence = merge.acceptedHeads + outsideDecision.reference())
        assertEquals(SyncVersionState.ADMITTED, graph.offer(newest))
        val policy = graph.admission(listOf(before, later, keptByDecision, outsideDecision), emptyList())
        policy.requireAuthorized(before)
        policy.requireAuthorized(keptByDecision)
        for (held in listOf(later, outsideDecision)) assertEquals("SYNC_GROUP_REVOKED_VERSION",
            assertFailsWith<IllegalArgumentException> { policy.requireAuthorized(held) }.message)
    }

    @Test fun aFactOutsideTheSelectedAuthorityRequiresItsOriginalSignedRecoveryAcceptance() {
        val f = Group()
        val primary = f.added(3)
        val side = f.added(4)
        val accepted = f.fact(4, side)
        val deferred = f.fact(4, side, accepted)
        val recovery = f.merge(listOf(primary, side), primary, fence = setOf(accepted.reference()))
        val later = f.child(recovery, key = SyncStreamingCipher.create(f.group), fence = recovery.acceptedHeads + deferred.reference())
        val graph = SyncDeviceLedger(f.trust)
        listOf(f.genesis, primary, side, recovery, later).forEach { assertEquals(SyncVersionState.ADMITTED, graph.offer(it)) }
        val policy = graph.admission(listOf(accepted, deferred), emptyList())
        policy.requireAuthorized(accepted)
        assertEquals("SYNC_GROUP_RECOVERY_VERSION_NOT_REVIEWED",
            assertFailsWith<IllegalArgumentException> { policy.requireAuthorized(deferred) }.message)
    }
}
