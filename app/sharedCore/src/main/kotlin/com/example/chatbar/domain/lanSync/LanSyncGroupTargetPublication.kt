package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.WeakWebDavAttemptStore
import com.example.chatbar.domain.syncStore.webdav.WeakWebDavCandidate
import com.example.chatbar.domain.syncStore.webdav.WeakWebDavObservation
import com.example.chatbar.domain.syncStore.webdav.WeakWebDavObservationOrigin
import java.io.FilterInputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable

@Serializable enum class LanSyncGroupTargetObjectRole { VAULT, RESOURCE, CAPSULE, NOTICE, LEDGER, VERSION }
@Serializable data class LanSyncGroupTargetObject(val role: LanSyncGroupTargetObjectRole, val slot: String,
    val key: CiphertextObjectKey, val descriptor: CiphertextDescriptor, val operation: SyncStoreId)
@Serializable data class LanSyncGroupTargetPlan(val group: SyncStoreId, val checkpoint: SyncVersionRef,
    val epoch: SyncStoreId, val commitment: String, val target: SyncDeliveryTarget,
    val versions: Set<SyncVersionRef>, val objects: List<LanSyncGroupTargetObject>)
/** Exact backend response retained only after a complete descriptor-pinned readback of that object. */
@Serializable data class LanSyncGroupTargetObjectReceipt(val objectRequest: LanSyncGroupTargetObject,
    val returnedKey: CiphertextObjectKey, val returnedDescriptor: CiphertextDescriptor, val disposition: String)
/** Historical completion of this exact selection and route. It is neither freshness nor native activation. */
@Serializable data class LanSyncGroupTargetReceipt(val planHash: String, val target: SyncDeliveryTarget,
    val checkpoint: SyncVersionRef, val objects: List<LanSyncGroupTargetObjectReceipt>)
/** Original physical candidate and full-GET observation, explicitly separate from strict put results. */
@Serializable data class LanSyncGroupWeakTargetObservation(val route: SyncStoreId, val key: CiphertextObjectKey,
    val descriptor: CiphertextDescriptor, val operation: SyncStoreId, val attempt: SyncStoreId,
    val origin: String, val observedAtMillis: Long)
@Serializable data class LanSyncGroupWeakTargetObjectReceipt(val objectRequest: LanSyncGroupTargetObject,
    val appendObservation: LanSyncGroupWeakTargetObservation, val readbackObservation: LanSyncGroupWeakTargetObservation)
/** Historical authenticated selection delivery through unique attempts; no immutable-store claim. */
@Serializable data class LanSyncGroupWeakTargetReceipt(val planHash: String, val target: SyncDeliveryTarget,
    val checkpoint: SyncVersionRef, val objects: List<LanSyncGroupWeakTargetObjectReceipt>)

/**
 * Daily, non-signing relay of a complete frozen admitted selection. Raw quarantine never becomes a
 * strict target's canonical object. All resource/transport I/O runs outside Node/native admission.
 * The caller must keep target.id bound to one actual route/account; provider configuration owns that
 * endpoint binding. This first version accepts strict stores and current-epoch version containers.
 */
