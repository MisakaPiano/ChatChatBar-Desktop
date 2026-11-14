package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.WeakWebDavAttemptStore
import com.example.chatbar.domain.syncStore.webdav.WeakWebDavCandidate
import com.example.chatbar.domain.syncStore.webdav.WeakWebDavObservation
import com.example.chatbar.domain.syncStore.webdav.WeakWebDavObservationOrigin
import com.example.chatbar.domain.syncStore.webdav.WeakWebDavChunkAssembly
import com.example.chatbar.domain.syncStore.webdav.WeakWebDavAssemblyObservation
import com.example.chatbar.domain.syncStore.webdav.WeakWebDavCursor
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
import kotlinx.serialization.KSerializer

@Serializable enum class LanSyncGroupTargetObjectRole { VAULT, RESOURCE, CAPSULE, NOTICE, LEDGER, VERSION }
@Serializable data class LanSyncGroupTargetObject(val role: LanSyncGroupTargetObjectRole, val slot: String,
    val key: CiphertextObjectKey, val descriptor: CiphertextDescriptor, val operation: SyncStoreId)
@Serializable data class LanSyncGroupTargetPlan(val group: SyncStoreId, val checkpoint: SyncVersionRef,
    val epoch: SyncStoreId, val commitment: String, val target: SyncDeliveryTarget,
    val versions: Set<SyncVersionRef>, val objects: List<LanSyncGroupTargetObject>)
/** Exact backend response retained only after a complete descriptor-pinned readback of that object. */
@Serializable data class LanSyncGroupTargetObjectReceipt(val objectRequest: LanSyncGroupTargetObject,
    val returnedKey: CiphertextObjectKey, val returnedDescriptor: CiphertextDescriptor, val disposition: String,
    /** Null preserves an older historical receipt whose exact readback time was not recorded. */
    val observedAtMillis: Long? = null)
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
/** Physical provider manifest identity; its descriptor is not the original ciphertext descriptor. */
@Serializable data class LanSyncGroupChunkManifestLocator(val route: SyncStoreId, val manifestKey: CiphertextObjectKey,
    val manifestDescriptor: CiphertextDescriptor, val manifestOperation: SyncStoreId, val attempt: SyncStoreId)
@Serializable data class LanSyncGroupChunkTargetObservation(val logicalKey: CiphertextObjectKey,
    val logicalDescriptor: CiphertextDescriptor, val logicalOperation: SyncStoreId, val manifest: LanSyncGroupChunkManifestLocator,
    val chunkCount: Long, val observedAtMillis: Long)
@Serializable data class LanSyncGroupChunkTargetObjectReceipt(val objectRequest: LanSyncGroupTargetObject,
    val appendObservation: LanSyncGroupChunkTargetObservation, val readbackObservation: LanSyncGroupChunkTargetObservation)
/** Authenticated historical assembly selection, distinct from whole-object/strict/native receipts. */
@Serializable data class LanSyncGroupChunkTargetReceipt(val planHash: String, val target: SyncDeliveryTarget,
    val checkpoint: SyncVersionRef, val objects: List<LanSyncGroupChunkTargetObjectReceipt>)

/**
 * Daily, non-signing relay of a complete frozen admitted selection. Raw quarantine never becomes a
 * strict target's canonical object. All resource/transport I/O runs outside Node/native admission.
 * The caller must keep target.id bound to one actual route/account; provider configuration owns that
 * endpoint binding. Strict stores and weak unique attempts retain separate historical receipt types;
 * neither receipt is freshness. Every selection still authenticates its complete local dependency set.
 */
