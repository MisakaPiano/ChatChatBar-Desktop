package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Base64
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class LanSyncGroupNodeStage { READY, WAITING_FOR_KEY, DEVICE_REVOKED, TRUST_BLOCKED }
enum class LanSyncGroupEnrollmentProgress { NONE, IDENTITY_ONLY, HISTORY }
class LanSyncGroupNodeView internal constructor(val stage: LanSyncGroupNodeStage, val reconciliation: SyncInboxReconciliation,
    val nativeState: LanSyncGroupNativeState, val plan: LanSyncGroupNativePlan?, val unsignedPreparations: Set<SyncStoreId>,
    private val dependencies: Map<LanSyncGroupMembershipRequest, LanSyncGroupDependencyResult>, private val evidence: LanSyncGroupRecordEvidence?,
    val resolutions: List<LanSyncGroupResolutionLocalState> = emptyList(), val reservedUnits: Set<String> = emptySet(),
    val incomingReview: LanSyncGroupIncomingReview? = null) {
    val needsDependencyFetch: Boolean get() = dependencies.values.any { it.stage in setOf(
        LanSyncGroupDependencyStage.WAITING_FOR_RESOURCE, LanSyncGroupDependencyStage.WAITING_FOR_KEY,
        LanSyncGroupDependencyStage.RECORD_MEMBERSHIP_AVAILABLE, LanSyncGroupDependencyStage.RECONCILE_AGAIN) }
    internal val hasMissingResources: Boolean get() = dependencies.values.any { it.stage == LanSyncGroupDependencyStage.WAITING_FOR_RESOURCE }
    fun review(selected: Set<String>): LanSyncGroupSourceReview {
        require(selected.intersect(reservedUnits).isEmpty()) { "SYNC_GROUP_RESOLUTION_RESERVED" }
        return sourceReview(selected)
    }
    internal fun sourceReview(selected: Set<String>): LanSyncGroupSourceReview {
        val current = requireNotNull(plan) { "SYNC_GROUP_NODE_NOT_READY" }
        require(selected.intersect(current.recoveryHeldUnits).isEmpty()) { LanSyncGroupNativePlanner.RECOVERY_HELD }
        return LanSyncGroupSourceReview.prepare(current, reconciliation, selected, dependencies, requireNotNull(evidence))
    }
    internal fun resolution(capture: LanSyncCapture, choices: Map<String, LanSyncGroupResolutionChoice>, operation: String): LanSyncGroupResolutionPreview {
        val current = requireNotNull(plan) { "SYNC_GROUP_NODE_NOT_READY" }
        require(choices.keys.intersect(current.recoveryHeldUnits).isEmpty()) { LanSyncGroupNativePlanner.RECOVERY_HELD }
        return LanSyncGroupResolution.prepare(current, capture, reconciliation, dependencies, requireNotNull(evidence), choices, operation)
    }
}

/**
 * Serialized enrolled-node composition. Native calls use the supplied platform storage gate; network
 * discovery/delivery do not hold it. Enrollment, routes, scheduling and UI remain platform owners.
 */
