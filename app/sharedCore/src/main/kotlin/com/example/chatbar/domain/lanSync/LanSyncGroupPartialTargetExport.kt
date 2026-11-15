package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.DataOutputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Collections
import kotlinx.serialization.Serializable

@Serializable data class LanSyncGroupPartialUnitRef(val version: SyncVersionRef, val unit: String)
@Serializable data class LanSyncGroupPartialObjectRef(val key: CiphertextObjectKey, val descriptor: CiphertextDescriptor) {
    internal fun metadata() = CiphertextObjectMetadata(key, descriptor)
}
@Serializable enum class LanSyncGroupPartialObjectRole { VAULT, RESOURCE, CAPSULE, NOTICE, LEDGER, VERSION }
@Serializable data class LanSyncGroupPartialObject(val role: LanSyncGroupPartialObjectRole, val slot: String,
    val location: LanSyncGroupPartialObjectRef)

/** Wanted is the entire original frozen admission view, never the currently available subset. */
@Serializable data class LanSyncGroupPartialTargetScope(val group: SyncStoreId, val checkpoint: SyncVersionRef,
    val epoch: SyncStoreId, val commitment: String, val heads: Set<SyncVersionRef>,
    val wantedVersionRefs: Set<SyncVersionRef>, val wantedUnitRefs: Set<LanSyncGroupPartialUnitRef>,
    val wantedControlSlots: Set<String>, val scopeHash: String)

@Serializable data class LanSyncGroupPartialUnitPackage(val ref: LanSyncGroupPartialUnitRef,
    val container: LanSyncGroupPartialObjectRef, val metadataClosure: Set<SyncVersionRef>,
    val objects: Set<LanSyncGroupPartialObjectRef>)
@Serializable data class LanSyncGroupPartialMembership(val ref: LanSyncGroupPartialUnitRef,
    val container: LanSyncGroupPartialObjectRef, val identity: SyncPlaintextIdentity,
    val objectRef: LanSyncGroupPartialObjectRef, val derivedFromBody: Boolean)
@Serializable enum class LanSyncGroupPartialPendingReason {
    CONTROL_UNAVAILABLE, METADATA_UNAVAILABLE, CAUSAL_METADATA_UNAVAILABLE, AUTHOR_FORK,
    MEMBERSHIP_UNAVAILABLE, UNIT_CONTENT_UNAVAILABLE, RECORD_HISTORY_UNAVAILABLE,
    RECORD_REMOVAL_REVIEW, VERSION_BINDINGS_UNVERIFIED
}
@Serializable data class LanSyncGroupPartialPending(val reason: LanSyncGroupPartialPendingReason,
    val version: SyncVersionRef? = null, val unit: String? = null, val control: String? = null,
    val dependencyStage: String? = null)

/** This is a local authenticated subset, not a cloud observation or a native-ready plan. */
@Serializable data class LanSyncGroupPartialTargetSelection(val scope: LanSyncGroupPartialTargetScope,
    val metadataRefs: Set<SyncVersionRef>, val completeUnitPackages: List<LanSyncGroupPartialUnitPackage>,
    val membershipEvidence: List<LanSyncGroupPartialMembership>, val exactObjects: List<LanSyncGroupPartialObject>,
    val pending: List<LanSyncGroupPartialPending>, val rejectedCandidates: Int) {
    val controlsPending: Set<String> get() = pending.mapNotNull { it.control }.toSet()
    val originalScopeComplete: Boolean get() = pending.isEmpty() && metadataRefs == scope.wantedVersionRefs &&
        completeUnitPackages.map { it.ref }.toSet() == scope.wantedUnitRefs
}

/** Caller must keep this owner alive until its exact bytes have been observed or durably retained. */
internal class LanSyncGroupPartialTargetPreparation internal constructor(val selection: LanSyncGroupPartialTargetSelection,
    private val pool: LanSyncGroupTargetStagePool, stages: List<LanSyncGroupTargetStage>, private val live: () -> Unit) : AutoCloseable {
    private var closed = false
    private fun requireOpen() { check(!closed) { "SYNC_GROUP_PARTIAL_CLOSED" }; live() }
    private val lens = lanSyncTargetLens(stages, ::requireOpen)
    val source: CiphertextStore = object : CiphertextStore by lens {
        override fun metadata(key: CiphertextObjectKey): CiphertextObjectMetadata? { requireOpen(); return lens.metadata(key) }
        override fun readTo(key: CiphertextObjectKey, destination: java.io.OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
            requireOpen(); return lens.readTo(key, destination, expected)
        }
    }
    override fun close() { if (!closed) { closed = true; pool.close() } }
}

