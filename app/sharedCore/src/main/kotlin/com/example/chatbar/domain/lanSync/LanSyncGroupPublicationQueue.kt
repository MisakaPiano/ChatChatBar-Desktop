package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlinx.serialization.Serializable

@Serializable data class LanSyncGroupPublishObject(val operation: SyncStoreId, val key: CiphertextObjectKey,
    val descriptor: CiphertextDescriptor, val plaintext: SyncPlaintextIdentity)
@Serializable data class LanSyncGroupPublishPlan(val group: SyncStoreId, val epoch: SyncStoreId, val keyCommitment: String,
    val reference: SyncVersionRef, val metadata: LanSyncGroupPublishObject, val blobs: Map<String, LanSyncGroupPublishObject>)
@Serializable data class LanSyncGroupPublishReceipt(val planHash: String, val metadata: CiphertextDescriptor, val resourceCount: Long)

/**
 * Encrypted delivery ordering only. The native writer still owns approved changes, durable author
 * sequence, proposals and independent recovery custody; this queue never activates app data.
 */
class LanSyncGroupPublicationQueue(private val root: Path, private val ledger: SyncDeviceLedger,
    private val outbox: SyncEncryptedOutbox, private val localDevice: String,
    private val observe: (String) -> Unit = {}, private val metadataBudget: LanSyncMetadataBudget = LanSyncMetadataBudget()) {
    private val lock: ReentrantLock
    init {
        lanSyncSafe(root, true); require(localDevice.matches(Regex("[a-f0-9]{64}"))) { "SYNC_GROUP_PUBLICATION_DEVICE" }
        lock = locks.computeIfAbsent(root) { ReentrantLock() }
    }
    private fun planPath(operation: SyncStoreId) = root.resolve("publication-${operation.value}.json")
    private fun receiptPath(operation: SyncStoreId) = root.resolve("publication-receipt-${operation.value}.json")

    fun prepare(metadata: SyncPreparedGroupObject, units: Collection<LanSyncDecodedGroupUnit>,
        blobOperations: Map<String, SyncStoreId>, knownVersions: Collection<SyncCausalVersion>): LanSyncGroupPublishPlan = guarded {
        val latest = requireCurrent(metadata.context.group, metadata.context.epoch, metadata.authenticated.keyCommitment)
        val envelope = metadata.envelope
        val expected = linkedMapOf<String, SyncPlaintextIdentity>()
        fun add(identity: SyncPlaintextIdentity) {
            require(expected.put(identity.sha256, identity)?.let { it == identity } != false) { "SYNC_GROUP_PUBLICATION_DEPENDENCY" }
        }
        val native = units.associateBy { it.payload.unit }
        require(native.size == units.size) { "SYNC_GROUP_PUBLICATION_UNIT" }
        if (envelope.kind == SyncGroupObjectKind.VERSION) {
            val version = requireNotNull(envelope.version)
            ledger.admission(knownVersions + version, listOf(metadata.authenticated)).requireAuthorized(version)
            val authoredScope = requireNotNull(ledger.record(version.deviceLedger)) { "SYNC_GROUP_PUBLICATION_LEDGER" }.coveredTypes
            val changed = version.changes.filterValues { it.content != null }
            require(native.keys == changed.keys) { "SYNC_GROUP_PUBLICATION_UNIT" }
            changed.forEach { (unit, change) ->
                val proof = native.getValue(unit)
                require(proof.plaintext == change.content && proof.valueHash == change.valueHash && proof.payload.coveredTypes == authoredScope) { "SYNC_GROUP_PUBLICATION_UNIT" }
                add(proof.plaintext); proof.dependencies.forEach(::add)
            }
        } else {
            require(native.isEmpty() && ledger.contains(requireNotNull(envelope.deviceLedger).reference())) { "SYNC_GROUP_PUBLICATION_LEDGER" }
        }
        require(envelope.blobs.keys == expected.keys && blobOperations.keys == expected.keys) { "SYNC_GROUP_PUBLICATION_DEPENDENCY" }
        val blobs = expected.toSortedMap().mapValues { (hash, identity) ->
            val binding = envelope.blobs.getValue(hash)
            require(binding.plaintext == identity && binding.epoch == latest.epoch) { "SYNC_GROUP_PUBLICATION_DEPENDENCY" }
            val entry = requireNotNull(outbox.entry(blobOperations.getValue(hash))) { "SYNC_GROUP_PUBLICATION_BLOB_NOT_READY" }
            require(entry.context == SyncEncryptionContext(latest.group, latest.epoch, binding.key) && entry.plaintext == identity &&
                entry.request.descriptor == binding.descriptor) { "SYNC_GROUP_PUBLICATION_BLOB_CHANGED" }
            objectFrom(entry)
        }
        val retainedMetadata = requireNotNull(outbox.entry(metadata.request.operationId)) { "SYNC_GROUP_PUBLICATION_METADATA_NOT_READY" }
        require(retainedMetadata.context == metadata.context && retainedMetadata.request == metadata.request &&
            retainedMetadata.plaintext == metadata.entry.plaintext && retainedMetadata.request.key.kind == CiphertextObjectKind.COMMIT) { "SYNC_GROUP_PUBLICATION_METADATA_CHANGED" }
        val plan = LanSyncGroupPublishPlan(latest.group, latest.epoch, latest.keyCommitment, envelope.reference(), objectFrom(retainedMetadata), blobs)
        val path = planPath(plan.metadata.operation)
        val previous = readPlan(plan.metadata.operation)
        if (previous != null) require(previous == plan) { "SYNC_GROUP_PUBLICATION_PLAN_CHANGED" }
        else {
            require(!retainedMetadata.uploaded && !Files.exists(receiptPath(plan.metadata.operation), NOFOLLOW_LINKS)) { "SYNC_GROUP_PUBLICATION_PREPARATION_REQUIRED" }
            LanSyncJsonFiles.write(path, LanSyncGroupPublishPlan.serializer(), plan)
        }
        outbox.bindDeliveryHash(plan.metadata.operation, hash(path))
        observe("PLAN_RECORDED")
        plan
    }

    fun upload(operation: SyncStoreId, backend: CiphertextStore): LanSyncGroupPublishReceipt = guarded {
        val plan = requireNotNull(readPlan(operation)) { "SYNC_GROUP_PUBLICATION_PLAN_MISSING" }
        requireBound(operation)
        readReceipt(operation, plan)?.let { return@guarded it }
        requireCurrent(plan.group, plan.epoch, plan.keyCommitment)
        for (blob in plan.blobs.values) {
            requireCurrent(plan.group, plan.epoch, plan.keyCommitment)
            deliver(blob, plan, backend)
            requireCurrent(plan.group, plan.epoch, plan.keyCommitment)
            observe("BLOB_READY")
        }
        requireCurrent(plan.group, plan.epoch, plan.keyCommitment)
        observe("ALL_BLOBS_READY")
        requireCurrent(plan.group, plan.epoch, plan.keyCommitment)
        deliver(plan.metadata, plan, backend)
        observe("METADATA_READY")
        requireCurrent(plan.group, plan.epoch, plan.keyCommitment)
        val receipt = LanSyncGroupPublishReceipt(hash(planPath(operation)), plan.metadata.descriptor, plan.blobs.size.toLong())
        LanSyncJsonFiles.write(receiptPath(operation), LanSyncGroupPublishReceipt.serializer(), receipt)
        observe("RECEIPT_RECORDED")
        receipt
    }
    fun receipt(operation: SyncStoreId): LanSyncGroupPublishReceipt? = guarded {
        val plan = readPlan(operation)
        require(plan != null || !Files.exists(receiptPath(operation), NOFOLLOW_LINKS)) { "SYNC_GROUP_PUBLICATION_PLAN_MISSING" }
        plan?.let { readReceipt(operation, it) }
    }
    private fun deliver(item: LanSyncGroupPublishObject, plan: LanSyncGroupPublishPlan, backend: CiphertextStore) {
        val entry = requireNotNull(outbox.entry(item.operation)) { "SYNC_GROUP_PUBLICATION_OBJECT_MISSING" }
        require(entry.context == SyncEncryptionContext(plan.group, plan.epoch, item.key) && objectFrom(entry) == item) { "SYNC_GROUP_PUBLICATION_OBJECT_CHANGED" }
        val stored = backend.metadata(item.key)
        if (stored == null) {
            val result = outbox.upload(item.operation, backend)
            require(result.metadata == CiphertextObjectMetadata(item.key, item.descriptor)) { "SYNC_GROUP_PUBLICATION_BACKEND_RECEIPT" }
            observe("OBJECT_UPLOADED")
        } else {
            require(stored == CiphertextObjectMetadata(item.key, item.descriptor)) { "SYNC_GROUP_PUBLICATION_REMOTE_CONFLICT" }
            observe("OBJECT_ALREADY_PRESENT")
        }
    }
    private fun requireCurrent(group: SyncStoreId, epoch: SyncStoreId, commitment: String): SyncDeviceLedgerRecord = ledger.current().also {
        require(it.group == group) { "SYNC_GROUP_PUBLICATION_DEVICE" }
        require(it.members[localDevice]?.active == true) { "SYNC_GROUP_PUBLICATION_DEVICE_REVOKED" }
        require(it.epoch == epoch && it.keyCommitment == commitment) { "SYNC_GROUP_PUBLICATION_EPOCH_CHANGED" }
    }
    private fun objectFrom(entry: SyncOutboxEntry) = LanSyncGroupPublishObject(entry.request.operationId, entry.request.key, entry.request.descriptor, entry.plaintext)
    private fun readPlan(operation: SyncStoreId): LanSyncGroupPublishPlan? {
        val path = planPath(operation)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        metadataBudget.validate(path)
        return requireNotNull(LanSyncJsonFiles.read(path, LanSyncGroupPublishPlan.serializer())).also { plan ->
            require(plan.metadata.operation == operation && plan.metadata.key.kind == CiphertextObjectKind.COMMIT && plan.keyCommitment.matches(Regex("[a-f0-9]{64}")) &&
                plan.blobs.all { (hash, blob) -> blob.key.kind == CiphertextObjectKind.BLOB && hash == blob.plaintext.sha256 }) { "SYNC_GROUP_PUBLICATION_PLAN_CHANGED" }
        }
    }
    private fun readReceipt(operation: SyncStoreId, plan: LanSyncGroupPublishPlan): LanSyncGroupPublishReceipt? {
        val path = receiptPath(operation)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        requireBound(operation)
        metadataBudget.validate(path)
        return requireNotNull(LanSyncJsonFiles.read(path, LanSyncGroupPublishReceipt.serializer())).also {
            require(it.planHash == hash(planPath(operation)) && it.metadata == plan.metadata.descriptor && it.resourceCount == plan.blobs.size.toLong()) { "SYNC_GROUP_PUBLICATION_RECEIPT_CHANGED" }
        }
    }
    private fun hash(path: Path) = LanSyncResourceStore.hashFile(path, Files.size(path))
    private fun requireBound(operation: SyncStoreId) {
        require(outbox.deliveryHash(operation) == hash(planPath(operation))) { "SYNC_GROUP_PUBLICATION_PLAN_SEAL" }
    }
    private fun <T> guarded(work: () -> T): T {
        check(!lock.isHeldByCurrentThread) { "SYNC_GROUP_PUBLICATION_REENTRY" }
        lock.lockInterruptibly()
        try {
            lanSyncSafe(root, true)
            val path = root.resolve("publication.lock")
            FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                lanSyncSafe(path); channel.lock().use { return work() }
            }
        } finally { lock.unlock() }
    }
    companion object { private val locks = ConcurrentHashMap<Path, ReentrantLock>() }
}
