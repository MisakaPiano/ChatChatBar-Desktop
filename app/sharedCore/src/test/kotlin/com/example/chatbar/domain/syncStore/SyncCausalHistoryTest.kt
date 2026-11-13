package com.example.chatbar.domain.syncStore

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.*

/** Causal/authentication kernel evidence only; object references here are synthetic metadata. */
class SyncCausalHistoryTest {
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private inner class Device {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val id = MessageDigest.getInstance("SHA-256").digest(keys.public.encoded).joinToString("") { "%02x".format(it) }
    }
    private inner class Group {
        val id = SyncStoreId.random(); val ledger = SyncVersionRef(SyncStoreId.random(), hash("synthetic signed ledger placeholder"))
        val devices = List(4) { Device() }
        val admission = SyncVersionAdmission { version ->
            require(version.deviceLedger == ledger) { "SYNC_TEST_UNKNOWN_LEDGER" }
            val device = requireNotNull(devices.firstOrNull { it.id == version.author }) { "SYNC_TEST_UNKNOWN_DEVICE" }
            require(Signature.getInstance("SHA256withECDSA").run {
                initVerify(device.keys.public); update(version.signingBytes()); verify(Base64.getDecoder().decode(version.signature))
            }) { "SYNC_TEST_SIGNATURE" }
        }
        fun history() = SyncCausalHistory(id, admission)
        fun version(device: Int, parents: Set<SyncVersionRef>, changes: Map<String, SyncUnitChange>, previous: SyncCausalVersion? = null): SyncCausalVersion {
            val actor = devices[device]
            val unsigned = SyncCausalVersion(id, SyncStoreId.random(), ledger, actor.id,
                previous?.authorSequence?.plus(1) ?: 1L, previous?.reference(), parents, changes, "unsigned")
            val signed = Signature.getInstance("SHA256withECDSA").run { initSign(actor.keys.private); update(unsigned.signingBytes()); sign() }
            return unsigned.copy(signature = Base64.getEncoder().encodeToString(signed))
        }
        fun value(text: String, previous: Set<SyncVersionRef> = emptySet()) = SyncUnitChange(hash(text),
            SyncVersionContent(SyncStoreId.random(), CiphertextDescriptor(117, CiphertextSha256.parse(hash("synthetic ciphertext $text")))), previous)
    }

    @Test fun fourDevicesConvergeOutOfOrderWithoutResurrectingADeletedUnitFromAnOfflineUnrelatedBranch() {
        val group = Group(); val graph = group.history()
        val initial = group.version(0, emptySet(), mapOf("card/a" to group.value("原件")))
        val pc2 = group.version(1, setOf(initial.reference()), mapOf("world/b" to group.value("第二台电脑独立修改")))
        val deleted = group.version(2, setOf(initial.reference()), mapOf("card/a" to SyncUnitChange(null, null, setOf(initial.reference()))))
        val offline = group.version(3, setOf(initial.reference()), mapOf("conversation/c" to group.value("离线手机独立修改")))
        for (version in listOf(offline, pc2, deleted)) assertEquals(SyncVersionState.WAITING_FOR_PARENTS, graph.offer(version))
        assertTrue(graph.heads().isEmpty()); assertEquals(SyncVersionState.ADMITTED, graph.offer(initial))
        assertEquals(setOf(pc2.reference(), deleted.reference(), offline.reference()), graph.heads())
        assertEquals(setOf(deleted.reference()), graph.frontier("card/a").map { it.version }.toSet())
        assertNull(graph.frontier("card/a").single().change.valueHash)
        assertEquals(pc2.reference(), graph.frontier("world/b").single().version)
        assertEquals(offline.reference(), graph.frontier("conversation/c").single().version)
        val reopened = group.history()
        for (version in listOf(deleted, initial, offline, pc2, offline, deleted)) reopened.offer(version)
        assertEquals(graph.heads(), reopened.heads()); assertEquals(graph.frontier("card/a"), reopened.frontier("card/a"))
    }

    @Test fun concurrentEditAndDeletionStaySeparateUntilANewRevisionAcknowledgesBothBranches() {
        val group = Group(); val graph = group.history()
        val initial = group.version(0, emptySet(), mapOf("conversation/a" to group.value("原消息及版本")))
        val edit = group.version(1, setOf(initial.reference()), mapOf("conversation/a" to group.value("离线编辑", setOf(initial.reference()))))
        val deletion = group.version(2, setOf(initial.reference()), mapOf("conversation/a" to SyncUnitChange(null, null, setOf(initial.reference()))))
        listOf(edit, deletion, initial).forEach(graph::offer)
        val frontier = graph.frontier("conversation/a"); assertEquals(setOf(edit.reference(), deletion.reference()), frontier.map { it.version }.toSet())
        val resolved = group.version(0, graph.heads(), mapOf("conversation/a" to group.value("明确选择的新版本", frontier.map { it.version }.toSet())), initial)
        assertEquals(SyncVersionState.ADMITTED, graph.offer(resolved))
        assertEquals(setOf(resolved.reference()), graph.heads()); assertEquals(resolved.reference(), graph.frontier("conversation/a").single().version)
        assertEquals(hash("明确选择的新版本"), graph.frontier("conversation/a").single().change.valueHash)
    }

