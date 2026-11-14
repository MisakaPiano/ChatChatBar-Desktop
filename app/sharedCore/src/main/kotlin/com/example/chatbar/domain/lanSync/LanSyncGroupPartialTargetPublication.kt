package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.*
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@Serializable data class LanSyncGroupPartialTargetPlan(val scope: LanSyncGroupPartialTargetScope,
    val target: SyncDeliveryTarget, val selection: LanSyncGroupPartialTargetSelection,
    val objects: List<LanSyncGroupTargetObject>)

/** Historical object observations and the packages they actually prove; never a full target receipt. */
@Serializable data class LanSyncGroupPartialCloudReceipt(val planHash: String, val target: SyncDeliveryTarget,
    val scope: LanSyncGroupPartialTargetScope, val observedSelection: LanSyncGroupPartialTargetSelection,
    val objects: List<LanSyncGroupCloudTargetObjectReceipt>, val rejectedCandidates: Int = 0)

data class LanSyncGroupPartialTargetProgress(val scopeHash: String, val availableObjects: Int, val observedObjects: Int,
    val completeUnitPackages: Int, val pendingUnitPackages: Int, val pendingControlSlots: Int,
    val lastObservedAt: Long?, val rejectedCandidates: Int) {
    override fun toString() = "LanSyncGroupPartialTargetProgress(REDACTED)"
}

