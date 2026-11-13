package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

/** Source acknowledgments update native control only; genuine source originals and author journal remain mandatory. */
class LanSyncGroupSourceReceiptTest {
    private class Fixture(val root: Path) {
        val source = LanSyncGroupSourceWriterTest.Node(root)
        val control = Files.createDirectory(root.resolve("source-native-control"))
        fun owner(observe: (String) -> Unit = {}) = LanSyncGroupNativeApplyOwner(control, source.f.workspace, source.f.source.ledger.trustAnchor,
            Base64.getEncoder().encodeToString(source.f.source.owner.certificate.encoded), LanSyncProjection(), { source.f.custody().ledger() },
            source::view, { it.reversedArray() }, { it.reversedArray() }, observe, source.journal())
        init { owner().initializeNewEnrollment() }
        fun card(id: String) = requireNotNull(LanSyncJsonFiles.read(source.f.target.resolve("entities/character_cards/$id.json"), CharacterCard.serializer()))
        fun commit(): LanSyncGroupSourceCommit {
            source.localCard("a", "签名时的原文")
            return source.writer().commit(source.review(setOf("character_cards/a")).authorize(), SyncStoreId.random()).also { source.local += it.metadata }
        }
    }
    private fun withFixture(work: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("synthetic-source-native-receipt-").toRealPath()
        try { work(Fixture(root)) } finally { check(root.toFile().deleteRecursively()) }
    }
    @Test fun recordingTheSignedSourceAfterLaterLocalEditsNeverReplaysItsOldSnapshot() = withFixture { f ->
        val commit = f.commit(); f.source.localCard("a", "签名以后本机又修改")
        val receipt = f.owner().recordSource(commit)
        assertEquals(LanSyncGroupNativeApplyStatus.SOURCE_COMMIT_RECORDED, receipt.status)
        assertEquals(setOf("character_cards/a"), receipt.advancedUnits); assertTrue(receipt.heldUnits.isEmpty())
        assertFalse(f.source.f.workspace.applied(receipt.transaction)); assertEquals("签名以后本机又修改", f.card("a").name)
        val state = f.owner().state(); assertEquals(setOf(commit.entry.version.reference()), state.units.getValue("character_cards/a").frontiers)
        f.source.applied = state.units
        assertTrue(f.source.review(setOf("character_cards/a")).requiredBindings.isEmpty())
        assertEquals(receipt, f.owner().recordSource(commit)); assertEquals(1L, f.source.journal().state().committedSequence)
        assertEquals("签名以后本机又修改", f.card("a").name)
    }
    @Test fun aLostSourceReceiptRecoversItsHistoricalFactWithoutAnyNativeDataWrite() = withFixture { f ->
        val commit = f.commit()
        assertFailsWith<IOException> { f.owner { if (it == "NATIVE_SOURCE_INTENT_RETAINED") throw IOException("SYNTHETIC_SOURCE_RECEIPT_INTERRUPTION") }.recordSource(commit) }
        val pending = requireNotNull(f.owner().state().pendingTransaction)
        f.source.localCard("a", "中断后的本机内容")
        val state = f.owner().recover()
        assertNull(state.pendingTransaction); assertEquals(setOf(commit.entry.version.reference()), state.units.getValue("character_cards/a").frontiers)
        assertFalse(f.source.f.workspace.applied(pending)); assertEquals("中断后的本机内容", f.card("a").name)
        assertEquals(LanSyncGroupNativeApplyStatus.SOURCE_COMMIT_RECORDED, f.owner().recordSource(commit).status)
    }
    @Test fun independentNativeProgressAfterSourceSigningIsRetainedWhenOnlyThatSourceUnitIsRecorded() = withFixture { f ->
        val commit = f.commit(); val node = f.source
        node.localCard("a", "保留随后本机编辑"); node.f.card("b", "已收到的独立角色")
        val incoming = node.f.publish(setOf("character_cards/b"))
        val (view, evidence, dependencies) = node.inputs()
        val plan = node.f.workspace.capture(node.f.source.owner.fingerprint).use { LanSyncGroupNativePlanner.prepare(it, view, f.owner().state().units, dependencies, evidence) }
        assertEquals(setOf("character_cards/b"), plan.advances.keys)
        f.owner().apply(plan)
        val receipt = f.owner().recordSource(commit)
        assertEquals(setOf("character_cards/a"), receipt.advancedUnits)
        val state = f.owner().state()
        assertEquals(setOf(incoming.reference()), state.units.getValue("character_cards/b").frontiers)
        assertEquals(setOf(commit.entry.version.reference()), state.units.getValue("character_cards/a").frontiers)
        assertEquals("保留随后本机编辑", f.card("a").name); assertEquals("已收到的独立角色", f.card("b").name)
    }
}
