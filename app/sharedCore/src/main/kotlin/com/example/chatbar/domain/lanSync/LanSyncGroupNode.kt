package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Base64
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class LanSyncGroupNodeStage { READY, WAITING_FOR_KEY, DEVICE_REVOKED, TRUST_BLOCKED }
class LanSyncGroupNodeView internal constructor(val stage: LanSyncGroupNodeStage, val reconciliation: SyncInboxReconciliation,
    val nativeState: LanSyncGroupNativeState, val plan: LanSyncGroupNativePlan?, val unsignedPreparations: Set<SyncStoreId>,
    private val dependencies: Map<LanSyncGroupMembershipRequest, LanSyncGroupDependencyResult>, private val evidence: LanSyncGroupRecordEvidence?) {
    fun review(selected: Set<String>): LanSyncGroupSourceReview = LanSyncGroupSourceReview.prepare(
        requireNotNull(plan) { "SYNC_GROUP_NODE_NOT_READY" }, reconciliation, selected, dependencies, requireNotNull(evidence))
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
    private var current: SyncInboxReconciliation? = null
    private var pendingRestorations: Map<String, Set<String>> = emptyMap()
    private val localVersions = linkedMapOf<SyncStoreId, SyncAuthenticatedGroupObject>()
    private val actorCertificate = Base64.getEncoder().encodeToString(identity.certificate.encoded)
    private fun directory(name: String): Path = root.resolve(name).also {
        PrivateSyncFile.checked(root, true)
        if (!Files.exists(it, NOFOLLOW_LINKS)) Files.createDirectory(it)
        PrivateSyncFile.checked(it, true)
    }
    private val inboxRoot = directory("inbox"); private val work = directory("preparations"); private val queueRoot = directory("queues")
    private val authorRoot = directory("author"); private val nativeRoot = directory("native")
    private val author = SyncAuthorJournal(authorRoot, trust, actorCertificate, protect, unprotect)
    private val native = LanSyncGroupNativeApplyOwner(nativeRoot, workspace, trust, actorCertificate, projection,
        { custody.ledger() }, ::currentView, protect, unprotect, observe, author)
    private val writer = LanSyncGroupSourceWriter(directory("source"), workspace, identity, author, { custody.ledger() }, custody::key,
        { epoch -> val path = queueRoot.resolve(epoch.value)
            if (!Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
            SyncEncryptedOutbox(path, custody.key(epoch)) }, ::currentView, native::state, projection, observe,
        restorationRevisions = { pendingRestorations })
    init { require(identity.fingerprint == recipient.device && trust.group == custody.trust().group) { "SYNC_GROUP_NODE_IDENTITY" } }
    private fun currentView() = requireNotNull(current) { "SYNC_GROUP_NODE_RECONCILIATION_REQUIRED" }
    private fun inbox(store: CiphertextStore = dependencyStore) = SyncGroupInbox(inboxRoot, store, custody, recipient)

    /** Explicit new enrollment/retry only. Existing nonempty author/native histories cannot be reset. */
    suspend fun initializeNewEnrollment() = serialized {
        exclusive {
            if (Files.exists(authorRoot.resolve("origin.protected"), NOFOLLOW_LINKS)) {
                val state = author.state()
                require(state.committedSequence == 0L && state.pendingApproved == null) { "SYNC_GROUP_NODE_ALREADY_USED" }
            } else author.initializeNewEnrollment()
            if (Files.exists(nativeRoot.resolve("origin.protected"), NOFOLLOW_LINKS)) {
                val state = native.resumeNewEnrollment()
                require(state.completedSequence == 0L && state.pendingTransaction == null && state.units.isEmpty()) { "SYNC_GROUP_NODE_ALREADY_USED" }
            } else native.initializeNewEnrollment()
        }
    }

    /** All destinations share this node's immutable mirror. Callers must periodically rescan from null. */
    suspend fun discover(store: CiphertextStore, cursor: CommitCursor? = null, pageSize: Int = 100): SyncInboxDiscovery = serialized {
        inbox(store).discover(cursor, pageSize)
    }

    suspend fun inspect(): LanSyncGroupNodeView = serialized { inspectOwned() }

    private suspend fun inspectOwned(): LanSyncGroupNodeView {
        current = inbox().reconcile(localVersions.values)
        var state = exclusive {
            val before = native.state(); val recovered = native.recover(); author.state()
            if (before.pendingTransaction != null) reload()
            recovered
        }
        fun held(stage: LanSyncGroupNodeStage) = LanSyncGroupNodeView(stage, currentView(), state, null, emptySet(), emptyMap(), null)
        if (currentView().trustBlocked) return held(LanSyncGroupNodeStage.TRUST_BLOCKED)
        val latest = custody.ledger().current()
        if (latest.members[identity.fingerprint]?.active != true) return held(LanSyncGroupNodeStage.DEVICE_REVOKED)
        try { custody.key(latest.epoch) } catch (failure: IllegalArgumentException) {
            if (failure.message == "SYNC_GROUP_CUSTODY_KEY_MISSING") return held(LanSyncGroupNodeStage.WAITING_FOR_KEY)
            throw failure
        }
        val unsigned = recoverSources()
        refreshRestorations()
        state = exclusive { native.state() }
        val view = currentView(); val history = requireNotNull(view.history) { "SYNC_GROUP_NODE_RECONCILIATION_REQUIRED" }
        val evidence = LanSyncGroupRecordEvidence(history, requireNotNull(view.ledgerCheckpoint))
        val service = LanSyncGroupDependencies(trust.group, dependencyStore, custody, work, workspace.resourceStore, validateResource)
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
            val known = it.snapshot.units().keys + state.units.keys + history.admittedVersions().flatMap { version -> version.changes.keys }
            LanSyncGroupNativePlanner.prepare(it, view, state.units, dependencies, evidence,
                freshLocalRevisions() + pendingRestorations.keys.filter { unit -> unit in known })
        } }
        observe("NATIVE_PLAN_READY")
        return LanSyncGroupNodeView(LanSyncGroupNodeStage.READY, view, state, plan, Collections.unmodifiableSet(unsigned),
            Collections.unmodifiableMap(dependencies), evidence)
    }

    /** Dense author order, never random preparation-filename order or a reset counter. */
    private suspend fun recoverSources(): Set<SyncStoreId> {
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
            exclusive { native.recordSource(commit) }
        }
        observe("LOCAL_SOURCE_HISTORY_RECOVERED")
        return entries.second
    }

    /** Frozen review authorization remains exact; this never confirms a held reduction implicitly. */
    suspend fun commit(review: LanSyncGroupSourceReview, confirmations: Map<String, String> = emptyMap(),
        operation: SyncStoreId = SyncStoreId.random()): LanSyncGroupSourceCommit = serialized {
        refreshRestorations()
        val commit = exclusive { writer.commit(review.authorize(confirmations), operation) }
        remember(commit); exclusive { native.recordSource(commit) }; commit
    }

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

    suspend fun apply(plan: LanSyncGroupNativePlan): LanSyncGroupNativeApplyReceipt? = serialized {
        current = inbox().reconcile(localVersions.values)
        exclusive {
            native.apply(plan).also { if (it?.status == LanSyncGroupNativeApplyStatus.APPLIED)
                reload(plan.before.records.filter { record -> record.type == "chat_sessions" }.mapTo(linkedSetOf()) { record -> record.id }) }
        }
    }

    suspend fun upload(operation: SyncStoreId, store: CiphertextStore, target: SyncDeliveryTarget): LanSyncGroupPublishReceipt = serialized {
        // Re-key/preparation and source control are gated; slow immutable delivery is outside the gate.
        val commit = exclusive { writer.resume(operation) }
        remember(commit); exclusive { native.recordSource(commit) }
        writer.uploadPrepared(commit, store, target)
    }

    suspend fun receipt(operation: SyncStoreId, target: SyncDeliveryTarget): LanSyncGroupPublishReceipt? = serialized { writer.receipt(operation, target) }

    private fun remember(commit: LanSyncGroupSourceCommit) {
        localVersions[commit.entry.version.id] = commit.metadata
        current = inbox().reconcile(localVersions.values)
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
