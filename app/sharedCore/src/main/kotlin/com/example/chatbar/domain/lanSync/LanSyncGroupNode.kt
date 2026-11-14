package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Base64
import java.util.Collections
import java.util.UUID
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
    fun review(selected: Set<String>): LanSyncGroupSourceReview {
        require(selected.intersect(reservedUnits).isEmpty()) { "SYNC_GROUP_RESOLUTION_RESERVED" }
        return sourceReview(selected)
    }
    internal fun sourceReview(selected: Set<String>) = LanSyncGroupSourceReview.prepare(
        requireNotNull(plan) { "SYNC_GROUP_NODE_NOT_READY" }, reconciliation, selected, dependencies, requireNotNull(evidence))
    internal fun resolution(capture: LanSyncCapture, choices: Map<String, LanSyncGroupResolutionChoice>, operation: String) =
        LanSyncGroupResolution.prepare(requireNotNull(plan) { "SYNC_GROUP_NODE_NOT_READY" }, capture, reconciliation,
            dependencies, requireNotNull(evidence), choices, operation)
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
    private val projection: LanSyncProjection = LanSyncProjection(), private val observe: (String) -> Unit = {}) {
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
                workspace.capture(identity.fingerprint, projection).use {
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
        val service = LanSyncGroupDependencies(trust.group, dependencyStore, custody, prefetchWork, workspace.resourceStore, validateResource)
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
            val before = native.state()
            if (recoverNative) {
                val recovered = native.recover(); author.state()
                if (before.pendingTransaction != null) reload()
                val sessions = resolutions.recover()
                if (resolutions.needsReload()) { reload(sessions); resolutions.acknowledgeReload() }
                recovered
            } else {
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
        val service = LanSyncGroupDependencies(trust.group, if (allowNetwork) dependencyStore else localDependencies(), custody, work, workspace.resourceStore, validateResource)
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
        state.units.forEach { (unit, applied) -> applied.frontiers.forEach { required += LanSyncGroupMembershipRequest(it, unit) } }
        val dependencies = required.associateWith { request ->
            val actual = requireNotNull(metadata[request.version]) { "SYNC_GROUP_APPLIED_EVIDENCE_MISSING" }
            service.inspect(view, actual, request.unit)
        }
        val plan = exclusive { workspace.capture(identity.fingerprint, projection).use {
            val known = it.snapshot.units().keys + state.units.keys + history.admittedVersions().flatMap { version -> version.changes.keys } + pendingResolutions
            LanSyncGroupNativePlanner.prepare(it, view, state.units, dependencies, evidence,
                freshLocalRevisions() + (pendingRestorations.keys + pendingResolutions).filter { unit -> unit in known },
                unpublishedLocalUnits = pendingResolutions)
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
            val commit = exclusive { writer.resume(entry.request.operation) }
            remember(commit)
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
        exclusive { workspace.capture(identity.fingerprint, projection).use { view.resolution(it, choices, operation) } }
    }
    /** Approved native adoption, fresh source validation, then one bound causal author operation. */
    suspend fun resolve(candidate: LanSyncGroupResolvedCandidate): LanSyncGroupSourceCommit? = serialized {
        inspectOwned()
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
            val units = vault.affected(entry).sorted().associateWith { unit ->
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
        exclusive { workspace.prepareRecovery(transaction, unit, identity.fingerprint, projection) }
    }
    /** Explicit local restore; per-unit sync receipts are preserved and its fresh revision stays pending. */
    suspend fun applyRecovery(preview: LanSyncRestorePreview): LanSyncRestoreRevision = serialized {
        val restored = exclusive {
            author.state(); native.state()
            workspace.applyRecovery(preview, identity.fingerprint, projection).also {
                reload(preview.currentRecords.filter { record -> record.type == "chat_sessions" }.mapTo(linkedSetOf()) { record -> record.id })
            }
        }
        restored
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
            native.apply(LanSyncGroupNativePlanner.restrict(plan, pendingResolutions + heldResolutions)).also { if (it?.status == LanSyncGroupNativeApplyStatus.APPLIED)
                reload(plan.before.records.filter { record -> record.type == "chat_sessions" }.mapTo(linkedSetOf()) { record -> record.id }) }
        }
    }

    suspend fun upload(operation: SyncStoreId, store: CiphertextStore, target: SyncDeliveryTarget, recoverNative: Boolean = true): LanSyncGroupPublishReceipt = serialized {
        // Re-key/preparation and source control are gated; slow immutable delivery is outside the gate.
        val commit = exclusive {
            if (!recoverNative) requireSourceOnlyReady()
            writer.resume(operation)
        }
        remember(commit); exclusive { native.recordSource(commit, recoverNative) }
        writer.uploadPrepared(commit, store, target)
    }

    suspend fun receipt(operation: SyncStoreId, target: SyncDeliveryTarget): LanSyncGroupPublishReceipt? = serialized { writer.receipt(operation, target) }

    /** Every retained signed/approved operation in author order; unsigned proposals stay separate. */
    suspend fun sourceOperations(): List<SyncStoreId> = serialized {
        exclusive {
            val state = author.state(); val operations = writer.operations()
            val signed = operations.mapNotNull { author.entry(it) }
            require(signed.size.toLong() == state.committedSequence &&
                (state.pendingApproved == null || state.pendingApproved.request.operation in operations)) { "SYNC_GROUP_NODE_SOURCE_HISTORY_MISSING" }
            val entries = (signed + listOfNotNull(state.pendingApproved)).sortedBy { it.version.authorSequence }
            entries.forEachIndexed { index, entry -> require(entry.version.authorSequence == index.toLong() + 1) { "SYNC_GROUP_NODE_SOURCE_HISTORY_MISSING" } }
            entries.map { it.request.operation }
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
    private suspend fun reload(previousSessions: Set<String> = emptySet()) {
        val sessions = workspace.capture(identity.fingerprint, projection).use { capture ->
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