/** Separate immutable partial plans/object evidence. Existing full plan/receipt/cache files are untouched. */
internal class LanSyncGroupPartialTargetPublication(private val root: Path, private val target: SyncDeliveryTarget,
    private val proof: LanSyncGroupPartialTargetExport, private val observeInitialization: (String) -> Unit = {}) {
    @Serializable private data class Binding(val group: SyncStoreId, val genesis: SyncVersionRef, val device: String, val target: SyncDeliveryTarget)
    @Serializable private data class ItemBinding(val owner: Binding, val key: CiphertextObjectKey, val descriptor: CiphertextDescriptor, val operation: SyncStoreId)
    @Serializable private data class Current(val scopeHash: String, val planId: String, val planHash: String)
    @Serializable private data class LegacyOrigin(val kind: String, val planId: String, val planHash: String, val receiptHash: String)
    @Serializable private data class ObjectEvidence(val binding: ItemBinding, val originPlan: String, val originPlanHash: String,
        val observation: LanSyncGroupCloudTargetObjectReceipt, val rejectedCandidates: Int, val legacy: LegacyOrigin? = null)
    @Serializable private data class ReceiptPointer(val planHash: String, val file: String, val hash: String)
    @Serializable private data class RetainedOrigin(val owner: Binding, val id: SyncStoreId)
    @Serializable private data class Ready(val owner: Binding, val retainedOrigin: SyncStoreId, val bindingsStore: SyncStoreId, val evidenceStore: SyncStoreId)
    private class Initialized(val work: Path, val retained: Path, val bindings: FileCiphertextStore, val evidence: FileCiphertextStore, val ready: Ready)
    private class Origin(val plan: LanSyncGroupPartialTargetPlan, val hash: String)
    private val binding = Binding(proof.trust.group, proof.trust.genesis, proof.device, target)
    private val lock = locks.computeIfAbsent(root) { ReentrantLock() }
    private val itemBindingsPath = root.resolve("partial-item-bindings")
    private val objectEvidencePath = root.resolve("partial-object-evidence")
    private val initialized = guarded { initialize() }
    private val work = initialized.work
    private val retained = initialized.retained
    private val itemBindings = initialized.bindings
    private val objectEvidence = initialized.evidence
    private val currentPath get() = root.resolve("partial-current-${proof.scope.scopeHash}.json")

    /** Intent may resume only before use. READY pins origins and is written last under this lock. */
    private fun initialize(): Initialized {
        require(target.kind == SyncDeliveryKind.CLOUD_STORE && target != SyncDeliveryTarget.LEGACY) { "SYNC_GROUP_PARTIAL_EXPLICIT_CLOUD_REQUIRED" }
        proof.requireLive()
        val path = root.resolve("partial-target.json"); val readyPath = root.resolve("partial-ready.json")
        val prior = LanSyncJsonFiles.read(path, Binding.serializer())
        val ready = LanSyncJsonFiles.read(readyPath, Ready.serializer())
        if (ready != null) {
            require(prior == binding && ready.owner == binding) { "SYNC_GROUP_PARTIAL_ORIGIN_MISSING" }
            val retained = root.resolve("partial-retained"); PrivateSyncFile.checked(retained, true)
            val retainedOrigin = requireNotNull(LanSyncJsonFiles.read(retained.resolve("origin.json"), RetainedOrigin.serializer())) { "SYNC_GROUP_PARTIAL_ORIGIN_MISSING" }
            require(retainedOrigin.owner == binding && retainedOrigin.id == ready.retainedOrigin &&
                storeId(itemBindingsPath) == ready.bindingsStore && storeId(objectEvidencePath) == ready.evidenceStore) { "SYNC_GROUP_PARTIAL_ORIGIN_CHANGED" }
            val used = LanSyncJsonFiles.read(root.resolve("partial-used.json"), Ready.serializer())
            used?.let { require(it == ready) { "SYNC_GROUP_PARTIAL_ORIGIN_CHANGED" } }
            require(used != null || unusedInitialization()) { "SYNC_GROUP_PARTIAL_ORIGIN_MISSING" }
            return Initialized(directory("preparations"), retained, openStore(itemBindingsPath), openStore(objectEvidencePath), ready)
        }
        if (prior == null) {
            require(Files.newDirectoryStream(root).use { entries -> entries.none {
                it.fileName.toString().startsWith("partial-") && it.fileName.toString() != "partial-publication.lock"
            } }) { "SYNC_GROUP_PARTIAL_ORIGIN_MISSING" }
            LanSyncJsonFiles.read(root.resolve("target.json"), Binding.serializer())?.let { require(it == binding) { "SYNC_GROUP_PARTIAL_ROUTE_CHANGED" } }
            LanSyncJsonFiles.write(path, Binding.serializer(), binding)
            observeInitialization("PARTIAL_INTENT_RETAINED")
        } else require(prior == binding) { "SYNC_GROUP_PARTIAL_ROUTE_CHANGED" }
        require(unusedInitialization()) { "SYNC_GROUP_PARTIAL_READY_MISSING" }
        val retained = directory("partial-retained"); val retainedPath = retained.resolve("origin.json")
        val retainedOrigin = LanSyncJsonFiles.read(retainedPath, RetainedOrigin.serializer()) ?: RetainedOrigin(binding, SyncStoreId.random()).also {
            LanSyncJsonFiles.write(retainedPath, RetainedOrigin.serializer(), it)
        }
        require(retainedOrigin.owner == binding) { "SYNC_GROUP_PARTIAL_ROUTE_CHANGED" }
        observeInitialization("PARTIAL_RETAINED_ORIGIN_RETAINED")
        val bindings = FileCiphertextStore(itemBindingsPath)
        observeInitialization("PARTIAL_BINDINGS_STORE_RETAINED")
        val evidence = FileCiphertextStore(objectEvidencePath)
        observeInitialization("PARTIAL_EVIDENCE_STORE_RETAINED")
        val complete = Ready(binding, retainedOrigin.id, storeId(itemBindingsPath), storeId(objectEvidencePath))
        val work = directory("preparations")
        proof.requireLive(); LanSyncJsonFiles.write(readyPath, Ready.serializer(), complete)
        return Initialized(work, retained, bindings, evidence, complete)
    }

    private fun unusedInitialization(): Boolean {
        val allowed = setOf("partial-publication.lock", "partial-target.json", "partial-retained", "partial-item-bindings", "partial-object-evidence", "partial-ready.json")
        if (Files.newDirectoryStream(root).use { paths -> paths.any { it.fileName.toString().startsWith("partial-") && it.fileName.toString() !in allowed } }) return false
        val retained = root.resolve("partial-retained")
        if (Files.exists(retained, NOFOLLOW_LINKS)) {
            PrivateSyncFile.checked(retained, true)
            Files.newDirectoryStream(retained).use { paths -> for (file in paths) {
                if (file.fileName.toString() == "origin.json") continue
                // A process can stop before the atomic initialization JSON rename. These private
                // uncommitted bytes grant no authority and remain retained, rather than repaired.
                if (!file.fileName.toString().matches(Regex("\\.[a-f0-9-]{36}\\.json-preparation"))) return false
                PrivateSyncFile.checked(file)
            } }
        }
        for (store in listOf(itemBindingsPath, objectEvidencePath)) if (Files.exists(store, NOFOLLOW_LINKS)) {
            PrivateSyncFile.checked(store, true)
            val names = setOf("store.id", "store.lock", "objects", "commits", "operations", "staging")
            if (Files.newDirectoryStream(store).use { paths -> paths.any { it.fileName.toString() !in names } }) return false
            for (name in listOf("objects", "commits", "operations")) if (Files.exists(store.resolve(name), NOFOLLOW_LINKS)) {
                PrivateSyncFile.checked(store.resolve(name), true)
                if (Files.newDirectoryStream(store.resolve(name)).use { it.iterator().hasNext() }) return false
            }
            if (Files.exists(store.resolve("store.id"), NOFOLLOW_LINKS)) storeId(store)
            val staging = store.resolve("staging")
            if (Files.exists(staging, NOFOLLOW_LINKS)) {
                PrivateSyncFile.checked(staging, true)
                Files.newDirectoryStream(staging).use { paths -> for (file in paths) {
                    PrivateSyncFile.checked(file)
                    if (!file.fileName.toString().matches(Regex("[a-f0-9]{32}\\.part")) || Files.size(file) > 32) return false
                    if (Files.readAllBytes(file).any { it.toInt().toChar() !in "0123456789abcdef" }) return false
                } }
            }
        }
        return true
    }
    private fun storeId(path: Path): SyncStoreId {
        require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "SYNC_GROUP_PARTIAL_ORIGIN_MISSING" }
        PrivateSyncFile.checked(path, true)
        val id = path.resolve("store.id")
        require(Files.isRegularFile(id, NOFOLLOW_LINKS)) { "SYNC_GROUP_PARTIAL_ORIGIN_MISSING" }
        PrivateSyncFile.checked(id)
        require(Files.size(id) == 32L) { "SYNC_GROUP_PARTIAL_ORIGIN_CHANGED" }
        return SyncStoreId.parse(Files.readString(id, Charsets.US_ASCII))
    }

    /** Always refreshes availability; an older subset must not freeze newly available packages out. */
    suspend fun prepare(): LanSyncGroupPartialTargetPlan = runInterruptible(Dispatchers.IO) { guarded {
        proof.requireLive()
        val used = root.resolve("partial-used.json")
        require(Files.exists(used, NOFOLLOW_LINKS) || unusedInitialization()) { "SYNC_GROUP_PARTIAL_ORIGIN_MISSING" }
        immutable(used, Ready.serializer(), initialized.ready)
        val prior = current()
        prior?.let { readReceipt(it) }
        val preparation = if (prior == null) proof.prepareAvailable() else {
            val retainedSource = retainedLens(prior.plan.objects.map { it.ref() }.toSet())
            proof.validateAvailable(prior.plan.selection, retainedSource)
            proof.prepareAvailable(prior.plan.selection.exactObjects, retainedSource)
        }
        preparation.use { prepared ->
            val items = prepared.selection.exactObjects.map { row ->
                val item = itemBinding(row.location.metadata())
                LanSyncGroupTargetObject(LanSyncGroupTargetObjectRole.valueOf(row.role.name), row.slot, item.key, item.descriptor, item.operation)
            }
            val plan = LanSyncGroupPartialTargetPlan(proof.scope, target, prepared.selection, items)
            validatePlan(plan, current = true)
            for (item in items) {
                val metadata = CiphertextObjectMetadata(item.key, item.descriptor)
                lanSyncTargetStage(work, prepared.source, metadata, ::checkLive).use { staged ->
                    Files.newInputStream(staged.file.path, NOFOLLOW_LINKS).use {
                        retainedStore(metadata).put(CiphertextPutRequest(item.key, item.operation, item.descriptor), liveInput(it))
                    }
                }
            }
            val id = planId(plan); val path = planPath(id)
            immutable(path, LanSyncGroupPartialTargetPlan.serializer(), plan)
            proof.requireLive()
            LanSyncJsonFiles.write(currentPath, Current.serializer(), Current(proof.scope.scopeHash, id, fileHash(path)))
            plan
        }
    } }

    /** Each exact object gets its own durable typed evidence before the next provider object starts. */
    suspend fun uploadCloud(attempts: WeakWebDavAttemptStore, parts: WeakWebDavChunkAssembly,
        onProgress: (LanSyncGroupPartialTargetProgress) -> Unit = {}): LanSyncGroupPartialCloudReceipt =
        runInterruptible(Dispatchers.IO) { guarded {
            proof.requireLive()
            require(attempts.routeId == target.id && parts.routeId == target.id) { "SYNC_GROUP_PARTIAL_ROUTE_CHANGED" }
            val origin = requireNotNull(current()) { "SYNC_GROUP_PARTIAL_PLAN_MISSING" }; val plan = origin.plan
            readReceipt(origin) // A present damaged aggregate is not a missing derived index.
            // Recheck the frozen subset locally before trusting any historical target observation.
            proof.validateAvailable(plan.selection, retainedLens(plan.objects.map { it.ref() }.toSet()))
            val origins = mutableMapOf<String, Origin>()
            // Older complete typed history is stronger object evidence, with its original locator
            // and time. Adoption creates only this mode's independent derived object record.
            for (item in plan.objects) if (readEvidence(item, origins) == null) {
                legacyObservation(item)?.let { (row, legacy) ->
                    val metadata = CiphertextObjectMetadata(item.key, item.descriptor)
                    writeRecord(objectEvidence, objectEvidencePath, metadata, "OBSERVATION", ObjectEvidence.serializer(),
                        ObjectEvidence(itemBinding(metadata, item.operation), planId(plan), origin.hash, row.copy(objectRequest = item), 0, legacy))
                }
            }
            val cached = plan.objects.associateWith { readEvidence(it, origins) }
            var rejected = cached.values.filterNotNull().maxOfOrNull { it.rejectedCandidates } ?: 0
            fun reject() { if (rejected < Int.MAX_VALUE) rejected++ }
            try {
                for (item in plan.objects) {
                    proof.requireLive()
                    if (cached[item] != null) continue
                    val metadata = CiphertextObjectMetadata(item.key, item.descriptor)
                    val found = probe(item, attempts, parts, ::reject)
                    val observation: LanSyncGroupCloudTargetObjectReceipt
                    if (found != null) {
                        found.first.use { readback ->
                            // Full descriptor equality to the already authenticated immutable source
                            // binds this provider read to that original local proof, without re-signing.
                            retainReadback(item, readback)
                        }
                        observation = LanSyncGroupCloudTargetObjectReceipt(item, LanSyncGroupCloudTargetEvidence.Existing(found.second))
                    } else {
                        val appended = lanSyncTargetStage(work, retainedLens(setOf(item.ref())), metadata, ::checkLive).use { staged ->
                            Files.newInputStream(staged.file.path, NOFOLLOW_LINKS).use {
                                parts.append(CiphertextPutRequest(item.key, item.operation, item.descriptor), liveInput(it), ::checkLive)
                            }
                        }
                        requireAssembly(item, appended)
                        var observed: WeakWebDavAssemblyObservation? = null
                        lanSyncTargetReceiveStage(work, metadata, ::checkLive) { output ->
                            val actual = parts.readAssembly(appended.assembly, metadata, output, ::checkLive)
                                ?: throw CiphertextStoreException(CiphertextStoreFailure.TRANSIENT)
                            requireAssembly(item, actual)
                            require(actual.assembly == appended.assembly && actual.chunkCount == appended.chunkCount) { "SYNC_GROUP_PARTIAL_ASSEMBLY_CHANGED" }
                            observed = actual; metadata
                        }.use { retainReadback(item, it) }
                        observation = LanSyncGroupCloudTargetObjectReceipt(item, LanSyncGroupCloudTargetEvidence.Appended(
                            chunks(appended), chunks(requireNotNull(observed))))
                    }
                    requireRow(observation)
                    val evidence = ObjectEvidence(itemBinding(metadata, item.operation), planId(plan), origin.hash, observation, rejected)
                    proof.requireLive(); writeRecord(objectEvidence, objectEvidencePath, metadata, "OBSERVATION", ObjectEvidence.serializer(), evidence)
                    // Callback failure also leaves the just-forced object observation intact.
                    onProgress(progressOwned(plan, origins))
                }
                val receipt = snapshot(origin, rejected, origins)
                onProgress(progressOwned(plan, origins))
                receipt
            } catch (failure: Throwable) {
                // Provider failure must not erase completed independent objects. This pass is local
                // only; cancellation/changed authority can refuse the aggregate without losing rows.
                try { snapshot(origin, rejected, origins); onProgress(progressOwned(plan, origins)) }
                catch (local: Throwable) { if (local !== failure) failure.addSuppressed(local) }
                throw failure
            }
        } }

    /** Blocking local I/O only. Rebuilds a missing aggregate from the immutable object observations. */
    fun receipt(): LanSyncGroupPartialCloudReceipt? = guarded {
        val origin = current() ?: return@guarded null
        readReceipt(origin)
        val origins = mutableMapOf<String, Origin>()
        val rows = origin.plan.objects.mapNotNull { readEvidence(it, origins) }
        if (rows.isEmpty()) return@guarded null
        snapshot(origin, rows.maxOfOrNull { it.rejectedCandidates } ?: 0, origins)
    }
    /** Object counts remain useful even if cancellation prevented an aggregate package proof. */
    fun progress(): LanSyncGroupPartialTargetProgress = guarded {
        val origin = current() ?: return@guarded LanSyncGroupPartialTargetProgress(proof.scope.scopeHash, 0, 0, 0,
            proof.scope.wantedUnitRefs.size, proof.scope.wantedControlSlots.size, null, 0)
        progressOwned(origin.plan, mutableMapOf())
    }

    private fun snapshot(origin: Origin, rejected: Int, origins: MutableMap<String, Origin>): LanSyncGroupPartialCloudReceipt {
        proof.requireLive(); val plan = origin.plan
        readReceipt(origin) // Validate first; only an absent aggregate may be reconstructed.
        val rows = plan.objects.mapNotNull { item -> readEvidence(item, origins)?.observation?.copy(objectRequest = item) }
        val allowed = rows.map { it.objectRequest.ref() }.toSet()
        val exact = plan.selection.exactObjects.filter { it.location in allowed }
        // This executes the same AEAD/ledger/native-unit/membership proof as validateAvailable,
        // against only actually observed exact objects, never the complete warm source lens.
        proof.observeAvailable(exact, retainedLens(allowed)).use { observed ->
            val receipt = LanSyncGroupPartialCloudReceipt(origin.hash, target, plan.scope, observed.selection, rows, rejected)
            validateReceipt(receipt, origin)
            val id = lanSyncHash(lanSyncJson.encodeToJsonElement(LanSyncGroupPartialCloudReceipt.serializer(), receipt))
            val name = "partial-cloud-receipt-${planId(plan)}-$id.json"; val path = root.resolve(name)
            immutable(path, LanSyncGroupPartialCloudReceipt.serializer(), receipt)
            proof.requireLive()
            LanSyncJsonFiles.write(receiptPointer(plan), ReceiptPointer.serializer(), ReceiptPointer(origin.hash, name, fileHash(path)))
            return receipt
        }
    }
    private fun progressOwned(plan: LanSyncGroupPartialTargetPlan, origins: MutableMap<String, Origin>): LanSyncGroupPartialTargetProgress {
        val rows = plan.objects.mapNotNull { readEvidence(it, origins) }
        val prior = readReceipt(Origin(plan, fileHash(planPath(planId(plan)))))
        val completed = prior?.observedSelection?.completeUnitPackages?.size ?: 0
        return LanSyncGroupPartialTargetProgress(plan.scope.scopeHash, plan.objects.size, rows.size, completed,
            (plan.scope.wantedUnitRefs.size - completed).coerceAtLeast(0), prior?.observedSelection?.controlsPending?.size ?: plan.scope.wantedControlSlots.size,
            rows.maxOfOrNull { it.observation.evidence.readback.observedAtMillis }, rows.maxOfOrNull { it.rejectedCandidates } ?: 0)
    }

    private fun current(): Origin? {
        proof.requireLive()
        val pointer = LanSyncJsonFiles.read(currentPath, Current.serializer()) ?: return null
        requireUsed()
        require(pointer.scopeHash == proof.scope.scopeHash && pointer.planId.matches(HEX) && pointer.planHash.matches(HEX)) { "SYNC_GROUP_PARTIAL_CURRENT_CHANGED" }
        return readOrigin(pointer.planId).also { require(it.hash == pointer.planHash && it.plan.scope == proof.scope) { "SYNC_GROUP_PARTIAL_CURRENT_CHANGED" } }
    }
    private fun readOrigin(id: String): Origin {
        require(id.matches(HEX)) { "SYNC_GROUP_PARTIAL_PLAN_CHANGED" }
        val path = planPath(id); LanSyncMetadataBudget().validate(path)
        val value = requireNotNull(LanSyncJsonFiles.read(path, LanSyncGroupPartialTargetPlan.serializer())) { "SYNC_GROUP_PARTIAL_ORIGIN_MISSING" }
        require(planId(value) == id) { "SYNC_GROUP_PARTIAL_PLAN_CHANGED" }; validatePlan(value, current = false)
        return Origin(value, fileHash(path))
    }
    private fun validatePlan(plan: LanSyncGroupPartialTargetPlan, current: Boolean) {
        proof.requireLive()
        require(plan.target == target && plan.scope.group == binding.group && plan.selection.scope == plan.scope &&
            (!current || plan.scope == proof.scope) && proof.ledger.contains(plan.scope.checkpoint) &&
            plan.objects.map { it.ref() }.toSet().size == plan.objects.size && plan.objects.map { it.operation }.toSet().size == plan.objects.size &&
            plan.objects.map { LanSyncGroupPartialObject(LanSyncGroupPartialObjectRole.valueOf(it.role.name), it.slot, it.ref()) } == plan.selection.exactObjects) { "SYNC_GROUP_PARTIAL_PLAN_CHANGED" }
        plan.objects.forEach { requireItem(itemBinding(CiphertextObjectMetadata(it.key, it.descriptor), it.operation), it) }
    }
    private fun validateReceipt(receipt: LanSyncGroupPartialCloudReceipt, origin: Origin) {
        require(receipt.planHash == origin.hash && receipt.target == target && receipt.scope == origin.plan.scope &&
            receipt.observedSelection.scope == receipt.scope && receipt.rejectedCandidates >= 0 &&
            receipt.objects.map { it.objectRequest }.distinct().size == receipt.objects.size &&
            receipt.objects.all { it.objectRequest in origin.plan.objects } &&
            receipt.observedSelection.exactObjects.all { row -> receipt.objects.any { it.objectRequest.ref() == row.location } }) { "SYNC_GROUP_PARTIAL_RECEIPT_CHANGED" }
        receipt.objects.forEach(::requireRow)
    }
    private fun readReceipt(origin: Origin): LanSyncGroupPartialCloudReceipt? {
        val pointer = LanSyncJsonFiles.read(receiptPointer(origin.plan), ReceiptPointer.serializer())
        if (pointer == null) {
            // A missing derived pointer cannot hide the loss of primary object evidence that an
            // immutable aggregate already references. Validate retained history before rebuilding.
            Files.newDirectoryStream(root, "partial-cloud-receipt-${planId(origin.plan)}-*.json").use { paths ->
                for (path in paths) readReceiptFile(origin, path, fileHash(path))
            }
            return null
        }
        require(pointer.planHash == origin.hash && pointer.hash.matches(HEX) && pointer.file.matches(Regex("partial-cloud-receipt-[a-f0-9]{64}-[a-f0-9]{64}\\.json"))) { "SYNC_GROUP_PARTIAL_RECEIPT_CHANGED" }
        return readReceiptFile(origin, root.resolve(pointer.file), pointer.hash)
    }
    private fun readReceiptFile(origin: Origin, path: Path, hash: String): LanSyncGroupPartialCloudReceipt {
        LanSyncMetadataBudget().validate(path)
        return requireNotNull(LanSyncJsonFiles.read(path, LanSyncGroupPartialCloudReceipt.serializer(), hash)).also {
            validateReceipt(it, origin)
            val id = lanSyncHash(lanSyncJson.encodeToJsonElement(LanSyncGroupPartialCloudReceipt.serializer(), it))
            require(path.fileName.toString() == "partial-cloud-receipt-${planId(origin.plan)}-$id.json") { "SYNC_GROUP_PARTIAL_RECEIPT_CHANGED" }
            val origins = mutableMapOf<String, Origin>()
            for (row in it.objects) {
                val evidence = requireNotNull(readEvidence(row.objectRequest, origins)) { "SYNC_GROUP_PARTIAL_OBJECT_ORIGIN_MISSING" }
                require(evidence.observation.copy(objectRequest = row.objectRequest) == row) { "SYNC_GROUP_PARTIAL_RECEIPT_CHANGED" }
            }
        }
    }
    private fun readEvidence(item: LanSyncGroupTargetObject, origins: MutableMap<String, Origin>): ObjectEvidence? {
        val metadata = CiphertextObjectMetadata(item.key, item.descriptor)
        val value = readRecord(objectEvidence, objectEvidencePath, metadata, "OBSERVATION", ObjectEvidence.serializer()) ?: return null
        requireItem(value.binding, item)
        val origin = origins.getOrPut(value.originPlan) { readOrigin(value.originPlan) }
        require(origin.hash == value.originPlanHash && value.rejectedCandidates >= 0 &&
            value.observation.objectRequest in origin.plan.objects && value.observation.objectRequest.key == item.key &&
            value.observation.objectRequest.descriptor == item.descriptor && value.observation.objectRequest.operation == item.operation) { "SYNC_GROUP_PARTIAL_OBJECT_ORIGIN_CHANGED" }
        value.legacy?.let { legacy ->
            val row = legacyRows(legacy).singleOrNull { it.objectRequest.key == item.key && it.objectRequest.descriptor == item.descriptor && it.objectRequest.operation == item.operation }
            require(row?.evidence == value.observation.evidence) { "SYNC_GROUP_PARTIAL_LEGACY_CHANGED" }
        }
        requireRow(value.observation); return value
    }
    private fun itemBinding(metadata: CiphertextObjectMetadata, operation: SyncStoreId? = null): ItemBinding {
        val prior = readRecord(itemBindings, itemBindingsPath, metadata, "BINDING", ItemBinding.serializer())
        if (prior != null) {
            require(prior.owner == binding && prior.key == metadata.key && prior.descriptor == metadata.descriptor && (operation == null || prior.operation == operation)) { "SYNC_GROUP_PARTIAL_BINDING_CHANGED" }
            return prior
        }
        require(operation == null) { "SYNC_GROUP_PARTIAL_BINDING_MISSING" }
        require(objectEvidence.metadata(recordKey(metadata)) == null &&
            !Files.exists(objectEvidencePath.resolve("operations/${recordOperation(metadata, "OBSERVATION").value}.op"), NOFOLLOW_LINKS) &&
            !planned(metadata, partial = true)) { "SYNC_GROUP_PARTIAL_BINDING_MISSING" }
        val value = ItemBinding(binding, metadata.key, metadata.descriptor, legacyBinding(metadata)?.operation ?: SyncStoreId.random())
        writeRecord(itemBindings, itemBindingsPath, metadata, "BINDING", ItemBinding.serializer(), value)
        return value
    }
    private fun legacyBinding(metadata: CiphertextObjectMetadata): ItemBinding? {
        val path = root.resolve("item-bindings")
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            require(!planned(metadata, partial = false)) { "SYNC_GROUP_PARTIAL_LEGACY_BINDING_MISSING" }; return null
        }
        val store = openStore(path); val record = store.metadata(metadata.key)
        if (record == null) { require(!planned(metadata, partial = false)) { "SYNC_GROUP_PARTIAL_LEGACY_BINDING_MISSING" }; return null }
        require(record.descriptor.length <= 64 * 1024) { "SYNC_GROUP_PARTIAL_LEGACY_CHANGED" }
        val operation = SyncStoreId.parse(lanSyncHash("CCB-GROUP-TARGET-ITEM-BINDING/1\n${target.kind}\n${target.id.value}\n${metadata.key.kind}\n${metadata.key.id.value}".toByteArray(Charsets.US_ASCII)).take(32))
        val operationPath = path.resolve("operations/${operation.value}.op")
        require(Files.isRegularFile(operationPath, NOFOLLOW_LINKS)) { "SYNC_GROUP_PARTIAL_LEGACY_OPERATION_MISSING" }
        PrivateSyncFile.checked(operationPath)
        store.reserveOperation(CiphertextPutRequest(metadata.key, operation, record.descriptor))
        val bytes = ByteArrayOutputStream(record.descriptor.length.toInt())
        require(store.readTo(metadata.key, guardedOutput(bytes), record.descriptor) == record) { "SYNC_GROUP_PARTIAL_LEGACY_CHANGED" }
        val value = lanSyncJson.decodeFromString(ItemBinding.serializer(), bytes.toByteArray().decodeToString(throwOnInvalidSequence = true))
        require(value.owner == binding && value.key == metadata.key) { "SYNC_GROUP_PARTIAL_LEGACY_CHANGED" }
        return value.takeIf { it.descriptor == metadata.descriptor }
    }
    /** Losing a current pointer must not make a previously allocated exact item look new. */
    private fun planned(metadata: CiphertextObjectMetadata, partial: Boolean): Boolean {
        val prefix = if (partial) "partial-plan-" else "plan-"
        Files.newDirectoryStream(root, "$prefix*.json").use { paths -> for (path in paths) {
            proof.requireLive(); LanSyncMetadataBudget().validate(path)
            val items = if (partial) {
                val plan = requireNotNull(LanSyncJsonFiles.read(path, LanSyncGroupPartialTargetPlan.serializer()))
                require(plan.target == target && plan.scope.group == binding.group && path.fileName.toString() == "$prefix${planId(plan)}.json") { "SYNC_GROUP_PARTIAL_PLAN_CHANGED" }
                plan.objects
            } else {
                val plan = requireNotNull(LanSyncJsonFiles.read(path, LanSyncGroupTargetPlan.serializer()))
                require(plan.target == target && plan.group == binding.group) { "SYNC_GROUP_PARTIAL_LEGACY_CHANGED" }; plan.objects
            }
            if (items.any { it.key == metadata.key && it.descriptor == metadata.descriptor }) return true
        } }
        return false
    }
    private fun legacyObservation(item: LanSyncGroupTargetObject): Pair<LanSyncGroupCloudTargetObjectReceipt, LegacyOrigin>? {
        for (kind in listOf("cloud", "weak", "chunk")) Files.newDirectoryStream(root, "$kind-receipt-*.json").use { paths ->
            for (path in paths) {
                proof.requireLive()
                val id = path.fileName.toString().removePrefix("$kind-receipt-").removeSuffix(".json")
                require(id.matches(HEX)) { "SYNC_GROUP_PARTIAL_LEGACY_CHANGED" }
                val evidence = LegacyOrigin(kind, id, fileHash(root.resolve("plan-$id.json")), fileHash(path))
                val rows = legacyRows(evidence)
                rows.singleOrNull { it.objectRequest.key == item.key && it.objectRequest.descriptor == item.descriptor && it.objectRequest.operation == item.operation }
                    ?.let { return it to evidence }
            }
        }
        return null
    }
    private fun legacyRows(origin: LegacyOrigin): List<LanSyncGroupCloudTargetObjectReceipt> {
        require(origin.kind in setOf("cloud", "weak", "chunk") && origin.planId.matches(HEX) && origin.planHash.matches(HEX) && origin.receiptHash.matches(HEX)) { "SYNC_GROUP_PARTIAL_LEGACY_CHANGED" }
        val planPath = root.resolve("plan-${origin.planId}.json"); val receiptPath = root.resolve("${origin.kind}-receipt-${origin.planId}.json")
        LanSyncMetadataBudget().validate(planPath); LanSyncMetadataBudget().validate(receiptPath)
        val plan = requireNotNull(LanSyncJsonFiles.read(planPath, LanSyncGroupTargetPlan.serializer(), origin.planHash))
        require(plan.group == binding.group && plan.target == target && plan.objects.map { it.key }.toSet().size == plan.objects.size &&
            plan.objects.map { it.operation }.toSet().size == plan.objects.size) { "SYNC_GROUP_PARTIAL_LEGACY_CHANGED" }
        val rows = when (origin.kind) {
            "cloud" -> requireNotNull(LanSyncJsonFiles.read(receiptPath, LanSyncGroupCloudTargetReceipt.serializer(), origin.receiptHash)).let {
                require(it.planHash == origin.planHash && it.target == target && it.checkpoint == plan.checkpoint && it.rejectedCandidates >= 0) { "SYNC_GROUP_PARTIAL_LEGACY_CHANGED" }; it.objects
            }
            "weak" -> requireNotNull(LanSyncJsonFiles.read(receiptPath, LanSyncGroupWeakTargetReceipt.serializer(), origin.receiptHash)).let {
                require(it.planHash == origin.planHash && it.target == target && it.checkpoint == plan.checkpoint) { "SYNC_GROUP_PARTIAL_LEGACY_CHANGED" }
                it.objects.map { row -> LanSyncGroupCloudTargetObjectReceipt(row.objectRequest, LanSyncGroupCloudTargetEvidence.Appended(
                    LanSyncGroupCloudTargetObservation.Whole(row.appendObservation), LanSyncGroupCloudTargetObservation.Whole(row.readbackObservation))) }
            }
            else -> requireNotNull(LanSyncJsonFiles.read(receiptPath, LanSyncGroupChunkTargetReceipt.serializer(), origin.receiptHash)).let {
                require(it.planHash == origin.planHash && it.target == target && it.checkpoint == plan.checkpoint) { "SYNC_GROUP_PARTIAL_LEGACY_CHANGED" }
                it.objects.map { row -> LanSyncGroupCloudTargetObjectReceipt(row.objectRequest, LanSyncGroupCloudTargetEvidence.Appended(
                    LanSyncGroupCloudTargetObservation.Chunks(row.appendObservation), LanSyncGroupCloudTargetObservation.Chunks(row.readbackObservation))) }
            }
        }
        require(rows.map { it.objectRequest } == plan.objects) { "SYNC_GROUP_PARTIAL_LEGACY_CHANGED" }
        rows.forEach(::requireRow); return rows
    }
    private fun requireItem(value: ItemBinding, item: LanSyncGroupTargetObject) {
        require(value.owner == binding && value.key == item.key && value.descriptor == item.descriptor && value.operation == item.operation) { "SYNC_GROUP_PARTIAL_BINDING_CHANGED" }
    }
    private fun <T> readRecord(store: FileCiphertextStore, path: Path, metadata: CiphertextObjectMetadata, domain: String, serializer: KSerializer<T>): T? {
        proof.requireLive(); requireUsed(); val key = recordKey(metadata); val record = store.metadata(key)
        if (record == null) {
            require(!Files.exists(path.resolve("operations/${recordOperation(metadata, domain).value}.op"), NOFOLLOW_LINKS)) { "SYNC_GROUP_PARTIAL_OBJECT_ORIGIN_MISSING" }
            return null
        }
        require(record.descriptor.length <= 64 * 1024) { "SYNC_GROUP_PARTIAL_RECORD_CHANGED" }
        val operationPath = path.resolve("operations/${recordOperation(metadata, domain).value}.op")
        require(Files.isRegularFile(operationPath, NOFOLLOW_LINKS)) { "SYNC_GROUP_PARTIAL_RECORD_OPERATION_MISSING" }
        PrivateSyncFile.checked(operationPath)
        store.reserveOperation(CiphertextPutRequest(key, recordOperation(metadata, domain), record.descriptor))
        val bytes = ByteArrayOutputStream(record.descriptor.length.toInt())
        require(store.readTo(key, guardedOutput(bytes), record.descriptor) == record) { "SYNC_GROUP_PARTIAL_RECORD_CHANGED" }
        return lanSyncJson.decodeFromString(serializer, bytes.toByteArray().decodeToString(throwOnInvalidSequence = true))
    }
    private fun <T> writeRecord(store: FileCiphertextStore, path: Path, metadata: CiphertextObjectMetadata, domain: String, serializer: KSerializer<T>, value: T) {
        readRecord(store, path, metadata, domain, serializer)?.let { require(it == value) { "SYNC_GROUP_PARTIAL_RECORD_CHANGED" }; return }
        val bytes = lanSyncJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 64 * 1024) { "SYNC_GROUP_PARTIAL_RECORD_CHANGED" }
        val descriptor = CiphertextDescriptor(bytes.size.toLong(), CiphertextSha256.parse(lanSyncHash(bytes)))
        proof.requireLive(); store.put(CiphertextPutRequest(recordKey(metadata), recordOperation(metadata, domain), descriptor), liveInput(bytes.inputStream()))
    }
    private fun requireUsed() {
        require(LanSyncJsonFiles.read(root.resolve("partial-used.json"), Ready.serializer()) == initialized.ready) { "SYNC_GROUP_PARTIAL_ORIGIN_MISSING" }
    }
    private fun retainedStore(metadata: CiphertextObjectMetadata) = openStore(retained.resolve(exactId(metadata)))
    private fun retainReadback(item: LanSyncGroupTargetObject, stage: LanSyncGroupTargetStage) {
        require(stage.metadata == CiphertextObjectMetadata(item.key, item.descriptor)) { "SYNC_GROUP_PARTIAL_READBACK_CHANGED" }
        Files.newInputStream(stage.file.path, NOFOLLOW_LINKS).use { retainedStore(stage.metadata).put(CiphertextPutRequest(item.key, item.operation, item.descriptor), liveInput(it)) }
    }
    private fun retainedLens(allowed: Set<LanSyncGroupPartialObjectRef>): CiphertextStore = object : CiphertextStore {
        override val capabilities = CiphertextStoreCapabilities(CommitListingSemantics.KEYSET_RESCAN_REQUIRED, untrustedCandidateVariants = true)
        override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult = error("SYNC_GROUP_PARTIAL_READ_ONLY")
        override fun metadata(key: CiphertextObjectKey): CiphertextObjectMetadata? {
            proof.requireLive(); val candidates = allowed.filter { it.key == key }
            require(candidates.size <= 1) { "SYNC_GROUP_PARTIAL_EXACT_DESCRIPTOR_REQUIRED" }
            return candidates.singleOrNull()?.metadata()
        }
        override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
            proof.requireLive(); val candidates = allowed.filter { it.key == key && (expected == null || it.descriptor == expected) }
            require(candidates.size <= 1) { "SYNC_GROUP_PARTIAL_EXACT_DESCRIPTOR_REQUIRED" }
            val metadata = candidates.singleOrNull()?.metadata() ?: return null
            val path = retained.resolve(exactId(metadata))
            require(Files.exists(path, NOFOLLOW_LINKS)) { "SYNC_GROUP_PARTIAL_RETAINED_MISSING" }
            return retainedStore(metadata).readTo(key, guardedOutput(destination), metadata.descriptor).also { require(it == metadata) { "SYNC_GROUP_PARTIAL_RETAINED_CHANGED" } }
        }
        override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage = error("SYNC_GROUP_PARTIAL_EXACT_READ_REQUIRED")
    }

    private class MissingCandidate : RuntimeException(null, null, false, false)
    private fun probe(item: LanSyncGroupTargetObject, attempts: WeakWebDavAttemptStore, parts: WeakWebDavChunkAssembly,
        reject: () -> Unit): Pair<LanSyncGroupTargetStage, LanSyncGroupCloudTargetObservation>? {
        val metadata = CiphertextObjectMetadata(item.key, item.descriptor); var cursor: WeakWebDavCursor? = null; val seen = hashSetOf<String>()
        do {
            proof.requireLive(); cursor?.let { require(seen.add(it.token)) { "SYNC_GROUP_PARTIAL_LISTING_CHANGED" } }
            val page = attempts.listCandidates(item.key, cursor, checkActive = ::checkLive)
            for (candidate in page.entries) {
                if (candidate.descriptor != item.descriptor) { reject(); continue }
                var observed: WeakWebDavObservation? = null
                val stage = try { lanSyncTargetReceiveStage(work, metadata, ::checkLive) { output ->
                    val actual = attempts.readCandidate(candidate, output, ::checkLive) ?: throw MissingCandidate()
                    require(actual.candidate == candidate) { "SYNC_GROUP_PARTIAL_CANDIDATE_CHANGED" }; observed = actual; metadata
                } } catch (_: MissingCandidate) { reject(); continue }
                catch (failure: CiphertextStoreException) { if (failure.failure != CiphertextStoreFailure.INTEGRITY) throw failure; reject(); continue }
                val value = requireNotNull(observed); val physical = value.candidate
                return stage to LanSyncGroupCloudTargetObservation.Whole(LanSyncGroupWeakTargetObservation(physical.route, physical.key, physical.descriptor,
                    physical.operationId, physical.attempt, value.origin.name, value.observedAtMillis))
            }
            cursor = page.nextCursor
        } while (cursor != null)
        seen.clear()
        do {
            proof.requireLive(); cursor?.let { require(seen.add(it.token)) { "SYNC_GROUP_PARTIAL_LISTING_CHANGED" } }
            val page = parts.listAssemblies(item.key, cursor, checkActive = ::checkLive)
            for (candidate in page.entries) {
                val header = try { parts.inspectAssembly(candidate, ::checkLive) }
                catch (failure: CiphertextStoreException) { if (failure.failure != CiphertextStoreFailure.INTEGRITY) throw failure; reject(); continue }
                if (header == null || header.originalRequest.key != item.key || header.originalRequest.descriptor != item.descriptor) { reject(); continue }
                var observed: WeakWebDavAssemblyObservation? = null
                val stage = try { lanSyncTargetReceiveStage(work, metadata, ::checkLive) { output ->
                    val actual = parts.readAssembly(candidate, metadata, output, ::checkLive) ?: throw MissingCandidate()
                    require(actual.assembly == candidate && actual.request == header.originalRequest && actual.chunkCount == header.chunkCount) { "SYNC_GROUP_PARTIAL_ASSEMBLY_CHANGED" }
                    observed = actual; metadata
                } } catch (_: MissingCandidate) { reject(); continue }
                catch (failure: CiphertextStoreException) { if (failure.failure != CiphertextStoreFailure.INTEGRITY) throw failure; reject(); continue }
                return stage to chunks(requireNotNull(observed))
            }
            cursor = page.nextCursor
        } while (cursor != null)
        proof.requireLive(); return null
    }
    private fun requireAssembly(item: LanSyncGroupTargetObject, value: WeakWebDavAssemblyObservation) {
        require(value.request == CiphertextPutRequest(item.key, item.operation, item.descriptor) && value.assembly.route == target.id &&
            value.assembly.key == item.key && value.chunkCount >= 0 && (value.chunkCount == 0L) == (item.descriptor.length == 0L)) { "SYNC_GROUP_PARTIAL_ASSEMBLY_CHANGED" }
    }
    private fun chunks(value: WeakWebDavAssemblyObservation) = LanSyncGroupCloudTargetObservation.Chunks(LanSyncGroupChunkTargetObservation(
        value.request.key, value.request.descriptor, value.request.operationId, LanSyncGroupChunkManifestLocator(value.assembly.route,
            value.assembly.key, value.assembly.descriptor, value.assembly.operationId, value.assembly.attempt), value.chunkCount, value.observedAtMillis))
    private fun requireRow(row: LanSyncGroupCloudTargetObjectReceipt) {
        val item = row.objectRequest
        fun matches(value: LanSyncGroupCloudTargetObservation, owned: Boolean) = when (value) {
            is LanSyncGroupCloudTargetObservation.Whole -> value.observation.let { it.route == target.id && it.key == item.key && it.descriptor == item.descriptor &&
                (!owned || it.operation == item.operation) && it.observedAtMillis >= 0 && it.origin in WeakWebDavObservationOrigin.entries.map { origin -> origin.name } }
            is LanSyncGroupCloudTargetObservation.Chunks -> value.observation.let { it.manifest.route == target.id && it.logicalKey == item.key && it.logicalDescriptor == item.descriptor &&
                (!owned || it.logicalOperation == item.operation) && it.manifest.manifestKey == item.key && it.manifest.manifestDescriptor.length > 0 &&
                it.chunkCount >= 0 && (it.chunkCount == 0L) == (item.descriptor.length == 0L) && it.observedAtMillis >= 0 }
        }
        fun readback(value: LanSyncGroupCloudTargetObservation) = value !is LanSyncGroupCloudTargetObservation.Whole || value.observation.origin == WeakWebDavObservationOrigin.EXISTING_ATTEMPT_READBACK.name
        val valid = when (val value = row.evidence) {
            is LanSyncGroupCloudTargetEvidence.Existing -> matches(value.readback, false) && readback(value.readback)
            is LanSyncGroupCloudTargetEvidence.Appended -> {
                val one = value.append; val two = value.readback
                val same = when {
                    one is LanSyncGroupCloudTargetObservation.Whole && two is LanSyncGroupCloudTargetObservation.Whole -> one.observation.let { a -> two.observation.let { b ->
                        a.route == b.route && a.key == b.key && a.descriptor == b.descriptor && a.operation == b.operation && a.attempt == b.attempt } }
                    one is LanSyncGroupCloudTargetObservation.Chunks && two is LanSyncGroupCloudTargetObservation.Chunks -> one.observation.let { a -> two.observation.let { b ->
                        a.logicalKey == b.logicalKey && a.logicalDescriptor == b.logicalDescriptor && a.logicalOperation == b.logicalOperation && a.manifest == b.manifest && a.chunkCount == b.chunkCount } }
                    else -> false
                }
                matches(one, true) && matches(two, true) && readback(two) && same
            }
        }
        require(valid) { "SYNC_GROUP_PARTIAL_EVIDENCE_CHANGED" }
    }

    private fun exactId(metadata: CiphertextObjectMetadata) = lanSyncHash("CCB-SYNC-PARTIAL-EXACT/1\n${metadata.key.kind.name}\n${metadata.key.id.value}\n${metadata.descriptor.length}\n${metadata.descriptor.sha256.hex}\n".toByteArray(Charsets.US_ASCII))
    private fun recordKey(metadata: CiphertextObjectMetadata) = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.parse(exactId(metadata).take(32)))
    private fun recordOperation(metadata: CiphertextObjectMetadata, domain: String) = SyncStoreId.parse(lanSyncHash("CCB-SYNC-PARTIAL-$domain/1\n${exactId(metadata)}\n".toByteArray(Charsets.US_ASCII)).take(32))
    private fun LanSyncGroupTargetObject.ref() = LanSyncGroupPartialObjectRef(key, descriptor)
    private fun planId(plan: LanSyncGroupPartialTargetPlan) = lanSyncHash(lanSyncJson.encodeToJsonElement(LanSyncGroupPartialTargetPlan.serializer(), plan))
    private fun planPath(id: String) = root.resolve("partial-plan-$id.json")
    private fun receiptPointer(plan: LanSyncGroupPartialTargetPlan) = root.resolve("partial-receipt-current-${planId(plan)}.json")
    private fun fileHash(path: Path) = LanSyncResourceStore.hashFile(path, Files.size(path))
    private fun directory(name: String) = root.resolve(name).also { if (!Files.exists(it, NOFOLLOW_LINKS)) Files.createDirectory(it); PrivateSyncFile.checked(it, true) }
    private fun openStore(path: Path): FileCiphertextStore {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            PrivateSyncFile.checked(path, true)
            for (name in listOf("store.id", "store.lock")) PrivateSyncFile.checked(path.resolve(name))
            for (name in listOf("objects", "commits", "operations", "staging")) PrivateSyncFile.checked(path.resolve(name), true)
        }
        return FileCiphertextStore(path)
    }
    private fun <T> immutable(path: Path, serializer: KSerializer<T>, value: T) {
        val prior = LanSyncJsonFiles.read(path, serializer)
        if (prior != null) require(prior == value) { "SYNC_GROUP_PARTIAL_IMMUTABLE_CHANGED" }
        else LanSyncJsonFiles.write(path, serializer, value)
    }
    private fun guardedOutput(output: OutputStream) = object : OutputStream() {
        override fun write(value: Int) { checkLive(); output.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { checkLive(); output.write(bytes, offset, length) }
    }
    private fun liveInput(input: InputStream) = object : FilterInputStream(input) {
        override fun read(): Int { checkLive(); val value = `in`.read(); checkLive(); return value }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int { checkLive(); val count = `in`.read(bytes, offset, length); checkLive(); return count }
    }
    /** Local guards are opaque to remote candidate-integrity retry, even for the same exception type. */
    private class LiveFailure(val original: Throwable) : RuntimeException(null, null, false, false)
    private fun checkLive() { try { proof.requireLive() } catch (failure: Throwable) { throw LiveFailure(failure) } }
    private fun <T> guarded(work: () -> T): T {
        check(!lock.isHeldByCurrentThread) { "SYNC_GROUP_PARTIAL_REENTRY" }; lock.lockInterruptibly()
        try {
            PrivateSyncFile.checked(root, true)
            FileChannel.open(root.resolve("partial-publication.lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel -> channel.lock().use { return work() } }
        } catch (failure: LiveFailure) { throw failure.original }
        catch (failure: LanSyncGroupTargetSourceIoFailure) {
            val live = failure.original as? LiveFailure
            if (live != null) throw live.original
            throw failure
        }
        finally { lock.unlock() }
    }
    private companion object { val HEX = Regex("[a-f0-9]{64}"); val locks = ConcurrentHashMap<Path, ReentrantLock>() }
}
