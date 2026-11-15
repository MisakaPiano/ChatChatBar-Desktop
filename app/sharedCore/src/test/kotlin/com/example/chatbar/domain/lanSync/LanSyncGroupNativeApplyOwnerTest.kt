package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

/** Real Workspace WAL/recovery with synthetic protected control; not DPAPI/Keystore or app lifecycle acceptance. */
class LanSyncGroupNativeApplyOwnerTest {
    private class Node(val root: Path, observeJournal: (String, Int) -> Unit = { _, _ -> }) {
        val f = LanSyncGroupNativePlannerTest.Fixture(root, observeJournal)
        val control = Files.createDirectory(root.resolve("native-control"))
        fun owner(observe: (String) -> Unit = {}) = LanSyncGroupNativeApplyOwner(control, f.workspace, f.source.ledger.trustAnchor,
            Base64.getEncoder().encodeToString(f.source.owner.certificate.encoded), LanSyncProjection(), { f.custody().ledger() }, f::view,
            { it.reversedArray() }, { it.reversedArray() }, observe)
        init { owner().initializeNewEnrollment() }
        fun plan() = f.plan(owner().state().units)
        fun localCard(id: String, name: String, updatedAt: Long = 1) = LanSyncJsonFiles.write(f.target.resolve("entities/character_cards/$id.json"),
            CharacterCard.serializer(), CharacterCard(id, name, createdAt = 1, updatedAt = updatedAt))
        fun readCard(id: String) = requireNotNull(LanSyncJsonFiles.read(f.target.resolve("entities/character_cards/$id.json"), CharacterCard.serializer()))
    }
    private fun withNode(work: (Node) -> Unit) {
        val root = Files.createTempDirectory("synthetic-native-group-control-").toRealPath()
        try { work(Node(root)) } finally { check(root.toFile().deleteRecursively()) }
    }
    @Test fun restrictedTargetsKeepHeldDataAndReceiptsWhileIndependentIncomingContentReallyApplies() = withNode { n ->
        val a = "character_cards/a"; val b = "character_cards/b"; val f = n.f
        f.card("a", "held"); f.card("b", "independent"); val version = f.publish(setOf(a, b))
        val restricted = LanSyncGroupNativePlanner.restrict(n.plan(), setOf(a))
        assertEquals(setOf(b), restricted.advances.keys); assertEquals(setOf(b), restricted.plan.snapshot.units().keys)
        val receipt = requireNotNull(n.owner().apply(restricted))
        assertEquals(setOf(b), receipt.advancedUnits); assertFalse(a in n.owner().state().units)
        assertEquals(setOf(version.reference()), n.owner().state().units.getValue(b).frontiers)
        assertFalse(Files.exists(f.target.resolve("entities/character_cards/a.json"))); assertEquals("independent", n.readCard("b").name)
        val allHeld = LanSyncGroupNativePlanner.restrict(n.plan(), setOf(a))
        assertTrue(allHeld.advances.isEmpty()); assertEquals(allHeld.before.hashes(), allHeld.plan.snapshot.hashes())
        val sequence = n.owner().state().completedSequence; assertNull(n.owner().apply(allHeld))
        assertEquals(sequence, n.owner().state().completedSequence)
    }
    @Test fun restrictionKeepsAnEntireSharedMediaComponentOutOfTheNativeTarget() = withNode { n ->
        val f = n.f; val units = setOf("character_cards/a", "character_cards/b", "character_cards/c")
        val image = f.source.data.resolve("images/shared.png"); Files.createDirectories(image.parent)
        Files.write(image, Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="))
        for (id in listOf("a", "b")) {
            val path = f.source.data.resolve("entities/character_cards/$id.json"); Files.createDirectories(path.parent)
            LanSyncJsonFiles.write(path, CharacterCard.serializer(), CharacterCard(id, id, avatar = image.toString(), createdAt = 1, updatedAt = 1))
        }
        f.card("c", "independent"); f.publish(units)
        val restricted = LanSyncGroupNativePlanner.restrict(n.plan(), setOf("character_cards/a"))
        assertEquals(setOf("character_cards/c"), restricted.advances.keys)
        assertEquals(setOf("character_cards/c"), restricted.plan.snapshot.units().keys); assertTrue(restricted.plan.snapshot.resources.isEmpty())
        assertEquals(setOf("character_cards/c"), requireNotNull(n.owner().apply(restricted)).advancedUnits)
        assertFalse(Files.exists(f.target.resolve("entities/character_cards/a.json")))
        assertFalse(Files.exists(f.target.resolve("entities/character_cards/b.json")))
    }

    @Test fun perUnitNativeReceiptsPreserveAnUnsignedLocalDeletionAcrossIndependentApplyAndRepeat() = withNode { n ->
        val a = "character_cards/a"; val b = "character_cards/b"; val f = n.f
        f.card("a", "原角色"); val first = f.publish(setOf(a)); n.owner().apply(n.plan())
        Files.delete(f.target.resolve("entities/character_cards/a.json"))
        f.card("a", "收到的新内容"); f.card("b", "独立角色"); val next = f.publish(setOf(a, b))
        val plan = n.plan(); val receipt = requireNotNull(n.owner().apply(plan))
        assertEquals(setOf(b), receipt.advancedUnits)
        assertEquals(setOf(first.reference()), n.owner().state().units.getValue(a).frontiers)
        assertEquals(setOf(next.reference()), n.owner().state().units.getValue(b).frontiers)
        assertEquals(receipt, n.owner().apply(plan)); assertEquals(2L, n.owner().state().completedSequence)
        assertFalse(Files.exists(f.target.resolve("entities/character_cards/a.json")))
        assertEquals(LanSyncGroupNativeStage.LOCAL_REDUCTION_REVIEW, n.plan().units.getValue(a).stage)
    }

    @Test fun aLostControlReceiptAfterNativeApplyAdvancesExactUnitsWithoutOverwritingLaterLocalEdits() = withNode { n ->
        val a = "character_cards/a"; val f = n.f
        f.card("a", "原角色"); f.publish(setOf(a)); n.owner().apply(n.plan())
        f.card("a", "收到的新内容"); val next = f.publish(setOf(a)); val plan = n.plan()
        assertFailsWith<IOException> { n.owner { if (it == "NATIVE_DATA_APPLIED") throw IOException("SYNTHETIC_CONTROL_RECEIPT_LOSS") }.apply(plan) }
        assertEquals(plan.plan.transaction, n.owner().state().pendingTransaction)
        assertEquals("收到的新内容", n.readCard("a").name)
        n.localCard("a", "随后本机编辑")
        val recovered = n.owner().recover()
        assertNull(recovered.pendingTransaction); assertEquals(setOf(next.reference()), recovered.units.getValue(a).frontiers)
        assertEquals("随后本机编辑", n.readCard("a").name)
        assertEquals(LanSyncGroupNativeStage.LOCAL_PROPOSAL, n.plan().units.getValue(a).stage)
        assertEquals(setOf(a), requireNotNull(n.owner().apply(plan)).advancedUnits)
        assertEquals("随后本机编辑", n.readCard("a").name)
        val preimage = f.workspace.recovery.list().single { it.targetBinding == lanSyncHash(kotlinx.serialization.json.JsonObject(plan.plan.snapshot.hashes().mapValues { row -> kotlinx.serialization.json.JsonPrimitive(row.value) })) }
        f.workspace.recovery.verify(preimage)
        assertTrue(f.workspace.recovery.snapshot(preimage).records.any { it.text("name") == "原角色" })
    }

    @Test fun anIntentWithoutAuthorizedNativeWritesIsRetainedAsNotAppliedAndRequiresAFreshPlan() = withNode { n ->
        val a = "character_cards/a"; val f = n.f
        f.card("a", "收到的新内容"); f.publish(setOf(a)); val plan = n.plan()
        assertFailsWith<IOException> { n.owner { if (it == "NATIVE_INTENT_RETAINED") throw IOException("SYNTHETIC_BEFORE_NATIVE_WAL") }.apply(plan) }
        assertFalse(f.workspace.applied(plan.plan.transaction))
        assertTrue(n.owner().recover().units.isEmpty())
        val old = requireNotNull(n.owner().apply(plan)); assertEquals(LanSyncGroupNativeApplyStatus.NOT_APPLIED, old.status)
        assertTrue(old.advancedUnits.isEmpty())
        val fresh = n.plan(); assertNotEquals(plan.plan.transaction, fresh.plan.transaction)
        assertEquals(LanSyncGroupNativeApplyStatus.APPLIED, requireNotNull(n.owner().apply(fresh)).status)
        assertEquals("收到的新内容", n.readCard("a").name)
    }

    @Test fun rawOnlySourceChangesAndLiveLedgerRotationRefuseAStaleFrozenPlan() = withNode { n ->
        val a = "character_cards/a"; val f = n.f
        f.card("a", "原角色"); f.publish(setOf(a)); n.owner().apply(n.plan())
        f.card("a", "收到的新内容"); f.publish(setOf(a)); val stale = n.plan()
        n.localCard("a", "原角色", updatedAt = 2)
        assertEquals("SYNC_GROUP_NATIVE_SOURCE_CHANGED", assertFailsWith<IllegalArgumentException> { n.owner().apply(stale) }.message)
        assertEquals(1L, n.owner().state().completedSequence)
        val fresh = n.plan()
        val key = SyncStreamingCipher.create(f.source.cipher.group)
        val row = f.source.sign(f.source.genesis.copy(id = SyncStoreId.random(), parent = f.source.genesis.reference(), sequence = 2,
            epoch = key.epoch, keyCommitment = key.keyCommitment()))
        f.custody().update(listOf(row))
        assertEquals("SYNC_GROUP_NATIVE_VIEW_CHANGED", assertFailsWith<IllegalArgumentException> { n.owner().apply(fresh) }.message)
        assertEquals("原角色", n.readCard("a").name)
    }

    @Test fun missingOriginsOrAcknowledgedHistoryNeverCreateAnEmptyReplacementState() = withNode { n ->
        val a = "character_cards/a"; val f = n.f
        f.card("a", "原角色"); f.publish(setOf(a)); n.owner().apply(n.plan())
        Files.delete(n.control.resolve("receipt-0000000000000000001.protected"))
        assertEquals("SYNC_GROUP_NATIVE_HISTORY_MISSING", assertFailsWith<IllegalArgumentException> { n.owner().state() }.message)
        Files.delete(n.control.resolve("origin.protected"))
        assertEquals("SYNC_GROUP_NATIVE_ORIGIN_MISSING", assertFailsWith<IllegalArgumentException> { n.owner().state() }.message)
        assertEquals("原角色", n.readCard("a").name)
    }

    @Test fun anAuthorizedWorkspaceInterruptionRecoversBeforeNativeControlAdvances() {
        val root = Files.createTempDirectory("synthetic-native-group-wal-").toRealPath(); var interrupt = false
        try {
            val n = Node(root) { stage, _ -> if (interrupt && stage == "AUTHORIZED") { interrupt = false; throw IOException("SYNTHETIC_AFTER_WAL_AUTHORIZATION") } }
            val a = "character_cards/a"; val f = n.f
            f.card("a", "原角色"); f.publish(setOf(a)); n.owner().apply(n.plan())
            f.card("a", "收到的新内容"); val next = f.publish(setOf(a)); val plan = n.plan(); interrupt = true
            assertFailsWith<IOException> { n.owner().apply(plan) }
            assertFalse(f.workspace.applied(plan.plan.transaction)); assertEquals("原角色", n.readCard("a").name)
            assertEquals(setOf(next.reference()), n.owner().recover().units.getValue(a).frontiers)
            assertEquals("收到的新内容", n.readCard("a").name)
            assertTrue(f.workspace.applied(plan.plan.transaction))
        } finally { check(root.toFile().deleteRecursively()) }
    }
}
