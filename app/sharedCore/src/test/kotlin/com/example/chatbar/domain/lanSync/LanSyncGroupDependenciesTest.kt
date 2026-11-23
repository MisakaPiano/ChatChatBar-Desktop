package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class LanSyncGroupDependenciesTest {
    private fun withRoot(work: (Path) -> Unit) {
        val root = Files.createTempDirectory("synthetic-group-dependencies-").toRealPath()
        try { work(root) } finally { check(root.toFile().deleteRecursively()) }
    }
    private class Fixture(root: Path, imageBytes: ByteArray = LanSyncPreviewSample.image()) {
        val source = LanSyncGroupPublicationQueueTest.Fixture(Files.createDirectory(root.resolve("source")))
        val prepared = source.prepare(imageBytes)
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
        fun service(backend: CiphertextStore = source.backend, budget: LanSyncMetadataBudget = LanSyncMetadataBudget(),
            validator: (String, Path, LanSyncRecord) -> Unit = { _, _, _ -> }) =
            LanSyncGroupDependencies(source.cipher.group, backend, custody(), source.work, workspace.resourceStore, validator, budget)

        fun recordEvidence(reconciled: SyncInboxReconciliation, metadata: SyncAuthenticatedGroupObject): LanSyncGroupRecordEvidence =
            LanSyncGroupRecordEvidence(requireNotNull(reconciled.history), requireNotNull(reconciled.ledgerCheckpoint)).also { evidence ->
                service().membership(reconciled, metadata, prepared.unit.payload.unit).membership?.let(evidence::offer)
            }

        fun plan(reconciled: SyncInboxReconciliation, metadata: SyncAuthenticatedGroupObject, result: LanSyncGroupDependencyResult) =
            workspace.capture(source.owner.fingerprint).use { capture ->
                val request = LanSyncGroupMembershipRequest(requireNotNull(metadata.envelope.version).reference(), prepared.unit.payload.unit)
                LanSyncGroupNativePlanner.prepare(capture, reconciled, emptyMap(), mapOf(request to result), recordEvidence(reconciled, metadata))
            }
    }

    @Test fun missingImageWaitsThenVerifiedUnitAppliesAndReopensWithoutRedownloadingCachedContent() = withRoot { root ->
        val f = Fixture(root); val source = f.source
        source.outbox.upload(f.prepared.operations.getValue(f.prepared.unit.plaintext.sha256), source.backend)
        source.outbox.upload(f.prepared.operations.getValue(f.prepared.unit.membership.sha256), source.backend)
        val (reconciled, metadata) = f.received(); val unit = f.prepared.unit.payload.unit
        val reference = requireNotNull(metadata.envelope.version).reference()
        val before = f.service().inspect(reconciled, metadata, unit)
        assertEquals(LanSyncGroupDependencyStage.WAITING_FOR_RESOURCE, before.stage); assertNull(before.available)
        val waitingPlan = f.plan(reconciled, metadata, before)
        val frozenWaiting = waitingPlan.units.getValue(unit)
        assertTrue(waitingPlan.advances.isEmpty())
        assertEquals("等待正文或附件", LanSyncGroupDisplay.nativeStageLabel(frozenWaiting))
        assertContains(requireNotNull(LanSyncGroupDisplay.versionDependencyNotice(frozenWaiting, reference)), "连接持有该版本的设备")
        val image = f.prepared.unit.dependencies.single()
        source.outbox.upload(f.prepared.operations.getValue(image.sha256), source.backend)
        var validations = 0
        val ready = f.service { kind, path, owner ->
            assertEquals("images", kind); assertEquals("character_cards/card", owner.key)
            LanSyncRasterPolicy.checkPng(path, staticPngRequired = false)
            assertContentEquals(LanSyncPreviewSample.image(), Files.readAllBytes(path)); validations++
        }.inspect(reconciled, metadata, unit)
        assertEquals(LanSyncGroupDependencyStage.UNIT_CONTENT_AVAILABLE, ready.stage); assertTrue(validations > 0)
        val readyPlan = f.plan(reconciled, metadata, ready)
        assertEquals(setOf(unit), readyPlan.advances.keys)
        val frozenReady = readyPlan.units.getValue(unit)
        assertEquals("传入内容已就绪", LanSyncGroupDisplay.nativeStageLabel(frozenReady))
        assertNull(LanSyncGroupDisplay.versionDependencyNotice(frozenReady, reference))
        assertEquals("等待正文或附件", LanSyncGroupDisplay.nativeStageLabel(frozenWaiting))
        val available = requireNotNull(ready.available)
        // Test the incoming-cache diagnosis while this receiver has no local copy of the same image.
        val cachedImage = available.resources.getValue(image.sha256)
        try {
            Files.write(cachedImage, byteArrayOf(9, 8, 7))
            val damaged = f.service().inspect(reconciled, metadata, unit)
            assertEquals(LanSyncGroupDependencyStage.INVALID_CONTENT, damaged.stage)
            assertNull(damaged.available)
            assertNull(damaged.rejectedPart)
            val unavailableRow = f.plan(reconciled, metadata, damaged).units.getValue(unit)
            assertEquals("此版本未能通过资料检查", LanSyncGroupDisplay.nativeStageLabel(unavailableRow))
            assertContains(requireNotNull(LanSyncGroupDisplay.versionDependencyNotice(unavailableRow, reference)), "本机存储")
        } finally {
            Files.write(cachedImage, LanSyncPreviewSample.image())
        }
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
    }

    @Test fun authenticatedInvalidAttachmentKeepsItsFrozenReasonAndCanBeLeftForLater() = withRoot { root ->
        val damaged = LanSyncPreviewSample.image().copyOf(12)
        val f = Fixture(root, damaged)
        val source = f.source
        val unit = f.prepared.unit.payload.unit
        f.prepared.operations.values.forEach { source.outbox.upload(it, source.backend) }
        val (reconciled, metadata) = f.received()
        val reference = requireNotNull(metadata.envelope.version).reference()
        val result = f.service { kind, path, _ ->
            assertEquals("images", kind)
            LanSyncRasterPolicy.checkPng(path, staticPngRequired = false)
        }.inspect(reconciled, metadata, unit)
        assertEquals(LanSyncGroupDependencyStage.INVALID_CONTENT, result.stage)
        assertEquals(LanSyncGroupDependencyPart.ATTACHMENT, result.rejectedPart)
        assertNull(result.available)
        val plan = f.plan(reconciled, metadata, result)
        val row = plan.units.getValue(unit)
        assertTrue(plan.advances.isEmpty())
        assertEquals("已取得的附件未通过验证", LanSyncGroupDisplay.nativeStageLabel(row))
        assertTrue(LanSyncGroupDisplay.componentHasRejectedVersion(plan, unit))
        val notice = requireNotNull(LanSyncGroupDisplay.versionDependencyNotice(row, reference))
        assertContains(notice, "来源设备核对并保留原件")
        assertContains(notice, "SYNC_IMAGE_INVALID")

        f.workspace.capture(source.owner.fingerprint).use { capture ->
            val evidence = f.recordEvidence(reconciled, metadata)
            val dependencies = mapOf(LanSyncGroupMembershipRequest(reference, unit) to result)
            val later = LanSyncGroupResolution.prepare(plan, capture, reconciled, dependencies, evidence,
                mapOf(unit to LanSyncGroupResolutionChoice.Later), UUID.randomUUID().toString())
            assertEquals(setOf(unit), later.heldUnits)
            assertTrue(later.resolvedUnits.isEmpty())
            val choices = listOf(LanSyncGroupResolutionSource.Local, LanSyncGroupResolutionSource.Version(reference))
            for (choice in choices) {
                assertEquals("SYNC_GROUP_RESOLUTION_BODY_MISSING", assertFailsWith<IllegalArgumentException> {
                    LanSyncGroupResolution.prepare(plan, capture, reconciled, dependencies, evidence,
                        mapOf(unit to LanSyncGroupResolutionChoice.Keep(choice)), UUID.randomUUID().toString())
                }.message)
            }
            assertTrue(capture.snapshot.records.isEmpty())
        }
        val image = f.prepared.unit.dependencies.single()
        assertContentEquals(damaged, Files.readAllBytes(requireNotNull(f.workspace.resourceStore.completed(image.sha256, image.length))))
        assertContentEquals(damaged, Files.readAllBytes(source.data.resolve("images/image.png")))
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

    @Test fun authenticatedRemoteRecordShapeUtf8AndBudgetsAreCheckedBeforeContentIsAvailable() = withRoot { root ->
        val f = Fixture(root)
        val source = f.source
        val original = f.prepared.metadata.envelope
        val version = requireNotNull(original.version)
        val unit = f.prepared.unit.payload.unit
        val change = version.changes.getValue(unit)
        val originalContent = requireNotNull(change.content)
        f.prepared.operations.values.forEach { source.outbox.upload(it, source.backend) }

        fun receivedWithContent(bytes: ByteArray): Pair<SyncInboxReconciliation, SyncAuthenticatedGroupObject> {
            val path = Files.createTempFile(root, "signed-unit-", ".json")
            Files.write(path, bytes)
            val identity = SyncPlaintextIdentity(bytes.size.toLong(), lanSyncHash(bytes))
            val operation = SyncStoreId.random()
            val key = CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random())
            val binding = source.blobs.enqueue(source.outbox, operation, key, source.cipher, path, identity)
            source.outbox.upload(operation, source.backend)
            val unsigned = version.copy(id = SyncStoreId.random(),
                changes = mapOf(unit to change.copy(content = identity)), signature = "unsigned")
            val signed = unsigned.copy(signature = Base64.getEncoder()
                .encodeToString(source.sender.signSyncGroupPayload(unsigned.signingBytes())))
            val prepared = source.codec.enqueue(source.outbox, SyncStoreId.random(),
                CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()), source.cipher,
                original.copy(version = signed, blobs = original.blobs - originalContent.sha256 + (identity.sha256 to binding)))
            return f.received(prepared)
        }

        fun assertRejected(result: LanSyncGroupDependencyResult, code: String) {
            assertEquals(code, result.code)
            assertEquals(LanSyncGroupDependencyStage.INVALID_CONTENT, result.stage)
            assertNull(result.available)
            assertNull(result.membership)
        }

        val payload = lanSyncJson.encodeToJsonElement(LanSyncGroupUnitPayload.serializer(), f.prepared.unit.payload).jsonObject
        val record = payload.getValue("records").jsonArray.single().jsonObject
        val invalidRecord = JsonObject(record + ("body" to JsonArray(emptyList())))
        val invalidPayload = JsonObject(payload + ("records" to JsonArray(listOf(invalidRecord))))
        val (badShape, badShapeObject) = receivedWithContent(invalidPayload.toString().encodeToByteArray())
        val rejectedBody = f.service().inspect(badShape, badShapeObject, unit)
        assertRejected(rejectedBody, "SYNC_METADATA_STRUCTURE")
        assertEquals(LanSyncGroupDependencyPart.BODY, rejectedBody.rejectedPart)
        val badShapeRow = f.plan(badShape, badShapeObject, rejectedBody).units.getValue(unit)
        assertEquals("已取得的正文未通过验证", LanSyncGroupDisplay.nativeStageLabel(badShapeRow))
        assertContains(requireNotNull(LanSyncGroupDisplay.versionDependencyNotice(badShapeRow,
            requireNotNull(badShapeObject.envelope.version).reference())), "SYNC_METADATA_STRUCTURE")

        val badUtf8 = payload.toString().encodeToByteArray()
        val startOfNonAsciiText = badUtf8.indexOfFirst { it < 0 }
        assertTrue(startOfNonAsciiText >= 0)
        badUtf8[startOfNonAsciiText] = 0xff.toByte()
        val (badEncoding, badEncodingObject) = receivedWithContent(badUtf8)
        assertRejected(f.service().inspect(badEncoding, badEncodingObject, unit), "SYNC_INVALID_UTF8")

        val (reconciled, metadata) = f.received()
        val smallBodyWindow = LanSyncMetadataBudget(maximumDecodedCost = 1)
        assertRejected(f.service(budget = smallBodyWindow).inspect(reconciled, metadata, unit), "SYNC_METADATA_WORKING_BUDGET")
        val smallIndexWindow = LanSyncMetadataBudget(maximumIndexCost = 1)
        assertRejected(f.service(budget = smallIndexWindow).inspect(reconciled, metadata, unit), "SYNC_METADATA_INDEX_BUDGET")
        Files.newDirectoryStream(source.work.resolve("decoded-records")).use { assertFalse(it.iterator().hasNext()) }
        f.workspace.capture(source.owner.fingerprint).use { assertTrue(it.snapshot.records.isEmpty()) }

        val ready = f.service().inspect(reconciled, metadata, unit)
        assertEquals(LanSyncGroupDependencyStage.UNIT_CONTENT_AVAILABLE, ready.stage)
        assertEquals(f.prepared.unit.valueHash, requireNotNull(ready.available).decoded.valueHash)
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

    @Test fun signedMembershipLoadsWithoutHistoricalPayloadOrImageAndBrokenPrimaryManifestDoesNotDeriveAFallback() = withRoot { root ->
        val f = Fixture(root); val source = f.source; val unit = f.prepared.unit.payload.unit
        source.outbox.upload(f.prepared.operations.getValue(f.prepared.unit.membership.sha256), source.backend)
        val (reconciled, actual) = f.received()
        assertNull(source.backend.metadata(f.prepared.metadata.envelope.blobs.getValue(f.prepared.unit.plaintext.sha256).key))
        val membership = f.service().membership(reconciled, actual, unit)
        assertEquals(LanSyncGroupDependencyStage.RECORD_MEMBERSHIP_AVAILABLE, membership.stage)
        val evidence = requireNotNull(membership.membership); assertFalse(evidence.validatedAgainstNativePayload)
        val keys = mutableListOf<String>(); LanSyncGroupMembership.visit(evidence.path, evidence.identity, keys::add)
        assertEquals(listOf("character_cards/card"), keys)
        assertEquals(LanSyncGroupDependencyStage.WAITING_FOR_RESOURCE, f.service().inspect(reconciled, actual, unit).stage)
        source.outbox.upload(f.prepared.operations.getValue(f.prepared.unit.plaintext.sha256), source.backend)
        val original = requireNotNull(f.prepared.metadata.envelope.version)
        val legacy = original.copy(changes = original.changes.mapValues { it.value.copy(membership = null) }, signature = "unsigned").let {
            it.copy(signature = Base64.getEncoder().encodeToString(source.sender.signSyncGroupPayload(it.signingBytes())))
        }
        val oldMetadata = source.codec.enqueue(source.outbox, SyncStoreId.random(), CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()), source.cipher,
            f.prepared.metadata.envelope.copy(version = legacy))
        val (oldReconciliation, oldRead) = f.received(oldMetadata)
        val derived = f.service().membership(oldReconciliation, oldRead, unit)
        assertEquals("SYNC_GROUP_LEGACY_MEMBERSHIP_FROM_UNIT", derived.code); assertTrue(requireNotNull(derived.membership).validatedAgainstNativePayload)
        val broken = source.codec.enqueue(source.outbox, SyncStoreId.random(), CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()), source.cipher,
            f.prepared.metadata.envelope.copy(blobs = f.prepared.metadata.envelope.blobs - f.prepared.unit.membership.sha256))
        val (badReconciliation, badRead) = f.received(broken)
        assertEquals("SYNC_GROUP_DEPENDENCY_BINDING_MISSING", f.service().membership(badReconciliation, badRead, unit).code)
    }
}
