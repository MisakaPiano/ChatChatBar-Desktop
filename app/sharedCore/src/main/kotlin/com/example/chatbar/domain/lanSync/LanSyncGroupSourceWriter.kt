package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class LanSyncGroupSourceCommit internal constructor(val entry: SyncAuthorJournalEntry, val metadata: SyncAuthenticatedGroupObject,
    val publication: LanSyncGroupPublishPlan, internal val expectedApplied: Map<String, LanSyncGroupAppliedUnit?>,
    internal val coverage: Set<String>, internal val restorations: Map<String, Set<String>> = emptyMap())

/** Source signing owner; native preparation requires the platform data gate. Queue-only delivery/receipt do not. */
class LanSyncGroupSourceWriter(private val root: Path, private val workspace: LanSyncWorkspace, private val identity: LanSyncTlsIdentity,
    private val journal: SyncAuthorJournal, private val authority: () -> SyncDeviceLedger,
    private val cipher: (SyncStoreId) -> SyncStreamingCipher, private val outbox: (SyncStoreId) -> SyncEncryptedOutbox,
    private val currentView: () -> SyncInboxReconciliation, private val nativeState: () -> LanSyncGroupNativeState,
    private val projection: LanSyncProjection = LanSyncProjection(), private val observe: (String) -> Unit = {},
    private val restorationRevisions: () -> Map<String, Set<String>> = { emptyMap() },
    private val resolutionReservations: () -> Set<String> = { emptySet() }) {
    @Serializable private data class Blob(val operation: SyncStoreId, val key: CiphertextObjectKey, val plaintext: SyncPlaintextIdentity)
    @Serializable private data class Resolution(val revision: String, val binding: String)
    @Serializable private data class Source(val format: Int, val operation: SyncStoreId, val device: String, val group: SyncStoreId,
        val ledger: SyncVersionRef, val heads: Set<SyncVersionRef>, val parents: Set<SyncVersionRef>, val authorHead: SyncVersionRef?, val coverage: Set<String>,
        val changes: Map<String, SyncUnitChange>, val review: String, val checkpoint: String, val checkpointHash: String?,
        val rawSource: String, val sourceHashes: Map<String, String>, val projection: String,
        val applied: Map<String, LanSyncGroupAppliedUnit?>, val epoch: SyncStoreId, val commitment: String, val blobs: Map<String, Blob>,
        val restorations: Map<String, Set<String>> = emptyMap(), val resolution: Resolution? = null,
        val blobReuseOwner: SyncStoreId? = null)
    @Serializable private data class Delivery(val source: String, val version: SyncVersionRef, val epoch: SyncStoreId, val commitment: String,
        val metadataOperation: SyncStoreId, val metadataKey: CiphertextObjectKey, val blobs: Map<String, Blob>,
        val blobReuseOwner: SyncStoreId? = null)
    private val lock: ReentrantLock
    private val work: Path; private val publications: Path
    init {
        PrivateSyncFile.checked(root, true); projection.validate(); lock = locks.computeIfAbsent(root) { ReentrantLock() }
        fun directory(name: String) = root.resolve(name).also { if (!Files.exists(it, NOFOLLOW_LINKS)) Files.createDirectory(it); PrivateSyncFile.checked(it, true) }
        work = directory("preparations"); publications = directory("publications")
    }
    fun commit(authorization: LanSyncGroupSourceAuthorization, operation: SyncStoreId): LanSyncGroupSourceCommit = guarded {
        val existing = readSource(operation)
        if (existing != null) {
            require(existing.review == authorization.binding) { "SYNC_GROUP_SOURCE_OPERATION_CHANGED" }
            journal.entry(operation)?.let { return@guarded finish(existing, it) }
            val pending = journal.state().pendingApproved
            if (pending?.request?.operation == operation) return@guarded finish(existing, sign(existing))
        }
        val native = authorization.native
        require(native.before.deviceId == identity.fingerprint && authorization.units.isNotEmpty()) { "SYNC_GROUP_SOURCE_DEVICE" }
        requireView(native.checkpoint, native.heads, native.before.coveredTypes)
        val selected = authorization.units.keys
        val resolution = authorization.resolution
        if (resolution == null) {
            val blocked = resolutionReservations()
            require(selected.all { unit -> native.components.getValue(unit).intersect(blocked).isEmpty() }) { "SYNC_GROUP_RESOLUTION_RESERVED" }
        }
        else require(operation == resolution.operation && selected == resolution.resolvedUnits && native.checkpoint == resolution.checkpoint &&
            native.heads == resolution.heads && native.expected == resolution.targetHashes && native.sourceFiles == resolution.postFiles &&
            native.appliedBefore == resolution.appliedBefore) { "SYNC_GROUP_RESOLUTION_SOURCE_CHANGED" }
        require(nativeState().units.filterKeys(selected::contains) == native.appliedBefore.filterKeys(selected::contains)) { "SYNC_GROUP_SOURCE_CONTROL_CHANGED" }
        workspace.capture(identity.fingerprint, projection).use { capture ->
            require(capture.sourceFiles == native.sourceFiles && capture.snapshot.hashes() == native.expected) { "SYNC_GROUP_SOURCE_CHANGED" }
            val author = journal.state(); require(author.pendingApproved == null) { "SYNC_AUTHOR_PENDING_APPROVED_COMMIT" }
            val history = requireNotNull(currentView().history)
            val authorHead = author.last?.version?.reference()
            require(resolution == null || resolution.authorHead == authorHead) { "SYNC_GROUP_RESOLUTION_AUTHOR_CHANGED" }
            require(authorHead == null || history.contains(authorHead)) { "SYNC_GROUP_SOURCE_LOCAL_HISTORY_MISSING" }
            val key = currentKey(); val queue = outbox(key.epoch)
            val reuse by lazy { blobReuse(key) }
            val prepared = mutableListOf<LanSyncPreparedGroupUnit>()
            try {
                val unitProofs = selected.filter { it in capture.snapshot.units() }.associateWith { unit ->
                    LanSyncGroupUnitCodec(work, capture.snapshot.coveredTypes).prepare(capture, unit).also { prepared += it }
                }
                val paths = linkedMapOf<String, Pair<SyncPlaintextIdentity, Path>>()
                unitProofs.values.forEach { unit ->
                    paths[unit.decoded.plaintext.sha256] = unit.decoded.plaintext to unit.path
                    paths[unit.decoded.membership.sha256] = unit.decoded.membership to unit.membershipPath
                    unit.decoded.dependencies.forEach { paths[it.sha256] = it to capture.blobs.getValue(it.sha256) }
                }
                val parents = native.heads + listOfNotNull(authorHead)
                val changes = selected.associateWith { unit ->
                    val previous = history.frontier(unit, parents).map { it.version }.toSet(); val proof = unitProofs[unit]?.decoded
                    SyncUnitChange(proof?.valueHash, proof?.plaintext, previous, proof?.membership)
                }
                require(resolution == null || changes.mapValues { it.value.previous } == resolution.previous) { "SYNC_GROUP_RESOLUTION_PREVIOUS_CHANGED" }
                val source = existing ?: run {
                    val revision = UUID.randomUUID().toString()
                    val checkpoint = workspace.recovery.checkpointSource(workspace.dataRoot, capture, key.group, revision, projection)
                    Source(1, operation, identity.fingerprint, key.group, native.checkpoint, native.heads, parents, authorHead, capture.snapshot.coveredTypes,
                        changes, authorization.binding, checkpoint?.transaction ?: revision, checkpoint?.snapshotHash,
                        rawBinding(capture.sourceFiles), capture.snapshot.hashes(), projection.digest(), selected.associateWith { native.appliedBefore[it] },
                        key.epoch, key.keyCommitment(), paths.mapValues { (_, value) ->
                            val slot = reuse.first.select(key, value.first, queue, reuse.second)
                            Blob(slot.operation, slot.key, slot.plaintext)
                        },
                        restorationRevisions().filterKeys(selected::contains).mapValues { it.value.toSortedSet() },
                        resolution?.let { Resolution(it.revision, it.binding) }, reuse.first.owner).also {
                        require(it.restorations.values.all { revisions -> revisions.isNotEmpty() &&
                            revisions.all { id -> id.matches(Regex("[a-f0-9-]{36}")) } }) { "SYNC_GROUP_SOURCE_RESTORATIONS" }
                        LanSyncJsonFiles.write(sourcePath(operation), Source.serializer(), it); observe("SOURCE_PREPARATION_RETAINED")
                    }
                }
                require(source.changes == changes && source.authorHead == authorHead && source.parents == parents && source.epoch == key.epoch &&
                    source.commitment == key.keyCommitment() && source.rawSource == rawBinding(capture.sourceFiles) &&
                    source.sourceHashes == capture.snapshot.hashes() && source.blobs.mapValues { it.value.plaintext } == paths.mapValues { it.value.first }) { "SYNC_GROUP_SOURCE_PREPARATION_CHANGED" }
                verifyCustody(source)
                source.blobs.forEach { (hash, slot) ->
                    val (plain, path) = paths.getValue(hash)
                    workspace.resourceStore.retain(path, plain.sha256, plain.length)
                    if (source.blobReuseOwner == null)
                        SyncGroupBlobStore(source.group, work).enqueue(queue, slot.operation, slot.key, key, path, plain)
                    else {
                        require(reuse.first.owner == source.blobReuseOwner) { "SYNC_GROUP_BLOB_REUSE_OWNER_CHANGED" }
                        reuse.first.complete(reuse.first.requireSlot(key, plain, slot.operation, slot.key), key, queue, path)
                    }
                }
                // Do not acquire native-control again from the journal's locked admission callback.
                require(nativeState().units.filterKeys(selected::contains) == native.appliedBefore.filterKeys(selected::contains)) { "SYNC_GROUP_SOURCE_CONTROL_CHANGED" }
                finish(source, sign(source))
            } finally { prepared.asReversed().forEach { it.close() } }
        }
    }
    fun commitResolvedLocal(authorization: LanSyncGroupSourceAuthorization, operation: SyncStoreId): LanSyncGroupSourceCommit {
        require(authorization.resolution != null) { "SYNC_GROUP_RESOLUTION_APPROVAL_REQUIRED" }
        return commit(authorization, operation)
    }

    /** Resume only a protected already-approved intent or signed fact, never a merely prepared proposal. */
    fun resume(operation: SyncStoreId): LanSyncGroupSourceCommit = guarded {
        val source = requireNotNull(readSource(operation)) { "SYNC_GROUP_SOURCE_PREPARATION_MISSING" }
        resumeOwned(source)
    }
    fun resumeResolved(operation: SyncStoreId, revision: String, binding: String): LanSyncGroupSourceCommit = guarded {
        val source = requireNotNull(readSource(operation)) { "SYNC_GROUP_SOURCE_PREPARATION_MISSING" }
        require(source.resolution == Resolution(revision, binding)) { "SYNC_GROUP_RESOLUTION_OPERATION_CHANGED" }
        resumeOwned(source)
    }
    private fun resumeOwned(source: Source): LanSyncGroupSourceCommit {
        val operation = source.operation
        val committed = journal.entry(operation)
        if (committed != null) return finish(source, committed)
        require(journal.state().pendingApproved?.request == request(source)) { "SYNC_GROUP_SOURCE_APPROVAL_NOT_RESERVED" }
        return finish(source, sign(source))
    }
    fun operations(): List<SyncStoreId> = guarded { sourceOperations() }
    private fun sourceOperations(): List<SyncStoreId> =
        Files.newDirectoryStream(root, "source-*.json").use { files -> files.map { path ->
            PrivateSyncFile.checked(path)
            SyncStoreId.parse(path.fileName.toString().removePrefix("source-").removeSuffix(".json")).also { requireNotNull(readSource(it)) }
        } }
    /** A protected approved signature binds these exact IDs through the complete source hash. */
    fun acknowledgedRestorations(): Map<String, Set<String>> = guarded {
        val result = linkedMapOf<String, MutableSet<String>>()
        sourceOperations().forEach { operation ->
            val source = requireNotNull(readSource(operation))
            journal.entry(operation)?.let { entry ->
                require(entry.request == request(source)) { "SYNC_GROUP_SOURCE_SIGNED_ENTRY_CHANGED" }
                source.restorations.forEach { (unit, revisions) -> result.getOrPut(unit) { linkedSetOf() }.addAll(revisions) }
            }
        }
        result.mapValues { Collections.unmodifiableSet(it.value.toSet()) }
    }
    fun acknowledgedResolutions(): Map<String, Set<String>> = guarded {
        val result = linkedMapOf<String, MutableSet<String>>()
        sourceOperations().forEach { operation ->
            val source = requireNotNull(readSource(operation))
            journal.entry(operation)?.let { entry ->
                require(entry.request == request(source)) { "SYNC_GROUP_SOURCE_SIGNED_ENTRY_CHANGED" }
                source.resolution?.let { revision -> source.changes.keys.forEach { unit -> result.getOrPut(unit) { linkedSetOf() }.add(revision.revision) } }
            }
        }
        result.mapValues { Collections.unmodifiableSet(it.value.toSet()) }
    }
    fun upload(operation: SyncStoreId, backend: CiphertextStore, target: SyncDeliveryTarget = SyncDeliveryTarget.LEGACY): LanSyncGroupPublishReceipt = guarded {
        val source = requireNotNull(readSource(operation)) { "SYNC_GROUP_SOURCE_PREPARATION_MISSING" }
        val entry = requireNotNull(journal.entry(operation)) { "SYNC_GROUP_SOURCE_NOT_SIGNED" }
        val result = finish(source, entry); publisher(result.metadata.context.epoch).upload(result.publication.metadata.operation, backend, target)
    }
    /** Delivery of an already prepared commit touches only private queues, not native data or approval. */
    fun uploadPrepared(commit: LanSyncGroupSourceCommit, backend: CiphertextStore, target: SyncDeliveryTarget): LanSyncGroupPublishReceipt = guarded {
        val source = requireNotNull(readSource(commit.entry.request.operation)) { "SYNC_GROUP_SOURCE_PREPARATION_MISSING" }
        require(journal.entry(source.operation) == commit.entry && commit.entry.request == request(source) &&
            commit.publication.reference == commit.entry.version.reference() && commit.publication.metadata.key == commit.metadata.context.key &&
            commit.publication.metadata.descriptor == commit.metadata.metadata.descriptor) { "SYNC_GROUP_SOURCE_SIGNED_ENTRY_CHANGED" }
        publisher(commit.metadata.context.epoch).upload(commit.publication.metadata.operation, backend, target)
    }
    /** Current-epoch route observation only. Inspection does not prepare, re-key or contact a backend. */
    fun receipt(operation: SyncStoreId, target: SyncDeliveryTarget): LanSyncGroupPublishReceipt? = guarded {
        val source = readSource(operation) ?: return@guarded null
        val current = authority().current(); val path = deliveryPath(operation, current.epoch)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return@guarded null
        LanSyncMetadataBudget().validate(path)
        val delivery = requireNotNull(LanSyncJsonFiles.read(path, Delivery.serializer()))
        val entry = requireNotNull(journal.entry(operation)) { "SYNC_GROUP_SOURCE_NOT_SIGNED" }
        require(entry.request == request(source) && delivery.source == hash(source) && delivery.version == entry.version.reference() &&
            delivery.epoch == current.epoch && delivery.commitment == current.keyCommitment) { "SYNC_GROUP_SOURCE_DELIVERY_CHANGED" }
        publisher(current.epoch).receipt(delivery.metadataOperation, target)
    }
    private fun sign(source: Source): SyncAuthorJournalEntry = journal.commitApprovedLive(request(source), authority, SyncAuthorCommitAdmission {
        requireView(source.ledger, source.heads, source.coverage); verifyCustody(source)
        workspace.capture(identity.fingerprint, projection).use { fresh ->
            require(fresh.snapshot.hashes() == source.sourceHashes && rawBinding(fresh.sourceFiles) == source.rawSource) { "SYNC_GROUP_SOURCE_CHANGED" }
        }
    }, identity::signSyncGroupPayload)
    private fun finish(source: Source, entry: SyncAuthorJournalEntry): LanSyncGroupSourceCommit {
        require(entry.request == request(source) && entry.version.author == identity.fingerprint) { "SYNC_GROUP_SOURCE_SIGNED_ENTRY_CHANGED" }
        verifyCustody(source)
        val key = currentKey(); val queue = outbox(key.epoch); val path = deliveryPath(source.operation, key.epoch)
        val reuse by lazy { blobReuse(key) }
        val sourceHash = hash(source)
        val delivery = LanSyncJsonFiles.read(path, Delivery.serializer()) ?: Delivery(sourceHash, entry.version.reference(), key.epoch, key.keyCommitment(),
            SyncStoreId.random(), CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()), source.blobs.mapValues { (_, slot) ->
                if (source.epoch == key.epoch) slot else {
                    val selected = reuse.first.select(key, slot.plaintext, queue, reuse.second)
                    Blob(selected.operation, selected.key, selected.plaintext)
                }
            }, if (source.epoch == key.epoch) source.blobReuseOwner else reuse.first.owner)
            .also { LanSyncJsonFiles.write(path, Delivery.serializer(), it); observe("SOURCE_DELIVERY_SLOTS_RETAINED") }
        require(delivery.source == sourceHash && delivery.version == entry.version.reference() && delivery.epoch == key.epoch &&
            delivery.commitment == key.keyCommitment() && delivery.blobs.mapValues { it.value.plaintext } == source.blobs.mapValues { it.value.plaintext }) { "SYNC_GROUP_SOURCE_DELIVERY_CHANGED" }
        // Reopening an exact delivery needs its pinned owner, not a scan of unrelated source history.
        val retainedReuse by lazy { LanSyncGroupBlobReuse(root, key.group, identity.fingerprint, setOfNotNull(delivery.blobReuseOwner)) }
        val bindings = delivery.blobs.mapValues { (hash, slot) ->
            val owned = delivery.blobReuseOwner?.let {
                retainedReuse.requireSlot(key, slot.plaintext, slot.operation, slot.key)
            }
            val ready = if (owned == null) queue.entry(slot.operation) else retainedReuse.entry(owned, queue,
                source.epoch == key.epoch || Files.exists(publications.resolve("publication-${delivery.metadataOperation.value}.json"), NOFOLLOW_LINKS))
            if (ready != null) {
                require(ready.context == SyncEncryptionContext(source.group, key.epoch, slot.key) && ready.plaintext == slot.plaintext) { "SYNC_GROUP_SOURCE_BLOB_CHANGED" }
                SyncEnvelopeBlobBinding(ready.plaintext, ready.request.key, ready.context.epoch, ready.request.descriptor)
            } else outbox(source.epoch).readPlaintext(source.blobs.getValue(hash).operation, work).use { original ->
                require(original.plaintext.identity == slot.plaintext && original.entry.context == SyncEncryptionContext(source.group, source.epoch,
                    source.blobs.getValue(hash).key) && original.keyCommitment == source.commitment) { "SYNC_GROUP_SOURCE_BLOB_CHANGED" }
                if (owned == null) SyncGroupBlobStore(source.group, work).enqueue(queue, slot.operation, slot.key, key, original.plaintext.path, slot.plaintext)
                else retainedReuse.complete(owned, key, queue, original.plaintext.path).let {
                    SyncEnvelopeBlobBinding(it.plaintext, it.request.key, it.context.epoch, it.request.descriptor)
                }
            }
        }
        val metadata = SyncGroupObjectCodec(work).enqueue(queue, delivery.metadataOperation, delivery.metadataKey, key,
            SyncGroupEnvelope(2, SyncGroupObjectKind.VERSION, version = entry.version, blobs = bindings))
        val proofs = source.changes.filterValues { it.content != null }.map { (unit, change) ->
            val plain = requireNotNull(change.content)
            if (workspace.resourceStore.completed(plain.sha256, plain.length) == null) outbox(source.epoch).readPlaintext(source.blobs.getValue(plain.sha256).operation, work).use {
                require(it.plaintext.identity == plain && it.entry.context == SyncEncryptionContext(source.group, source.epoch,
                    source.blobs.getValue(plain.sha256).key) && it.keyCommitment == source.commitment) { "SYNC_GROUP_SOURCE_BLOB_CHANGED" }
                workspace.resourceStore.retain(it.plaintext.path, plain.sha256, plain.length)
            }
            LanSyncGroupUnitCodec(work, source.coverage).readCached(workspace.resourceStore, unit, change)
        }
        val known = currentView().authenticatedVersions.mapNotNull { it.envelope.version }
        val publication = publisher(key.epoch).prepare(metadata, proofs, delivery.blobs.mapValues { it.value.operation }, known)
        observe("SOURCE_SIGNED_DELIVERY_READY")
        return LanSyncGroupSourceCommit(entry, SyncGroupObjectCodec(work).readQueued(queue, delivery.metadataOperation, entry.version.reference()), publication,
            Collections.unmodifiableMap(source.applied.toMap()), Collections.unmodifiableSet(source.coverage.toSet()),
            Collections.unmodifiableMap(source.restorations.mapValues { Collections.unmodifiableSet(it.value.toSet()) }))
    }
    private fun request(source: Source) = SyncAuthorCommitRequest(source.operation, source.ledger, source.parents, source.changes,
        source.checkpoint, source.rawSource, lanSyncHash(buildJsonObject { put("review", source.review); put("preparation", hash(source)) }),
        SyncAuthorHeadExpectation(source.authorHead))
    private fun verifyCustody(source: Source) {
        val entry = workspace.recovery.read(source.checkpoint)
        if (entry == null) require(source.checkpointHash == null && source.sourceHashes.isEmpty() && source.rawSource == rawBinding(emptyMap())) { "SYNC_GROUP_SOURCE_ORIGINALS_MISSING" }
        else {
            require(entry.sourceCheckpointGroup == source.group.value && entry.sourceDevice == identity.fingerprint && entry.hasLocalOriginals &&
                entry.snapshotHash == source.checkpointHash && entry.rawSourceBinding == source.rawSource &&
                workspace.recovery.snapshot(entry).hashes() == source.sourceHashes && workspace.recovery.sourceProjection(entry) == projection) { "SYNC_GROUP_SOURCE_ORIGINALS_CHANGED" }
            workspace.recovery.verify(entry)
        }
    }
    private fun readSource(operation: SyncStoreId): Source? {
        val path = sourcePath(operation); if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        LanSyncMetadataBudget().validate(path)
        return requireNotNull(LanSyncJsonFiles.read(path, Source.serializer())).also {
            require(it.format == 1 && it.operation == operation && it.device == identity.fingerprint && it.group == authority().trustAnchor.group &&
                it.projection == projection.digest() && it.blobs.all { (hash, slot) -> hash == slot.plaintext.sha256 && slot.key.kind == CiphertextObjectKind.BLOB } &&
                it.restorations.all { (unit, revisions) -> unit in it.changes && revisions.isNotEmpty() &&
                    revisions.all { revision -> revision.matches(Regex("[a-f0-9-]{36}")) } } &&
                (it.resolution?.let { revision -> revision.revision.matches(Regex("[a-f0-9-]{36}")) &&
                    revision.binding.matches(Regex("[a-f0-9]{64}")) } != false)) { "SYNC_GROUP_SOURCE_PRIVATE_CONTEXT" }
        }
    }
    private fun requireView(checkpoint: SyncVersionRef, heads: Set<SyncVersionRef>, coverage: Set<String>) {
        val key = currentKey(); val ledger = authority(); val view = currentView()
        require(ledger.current().reference() == checkpoint && ledger.current().epoch == key.epoch && ledger.current().coveredTypes == coverage &&
            !view.trustBlocked && view.ledgerCheckpoint == checkpoint && view.history?.heads() == heads) { "SYNC_GROUP_SOURCE_VIEW_CHANGED" }
    }
    private fun currentKey(): SyncStreamingCipher {
        val ledger = authority(); val current = ledger.current()
        require(current.members[identity.fingerprint]?.let { it.active && it.certificate == Base64.getEncoder().encodeToString(identity.certificate.encoded) } == true) { "SYNC_GROUP_SOURCE_AUTHORITY_CHANGED" }
        val key = cipher(current.epoch)
        require(key.group == current.group && key.epoch == current.epoch && key.keyCommitment() == current.keyCommitment) { "SYNC_GROUP_SOURCE_AUTHORITY_CHANGED" }
        return key
    }
    private fun publisher(epoch: SyncStoreId) = LanSyncGroupPublicationQueue(publications, authority(), outbox(epoch), identity.fingerprint, currentLedger = authority)
    /** Retained source/delivery pins distinguish a new reservation from a deleted already-owned cache. */
    private fun blobReuse(key: SyncStreamingCipher): Pair<LanSyncGroupBlobReuse, List<LanSyncGroupBlobSeed>> {
        val sources = sourceOperations().map { requireNotNull(readSource(it)) }
        val owners = sources.mapNotNullTo(linkedSetOf()) { it.blobReuseOwner }
        val seeds = mutableListOf<LanSyncGroupBlobSeed>()
        val pending = journal.state().pendingApproved?.request
        sources.forEach { source ->
            val signed = journal.entry(source.operation)
            require(signed == null || signed.request == request(source)) { "SYNC_GROUP_SOURCE_SIGNED_ENTRY_CHANGED" }
            if (source.epoch == key.epoch && source.commitment == key.keyCommitment()) source.blobs.values.forEach { slot ->
                seeds += LanSyncGroupBlobSeed(source.blobReuseOwner, slot.plaintext, slot.operation, slot.key,
                    signed != null || pending?.operation == source.operation)
            }
        }
        val byHash = sources.associateBy(::hash)
        Files.newDirectoryStream(root, "delivery-*.json").use { files -> files.forEach { path ->
            LanSyncMetadataBudget().validate(path)
            val delivery = requireNotNull(LanSyncJsonFiles.read(path, Delivery.serializer()))
            val source = requireNotNull(byHash[delivery.source]) { "SYNC_GROUP_SOURCE_DELIVERY_CHANGED" }
            val signed = requireNotNull(journal.entry(source.operation)) { "SYNC_GROUP_SOURCE_NOT_SIGNED" }
            require(path == deliveryPath(source.operation, delivery.epoch) && delivery.version == signed.version.reference() &&
                delivery.metadataKey.kind == CiphertextObjectKind.COMMIT && delivery.commitment.matches(Regex("[a-f0-9]{64}")) &&
                delivery.blobs.mapValues { it.value.plaintext } == source.blobs.mapValues { it.value.plaintext } &&
                delivery.blobs.values.all { it.key.kind == CiphertextObjectKind.BLOB } &&
                (delivery.epoch != source.epoch || (delivery.commitment == source.commitment && delivery.blobs == source.blobs &&
                    delivery.blobReuseOwner == source.blobReuseOwner))) { "SYNC_GROUP_SOURCE_DELIVERY_CHANGED" }
            delivery.blobReuseOwner?.let(owners::add)
            if (delivery.epoch == key.epoch && delivery.commitment == key.keyCommitment()) {
                val required = source.epoch == key.epoch || Files.exists(
                    publications.resolve("publication-${delivery.metadataOperation.value}.json"), NOFOLLOW_LINKS)
                delivery.blobs.values.forEach { slot -> seeds += LanSyncGroupBlobSeed(delivery.blobReuseOwner,
                    slot.plaintext, slot.operation, slot.key, required) }
            }
        } }
        return LanSyncGroupBlobReuse(root, key.group, identity.fingerprint, owners) to seeds
    }
    private fun sourcePath(operation: SyncStoreId) = root.resolve("source-${operation.value}.json")
    private fun deliveryPath(operation: SyncStoreId, epoch: SyncStoreId) = root.resolve("delivery-${operation.value}-${epoch.value}.json")
    private fun hash(source: Source): String {
        val json = lanSyncJson.encodeToJsonElement(Source.serializer(), source).jsonObject
        // Preserve existing approved request/delivery hashes when the optional extension is absent.
        val withRestorations = if (source.restorations.isEmpty()) JsonObject(json - "restorations") else json
        val withResolution = if (source.resolution == null) JsonObject(withRestorations - "resolution") else withRestorations
        return lanSyncHash(if (source.blobReuseOwner == null) JsonObject(withResolution - "blobReuseOwner") else withResolution)
    }
    private fun rawBinding(files: Map<String, String>) = lanSyncHashSequence(files.toSortedMap().asSequence().map { (path, hash) -> buildJsonObject { put("path", path); put("hash", hash) } })
    private fun <T> guarded(work: () -> T): T {
        check(!lock.isHeldByCurrentThread) { "SYNC_GROUP_SOURCE_REENTRY" }; lock.lockInterruptibly()
        try { PrivateSyncFile.checked(root, true); val path = root.resolve("source.lock")
            FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS).use { channel -> PrivateSyncFile.checked(path); channel.lock().use { return work() } }
        } finally { lock.unlock() }
    }
    companion object { private val locks = ConcurrentHashMap<Path, ReentrantLock>() }
}