class LanSyncGroupNode(private val root: Path, private val workspace: LanSyncWorkspace, private val platform: LanSyncPlatform,
    private val identity: LanSyncTlsIdentity, private val trust: SyncGroupTrust, private val custody: SyncGroupCustody,
    private val recipient: SyncDeviceEncryptionKey, private val dependencyStore: CiphertextStore,
    protect: (ByteArray) -> ByteArray, unprotect: (ByteArray) -> ByteArray,
    private val freshLocalRevisions: () -> Set<String>, private val validateResource: (String, Path, LanSyncRecord) -> Unit,
    private val projection: LanSyncProjection = LanSyncProjection(), private val localPresetsRequiringReview: Set<String> = emptySet(),
    private val observe: (String) -> Unit = {}) {
    private val operation = Mutex()
    private val discoveryOperation = Mutex()
    private var current: SyncInboxReconciliation? = null
    private var pendingRestorations: Map<String, Set<String>> = emptyMap()
    private var pendingResolutions: Set<String> = emptySet()
    private var heldResolutions: Set<String> = emptySet()
    private val localVersions = linkedMapOf<SyncStoreId, SyncAuthenticatedGroupObject>()
    private val actorCertificate = Base64.getEncoder().encodeToString(identity.certificate.encoded)
    private fun directory(name: String): Path = root.resolve(name).also {
        PrivateSyncFile.checked(root, true)
        if (!Files.exists(it, NOFOLLOW_LINKS)) Files.createDirectory(it)
        PrivateSyncFile.checked(it, true)
    }
    private val inboxRoot = directory("inbox"); private val work = directory("preparations"); private val queueRoot = directory("queues")
    private val prefetchWork = directory("prefetch-preparations")
    private val authorRoot = directory("author"); private val nativeRoot = directory("native")
    private val author = SyncAuthorJournal(authorRoot, trust, actorCertificate, protect, unprotect, observe)
    private val native = LanSyncGroupNativeApplyOwner(nativeRoot, workspace, trust, actorCertificate, projection,
        { custody.ledger() }, ::currentView, protect, unprotect, observe, author)
    private val resolutions = LanSyncGroupResolutionOwner(directory("resolutions"), workspace, trust, actorCertificate, projection,
        custody::ledger, ::currentView, native::state, author, protect, unprotect, observe)
    private val writer = LanSyncGroupSourceWriter(directory("source"), workspace, identity, author, { custody.ledger() }, custody::key,
        { epoch -> val path = queueRoot.resolve(epoch.value)
            if (!Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
            SyncEncryptedOutbox(path, custody.key(epoch)) }, ::currentView, native::state, projection, observe,
        restorationRevisions = { pendingRestorations }, resolutionReservations = { pendingResolutions + heldResolutions })
    init {
        require(identity.fingerprint == recipient.device && trust.group == custody.trust().group) { "SYNC_GROUP_NODE_IDENTITY" }
        // The remote worker may start alongside local inspection, after all shared inbox dirs exist.
        inbox()
    }
    private fun currentView() = requireNotNull(current) { "SYNC_GROUP_NODE_RECONCILIATION_REQUIRED" }
    private fun inbox(store: CiphertextStore = dependencyStore) = SyncGroupInbox(inboxRoot, store, custody, recipient)

    internal class MembershipRecoveryContent(val keys: Map<SyncStoreId, SyncStreamingCipher>,
        val cuts: Map<SyncVersionRef, Set<SyncVersionRef>>)

    internal suspend fun membershipRecoveryContent(graph: SyncDeviceLedger, session: SyncManagementSession,
        retainedRecoveryKeys: Map<SyncStoreId, SyncStreamingCipher>):
        MembershipRecoveryContent = serialized {
        val tips = graph.admittedTips().map { requireNotNull(graph.record(it)) }
        val branches = tips.map { tip ->
            SyncDeviceLedger(graph.trustAnchor.copy(checkpoint = tip.reference())).also { branch ->
                graph.ancestry(tip.reference()).forEach(branch::offer)
                check(branch.current().reference() == tip.reference()) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_SIGNATURE" }
            }
        }
        val inbox = inbox(localDependencies())
        val keys = session.withRecipient { inbox.recoveryHistoricalKeys(branches, it, retainedRecoveryKeys) }
        val histories = branches.associate { branch ->
            branch.current().reference() to inbox.reviewRecoveryBranch(branch, keys, localVersions.values.toList())
        }
        val alreadyApplied = exclusive { native.recoveryFrontiers() }
        require((tips.flatMap { it.acceptedHeads } + alreadyApplied).all { required -> histories.values.any { it.contains(required) } }) {
            "SYNC_GROUP_RECOVERY_CAUSAL_HISTORY"
        }
        MembershipRecoveryContent(keys, histories.mapValues { (_, history) -> history.heads() })
    }

    /** Explicit pending-profile review only; preserves every retained origin and incarnation. */
    internal suspend fun enrollmentProgress(): LanSyncGroupEnrollmentProgress = serialized { exclusive {
        val authorExists = Files.exists(authorRoot.resolve("origin.protected"), NOFOLLOW_LINKS)
        val nativeExists = Files.exists(nativeRoot.resolve("origin.protected"), NOFOLLOW_LINKS)
        val resolutionExists = Files.exists(root.resolve("resolutions/state.protected"), NOFOLLOW_LINKS)
        if (!authorExists) require(Files.newDirectoryStream(authorRoot).use { files -> files.all { it.fileName.toString() == "author.lock" } } &&
            !nativeExists && !resolutionExists) { "SYNC_GROUP_ENROLLMENT_EXISTING_EVIDENCE" }
        if (!nativeExists) require(Files.newDirectoryStream(nativeRoot).use { files -> files.all { it.fileName.toString() == "native.lock" } } &&
            !resolutionExists) { "SYNC_GROUP_ENROLLMENT_EXISTING_EVIDENCE" }
        val authorState = if (authorExists) author.state() else null
        if (authorState?.let { it.committedSequence != 0L || it.pendingApproved != null } == true) return@exclusive LanSyncGroupEnrollmentProgress.HISTORY
        val state = if (nativeExists) {
            if (Files.exists(nativeRoot.resolve("head.protected"), NOFOLLOW_LINKS)) native.state() else native.resumeNewEnrollment()
        } else null
        if (state?.let { it.completedSequence != 0L || it.pendingTransaction != null || it.units.isNotEmpty() } == true ||
            resolutionExists && resolutions.state().isNotEmpty() || writer.operations().isNotEmpty()) LanSyncGroupEnrollmentProgress.HISTORY
        else if (authorExists || nativeExists || resolutionExists) LanSyncGroupEnrollmentProgress.IDENTITY_ONLY else LanSyncGroupEnrollmentProgress.NONE
    } }

    /** Explicit new enrollment/retry only. Existing nonempty author/native histories cannot be reset. */
    suspend fun initializeNewEnrollment(expectedEnrollmentSource: Map<String, String>? = null) = serialized {
        exclusive {
            expectedEnrollmentSource?.let { expected ->
                workspace.captureGroup(identity.fingerprint, projection).use {
                    require(it.sourceFiles == expected) { "SYNC_GROUP_ENROLLMENT_LOCAL_CHANGED" }
                }
            }
            if (Files.exists(authorRoot.resolve("origin.protected"), NOFOLLOW_LINKS)) {
                val state = author.state()
                require(state.committedSequence == 0L && state.pendingApproved == null) { "SYNC_GROUP_NODE_ALREADY_USED" }
            } else author.initializeNewEnrollment()
            if (Files.exists(nativeRoot.resolve("origin.protected"), NOFOLLOW_LINKS)) {
                val state = native.resumeNewEnrollment()
                require(state.completedSequence == 0L && state.pendingTransaction == null && state.units.isEmpty()) { "SYNC_GROUP_NODE_ALREADY_USED" }
            } else native.initializeNewEnrollment()
            if (Files.exists(root.resolve("resolutions/state.protected"), NOFOLLOW_LINKS)) {
                require(resolutions.state().isEmpty()) { "SYNC_GROUP_NODE_ALREADY_USED" }
            } else resolutions.initializeNewEnrollment()
        }
    }

    /** All destinations share this node's immutable mirror. Callers must periodically rescan from null. */
    suspend fun discover(store: CiphertextStore, cursor: CommitCursor? = null, pageSize: Int = 100): SyncInboxDiscovery = withContext(Dispatchers.IO) {
        discoveryOperation.withLock { inbox(store).discover(cursor, pageSize) }
    }

    /** Import only already retained objects without sharing the route worker's network lock. */
    suspend fun discoverLocal(cursor: CommitCursor? = null, pageSize: Int = 100): SyncInboxDiscovery = serialized {
        inbox(localDependencies()).discover(cursor, pageSize)
    }

    suspend fun inspect(): LanSyncGroupNodeView = serialized { inspectOwned() }

    /** Committed local data remains inspectable while the independent route worker is blocked. */
    suspend fun inspectLocal(recoverNative: Boolean = true): LanSyncGroupNodeView = serialized {
        inspectOwned(allowNetwork = false, recoverNative = recoverNative)
    }
    private fun localDependencies(): CiphertextStore = when (val store = dependencyStore) {
        is LanSyncGroupReplicaReader -> store.cachedOnly()
        is FileCiphertextStore -> store
        else -> error("SYNC_GROUP_LOCAL_DEPENDENCY_STORE_REQUIRED")
    }

    /** Authenticates only retained local metadata; no capture, native apply or live route reads. */
    suspend fun reconcileForPrefetch(): SyncInboxReconciliation = serialized {
        inbox().reconcile(localVersions.values.toList())
    }

    /** Inspect the actual published replica without reading or authoring the local workspace. */
    internal suspend fun publishedInvitationHistory(published: CiphertextStore): Pair<SyncVersionRef, Set<SyncVersionRef>> = serialized {
        val reader = inbox(published)
        val candidates = linkedSetOf<CiphertextObjectMetadata>()
        var cursor: CommitCursor? = null
        do {
            val page = reader.discover(cursor)
            page.observations.forEach { observation ->
                require(observation.stage == SyncInboxStage.MIRRORED) { "SYNC_GROUP_INVITE_PUBLISHED_HISTORY_UNAVAILABLE" }
                candidates += CiphertextObjectMetadata(observation.key, requireNotNull(observation.descriptor))
            }
            cursor = page.nextCursor
        } while (cursor != null)
        val verified = reader.reconcilePublished(candidates)
        val incomplete = setOf(SyncInboxStage.WAITING_FOR_KEY, SyncInboxStage.WAITING_FOR_LEDGER,
            SyncInboxStage.WAITING_FOR_PARENTS, SyncInboxStage.TRUST_BLOCKED, SyncInboxStage.LOCAL_CUSTODY_BLOCKED)
        val unresolved = verified.observations.firstOrNull { it.stage in incomplete }
        if (verified.trustBlocked || unresolved != null) {
            val reason = verified.trustFailureCode ?: unresolved?.code ?: unresolved?.stage?.name
            throw IllegalArgumentException("SYNC_GROUP_INVITE_PUBLISHED_HISTORY_UNAVAILABLE", IllegalStateException(reason))
        }
        val ledger = custody.ledger().current()
        require(verified.ledgerCheckpoint == ledger.reference() && ledger.members[identity.fingerprint]?.active == true) {
            "SYNC_GROUP_INVITE_PUBLISHED_HISTORY_UNAVAILABLE"
        }
        val heads = requireNotNull(verified.history) { "SYNC_GROUP_INVITE_PUBLISHED_HISTORY_UNAVAILABLE" }.heads()
        observe("INVITATION_PUBLISHED_HISTORY_CHECKED")
        ledger.reference() to heads
    }

    /** Freeze admission only; potentially long ciphertext/resource preparation stays outside this mutex. */
    internal suspend fun targetPublication(directory: Path, target: SyncDeliveryTarget,
        vault: SyncManagementVaultContext, vaultDescriptor: CiphertextDescriptor,
        recoverNative: Boolean = true, prepare: Boolean = true): LanSyncGroupTargetPublication {
        val export = serialized {
            val (ledger, frozen) = freezeTargetAdmission(recoverNative)
            LanSyncGroupTargetExport(trust, identity.fingerprint, ledger, frozen, vault, vaultDescriptor,
                dependencyStore, custody::ledger, custody::key, custody, workspace.resourceStore, validateResource)
        }
        val publication = kotlinx.coroutines.runInterruptible(Dispatchers.IO) { LanSyncGroupTargetPublication(directory, target, export) }
        if (prepare) publication.prepare()
        return publication
    }

    /** Partial admission keeps the complete wanted scope even when full export is held. */
    internal suspend fun partialTargetPublication(directory: Path, target: SyncDeliveryTarget,
        vault: SyncManagementVaultContext, vaultDescriptor: CiphertextDescriptor,
        recoverNative: Boolean = true): LanSyncGroupPartialTargetPublication {
        val work = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
            lanSyncSafe(directory, true)
            directory.resolve("preparations").also {
                if (!java.nio.file.Files.exists(it, java.nio.file.LinkOption.NOFOLLOW_LINKS)) java.nio.file.Files.createDirectory(it)
                lanSyncSafe(it, true)
            }
        }
        val export = serialized {
            val (ledger, frozen) = freezeTargetAdmission(recoverNative)
            LanSyncGroupPartialTargetExport(trust, identity.fingerprint, ledger, frozen, vault, vaultDescriptor,
                dependencyStore, custody::ledger, custody::key, custody, workspace.resourceStore, validateResource, work)
        }
        return kotlinx.coroutines.runInterruptible(Dispatchers.IO) { LanSyncGroupPartialTargetPublication(directory, target, export) }
    }

    /** Local-only full ciphertext export. Long preparation stays outside Node/native admission. */
    internal suspend fun prepareEncryptedFile(directory: Path, vault: SyncManagementVaultContext,
        vaultDescriptor: CiphertextDescriptor, checkActive: () -> Unit): LanSyncGroupEncryptedFilePackage.Prepared {
        val owned = java.util.concurrent.atomic.AtomicReference<LanSyncGroupEncryptedFilePackage.Prepared?>()
        try {
            prepareEncryptedFile(directory, vault, vaultDescriptor, checkActive) { prepared ->
                check(owned.compareAndSet(null, prepared)) { "SYNC_GROUP_FILE_PREPARATION_REPLAY" }
            }
            return requireNotNull(owned.getAndSet(null))
        } finally { owned.getAndSet(null)?.close() }
    }
    internal suspend fun prepareEncryptedFile(directory: Path, vault: SyncManagementVaultContext,
        vaultDescriptor: CiphertextDescriptor, checkActive: () -> Unit,
        receive: (LanSyncGroupEncryptedFilePackage.Prepared) -> Unit) {
        val frozen = serialized {
            checkActive()
            val (ledger, view) = freezeTargetAdmission(recoverNative = true)
            val proof = LanSyncGroupTargetExport(trust, identity.fingerprint, ledger, view, vault, vaultDescriptor,
                localDependencies(), custody::ledger, custody::key, custody, workspace.resourceStore, validateResource, checkActive)
            proof to custody.key(ledger.current().epoch)
        }
        val target = SyncDeliveryTarget(SyncDeliveryKind.LOCAL_REPLICA, SyncStoreId.parse(lanSyncHash(
            "CCB-GROUP-FILE-EXPORT-STAGE/1\n${trust.group.value}\n${trust.genesis}\n${identity.fingerprint}".toByteArray(Charsets.UTF_8)).take(32)))
        val publication = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
            lanSyncSafe(directory, true); checkActive()
            LanSyncGroupTargetPublication(directory, target, frozen.first)
        }
        publication.prepare()
        kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
            publication.withPreparedSource { plan, source ->
                val prepared = LanSyncGroupEncryptedFilePackage.prepare(plan, source, trust, frozen.second,
                    directory.resolve("preparations"), checkActive = { checkActive(); frozen.first.requireLive() })
                try { receive(prepared) } catch (failure: Throwable) {
                    try { prepared.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    throw failure
                }
            }
        }
    }

    /** Called only under Node serialization; reads retained metadata and never a live route. */
    private suspend fun freezeTargetAdmission(recoverNative: Boolean): Pair<SyncDeviceLedger, SyncInboxReconciliation> {
        // Export never applies/recovers native plans. Shutdown callers additionally require a
        // settled source-only boundary after their final writer/source sealing has completed.
        if (!recoverNative) exclusive { requireSourceOnlyReady() }
        val reconciled = reconcileRetainedMetadata()
        val ledger = custody.ledger()
        val frozen = reconciled.copy(history = reconciled.history?.snapshot(),
            authenticatedVersions = reconciled.authenticatedVersions.toList(), observations = reconciled.observations.toList())
        return ledger to frozen
    }

    /** Cold queue replay and export share the same local-only metadata preflight. */
    private suspend fun reconcileRetainedMetadata(): SyncInboxReconciliation {
        val versions = localVersions.values.toList()
        return kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
            val local = inbox(localDependencies())
            var cursor: CommitCursor? = null
            do { cursor = local.discover(cursor).nextCursor } while (cursor != null)
            local.reconcile(versions)
        }
    }

    /** Resource I/O deliberately stays outside the source/native operation mutex. */
    suspend fun prefetch(view: SyncInboxReconciliation, applied: Map<String, LanSyncGroupAppliedUnit> = emptyMap()) = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
        if (view.trustBlocked || view.history == null || view.ledgerCheckpoint == null) return@runInterruptible
        val history = view.history.snapshot()
        val ledger = custody.ledger()
        if (ledger.current().reference() != view.ledgerCheckpoint || ledger.current().members[identity.fingerprint]?.active != true) return@runInterruptible
        val required = linkedSetOf<LanSyncGroupMembershipRequest>()
        history.admittedVersions().flatMap { it.changes.keys }.toSet().forEach { unit ->
            history.frontier(unit).forEach { required += LanSyncGroupMembershipRequest(it.version, unit) }
        }
        applied.forEach { (unit, state) -> state.frontiers.forEach { required += LanSyncGroupMembershipRequest(it, unit) } }
        val metadata = view.authenticatedVersions.filter { it.envelope.version?.reference()?.let(history::contains) == true }
            .groupBy { requireNotNull(it.envelope.version).reference() }.mapValues { (_, values) ->
                values.firstOrNull { it.context.epoch == ledger.current().epoch } ?: values.first()
            }
        val service = LanSyncGroupDependencies(trust.group, dependencyStore, custody, prefetchWork, workspace.resourceStore,
            validateResource, workspace.metadataBudget)
        metadata.values.forEach { actual ->
            val version = requireNotNull(actual.envelope.version)
            require(actual.context.group == trust.group && history.contains(version.reference())) { "SYNC_GROUP_PREFETCH_NOT_ADMITTED" }
            version.changes.filterValues { it.content != null }.keys.forEach { unit ->
                service.membership(view, actual, unit)
                if (LanSyncGroupMembershipRequest(version.reference(), unit) in required) {
                    val result = service.inspect(view, actual, unit)
                    observe("GROUP_PREFETCH_${result.stage}")
                }
            }
        }
    }

    private suspend fun inspectOwned(allowNetwork: Boolean = true, recoverNative: Boolean = true): LanSyncGroupNodeView {
        current = inbox().reconcile(localVersions.values)
        var state = exclusive {
            if (recoverNative) {
                val (recovered, pendingReload) = native.recoverForReload()
                author.state()
                reloadNative(pendingReload)
                reloadLocalRecoveries()
                val sessions = resolutions.recover()
                if (resolutions.needsReload()) { reload(sessions); resolutions.acknowledgeReload() }
                recovered
            } else {
                val before = native.state()
                workspace.requireSettledForSourceOnly()
                require(before.pendingTransaction == null && !resolutions.needsReload() &&
                    resolutions.state().none { it.stage == LanSyncGroupResolutionLocalStage.PREPARED }) { "SYNC_GROUP_SOURCE_RECOVERY_REQUIRED" }
                author.state(); before
            }
        }
        fun held(stage: LanSyncGroupNodeStage) = LanSyncGroupNodeView(stage, currentView(), state, null, emptySet(), emptyMap(), null)
        if (currentView().trustBlocked) return held(LanSyncGroupNodeStage.TRUST_BLOCKED)
        val latest = custody.ledger().current()
        if (latest.members[identity.fingerprint]?.active != true) return held(LanSyncGroupNodeStage.DEVICE_REVOKED)
        try { custody.key(latest.epoch) } catch (failure: IllegalArgumentException) {
            if (failure.message == "SYNC_GROUP_CUSTODY_KEY_MISSING") return held(LanSyncGroupNodeStage.WAITING_FOR_KEY)
            throw failure
        }
        val unsigned = recoverSources(recoverNative)
        refreshRestorations()
        refreshResolutions()
        state = exclusive { native.state() }
        val view = currentView(); val history = requireNotNull(view.history) { "SYNC_GROUP_NODE_RECONCILIATION_REQUIRED" }
        val evidence = LanSyncGroupRecordEvidence(history, requireNotNull(view.ledgerCheckpoint))
        val service = LanSyncGroupDependencies(trust.group, if (allowNetwork) dependencyStore else localDependencies(), custody, work,
            workspace.resourceStore, validateResource, workspace.metadataBudget)
        val metadata = view.authenticatedVersions.filter { it.envelope.version?.reference()?.let(history::contains) == true }
            .groupBy { requireNotNull(it.envelope.version).reference() }.mapValues { (_, candidates) ->
                candidates.firstOrNull { it.context.epoch == latest.epoch } ?: candidates.first()
            }
        metadata.values.forEach { actual ->
            requireNotNull(actual.envelope.version).changes.filterValues { it.content != null }.keys.forEach { unit ->
                service.membership(view, actual, unit).membership?.let(evidence::offer)
            }
        }
        // Full before-bodies are needed only for current frontiers and native per-unit receipts.
        val required = linkedSetOf<LanSyncGroupMembershipRequest>()
        history.admittedVersions().flatMap { it.changes.keys }.toSet().forEach { unit ->
            history.frontier(unit).forEach { required += LanSyncGroupMembershipRequest(it.version, unit) }
        }
        state.units.forEach { (unit, applied) -> applied.frontiers.forEach { reference ->
            if (!LanSyncGroupNativePlanner.wasHeldByRecovery(view, unit, applied, reference)) {
                required += LanSyncGroupMembershipRequest(reference, unit)
            }
        } }
        var retainedIndexCost = 0L
        val dependencies = required.associateWith { request ->
            val actual = requireNotNull(metadata[request.version]) { "SYNC_GROUP_APPLIED_EVIDENCE_MISSING" }
            service.inspect(view, actual, request.unit).also { result ->
                retainedIndexCost = lanSyncAddLength(retainedIndexCost,
                    512 + (result.available?.decoded?.metadataIndexCost ?: 0))
                require(retainedIndexCost <= workspace.metadataBudget.maximumIndexCost) { "SYNC_METADATA_INDEX_BUDGET" }
            }
        }
        val plan = exclusive { workspace.captureGroup(identity.fingerprint, projection).use {
            val known = it.snapshot.units().keys + state.units.keys + history.admittedVersions().flatMap { version -> version.changes.keys } + pendingResolutions
            LanSyncGroupNativePlanner.prepare(it, view, state.units, dependencies, evidence,
                freshLocalRevisions() + (pendingRestorations.keys + pendingResolutions).filter { unit -> unit in known },
                unpublishedLocalUnits = pendingResolutions, heldLocalUnits = heldResolutions,
                localPresetsRequiringReview = localPresetsRequiringReview)
        } }
        val restricted = LanSyncGroupNativePlanner.restrict(plan, pendingResolutions + heldResolutions)
        val incoming = LanSyncGroupIncomingReview.prepare(restricted)
        observe("NATIVE_PLAN_READY")
        return LanSyncGroupNodeView(LanSyncGroupNodeStage.READY, view, state, incoming.defaultPlan, Collections.unmodifiableSet(unsigned),
            Collections.unmodifiableMap(dependencies), evidence, exclusive { resolutions.state() },
            Collections.unmodifiableSet((pendingResolutions + heldResolutions).toSet()), incoming)
    }

    /** Dense author order, never random preparation-filename order or a reset counter. */
    private suspend fun recoverSources(recoverNative: Boolean = true): Set<SyncStoreId> {
        val entries = exclusive {
            val state = author.state(); val operations = writer.operations()
            val signed = operations.mapNotNull { author.entry(it) }
            require(signed.size.toLong() == state.committedSequence &&
                (state.pendingApproved == null || state.pendingApproved.request.operation in operations)) { "SYNC_GROUP_NODE_SOURCE_HISTORY_MISSING" }
            val pending = state.pendingApproved
            val active = (signed + listOfNotNull(pending)).sortedBy { it.version.authorSequence }
            active.forEachIndexed { index, entry -> require(entry.version.authorSequence == index.toLong() + 1) { "SYNC_GROUP_NODE_SOURCE_HISTORY_MISSING" } }
            active to (operations.toSet() - active.map { it.request.operation }.toSet())
        }
        for (entry in entries.first) {
            if (entry.version.reference() in currentView().recoveryHeldVersions) continue
            val commit = exclusive { writer.resume(entry.request.operation) }
            remember(commit)
            if (commit.entry.version.reference() in currentView().recoveryHeldVersions) continue
            exclusive { native.recordSource(commit, recoverNative) }
        }
        observe("LOCAL_SOURCE_HISTORY_RECOVERED")
        return entries.second
    }

    /** Frozen review authorization remains exact; this never confirms a held reduction implicitly. */
    suspend fun commit(review: LanSyncGroupSourceReview, confirmations: Map<String, String> = emptyMap(),
        operation: SyncStoreId = SyncStoreId.random(), recoverNative: Boolean = true): LanSyncGroupSourceCommit = serialized {
        if (!recoverNative) exclusive { requireSourceOnlyReady() }
        refreshRestorations()
        refreshResolutions()
        val commit = exclusive {
            if (!recoverNative) requireSourceOnlyReady()
            writer.commit(review.authorize(confirmations), operation)
        }
        remember(commit); exclusive { native.recordSource(commit, recoverNative) }; commit
    }
    suspend fun prepareResolution(choices: Map<String, LanSyncGroupResolutionChoice>, operation: String = UUID.randomUUID().toString()): LanSyncGroupResolutionPreview = serialized {
        val view = inspectOwned()
        exclusive { workspace.captureGroup(identity.fingerprint, projection).use { capture ->
            require(choices.keys.intersect(capture.blockedResourceUnits).isEmpty()) { "SYNC_GROUP_LOCAL_RESOURCE_BLOCKED" }
            view.resolution(capture, choices, operation)
        } }
    }
    /** Approved native adoption, fresh source validation, then one bound causal author operation. */
    suspend fun resolve(candidate: LanSyncGroupResolvedCandidate): LanSyncGroupSourceCommit? = serialized {
        val before = inspectOwned()
        require(candidate.preview.units.keys.intersect(before.plan?.recoveryHeldUnits.orEmpty()).isEmpty()) { LanSyncGroupNativePlanner.RECOVERY_HELD }
        val revision = candidate.preview.operation
        val adopted = try { exclusive { resolutions.adopt(candidate, writer.acknowledgedResolutions()) } }
        catch (failure: Throwable) {
            try { exclusive {
                workspace.recover()
                if (workspace.applied(revision)) reload(candidate.preview.units.values.flatMap { it.current }
                    .filter { it.type == "chat_sessions" }.mapTo(linkedSetOf()) { it.id })
            } } catch (recovery: Throwable) { failure.addSuppressed(recovery) }
            throw failure
        }
        finishResolution(revision, adopted, inspectOwned())
    }
    /** Explicit retry of a retained acknowledged decision after process death; no preview object required. */
    suspend fun resumeResolution(revision: String): LanSyncGroupSourceCommit? = serialized {
        require(UUID.fromString(revision).toString() == revision) { "SYNC_GROUP_RESOLUTION_OPERATION" }
        val after = inspectOwned()
        val retained = after.resolutions.singleOrNull { it.operation == revision } ?: error("SYNC_GROUP_RESOLUTION_MISSING")
        finishResolution(revision, retained, after)
    }
    private suspend fun finishResolution(revision: String, adopted: LanSyncGroupResolutionLocalState,
        after: LanSyncGroupNodeView): LanSyncGroupSourceCommit? {
        if (adopted.resolvedUnits.isEmpty()) return null
        require(adopted.resolvedUnits.intersect(after.plan?.recoveryHeldUnits.orEmpty()).isEmpty()) { LanSyncGroupNativePlanner.RECOVERY_HELD }
        val operation = SyncStoreId.parse(revision.replace("-", ""))
        val commit = exclusive {
            val approved = author.entry(operation) != null || author.state().pendingApproved?.request?.operation == operation
            if (approved) writer.resumeResolved(operation, revision, resolutions.decisionBinding(revision))
            else {
                val approval = resolutions.approval(revision)
                writer.commitResolvedLocal(after.sourceReview(approval.resolvedUnits).authorizeResolved(approval), operation)
            }
        }
        remember(commit); exclusive { native.recordSource(commit) }; refreshResolutions(); return commit
    }

    /** Metadata listing only; opening an entry independently verifies every retained byte. */
    suspend fun recoveryEntries(): List<LanSyncRecoveryEntry> = serialized {
        exclusive {
            Collections.unmodifiableList(workspace.recovery.list().filter {
                (it.custodyDevice ?: it.sourceDevice) == identity.fingerprint
            })
        }
    }

    /** Browse the sealed historical source without capturing or reading today's user data. */
    suspend fun browseRecovery(transaction: String): LanSyncGroupRecoveryView = serialized {
        exclusive {
            val vault = workspace.recovery
            val entry = requireNotNull(vault.read(transaction)) { "SYNC_RECOVERY_MISSING" }
            require((entry.custodyDevice ?: entry.sourceDevice) == identity.fingerprint) { "SYNC_RECOVERY_DEVICE" }
            vault.verify(entry)
            val snapshot = vault.snapshot(entry)
            val retained = snapshot.units()
            val unavailable = LanSyncMissingImage.units(snapshot)
            val units = (vault.affected(entry) - unavailable).sorted().associateWith { unit ->
                Collections.unmodifiableList(requireNotNull(retained[unit]) { "SYNC_RECOVERY_UNIT" }.toList())
            }
            LanSyncGroupRecoveryView(entry, Collections.unmodifiableMap(units))
        }
    }

    /** Caller first admits the exact selected frozen record/reference; this reads immutable cache only. */
    internal suspend fun readFrozenImage(record: LanSyncRecord, reference: String): ByteArray = withContext(Dispatchers.IO) {
        workspace.readImagePreview(workspace.previewImage(record, reference))
    }

    internal suspend fun prepareFrozenImage(record: LanSyncRecord, reference: String): LanSyncImagePreview = withContext(Dispatchers.IO) {
        workspace.previewImage(record, reference)
    }
    internal fun readFrozenImagePreview(preview: LanSyncImagePreview): ByteArray = workspace.readImagePreview(preview)

    suspend fun prepareRecovery(transaction: String, unit: String): LanSyncRestorePreview = serialized {
        exclusive { workspace.prepareRecovery(transaction, unit, identity.fingerprint, projection, isolateLocalImages = true) }
    }
    /** Explicit local restore; per-unit sync receipts are preserved and its fresh revision stays pending. */
    suspend fun applyRecovery(preview: LanSyncRestorePreview): LanSyncRestoreRevision = serialized {
        exclusive {
            author.state(); native.state()
            val revision = workspace.applyRecovery(preview, identity.fingerprint, projection, isolateLocalImages = true, trackReload = true)
            reloadLocalRecoveries()
            revision.copy(reloadPending = false)
        }
    }

    private suspend fun refreshRestorations() = exclusive {
        val acknowledged = writer.acknowledgedRestorations()
        val pending = linkedMapOf<String, MutableSet<String>>()
        workspace.completedRecoveries(identity.fingerprint).forEach { revision ->
            revision.restoredUnits.forEach { unit ->
                if (revision.revision !in acknowledged[unit].orEmpty()) pending.getOrPut(unit) { linkedSetOf() }.add(revision.revision)
            }
        }
        pendingRestorations = pending.mapValues { Collections.unmodifiableSet(it.value.toSet()) }
    }
    private suspend fun refreshResolutions() = exclusive {
        pendingResolutions = resolutions.reservations(writer.acknowledgedResolutions())
        heldResolutions = resolutions.holds()
    }

    suspend fun apply(plan: LanSyncGroupNativePlan): LanSyncGroupNativeApplyReceipt? = serialized {
        applyIncomingOwned(LanSyncGroupIncomingReview.prepare(plan).defaultPlan)
    }
    suspend fun applyIncoming(approval: LanSyncGroupIncomingApproval): LanSyncGroupNativeApplyReceipt? = serialized {
        applyIncomingOwned(approval.review.confirmedPlan(approval))
    }
    private suspend fun applyIncomingOwned(plan: LanSyncGroupNativePlan): LanSyncGroupNativeApplyReceipt? {
        current = inbox().reconcile(localVersions.values)
        refreshResolutions()
        return exclusive {
            val restricted = LanSyncGroupNativePlanner.restrict(plan, pendingResolutions + heldResolutions)
            val (receipt, pendingReload) = native.applyForReload(restricted)
            reloadNative(pendingReload)
            receipt
        }
    }

    suspend fun upload(operation: SyncStoreId, store: CiphertextStore, target: SyncDeliveryTarget, recoverNative: Boolean = true): LanSyncGroupPublishReceipt = serialized {
        // A cold caller need not capture today's source or recover native state to replay a queue.
        current = reconcileRetainedMetadata()
        // Re-key/preparation and source control are gated; slow immutable delivery is outside the gate.
        val commit = exclusive {
            if (!recoverNative) requireSourceOnlyReady()
            val withheld = currentView().recoveryHeldVersions
            if (withheld.isNotEmpty()) require(author.entry(operation)?.version?.reference()?.let { it !in withheld } != false) {
                LanSyncGroupNativePlanner.RECOVERY_HELD
            }
            writer.resume(operation)
        }
        remember(commit)
        require(commit.entry.version.reference() !in currentView().recoveryHeldVersions) { LanSyncGroupNativePlanner.RECOVERY_HELD }
        exclusive { native.recordSource(commit, recoverNative) }
        writer.uploadPrepared(commit, store, target)
    }

    suspend fun receipt(operation: SyncStoreId, target: SyncDeliveryTarget): LanSyncGroupPublishReceipt? = serialized { writer.receipt(operation, target) }

    /** Every retained signed/approved operation in author order; unsigned proposals stay separate. */
    suspend fun sourceOperations(eligibleForDelivery: Boolean = false): List<SyncStoreId> = serialized {
        exclusive {
            val state = author.state(); val operations = writer.operations()
            val signed = operations.mapNotNull { author.entry(it) }
            require(signed.size.toLong() == state.committedSequence &&
                (state.pendingApproved == null || state.pendingApproved.request.operation in operations)) { "SYNC_GROUP_NODE_SOURCE_HISTORY_MISSING" }
            val entries = (signed + listOfNotNull(state.pendingApproved)).sortedBy { it.version.authorSequence }
            entries.forEachIndexed { index, entry -> require(entry.version.authorSequence == index.toLong() + 1) { "SYNC_GROUP_NODE_SOURCE_HISTORY_MISSING" } }
            val withheld = if (eligibleForDelivery) current?.recoveryHeldVersions.orEmpty() else emptyMap()
            entries.filterNot { it.version.reference() in withheld }.map { it.request.operation }
        }
    }

    private fun remember(commit: LanSyncGroupSourceCommit) {
        localVersions[commit.entry.version.id] = commit.metadata
        current = inbox().reconcile(localVersions.values)
    }
    private fun requireSourceOnlyReady() {
        workspace.requireSettledForSourceOnly()
        require(native.state().pendingTransaction == null && !resolutions.needsReload() &&
            resolutions.state().none { it.stage == LanSyncGroupResolutionLocalStage.PREPARED }) { "SYNC_GROUP_SOURCE_RECOVERY_REQUIRED" }
    }
    private suspend fun reloadNative(work: LanSyncGroupNativeApplyOwner.ReloadWork?) {
        if (work == null) return
        reloadPendingRepositories(work.sessions)
        native.acknowledgeReload(work)
    }
    private suspend fun reloadLocalRecoveries() {
        val pending = workspace.completedRecoveries(identity.fingerprint).filter { it.reloadPending }
        if (pending.isEmpty()) return
        val affected = pending.flatMap { it.restoredUnits }.filter { it.startsWith("conversation/") }
            .mapTo(linkedSetOf()) { it.removePrefix("conversation/") }
        reloadPendingRepositories(affected)
        pending.forEach(workspace::acknowledgeRecoveryReload)
    }
    private suspend fun reloadPendingRepositories(sessions: Set<String>) {
        try { reload(sessions) }
        catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { throw IllegalStateException("SYNC_GROUP_NATIVE_RELOAD_PENDING", failure) }
    }
    private suspend fun reload(previousSessions: Set<String> = emptySet()) {
        val sessions = workspace.captureGroup(identity.fingerprint, projection).use { capture ->
            capture.snapshot.records.filter { it.type == "chat_sessions" }.mapTo(linkedSetOf()) { it.id }
        }
        platform.reload(previousSessions + sessions)
    }
    private suspend fun <T> exclusive(work: suspend () -> T): T {
        var answer: () -> T = { error("SYNC_GROUP_NODE_NATIVE_GATE_NOT_ENTERED") }
        platform.exclusive { withContext(NonCancellable) { val value = work(); answer = { value } } }
        return answer()
    }
    private suspend fun <T> serialized(work: suspend () -> T): T = withContext(Dispatchers.IO) { operation.withLock { work() } }
}