    @Test fun forgedSignaturesAndParentSubstitutionRefuseWhileIndependentHistoryStillAdvances() {
        val group = Group(); val graph = group.history()
        val initial = group.version(0, emptySet(), mapOf("card/a" to group.value("原件")))
        val forged = initial.copy(changes = mapOf("card/a" to group.value("伪造正文")))
        assertEquals("SYNC_TEST_SIGNATURE", assertFailsWith<IllegalArgumentException> { graph.offer(forged) }.message)
        assertTrue(graph.heads().isEmpty()); graph.offer(initial)
        val wrongParent = initial.reference().copy(bodyHash = hash("wrong body"))
        val bad = group.version(1, setOf(wrongParent), mapOf("card/a" to group.value("不能替换父版本", setOf(wrongParent))))
        assertEquals(SyncVersionState.REJECTED, graph.offer(bad)); assertEquals("SYNC_VERSION_PARENT_BINDING", graph.failure(bad.id))
        val independent = group.version(2, setOf(initial.reference()), mapOf("card/b" to group.value("继续处理")))
        assertEquals(SyncVersionState.ADMITTED, graph.offer(independent)); assertEquals(independent.reference(), graph.frontier("card/b").single().version)
        assertEquals(initial.reference(), graph.frontier("card/a").single().version)
    }

    @Test fun incorrectDeletionEvidenceAndSkippedAuthorPredecessorsHaveNoPublicationAuthority() {
        val group = Group(); val graph = group.history()
        val initial = group.version(0, emptySet(), mapOf("card/a" to group.value("原件")))
        graph.offer(initial)
        val badDeletion = group.version(1, setOf(initial.reference()), mapOf("card/a" to SyncUnitChange(null, null, emptySet())))
        assertEquals(SyncVersionState.REJECTED, graph.offer(badDeletion)); assertEquals("SYNC_VERSION_UNIT_ANCESTOR", graph.failure(badDeletion.id))
        val emptyDeletion = group.version(2, setOf(initial.reference()), mapOf("absent" to SyncUnitChange(null, null, emptySet())))
        assertEquals(SyncVersionState.REJECTED, graph.offer(emptyDeletion)); assertEquals("SYNC_VERSION_EMPTY_TOMBSTONE", graph.failure(emptyDeletion.id))
        val skipped = group.version(0, emptySet(), mapOf("card/b" to group.value("跳过设备前序")), initial)
        assertEquals(SyncVersionState.REJECTED, graph.offer(skipped)); assertEquals("SYNC_VERSION_AUTHOR_CHAIN", graph.failure(skipped.id))
        assertEquals(setOf(initial.reference()), graph.heads())
    }

    @Test fun callersCannotMutateAdmittedFactsAndSameIdentityBodiesNeverOverwriteEarlierEvidence() {
        val group = Group(); val graph = group.history()
        val rows = linkedMapOf("card/a" to group.value("原件"))
        val signed = group.version(0, emptySet(), rows); val frozen = signed.copy(changes = rows.toMap()); graph.offer(signed)
        rows.clear(); assertEquals(hash("原件"), graph.frontier("card/a").single().change.valueHash)
        assertFails { (graph.frontier("card/a").single().change.previous as MutableSet<SyncVersionRef>).add(frozen.reference()) }
        val changed = group.version(0, emptySet(), mapOf("card/a" to group.value("试图复用身份"))).copy(id = signed.id)
        assertEquals("SYNC_VERSION_ID_COLLISION", assertFailsWith<IllegalArgumentException> { graph.offer(changed) }.message)
        assertEquals(setOf(frozen.reference()), graph.heads())
        assertEquals(SyncVersionState.ADMITTED, graph.offer(frozen))
    }

    @Test fun sameDeviceSequenceForkIsExplicitAndNeverSilentlyChoosesOneBranch() {
        val group = Group(); val graph = group.history()
        val a = group.version(0, emptySet(), mapOf("card/a" to group.value("设备分支甲")))
        val b = group.version(0, emptySet(), mapOf("card/a" to group.value("设备分支乙")))
        graph.offer(a); graph.offer(b)
        assertEquals(setOf(group.devices[0].id), graph.authorForks())
        assertEquals(setOf(a.reference(), b.reference()), graph.frontier("card/a").map { it.version }.toSet())
    }
}
