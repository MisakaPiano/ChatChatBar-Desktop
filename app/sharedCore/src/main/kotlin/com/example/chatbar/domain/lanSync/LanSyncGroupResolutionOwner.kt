package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

enum class LanSyncGroupResolutionLocalStage { PREPARED, LOCAL_READY, NEEDS_REVIEW, SUPERSEDED }
data class LanSyncGroupResolutionLocalState(val operation: String, val binding: String,
    val stage: LanSyncGroupResolutionLocalStage, val resolvedUnits: Set<String>, val heldUnits: Set<String>, val applied: Boolean, val code: String? = null)
internal class LanSyncGroupResolvedLocalApproval internal constructor(val operation: SyncStoreId, val revision: String,
    val binding: String, val checkpoint: SyncVersionRef, val heads: Set<SyncVersionRef>, val authorHead: SyncVersionRef?,
    val postFiles: Map<String, String>, val targetHashes: Map<String, String>, val appliedBefore: Map<String, LanSyncGroupAppliedUnit>,
    val previous: Map<String, Set<SyncVersionRef>>, val resolvedUnits: Set<String>)

/** Native gate required. Owns approved local adoption/reservation; never signs or advances group receipts. */
class LanSyncGroupResolutionOwner(private val root: Path, private val workspace: LanSyncWorkspace,
    private val trust: SyncGroupTrust, private val actorCertificate: String, private val projection: LanSyncProjection,
    private val authority: () -> SyncDeviceLedger, private val view: () -> SyncInboxReconciliation,
    private val nativeState: () -> LanSyncGroupNativeState, private val author: SyncAuthorJournal,
    private val protect: (ByteArray) -> ByteArray, private val unprotect: (ByteArray) -> ByteArray,
    private val observe: (String) -> Unit = {}) {
    @Serializable private data class Row(val operation: String, val binding: String, val data: SyncPlaintextIdentity,
        val resolved: Set<String>, val held: Set<String>, val stage: LanSyncGroupResolutionLocalStage,
        val applied: Boolean = false, val post: SyncPlaintextIdentity? = null, val reload: Boolean = false, val code: String? = null)
    @Serializable private data class State(val format: Int, val trust: SyncGroupTrust, val certificate: String,
        val projection: String, val incarnation: SyncStoreId, val rows: List<Row>)
    @Serializable private data class Spec(val group: SyncStoreId, val actor: String, val incarnation: SyncStoreId,
        val operation: String, val binding: String, val authorHead: SyncVersionRef?)
    @Serializable private data class Data(val operation: String, val binding: String, val checkpoint: SyncVersionRef,
        val heads: Set<SyncVersionRef>, val authorHead: SyncVersionRef?, val before: Map<String, String>,
        val beforeFiles: Map<String, String>, val applied: Map<String, LanSyncGroupAppliedUnit>,
        val previous: Map<String, Set<SyncVersionRef>>, val target: SyncPlaintextIdentity, val parents: Map<String, String>,
        val preimage: String?, val preimageHash: String?, val beforeSessions: Set<String>)
    @Serializable private data class Post(val files: Map<String, String>)
    private val actor = SyncDeviceSignatures.decode(actorCertificate)
    private val lock = locks.computeIfAbsent(root) { ReentrantLock() }
    init { PrivateSyncFile.checked(root, true); projection.validate(); require(actor.encoded == actorCertificate) { "SYNC_GROUP_RESOLUTION_DEVICE" } }

    fun initializeNewEnrollment() = guarded {
        require(Files.newDirectoryStream(root).use { files -> files.all { it.fileName.toString() == "resolution.lock" } }) { "SYNC_GROUP_RESOLUTION_EXISTING_EVIDENCE" }
        requireAuthority()
        val original = author.state(); val native = nativeState()
        require(original.committedSequence == 0L && original.pendingApproved == null && native.completedSequence == 0L &&
            native.pendingTransaction == null && native.units.isEmpty()) { "SYNC_GROUP_RESOLUTION_EXISTING_EVIDENCE" }
        writeState(State(1, trust, actorCertificate, projection.digest(), SyncStoreId.random(), emptyList()))
    }
    fun state(): List<LanSyncGroupResolutionLocalState> = guarded { load().rows.map(::publicState) }
    private fun publicState(row: Row) = LanSyncGroupResolutionLocalState(row.operation, row.binding, row.stage,
        Collections.unmodifiableSet(row.resolved.toSet()), Collections.unmodifiableSet(row.held.toSet()), row.applied, row.code)
    private fun live() {
        val ledger = requireAuthority(); val current = view()
        require(!current.trustBlocked && current.ledgerCheckpoint == ledger.current().reference()) {
            "SYNC_GROUP_RESOLUTION_AUTHORITY"
        }
    }
    private fun requireAuthority() = authority().also { ledger ->
        require(ledger.trustAnchor.group == trust.group && ledger.trustAnchor.genesis == trust.genesis &&
            ledger.trustAnchor.ownerCertificate == trust.ownerCertificate && ledger.current().members[actor.fingerprint]?.active == true) {
            "SYNC_GROUP_RESOLUTION_AUTHORITY"
        }
    }
    private fun fresh(data: Data): Boolean {
        val current = view(); val state = author.state()
        return !current.trustBlocked && current.ledgerCheckpoint == data.checkpoint && current.history?.heads() == data.heads &&
            authority().current().reference() == data.checkpoint && nativeState().units == data.applied &&
            state.pendingApproved == null && state.last?.version?.reference() == data.authorHead
    }
    fun adopt(candidate: LanSyncGroupResolvedCandidate, acknowledged: Map<String, Set<String>> = emptyMap()): LanSyncGroupResolutionLocalState = guarded {
        val preview = candidate.preview; var state = load(); live()
        state.rows.firstOrNull { it.operation == preview.operation }?.let { row ->
            require(row.binding == preview.binding) { "SYNC_GROUP_RESOLUTION_OPERATION_CHANGED" }
            return@guarded publicState(complete(state, row))
        }
        workspace.capture(actor.fingerprint, projection).use { capture ->
            preview.requireFresh(capture, view(), nativeState().units)
            val authorState = author.state(); require(authorState.pendingApproved == null) { "SYNC_AUTHOR_PENDING_APPROVED_COMMIT" }
            // A new review can supersede a stale decision only as its whole reserved adoption.
            val superseded = state.rows.filter { it.stage != LanSyncGroupResolutionLocalStage.SUPERSEDED &&
                it.resolved.any { unit -> it.operation !in acknowledged[unit].orEmpty() } && it.resolved.intersect(preview.resolvedUnits).isNotEmpty() }
            superseded.forEach {
                require(preview.resolvedUnits.containsAll(it.resolved)) { "SYNC_GROUP_RESOLUTION_RESERVED_SELECTION" }
            }
            val spec = Spec(trust.group, actor.fingerprint, state.incarnation, preview.operation, preview.binding, authorState.last?.version?.reference())
            val specPath = root.resolve("spec-${preview.operation}.protected")
            val old = readProtected(specPath, Spec.serializer())
            if (old == null) writeProtected(specPath, Spec.serializer(), spec) else require(old == spec) { "SYNC_GROUP_RESOLUTION_OPERATION_CHANGED" }
            observe("RESOLUTION_BINDING_RETAINED")
            preview.resourceSources.forEach { (hash, source) ->
                val retained = workspace.resourceStore.completed(hash, source.identity.length)
                if (retained == null) {
                    val path = source.paths.firstOrNull { Files.isRegularFile(it, NOFOLLOW_LINKS) &&
                        LanSyncResourceStore.hashFile(it, source.identity.length) == hash } ?: error("SYNC_GROUP_RESOLUTION_RESOURCE_MISSING")
                    workspace.resourceStore.retain(path, hash, source.identity.length)
                }
            }
            val target = root.resolve("target-${preview.operation}.json")
            if (!Files.exists(target, NOFOLLOW_LINKS)) LanSyncJsonFiles.write(target, LanSyncSnapshot.serializer(), preview.target)
            else require(readJson(target, LanSyncSnapshot.serializer(), identity(target)) == preview.target) { "SYNC_GROUP_RESOLUTION_OPERATION_CHANGED" }
            val checkpointId = UUID.nameUUIDFromBytes("ccb-resolution-original/${trust.group.value}/${preview.operation}".toByteArray()).toString()
            val original = if (preview.resolvedUnits.isEmpty()) null else
                workspace.recovery.checkpointSource(workspace.dataRoot, capture, trust.group, checkpointId, projection)
            // Explicitly dropped never-published copies clear reservations without inventing tombstones.
            val targetHashes = preview.target.hashes()
            val authored = preview.resolvedUnits.filterTo(linkedSetOf()) {
                it in targetHashes || preview.previous.getValue(it).isNotEmpty()
            }
            val data = Data(preview.operation, preview.binding, preview.checkpoint, preview.heads, spec.authorHead,
                preview.expected, preview.sourceFiles, preview.appliedBefore, preview.previous.filterKeys(authored::contains), identity(target), preview.forkParents,
                original?.transaction, original?.snapshotHash, capture.snapshot.records.filter { it.type == "chat_sessions" }.map { it.id }.toSet())
            val dataPath = root.resolve("decision-${preview.operation}.json")
            if (!Files.exists(dataPath, NOFOLLOW_LINKS)) LanSyncJsonFiles.write(dataPath, Data.serializer(), data)
            else require(readJson(dataPath, Data.serializer(), identity(dataPath)) == data) { "SYNC_GROUP_RESOLUTION_OPERATION_CHANGED" }
            val retainedHolds = superseded.flatMap { it.held }.toSet() - preview.resolvedUnits
            val explicitHolds = preview.choices.filterValues { it == LanSyncGroupResolutionChoice.Later }.keys
            val row = Row(preview.operation, preview.binding, identity(dataPath), authored, explicitHolds + retainedHolds,
                if (authored.isEmpty()) LanSyncGroupResolutionLocalStage.LOCAL_READY else LanSyncGroupResolutionLocalStage.PREPARED)
            state = state.copy(rows = state.rows.map { previous ->
                if (previous in superseded) previous.copy(stage = LanSyncGroupResolutionLocalStage.SUPERSEDED) else previous
            } + row)
            writeState(state); observe("RESOLUTION_INTENT_RETAINED")
            publicState(complete(state, row))
        }
    }
    private fun data(row: Row): Data = readJson(root.resolve("decision-${row.operation}.json"), Data.serializer(), row.data).also {
        require(it.operation == row.operation && it.binding == row.binding && it.previous.keys == row.resolved) { "SYNC_GROUP_RESOLUTION_DAMAGED" }
        if (it.preimage != null) {
            val entry = requireNotNull(workspace.recovery.read(it.preimage)) { "SYNC_GROUP_RESOLUTION_PREIMAGE_MISSING" }
            require(entry.snapshotHash == it.preimageHash && entry.sourceCheckpointGroup == trust.group.value) { "SYNC_GROUP_RESOLUTION_PREIMAGE_CHANGED" }
            workspace.recovery.verify(entry)
        } else require(it.before.isEmpty() || row.resolved.isEmpty()) { "SYNC_GROUP_RESOLUTION_PREIMAGE_MISSING" }
    }
    private fun complete(state: State, row: Row): Row {
        if (row.stage != LanSyncGroupResolutionLocalStage.PREPARED) return row
        val data = data(row); val target = readJson(root.resolve("target-${row.operation}.json"), LanSyncSnapshot.serializer(), data.target)
        target.validate(); LanSyncScope.validateGraph(target)
        workspace.recover()
        if (!workspace.applied(row.operation)) {
            if (!fresh(data)) return update(state, row.copy(stage = LanSyncGroupResolutionLocalStage.NEEDS_REVIEW))
            val unchanged = workspace.capture(actor.fingerprint, projection).use { it.sourceFiles == data.beforeFiles && it.snapshot.hashes() == data.before }
            if (!unchanged) return update(state, row.copy(stage = LanSyncGroupResolutionLocalStage.NEEDS_REVIEW))
            val blobs = target.resources.mapValues { (hash, length) -> requireNotNull(workspace.resourceStore.completed(hash, length)) { "SYNC_GROUP_RESOLUTION_RESOURCE_MISSING" } }
            workspace.apply(LanSyncPlan(row.operation, target, data.parents), data.before, actor.fingerprint, blobs, projection,
                expectedSourceFiles = data.beforeFiles)
        }
        observe("RESOLUTION_LOCAL_DATA_APPLIED")
        val expectedPost = requireNotNull(workspace.appliedSourceFiles(row.operation, data.beforeFiles)) { "SYNC_GROUP_RESOLUTION_WAL_MISSING" }
        val post = root.resolve("post-${row.operation}.json")
        if (!Files.exists(post, NOFOLLOW_LINKS)) LanSyncJsonFiles.write(post, Post.serializer(), Post(expectedPost))
        else require(readJson(post, Post.serializer(), identity(post)).files == expectedPost) { "SYNC_GROUP_RESOLUTION_POST_CHANGED" }
        val matches = workspace.capture(actor.fingerprint, projection).use { it.sourceFiles == expectedPost && it.snapshot.hashes() == target.hashes() && it.snapshot.coveredTypes == target.coveredTypes }
        return update(state, row.copy(stage = if (matches && fresh(data)) LanSyncGroupResolutionLocalStage.LOCAL_READY else LanSyncGroupResolutionLocalStage.NEEDS_REVIEW,
            applied = true, post = identity(post), reload = true)).also { observe("RESOLUTION_POST_SOURCE_RETAINED") }
    }
    /** Caller reloads repositories for every returned session set, then acknowledges that obligation. */
    fun recover(): Set<String> = guarded {
        var state = load()
        state.rows.filter { it.stage == LanSyncGroupResolutionLocalStage.PREPARED }.map { it.operation }.forEach { operation ->
            val row = state.rows.single { it.operation == operation }
            try { complete(state, row) }
            catch (failure: Exception) {
                // Unpublished preparation cannot block unrelated units; an authorized WAL must recover.
                if (workspace.authorized(operation) || workspace.applied(operation)) throw failure
                val code = failure.message?.takeIf { it.matches(Regex("SYNC_[A-Z0-9_]{1,100}")) } ?: "SYNC_GROUP_RESOLUTION_PREPARATION_FAILED"
                update(state, row.copy(stage = LanSyncGroupResolutionLocalStage.NEEDS_REVIEW, code = code))
            }
            state = load()
        }
        state.rows.filter { it.reload }.flatMap { data(it).beforeSessions }.toSet()
    }
    fun needsReload(): Boolean = guarded { load().rows.any { it.reload } }
    fun acknowledgeReload() = guarded { val state = load(); writeState(state.copy(rows = state.rows.map { it.copy(reload = false) })) }

    fun reservations(acknowledged: Map<String, Set<String>> = emptyMap()): Set<String> = guarded {
        load().rows.filter { it.stage != LanSyncGroupResolutionLocalStage.SUPERSEDED }.flatMap { row ->
            row.resolved.filter { row.operation !in acknowledged[it].orEmpty() }
        }.toSet()
    }
    fun holds(): Set<String> = guarded {
        val result = linkedSetOf<String>()
        load().rows.filter { it.stage != LanSyncGroupResolutionLocalStage.SUPERSEDED }.forEach { row ->
            result.removeAll(row.resolved); result.addAll(row.held)
        }
        result
    }
    internal fun decisionBinding(operation: String): String = guarded {
        val row = load().rows.singleOrNull { it.operation == operation } ?: error("SYNC_GROUP_RESOLUTION_MISSING")
        data(row); row.data.sha256
    }
    internal fun approval(operation: String): LanSyncGroupResolvedLocalApproval = guarded {
        val row = load().rows.singleOrNull { it.operation == operation } ?: error("SYNC_GROUP_RESOLUTION_MISSING")
        require(row.stage == LanSyncGroupResolutionLocalStage.LOCAL_READY && row.applied && !row.reload && row.resolved.isNotEmpty()) { "SYNC_GROUP_RESOLUTION_REVIEW_REQUIRED" }
        val data = data(row); require(fresh(data)) { "SYNC_GROUP_RESOLUTION_STALE" }
        val post = readJson(root.resolve("post-$operation.json"), Post.serializer(), requireNotNull(row.post))
        val target = readJson(root.resolve("target-$operation.json"), LanSyncSnapshot.serializer(), data.target)
        workspace.capture(actor.fingerprint, projection).use {
            require(it.sourceFiles == post.files && it.snapshot.hashes() == target.hashes() && it.snapshot.coveredTypes == target.coveredTypes) { "SYNC_GROUP_RESOLUTION_POST_CHANGED" }
        }
        LanSyncGroupResolvedLocalApproval(SyncStoreId.parse(operation.replace("-", "")), operation, row.data.sha256,
            data.checkpoint, data.heads, data.authorHead, post.files, target.hashes(), data.applied, data.previous, row.resolved)
    }
    private fun update(state: State, row: Row): Row { writeState(state.copy(rows = state.rows.map { if (it.operation == row.operation) row else it })); return row }
    private fun load(): State = requireNotNull(readProtected(root.resolve("state.protected"), State.serializer())) { "SYNC_GROUP_RESOLUTION_ORIGIN_MISSING" }.also {
        require(it.format == 1 && it.trust.group == trust.group && it.trust.genesis == trust.genesis && it.trust.ownerCertificate == trust.ownerCertificate &&
            it.certificate == actorCertificate && it.projection == projection.digest() && it.rows.map { row -> row.operation }.distinct().size == it.rows.size &&
            it.rows.all { row -> UUID.fromString(row.operation).toString() == row.operation && row.binding.matches(Regex("[a-f0-9]{64}")) }) { "SYNC_GROUP_RESOLUTION_ORIGIN_CHANGED" }
    }
    private fun writeState(state: State) = writeProtected(root.resolve("state.protected"), State.serializer(), state)
    private fun identity(path: Path): SyncPlaintextIdentity { PrivateSyncFile.checked(path); val length = Files.size(path); return SyncPlaintextIdentity(length, LanSyncResourceStore.hashFile(path, length)) }
    private fun <T> readJson(path: Path, serializer: KSerializer<T>, identity: SyncPlaintextIdentity): T {
        PrivateSyncFile.checked(path); require(Files.size(path) == identity.length) { "SYNC_GROUP_RESOLUTION_DAMAGED" }
        LanSyncMetadataBudget().validate(path)
        return try { requireNotNull(LanSyncJsonFiles.read(path, serializer, identity.sha256)) { "SYNC_GROUP_RESOLUTION_DAMAGED" } }
        catch (_: Exception) { error("SYNC_GROUP_RESOLUTION_DAMAGED") }
    }
    private fun <T> readProtected(path: Path, serializer: KSerializer<T>): T? {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        val plain = unprotect(lanSyncRead(path, MAX_PROTECTED))
        try { require(plain.size in 1..MAX_PLAIN) { "SYNC_GROUP_RESOLUTION_CONTROL_BUDGET" }
            return lanSyncJson.decodeFromString(serializer, lanSyncUtf8(plain))
        } finally { plain.fill(0) }
    }
    private fun <T> writeProtected(path: Path, serializer: KSerializer<T>, value: T) {
        val plain = lanSyncJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
        try { require(plain.size in 1..MAX_PLAIN) { "SYNC_GROUP_RESOLUTION_CONTROL_BUDGET" }
            val encrypted = protect(plain); val roundTrip = unprotect(encrypted)
            try { require(roundTrip.contentEquals(plain)) { "SYNC_GROUP_RESOLUTION_PROTECTION" } } finally { roundTrip.fill(0) }
            require(encrypted.size in 1..MAX_PROTECTED) { "SYNC_GROUP_RESOLUTION_CONTROL_BUDGET" }; lanSyncAtomicWrite(path, encrypted)
        } finally { plain.fill(0) }
    }
    private fun <T> guarded(work: () -> T): T {
        check(!lock.isHeldByCurrentThread) { "SYNC_GROUP_RESOLUTION_REENTRY" }; lock.lockInterruptibly()
        try { PrivateSyncFile.checked(root, true); val path = root.resolve("resolution.lock")
            FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS).use { channel -> PrivateSyncFile.checked(path); channel.lock().use { return work() } }
        } finally { lock.unlock() }
    }
    companion object {
        private val locks = ConcurrentHashMap<Path, ReentrantLock>()
        private const val MAX_PLAIN = 16 * 1024 * 1024
        private const val MAX_PROTECTED = MAX_PLAIN + 2 * 1024 * 1024
    }
}