/** Independent partial export. No signing, author allocation, native mutation or target receipt. */
internal class LanSyncGroupPartialTargetExport(val trust: SyncGroupTrust, val device: String,
    val ledger: SyncDeviceLedger, view: SyncInboxReconciliation,
    val vault: SyncManagementVaultContext, val vaultDescriptor: CiphertextDescriptor,
    val source: CiphertextStore, val authority: () -> SyncDeviceLedger,
    val cipher: (SyncStoreId) -> SyncStreamingCipher, private val custody: SyncGroupCustody,
    val cache: LanSyncResourceStore, val validateResource: (String, Path, LanSyncRecord) -> Unit,
    private val work: Path) {
    private val current = ledger.current()
    private val history = requireNotNull(view.history).snapshot()
    private val view = view.copy(history = history, authenticatedVersions = view.authenticatedVersions.toList())
    private val versions = history.admittedVersions().sortedBy { it.id.value }
    private val byRef = versions.associateBy { it.reference() }
    private val keySlots = ledger.history().map { it.epoch }.distinct().flatMap { epoch ->
        current.members.filterValues { it.active }.keys.map { recipient -> "${epoch.value}:$recipient" }
    }.toSet()
    private val noticeSlots = SyncLedgerNotice.revocations(ledger).keys
    val scope: LanSyncGroupPartialTargetScope

    init {
        PrivateSyncFile.checked(work, true)
        require(!view.trustBlocked && view.ledgerCheckpoint == current.reference()) { "SYNC_GROUP_TARGET_ADMISSION_REQUIRED" }
        require(vault.group == trust.group && vault.genesis == trust.genesis &&
            vault.owner == SyncDeviceSignatures.decode(trust.ownerCertificate).fingerprint) { "SYNC_GROUP_TARGET_VAULT_CHANGED" }
        requireLive(); requireCurrentKey()
        val refs = immutableSet(versions.map { it.reference() })
        val units = immutableSet(versions.flatMap { version -> version.changes.keys.map { LanSyncGroupPartialUnitRef(version.reference(), it) } })
        val controls = immutableSet(listOf("vault", "ledger") + keySlots.map { "capsule:$it" } + noticeSlots.map { "notice:$it" })
        val heads = immutableSet(history.heads())
        scope = LanSyncGroupPartialTargetScope(trust.group, current.reference(), current.epoch, current.keyCommitment,
            heads, refs, units, controls, scopeHash(heads, refs, units, controls))
    }

    fun prepareAvailable(): LanSyncGroupPartialTargetPreparation = prepare(source, null, null)

    /** Owned exact retained bytes remain available during a cold retry; all original checks still run. */
    internal fun prepareAvailable(retainedObjects: List<LanSyncGroupPartialObject>, retained: CiphertextStore): LanSyncGroupPartialTargetPreparation {
        val known = retainedObjects.map { it.location.metadata() }.toSet()
        val overlay = object : CiphertextStore by source {
            private val commits by lazy { (inventory(source) + known.filter { it.key.kind == CiphertextObjectKind.COMMIT }).distinct().sortedWith(metadataOrder) }
            override fun readTo(key: CiphertextObjectKey, destination: java.io.OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
                val matches = known.filter { it.key == key && (expected == null || it.descriptor == expected) }
                require(matches.size <= 1) { "SYNC_GROUP_PARTIAL_EXACT_DESCRIPTOR_REQUIRED" }
                return if (matches.isNotEmpty()) retained.readTo(key, destination, matches.single().descriptor) else source.readTo(key, destination, expected)
            }
            override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage {
                requireLive(); require(pageSize in 1..1000)
                val index = cursor?.token?.removePrefix("partial-retained:")?.toIntOrNull() ?: if (cursor == null) 0 else error("SYNC_GROUP_PARTIAL_CURSOR_CHANGED")
                require(index in 0..commits.size) { "SYNC_GROUP_PARTIAL_CURSOR_CHANGED" }
                val end = minOf(commits.size.toLong(), index.toLong() + pageSize).toInt()
                return CommitPage(commits.subList(index, end), if (end < commits.size) CommitCursor("partial-retained:$end") else null)
            }
        }
        return prepare(overlay, null, null)
    }

    /** Rebuilds only what the exact observed ciphertext lens can prove, with the original wanted scope. */
    internal fun observeAvailable(exactObjects: List<LanSyncGroupPartialObject>, observed: CiphertextStore): LanSyncGroupPartialTargetPreparation =
        prepare(observed, exactObjects, null)

    /** Reauthenticates the exact observed subset. No listing or warm-cache-only claim is accepted. */
    fun validateAvailable(selection: LanSyncGroupPartialTargetSelection, observed: CiphertextStore) {
        require(selection.scope == scope) { "SYNC_GROUP_PARTIAL_SCOPE_CHANGED" }
        require(selection.exactObjects.distinct() == selection.exactObjects && selection.rejectedCandidates >= 0) { "SYNC_GROUP_PARTIAL_SELECTION_CHANGED" }
        prepare(observed, selection.exactObjects, selection).use { actual ->
            val checked = actual.selection
            require(checked.metadataRefs == selection.metadataRefs && checked.completeUnitPackages == selection.completeUnitPackages &&
                checked.membershipEvidence == selection.membershipEvidence && checked.exactObjects == selection.exactObjects &&
                checked.pending.map(::pendingIdentity) == selection.pending.map(::pendingIdentity)) { "SYNC_GROUP_PARTIAL_OBSERVATION_INCOMPLETE" }
        }
    }

    private fun prepare(backend: CiphertextStore, inventory: List<LanSyncGroupPartialObject>?,
        expected: LanSyncGroupPartialTargetSelection?): LanSyncGroupPartialTargetPreparation {
        requireLive(); requireCurrentKey()
        val pool = LanSyncGroupTargetStagePool(work, ::requireLive)
        val selected = linkedMapOf<CiphertextObjectMetadata, Pair<LanSyncGroupPartialObject, LanSyncGroupTargetStage>>()
        val actuals = linkedMapOf<SyncVersionRef, MutableList<SyncAuthenticatedGroupObject>>()
        val pending = linkedMapOf<List<Any?>, LanSyncGroupPartialPending>()
        val memberships = linkedMapOf<LanSyncGroupPartialUnitRef, LanSyncGroupPartialMembership>()
        val memberWitnesses = linkedMapOf<LanSyncGroupPartialUnitRef, LanSyncGroupMembershipEvidence>()
        val evidence = LanSyncGroupRecordEvidence(history, current.reference())
        val packages = linkedMapOf<LanSyncGroupPartialUnitRef, LanSyncGroupPartialUnitPackage>()
        var rejected = 0
        fun pend(value: LanSyncGroupPartialPending) { pending.putIfAbsent(pendingIdentity(value), value) }
        fun reject(failure: Exception) {
            lanSyncTargetCandidateFailure(failure)
            if (failure is LocalKeyBlocked) throw failure
            requireLive(); if (rejected < Int.MAX_VALUE) rejected++
        }
        fun add(role: LanSyncGroupPartialObjectRole, slot: String, stage: LanSyncGroupTargetStage) {
            pool.pin(stage)
            val row = LanSyncGroupPartialObject(role, slot, stage.metadata.ref())
            selected[stage.metadata]?.let { require(it.first == row) { "SYNC_GROUP_PARTIAL_OBJECT_ROLE_CHANGED" } }
            selected[stage.metadata] = row to stage
        }
        fun resource(stage: LanSyncGroupTargetStage) = add(LanSyncGroupPartialObjectRole.RESOURCE, stage.metadata.key.id.value, stage)
        fun raw(actual: SyncAuthenticatedGroupObject, identity: SyncPlaintextIdentity,
            trial: MutableList<LanSyncGroupTargetStage>): LanSyncGroupTargetStage {
            requireLive()
            val binding = requireNotNull(actual.envelope.blobs[identity.sha256]) { "SYNC_GROUP_PARTIAL_BINDING_MISSING" }
            require(binding.plaintext == identity) { "SYNC_GROUP_TARGET_DEPENDENCY_CHANGED" }
            val stage = pool.stage(CiphertextObjectMetadata(binding.key, binding.descriptor), backend); trial += stage
            val key = trustedKey(binding.epoch)
            val checked = LanSyncGroupTargetRawCheck(trust.group, binding.epoch, key.keyCommitment(), stage.metadata, identity)
            if (pool.checkedRaw.add(checked)) try {
                SyncGroupBlobStore(trust.group, work).read(lanSyncTargetLens(listOf(stage), ::requireLive), key, binding, identity).use {
                    cache.retain(it.path, it.identity.sha256, it.identity.length)
                }
            } catch (failure: Throwable) { pool.checkedRaw.remove(checked); throw failure }
            return stage
        }
        try {
            val controls = linkedSetOf<String>()
            try {
                val stage = pool.stage(CiphertextObjectMetadata(vault.key, vaultDescriptor), backend)
                add(LanSyncGroupPartialObjectRole.VAULT, "vault", stage); controls += "vault"
            } catch (failure: Exception) { reject(failure) }
            val candidates = inventory?.filter { it.location.key.kind == CiphertextObjectKind.COMMIT }?.map { it.location.metadata() }
                ?: this.inventory(backend)
            for (metadata in candidates.distinct().sortedWith(metadataOrder)) {
                requireLive()
                try {
                    val stage = pool.stage(metadata, backend); val prefix = lanSyncTargetPrefix(stage)
                    when {
                        SyncKeyDeliveryCodec.hasPrefix(prefix) && metadata.descriptor.length <= SyncKeyDeliveryCodec.MAX_BYTES -> {
                            val value = Files.newInputStream(stage.file.path, NOFOLLOW_LINKS).use { SyncKeyDeliveryCodec.decode(SyncKeyDeliveryCodec.read(it)) }
                            val slot = capsule(value, metadata.key)
                            if (slot in keySlots && controls.add("capsule:$slot")) add(LanSyncGroupPartialObjectRole.CAPSULE, slot, stage)
                        }
                        SyncLedgerNoticeCodec.hasPrefix(prefix) && metadata.descriptor.length <= SyncLedgerNoticeCodec.MAX_BYTES -> {
                            val value = Files.newInputStream(stage.file.path, NOFOLLOW_LINKS).use { SyncLedgerNoticeCodec.decode(SyncLedgerNoticeCodec.read(it)) }
                            val slot = notice(value, metadata.key)
                            if (controls.add("notice:$slot")) add(LanSyncGroupPartialObjectRole.NOTICE, slot, stage)
                        }
                        "ledger" !in controls && SyncCiphertextHeader.hasPrefix(prefix) -> {
                            val hint = Files.newInputStream(stage.file.path, NOFOLLOW_LINKS).use(SyncCiphertextHeader::peek)
                            if (hint.context.key == metadata.key && hint.context.group == trust.group && hint.context.epoch == current.epoch && hint.keyCommitment == current.keyCommitment) {
                                val actual = SyncGroupObjectCodec(work).read(lanSyncTargetLens(listOf(stage), ::requireLive), cipher(current.epoch), hint.context, metadata.descriptor)
                                if (actual.envelope.deviceLedger != null) { verifyRoot(actual); add(LanSyncGroupPartialObjectRole.LEDGER, "ledger", stage); controls += "ledger" }
                            }
                        }
                    }
                } catch (failure: Exception) { reject(failure) }
            }
            (scope.wantedControlSlots - controls).sorted().forEach { pend(LanSyncGroupPartialPending(LanSyncGroupPartialPendingReason.CONTROL_UNAVAILABLE, control = it)) }

            val allowedMetadata = expected?.exactObjects?.filter { it.role == LanSyncGroupPartialObjectRole.VERSION }?.map { it.location.metadata() }?.toSet()
            for (version in versions) {
                val candidatesForVersion = view.authenticatedVersions.filter { it.envelope.version?.reference() == version.reference() &&
                    it.context.epoch == current.epoch && it.keyCommitment == current.keyCommitment && (allowedMetadata == null || it.metadata in allowedMetadata) }
                    .distinctBy { it.metadata }.sortedWith(compareBy({ it.metadata.key.id.value }, { it.metadata.descriptor.sha256.hex }))
                for (witness in candidatesForVersion) try {
                    val stage = pool.stage(witness.metadata, backend)
                    val actual = SyncGroupObjectCodec(work).read(lanSyncTargetLens(listOf(stage), ::requireLive), cipher(current.epoch), witness.context,
                        witness.metadata.descriptor, version.reference())
                    verifyVersion(actual)
                    add(LanSyncGroupPartialObjectRole.VERSION, version.id.value, stage)
                    actuals.getOrPut(version.reference()) { mutableListOf() } += actual
                } catch (failure: Exception) { reject(failure) }
                if (actuals[version.reference()].isNullOrEmpty()) pend(LanSyncGroupPartialPending(LanSyncGroupPartialPendingReason.METADATA_UNAVAILABLE, version.reference()))
            }
            val inspected = view.copy(authenticatedVersions = view.authenticatedVersions + actuals.values.flatten())
            // Only already complete-AEAD raw ciphertext may back a plaintext cache hit below.
            for ((ref, values) in actuals) for ((unit, change) in byRef.getValue(ref).changes) if (change.content != null) {
                val request = LanSyncGroupPartialUnitRef(ref, unit)
                var stage: LanSyncGroupDependencyStage? = null
                for (actual in values) {
                    val trial = mutableListOf<LanSyncGroupTargetStage>()
                    try {
                        val identity = change.membership ?: change.content
                        val staged = raw(actual, identity, trial)
                        val result = LanSyncGroupDependencies(trust.group, lanSyncTargetLens(trial, ::requireLive), custody, work, cache, validateResource)
                            .membership(inspected, actual, unit)
                        stage = result.stage
                        if (stage == LanSyncGroupDependencyStage.LOCAL_CUSTODY_BLOCKED) throw LocalKeyBlocked()
                        val member = result.membership ?: continue
                        require(result.stage == LanSyncGroupDependencyStage.RECORD_MEMBERSHIP_AVAILABLE) { "SYNC_GROUP_PARTIAL_MEMBERSHIP_UNAVAILABLE" }
                        evidence.offer(member); trial.forEach(::resource)
                        memberWitnesses[request] = member
                        memberships[request] = LanSyncGroupPartialMembership(request, actual.metadata.ref(), member.identity, staged.metadata.ref(), member.validatedAgainstNativePayload)
                        break
                    } catch (failure: Exception) { reject(failure) }
                }
                if (request !in memberships) pend(LanSyncGroupPartialPending(LanSyncGroupPartialPendingReason.MEMBERSHIP_UNAVAILABLE, ref, unit, dependencyStage = stage?.name))
            }

            val decoded = linkedMapOf<Pair<CiphertextObjectMetadata, String>, LanSyncDecodedGroupUnit>()
            val forks = history.authorForks()
            for (version in versions) for ((unit, change) in version.changes.toSortedMap()) {
                val ref = version.reference(); val request = LanSyncGroupPartialUnitRef(ref, unit); val closure = closure(ref)
                if (closure.any { byRef.getValue(it).author in forks }) {
                    pend(LanSyncGroupPartialPending(LanSyncGroupPartialPendingReason.AUTHOR_FORK, ref, unit)); continue
                }
                if (!actuals.keys.containsAll(closure)) {
                    pend(LanSyncGroupPartialPending(LanSyncGroupPartialPendingReason.CAUSAL_METADATA_UNAVAILABLE, ref, unit)); continue
                }
                val prior = versions.filter { it.changes[unit]?.content != null && history.isAncestorOrSelf(it.reference(), ref) }
                    .map { LanSyncGroupPartialUnitRef(it.reference(), unit) }.toSet()
                if (!memberships.keys.containsAll(prior)) {
                    pend(LanSyncGroupPartialPending(LanSyncGroupPartialPendingReason.RECORD_HISTORY_UNAVAILABLE, ref, unit)); continue
                }
                if (change.content == null) {
                    val actual = actuals.getValue(ref).first()
                    packages[request] = LanSyncGroupPartialUnitPackage(request, actual.metadata.ref(), immutableSet(closure),
                        immutableSet(prior.map { memberships.getValue(it).objectRef }))
                    continue
                }
                val ownMembership = memberWitnesses.getValue(request)
                val keys = linkedSetOf<String>()
                LanSyncGroupMembership.visit(ownMembership.path, ownMembership.identity, keys::add)
                // Even an acknowledged removal must retain its actual historical membership.
                // The committed gate below, rather than this inventory check, decides restoration.
                val historical = evidence.checkProposal(unit, keys) { _, _ -> false }
                if (historical.missing.isNotEmpty()) {
                    pend(LanSyncGroupPartialPending(LanSyncGroupPartialPendingReason.RECORD_HISTORY_UNAVAILABLE, ref, unit)); continue
                }
                val gate = evidence.checkCommitted(ref, unit)
                if (!gate.permitsContent) {
                    pend(LanSyncGroupPartialPending(if (gate.missing.isNotEmpty()) LanSyncGroupPartialPendingReason.RECORD_HISTORY_UNAVAILABLE
                        else LanSyncGroupPartialPendingReason.RECORD_REMOVAL_REVIEW, ref, unit)); continue
                }
                var stage: LanSyncGroupDependencyStage? = null
                for (actual in actuals.getValue(ref)) {
                    val trial = mutableListOf<LanSyncGroupTargetStage>()
                    try {
                        raw(actual, change.content, trial)
                        val covered = requireNotNull(ledger.record(version.deviceLedger)).coveredTypes
                        val body = LanSyncGroupUnitCodec(work, covered).readCached(cache, unit, change)
                        change.membership?.let { raw(actual, it, trial) }; body.dependencies.forEach { raw(actual, it, trial) }
                        val result = LanSyncGroupDependencies(trust.group, lanSyncTargetLens(trial, ::requireLive), custody, work, cache, validateResource)
                            .inspect(inspected, actual, unit)
                        stage = result.stage
                        if (stage == LanSyncGroupDependencyStage.LOCAL_CUSTODY_BLOCKED) throw LocalKeyBlocked()
                        if (stage != LanSyncGroupDependencyStage.UNIT_CONTENT_AVAILABLE) continue
                        val ready = requireNotNull(result.available).decoded
                        require(ready.plaintext == body.plaintext) { "SYNC_GROUP_TARGET_DEPENDENCY_CHANGED" }
                        trial.forEach(::resource); decoded[actual.metadata to unit] = ready
                        packages.putIfAbsent(request, LanSyncGroupPartialUnitPackage(request, actual.metadata.ref(), immutableSet(closure), immutableSet(trial.map { it.metadata.ref() })))
                    } catch (failure: Exception) { reject(failure) }
                }
                if (request !in packages) pend(LanSyncGroupPartialPending(LanSyncGroupPartialPendingReason.UNIT_CONTENT_UNAVAILABLE, ref, unit, dependencyStage = stage?.name))
            }
            // A unit package never asserts that all of its unchanged original VERSION bindings arrived.
            val completeContainers = linkedMapOf<SyncVersionRef, SyncAuthenticatedGroupObject>()
            for ((ref, values) in actuals) {
                val version = byRef.getValue(ref)
                val completeContainer = values.firstOrNull { actual ->
                    val expectedBindings = linkedMapOf<String, SyncPlaintextIdentity>()
                    var complete = true
                    for ((unit, change) in version.changes) if (change.content != null) {
                        val body = decoded[actual.metadata to unit]
                        if (body == null) { complete = false; continue }
                        for (identity in listOfNotNull(body.plaintext, change.membership) + body.dependencies) {
                            if (expectedBindings.put(identity.sha256, identity)?.let { it != identity } == true) complete = false
                        }
                    }
                    complete && actual.envelope.blobs.mapValues { it.value.plaintext } == expectedBindings
                }
                if (completeContainer == null) pend(LanSyncGroupPartialPending(LanSyncGroupPartialPendingReason.VERSION_BINDINGS_UNVERIFIED, ref))
                else completeContainers[ref] = completeContainer
            }
            // Prefer one fully checked original container where possible. An authenticated side
            // candidate with unproved extra bindings cannot ride on another container's proof.
            for ((ref, actual) in completeContainers) for ((unit, change) in byRef.getValue(ref).changes) {
                val request = LanSyncGroupPartialUnitRef(ref, unit)
                memberships[request]?.let { prior ->
                    val identity = requireNotNull(change.membership ?: change.content)
                    val binding = actual.envelope.blobs.getValue(identity.sha256)
                    memberships[request] = prior.copy(container = actual.metadata.ref(), objectRef = LanSyncGroupPartialObjectRef(binding.key, binding.descriptor))
                }
                if (change.content != null) packages[request]?.let { prior ->
                    val body = decoded.getValue(actual.metadata to unit)
                    val identities = listOfNotNull(body.plaintext, change.membership) + body.dependencies
                    packages[request] = prior.copy(container = actual.metadata.ref(), objects = immutableSet(identities.map {
                        val binding = actual.envelope.blobs.getValue(it.sha256); LanSyncGroupPartialObjectRef(binding.key, binding.descriptor)
                    }))
                }
            }
            for ((request, prior) in packages.toMap()) if (byRef.getValue(request.version).changes.getValue(request.unit).content == null) {
                val historical = versions.filter { it.changes[request.unit]?.content != null && history.isAncestorOrSelf(it.reference(), request.version) }
                    .map { memberships.getValue(LanSyncGroupPartialUnitRef(it.reference(), request.unit)).objectRef }
                packages[request] = prior.copy(container = (completeContainers[request.version] ?: actuals.getValue(request.version).first()).metadata.ref(), objects = immutableSet(historical))
            }
            val neededContainers = (packages.values.map { it.container } + memberships.values.map { it.container }).toMutableSet()
            for ((ref, values) in actuals) if (values.none { it.metadata.ref() in neededContainers })
                neededContainers += (completeContainers[ref] ?: values.first()).metadata.ref()
            val neededResources = (packages.values.flatMap { it.objects } + memberships.values.map { it.objectRef }).toSet()
            val unused = selected.filterValues { (row, _) -> when (row.role) {
                LanSyncGroupPartialObjectRole.VERSION -> row.location !in neededContainers
                LanSyncGroupPartialObjectRole.RESOURCE -> row.location !in neededResources
                else -> false
            } }.keys
            unused.forEach { selected.remove(it) }
            requireLive(); requireCurrentKey()
            val rows = selected.values.map { it.first }.sortedWith(objectOrder)
            val selection = LanSyncGroupPartialTargetSelection(scope, immutableSet(actuals.keys),
                Collections.unmodifiableList(packages.values.sortedWith(compareBy({ it.ref.version.id.value }, { it.ref.unit }))),
                Collections.unmodifiableList(memberships.values.sortedWith(compareBy({ it.ref.version.id.value }, { it.ref.unit }))),
                Collections.unmodifiableList(rows), Collections.unmodifiableList(pending.values.sortedWith(pendingOrder)), rejected)
            return LanSyncGroupPartialTargetPreparation(selection, pool, rows.map { selected.getValue(it.location.metadata()).second }, ::requireLive)
        } catch (failure: Throwable) { try { pool.close() } catch (close: Throwable) { failure.addSuppressed(close) }; throw failure }
    }

    internal fun requireLive() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SYNC_GROUP_TARGET_INTERRUPTED")
        val live = authority()
        require(live.trustAnchor.group == trust.group && live.trustAnchor.genesis == trust.genesis &&
            live.ownerCertificate == trust.ownerCertificate && live.contains(trust.checkpoint) && live.current() == current &&
            live.current().members[device]?.active == true) { "SYNC_GROUP_TARGET_AUTHORITY_CHANGED" }
    }
    private fun requireCurrentKey() {
        val key = trustedKey(current.epoch)
        require(key.group == trust.group && key.epoch == current.epoch && key.keyCommitment() == current.keyCommitment) { "SYNC_GROUP_TARGET_KEY_CHANGED" }
    }
    private class LocalKeyBlocked : IllegalStateException("SYNC_GROUP_PARTIAL_LOCAL_KEY_UNTRUSTED")
    private fun trustedKey(epoch: SyncStoreId): SyncStreamingCipher {
        val key = try { cipher(epoch) } catch (failure: Exception) {
            lanSyncTargetCandidateFailure(failure)
            if (failure.message == "SYNC_GROUP_CUSTODY_KEY_MISSING") throw failure
            throw LocalKeyBlocked()
        }
        if (key.group != trust.group || key.epoch != epoch || !ledger.hasKey(epoch, key.keyCommitment(), current.reference())) throw LocalKeyBlocked()
        return key
    }
    private fun verifyVersion(actual: SyncAuthenticatedGroupObject) {
        val version = requireNotNull(actual.envelope.version)
        require(actual.context.epoch == current.epoch && actual.keyCommitment == current.keyCommitment && byRef[version.reference()] == version) { "SYNC_GROUP_TARGET_CURRENT_CONTAINER_REQUIRED" }
        ledger.admission(versions, view.authenticatedVersions + actual).requireAuthorized(version)
    }
    private fun verifyRoot(actual: SyncAuthenticatedGroupObject) {
        require(actual.context.epoch == current.epoch && actual.keyCommitment == current.keyCommitment && actual.envelope.deviceLedger == current &&
            actual.envelope.ledgerHistory == ledger.history().dropLast(1)) { "SYNC_GROUP_TARGET_LEDGER_CHANGED" }
    }
    private fun capsule(value: SyncGroupKeyDelivery, key: CiphertextObjectKey): String {
        value.verifyOwner(trust, key); val context = value.context
        require(context.authorizingLedger == current.reference() && ledger.hasKey(context.epoch, context.keyCommitment, current.reference()) &&
            current.members[context.recipient]?.let { it.active && SyncRecipientPublicKey.parse(it.recipientKey).hash == context.recipientKeyHash } == true) { "SYNC_GROUP_TARGET_CAPSULE_CHANGED" }
        return "${context.epoch.value}:${context.recipient}"
    }
    private fun notice(value: SyncLedgerNotice, key: CiphertextObjectKey): String {
        value.verifyOwner(trust, key); val context = value.context
        require(SyncLedgerNotice.revocations(ledger)[context.recipient] == context.revocation &&
            SyncRecipientPublicKey.parse(requireNotNull(ledger.record(context.revocation)).members.getValue(context.recipient).recipientKey).hash == context.recipientKeyHash) { "SYNC_GROUP_TARGET_NOTICE_CHANGED" }
        return context.recipient
    }
    private fun closure(ref: SyncVersionRef): Set<SyncVersionRef> {
        val result = linkedSetOf<SyncVersionRef>(); val queue = java.util.ArrayDeque<SyncVersionRef>(); queue.add(ref)
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!result.add(next)) continue
            val version = byRef.getValue(next)
            queue.addAll(version.parents); version.previousByAuthor?.let(queue::add); version.changes.values.forEach { queue.addAll(it.previous) }
        }
        return result
    }
    private fun inventory(backend: CiphertextStore): List<CiphertextObjectMetadata> {
        val values = linkedSetOf<CiphertextObjectMetadata>(); var cursor: CommitCursor? = null; val seen = hashSetOf<String>()
        do {
            requireLive(); cursor?.let { require(seen.add(it.token)) { "SYNC_GROUP_TARGET_LISTING_CHANGED" } }
            val page = backend.listCommits(cursor); values += page.entries; cursor = page.nextCursor
        } while (cursor != null)
        return values.toList()
    }
    private fun scopeHash(heads: Set<SyncVersionRef>, versionRefs: Set<SyncVersionRef>, units: Set<LanSyncGroupPartialUnitRef>, controls: Set<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : java.io.OutputStream() {
            override fun write(value: Int) {}
            override fun write(bytes: ByteArray, offset: Int, length: Int) {}
        }
        DataOutputStream(DigestOutputStream(sink, digest)).use { output ->
            fun ref(value: SyncVersionRef) { output.writeUTF(value.id.value); output.writeUTF(value.bodyHash) }
            fun refs(values: Set<SyncVersionRef>) { output.writeInt(values.size); values.sortedBy { it.id.value }.forEach(::ref) }
            output.writeUTF("CCB-SYNC-PARTIAL-TARGET-SCOPE/1"); output.writeUTF(trust.group.value); ref(current.reference())
            output.writeUTF(current.epoch.value); output.writeUTF(current.keyCommitment); refs(heads); refs(versionRefs)
            output.writeInt(units.size); units.sortedWith(compareBy({ it.version.id.value }, { it.unit })).forEach { ref(it.version); output.writeUTF(it.unit) }
            output.writeInt(controls.size); controls.sorted().forEach(output::writeUTF)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private companion object {
        fun CiphertextObjectMetadata.ref() = LanSyncGroupPartialObjectRef(key, descriptor)
        fun <T> immutableSet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(values.toCollection(linkedSetOf()))
        fun pendingIdentity(value: LanSyncGroupPartialPending): List<Any?> = listOf(value.reason, value.version, value.unit, value.control)
        val metadataOrder = compareBy<CiphertextObjectMetadata>({ it.key.id.value }, { it.descriptor.sha256.hex })
        val objectOrder = compareBy<LanSyncGroupPartialObject>({ it.role.ordinal }, { it.slot }, { it.location.key.id.value }, { it.location.descriptor.sha256.hex })
        val pendingOrder = compareBy<LanSyncGroupPartialPending>({ it.reason.ordinal }, { it.version?.id?.value.orEmpty() }, { it.unit.orEmpty() }, { it.control.orEmpty() })
    }
}
