package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

/** Real signatures and streamed membership. Payload identities are placeholders, not native readiness. */
class LanSyncGroupRecordEvidenceTest {
    private class Group(val root: Path) {
        val group = SyncStoreId.random(); val ledger = SyncVersionRef(SyncStoreId.random(), "c".repeat(64))
        val actors = List(3) { LanSyncTlsIdentity.create() }
        val certificates = actors.associate { it.fingerprint to SyncDeviceSignatures.decode(Base64.getEncoder().encodeToString(it.certificate.encoded)) }
        val history = SyncCausalHistory(group, SyncVersionAdmission { version ->
            require(version.group == group && certificates.getValue(version.author).verifies(version.signingBytes(), version.signature)) { "SYNC_SYNTHETIC_SIGNATURE" }
        })
        private val offered = mutableListOf<LanSyncGroupMembershipEvidence>()
        val evidence get() = LanSyncGroupRecordEvidence(history, ledger).also { view -> offered.forEach(view::offer) }
        private val manifests = linkedMapOf<SyncPlaintextIdentity, Path>()
        fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        fun value(label: String, vararg records: String, previous: Set<SyncVersionRef> = emptySet()): SyncUnitChange {
            val path = Files.createTempFile(root, "members-", ".bin")
            Files.writeString(path, "CCB-SYNC-NATIVE-MEMBERS/1\n" + records.sorted().joinToString("") { JsonPrimitive(it).toString() + "\n" })
            val identity = SyncPlaintextIdentity(Files.size(path), LanSyncResourceStore.hashFile(path, Files.size(path))); manifests[identity] = path
            return SyncUnitChange(hash(label), SyncPlaintextIdentity(1, hash("payload-$label")), previous, identity)
        }
        fun version(actor: Int, sequence: Long, previous: SyncCausalVersion? = null, parents: Set<SyncVersionRef> = emptySet(),
            changes: Map<String, SyncUnitChange>): SyncCausalVersion {
            val row = SyncCausalVersion(group, SyncStoreId.random(), ledger, actors[actor].fingerprint, sequence, previous?.reference(), parents, changes, "unsigned")
            return row.copy(signature = Base64.getEncoder().encodeToString(actors[actor].signSyncGroupPayload(row.signingBytes()))).also {
                assertEquals(SyncVersionState.ADMITTED, history.offer(it))
            }
        }
        fun offer(version: SyncCausalVersion, unit: String) {
            val identity = requireNotNull(version.changes.getValue(unit).membership)
            offered += LanSyncGroupMembershipEvidence(version.reference(), ledger, unit, identity, manifests.getValue(identity), false)
        }
    }
    private fun withGroup(work: (Group) -> Unit) {
        val root = Files.createTempDirectory("synthetic-group-record-evidence-").toRealPath()
        try { work(Group(root)) } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun aStaleMomentMergeCannotReintroduceARecordDeletedInTheOtherComponentBranch() = withGroup { g ->
        val old = "moment-task/old"; val moved = "moment-task/new"; val record = "moment_posts/post"
        val first = g.version(0, 1, changes = mapOf(old to g.value("first", record)))
        val deletion = g.version(1, 1, parents = setOf(first.reference()), changes = mapOf(old to SyncUnitChange(null, null, setOf(first.reference()))))
        val offline = g.version(0, 2, first, setOf(first.reference()), mapOf(old to SyncUnitChange(null, null, setOf(first.reference())),
            moved to g.value("offline merge", record, "moment_tasks/new")))
        g.offer(first, old); g.offer(offline, moved)
        val result = g.evidence.checkCommitted(offline.reference(), moved)
        assertFalse(result.permitsContent); assertTrue(result.missing.isEmpty())
        assertEquals(setOf(LanSyncGroupRecordRemoval(record, old, deletion.reference())), result.removals)
        assertFalse(g.evidence.checkProposal(moved, setOf(record, "moment_tasks/new")) { _, _ -> false }.permitsContent)
        assertTrue(g.evidence.checkProposal(moved, setOf(record, "moment_tasks/new")) { unit, removal ->
            unit == old && removal in setOf(deletion.reference(), offline.reference())
        }.permitsContent)
    }

    @Test fun repeatedTombstonesKeepRecordEvidenceAndAnAcknowledgedRestorationIsANewRevision() = withGroup { g ->
        val old = "moment-task/old"; val moved = "moment-task/new"; val record = "moment_posts/post"
        val first = g.version(0, 1, changes = mapOf(old to g.value("first", record)))
        val deletion = g.version(1, 1, parents = setOf(first.reference()), changes = mapOf(old to SyncUnitChange(null, null, setOf(first.reference()))))
        val offline = g.version(0, 2, first, setOf(first.reference()), mapOf(old to SyncUnitChange(null, null, setOf(first.reference())),
            moved to g.value("offline", record, "moment_tasks/new")))
        val repeated = g.version(1, 2, deletion, setOf(deletion.reference()), mapOf(old to SyncUnitChange(null, null, setOf(deletion.reference()))))
        g.offer(first, old); g.offer(offline, moved)
        assertEquals(setOf(repeated.reference()), g.evidence.checkCommitted(offline.reference(), moved).removals.map { it.removal }.toSet())
        val restored = g.version(0, 3, offline, setOf(offline.reference(), repeated.reference()),
            mapOf(moved to g.value("reviewed restoration", record, "moment_tasks/new", previous = setOf(offline.reference()))))
        g.offer(restored, moved)
        assertTrue(g.evidence.checkCommitted(restored.reference(), moved).permitsContent)
    }

    @Test fun missingRelatedMembershipWaitsButAnIndependentFixedNamespaceStillAdvances() = withGroup { g ->
        val old = "moment-task/old"; val moved = "moment-task/new"; val record = "moment_posts/post"
        val first = g.version(0, 1, changes = mapOf(old to g.value("first", record)))
        g.version(1, 1, parents = setOf(first.reference()), changes = mapOf(old to SyncUnitChange(null, null, setOf(first.reference()))))
        val offline = g.version(0, 2, first, setOf(first.reference()), mapOf(old to SyncUnitChange(null, null, setOf(first.reference())),
            moved to g.value("offline", record, "moment_tasks/new")))
        g.offer(offline, moved)
        assertEquals(setOf(LanSyncGroupMembershipRequest(first.reference(), old)), g.evidence.checkCommitted(offline.reference(), moved).missing)
        val card = g.version(2, 1, changes = mapOf("character_cards/independent" to g.value("independent", "character_cards/independent")))
        g.offer(card, "character_cards/independent")
        assertTrue(g.evidence.checkCommitted(card.reference(), "character_cards/independent").permitsContent)
    }

    @Test fun aFrozenRecordViewDoesNotChangeWhenDiscoveryAddsAConcurrentDeletion() = withGroup { g ->
        val old = "moment-task/old"; val moved = "moment-task/new"; val record = "moment_posts/post"
        val first = g.version(0, 1, changes = mapOf(old to g.value("first", record)))
        val movement = g.version(0, 2, first, setOf(first.reference()), mapOf(old to SyncUnitChange(null, null, setOf(first.reference())),
            moved to g.value("moved", record)))
        g.offer(first, old); g.offer(movement, moved)
        val frozen = g.evidence
        assertTrue(frozen.checkCommitted(movement.reference(), moved).permitsContent)
        val deletion = g.version(1, 1, parents = setOf(first.reference()), changes = mapOf(old to SyncUnitChange(null, null, setOf(first.reference()))))
        assertTrue(frozen.checkCommitted(movement.reference(), moved).permitsContent)
        assertEquals(setOf(deletion.reference()), g.evidence.checkCommitted(movement.reference(), moved).removals.map { it.removal }.toSet())
    }
}
