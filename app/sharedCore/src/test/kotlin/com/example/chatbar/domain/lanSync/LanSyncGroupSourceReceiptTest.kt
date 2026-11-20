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

    private fun withChangedText(file: Path, original: String, work: () -> Unit) {
        val bytes = Files.readAllBytes(file)
        val edited = bytes.toString(Charsets.UTF_8).replace(original, "x" + original.drop(1)).toByteArray(Charsets.UTF_8)
        check(edited.size == bytes.size && !edited.contentEquals(bytes))
        val modified = Files.getLastModifiedTime(file)
        Files.write(file, edited)
        try {
            Files.setLastModifiedTime(file, modified)
            work()
        } finally {
            Files.write(file, bytes)
            Files.setLastModifiedTime(file, modified)
        }
    }

    @Test fun sourceOnlyRepeatVerifiesOneNativePayloadAndAuthenticatesItOnEveryCall() = withFixture { f ->
        val commit = f.commit()
        val receipt = f.owner().recordSource(commit)
        val state = f.owner().state()
        f.source.localCard("a", "本机保留新写入")
        val current = f.card("a")
        var checks = 0
        val owner = f.owner { if (it == "NATIVE_PAYLOAD_VERIFIED") checks++ }
        assertEquals(receipt, owner.recordSource(commit, recoverNative = false))
        assertEquals(1, checks)
        val payload = f.control.resolve("plan-${receipt.transaction}.json")
        withChangedText(payload, receipt.transaction) {
            assertEquals("SYNC_METADATA_DAMAGED", assertFailsWith<IllegalArgumentException> {
                owner.recordSource(commit, recoverNative = false)
            }.message)
            assertEquals(1, checks)
            assertEquals(current, f.card("a"))
        }
        assertEquals(receipt, owner.recordSource(commit, recoverNative = false))
        assertEquals(2, checks)
        assertEquals(state, f.owner().state())
        assertEquals(current, f.card("a"))
        assertEquals(1L, f.source.journal().state().committedSequence)
    }

    @Test fun completedSourceRevalidatesTheCheckpointEvenWhenItsSizeAndTimestampMatch() = withFixture { f ->
        val commit = f.commit()
        val writer = f.source.writer()
        val operation = commit.entry.request.operation
        assertEquals(commit.entry, writer.resume(operation).entry)
        val current = f.card("a")
        val checkpoint = f.source.f.state.resolve("recovery/entries").resolve(commit.entry.request.sourceCheckpoint).resolve("snapshot.json")
        withChangedText(checkpoint, f.source.f.source.owner.fingerprint) {
            assertEquals("SYNC_RECOVERY_DAMAGED", assertFailsWith<IllegalArgumentException> { writer.resume(operation) }.message)
            assertEquals(current, f.card("a"))
        }
        assertEquals(commit.publication, writer.resume(operation).publication)
        assertEquals(1L, f.source.journal().state().committedSequence)
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
        assertEquals("SYNC_GROUP_SOURCE_RECOVERY_REQUIRED", assertFailsWith<IllegalArgumentException> {
            f.owner().recordSource(commit, recoverNative = false)
        }.message)
        assertEquals(pending, f.owner().state().pendingTransaction)
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
