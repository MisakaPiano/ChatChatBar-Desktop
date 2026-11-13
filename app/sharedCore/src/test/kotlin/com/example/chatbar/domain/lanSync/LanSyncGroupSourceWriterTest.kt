package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.domain.syncStore.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

/** Actual source capture/custody, approval, signed journal and AEAD publication. Control protection is synthetic. */
class LanSyncGroupSourceWriterTest {
    internal class Node(val root: Path) {
        val f = LanSyncGroupNativePlannerTest.Fixture(root)
        val author = Files.createDirectory(root.resolve("source-author")); val sourceRoot = Files.createDirectory(root.resolve("source-writer"))
        val queues = Files.createDirectory(root.resolve("source-queues"))
        val keys = linkedMapOf(f.source.cipher.epoch to f.source.cipher)
        val local = mutableListOf<SyncAuthenticatedGroupObject>()
        var applied: Map<String, LanSyncGroupAppliedUnit> = emptyMap()
        var journalHook: (String) -> Unit = {}
        fun journal() = SyncAuthorJournal(author, f.source.ledger.trustAnchor,
            Base64.getEncoder().encodeToString(f.source.owner.certificate.encoded), { it.reversedArray() }, { it.reversedArray() }, { journalHook(it) })
        init { journal().initializeNewEnrollment() }
        fun outbox(epoch: SyncStoreId): SyncEncryptedOutbox {
            val root = queues.resolve(epoch.value); if (!Files.exists(root)) Files.createDirectory(root)
            return SyncEncryptedOutbox(root, keys.getValue(epoch))
        }
        fun view(): SyncInboxReconciliation {
            val base = f.view(); val actual = base.authenticatedVersions + local
            val versions = actual.map { requireNotNull(it.envelope.version) }
            val ledger = f.custody().ledger(); val history = SyncCausalHistory(f.source.cipher.group, ledger.admission(versions, actual))
            versions.forEach { try { history.offer(it) } catch (_: IllegalArgumentException) { /* A rotated outer epoch remains pending rewrap. */ } }
            return SyncInboxReconciliation(emptyList(), history, actual, false, ledger.current().reference())
        }
        fun writer() = LanSyncGroupSourceWriter(sourceRoot, f.workspace, f.source.owner, journal(), { f.custody().ledger() },
            { keys.getValue(it) }, ::outbox, ::view, { LanSyncGroupNativeState(applied, 0, null) })
        fun localCard(id: String, name: String) {
            val path = f.target.resolve("entities/character_cards/$id.json"); Files.createDirectories(path.parent)
            LanSyncJsonFiles.write(path, CharacterCard.serializer(), CharacterCard(id, name, createdAt = 1, updatedAt = 1))
        }
        fun inputs(): Triple<SyncInboxReconciliation, LanSyncGroupRecordEvidence, Map<LanSyncGroupMembershipRequest, LanSyncGroupDependencyResult>> {
            val view = view(); val evidence = LanSyncGroupRecordEvidence(requireNotNull(view.history), requireNotNull(view.ledgerCheckpoint))
            val service = LanSyncGroupDependencies(f.source.cipher.group, f.source.backend, f.custody(), f.source.work, f.workspace.resourceStore, { _, _, _ -> })
            val dependencies = linkedMapOf<LanSyncGroupMembershipRequest, LanSyncGroupDependencyResult>()
            view.authenticatedVersions.forEach { metadata ->
                val version = requireNotNull(metadata.envelope.version)
                version.changes.filterValues { it.content != null }.forEach { (unit, _) ->
                    service.membership(view, metadata, unit).membership?.let(evidence::offer)
                    dependencies[LanSyncGroupMembershipRequest(version.reference(), unit)] = service.inspect(view, metadata, unit)
                }
            }
            return Triple(view, evidence, dependencies)
        }
        fun review(selected: Set<String>): LanSyncGroupSourceReview {
            val (view, evidence, dependencies) = inputs()
            val plan = f.workspace.capture(f.source.owner.fingerprint).use { LanSyncGroupNativePlanner.prepare(it, view, applied, dependencies, evidence) }
            return LanSyncGroupSourceReview.prepare(plan, view, selected, dependencies, evidence)
        }
        fun acknowledge(commit: LanSyncGroupSourceCommit) {
            local += commit.metadata
            val (view, evidence, dependencies) = inputs()
            val plan = f.workspace.capture(f.source.owner.fingerprint).use { LanSyncGroupNativePlanner.prepare(it, view, applied, dependencies, evidence) }
            applied = f.apply(plan)
        }
    }
    private fun withNode(work: (Node) -> Unit) {
        val root = Files.createTempDirectory("synthetic-native-source-writer-").toRealPath()
        try { work(Node(root)) } finally { check(root.toFile().deleteRecursively()) }
    }
    @Test fun approvedSourceSurvivesDeletedOriginalsAndResumesTheExactLogicalAndCiphertextPublication() = withNode { n ->
        val a = "character_cards/a"; n.localCard("a", "合成完整源端角色")
        val operation = SyncStoreId.random(); val commit = n.writer().commit(n.review(setOf(a)).authorize(), operation)
        assertEquals(1L, n.journal().state().committedSequence)
        assertNull(n.f.source.backend.metadata(commit.publication.metadata.key))
        val checkpoint = requireNotNull(n.f.workspace.recovery.read(commit.entry.request.sourceCheckpoint))
        n.f.workspace.recovery.verify(checkpoint)
        assertEquals(n.f.source.cipher.group.value, checkpoint.sourceCheckpointGroup)
        assertTrue(n.f.workspace.recovery.snapshot(checkpoint).records.any { it.text("name") == "合成完整源端角色" })
        check(n.f.target.startsWith(n.root)); check(n.f.target.toFile().deleteRecursively())
        val resumed = n.writer().resume(operation)
        assertEquals(commit.entry, resumed.entry); assertEquals(commit.publication, resumed.publication)
        assertEquals(listOf(operation), n.writer().operations())
        val first = n.writer().upload(operation, n.f.source.backend)
        assertEquals(first, n.writer().upload(operation, n.f.source.backend))
        assertEquals(1L, n.journal().state().committedSequence)
        val read = SyncGroupObjectCodec(n.f.source.work).read(n.f.source.backend, n.f.source.cipher, resumed.metadata.context,
            resumed.metadata.metadata.descriptor, commit.entry.version.reference())
        assertEquals(commit.entry.version, read.envelope.version)
    }
    @Test fun anUnsignedDeletionAllocatesNothingWhileAnIndependentAdditionCanCommitAndLaterConfirmationDeletesOnlyItsUnit() = withNode { n ->
        val a = "character_cards/a"; val b = "character_cards/b"
        n.f.card("a", "原角色"); val first = n.f.publish(setOf(a)); n.applied = n.f.apply(n.f.plan(emptyMap()))
        Files.delete(n.f.target.resolve("entities/character_cards/a.json")); n.localCard("b", "独立新增")
        assertEquals("SYNC_GROUP_SOURCE_CONFIRMATION_REQUIRED", assertFailsWith<IllegalArgumentException> { n.review(setOf(a)).authorize() }.message)
        assertEquals(0L, n.journal().state().committedSequence); assertNull(n.journal().state().pendingApproved)
        val addition = n.writer().commit(n.review(setOf(b)).authorize(), SyncStoreId.random()); n.acknowledge(addition)
        assertEquals(setOf(b), addition.entry.version.changes.keys); assertEquals(setOf(first.reference()), n.applied.getValue(a).frontiers)
        val review = n.review(setOf(a)); val deletion = n.writer().commit(review.authorize(review.requiredBindings), SyncStoreId.random())
        assertEquals(2L, deletion.entry.version.authorSequence); assertNull(deletion.entry.version.changes.getValue(a).content)
        n.acknowledge(deletion)
        assertEquals(setOf(deletion.entry.version.reference()), n.applied.getValue(a).frontiers)
        assertEquals(setOf(addition.entry.version.reference()), n.applied.getValue(b).frontiers)
        assertFalse(Files.exists(n.f.target.resolve("entities/character_cards/a.json")))
    }
    @Test fun aProtectedApprovedIntentResumesAfterTheSourceDisappearsWithoutRequestingFreshApprovalOrAnotherCounter() = withNode { n ->
        val a = "character_cards/a"; n.localCard("a", "已批准中断源")
        val operation = SyncStoreId.random(); val authorization = n.review(setOf(a)).authorize()
        n.journalHook = { if (it == "APPROVED_AUTHOR_INTENT_RETAINED") throw IOException("SYNTHETIC_APPROVED_SOURCE_INTERRUPTION") }
        assertFailsWith<IOException> { n.writer().commit(authorization, operation) }
        val intent = requireNotNull(n.journal().state().pendingApproved)
        assertEquals(0L, n.journal().state().committedSequence)
        check(n.f.target.startsWith(n.root)); check(n.f.target.toFile().deleteRecursively()); n.journalHook = {}
        val resumed = n.writer().resume(operation)
        assertEquals(intent.version.reference(), resumed.entry.version.reference())
        assertEquals(1L, resumed.entry.version.authorSequence); assertEquals(resumed.entry, n.writer().resume(operation).entry)
        assertNull(n.journal().state().pendingApproved)
    }
    @Test fun epochRotationRewrapsFromRetainedCiphertextWithoutResigningOrReusingOldDeliveryIds() = withNode { n ->
        val a = "character_cards/a"; n.localCard("a", "换钥前已批准源")
        val operation = SyncStoreId.random(); val initial = n.writer().commit(n.review(setOf(a)).authorize(), operation)
        n.local += initial.metadata
        check(n.f.target.startsWith(n.root)); check(n.f.target.toFile().deleteRecursively())
        val key = SyncStreamingCipher.create(n.f.source.cipher.group); n.keys[key.epoch] = key
        val row = n.f.source.sign(n.f.source.genesis.copy(id = SyncStoreId.random(), parent = n.f.source.genesis.reference(), sequence = 2,
            epoch = key.epoch, keyCommitment = key.keyCommitment()))
        n.f.custody().update(listOf(row))
        val rotated = n.writer().resume(operation)
        assertEquals(initial.entry, rotated.entry); assertEquals(key.epoch, rotated.metadata.context.epoch)
        assertNotEquals(initial.publication.metadata.operation, rotated.publication.metadata.operation)
        assertTrue(rotated.publication.blobs.values.none { blob -> initial.publication.blobs.values.any { it.key == blob.key } })
        assertEquals(1L, n.journal().state().committedSequence)
        n.writer().upload(operation, n.f.source.backend)
        val read = SyncGroupObjectCodec(n.f.source.work).read(n.f.source.backend, key, rotated.metadata.context,
            rotated.metadata.metadata.descriptor, initial.entry.version.reference())
        assertEquals(initial.entry.version, read.envelope.version)
    }

