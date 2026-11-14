package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Three actual native roots with real signatures/HPKE/AEAD and synthetic protection/file transport. */
class LanSyncGroupNodeTest {
    internal class Group(val root: Path) {
        val identities = List(3) { LanSyncTlsIdentity.create() }
        val recipients = identities.map { SyncDeviceEncryptionKey.create(it.fingerprint) }
        val cipher = SyncStreamingCipher.create()
        val members = identities.indices.associate { index -> identities[index].fingerprint to SyncGroupMember(
            Base64.getEncoder().encodeToString(identities[index].certificate.encoded), recipients[index].publicKey.encoded) }
        val unsigned = SyncDeviceLedgerRecord(cipher.group, SyncStoreId.random(), null, 1, cipher.epoch, cipher.keyCommitment(),
            LanSyncScope.schemas.keys, members, emptySet(), "unsigned")
        val genesis = unsigned.copy(signature = Base64.getEncoder().encodeToString(identities[0].signSyncGroupPayload(unsigned.signingBytes())))
        val trust = SyncGroupTrust(cipher.group, genesis.reference(), members.getValue(identities[0].fingerprint).certificate)
        val ledger = SyncDeviceLedger(trust).also { it.offer(genesis) }
        val backend = FileCiphertextStore(root.resolve("ciphertext-transport"))
        inner class End(val index: Int) {
            val base = Files.createDirectory(root.resolve("node-$index")); val data = Files.createDirectory(base.resolve("data"))
            val runtime = Files.createDirectory(base.resolve("runtime")); val custodyRoot = Files.createDirectory(base.resolve("custody"))
            var journalHook: (String, Int) -> Unit = { _, _ -> }
            val workspace = LanSyncWorkspace(data, Files.createDirectory(base.resolve("workspace")), { Path.of(it) },
                observeJournal = { stage, index -> journalHook(stage, index) })
            var gated = false; var reloads = 0; var hook: (String) -> Unit = {}
            fun custody() = SyncGroupCustody(custodyRoot, cipher.group, recipients[index], { it.reversedArray() }, { it.reversedArray() })
            fun author() = SyncAuthorJournal(runtime.resolve("author"), trust,
                Base64.getEncoder().encodeToString(identities[index].certificate.encoded), { it.reversedArray() }, { it.reversedArray() })
            val platform = object : LanSyncPlatform {
                override suspend fun exclusive(work: suspend () -> Unit) {
                    check(!gated); gated = true
                    try { work() } finally { gated = false }
                }
                override suspend fun reload(sessionIds: Set<String>) { check(gated); reloads++ }
            }
            fun open() = LanSyncGroupNode(runtime, workspace, platform, identities[index], trust, custody(), recipients[index], backend,
                { it.reversedArray() }, { it.reversedArray() }, { emptySet() }, { _, _, _ -> }, observe = { hook(it) })
            suspend fun enroll(): LanSyncGroupNode {
                val admitted = custody().enroll(trust, listOf(genesis))
                val capsule = SyncGroupKeyDelivery.create(ledger, cipher, identities[index].fingerprint, SyncStoreId.random(), identities[0]::signSyncGroupPayload)
                custody().rememberKey(capsule.openTentative(trust, recipients[index], CiphertextObjectKey(CiphertextObjectKind.COMMIT, capsule.context.objectId)).admit(admitted))
                return open().also { it.initializeNewEnrollment() }
            }
            fun card(id: String, name: String) {
                val path = data.resolve("entities/character_cards/$id.json"); Files.createDirectories(path.parent)
                LanSyncJsonFiles.write(path, CharacterCard.serializer(), CharacterCard(id, name, createdAt = 1, updatedAt = 1))
            }
            fun name(id: String) = LanSyncJsonFiles.read(data.resolve("entities/character_cards/$id.json"), CharacterCard.serializer())?.name
            val target = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, SyncStoreId.random())
            suspend fun send(node: LanSyncGroupNode, commit: LanSyncGroupSourceCommit) {
                val store = object : CiphertextStore by backend {
                    override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult {
                        assertFalse(gated, "network delivery held native storage gate"); return backend.put(request, source)
                    }
                }
                node.upload(commit.entry.request.operation, store, target)
            }
            suspend fun scan(node: LanSyncGroupNode): LanSyncGroupNodeView {
                var cursor: CommitCursor? = null
                do { cursor = node.discover(backend, cursor, 1).nextCursor } while (cursor != null)
                return node.inspect()
            }
        }
    }
    private fun withGroup(work: suspend (Group) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("synthetic-native-group-nodes-").toRealPath()
        try { work(Group(root)) } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun threeIndependentNodesRetainOfflineBranchesPartialDeletionAndRestartedSourceOrder() = withGroup { g ->
        val a = g.End(0); var an = a.enroll(); val b = g.End(1); val bn = b.enroll(); val c = g.End(2); val cn = c.enroll()
        val ua = "character_cards/a"; val ub = "character_cards/b"; val uc = "character_cards/c"
        a.card("a", "A的独立角色"); b.card("b", "B离线新建角色")
        val first = an.commit(an.inspect().review(setOf(ua)), operation = SyncStoreId.parse("f".repeat(32)))
        val offline = bn.commit(bn.inspect().review(setOf(ub)))
        assertNull(an.receipt(first.entry.request.operation, a.target)); assertNull(g.backend.metadata(first.publication.metadata.key))
        a.send(an, first); b.send(bn, offline)
        val incoming = c.scan(cn); assertEquals(setOf(ua, ub), incoming.plan?.advances?.keys)
        cn.apply(requireNotNull(incoming.plan)); assertEquals("A的独立角色", c.name("a")); assertEquals("B离线新建角色", c.name("b"))
        an.apply(requireNotNull(a.scan(an).plan)); bn.apply(requireNotNull(b.scan(bn).plan))
        // Local deletion remains unsigned while a separate addition progresses.
        Files.delete(a.data.resolve("entities/character_cards/a.json")); a.card("c", "A随后独立新增")
        var view = an.inspect(); assertEquals(LanSyncGroupNativeStage.LOCAL_REDUCTION_REVIEW, view.plan?.units?.get(ua)?.stage)
        assertFailsWith<IllegalArgumentException> { view.review(setOf(ua)).authorize() }
        val addition = an.commit(view.review(setOf(uc)), operation = SyncStoreId.parse("0".repeat(31) + "1"))
        assertEquals(2L, addition.entry.version.authorSequence); assertEquals(setOf(uc), addition.entry.version.changes.keys)
        a.send(an, addition); a.card("c", "重启前又编辑的原文")
        an = a.open(); view = an.inspect()
        assertEquals(2L, addition.entry.version.authorSequence)
        assertEquals(setOf(addition.entry.version.reference()), view.nativeState.units.getValue(uc).frontiers)
        assertEquals("重启前又编辑的原文", a.name("c")); assertNull(a.name("a"))
        val before = view.nativeState.completedSequence; assertEquals(before, an.inspect().nativeState.completedSequence)
        // B diverges locally; the signed deletion must not overwrite that branch.
        b.card("a", "B保留的离线修改")
        val deletionReview = an.inspect().review(setOf(ua))
        val deletion = an.commit(deletionReview, deletionReview.requiredBindings)
        assertEquals(3L, deletion.entry.version.authorSequence); assertNull(deletion.entry.version.changes.getValue(ua).content)
        a.send(an, deletion)
        val bv = b.scan(bn); assertEquals(LanSyncGroupNativeStage.CONFLICT, bv.plan?.units?.get(ua)?.stage)
        assertEquals(setOf(uc), bv.plan?.advances?.keys); bn.apply(requireNotNull(bv.plan))
        assertEquals("B保留的离线修改", b.name("a")); assertEquals("A随后独立新增", b.name("c"))
        val cv = c.scan(cn); cn.apply(requireNotNull(cv.plan))
        assertEquals("A的独立角色", c.name("a")); assertEquals("A随后独立新增", c.name("c"))
        val deletionPreview = requireNotNull(cn.inspect().incomingReview)
        assertEquals(setOf(ua), deletionPreview.reductions.keys)
        cn.applyIncoming(deletionPreview.acknowledge(deletionPreview.requiredBindings)); assertNull(c.name("a"))
        assertTrue(c.workspace.recovery.list().isNotEmpty()); assertTrue(c.reloads >= 2)
        val repeat = c.open().inspect(); assertTrue(requireNotNull(repeat.plan).advances.isEmpty())
        assertEquals(setOf(deletion.entry.version.reference()), repeat.nativeState.units.getValue(ua).frontiers)
    }

    @Test fun anUnapprovedPreparationSurvivesRestartWithoutAllocatingOrPublishingAnAuthorVersion() = withGroup { g ->
        val end = g.End(0); val node = end.enroll(); end.card("a", "尚未保留批准意图")
        val view = node.inspect(); val operation = SyncStoreId.random()
        end.hook = { if (it == "SOURCE_PREPARATION_RETAINED") throw IOException("SYNTHETIC_SOURCE_PREPARATION_INTERRUPTED") }
        assertFailsWith<IOException> { node.commit(view.review(setOf("character_cards/a")), operation = operation) }
        end.hook = {}; val recovered = end.open().inspect()
        assertEquals(setOf(operation), recovered.unsignedPreparations); assertEquals(0L, recovered.nativeState.completedSequence)
        assertTrue(requireNotNull(recovered.reconciliation.history).admittedVersions().isEmpty())
        assertTrue(g.backend.listCommits().entries.isEmpty()); assertEquals("尚未保留批准意图", end.name("a"))
    }

    @Test fun ordinaryStartupRefusesAMissingAuthorOriginInsteadOfReinitializingIt() = withGroup { g ->
        val end = g.End(0); end.enroll(); end.card("a", "保留原资料")
        val origin = end.runtime.resolve("author/origin.protected"); Files.delete(origin)
        assertEquals("SYNC_AUTHOR_ORIGIN_MISSING", assertFailsWith<IllegalArgumentException> { end.open().inspect() }.message)
        assertFalse(Files.exists(origin)); assertEquals("保留原资料", end.name("a")); assertFalse(end.gated)
    }

    @Test fun restoringAnOwnAlreadySignedValueSurvivesRestartAsExactlyOneFreshLogicalRevision() = withGroup { g ->
        val end = g.End(0); var node = end.enroll(); val unit = "character_cards/a"
        end.card("a", "已签名的旧原文")
        val original = node.commit(node.inspect().review(setOf(unit)))
        end.card("a", "尚未签名的临时编辑")
        val preview = node.prepareRecovery(original.entry.request.sourceCheckpoint, unit)
        assertTrue(preview.hasChanges)
        val restored = node.applyRecovery(preview)
        assertTrue(restored.applied); assertEquals(setOf(unit), restored.restoredUnits)
        assertEquals("已签名的旧原文", end.name("a"))
        node = end.open(); val fresh = node.inspect()
        assertEquals(LanSyncGroupNativeStage.LOCAL_PROPOSAL, fresh.plan?.units?.get(unit)?.stage)
        assertEquals(setOf(original.entry.version.reference()), fresh.nativeState.units.getValue(unit).frontiers)
        val revision = node.commit(fresh.review(setOf(unit)))
        assertEquals(2L, revision.entry.version.authorSequence)
        assertEquals(original.entry.version.changes.getValue(unit).valueHash, revision.entry.version.changes.getValue(unit).valueHash)
        assertNotEquals(original.entry.version.reference(), revision.entry.version.reference())
        assertEquals(mapOf(unit to setOf(restored.revision)), revision.restorations)
        val completed = end.open().inspect()
        assertEquals(LanSyncGroupNativeStage.UNCHANGED, completed.plan?.units?.get(unit)?.stage)
        // The signed acknowledgment of one exact restore cannot swallow a subsequent restore of that unit.
        end.card("a", "又一次临时编辑")
        val later = node.applyRecovery(node.prepareRecovery(original.entry.request.sourceCheckpoint, unit))
        assertNotEquals(restored.revision, later.revision)
        assertEquals(LanSyncGroupNativeStage.LOCAL_PROPOSAL, end.open().inspect().plan?.units?.get(unit)?.stage)
    }
}
