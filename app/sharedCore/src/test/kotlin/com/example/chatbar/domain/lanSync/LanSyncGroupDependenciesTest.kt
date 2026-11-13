package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import kotlin.test.*

class LanSyncGroupDependenciesTest {
    private fun withRoot(work: (Path) -> Unit) {
        val root = Files.createTempDirectory("synthetic-group-dependencies-").toRealPath()
        try { work(root) } finally { check(root.toFile().deleteRecursively()) }
    }
    private class Fixture(root: Path) {
        val source = LanSyncGroupPublicationQueueTest.Fixture(Files.createDirectory(root.resolve("source")))
        val prepared = source.prepare()
        val target = Files.createDirectory(root.resolve("target")); val state = Files.createDirectory(root.resolve("target-state"))
        val workspace = LanSyncWorkspace(target, state, { Path.of(it) })
        val custodyRoot = Files.createDirectory(root.resolve("group-custody"))
        val recipient = source.recipients.getValue(source.owner.fingerprint)
        fun custody() = SyncGroupCustody(custodyRoot, source.cipher.group, recipient, { it.reversedArray() }, { it.reversedArray() })
        init {
            val ledger = custody().enroll(source.ledger.trustAnchor, source.ledger.history())
            val capsule = SyncGroupKeyDelivery.create(source.ledger, source.cipher, recipient.device, SyncStoreId.random(), source.owner::signSyncGroupPayload)
            custody().rememberKey(capsule.openTentative(source.ledger.trustAnchor, recipient,
                CiphertextObjectKey(CiphertextObjectKind.COMMIT, capsule.context.objectId)).admit(ledger))
        }
        fun received(metadata: SyncPreparedGroupObject = prepared.metadata): Pair<SyncInboxReconciliation, SyncAuthenticatedGroupObject> {
            source.outbox.upload(metadata.request.operationId, source.backend)
            val actual = source.codec.read(source.backend, custody().key(source.cipher.epoch), metadata.context, metadata.request.descriptor)
            val version = requireNotNull(actual.envelope.version)
            val history = SyncCausalHistory(source.cipher.group, custody().ledger().admission(listOf(version), listOf(actual)))
            assertEquals(SyncVersionState.ADMITTED, history.offer(version))
            return SyncInboxReconciliation(emptyList(), history, listOf(actual), false, custody().ledger().current().reference()) to actual
        }
        fun service(backend: CiphertextStore = source.backend, validator: (String, Path, LanSyncRecord) -> Unit = { _, _, _ -> }) =
            LanSyncGroupDependencies(source.cipher.group, backend, custody(), source.work, workspace.resourceStore, validator)
    }

    @Test fun missingImageWaitsThenVerifiedUnitAppliesAndReopensWithoutRedownloadingCachedContent() = withRoot { root ->
        val f = Fixture(root); val source = f.source
        source.outbox.upload(f.prepared.operations.getValue(f.prepared.unit.plaintext.sha256), source.backend)
        val (reconciled, metadata) = f.received(); val unit = f.prepared.unit.payload.unit
        val before = f.service().inspect(reconciled, metadata, unit)
        assertEquals(LanSyncGroupDependencyStage.WAITING_FOR_RESOURCE, before.stage); assertNull(before.available)
        val image = f.prepared.unit.dependencies.single()
        source.outbox.upload(f.prepared.operations.getValue(image.sha256), source.backend)
        var validations = 0
        val ready = f.service { kind, path, owner ->
            assertEquals("images", kind); assertEquals("character_cards/card", owner.key)
            assertContentEquals(LanSyncPreviewSample.image(), Files.readAllBytes(path)); validations++
        }.inspect(reconciled, metadata, unit)
        assertEquals(LanSyncGroupDependencyStage.UNIT_CONTENT_AVAILABLE, ready.stage); assertTrue(validations > 0)
        val available = requireNotNull(ready.available)
        val snapshot = LanSyncSnapshot(deviceId = source.owner.fingerprint, records = available.decoded.payload.records,
            resources = available.decoded.payload.resources, coveredTypes = available.decoded.payload.coveredTypes)
        f.workspace.capture(source.owner.fingerprint).use { current -> f.workspace.apply(LanSyncPlan(UUID.randomUUID().toString(), snapshot, emptyMap()),
            current.snapshot.hashes(), source.owner.fingerprint, available.resources) }
        LanSyncWorkspace(f.target, f.state, { Path.of(it) }).capture(source.owner.fingerprint).use { reopened ->
            assertEquals(mapOf(unit to f.prepared.unit.valueHash), reopened.snapshot.hashes())
            assertEquals(mapOf(image.sha256 to image.length), reopened.snapshot.resources)
        }
        val unavailable = object : CiphertextStore by source.backend {
            override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? =
                throw IOException("SYNTHETIC_CACHED_CONTENT_MUST_NOT_DOWNLOAD")
        }
        assertEquals(LanSyncGroupDependencyStage.UNIT_CONTENT_AVAILABLE, f.service(unavailable).inspect(reconciled, metadata, unit).stage)
        Files.write(available.resources.getValue(image.sha256), byteArrayOf(9, 8, 7))
        val damaged = f.service().inspect(reconciled, metadata, unit)
        assertEquals(LanSyncGroupDependencyStage.INVALID_CONTENT, damaged.stage); assertNull(damaged.available)
    }

