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
        val recipients = listOf(owner, sender).associate { it.fingerprint to SyncDeviceEncryptionKey.create(it.fingerprint) }
        val members = listOf(owner, sender).associate { it.fingerprint to SyncGroupMember(Base64.getEncoder().encodeToString(it.certificate.encoded),
            recipients.getValue(it.fingerprint).publicKey.encoded) }
        val genesis = sign(SyncDeviceLedgerRecord(cipher.group, SyncStoreId.random(), null, 1, cipher.epoch, cipher.keyCommitment(), LanSyncScope.schemas.keys, members, emptySet(), "unsigned"))
        val ledger = SyncDeviceLedger(SyncGroupTrust(cipher.group, genesis.reference(), members.getValue(owner.fingerprint).certificate)).also { it.offer(genesis) }
        fun sign(row: SyncDeviceLedgerRecord) = row.copy(signature = Base64.getEncoder().encodeToString(owner.signSyncGroupPayload(row.signingBytes())))
        fun publisher(observe: (String) -> Unit = {}) = LanSyncGroupPublicationQueue(publications, ledger, outbox, sender.fingerprint, observe)
        val keysRoot = Files.createDirectory(root.resolve("recipient-keys"))
        fun keyQueue() = SyncKeyDeliveryQueue(keysRoot, ledger.trustAnchor, owner.fingerprint, { ledger })
        fun rootPublisher(observe: (String) -> Unit = {}) = LanSyncGroupPublicationQueue(publications, ledger, outbox, owner.fingerprint,
            observe, keyQueue = keyQueue())
        fun prepareLedger(): Pair<SyncPreparedGroupObject, Map<String, SyncStoreId>> {
            val current = ledger.current()
            val operations = current.members.filterValues { it.active }.keys.associate { recipient ->
                "${cipher.epoch.value}:$recipient" to SyncStoreId.random().also { keyQueue().prepare(it, cipher, recipient, owner::signSyncGroupPayload) }
            }
            val metadata = codec.enqueue(outbox, SyncStoreId.random(), CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()), cipher,
                SyncGroupEnvelope(2, SyncGroupObjectKind.DEVICE_LEDGER, deviceLedger = current, ledgerHistory = ledger.history().dropLast(1)))
            return metadata to operations
        }
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

    @Test fun everyActiveRecipientKeyPrecedesTheLedgerAndLostRepliesResumeForAnOfflineRecipient() = withRoot { root ->
        val f = Fixture(root); val (metadata, keys) = f.prepareLedger(); val publisher = f.rootPublisher()
        val plan = publisher.prepare(metadata, emptyList(), emptyMap(), emptyList(), keys)
        var lose = true; val observed = mutableListOf<CiphertextObjectKey>()
        val unreliable = object : CiphertextStore by f.backend {
            override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult {
                if (request.key == metadata.request.key) assertTrue(plan.keyDeliveries.values.all { f.backend.metadata(it.key)?.descriptor == it.descriptor })
                observed += request.key
                val result = f.backend.put(request, source)
                if (lose) { lose = false; throw IOException("SYNTHETIC_LOST_RECIPIENT_REPLY") }
                return result
            }
        }
        assertFailsWith<IOException> { publisher.upload(plan.metadata.operation, unreliable) }
        assertNull(f.backend.metadata(metadata.request.key)); assertNull(publisher.receipt(plan.metadata.operation))
        val reopened = f.rootPublisher(); val receipt = reopened.upload(plan.metadata.operation, unreliable)
        assertEquals(2L, receipt.recipientCount); assertEquals(0L, receipt.resourceCount)
        assertEquals(metadata.request.key, observed.last()); assertEquals(3, observed.size)
        val before = observed.size; assertEquals(receipt, reopened.upload(plan.metadata.operation, unreliable)); assertEquals(before, observed.size)
        // No root signer is needed when the previously offline recipient later consumes retained objects.
        val recipientKey = f.recipients.getValue(f.sender.fingerprint)
        val capsule = requireNotNull(f.backend.metadata(plan.keyDeliveries.getValue("${f.cipher.epoch.value}:${f.sender.fingerprint}").key))
        val tentative = SyncKeyDeliveryCodec.read(f.backend, capsule, f.ledger.trustAnchor, recipientKey)
        val read = f.codec.read(f.backend, tentative.cipher, metadata.context, metadata.request.descriptor, plan.reference)
        val recipientLedger = SyncDeviceLedger(f.ledger.trustAnchor)
        read.envelope.ledgerHistory.forEach { recipientLedger.offer(it) }; recipientLedger.offer(requireNotNull(read.envelope.deviceLedger))
        assertEquals(f.cipher.keyCommitment(), tentative.admit(recipientLedger).cipher.keyCommitment())
    }

    @Test fun missingSubstitutedRecipientPackagesAndOrdinaryAuthorsCannotPublishADeviceLedger() = withRoot { root ->
        val f = Fixture(root); val (metadata, keys) = f.prepareLedger()
        assertEquals("SYNC_GROUP_PUBLICATION_KEYS", assertFailsWith<IllegalArgumentException> {
            f.rootPublisher().prepare(metadata, emptyList(), emptyMap(), emptyList(), keys.entries.drop(1).associate { it.toPair() })
        }.message)
        assertEquals("SYNC_GROUP_PUBLICATION_KEYS", assertFailsWith<IllegalArgumentException> {
            f.rootPublisher().prepare(metadata, emptyList(), emptyMap(), emptyList(), keys.mapValues { keys.values.first() })
        }.message)
        assertEquals("SYNC_GROUP_PUBLICATION_LEDGER", assertFailsWith<IllegalArgumentException> {
            f.publisher().prepare(metadata, emptyList(), emptyMap(), emptyList(), keys)
        }.message)
        assertTrue(f.backend.listCommits().entries.isEmpty())
        val latest = f.sign(f.genesis.copy(id = SyncStoreId.random(), sequence = 2, parent = f.genesis.reference(),
            coveredTypes = f.genesis.coveredTypes + "synthetic_expansion"))
        val queue = f.rootPublisher { if (it == "ALL_RECIPIENT_KEYS_READY") f.ledger.offer(latest) }
        val plan = queue.prepare(metadata, emptyList(), emptyMap(), emptyList(), keys)
        assertEquals("SYNC_GROUP_PUBLICATION_LEDGER", assertFailsWith<IllegalArgumentException> { queue.upload(plan.metadata.operation, f.backend) }.message)
        assertNull(f.backend.metadata(metadata.request.key)); assertNull(queue.receipt(plan.metadata.operation))
        assertTrue(plan.keyDeliveries.values.all { f.backend.metadata(it.key)?.descriptor == it.descriptor })
    }

    @Test fun aNewLedgerCarriesTheCompleteSignedAncestryEvenIfIntermediatePublicationNeverHappened() = withRoot { root ->
        val f = Fixture(root)
        val middle = f.sign(f.genesis.copy(id = SyncStoreId.random(), sequence = 2, parent = f.genesis.reference()))
        val latest = f.sign(middle.copy(id = SyncStoreId.random(), sequence = 3, parent = middle.reference(),
            coveredTypes = middle.coveredTypes + "synthetic_expansion"))
        f.ledger.offer(middle); f.ledger.offer(latest)
        val (metadata, keys) = f.prepareLedger(); val queue = f.rootPublisher()
        val missingHistory = f.codec.enqueue(f.outbox, SyncStoreId.random(), CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()),
            f.cipher, metadata.envelope.copy(ledgerHistory = emptyList()))
        assertEquals("SYNC_GROUP_PUBLICATION_LEDGER", assertFailsWith<IllegalArgumentException> {
            queue.prepare(missingHistory, emptyList(), emptyMap(), emptyList(), keys)
        }.message)
        val plan = queue.prepare(metadata, emptyList(), emptyMap(), emptyList(), keys); queue.upload(plan.metadata.operation, f.backend)
        val capsule = requireNotNull(f.backend.metadata(plan.keyDeliveries.getValue("${f.cipher.epoch.value}:${f.sender.fingerprint}").key))
        val tentative = SyncKeyDeliveryCodec.read(f.backend, capsule, f.ledger.trustAnchor, f.recipients.getValue(f.sender.fingerprint))
        val read = f.codec.read(f.backend, tentative.cipher, metadata.context, metadata.request.descriptor)
        val fresh = SyncDeviceLedger(f.ledger.trustAnchor)
        assertEquals(SyncVersionState.WAITING_FOR_PARENTS, fresh.offer(requireNotNull(read.envelope.deviceLedger)))
        read.envelope.ledgerHistory.asReversed().forEach { fresh.offer(it) }
        assertEquals(latest.reference(), fresh.current().reference())
        assertEquals(f.cipher.keyCommitment(), tentative.admit(fresh).cipher.keyCommitment())
        assertEquals(listOf(f.genesis.reference(), middle.reference(), latest.reference()), fresh.history().map { it.reference() })
    }

    @Test fun newlyAdmittedDevicesReceiveHistoricalKeysAndRevokedDevicesReceiveNoEpochs() = withRoot { root ->
        val f = Fixture(root); val newPhone = LanSyncTlsIdentity.create(); val recipient = SyncDeviceEncryptionKey.create(newPhone.fingerprint)
        val currentKey = SyncStreamingCipher.create(f.cipher.group, SyncStoreId.random())
        val latest = f.sign(f.genesis.copy(id = SyncStoreId.random(), sequence = 2, parent = f.genesis.reference(),
            epoch = currentKey.epoch, keyCommitment = currentKey.keyCommitment(), members = f.members +
                (f.sender.fingerprint to f.members.getValue(f.sender.fingerprint).copy(active = false)) +
                (newPhone.fingerprint to SyncGroupMember(Base64.getEncoder().encodeToString(newPhone.certificate.encoded), recipient.publicKey.encoded))))
        f.ledger.offer(latest)
        val keys = listOf(f.cipher, currentKey).flatMap { cipher -> latest.members.filterValues { it.active }.keys.map { device ->
            "${cipher.epoch.value}:$device" to SyncStoreId.random().also { f.keyQueue().prepare(it, cipher, device, f.owner::signSyncGroupPayload) }
        } }.toMap()
        assertTrue(keys.keys.none { it.endsWith(":" + f.sender.fingerprint) })
        val outbox = SyncEncryptedOutbox(Files.createDirectory(root.resolve("new-epoch-queue")), currentKey)
        val metadata = f.codec.enqueue(outbox, SyncStoreId.random(), CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()), currentKey,
            SyncGroupEnvelope(2, SyncGroupObjectKind.DEVICE_LEDGER, deviceLedger = latest, ledgerHistory = f.ledger.history().dropLast(1)))
        val publisher = LanSyncGroupPublicationQueue(f.publications, f.ledger, outbox, f.owner.fingerprint, keyQueue = f.keyQueue())
        assertEquals("SYNC_GROUP_PUBLICATION_KEYS", assertFailsWith<IllegalArgumentException> {
            publisher.prepare(metadata, emptyList(), emptyMap(), emptyList(), keys.filterKeys { it.startsWith(currentKey.epoch.value) })
        }.message)
        val plan = publisher.prepare(metadata, emptyList(), emptyMap(), emptyList(), keys)
        val receipt = publisher.upload(plan.metadata.operation, f.backend)
        assertEquals(2L, receipt.recipientCount); assertEquals(4L, receipt.keyDeliveryCount)
        val currentCapsule = requireNotNull(f.backend.metadata(plan.keyDeliveries.getValue("${currentKey.epoch.value}:${newPhone.fingerprint}").key))
        val tentative = SyncKeyDeliveryCodec.read(f.backend, currentCapsule, f.ledger.trustAnchor, recipient)
        val read = f.codec.read(f.backend, tentative.cipher, metadata.context, metadata.request.descriptor)
        val fresh = SyncDeviceLedger(f.ledger.trustAnchor)
        read.envelope.ledgerHistory.forEach { fresh.offer(it) }; fresh.offer(requireNotNull(read.envelope.deviceLedger))
        assertEquals(currentKey.keyCommitment(), tentative.admit(fresh).cipher.keyCommitment())
        val oldCapsule = requireNotNull(f.backend.metadata(plan.keyDeliveries.getValue("${f.cipher.epoch.value}:${newPhone.fingerprint}").key))
        assertEquals(f.cipher.keyCommitment(), SyncKeyDeliveryCodec.read(f.backend, oldCapsule, f.ledger.trustAnchor, recipient).admit(fresh).cipher.keyCommitment())
    }
}