class LanSyncGroupTargetPublication internal constructor(private val root: Path,
    private val target: SyncDeliveryTarget, private val proof: LanSyncGroupTargetExport) {
    @Serializable private data class Selection(val group: SyncStoreId, val checkpoint: SyncVersionRef,
        val target: SyncDeliveryTarget, val vault: SyncManagementVaultContext, val vaultDescriptor: CiphertextDescriptor,
        val versions: Set<SyncVersionRef>)
    @Serializable private data class Binding(val group: SyncStoreId, val genesis: SyncVersionRef, val device: String,
        val target: SyncDeliveryTarget)
    private val lock = locks.computeIfAbsent(root) { ReentrantLock() }
    private fun directory(name: String) = root.resolve(name).also {
        PrivateSyncFile.checked(root, true)
        if (!Files.exists(it, NOFOLLOW_LINKS)) Files.createDirectory(it)
        PrivateSyncFile.checked(it, true)
    }
    private val work = directory("preparations")
    private val retained = FileCiphertextStore(directory("retained"))
    private val selection = Selection(proof.trust.group, proof.current.reference(), target, proof.vault,
        proof.vaultDescriptor, proof.versions.mapTo(linkedSetOf()) { it.reference() })
    private val id = lanSyncHash(lanSyncJson.encodeToJsonElement(Selection.serializer(), selection))
    private val planPath = root.resolve("plan-$id.json")
    private val receiptPath = root.resolve("receipt-$id.json")
    private val weakReceiptPath = root.resolve("weak-receipt-$id.json")
    init {
        require(target != SyncDeliveryTarget.LEGACY) { "SYNC_GROUP_TARGET_EXPLICIT_ROUTE_REQUIRED" }
        guarded {
            val path = root.resolve("target.json")
            val expected = Binding(proof.trust.group, proof.trust.genesis, proof.device, target)
            val prior = LanSyncJsonFiles.read(path, Binding.serializer())
            if (prior == null) LanSyncJsonFiles.write(path, Binding.serializer(), expected)
            else require(prior == expected) { "SYNC_GROUP_TARGET_ROUTE_CHANGED" }
        }
    }
    /** Completes and reauthenticates local preparation; a plan is never a target receipt. */
    suspend fun prepare(): LanSyncGroupTargetPlan = runInterruptible(Dispatchers.IO) { guarded {
        proof.requireLive()
        val prior = readPlan()
        if (prior != null) { validate(prior, retained); return@guarded prior }
        val stages = mutableListOf<LanSyncGroupTargetStage>()
        try {
            val items = mutableListOf<LanSyncGroupTargetObject>()
            fun add(role: LanSyncGroupTargetObjectRole, slot: String, stage: LanSyncGroupTargetStage) {
                stages += stage
                items += LanSyncGroupTargetObject(role, slot, stage.metadata.key, stage.metadata.descriptor, SyncStoreId.random())
            }
            add(LanSyncGroupTargetObjectRole.VAULT, "vault", stage(CiphertextObjectMetadata(proof.vault.key, proof.vaultDescriptor)))
            var rootFound = false
            val slots = linkedSetOf<String>(); val notices = linkedSetOf<String>()
            for (metadata in inventory()) {
                proof.requireLive()
                var candidate: LanSyncGroupTargetStage? = null
                try {
                    candidate = stage(metadata)
                    val prefix = lanSyncTargetPrefix(candidate)
                    when {
                        SyncKeyDeliveryCodec.hasPrefix(prefix) && metadata.descriptor.length <= SyncKeyDeliveryCodec.MAX_BYTES -> {
                            val value = Files.newInputStream(candidate.file.path, NOFOLLOW_LINKS).use { SyncKeyDeliveryCodec.decode(SyncKeyDeliveryCodec.read(it)) }
                            val slot = proof.capsule(value, metadata.key)
                            if (slot in proof.keySlots() && slots.add(slot)) { add(LanSyncGroupTargetObjectRole.CAPSULE, slot, candidate); candidate = null }
                        }
                        SyncLedgerNoticeCodec.hasPrefix(prefix) && metadata.descriptor.length <= SyncLedgerNoticeCodec.MAX_BYTES -> {
                            val value = Files.newInputStream(candidate.file.path, NOFOLLOW_LINKS).use { SyncLedgerNoticeCodec.decode(SyncLedgerNoticeCodec.read(it)) }
                            val recipient = proof.notice(value, metadata.key)
                            if (notices.add(recipient)) { add(LanSyncGroupTargetObjectRole.NOTICE, recipient, candidate); candidate = null }
                        }
                        !rootFound && SyncCiphertextHeader.hasPrefix(prefix) -> {
                            val hint = Files.newInputStream(candidate.file.path, NOFOLLOW_LINKS).use(SyncCiphertextHeader::peek)
                            if (hint.context.group == proof.trust.group && hint.context.epoch == proof.current.epoch && hint.keyCommitment == proof.current.keyCommitment) {
                                val actual = SyncGroupObjectCodec(work).read(lanSyncTargetLens(listOf(candidate), proof::requireLive), proof.cipher(proof.current.epoch), hint.context, metadata.descriptor)
                                if (actual.envelope.deviceLedger != null) {
                                    proof.root(actual); add(LanSyncGroupTargetObjectRole.LEDGER, "ledger", candidate); candidate = null; rootFound = true
                                }
                            }
                        }
                    }
                } catch (failure: Exception) { lanSyncTargetCandidateFailure(failure) }
                finally { candidate?.close() }
            }
            proof.requireLive()
            require(rootFound && slots == proof.keySlots() && notices == SyncLedgerNotice.revocations(proof.ledger).keys) { "SYNC_GROUP_TARGET_ROOT_INCOMPLETE" }
            for (version in proof.versions) {
                var selected = false
                val candidates = proof.view.authenticatedVersions.filter { it.envelope.version?.reference() == version.reference() &&
                    it.context.epoch == proof.current.epoch && it.keyCommitment == proof.current.keyCommitment }
                    .sortedWith(compareBy({ it.metadata.key.id.value }, { it.metadata.descriptor.sha256.hex }))
                for (witness in candidates) {
                    proof.requireLive()
                    val trial = mutableListOf<LanSyncGroupTargetStage>()
                    try {
                        val metadata = stage(witness.metadata); trial += metadata
                        val actual = SyncGroupObjectCodec(work).read(lanSyncTargetLens(trial, proof::requireLive), proof.cipher(proof.current.epoch), witness.context,
                            witness.metadata.descriptor, version.reference())
                        proof.version(actual)
                        actual.envelope.blobs.values.forEach { binding -> trial += stage(CiphertextObjectMetadata(binding.key, binding.descriptor)) }
                        proof.dependencies(listOf(actual), lanSyncTargetLens(trial, proof::requireLive), work)
                        add(LanSyncGroupTargetObjectRole.VERSION, version.id.value, trial.first())
                        trial.drop(1).forEach { add(LanSyncGroupTargetObjectRole.RESOURCE, it.metadata.key.id.value, it) }
                        trial.clear(); selected = true; break
                    } catch (failure: Exception) { lanSyncTargetCandidateFailure(failure) }
                    finally { trial.asReversed().forEach { it.close() } }
                }
                require(selected) { "SYNC_GROUP_TARGET_VERSION_INCOMPLETE" }
            }
            // A strict destination cannot represent two selected authenticated descriptors for one key.
            require(items.groupBy { it.key }.values.all { rows -> rows.map { it.descriptor }.toSet().size == 1 }) { "SYNC_GROUP_TARGET_DESCRIPTOR_CONFLICT" }
            val unique = items.distinctBy { it.key }.sortedWith(compareBy({ it.role.ordinal }, { it.key.id.value }))
            val plan = LanSyncGroupTargetPlan(proof.trust.group, proof.current.reference(), proof.current.epoch,
                proof.current.keyCommitment, target, selection.versions, unique)
            validate(plan, lanSyncTargetLens(stages, proof::requireLive))
            for (item in unique) {
                proof.requireLive()
                val actual = stages.first { it.metadata == CiphertextObjectMetadata(item.key, item.descriptor) }
                Files.newInputStream(actual.file.path, NOFOLLOW_LINKS).use {
                    retained.put(CiphertextPutRequest(item.key, item.operation, item.descriptor), liveInput(it))
                }
            }
            proof.requireLive(); LanSyncJsonFiles.write(planPath, LanSyncGroupTargetPlan.serializer(), plan)
            plan
        } finally { stages.asReversed().forEach { it.close() } }
    } }
    /** Fixed-route historical receipt. Does not inspect remote freshness. */
    fun receipt(): LanSyncGroupTargetReceipt? = guarded {
        proof.requireLive()
        val plan = readPlan() ?: run { require(!Files.exists(receiptPath, NOFOLLOW_LINKS)) { "SYNC_GROUP_TARGET_PLAN_MISSING" }; return@guarded null }
        readReceipt(plan)
    }
    suspend fun upload(backend: CiphertextStore): LanSyncGroupTargetReceipt = runInterruptible(Dispatchers.IO) { guarded {
        proof.requireLive()
        require(!backend.capabilities.untrustedCandidateVariants) { "SYNC_GROUP_TARGET_STRICT_STORE_REQUIRED" }
        val plan = requireNotNull(readPlan()) { "SYNC_GROUP_TARGET_PLAN_MISSING" }
        validate(plan, retained)
        readReceipt(plan)?.let { return@guarded it }
        val observations = mutableListOf<LanSyncGroupTargetObjectReceipt>()
        for (item in plan.objects) {
            proof.requireLive()
            val request = CiphertextPutRequest(item.key, item.operation, item.descriptor)
            // A new route receives this original request even when some other backend saw it already.
            val staged = stage(CiphertextObjectMetadata(item.key, item.descriptor), retained)
            try {
                val result = Files.newInputStream(staged.file.path, NOFOLLOW_LINKS).use { backend.put(request, liveInput(it)) }
                require(result.metadata == staged.metadata) { "SYNC_GROUP_TARGET_BACKEND_RECEIPT_CHANGED" }
                lanSyncTargetStage(work, backend, staged.metadata, proof::requireLive).use { }
                observations += LanSyncGroupTargetObjectReceipt(item, result.metadata.key, result.metadata.descriptor, result.disposition.name)
            } finally { staged.close() }
            proof.requireLive()
        }
        val receipt = LanSyncGroupTargetReceipt(planHash(), target, plan.checkpoint, observations.toList())
        proof.requireLive(); LanSyncJsonFiles.write(receiptPath, LanSyncGroupTargetReceipt.serializer(), receipt)
        receipt
    } }
    /** Unique-attempt delivery does not touch any strict/Outbox uploaded receipt. */
    suspend fun upload(backend: WeakWebDavAttemptStore): LanSyncGroupWeakTargetReceipt = runInterruptible(Dispatchers.IO) { guarded {
        proof.requireLive()
        require(target.kind == SyncDeliveryKind.CLOUD_STORE && backend.routeId == target.id) { "SYNC_GROUP_TARGET_ROUTE_CHANGED" }
        val plan = requireNotNull(readPlan()) { "SYNC_GROUP_TARGET_PLAN_MISSING" }
        validate(plan, retained)
        readWeakReceipt(plan)?.let { return@guarded it }
        val observations = mutableListOf<LanSyncGroupWeakTargetObjectReceipt>()
        val readbacks = mutableListOf<LanSyncGroupTargetStage>()
        try {
            for (item in plan.objects) {
                proof.requireLive()
                val metadata = CiphertextObjectMetadata(item.key, item.descriptor)
                val staged = stage(metadata, retained)
                val appended = try {
                    Files.newInputStream(staged.file.path, NOFOLLOW_LINKS).use { input ->
                        backend.appendAttempt(CiphertextPutRequest(item.key, item.operation, item.descriptor),
                            liveInput(input), checkActive = proof::requireLive)
                    }
                } finally { staged.close() }
                proof.requireLive(); requireCandidate(item, appended.candidate)
                var observed: WeakWebDavObservation? = null
                val readback = lanSyncTargetReceiveStage(work, metadata, proof::requireLive) { output ->
                    val actual = backend.readCandidate(appended.candidate, output, checkActive = proof::requireLive)
                        ?: return@lanSyncTargetReceiveStage null
                    requireCandidate(item, actual.candidate)
                    require(actual.candidate == appended.candidate) { "SYNC_GROUP_TARGET_ATTEMPT_CHANGED" }
                    observed = actual
                    metadata
                }
                readbacks += readback
                observations += LanSyncGroupWeakTargetObjectReceipt(item, observation(appended), observation(requireNotNull(observed)))
                proof.requireLive()
            }
            // Authenticate the actual readback bytes again; a provider observation alone is never authority.
            validate(plan, lanSyncTargetLens(readbacks, proof::requireLive))
            val receipt = LanSyncGroupWeakTargetReceipt(planHash(), target, plan.checkpoint, observations.toList())
            proof.requireLive(); LanSyncJsonFiles.write(weakReceiptPath, LanSyncGroupWeakTargetReceipt.serializer(), receipt)
            receipt
        } finally { readbacks.asReversed().forEach { it.close() } }
    } }
    fun weakReceipt(): LanSyncGroupWeakTargetReceipt? = guarded {
        proof.requireLive()
        val plan = readPlan() ?: run {
            require(!Files.exists(weakReceiptPath, NOFOLLOW_LINKS)) { "SYNC_GROUP_TARGET_PLAN_MISSING" }
            return@guarded null
        }
        readWeakReceipt(plan)
    }
    private fun stage(metadata: CiphertextObjectMetadata, source: CiphertextStore = proof.source) =
        lanSyncTargetStage(work, source, metadata, proof::requireLive)
    private fun inventory(): List<CiphertextObjectMetadata> {
        val values = linkedSetOf<CiphertextObjectMetadata>(); val seen = hashSetOf<String>(); var cursor: CommitCursor? = null
        do {
            proof.requireLive()
            cursor?.let { require(seen.add(it.token)) { "SYNC_GROUP_TARGET_LISTING_CHANGED" } }
            val page = proof.source.listCommits(cursor); values += page.entries; cursor = page.nextCursor
        } while (cursor != null)
        return values.sortedWith(compareBy({ it.key.id.value }, { it.descriptor.sha256.hex }))
    }
    private fun validate(plan: LanSyncGroupTargetPlan, source: CiphertextStore) {
        proof.requireLive()
        validateHeader(plan)
        val staged = mutableListOf<LanSyncGroupTargetStage>()
        try {
            plan.objects.forEach { staged += stage(CiphertextObjectMetadata(it.key, it.descriptor), source) }
            val lens = lanSyncTargetLens(staged, proof::requireLive); val values = mutableListOf<SyncAuthenticatedGroupObject>()
            val slots = linkedSetOf<String>(); val notices = linkedSetOf<String>(); var roots = 0; var vaults = 0
            for ((index, item) in plan.objects.withIndex()) {
                proof.requireLive(); val file = staged[index].file.path
                when (item.role) {
                    LanSyncGroupTargetObjectRole.VAULT -> {
                        require(item.slot == "vault" && item.key == proof.vault.key && item.descriptor == proof.vaultDescriptor) { "SYNC_GROUP_TARGET_VAULT_CHANGED" }; vaults++
                    }
                    LanSyncGroupTargetObjectRole.CAPSULE -> Files.newInputStream(file, NOFOLLOW_LINKS).use {
                        require(item.descriptor.length <= SyncKeyDeliveryCodec.MAX_BYTES) { "SYNC_GROUP_TARGET_CAPSULE_CHANGED" }
                        val slot = proof.capsule(SyncKeyDeliveryCodec.decode(SyncKeyDeliveryCodec.read(it)), item.key)
                        require(item.slot == slot && slots.add(slot)) { "SYNC_GROUP_TARGET_CAPSULE_CHANGED" }
                    }
                    LanSyncGroupTargetObjectRole.NOTICE -> Files.newInputStream(file, NOFOLLOW_LINKS).use {
                        require(item.descriptor.length <= SyncLedgerNoticeCodec.MAX_BYTES) { "SYNC_GROUP_TARGET_NOTICE_CHANGED" }
                        val recipient = proof.notice(SyncLedgerNoticeCodec.decode(SyncLedgerNoticeCodec.read(it)), item.key)
                        require(item.slot == recipient && notices.add(recipient)) { "SYNC_GROUP_TARGET_NOTICE_CHANGED" }
                    }
                    LanSyncGroupTargetObjectRole.LEDGER, LanSyncGroupTargetObjectRole.VERSION -> {
                        val hint = Files.newInputStream(file, NOFOLLOW_LINKS).use(SyncCiphertextHeader::peek)
                        require(hint.context.key == item.key && hint.context.group == proof.trust.group &&
                            hint.context.epoch == proof.current.epoch && hint.keyCommitment == proof.current.keyCommitment) { "SYNC_GROUP_TARGET_CURRENT_CONTAINER_REQUIRED" }
                        val actual = SyncGroupObjectCodec(work).read(lens, proof.cipher(proof.current.epoch), hint.context, item.descriptor)
                        if (item.role == LanSyncGroupTargetObjectRole.LEDGER) { require(item.slot == "ledger") { "SYNC_GROUP_TARGET_LEDGER_CHANGED" }; proof.root(actual); roots++ }
                        else { proof.version(actual); require(item.slot == requireNotNull(actual.envelope.version).id.value) { "SYNC_GROUP_TARGET_VERSION_CHANGED" }; values += actual }
                    }
                    LanSyncGroupTargetObjectRole.RESOURCE -> require(item.key.kind == CiphertextObjectKind.BLOB && item.slot == item.key.id.value) { "SYNC_GROUP_TARGET_RESOURCE_CHANGED" }
                }
            }
            require(vaults == 1 && roots == 1 && slots == proof.keySlots() && notices == SyncLedgerNotice.revocations(proof.ledger).keys &&
                values.map { it.envelope.reference() }.toSet() == selection.versions && values.size == selection.versions.size) { "SYNC_GROUP_TARGET_SELECTION_INCOMPLETE" }
            val blobs = values.flatMap { it.envelope.blobs.values }.map { CiphertextObjectMetadata(it.key, it.descriptor) }.toSet()
            require(plan.objects.filter { it.role == LanSyncGroupTargetObjectRole.RESOURCE }.map { CiphertextObjectMetadata(it.key, it.descriptor) }.toSet() == blobs) { "SYNC_GROUP_TARGET_RESOURCE_CHANGED" }
            proof.dependencies(values, lens, work)
        } finally { staged.asReversed().forEach { it.close() } }
        proof.requireLive()
    }
    private fun validateHeader(plan: LanSyncGroupTargetPlan) {
        require(plan.group == selection.group && plan.target == target && plan.checkpoint == selection.checkpoint &&
            plan.epoch == proof.current.epoch && plan.commitment == proof.current.keyCommitment && plan.versions == selection.versions &&
            plan.objects.map { it.key }.toSet().size == plan.objects.size && plan.objects.map { it.operation }.toSet().size == plan.objects.size &&
            plan.objects == plan.objects.sortedWith(compareBy({ it.role.ordinal }, { it.key.id.value }))) { "SYNC_GROUP_TARGET_PLAN_CHANGED" }
    }
    private fun readPlan(): LanSyncGroupTargetPlan? {
        if (!Files.exists(planPath, NOFOLLOW_LINKS)) return null
        LanSyncMetadataBudget().validate(planPath)
        return requireNotNull(LanSyncJsonFiles.read(planPath, LanSyncGroupTargetPlan.serializer())).also(::validateHeader)
    }
    private fun planHash() = LanSyncResourceStore.hashFile(planPath, Files.size(planPath))
    private fun readReceipt(plan: LanSyncGroupTargetPlan): LanSyncGroupTargetReceipt? {
        if (!Files.exists(receiptPath, NOFOLLOW_LINKS)) return null
        LanSyncMetadataBudget().validate(receiptPath)
        return requireNotNull(LanSyncJsonFiles.read(receiptPath, LanSyncGroupTargetReceipt.serializer())).also { receipt ->
            require(receipt.planHash == planHash() && receipt.target == target && receipt.checkpoint == plan.checkpoint &&
                receipt.objects.map { it.objectRequest } == plan.objects && receipt.objects.all {
                    it.returnedKey == it.objectRequest.key && it.returnedDescriptor == it.objectRequest.descriptor &&
                        it.disposition in CiphertextPutDisposition.entries.map { value -> value.name }
                }) { "SYNC_GROUP_TARGET_RECEIPT_CHANGED" }
        }
    }
    private fun requireCandidate(item: LanSyncGroupTargetObject, candidate: WeakWebDavCandidate) {
        require(candidate.route == target.id && candidate.key == item.key && candidate.descriptor == item.descriptor &&
            candidate.operationId == item.operation) { "SYNC_GROUP_TARGET_ATTEMPT_CHANGED" }
    }
    private fun observation(value: WeakWebDavObservation): LanSyncGroupWeakTargetObservation {
        val candidate = value.candidate
        return LanSyncGroupWeakTargetObservation(candidate.route, candidate.key, candidate.descriptor,
            candidate.operationId, candidate.attempt, value.origin.name, value.observedAtMillis)
    }
    private fun readWeakReceipt(plan: LanSyncGroupTargetPlan): LanSyncGroupWeakTargetReceipt? {
        if (!Files.exists(weakReceiptPath, NOFOLLOW_LINKS)) return null
        LanSyncMetadataBudget().validate(weakReceiptPath)
        return requireNotNull(LanSyncJsonFiles.read(weakReceiptPath, LanSyncGroupWeakTargetReceipt.serializer())).also { receipt ->
            require(receipt.planHash == planHash() && receipt.target == target && receipt.checkpoint == plan.checkpoint &&
                receipt.objects.map { it.objectRequest } == plan.objects && receipt.objects.all { item ->
                    val one = item.appendObservation; val two = item.readbackObservation
                    fun matches(value: LanSyncGroupWeakTargetObservation) = value.route == target.id && value.key == item.objectRequest.key &&
                        value.descriptor == item.objectRequest.descriptor && value.operation == item.objectRequest.operation &&
                        value.origin in WeakWebDavObservationOrigin.entries.map { it.name }
                    matches(one) && matches(two) && one.attempt == two.attempt &&
                        two.origin == WeakWebDavObservationOrigin.EXISTING_ATTEMPT_READBACK.name
                }) { "SYNC_GROUP_TARGET_RECEIPT_CHANGED" }
        }
    }
    private fun liveInput(input: java.io.InputStream) = object : FilterInputStream(input) {
        override fun read(): Int { proof.requireLive(); return `in`.read() }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int { proof.requireLive(); return `in`.read(bytes, offset, length) }
    }
    private fun <T> guarded(work: () -> T): T {
        check(!lock.isHeldByCurrentThread) { "SYNC_GROUP_TARGET_REENTRY" }; lock.lockInterruptibly()
        try { PrivateSyncFile.checked(root, true)
            FileChannel.open(root.resolve("publication.lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel -> channel.lock().use { return work() } }
        } finally { lock.unlock() }
    }
    companion object { private val locks = ConcurrentHashMap<Path, ReentrantLock>() }
}