    @Test fun missingBindingsWrongNativeBusinessHashAndChangedCheckpointCannotProduceAvailableContent() = withRoot { root ->
        val f = Fixture(root); val source = f.source; val unit = f.prepared.unit.payload.unit
        f.prepared.operations.values.forEach { source.outbox.upload(it, source.backend) }
        val missing = source.codec.enqueue(source.outbox, SyncStoreId.random(), CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()),
            source.cipher, f.prepared.metadata.envelope.copy(blobs = emptyMap()))
        val (reconciled, metadata) = f.received(missing)
        assertEquals("SYNC_GROUP_DEPENDENCY_BINDING_MISSING", f.service().inspect(reconciled, metadata, unit).code)
        val version = requireNotNull(f.prepared.metadata.envelope.version)
        val wrongVersion = version.copy(changes = version.changes.mapValues { it.value.copy(valueHash = "0".repeat(64)) }, signature = "unsigned").let {
            it.copy(signature = Base64.getEncoder().encodeToString(source.sender.signSyncGroupPayload(it.signingBytes())))
        }
        val wrong = source.codec.enqueue(source.outbox, SyncStoreId.random(), CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()),
            source.cipher, f.prepared.metadata.envelope.copy(version = wrongVersion))
        val (wrongReconciliation, wrongMetadata) = f.received(wrong)
        assertEquals("SYNC_GROUP_UNIT_BINDING", f.service().inspect(wrongReconciliation, wrongMetadata, unit).code)
        val (validReconciliation, valid) = f.received()
        val newer = source.sign(source.genesis.copy(id = SyncStoreId.random(), sequence = 2, parent = source.genesis.reference()))
        f.custody().update(listOf(newer))
        assertEquals(LanSyncGroupDependencyStage.RECONCILE_AGAIN, f.service().inspect(validReconciliation, valid, unit).stage)
        f.workspace.capture(source.owner.fingerprint).use { assertTrue(it.snapshot.records.isEmpty()) }
    }

    @Test fun deletionEvidenceRequiresReviewAndAnAuthorForkCannotExposeAnAutomaticallySelectedUnit() = withRoot { root ->
        val f = Fixture(root); val source = f.source; val unit = f.prepared.unit.payload.unit
        val (_, parentObject) = f.received(); val parent = requireNotNull(parentObject.envelope.version)
        fun received(version: SyncCausalVersion): SyncAuthenticatedGroupObject {
            val signed = version.copy(signature = Base64.getEncoder().encodeToString(source.sender.signSyncGroupPayload(version.signingBytes())))
            val prepared = source.codec.enqueue(source.outbox, SyncStoreId.random(), CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()),
                source.cipher, f.prepared.metadata.envelope.copy(version = signed))
            source.outbox.upload(prepared.request.operationId, source.backend)
            return source.codec.read(source.backend, source.cipher, prepared.context, prepared.request.descriptor)
        }
        fun reconciled(other: SyncAuthenticatedGroupObject): SyncInboxReconciliation {
            val witnesses = listOf(parentObject, other); val versions = witnesses.map { requireNotNull(it.envelope.version) }
            val history = SyncCausalHistory(source.cipher.group, f.custody().ledger().admission(versions, witnesses))
            versions.forEach { assertEquals(SyncVersionState.ADMITTED, history.offer(it)) }
            return SyncInboxReconciliation(emptyList(), history, witnesses, false, source.genesis.reference())
        }
        val deletion = received(parent.copy(id = SyncStoreId.random(), authorSequence = 2, previousByAuthor = parent.reference(),
            parents = setOf(parent.reference()), changes = mapOf(unit to SyncUnitChange(null, null, setOf(parent.reference()))), signature = "unsigned"))
        val review = f.service().inspect(reconciled(deletion), deletion, unit)
        assertEquals(LanSyncGroupDependencyStage.DELETION_REVIEW_REQUIRED, review.stage); assertNull(review.available)
        val fork = received(parent.copy(id = SyncStoreId.random(), signature = "unsigned"))
        val conflict = f.service().inspect(reconciled(fork), parentObject, unit)
        assertEquals(LanSyncGroupDependencyStage.AUTHOR_FORK, conflict.stage); assertNull(conflict.available)
        f.workspace.capture(source.owner.fingerprint).use { assertTrue(it.snapshot.records.isEmpty()) }
    }
}