class LanSyncGroupTargetPublication internal constructor(private val root: Path,
    private val target: SyncDeliveryTarget, private val proof: LanSyncGroupTargetExport) {
    @Serializable private data class Selection(val group: SyncStoreId, val checkpoint: SyncVersionRef,
        val target: SyncDeliveryTarget, val vault: SyncManagementVaultContext, val vaultDescriptor: CiphertextDescriptor,
        val versions: Set<SyncVersionRef>)
    @Serializable private data class Binding(val group: SyncStoreId, val genesis: SyncVersionRef, val device: String,
        val target: SyncDeliveryTarget)
    @Serializable private data class ItemBinding(val owner: Binding, val key: CiphertextObjectKey,
        val descriptor: CiphertextDescriptor, val operation: SyncStoreId)
    @Serializable private data class ItemObservation(val binding: ItemBinding, val selectionId: String,
        val planHash: String, val receiptHash: String, val strict: LanSyncGroupTargetObjectReceipt? = null,
        val weak: LanSyncGroupWeakTargetObjectReceipt? = null)
    @Serializable private data class ChunkItemObservation(val binding: ItemBinding, val selectionId: String,
        val planHash: String, val receiptHash: String, val observation: LanSyncGroupChunkTargetObjectReceipt)
    @Serializable private data class CloudItemObservation(val binding: ItemBinding, val selectionId: String,
        val planHash: String, val receiptHash: String, val observation: LanSyncGroupCloudTargetObjectReceipt)
    private val lock = locks.computeIfAbsent(root) { ReentrantLock() }
    private fun directory(name: String) = root.resolve(name).also {
        PrivateSyncFile.checked(root, true)
        if (!Files.exists(it, NOFOLLOW_LINKS)) Files.createDirectory(it)
        PrivateSyncFile.checked(it, true)
    }
    private val work = directory("preparations")
    private val retained = FileCiphertextStore(directory("retained"))
    private val itemBindings = FileCiphertextStore(directory("item-bindings"))
    private val strictObservations = FileCiphertextStore(directory("strict-object-observations"))
    private val weakObservations = FileCiphertextStore(directory("weak-object-observations"))
    private val chunkObservations = FileCiphertextStore(directory("chunk-object-observations"))
    private val cloudObservations = FileCiphertextStore(directory("cloud-object-observations"))
    private val ownerBinding = Binding(proof.trust.group, proof.trust.genesis, proof.device, target)
    private val selection = Selection(proof.trust.group, proof.current.reference(), target, proof.vault,
        proof.vaultDescriptor, proof.versions.mapTo(linkedSetOf()) { it.reference() })
    private val id = lanSyncHash(lanSyncJson.encodeToJsonElement(Selection.serializer(), selection))
    private val planPath = root.resolve("plan-$id.json")
    private val receiptPath = root.resolve("receipt-$id.json")
    private val weakReceiptPath = root.resolve("weak-receipt-$id.json")
    private val chunkReceiptPath = root.resolve("chunk-receipt-$id.json")
    private val cloudReceiptPath = root.resolve("cloud-receipt-$id.json")
    init {
        require(target != SyncDeliveryTarget.LEGACY) { "SYNC_GROUP_TARGET_EXPLICIT_ROUTE_REQUIRED" }
        guarded {
            val path = root.resolve("target.json")
            val expected = ownerBinding
            val prior = LanSyncJsonFiles.read(path, Binding.serializer())
            if (prior == null) LanSyncJsonFiles.write(path, Binding.serializer(), expected)
            else require(prior == expected) { "SYNC_GROUP_TARGET_ROUTE_CHANGED" }
        }
    }
    /** Completes and reauthenticates local preparation; a plan is never a target receipt. */
    suspend fun prepare(): LanSyncGroupTargetPlan = runInterruptible(Dispatchers.IO) { guarded {
        proof.requireLive()
        val prior = readPlan()
        if (prior != null) { validate(prior, retained); ensureBindings(prior); return@guarded prior }
        val stages = mutableListOf<LanSyncGroupTargetStage>()
        val pool = LanSyncGroupTargetStagePool(work, proof::requireLive)
        fun borrow(metadata: CiphertextObjectMetadata) = pool.stage(metadata, proof.source)
        try {
            val items = mutableListOf<LanSyncGroupTargetObject>()
            fun add(role: LanSyncGroupTargetObjectRole, slot: String, stage: LanSyncGroupTargetStage) {
                stages += stage
                pool.pin(stage)
                items += LanSyncGroupTargetObject(role, slot, stage.metadata.key, stage.metadata.descriptor,
                    itemBinding(stage.metadata.key, stage.metadata.descriptor).operation)
            }
            add(LanSyncGroupTargetObjectRole.VAULT, "vault", borrow(CiphertextObjectMetadata(proof.vault.key, proof.vaultDescriptor)))
            var rootFound = false
            val slots = linkedSetOf<String>(); val notices = linkedSetOf<String>()
            for (metadata in inventory()) {
                proof.requireLive()
                var candidate: LanSyncGroupTargetStage? = null
                try {
                    candidate = borrow(metadata)
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
                finally { candidate?.let(pool::discard) }
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
                        val metadata = borrow(witness.metadata); trial += metadata
                        val actual = SyncGroupObjectCodec(work).read(lanSyncTargetLens(trial, proof::requireLive), proof.cipher(proof.current.epoch), witness.context,
                            witness.metadata.descriptor, version.reference())
                        proof.version(actual)
                        actual.envelope.blobs.values.forEach { binding -> trial += borrow(CiphertextObjectMetadata(binding.key, binding.descriptor)) }
                        proof.dependencies(listOf(actual), lanSyncTargetLens(trial, proof::requireLive), work, pool.checkedRaw)
                        add(LanSyncGroupTargetObjectRole.VERSION, version.id.value, trial.first())
                        trial.drop(1).forEach { add(LanSyncGroupTargetObjectRole.RESOURCE, it.metadata.key.id.value, it) }
                        trial.clear(); selected = true; break
                    } catch (failure: Exception) { lanSyncTargetCandidateFailure(failure) }
                    finally { trial.asReversed().forEach(pool::discard) }
                }
                require(selected) { "SYNC_GROUP_TARGET_VERSION_INCOMPLETE" }
            }
            // A strict destination cannot represent two selected authenticated descriptors for one key.
            require(items.groupBy { it.key }.values.all { rows -> rows.map { it.descriptor }.toSet().size == 1 }) { "SYNC_GROUP_TARGET_DESCRIPTOR_CONFLICT" }
            val unique = items.distinctBy { it.key }.sortedWith(compareBy({ it.role.ordinal }, { it.key.id.value }))
            val plan = LanSyncGroupTargetPlan(proof.trust.group, proof.current.reference(), proof.current.epoch,
                proof.current.keyCommitment, target, selection.versions, unique)
            validate(plan, lanSyncTargetLens(stages, proof::requireLive), pool)
            for (item in unique) {
                proof.requireLive()
                val actual = stages.first { it.metadata == CiphertextObjectMetadata(item.key, item.descriptor) }
                Files.newInputStream(actual.file.path, NOFOLLOW_LINKS).use {
                    retained.put(CiphertextPutRequest(item.key, item.operation, item.descriptor), liveInput(it))
                }
            }
            proof.requireLive(); LanSyncJsonFiles.write(planPath, LanSyncGroupTargetPlan.serializer(), plan)
            plan
        } finally { pool.close() }
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
        ensureBindings(plan)
        readReceipt(plan)?.let { cacheSelection(plan, strict = it); return@guarded it }
        val origins = mutableMapOf<String, HistoricalSelection>()
        val cachedItems = plan.objects.associateWith { readObservation(it, weak = false, origins = origins) }
        val observations = mutableListOf<LanSyncGroupTargetObjectReceipt>()
        val readbacks = mutableListOf<LanSyncGroupTargetStage>()
        val historical = linkedSetOf<CiphertextObjectMetadata>()
        try {
            for (item in plan.objects) {
                proof.requireLive()
                val metadata = CiphertextObjectMetadata(item.key, item.descriptor)
                val cached = cachedItems[item]?.strict
                if (cached != null) {
                    historical += metadata
                    observations += cached.copy(objectRequest = item)
                    continue
                }
                val request = CiphertextPutRequest(item.key, item.operation, item.descriptor)
                val staged = stage(metadata, retained)
                try {
                    val result = Files.newInputStream(staged.file.path, NOFOLLOW_LINKS).use { backend.put(request, liveInput(it)) }
                    require(result.metadata == staged.metadata) { "SYNC_GROUP_TARGET_BACKEND_RECEIPT_CHANGED" }
                    readbacks += lanSyncTargetStage(work, backend, staged.metadata, proof::requireLive)
                    observations += LanSyncGroupTargetObjectReceipt(item, result.metadata.key, result.metadata.descriptor,
                        result.disposition.name, System.currentTimeMillis())
                } finally { staged.close() }
                proof.requireLive()
            }
            validate(plan, lanSyncTargetObservedLens(readbacks, historical, retained, proof::requireLive))
            val receipt = LanSyncGroupTargetReceipt(planHash(), target, plan.checkpoint, observations.toList())
            proof.requireLive(); LanSyncJsonFiles.write(receiptPath, LanSyncGroupTargetReceipt.serializer(), receipt)
            cacheSelection(plan, strict = receipt)
            receipt
        } finally { readbacks.asReversed().forEach { it.close() } }
    } }
    /** Unique-attempt delivery does not touch any strict/Outbox uploaded receipt. */
    suspend fun upload(backend: WeakWebDavAttemptStore): LanSyncGroupWeakTargetReceipt = runInterruptible(Dispatchers.IO) { guarded {
        proof.requireLive()
        require(target.kind == SyncDeliveryKind.CLOUD_STORE && backend.routeId == target.id) { "SYNC_GROUP_TARGET_ROUTE_CHANGED" }
        val plan = requireNotNull(readPlan()) { "SYNC_GROUP_TARGET_PLAN_MISSING" }
        validate(plan, retained)
        ensureBindings(plan)
        readWeakReceipt(plan)?.let { cacheSelection(plan, weak = it); return@guarded it }
        val origins = mutableMapOf<String, HistoricalSelection>()
        val cachedItems = plan.objects.associateWith { readObservation(it, weak = true, origins = origins) }
        val observations = mutableListOf<LanSyncGroupWeakTargetObjectReceipt>()
        val readbacks = mutableListOf<LanSyncGroupTargetStage>()
        val historical = linkedSetOf<CiphertextObjectMetadata>()
        try {
            for (item in plan.objects) {
                proof.requireLive()
                val metadata = CiphertextObjectMetadata(item.key, item.descriptor)
                val cached = cachedItems[item]?.weak
                if (cached != null) {
                    historical += metadata
                    observations += cached.copy(objectRequest = item)
                    continue
                }
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
            validate(plan, lanSyncTargetObservedLens(readbacks, historical, retained, proof::requireLive))
            val receipt = LanSyncGroupWeakTargetReceipt(planHash(), target, plan.checkpoint, observations.toList())
            proof.requireLive(); LanSyncJsonFiles.write(weakReceiptPath, LanSyncGroupWeakTargetReceipt.serializer(), receipt)
            cacheSelection(plan, weak = receipt)
            receipt
        } finally { readbacks.asReversed().forEach { it.close() } }
    } }
    /** Physical chunks have independent receipts; the complete original ciphertext is authenticated. */
    suspend fun upload(backend: WeakWebDavChunkAssembly): LanSyncGroupChunkTargetReceipt = runInterruptible(Dispatchers.IO) { guarded {
        proof.requireLive()
        require(target.kind == SyncDeliveryKind.CLOUD_STORE && backend.routeId == target.id) { "SYNC_GROUP_TARGET_ROUTE_CHANGED" }
        val plan = requireNotNull(readPlan()) { "SYNC_GROUP_TARGET_PLAN_MISSING" }
        validate(plan, retained); ensureBindings(plan)
        readChunkReceipt(plan)?.let { cacheChunkSelection(plan, it); return@guarded it }
        val origins = mutableMapOf<String, HistoricalSelection>()
        val cachedItems = plan.objects.associateWith { readChunkObservation(it, origins) }
        val observations = mutableListOf<LanSyncGroupChunkTargetObjectReceipt>()
        val readbacks = mutableListOf<LanSyncGroupTargetStage>()
        val historical = linkedSetOf<CiphertextObjectMetadata>()
        try {
            for (item in plan.objects) {
                proof.requireLive()
                val metadata = CiphertextObjectMetadata(item.key, item.descriptor)
                val cached = cachedItems[item]?.observation
                if (cached != null) {
                    historical += metadata; observations += cached.copy(objectRequest = item); continue
                }
                val staged = stage(metadata, retained)
                val appended = try {
                    Files.newInputStream(staged.file.path, NOFOLLOW_LINKS).use {
                        backend.append(CiphertextPutRequest(item.key, item.operation, item.descriptor), liveInput(it), proof::requireLive)
                    }
                } finally { staged.close() }
                requireAssembly(item, appended)
                var observed: WeakWebDavAssemblyObservation? = null
                readbacks += lanSyncTargetReceiveStage(work, metadata, proof::requireLive) { output ->
                    val actual = backend.readAssembly(appended.assembly, metadata, output, proof::requireLive)
                        ?: return@lanSyncTargetReceiveStage null
                    requireAssembly(item, actual)
                    require(actual.assembly == appended.assembly && actual.chunkCount == appended.chunkCount) { "SYNC_GROUP_TARGET_ASSEMBLY_CHANGED" }
                    observed = actual; metadata
                }
                observations += LanSyncGroupChunkTargetObjectReceipt(item, chunkObservation(appended), chunkObservation(requireNotNull(observed)))
                proof.requireLive()
            }
            validate(plan, lanSyncTargetObservedLens(readbacks, historical, retained, proof::requireLive))
            val receipt = LanSyncGroupChunkTargetReceipt(planHash(), target, plan.checkpoint, observations.toList())
            proof.requireLive(); LanSyncJsonFiles.write(chunkReceiptPath, LanSyncGroupChunkTargetReceipt.serializer(), receipt)
            cacheChunkSelection(plan, receipt)
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
    fun chunkReceipt(): LanSyncGroupChunkTargetReceipt? = guarded {
        proof.requireLive()
        val plan = readPlan() ?: run {
            require(!Files.exists(chunkReceiptPath, NOFOLLOW_LINKS)) { "SYNC_GROUP_TARGET_PLAN_MISSING" }; return@guarded null
        }
        readChunkReceipt(plan)
    }
    /** Probe only the current route; existing objects keep their actual foreign publication identity. */
    suspend fun uploadCloud(attempts: WeakWebDavAttemptStore, parts: WeakWebDavChunkAssembly): LanSyncGroupCloudTargetReceipt =
        runInterruptible(Dispatchers.IO) { guarded {
            proof.requireLive()
            require(target.kind == SyncDeliveryKind.CLOUD_STORE && attempts.routeId == target.id && parts.routeId == target.id) {
                "SYNC_GROUP_TARGET_ROUTE_CHANGED"
            }
            val plan = requireNotNull(readPlan()) { "SYNC_GROUP_TARGET_PLAN_MISSING" }
            validate(plan, retained); ensureBindings(plan)
            readCloudReceipt(plan)?.let { cacheCloudSelection(plan, it); return@guarded it }
            val origins = mutableMapOf<String, CloudHistoricalSelection>()
            val chunkOrigins = mutableMapOf<String, HistoricalSelection>(); val wholeOrigins = mutableMapOf<String, HistoricalSelection>()
            // Verify every existing derived index before any provider I/O, including older modes.
            val cached = plan.objects.associateWith { item ->
                val cloud = readCloudObservation(item, origins)?.observation
                val chunk = readChunkObservation(item, chunkOrigins)?.observation
                val whole = readObservation(item, weak = true, origins = wholeOrigins)?.weak
                cloud ?: chunk?.let { LanSyncGroupCloudTargetObjectReceipt(item, LanSyncGroupCloudTargetEvidence.Appended(
                    LanSyncGroupCloudTargetObservation.Chunks(it.appendObservation), LanSyncGroupCloudTargetObservation.Chunks(it.readbackObservation))) }
                    ?: whole?.let { LanSyncGroupCloudTargetObjectReceipt(item, LanSyncGroupCloudTargetEvidence.Appended(
                        LanSyncGroupCloudTargetObservation.Whole(it.appendObservation), LanSyncGroupCloudTargetObservation.Whole(it.readbackObservation))) }
            }
            val readbacks = mutableListOf<LanSyncGroupTargetStage>()
            val historical = linkedSetOf<CiphertextObjectMetadata>()
            val rows = mutableListOf<LanSyncGroupCloudTargetObjectReceipt>()
            var rejected = 0
            fun reject() { if (rejected < Int.MAX_VALUE) rejected++ }
            try {
                for (item in plan.objects) {
                    proof.requireLive()
                    val metadata = CiphertextObjectMetadata(item.key, item.descriptor)
                    cached[item]?.let { row -> historical += metadata; rows += row.copy(objectRequest = item) } ?: run {
                        val existing = probeCloudObject(item, attempts, parts, ::reject)
                        if (existing != null) {
                            readbacks += existing.first
                            rows += LanSyncGroupCloudTargetObjectReceipt(item, LanSyncGroupCloudTargetEvidence.Existing(existing.second))
                        } else {
                            val staged = stage(metadata, retained)
                            val appended = try { Files.newInputStream(staged.file.path, NOFOLLOW_LINKS).use {
                                parts.append(CiphertextPutRequest(item.key, item.operation, item.descriptor), liveInput(it), proof::requireLive)
                            } } finally { staged.close() }
                            requireAssembly(item, appended)
                            var observed: WeakWebDavAssemblyObservation? = null
                            readbacks += lanSyncTargetReceiveStage(work, metadata, proof::requireLive) { output ->
                                val actual = parts.readAssembly(appended.assembly, metadata, output, proof::requireLive)
                                    ?: throw CiphertextStoreException(CiphertextStoreFailure.TRANSIENT)
                                requireAssembly(item, actual)
                                require(actual.assembly == appended.assembly && actual.chunkCount == appended.chunkCount) { "SYNC_GROUP_TARGET_ASSEMBLY_CHANGED" }
                                observed = actual; metadata
                            }
                            rows += LanSyncGroupCloudTargetObjectReceipt(item, LanSyncGroupCloudTargetEvidence.Appended(
                                LanSyncGroupCloudTargetObservation.Chunks(chunkObservation(appended)),
                                LanSyncGroupCloudTargetObservation.Chunks(chunkObservation(requireNotNull(observed)))))
                        }
                    }
                }
                validate(plan, lanSyncTargetObservedLens(readbacks, historical, retained, proof::requireLive))
                rows.forEach(::requireCloudRow)
                val receipt = LanSyncGroupCloudTargetReceipt(planHash(), target, plan.checkpoint, rows.toList(), rejected)
                proof.requireLive(); LanSyncJsonFiles.write(cloudReceiptPath, LanSyncGroupCloudTargetReceipt.serializer(), receipt)
                cacheCloudSelection(plan, receipt)
                receipt
            } finally { readbacks.asReversed().forEach { it.close() } }
        } }

    private class MissingCloudCandidate : RuntimeException(null, null, false, false)
    private fun probeCloudObject(item: LanSyncGroupTargetObject, attempts: WeakWebDavAttemptStore,
        parts: WeakWebDavChunkAssembly, reject: () -> Unit): Pair<LanSyncGroupTargetStage, LanSyncGroupCloudTargetObservation>? {
        val metadata = CiphertextObjectMetadata(item.key, item.descriptor)
        var cursor: WeakWebDavCursor? = null; val seen = hashSetOf<String>()
        do {
            proof.requireLive(); cursor?.let { require(seen.add(it.token)) { "SYNC_GROUP_TARGET_LISTING_CHANGED" } }
            val page = attempts.listCandidates(item.key, cursor, checkActive = proof::requireLive)
            for (candidate in page.entries) {
                if (candidate.descriptor != item.descriptor) { reject(); continue }
                var observed: WeakWebDavObservation? = null
                val file = try { lanSyncTargetReceiveStage(work, metadata, proof::requireLive) { output ->
                    val actual = attempts.readCandidate(candidate, output, proof::requireLive) ?: throw MissingCloudCandidate()
                    require(actual.candidate == candidate) { "SYNC_GROUP_TARGET_ATTEMPT_CHANGED" }
                    observed = actual; metadata
                } } catch (_: MissingCloudCandidate) { reject(); continue }
                catch (failure: CiphertextStoreException) {
                    if (failure.failure != CiphertextStoreFailure.INTEGRITY) throw failure
                    reject(); continue
                }
                return file to LanSyncGroupCloudTargetObservation.Whole(observation(requireNotNull(observed)))
            }
            cursor = page.nextCursor
        } while (cursor != null)
        seen.clear()
        do {
            proof.requireLive(); cursor?.let { require(seen.add(it.token)) { "SYNC_GROUP_TARGET_LISTING_CHANGED" } }
            val page = parts.listAssemblies(item.key, cursor, checkActive = proof::requireLive)
            for (candidate in page.entries) {
                val header = try { parts.inspectAssembly(candidate, proof::requireLive) }
                catch (failure: CiphertextStoreException) {
                    if (failure.failure != CiphertextStoreFailure.INTEGRITY) throw failure
                    reject(); continue
                }
                if (header == null) { reject(); continue }
                if (header.originalRequest.key != item.key || header.originalRequest.descriptor != item.descriptor) { reject(); continue }
                var observed: WeakWebDavAssemblyObservation? = null
                val file = try { lanSyncTargetReceiveStage(work, metadata, proof::requireLive) { output ->
                    val actual = parts.readAssembly(candidate, metadata, output, proof::requireLive) ?: throw MissingCloudCandidate()
                    require(actual.assembly == candidate && actual.request == header.originalRequest && actual.chunkCount == header.chunkCount) {
                        "SYNC_GROUP_TARGET_ASSEMBLY_CHANGED"
                    }
                    observed = actual; metadata
                } } catch (_: MissingCloudCandidate) { reject(); continue }
                catch (failure: CiphertextStoreException) {
                    if (failure.failure != CiphertextStoreFailure.INTEGRITY) throw failure
                    reject(); continue
                }
                return file to LanSyncGroupCloudTargetObservation.Chunks(chunkObservation(requireNotNull(observed)))
            }
            cursor = page.nextCursor
        } while (cursor != null)
        proof.requireLive()
        return null
    }
    fun cloudReceipt(): LanSyncGroupCloudTargetReceipt? = guarded {
        proof.requireLive()
        val plan = readPlan() ?: run {
            require(!Files.exists(cloudReceiptPath, NOFOLLOW_LINKS)) { "SYNC_GROUP_TARGET_PLAN_MISSING" }; return@guarded null
        }
        readCloudReceipt(plan)
    }
    /** Rebuilds missing derived indexes from typed history; no ciphertext or network I/O. */
    fun recoverObjectObservations(): Boolean = guarded {
        proof.requireLive()
        val plan = readPlan() ?: return@guarded false
        val strict = readReceipt(plan); val weak = readWeakReceipt(plan); val chunk = readChunkReceipt(plan); val cloud = readCloudReceipt(plan)
        if (strict == null && weak == null && chunk == null && cloud == null) return@guarded false
        ensureBindings(plan)
        strict?.let { cacheSelection(plan, strict = it) }
        weak?.let { cacheSelection(plan, weak = it) }
        chunk?.let { cacheChunkSelection(plan, it) }
        cloud?.let { cacheCloudSelection(plan, it) }
        proof.requireLive()
        true
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
    private fun validate(plan: LanSyncGroupTargetPlan, source: CiphertextStore, shared: LanSyncGroupTargetStagePool? = null) {
        proof.requireLive()
        validateHeader(plan)
        val staged = mutableListOf<LanSyncGroupTargetStage>()
        val pool = shared ?: LanSyncGroupTargetStagePool(work, proof::requireLive)
        try {
            plan.objects.forEach { staged += pool.stage(CiphertextObjectMetadata(it.key, it.descriptor), source) }
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
            proof.dependencies(values, lens, work, pool.checkedRaw)
        } finally { if (shared == null) pool.close() }
        proof.requireLive()
    }
    private fun validateHeader(plan: LanSyncGroupTargetPlan) {
        require(plan.group == selection.group && plan.target == target && plan.checkpoint == selection.checkpoint &&
            plan.epoch == proof.current.epoch && plan.commitment == proof.current.keyCommitment && plan.versions == selection.versions &&
            plan.objects.map { it.key }.toSet().size == plan.objects.size && plan.objects.map { it.operation }.toSet().size == plan.objects.size &&
            plan.objects == plan.objects.sortedWith(compareBy({ it.role.ordinal }, { it.key.id.value }))) { "SYNC_GROUP_TARGET_PLAN_CHANGED" }
        for (item in plan.objects) readItem(itemBindings, item.key, ItemBinding.serializer())?.let { requireItemBinding(it, item) }
    }
    private fun readPlan(): LanSyncGroupTargetPlan? {
        if (!Files.exists(planPath, NOFOLLOW_LINKS)) return null
        LanSyncMetadataBudget().validate(planPath)
        return requireNotNull(LanSyncJsonFiles.read(planPath, LanSyncGroupTargetPlan.serializer())).also(::validateHeader)
    }
    private fun planHash() = LanSyncResourceStore.hashFile(planPath, Files.size(planPath))
    private fun readReceipt(plan: LanSyncGroupTargetPlan, path: Path = receiptPath, hash: String = planHash()): LanSyncGroupTargetReceipt? {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        LanSyncMetadataBudget().validate(path)
        return requireNotNull(LanSyncJsonFiles.read(path, LanSyncGroupTargetReceipt.serializer())).also { receipt ->
            require(receipt.planHash == hash && receipt.target == target && receipt.checkpoint == plan.checkpoint &&
                receipt.objects.map { it.objectRequest } == plan.objects && receipt.objects.all {
                    it.returnedKey == it.objectRequest.key && it.returnedDescriptor == it.objectRequest.descriptor &&
                        it.disposition in CiphertextPutDisposition.entries.map { value -> value.name } &&
                        (it.observedAtMillis == null || it.observedAtMillis >= 0)
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
    private fun readWeakReceipt(plan: LanSyncGroupTargetPlan, path: Path = weakReceiptPath, hash: String = planHash()): LanSyncGroupWeakTargetReceipt? {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        LanSyncMetadataBudget().validate(path)
        return requireNotNull(LanSyncJsonFiles.read(path, LanSyncGroupWeakTargetReceipt.serializer())).also { receipt ->
            require(receipt.planHash == hash && receipt.target == target && receipt.checkpoint == plan.checkpoint &&
                receipt.objects.map { it.objectRequest } == plan.objects && receipt.objects.all { item ->
                    val one = item.appendObservation; val two = item.readbackObservation
                    fun matches(value: LanSyncGroupWeakTargetObservation) = value.route == target.id && value.key == item.objectRequest.key &&
                        value.descriptor == item.objectRequest.descriptor && value.operation == item.objectRequest.operation &&
                        value.origin in WeakWebDavObservationOrigin.entries.map { it.name } && value.observedAtMillis >= 0
                    matches(one) && matches(two) && one.attempt == two.attempt &&
                        two.origin == WeakWebDavObservationOrigin.EXISTING_ATTEMPT_READBACK.name
                }) { "SYNC_GROUP_TARGET_RECEIPT_CHANGED" }
        }
    }
    private fun requireAssembly(item: LanSyncGroupTargetObject, value: WeakWebDavAssemblyObservation) {
        require(value.request == CiphertextPutRequest(item.key, item.operation, item.descriptor) &&
            value.assembly.route == target.id && value.assembly.key == item.key && value.chunkCount >= 0 &&
            (value.chunkCount == 0L) == (item.descriptor.length == 0L)) { "SYNC_GROUP_TARGET_ASSEMBLY_CHANGED" }
    }
    private fun chunkObservation(value: WeakWebDavAssemblyObservation) = LanSyncGroupChunkTargetObservation(
        value.request.key, value.request.descriptor, value.request.operationId,
        LanSyncGroupChunkManifestLocator(value.assembly.route, value.assembly.key, value.assembly.descriptor,
            value.assembly.operationId, value.assembly.attempt), value.chunkCount, value.observedAtMillis)
    private fun readChunkReceipt(plan: LanSyncGroupTargetPlan, path: Path = chunkReceiptPath,
        hash: String = planHash()): LanSyncGroupChunkTargetReceipt? {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        LanSyncMetadataBudget().validate(path)
        return requireNotNull(LanSyncJsonFiles.read(path, LanSyncGroupChunkTargetReceipt.serializer())).also { receipt ->
            require(receipt.planHash == hash && receipt.target == target && receipt.checkpoint == plan.checkpoint &&
                receipt.objects.map { it.objectRequest } == plan.objects && receipt.objects.all { row ->
                    fun matches(value: LanSyncGroupChunkTargetObservation) = value.logicalKey == row.objectRequest.key &&
                        value.logicalDescriptor == row.objectRequest.descriptor && value.logicalOperation == row.objectRequest.operation &&
                        value.manifest.route == target.id && value.manifest.manifestKey == row.objectRequest.key &&
                        value.manifest.manifestDescriptor.length > 0 && value.chunkCount >= 0 && value.observedAtMillis >= 0 &&
                        (value.chunkCount == 0L) == (row.objectRequest.descriptor.length == 0L)
                    matches(row.appendObservation) && matches(row.readbackObservation) &&
                        row.appendObservation.manifest == row.readbackObservation.manifest &&
                        row.appendObservation.chunkCount == row.readbackObservation.chunkCount
                }) { "SYNC_GROUP_TARGET_RECEIPT_CHANGED" }
        }
    }
    private fun itemBinding(key: CiphertextObjectKey, descriptor: CiphertextDescriptor, operation: SyncStoreId? = null): ItemBinding {
        val prior = readItem(itemBindings, key, ItemBinding.serializer())
        if (prior != null) {
            require(prior.owner == ownerBinding && prior.key == key && prior.descriptor == descriptor) { "SYNC_GROUP_TARGET_ITEM_BINDING_CHANGED" }
            require(operation == null || prior.operation == operation) { "SYNC_GROUP_TARGET_OPERATION_CHANGED" }
            return prior
        }
        val binding = ItemBinding(ownerBinding, key, descriptor, operation ?: SyncStoreId.random())
        writeItem(itemBindings, key, "ITEM-BINDING", ItemBinding.serializer(), binding)
        return binding
    }
    private fun requireItemBinding(binding: ItemBinding, item: LanSyncGroupTargetObject) {
        require(binding.owner == ownerBinding && binding.key == item.key && binding.descriptor == item.descriptor &&
            binding.operation == item.operation) { "SYNC_GROUP_TARGET_ITEM_BINDING_CHANGED" }
    }
    private fun ensureBindings(plan: LanSyncGroupTargetPlan) {
        for (item in plan.objects) { proof.requireLive(); requireItemBinding(itemBinding(item.key, item.descriptor, item.operation), item) }
    }
    private class HistoricalSelection(val plan: LanSyncGroupTargetPlan, val planHash: String, val receiptHash: String,
        val strict: LanSyncGroupTargetReceipt?, val weak: LanSyncGroupWeakTargetReceipt?, val chunk: LanSyncGroupChunkTargetReceipt? = null)
    private fun readObservation(item: LanSyncGroupTargetObject, weak: Boolean,
        origins: MutableMap<String, HistoricalSelection>): ItemObservation? {
        proof.requireLive()
        val cache = readItem(if (weak) weakObservations else strictObservations, item.key, ItemObservation.serializer())
            ?: recoverObservation(item, weak, origins) ?: return null
        requireItemBinding(cache.binding, item)
        require((cache.weak != null) == weak && (cache.strict != null) != weak &&
            cache.selectionId.matches(Regex("[a-f0-9]{64}")) && cache.planHash.matches(Regex("[a-f0-9]{64}")) &&
            cache.receiptHash.matches(Regex("[a-f0-9]{64}"))) { "SYNC_GROUP_TARGET_OBJECT_OBSERVATION_CHANGED" }
        val origin = origins.getOrPut(cache.selectionId) { historicalSelection(cache.selectionId, weak) }
        require(origin.planHash == cache.planHash && origin.receiptHash == cache.receiptHash) { "SYNC_GROUP_TARGET_OBJECT_ORIGIN_CHANGED" }
        val original = origin.plan.objects.singleOrNull { it.key == item.key && it.descriptor == item.descriptor && it.operation == item.operation }
        val exact = if (weak) origin.weak?.objects?.singleOrNull { it.objectRequest == original } == cache.weak
            else origin.strict?.objects?.singleOrNull { it.objectRequest == original } == cache.strict
        require(original != null && exact) { "SYNC_GROUP_TARGET_OBJECT_OBSERVATION_CHANGED" }
        proof.requireLive()
        return cache
    }
    private fun historicalSelection(selectionId: String, weak: Boolean, chunk: Boolean = false): HistoricalSelection {
        require(selectionId.matches(Regex("[a-f0-9]{64}"))) { "SYNC_GROUP_TARGET_OBJECT_ORIGIN_CHANGED" }
        val path = root.resolve("plan-$selectionId.json")
        require(Files.exists(path, NOFOLLOW_LINKS)) { "SYNC_GROUP_TARGET_OBJECT_ORIGIN_MISSING" }
        LanSyncMetadataBudget().validate(path)
        val plan = requireNotNull(LanSyncJsonFiles.read(path, LanSyncGroupTargetPlan.serializer())) { "SYNC_GROUP_TARGET_OBJECT_ORIGIN_MISSING" }
        require(plan.group == ownerBinding.group && plan.target == target && plan.objects.map { it.key }.toSet().size == plan.objects.size &&
            plan.objects.map { it.operation }.toSet().size == plan.objects.size) { "SYNC_GROUP_TARGET_OBJECT_ORIGIN_CHANGED" }
        val receipt = root.resolve("${if (chunk) "chunk-receipt" else if (weak) "weak-receipt" else "receipt"}-$selectionId.json")
        require(Files.exists(receipt, NOFOLLOW_LINKS)) { "SYNC_GROUP_TARGET_OBJECT_ORIGIN_MISSING" }
        val planHash = LanSyncResourceStore.hashFile(path, Files.size(path))
        LanSyncMetadataBudget().validate(receipt)
        val receiptHash = LanSyncResourceStore.hashFile(receipt, Files.size(receipt))
        return HistoricalSelection(plan, planHash, receiptHash,
            if (weak || chunk) null else requireNotNull(readReceipt(plan, receipt, planHash)) { "SYNC_GROUP_TARGET_OBJECT_ORIGIN_MISSING" },
            if (weak && !chunk) requireNotNull(readWeakReceipt(plan, receipt, planHash)) { "SYNC_GROUP_TARGET_OBJECT_ORIGIN_MISSING" } else null,
            if (chunk) requireNotNull(readChunkReceipt(plan, receipt, planHash)) { "SYNC_GROUP_TARGET_OBJECT_ORIGIN_MISSING" } else null)
    }
    private fun recoverObservation(item: LanSyncGroupTargetObject, weak: Boolean,
        origins: MutableMap<String, HistoricalSelection>): ItemObservation? {
        val prefix = if (weak) "weak-receipt-" else "receipt-"
        var conflict: CiphertextStoreException? = null
        Files.newDirectoryStream(root, "$prefix*.json").use { files -> for (file in files) {
            proof.requireLive()
            val originId = file.fileName.toString().removePrefix(prefix).removeSuffix(".json")
            val origin = origins.getOrPut(originId) { historicalSelection(originId, weak) }
            val original = origin.plan.objects.singleOrNull { it.key == item.key && it.descriptor == item.descriptor && it.operation == item.operation } ?: continue
            val strict = origin.strict?.objects?.single { it.objectRequest == original }
            val observed = origin.weak?.objects?.single { it.objectRequest == original }
            val cache = ItemObservation(itemBinding(item.key, item.descriptor, item.operation), originId,
                origin.planHash, origin.receiptHash, strict, observed)
            try {
                writeItem(if (weak) weakObservations else strictObservations, item.key, "OBJECT-OBSERVATION", ItemObservation.serializer(), cache)
                proof.requireLive(); return cache
            } catch (failure: CiphertextStoreException) {
                // A forced derived-index intent may precede its object. Only the exact original
                // authenticated receipt can complete that intent; never overwrite or remint it.
                if (failure.failure != CiphertextStoreFailure.OPERATION_CONFLICT) throw failure
                conflict = failure
            }
        } }
        conflict?.let { throw it }
        return null
    }
    /** Only called after complete actual-readback authentication and durable typed selection receipt. */
    private fun cacheSelection(plan: LanSyncGroupTargetPlan, strict: LanSyncGroupTargetReceipt? = null,
        weak: LanSyncGroupWeakTargetReceipt? = null) {
        require((strict == null) != (weak == null)) { "SYNC_GROUP_TARGET_RECEIPT_CHANGED" }
        val isWeak = weak != null
        val receipt = if (isWeak) weakReceiptPath else receiptPath
        val hash = LanSyncResourceStore.hashFile(receipt, Files.size(receipt))
        val frozenPlanHash = planHash()
        val origins = mutableMapOf<String, HistoricalSelection>()
        for ((index, item) in plan.objects.withIndex()) {
            proof.requireLive()
            if (readObservation(item, isWeak, origins) != null) continue
            val binding = itemBinding(item.key, item.descriptor, item.operation)
            val cache = ItemObservation(binding, id, frozenPlanHash, hash, strict?.objects?.get(index), weak?.objects?.get(index))
            writeItem(if (isWeak) weakObservations else strictObservations, item.key, "OBJECT-OBSERVATION", ItemObservation.serializer(), cache)
        }
        proof.requireLive()
    }
    private fun readChunkObservation(item: LanSyncGroupTargetObject,
        origins: MutableMap<String, HistoricalSelection>): ChunkItemObservation? {
        proof.requireLive()
        val cache = readItem(chunkObservations, item.key, ChunkItemObservation.serializer())
            ?: recoverChunkObservation(item, origins) ?: return null
        requireItemBinding(cache.binding, item)
        require(cache.selectionId.matches(Regex("[a-f0-9]{64}")) && cache.planHash.matches(Regex("[a-f0-9]{64}")) &&
            cache.receiptHash.matches(Regex("[a-f0-9]{64}"))) { "SYNC_GROUP_TARGET_OBJECT_OBSERVATION_CHANGED" }
        val origin = origins.getOrPut(cache.selectionId) { historicalSelection(cache.selectionId, weak = false, chunk = true) }
        require(origin.planHash == cache.planHash && origin.receiptHash == cache.receiptHash) { "SYNC_GROUP_TARGET_OBJECT_ORIGIN_CHANGED" }
        val original = origin.plan.objects.singleOrNull { it.key == item.key && it.descriptor == item.descriptor && it.operation == item.operation }
        require(original != null && origin.chunk?.objects?.singleOrNull { it.objectRequest == original } == cache.observation) {
            "SYNC_GROUP_TARGET_OBJECT_OBSERVATION_CHANGED"
        }
        proof.requireLive(); return cache
    }
    private fun recoverChunkObservation(item: LanSyncGroupTargetObject,
        origins: MutableMap<String, HistoricalSelection>): ChunkItemObservation? {
        var conflict: CiphertextStoreException? = null
        Files.newDirectoryStream(root, "chunk-receipt-*.json").use { files -> for (file in files) {
            proof.requireLive()
            val originId = file.fileName.toString().removePrefix("chunk-receipt-").removeSuffix(".json")
            val origin = origins.getOrPut(originId) { historicalSelection(originId, weak = false, chunk = true) }
            val original = origin.plan.objects.singleOrNull { it.key == item.key && it.descriptor == item.descriptor && it.operation == item.operation } ?: continue
            val row = requireNotNull(origin.chunk).objects.single { it.objectRequest == original }
            val cache = ChunkItemObservation(itemBinding(item.key, item.descriptor, item.operation), originId,
                origin.planHash, origin.receiptHash, row)
            try {
                writeItem(chunkObservations, item.key, "OBJECT-OBSERVATION", ChunkItemObservation.serializer(), cache)
                proof.requireLive(); return cache
            } catch (failure: CiphertextStoreException) {
                if (failure.failure != CiphertextStoreFailure.OPERATION_CONFLICT) throw failure
                conflict = failure
            }
        } }
        conflict?.let { throw it }; return null
    }
    private fun cacheChunkSelection(plan: LanSyncGroupTargetPlan, receipt: LanSyncGroupChunkTargetReceipt) {
        val hash = LanSyncResourceStore.hashFile(chunkReceiptPath, Files.size(chunkReceiptPath))
        val frozenPlanHash = planHash(); val origins = mutableMapOf<String, HistoricalSelection>()
        for ((index, item) in plan.objects.withIndex()) {
            proof.requireLive()
            if (readChunkObservation(item, origins) != null) continue
            val cache = ChunkItemObservation(itemBinding(item.key, item.descriptor, item.operation), id,
                frozenPlanHash, hash, receipt.objects[index])
            writeItem(chunkObservations, item.key, "OBJECT-OBSERVATION", ChunkItemObservation.serializer(), cache)
        }
        proof.requireLive()
    }
    private fun requireCloudRow(row: LanSyncGroupCloudTargetObjectReceipt) {
        val item = row.objectRequest
        fun matches(value: LanSyncGroupCloudTargetObservation, owned: Boolean): Boolean = when (value) {
            is LanSyncGroupCloudTargetObservation.Whole -> value.observation.let {
                it.route == target.id && it.key == item.key && it.descriptor == item.descriptor &&
                    (!owned || it.operation == item.operation) && it.observedAtMillis >= 0 &&
                    it.origin in WeakWebDavObservationOrigin.entries.map { origin -> origin.name }
            }
            is LanSyncGroupCloudTargetObservation.Chunks -> value.observation.let {
                it.logicalKey == item.key && it.logicalDescriptor == item.descriptor && (!owned || it.logicalOperation == item.operation) &&
                    it.manifest.route == target.id && it.manifest.manifestKey == item.key && it.manifest.manifestDescriptor.length > 0 &&
                    it.chunkCount >= 0 && (it.chunkCount == 0L) == (item.descriptor.length == 0L) && it.observedAtMillis >= 0
            }
        }
        fun readback(value: LanSyncGroupCloudTargetObservation) = value !is LanSyncGroupCloudTargetObservation.Whole ||
            value.observation.origin == WeakWebDavObservationOrigin.EXISTING_ATTEMPT_READBACK.name
        val valid = when (val evidence = row.evidence) {
            is LanSyncGroupCloudTargetEvidence.Existing -> matches(evidence.readback, false) && readback(evidence.readback)
            is LanSyncGroupCloudTargetEvidence.Appended -> {
                val one = evidence.append; val two = evidence.readback
                val same = when {
                    one is LanSyncGroupCloudTargetObservation.Whole && two is LanSyncGroupCloudTargetObservation.Whole ->
                        one.observation.let { a -> two.observation.let { b -> a.route == b.route && a.key == b.key &&
                            a.descriptor == b.descriptor && a.operation == b.operation && a.attempt == b.attempt } }
                    one is LanSyncGroupCloudTargetObservation.Chunks && two is LanSyncGroupCloudTargetObservation.Chunks ->
                        one.observation.let { a -> two.observation.let { b -> a.logicalKey == b.logicalKey && a.logicalDescriptor == b.logicalDescriptor &&
                            a.logicalOperation == b.logicalOperation && a.manifest == b.manifest && a.chunkCount == b.chunkCount } }
                    else -> false
                }
                matches(one, true) && matches(two, true) && readback(two) && same
            }
        }
        require(valid) { "SYNC_GROUP_TARGET_RECEIPT_CHANGED" }
    }
    private fun readCloudReceipt(plan: LanSyncGroupTargetPlan, path: Path = cloudReceiptPath,
        hash: String = planHash()): LanSyncGroupCloudTargetReceipt? {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        LanSyncMetadataBudget().validate(path)
        return requireNotNull(LanSyncJsonFiles.read(path, LanSyncGroupCloudTargetReceipt.serializer())).also {
            require(it.planHash == hash && it.target == target && it.checkpoint == plan.checkpoint &&
                it.objects.map { row -> row.objectRequest } == plan.objects && it.rejectedCandidates >= 0) { "SYNC_GROUP_TARGET_RECEIPT_CHANGED" }
            it.objects.forEach(::requireCloudRow)
        }
    }
    private class CloudHistoricalSelection(val plan: LanSyncGroupTargetPlan, val planHash: String,
        val receiptHash: String, val receipt: LanSyncGroupCloudTargetReceipt)
    private fun cloudHistoricalSelection(selectionId: String): CloudHistoricalSelection {
        require(selectionId.matches(Regex("[a-f0-9]{64}"))) { "SYNC_GROUP_TARGET_OBJECT_ORIGIN_CHANGED" }
        val path = root.resolve("plan-$selectionId.json"); val receipt = root.resolve("cloud-receipt-$selectionId.json")
        require(Files.exists(path, NOFOLLOW_LINKS) && Files.exists(receipt, NOFOLLOW_LINKS)) { "SYNC_GROUP_TARGET_OBJECT_ORIGIN_MISSING" }
        LanSyncMetadataBudget().validate(path); LanSyncMetadataBudget().validate(receipt)
        val plan = requireNotNull(LanSyncJsonFiles.read(path, LanSyncGroupTargetPlan.serializer()))
        require(plan.group == ownerBinding.group && plan.target == target && plan.objects.map { it.key }.toSet().size == plan.objects.size &&
            plan.objects.map { it.operation }.toSet().size == plan.objects.size) { "SYNC_GROUP_TARGET_OBJECT_ORIGIN_CHANGED" }
        val hash = LanSyncResourceStore.hashFile(path, Files.size(path))
        return CloudHistoricalSelection(plan, hash, LanSyncResourceStore.hashFile(receipt, Files.size(receipt)),
            requireNotNull(readCloudReceipt(plan, receipt, hash)))
    }
    private fun readCloudObservation(item: LanSyncGroupTargetObject,
        origins: MutableMap<String, CloudHistoricalSelection>): CloudItemObservation? {
        proof.requireLive()
        val cache = readItem(cloudObservations, item.key, CloudItemObservation.serializer())
            ?: recoverCloudObservation(item, origins) ?: return null
        requireItemBinding(cache.binding, item)
        require(cache.selectionId.matches(Regex("[a-f0-9]{64}")) && cache.planHash.matches(Regex("[a-f0-9]{64}")) &&
            cache.receiptHash.matches(Regex("[a-f0-9]{64}"))) { "SYNC_GROUP_TARGET_OBJECT_OBSERVATION_CHANGED" }
        val origin = origins.getOrPut(cache.selectionId) { cloudHistoricalSelection(cache.selectionId) }
        require(origin.planHash == cache.planHash && origin.receiptHash == cache.receiptHash) { "SYNC_GROUP_TARGET_OBJECT_ORIGIN_CHANGED" }
        val original = origin.plan.objects.singleOrNull { it.key == item.key && it.descriptor == item.descriptor && it.operation == item.operation }
        require(original != null && origin.receipt.objects.singleOrNull { it.objectRequest == original } == cache.observation) {
            "SYNC_GROUP_TARGET_OBJECT_OBSERVATION_CHANGED"
        }
        proof.requireLive(); return cache
    }
    private fun recoverCloudObservation(item: LanSyncGroupTargetObject,
        origins: MutableMap<String, CloudHistoricalSelection>): CloudItemObservation? {
        var conflict: CiphertextStoreException? = null
        Files.newDirectoryStream(root, "cloud-receipt-*.json").use { files -> for (file in files) {
            proof.requireLive()
            val originId = file.fileName.toString().removePrefix("cloud-receipt-").removeSuffix(".json")
            val origin = origins.getOrPut(originId) { cloudHistoricalSelection(originId) }
            val original = origin.plan.objects.singleOrNull { it.key == item.key && it.descriptor == item.descriptor && it.operation == item.operation } ?: continue
            val row = origin.receipt.objects.single { it.objectRequest == original }
            val cache = CloudItemObservation(itemBinding(item.key, item.descriptor, item.operation), originId, origin.planHash, origin.receiptHash, row)
            try {
                writeItem(cloudObservations, item.key, "OBJECT-OBSERVATION", CloudItemObservation.serializer(), cache)
                proof.requireLive(); return cache
            } catch (failure: CiphertextStoreException) {
                if (failure.failure != CiphertextStoreFailure.OPERATION_CONFLICT) throw failure
                conflict = failure
            }
        } }
        conflict?.let { throw it }; return null
    }
    private fun cacheCloudSelection(plan: LanSyncGroupTargetPlan, receipt: LanSyncGroupCloudTargetReceipt) {
        val hash = LanSyncResourceStore.hashFile(cloudReceiptPath, Files.size(cloudReceiptPath))
        val frozenPlanHash = planHash(); val origins = mutableMapOf<String, CloudHistoricalSelection>()
        for ((index, item) in plan.objects.withIndex()) {
            proof.requireLive()
            if (readCloudObservation(item, origins) != null) continue
            val cache = CloudItemObservation(itemBinding(item.key, item.descriptor, item.operation), id, frozenPlanHash, hash, receipt.objects[index])
            writeItem(cloudObservations, item.key, "OBJECT-OBSERVATION", CloudItemObservation.serializer(), cache)
        }
        proof.requireLive()
    }
    private fun <T> readItem(store: FileCiphertextStore, key: CiphertextObjectKey, serializer: KSerializer<T>): T? {
        val metadata = store.metadata(key) ?: return null
        require(metadata.descriptor.length <= 64L * 1024) { "SYNC_GROUP_TARGET_ITEM_RECORD_CHANGED" }
        store.reserveOperation(CiphertextPutRequest(key,
            itemRecordOperation(if (store === itemBindings) "ITEM-BINDING" else "OBJECT-OBSERVATION", key), metadata.descriptor))
        val bytes = java.io.ByteArrayOutputStream(metadata.descriptor.length.toInt())
        require(store.readTo(key, bytes, metadata.descriptor) == metadata) { "SYNC_GROUP_TARGET_ITEM_RECORD_CHANGED" }
        return lanSyncJson.decodeFromString(serializer, bytes.toByteArray().decodeToString(throwOnInvalidSequence = true))
    }
    private fun <T> writeItem(store: FileCiphertextStore, key: CiphertextObjectKey, domain: String, serializer: KSerializer<T>, value: T) {
        val bytes = lanSyncJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 64 * 1024) { "SYNC_GROUP_TARGET_ITEM_RECORD_CHANGED" }
        val operation = itemRecordOperation(domain, key)
        store.put(CiphertextPutRequest(key, operation, CiphertextDescriptor(bytes.size.toLong(), CiphertextSha256.parse(lanSyncHash(bytes)))), bytes.inputStream())
    }
    private fun itemRecordOperation(domain: String, key: CiphertextObjectKey) = SyncStoreId.parse(lanSyncHash(
        "CCB-GROUP-TARGET-$domain/1\n${target.kind}\n${target.id.value}\n${key.kind}\n${key.id.value}".toByteArray(Charsets.US_ASCII)).take(32))
    private fun liveInput(input: java.io.InputStream) = object : FilterInputStream(input) {
        override fun read(): Int { proof.requireLive(); val value = `in`.read(); proof.requireLive(); return value }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            proof.requireLive(); val count = `in`.read(bytes, offset, length); proof.requireLive(); return count
        }
    }
    private fun <T> guarded(work: () -> T): T {
        check(!lock.isHeldByCurrentThread) { "SYNC_GROUP_TARGET_REENTRY" }; lock.lockInterruptibly()
        try { PrivateSyncFile.checked(root, true)
            FileChannel.open(root.resolve("publication.lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel -> channel.lock().use { return work() } }
        } finally { lock.unlock() }
    }
    companion object { private val locks = ConcurrentHashMap<Path, ReentrantLock>() }
}