    @Test fun aPendingFormatDeletionHoldsItsSessionSourceWhileAnIndependentCardStillCommits() = withNode { n ->
        val formatUnit = "format_cards/format"; val sessionUnit = "conversation/session"; val independent = "character_cards/independent"
        val format = n.f.source.data.resolve("entities/format_cards/format.json"); Files.createDirectories(format.parent)
        LanSyncJsonFiles.write(format, FormatCard.serializer(), FormatCard("format", "合成格式", "合成内容", createdAt = 1))
        val session = ChatSession("session", "card", "合成关联会话", formatCardId = "format", createdAt = 1, updatedAt = 1)
        val path = n.f.source.data.resolve("entities/chat_sessions/session.json"); Files.createDirectories(path.parent)
        LanSyncJsonFiles.write(path, ChatSession.serializer(), session)
        val first = n.f.publish(setOf(formatUnit, sessionUnit)); n.applied = n.f.apply(n.f.plan(emptyMap()))
        Files.delete(n.f.target.resolve("entities/format_cards/format.json"))
        LanSyncJsonFiles.write(n.f.target.resolve("entities/chat_sessions/session.json"), ChatSession.serializer(), session.copy(formatCardId = null))
        n.localCard("independent", "独立提交")
        val partial = n.review(setOf(sessionUnit))
        assertEquals(setOf(formatUnit), partial.units.getValue(sessionUnit).dependencies)
        assertEquals("SYNC_GROUP_SOURCE_DEPENDENCY_REVIEW_REQUIRED", assertFailsWith<IllegalArgumentException> { partial.authorize(partial.requiredBindings) }.message)
        assertEquals(0L, n.journal().state().committedSequence)
        val card = n.writer().commit(n.review(setOf(independent)).authorize(), SyncStoreId.random()); n.acknowledge(card)
        assertEquals(setOf(first.reference()), n.applied.getValue(formatUnit).frontiers)
        assertEquals(setOf(first.reference()), n.applied.getValue(sessionUnit).frontiers)
        val whole = n.review(setOf(formatUnit, sessionUnit)); assertEquals(setOf(formatUnit), whole.requiredBindings.keys)
        val resolved = n.writer().commit(whole.authorize(whole.requiredBindings), SyncStoreId.random())
        assertEquals(setOf(formatUnit, sessionUnit), resolved.entry.version.changes.keys)
        assertNull(resolved.entry.version.changes.getValue(formatUnit).content)
        assertEquals(2L, resolved.entry.version.authorSequence)
        n.acknowledge(resolved)
        assertEquals(setOf(resolved.entry.version.reference()), n.applied.getValue(sessionUnit).frontiers)
        assertEquals(setOf(card.entry.version.reference()), n.applied.getValue(independent).frontiers)
    }
}
