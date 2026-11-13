package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.syncStore.*
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

class LanSyncGroupPublicationQueueTest {
    private fun withRoot(work: (Path) -> Unit) {
        val root = Files.createTempDirectory("group-native-publication-").toRealPath()
        try { work(root) } finally { check(root.toFile().deleteRecursively()) }
    }
    private data class Prepared(val metadata: SyncPreparedGroupObject, val unit: LanSyncDecodedGroupUnit,
        val operations: Map<String, SyncStoreId>)
    private class Fixture(val root: Path) {
        val owner = LanSyncTlsIdentity.create(); val sender = LanSyncTlsIdentity.create(); val cipher = SyncStreamingCipher.create()
        val data = Files.createDirectory(root.resolve("data")); val state = Files.createDirectory(root.resolve("state"))
        val work = Files.createDirectory(root.resolve("work")); val unitRoot = Files.createDirectory(root.resolve("units"))
        val queue = Files.createDirectory(root.resolve("queue")); val publications = Files.createDirectory(root.resolve("publications"))
        val outbox = SyncEncryptedOutbox(queue, cipher); val backend = FileCiphertextStore(root.resolve("backend"))
        val blobs = SyncGroupBlobStore(cipher.group, work); val codec = SyncGroupObjectCodec(work)
        val members = listOf(owner, sender).associate { it.fingerprint to SyncGroupMember(Base64.getEncoder().encodeToString(it.certificate.encoded),
            SyncDeviceEncryptionKey.create(it.fingerprint).publicKey.encoded) }
        val genesis = sign(SyncDeviceLedgerRecord(cipher.group, SyncStoreId.random(), null, 1, cipher.epoch, cipher.keyCommitment(), LanSyncScope.schemas.keys, members, emptySet(), "unsigned"))
        val ledger = SyncDeviceLedger(SyncGroupTrust(cipher.group, genesis.reference(), members.getValue(owner.fingerprint).certificate)).also { it.offer(genesis) }
        fun sign(row: SyncDeviceLedgerRecord) = row.copy(signature = Base64.getEncoder().encodeToString(owner.signSyncGroupPayload(row.signingBytes())))
        fun publisher(observe: (String) -> Unit = {}) = LanSyncGroupPublicationQueue(publications, ledger, outbox, sender.fingerprint, observe)
        fun prepare(): Prepared {
            val image = data.resolve("images/image.png"); Files.createDirectories(image.parent); Files.write(image, LanSyncPreviewSample.image())
            val card = data.resolve("entities/character_cards/card.json"); Files.createDirectories(card.parent)
            LanSyncJsonFiles.write(card, CharacterCard.serializer(), CharacterCard("card", "合成角色", avatar = image.toString(), createdAt = 1, updatedAt = 1))
            val workspace = LanSyncWorkspace(data, state, { Path.of(it) })
            return workspace.capture(sender.fingerprint).use { capture -> LanSyncGroupUnitCodec(unitRoot).prepare(capture, "character_cards/card").use { unit ->
                val bindings = linkedMapOf<String, SyncEnvelopeBlobBinding>(); val operations = linkedMapOf<String, SyncStoreId>()
                fun prepareBlob(path: Path, identity: SyncPlaintextIdentity) {
                    val operation = SyncStoreId.random(); val key = CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random())
                    bindings[identity.sha256] = blobs.enqueue(outbox, operation, key, cipher, path, identity); operations[identity.sha256] = operation
                }
                prepareBlob(unit.path, unit.decoded.plaintext)
                unit.decoded.dependencies.forEach { prepareBlob(requireNotNull(capture.blobs[it.sha256]), it) }
                val version = SyncCausalVersion(cipher.group, SyncStoreId.random(), genesis.reference(), sender.fingerprint, 1, null, emptySet(),
                    mapOf(unit.decoded.payload.unit to SyncUnitChange(unit.decoded.valueHash, unit.decoded.plaintext, emptySet())), "unsigned").let {
                    it.copy(signature = Base64.getEncoder().encodeToString(sender.signSyncGroupPayload(it.signingBytes())))
                }
                val metadata = codec.enqueue(outbox, SyncStoreId.random(), CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()), cipher,
                    SyncGroupEnvelope(2, SyncGroupObjectKind.VERSION, version = version, blobs = bindings))
                Prepared(metadata, unit.decoded, operations)
            } }
        }
    }

    @Test fun allActualNativeDependenciesPrecedeMetadataAndLostResponsesResumeWithoutOriginalSources() = withRoot { root ->
        val f = Fixture(root); val prepared = f.prepare(); val publisher = f.publisher()
        val plan = publisher.prepare(prepared.metadata, listOf(prepared.unit), prepared.operations, emptyList())
        val puts = mutableListOf<CiphertextObjectKind>(); var lost = true
        val unreliable = object : CiphertextStore by f.backend {
            override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult {
                puts += request.key.kind
                if (request.key.kind == CiphertextObjectKind.COMMIT) assertTrue(plan.blobs.values.all { f.backend.metadata(it.key)?.descriptor == it.descriptor })
                val result = f.backend.put(request, source)
                if (lost) { lost = false; throw IOException("synthetic lost object response") }
                return result
            }
        }
        assertFailsWith<IOException> { publisher.upload(plan.metadata.operation, unreliable) }
        assertNull(publisher.receipt(plan.metadata.operation)); assertNull(f.backend.metadata(plan.metadata.key))
        check(f.data.toFile().deleteRecursively())
        val reopened = f.publisher(); val receipt = reopened.upload(plan.metadata.operation, unreliable)
        assertEquals(2L, receipt.resourceCount); assertEquals(CiphertextObjectKind.COMMIT, puts.last())
        assertEquals(listOf(CiphertextObjectKind.BLOB, CiphertextObjectKind.BLOB, CiphertextObjectKind.COMMIT), puts)
        assertEquals(plan.metadata.descriptor, f.backend.metadata(plan.metadata.key)?.descriptor)
        val count = puts.size; assertEquals(receipt, reopened.upload(plan.metadata.operation, unreliable)); assertEquals(count, puts.size)
        val read = f.codec.read(f.backend, f.cipher, prepared.metadata.context, prepared.metadata.request.descriptor, plan.reference)
        assertEquals(prepared.metadata.envelope, read.envelope)
    }

    @Test fun missingOrSubstitutedDependenciesNeverCreateAPublicationPlan() = withRoot { root ->
        val f = Fixture(root); val prepared = f.prepare(); val publisher = f.publisher()
        assertEquals("SYNC_GROUP_PUBLICATION_UNIT", assertFailsWith<IllegalArgumentException> {
            publisher.prepare(prepared.metadata, emptyList(), prepared.operations, emptyList())
        }.message)
        assertEquals("SYNC_GROUP_PUBLICATION_DEPENDENCY", assertFailsWith<IllegalArgumentException> {
            publisher.prepare(prepared.metadata, listOf(prepared.unit), prepared.operations.entries.drop(1).associate { it.toPair() }, emptyList())
        }.message)
        val wrong = prepared.operations.keys.associateWith { prepared.operations.values.first() }
        assertEquals("SYNC_GROUP_PUBLICATION_BLOB_CHANGED", assertFailsWith<IllegalArgumentException> {
            publisher.prepare(prepared.metadata, listOf(prepared.unit), wrong, emptyList())
        }.message)
        assertNull(publisher.receipt(prepared.metadata.request.operationId)); assertNull(f.backend.metadata(prepared.metadata.request.key))
    }

    @Test fun learningANewerKeyBetweenResourcesAndMetadataBlocksTheOldCommitWithoutDiscardingItsPlan() = withRoot { root ->
        val f = Fixture(root); val prepared = f.prepare(); var rotated = false
        val nextKey = SyncStreamingCipher.create(f.cipher.group)
        val next = f.sign(f.genesis.copy(id = SyncStoreId.random(), parent = f.genesis.reference(), sequence = 2,
            epoch = nextKey.epoch, keyCommitment = nextKey.keyCommitment()))
        val publisher = f.publisher { if (it == "ALL_BLOBS_READY" && !rotated) { rotated = true; f.ledger.offer(next) } }
        val plan = publisher.prepare(prepared.metadata, listOf(prepared.unit), prepared.operations, emptyList())
        assertEquals("SYNC_GROUP_PUBLICATION_EPOCH_CHANGED", assertFailsWith<IllegalArgumentException> { publisher.upload(plan.metadata.operation, f.backend) }.message)
        assertTrue(rotated); assertNull(f.backend.metadata(plan.metadata.key)); assertNull(publisher.receipt(plan.metadata.operation))
        assertTrue(Files.exists(f.publications.resolve("publication-${plan.metadata.operation.value}.json")))
        assertTrue(plan.blobs.values.all { f.backend.metadata(it.key)?.descriptor == it.descriptor })
    }

    @Test fun lostCompletionAfterMetadataPublicationRetriesByObservationAndDamagedReceiptRefuses() = withRoot { root ->
        val f = Fixture(root); val prepared = f.prepare()
        val publisher = f.publisher { if (it == "METADATA_READY") throw IOException("synthetic publication receipt loss") }
        val plan = publisher.prepare(prepared.metadata, listOf(prepared.unit), prepared.operations, emptyList())
        assertFailsWith<IOException> { publisher.upload(plan.metadata.operation, f.backend) }
        assertEquals(plan.metadata.descriptor, f.backend.metadata(plan.metadata.key)?.descriptor); assertNull(publisher.receipt(plan.metadata.operation))
        val recovered = f.publisher(); val receipt = recovered.upload(plan.metadata.operation, f.backend)
        assertEquals(receipt, recovered.receipt(plan.metadata.operation))
        val path = f.publications.resolve("publication-receipt-${plan.metadata.operation.value}.json")
        LanSyncJsonFiles.write(path, LanSyncGroupPublishReceipt.serializer(), receipt.copy(resourceCount = receipt.resourceCount + 1))
        assertEquals("SYNC_GROUP_PUBLICATION_RECEIPT_CHANGED", assertFailsWith<IllegalArgumentException> { recovered.receipt(plan.metadata.operation) }.message)
    }

    @Test fun changedPlanAndLocalDeviceRevocationBothRefuseBeforeAnyBackendWrite() = withRoot { root ->
        val f = Fixture(root); val prepared = f.prepare(); val publisher = f.publisher()
        val plan = publisher.prepare(prepared.metadata, listOf(prepared.unit), prepared.operations, emptyList())
        val path = f.publications.resolve("publication-${plan.metadata.operation.value}.json")
        LanSyncJsonFiles.write(path, LanSyncGroupPublishPlan.serializer(), plan.copy(blobs = emptyMap()))
        assertEquals("SYNC_GROUP_PUBLICATION_PLAN_SEAL", assertFailsWith<IllegalArgumentException> { publisher.upload(plan.metadata.operation, f.backend) }.message)
        assertTrue(plan.blobs.values.all { f.backend.metadata(it.key) == null }); assertNull(f.backend.metadata(plan.metadata.key))
        val otherRoot = Files.createDirectory(root.resolve("revoked-fixture")); val other = Fixture(otherRoot)
        val second = other.prepare(); val queue = other.publisher()
        val pending = queue.prepare(second.metadata, listOf(second.unit), second.operations, emptyList())
        val key = SyncStreamingCipher.create(other.cipher.group)
        val revoked = other.sign(other.genesis.copy(id = SyncStoreId.random(), parent = other.genesis.reference(), sequence = 2,
            epoch = key.epoch, keyCommitment = key.keyCommitment(), members = other.members +
                (other.sender.fingerprint to other.members.getValue(other.sender.fingerprint).copy(active = false))))
        other.ledger.offer(revoked)
        assertEquals("SYNC_GROUP_PUBLICATION_DEVICE_REVOKED", assertFailsWith<IllegalArgumentException> { queue.upload(pending.metadata.operation, other.backend) }.message)
        assertTrue(pending.blobs.values.all { other.backend.metadata(it.key) == null }); assertNull(other.backend.metadata(pending.metadata.key))
    }
}
