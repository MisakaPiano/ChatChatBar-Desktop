package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

/** Actual native records/AEAD dependencies; explicit fixture signing is not the native approval UI. */
class LanSyncGroupNativePlannerTest {
    internal class Fixture(root: Path, observeJournal: (String, Int) -> Unit = { _, _ -> }) {
        val source = LanSyncGroupPublicationQueueTest.Fixture(Files.createDirectory(root.resolve("source")))
        val sourceWorkspace = LanSyncWorkspace(source.data, source.state, { Path.of(it) })
        val target = Files.createDirectory(root.resolve("target")); val state = Files.createDirectory(root.resolve("target-state"))
        val workspace = LanSyncWorkspace(target, state, { Path.of(it) }, observeJournal = observeJournal)
        val custodyRoot = Files.createDirectory(root.resolve("custody"))
        val recipient = source.recipients.getValue(source.owner.fingerprint)
        fun custody() = SyncGroupCustody(custodyRoot, source.cipher.group, recipient, { it.reversedArray() }, { it.reversedArray() })
        private val received = mutableListOf<SyncAuthenticatedGroupObject>()
        private val authors = listOf(source.sender, source.owner)
        private val last = mutableMapOf<Int, SyncCausalVersion>()
        init {
            val ledger = custody().enroll(source.ledger.trustAnchor, source.ledger.history())
            val capsule = SyncGroupKeyDelivery.create(source.ledger, source.cipher, recipient.device, SyncStoreId.random(), source.owner::signSyncGroupPayload)
            custody().rememberKey(capsule.openTentative(source.ledger.trustAnchor, recipient,
                CiphertextObjectKey(CiphertextObjectKind.COMMIT, capsule.context.objectId)).admit(ledger))
        }
        fun card(id: String, name: String) {
            val path = source.data.resolve("entities/character_cards/$id.json"); Files.createDirectories(path.parent)
            LanSyncJsonFiles.write(path, CharacterCard.serializer(), CharacterCard(id, name, createdAt = 1, updatedAt = 1))
        }
        fun view(): SyncInboxReconciliation {
            val versions = received.map { requireNotNull(it.envelope.version) }
            val history = SyncCausalHistory(source.cipher.group, custody().ledger().admission(versions, received))
            versions.forEach { assertEquals(SyncVersionState.ADMITTED, history.offer(it)) }
            return SyncInboxReconciliation(emptyList(), history, received.toList(), false, custody().ledger().current().reference())
        }
        fun publish(units: Set<String>, deleted: Set<String> = emptySet(), author: Int = 0,
            parents: Set<SyncVersionRef> = requireNotNull(view().history).heads()): SyncCausalVersion {
            val history = requireNotNull(view().history); val actualParents = parents + listOfNotNull(last[author]?.reference())
            val bindings = linkedMapOf<String, SyncEnvelopeBlobBinding>(); val changes = linkedMapOf<String, SyncUnitChange>()
            fun blob(path: Path, identity: SyncPlaintextIdentity) {
                if (identity.sha256 in bindings) return
                val operation = SyncStoreId.random()
                bindings[identity.sha256] = source.blobs.enqueue(source.outbox, operation,
                    CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random()), source.cipher, path, identity)
                source.outbox.upload(operation, source.backend)
            }
            sourceWorkspace.capture(source.sender.fingerprint).use { capture ->
                units.forEach { unit -> LanSyncGroupUnitCodec(source.unitRoot).prepare(capture, unit).use { prepared ->
                    val decoded = prepared.decoded; blob(prepared.path, decoded.plaintext); blob(prepared.membershipPath, decoded.membership)
                    decoded.dependencies.forEach { blob(capture.blobs.getValue(it.sha256), it) }
                    changes[unit] = SyncUnitChange(decoded.valueHash, decoded.plaintext, history.frontier(unit, actualParents).map { it.version }.toSet(), decoded.membership)
                } }
            }
            deleted.forEach { changes[it] = SyncUnitChange(null, null, history.frontier(it, actualParents).map { row -> row.version }.toSet()) }
            val actor = authors[author]; val previous = last[author]
            val version = SyncCausalVersion(source.cipher.group, SyncStoreId.random(), source.genesis.reference(), actor.fingerprint,
                (previous?.authorSequence ?: 0) + 1, previous?.reference(), actualParents, changes, "unsigned").let {
                it.copy(signature = Base64.getEncoder().encodeToString(actor.signSyncGroupPayload(it.signingBytes())))
            }
            val metadata = source.codec.enqueue(source.outbox, SyncStoreId.random(), CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()),
                source.cipher, SyncGroupEnvelope(2, SyncGroupObjectKind.VERSION, version = version, blobs = bindings))
            source.outbox.upload(metadata.request.operationId, source.backend)
            received += source.codec.read(source.backend, source.cipher, metadata.context, metadata.request.descriptor)
            last[author] = version
            assertTrue(requireNotNull(view().history).contains(version.reference()))
            return version
        }
        fun plan(applied: Map<String, LanSyncGroupAppliedUnit>, blocked: Set<String> = emptySet(), fresh: Set<String> = emptySet()): LanSyncGroupNativePlan {
            val view = view(); val evidence = LanSyncGroupRecordEvidence(requireNotNull(view.history), requireNotNull(view.ledgerCheckpoint))
            val dependencies = linkedMapOf<LanSyncGroupMembershipRequest, LanSyncGroupDependencyResult>()
            val service = LanSyncGroupDependencies(source.cipher.group, source.backend, custody(), source.work, workspace.resourceStore, { _, _, _ -> })
            view.authenticatedVersions.forEach { metadata ->
                val version = requireNotNull(metadata.envelope.version)
                version.changes.filterValues { it.content != null }.forEach { (unit, _) ->
                    service.membership(view, metadata, unit).membership?.let(evidence::offer)
                    if (unit !in blocked) dependencies[LanSyncGroupMembershipRequest(version.reference(), unit)] = service.inspect(view, metadata, unit)
                }
            }
            return workspace.capture(source.owner.fingerprint).use { LanSyncGroupNativePlanner.prepare(it, view, applied, dependencies, evidence, fresh) }
        }
        fun apply(plan: LanSyncGroupNativePlan): Map<String, LanSyncGroupAppliedUnit> {
            workspace.capture(source.owner.fingerprint).use { capture -> workspace.apply(plan.plan, plan.expected, source.owner.fingerprint,
                capture.blobs + plan.units.values.flatMap { it.available?.resources.orEmpty().entries }.associate { it.toPair() }) }
            return plan.appliedBefore + plan.advances
        }
    }
    private fun withFixture(work: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("synthetic-native-group-plan-").toRealPath()
        try { work(Fixture(root)) } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun localUnsignedDeletionStaysPendingWhileAnIndependentIncomingUnitApplies() = withFixture { f ->
        val a = "character_cards/a"; val b = "character_cards/b"
        f.card("a", "原角色"); val first = f.publish(setOf(a)); val baseline = f.apply(f.plan(emptyMap()))
        Files.delete(f.target.resolve("entities/character_cards/a.json"))
        f.card("a", "对端新内容"); f.card("b", "独立新角色"); val next = f.publish(setOf(a, b))
        val plan = f.plan(baseline)
        assertEquals(LanSyncGroupNativeStage.LOCAL_REDUCTION_REVIEW, plan.units.getValue(a).stage)
        assertEquals(setOf(next.reference()), plan.units.getValue(a).incoming.keys)
        assertEquals(setOf(b), plan.advances.keys)
        val applied = f.apply(plan)
        assertEquals(setOf(first.reference()), applied.getValue(a).frontiers)
        assertEquals(setOf(next.reference()), applied.getValue(b).frontiers)
        f.workspace.capture(f.source.owner.fingerprint).use { assertEquals(setOf(b), it.snapshot.units().keys) }
        val repeat = f.plan(applied)
        assertTrue(repeat.advances.isEmpty()); assertEquals(LanSyncGroupNativeStage.LOCAL_REDUCTION_REVIEW, repeat.units.getValue(a).stage)
    }

    @Test fun anApprovedRemoteDeletionAppliesOnlyToAnUnchangedReplica() = withFixture { f ->
        val a = "character_cards/a"; f.card("a", "原角色"); f.publish(setOf(a)); val baseline = f.apply(f.plan(emptyMap()))
        val deletion = f.publish(emptySet(), setOf(a))
        val plan = f.plan(baseline)
        assertEquals(LanSyncGroupNativeStage.READY_DELETION, plan.units.getValue(a).stage)
        val applied = f.apply(plan)
        assertNull(applied.getValue(a).valueHash); assertEquals(setOf(deletion.reference()), applied.getValue(a).frontiers)
        val entries = f.workspace.recovery.list(); assertTrue(entries.isNotEmpty())
        assertTrue(entries.any { f.workspace.recovery.snapshot(it).records.any { row -> row.key == a } })
    }

    @Test fun missingResourcesAndConcurrentChangesDoNotPreventAnUnrelatedNativeUnitFromAdvancing() = withFixture { f ->
        val a = "character_cards/a"; val b = "character_cards/b"; val c = "character_cards/c"
        f.card("a", "原角色"); val first = f.publish(setOf(a)); val baseline = f.apply(f.plan(emptyMap()))
        f.card("a", "设备一"); f.publish(setOf(a), parents = setOf(first.reference()))
        f.card("a", "设备二"); f.card("b", "等待附件"); f.card("c", "可独立应用")
        f.publish(setOf(a, b, c), author = 1, parents = setOf(first.reference()))
        val plan = f.plan(baseline, blocked = setOf(b))
        assertEquals(LanSyncGroupNativeStage.CONFLICT, plan.units.getValue(a).stage)
        assertEquals(LanSyncGroupNativeStage.WAITING_DEPENDENCY, plan.units.getValue(b).stage)
        assertEquals(setOf(c), plan.advances.keys)
        val result = f.apply(plan)
        assertEquals(baseline.getValue(a), result.getValue(a)); assertFalse(b in result)
        f.workspace.capture(f.source.owner.fingerprint).use { assertEquals(setOf(a, c), it.snapshot.units().keys) }
    }

    @Test fun anExplicitFreshLocalRestoreIsNotAcknowledgedAsAnUnchangedOldValue() = withFixture { f ->
        val a = "character_cards/a"; f.card("a", "原角色"); f.publish(setOf(a)); val baseline = f.apply(f.plan(emptyMap()))
        f.publish(emptySet(), setOf(a))
        val plan = f.plan(baseline, fresh = setOf(a))
        assertEquals(LanSyncGroupNativeStage.CONFLICT, plan.units.getValue(a).stage)
        assertTrue(plan.advances.isEmpty())
        f.workspace.capture(f.source.owner.fingerprint).use { assertEquals(setOf(a), it.snapshot.units().keys) }
    }

    @Test fun thisDevicesAlreadyCommittedExactValueCanBeAcknowledgedWithoutAnotherSourceRevision() = withFixture { f ->
        val a = "character_cards/a"; f.card("a", "原角色"); f.publish(setOf(a), author = 1)
        val baseline = f.apply(f.plan(emptyMap()))
        LanSyncJsonFiles.write(f.target.resolve("entities/character_cards/a.json"), CharacterCard.serializer(), CharacterCard("a", "本机已批准内容", createdAt = 1, updatedAt = 7))
        f.card("a", "本机已批准内容"); val committed = f.publish(setOf(a), author = 1)
        val plan = f.plan(baseline)
        assertEquals(LanSyncGroupNativeStage.READY_OBSERVATION, plan.units.getValue(a).stage)
        assertEquals(setOf(committed.reference()), f.apply(plan).getValue(a).frontiers)
        assertEquals(7L, LanSyncJsonFiles.read(f.target.resolve("entities/character_cards/a.json"), CharacterCard.serializer())?.updatedAt)
        assertTrue(f.plan(plan.appliedBefore + plan.advances).advances.isEmpty())
    }
}
