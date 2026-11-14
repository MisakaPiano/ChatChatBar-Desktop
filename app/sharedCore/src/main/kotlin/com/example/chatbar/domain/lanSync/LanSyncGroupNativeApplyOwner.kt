package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable enum class LanSyncGroupNativeApplyStatus { APPLIED, NOT_APPLIED, SOURCE_COMMIT_RECORDED }
data class LanSyncGroupNativeApplyReceipt(val transaction: String, val status: LanSyncGroupNativeApplyStatus, val advancedUnits: Set<String>,
    val heldUnits: Set<String> = emptySet())
data class LanSyncGroupNativeState(val units: Map<String, LanSyncGroupAppliedUnit>, val completedSequence: Long, val pendingTransaction: String?)

/** All methods run under the platform's exclusive native-data admission, including startup recovery. */
class LanSyncGroupNativeApplyOwner(private val root: Path, private val workspace: LanSyncWorkspace,
    private val trust: SyncGroupTrust, private val actorCertificate: String, private val projection: LanSyncProjection,
    private val authority: () -> SyncDeviceLedger, private val currentView: () -> SyncInboxReconciliation,
    private val protect: (ByteArray) -> ByteArray, private val unprotect: (ByteArray) -> ByteArray,
    private val observe: (String) -> Unit = {}, private val authorJournal: SyncAuthorJournal? = null) {
    private val actor = SyncDeviceSignatures.decode(actorCertificate)
    private val lock: ReentrantLock
    private var incarnation: SyncStoreId? = null
    private var originTrust: SyncGroupTrust? = null
    @Serializable private data class Origin(val trust: SyncGroupTrust, val certificate: String, val projection: String, val incarnation: SyncStoreId)
    @Serializable private data class Frame(val domain: String, val group: SyncStoreId, val device: String,
        val incarnation: SyncStoreId, val kind: String, val value: JsonElement)
    @Serializable private data class Head(val sequence: Long, val receipt: String?)
    @Serializable private data class Intent(val sequence: Long, val transaction: String, val previousReceipt: String?,
        val payload: SyncPlaintextIdentity, val preimage: String?)
    @Serializable private data class Receipt(val sequence: Long, val transaction: String, val intent: String, val status: LanSyncGroupNativeApplyStatus)
    @Serializable private data class Payload(val plan: LanSyncPlan, val checkpoint: SyncVersionRef, val heads: Set<SyncVersionRef>,
        val before: Map<String, String>, val rawSource: String, val appliedBefore: String, val advances: Map<String, LanSyncGroupAppliedUnit>,
        val preimageRequired: Boolean, val sourceOperation: SyncStoreId? = null, val sourceVersion: SyncCausalVersion? = null,
        val sourceHeld: Set<String> = emptySet())
    private data class Loaded(val units: Map<String, LanSyncGroupAppliedUnit>, val completed: Long, val receipt: String?, val pending: Intent?,
        val matched: Intent? = null, val matchedReceipt: Receipt? = null)
    init { PrivateSyncFile.checked(root, true); projection.validate()
        require(actor.encoded == actorCertificate) { "SYNC_GROUP_NATIVE_CERTIFICATE" }
        lock = locks.computeIfAbsent(root) { ReentrantLock() } }

    /** Explicit first enrollment only; normal startup never reconstructs a missing native origin/head. */
    fun initializeNewEnrollment() = guarded {
        require(!Files.exists(root.resolve("origin.protected"), NOFOLLOW_LINKS)) { "SYNC_GROUP_NATIVE_ALREADY_ENROLLED" }
        require(Files.newDirectoryStream(root).use { it.all { file -> file.fileName.toString() == "native.lock" } }) { "SYNC_GROUP_NATIVE_EXISTING_EVIDENCE" }
        val ledger = authority(); requireAuthority(ledger)
        val origin = Origin(trust, actorCertificate, projection.digest(), SyncStoreId.random())
        write(root.resolve("origin.protected"), "ORIGIN", Origin.serializer(), origin)
        incarnation = origin.incarnation
        write(root.resolve("head.protected"), "HEAD", Head.serializer(), Head(0, null))
    }
    /** Explicit pending-profile initialization only; never called by ordinary open/recovery. */
    internal fun resumeNewEnrollment(): LanSyncGroupNativeState = guarded {
        val author = requireNotNull(authorJournal) { "SYNC_GROUP_NATIVE_SOURCE_JOURNAL_MISSING" }.state()
        require(author.committedSequence == 0L && author.pendingApproved == null) { "SYNC_GROUP_NATIVE_ALREADY_USED" }
        if (Files.exists(root.resolve("head.protected"), NOFOLLOW_LINKS)) {
            val state = publicState(load())
            require(state.completedSequence == 0L && state.pendingTransaction == null && state.units.isEmpty()) { "SYNC_GROUP_NATIVE_ALREADY_USED" }
            return@guarded state
        }
        require(Files.newDirectoryStream(root).use { files -> files.all {
            it.fileName.toString() in setOf("native.lock", "origin.protected")
        } }) { "SYNC_GROUP_NATIVE_EXISTING_EVIDENCE" }
        val origin = requireNotNull(read(root.resolve("origin.protected"), "ORIGIN", Origin.serializer())) { "SYNC_GROUP_NATIVE_ORIGIN_MISSING" }
        require(sameAnchor(origin.trust, trust) && origin.certificate == actorCertificate && origin.projection == projection.digest()) {
            "SYNC_GROUP_NATIVE_ORIGIN_CHANGED"
        }
        originTrust = origin.trust; requireAuthority(authority())
        // read retained the original incarnation; never generate a replacement origin.
        write(root.resolve("head.protected"), "HEAD", Head.serializer(), Head(0, null))
        observe("INITIAL_NATIVE_HEAD_COMPLETED")
        publicState(load())
    }
    fun state(): LanSyncGroupNativeState = guarded { publicState(load()) }
    fun recover(): LanSyncGroupNativeState = guarded { recoverOwned(); publicState(load()) }

    /** Records an approved historical local source fact; never applies its old full snapshot. */
    fun recordSource(commit: LanSyncGroupSourceCommit): LanSyncGroupNativeApplyReceipt = guarded {
        recoverOwned()
        val entry = requireNotNull(authorJournal?.entry(commit.entry.request.operation)) { "SYNC_GROUP_NATIVE_SOURCE_JOURNAL_MISSING" }
        require(entry == commit.entry && commit.expectedApplied.keys == entry.version.changes.keys) { "SYNC_GROUP_NATIVE_SOURCE_CHANGED" }
        val transaction = java.util.UUID.nameUUIDFromBytes("ccb-group-source-receipt/${trust.group.value}/${actor.fingerprint}/${entry.request.operation.value}"
            .toByteArray(Charsets.UTF_8)).toString()
        val loaded = load(transaction)
        loaded.matched?.let { previous ->
            require(payload(previous).sourceOperation == entry.request.operation && payload(previous).sourceVersion == entry.version) { "SYNC_GROUP_NATIVE_OPERATION_CHANGED" }
            return@guarded publicReceipt(previous, requireNotNull(loaded.matchedReceipt))
        }
        val checkpoint = workspace.recovery.read(entry.request.sourceCheckpoint)
        val snapshot = checkpoint?.let(workspace.recovery::snapshot) ?: LanSyncSnapshot(deviceId = actor.fingerprint,
            records = emptyList(), coveredTypes = commit.coverage)
        val history = currentView().history
        val advances = linkedMapOf<String, LanSyncGroupAppliedUnit>(); val held = linkedSetOf<String>()
        entry.version.changes.forEach { (unit, change) ->
            val current = loaded.units[unit]; val ownReference = entry.version.reference()
            when {
                current == commit.expectedApplied[unit] -> advances[unit] = LanSyncGroupAppliedUnit(change.valueHash, setOf(ownReference))
                current?.frontiers?.all { it == ownReference || history?.isAncestorOrSelf(ownReference, it) == true } == true -> Unit
                else -> held += unit
            }
        }
        require(loaded.pending == null && loaded.completed < Long.MAX_VALUE) { "SYNC_GROUP_NATIVE_PENDING" }
        val payload = Payload(LanSyncPlan(transaction, snapshot, emptyMap()), entry.version.deviceLedger, entry.version.parents,
            snapshot.hashes(), entry.request.sourceRawBinding, stateBinding(loaded.units), advances, false, entry.request.operation,
            entry.version, held)
        verifySource(payload)
        val file = payloadPath(transaction); require(!Files.exists(file, NOFOLLOW_LINKS)) { "SYNC_GROUP_NATIVE_EXISTING_PAYLOAD" }
        LanSyncJsonFiles.write(file, Payload.serializer(), payload)
        val intent = Intent(loaded.completed + 1, transaction, loaded.receipt,
            SyncPlaintextIdentity(Files.size(file), LanSyncResourceStore.hashFile(file, Files.size(file))), null)
        write(intentPath(intent.sequence), "INTENT", Intent.serializer(), intent)
        observe("NATIVE_SOURCE_INTENT_RETAINED")
        val receipt = complete(intent, LanSyncGroupNativeApplyStatus.SOURCE_COMMIT_RECORDED)
        publicReceipt(intent, receipt)
    }

    /** No source signing or UI decisions occur here: only already-approved incoming facts can advance. */
    fun apply(plan: LanSyncGroupNativePlan): LanSyncGroupNativeApplyReceipt? = guarded {
        recoverOwned()
        val loaded = load(plan.plan.transaction)
        loaded.matched?.let { previous ->
            require(payload(previous) == payload(plan)) { "SYNC_GROUP_NATIVE_OPERATION_CHANGED" }
            val receipt = requireNotNull(loaded.matchedReceipt) { "SYNC_GROUP_NATIVE_PENDING" }
            return@guarded publicReceipt(previous, receipt)
        }
        require(loaded.pending == null && loaded.units == plan.appliedBefore) { "SYNC_GROUP_NATIVE_CONTROL_CHANGED" }
        if (plan.advances.isEmpty()) return@guarded null
        require(plan.plan.transaction.matches(Regex("[a-f0-9-]{36}")) && !workspace.applied(plan.plan.transaction)) { "SYNC_GROUP_NATIVE_FOREIGN_RECEIPT" }
        require(plan.before.deviceId == actor.fingerprint && plan.plan.snapshot.deviceId == actor.fingerprint) { "SYNC_GROUP_NATIVE_DEVICE" }
        requireView(plan)
        requireFreshSource(plan)
        val preimage = workspace.protectBeforeApply(plan.plan, plan.expected, actor.fingerprint, projection)
        val needsPreimage = LanSyncImpactReview.changes(plan.before.records, plan.plan.snapshot.records).any { it.needsProtection }
        require(!needsPreimage || preimage != null) { "SYNC_GROUP_NATIVE_PREIMAGE_MISSING" }
        preimage?.let { workspace.recovery.verify(it) }
        requireView(plan); requireFreshSource(plan)
        require(loaded.completed < Long.MAX_VALUE) { "SYNC_GROUP_NATIVE_SEQUENCE_EXHAUSTED" }
        val payload = payload(plan); val file = payloadPath(plan.plan.transaction)
        require(!Files.exists(file, NOFOLLOW_LINKS)) { "SYNC_GROUP_NATIVE_EXISTING_PAYLOAD" }
        LanSyncJsonFiles.write(file, Payload.serializer(), payload)
        val identity = SyncPlaintextIdentity(Files.size(file), LanSyncResourceStore.hashFile(file, Files.size(file)))
        val intent = Intent(loaded.completed + 1, plan.plan.transaction, loaded.receipt, identity, preimage?.transaction)
        write(intentPath(intent.sequence), "INTENT", Intent.serializer(), intent)
        observe("NATIVE_INTENT_RETAINED")
        // Authority is live, not the ledger object originally passed to a publication queue.
        requireView(plan); requireFreshSource(plan)
        workspace.capture(actor.fingerprint, projection).use { fresh ->
            val resources = fresh.blobs + plan.units.values.flatMap { it.available?.resources.orEmpty().entries }.associate { it.toPair() }
            workspace.apply(plan.plan, plan.expected, actor.fingerprint, resources, projection, expectedSourceFiles = plan.sourceFiles)
        }
        require(workspace.applied(plan.plan.transaction)) { "SYNC_GROUP_NATIVE_RECEIPT_MISSING" }
        observe("NATIVE_DATA_APPLIED")
        verifyPreimage(intent, payload)
        val receipt = complete(intent, LanSyncGroupNativeApplyStatus.APPLIED)
        publicReceipt(intent, receipt)
    }

    private fun recoverOwned() {
        val loaded = load()
        workspace.recover()
        val intent = loaded.pending ?: return
        val payload = payload(intent)
        require(payload.appliedBefore == stateBinding(loaded.units)) { "SYNC_GROUP_NATIVE_CONTROL_CHANGED" }
        if (payload.sourceOperation != null) {
            verifySource(payload)
            complete(intent, LanSyncGroupNativeApplyStatus.SOURCE_COMMIT_RECORDED)
            observe("NATIVE_SOURCE_RECEIPT_RECOVERED")
            return
        }
        // An applied WAL receipt is historical authority even if later native edits now differ.
        // Never replay this old target to 'verify' it against today's source.
        val status = if (workspace.applied(intent.transaction)) LanSyncGroupNativeApplyStatus.APPLIED else LanSyncGroupNativeApplyStatus.NOT_APPLIED
        if (status == LanSyncGroupNativeApplyStatus.APPLIED) verifyPreimage(intent, payload)
        complete(intent, status)
        observe(if (status == LanSyncGroupNativeApplyStatus.APPLIED) "NATIVE_RECEIPT_RECOVERED" else "NATIVE_INERT_PREPARATION_RETAINED")
    }
    private fun requireFreshSource(plan: LanSyncGroupNativePlan) = workspace.capture(actor.fingerprint, projection).use { fresh ->
        require(fresh.snapshot.coveredTypes == plan.before.coveredTypes && fresh.snapshot.hashes() == plan.expected && fresh.sourceFiles == plan.sourceFiles) {
            "SYNC_GROUP_NATIVE_SOURCE_CHANGED"
        }
    }
    private fun requireAuthority(ledger: SyncDeviceLedger) {
        require(sameAnchor(ledger.trustAnchor, trust) && ledger.contains(trust.checkpoint) && originTrust?.checkpoint?.let(ledger::contains) != false) { "SYNC_GROUP_NATIVE_TRUST_CHANGED" }
        require(ledger.current().members[actor.fingerprint]?.let { it.active && it.certificate == actorCertificate } == true) { "SYNC_GROUP_NATIVE_REVOKED_OR_CHANGED" }
    }
    private fun requireView(plan: LanSyncGroupNativePlan) {
        val ledger = authority(); requireAuthority(ledger)
        require(ledger.current().reference() == plan.checkpoint && ledger.current().coveredTypes == plan.before.coveredTypes) { "SYNC_GROUP_NATIVE_VIEW_CHANGED" }
        val view = currentView()
        require(!view.trustBlocked && ledger.current().reference() == plan.checkpoint && view.ledgerCheckpoint == plan.checkpoint &&
            view.history?.heads() == plan.heads && ledger.current().coveredTypes == plan.before.coveredTypes) { "SYNC_GROUP_NATIVE_VIEW_CHANGED" }
    }
    private fun payload(plan: LanSyncGroupNativePlan) = Payload(plan.plan, plan.checkpoint, plan.heads, plan.expected,
        rawBinding(plan.sourceFiles), stateBinding(plan.appliedBefore), plan.advances,
        LanSyncImpactReview.changes(plan.before.records, plan.plan.snapshot.records).any { it.needsProtection })
    private fun payload(intent: Intent): Payload {
        require(intent.transaction.matches(Regex("[a-f0-9-]{36}")) && intent.sequence > 0) { "SYNC_GROUP_NATIVE_INTENT" }
        val file = payloadPath(intent.transaction)
        require(Files.size(file) == intent.payload.length) { "SYNC_GROUP_NATIVE_PAYLOAD_CHANGED" }
        LanSyncMetadataBudget().validate(file)
        val payload = requireNotNull(LanSyncJsonFiles.read(file, Payload.serializer(), intent.payload.sha256)) { "SYNC_GROUP_NATIVE_PAYLOAD_MISSING" }
        require(payload.plan.transaction == intent.transaction && payload.plan.snapshot.deviceId == actor.fingerprint && (payload.advances.isNotEmpty() || payload.sourceOperation != null) &&
            payload.rawSource.matches(Regex("[a-f0-9]{64}")) && payload.appliedBefore.matches(Regex("[a-f0-9]{64}"))) { "SYNC_GROUP_NATIVE_PAYLOAD_CHANGED" }
        payload.plan.snapshot.validate(); LanSyncScope.validateGraph(payload.plan.snapshot)
        require((payload.sourceOperation == null) == (payload.sourceVersion == null) && (payload.sourceOperation != null || payload.sourceHeld.isEmpty())) { "SYNC_GROUP_NATIVE_PAYLOAD_CHANGED" }
        payload.sourceVersion?.let { source ->
            require(source.group == trust.group && source.author == actor.fingerprint && actor.verifies(source.signingBytes(), source.signature) &&
                payload.checkpoint == source.deviceLedger && payload.heads == source.parents &&
                source.changes.all { (unit, change) -> payload.plan.snapshot.hashes()[unit] == change.valueHash } &&
                payload.advances.all { (unit, value) -> unit in source.changes && value.valueHash == source.changes[unit]?.valueHash && value.frontiers == setOf(source.reference()) } &&
                payload.sourceHeld.all { it in source.changes && it !in payload.advances }) { "SYNC_GROUP_NATIVE_SOURCE_CHANGED" }
        }
        val expected = payload.before.toMutableMap()
        payload.advances.forEach { (unit, next) -> if (next.valueHash == null) expected.remove(unit) else expected[unit] = next.valueHash }
        require(payload.plan.snapshot.hashes() == expected) { "SYNC_GROUP_NATIVE_UNPROCESSED_UNIT_CHANGED" }
        return payload
    }
    private fun verifySource(payload: Payload) {
        val operation = requireNotNull(payload.sourceOperation) { "SYNC_GROUP_NATIVE_SOURCE_CHANGED" }
        val entry = requireNotNull(authorJournal?.entry(operation)) { "SYNC_GROUP_NATIVE_SOURCE_JOURNAL_MISSING" }
        require(entry.version == payload.sourceVersion && entry.version.group == trust.group && entry.version.author == actor.fingerprint &&
            entry.request.sourceRawBinding == payload.rawSource) { "SYNC_GROUP_NATIVE_SOURCE_CHANGED" }
        val checkpoint = workspace.recovery.read(entry.request.sourceCheckpoint)
        if (checkpoint == null) require(payload.plan.snapshot.records.isEmpty() && payload.plan.snapshot.resources.isEmpty() &&
            payload.rawSource == rawBinding(emptyMap()) && entry.version.changes.values.all { it.content == null }) { "SYNC_GROUP_NATIVE_SOURCE_ORIGINALS_MISSING" }
        else {
            require(checkpoint.sourceCheckpointGroup == trust.group.value && checkpoint.sourceDevice == actor.fingerprint && checkpoint.hasLocalOriginals &&
                checkpoint.rawSourceBinding == payload.rawSource && workspace.recovery.snapshot(checkpoint) == payload.plan.snapshot &&
                workspace.recovery.sourceProjection(checkpoint) == projection) { "SYNC_GROUP_NATIVE_SOURCE_ORIGINALS_CHANGED" }
            workspace.recovery.verify(checkpoint)
        }
        require(entry.version.changes.all { (unit, change) -> change.valueHash == payload.plan.snapshot.hashes()[unit] &&
            (change.membership == null || change.membership == LanSyncGroupMembership.identity(payload.plan.snapshot.units().getValue(unit))) }) { "SYNC_GROUP_NATIVE_SOURCE_CHANGED" }
    }
    private fun verifyPreimage(intent: Intent, payload: Payload) {
        require(!payload.preimageRequired || intent.preimage != null) { "SYNC_GROUP_NATIVE_PREIMAGE_MISSING" }
        intent.preimage?.let { id ->
            val entry = requireNotNull(workspace.recovery.read(id)) { "SYNC_GROUP_NATIVE_PREIMAGE_MISSING" }
            require(entry.hasLocalOriginals && (entry.custodyDevice ?: entry.sourceDevice) == actor.fingerprint &&
                entry.targetBinding == snapshotBinding(payload.plan.snapshot) && entry.sourceBinding ==
                lanSyncHash(JsonObject(payload.before.mapValues { JsonPrimitive(it.value) })) && entry.rawSourceBinding == payload.rawSource &&
                workspace.recovery.sourceProjection(entry) == projection) { "SYNC_GROUP_NATIVE_PREIMAGE_CHANGED" }
            workspace.recovery.verify(entry)
        }
    }
    private fun complete(intent: Intent, status: LanSyncGroupNativeApplyStatus): Receipt {
        val receipt = Receipt(intent.sequence, intent.transaction, binding(Intent.serializer(), intent), status)
        write(receiptPath(intent.sequence), "RECEIPT", Receipt.serializer(), receipt)
        observe("NATIVE_CONTROL_RECEIPT_RETAINED")
        write(root.resolve("head.protected"), "HEAD", Head.serializer(), Head(intent.sequence, binding(Receipt.serializer(), receipt)), true)
        return receipt
    }
    private fun load(match: String? = null): Loaded {
        val origin = requireNotNull(read(root.resolve("origin.protected"), "ORIGIN", Origin.serializer())) { "SYNC_GROUP_NATIVE_ORIGIN_MISSING" }
        require(sameAnchor(origin.trust, trust) && origin.certificate == actorCertificate && origin.projection == projection.digest()) { "SYNC_GROUP_NATIVE_ORIGIN_CHANGED" }
        originTrust = origin.trust
        val head = requireNotNull(read(root.resolve("head.protected"), "HEAD", Head.serializer())) { "SYNC_GROUP_NATIVE_HEAD_MISSING" }
        val intents = inventory("intent"); val receipts = inventory("receipt")
        require(head.sequence in 0..receipts && receipts <= intents && intents - receipts <= 1 && (head.sequence == 0L) == (head.receipt == null)) { "SYNC_GROUP_NATIVE_HISTORY_MISSING" }
        val units = linkedMapOf<String, LanSyncGroupAppliedUnit>(); var previous: String? = null
        var pending: Intent? = null; var matched: Intent? = null; var matchedReceipt: Receipt? = null
        var sequence = 0L
        while (sequence < intents) {
            sequence++
            val intent = requireNotNull(read(intentPath(sequence), "INTENT", Intent.serializer())) { "SYNC_GROUP_NATIVE_HISTORY_MISSING" }
            require(intent.sequence == sequence && intent.previousReceipt == previous) { "SYNC_GROUP_NATIVE_HISTORY_CHANGED" }
            val payload = payload(intent)
            require(payload.appliedBefore == stateBinding(units)) { "SYNC_GROUP_NATIVE_HISTORY_CHANGED" }
            val receipt = read(receiptPath(sequence), "RECEIPT", Receipt.serializer())
            if (receipt == null) { require(sequence == intents) { "SYNC_GROUP_NATIVE_HISTORY_MISSING" }; pending = intent }
            else {
                require(receipt.sequence == sequence && receipt.transaction == intent.transaction && receipt.intent == binding(Intent.serializer(), intent)) { "SYNC_GROUP_NATIVE_RECEIPT_CHANGED" }
                require((payload.sourceOperation != null) == (receipt.status == LanSyncGroupNativeApplyStatus.SOURCE_COMMIT_RECORDED)) { "SYNC_GROUP_NATIVE_RECEIPT_CHANGED" }
                if (receipt.status in setOf(LanSyncGroupNativeApplyStatus.APPLIED, LanSyncGroupNativeApplyStatus.SOURCE_COMMIT_RECORDED)) units.putAll(payload.advances.mapValues { (_, value) ->
                    value.copy(frontiers = Collections.unmodifiableSet(value.frontiers.toSet()))
                })
                previous = binding(Receipt.serializer(), receipt)
                if (sequence == head.sequence) require(previous == head.receipt) { "SYNC_GROUP_NATIVE_HEAD_CHANGED" }
            }
            if (intent.transaction == match) { require(matched == null) { "SYNC_GROUP_NATIVE_OPERATION_CHANGED" }; matched = intent; matchedReceipt = receipt }
        }
        if (head.sequence < receipts) write(root.resolve("head.protected"), "HEAD", Head.serializer(), Head(receipts, previous), true)
        return Loaded(Collections.unmodifiableMap(units), receipts, previous, pending, matched, matchedReceipt)
    }
    private fun inventory(prefix: String): Long {
        var count = 0L; var maximum = 0L
        Files.newDirectoryStream(root, "$prefix-*.protected").use { paths -> paths.forEach { path ->
            PrivateSyncFile.checked(path)
            val name = path.fileName.toString(); require(name.matches(Regex("$prefix-[0-9]{19}\\.protected"))) { "SYNC_GROUP_NATIVE_INVENTORY" }
            val sequence = requireNotNull(name.removePrefix("$prefix-").removeSuffix(".protected").toLongOrNull()) { "SYNC_GROUP_NATIVE_INVENTORY" }
            require(sequence > 0 && count < Long.MAX_VALUE) { "SYNC_GROUP_NATIVE_INVENTORY" }; count++; maximum = maxOf(maximum, sequence)
        } }
        require(count == maximum) { "SYNC_GROUP_NATIVE_HISTORY_MISSING" }; return count
    }
    private fun publicState(state: Loaded) = LanSyncGroupNativeState(state.units, state.completed, state.pending?.transaction)
    private fun publicReceipt(intent: Intent, receipt: Receipt) = LanSyncGroupNativeApplyReceipt(intent.transaction, receipt.status,
        if (receipt.status in setOf(LanSyncGroupNativeApplyStatus.APPLIED, LanSyncGroupNativeApplyStatus.SOURCE_COMMIT_RECORDED)) Collections.unmodifiableSet(payload(intent).advances.keys) else emptySet(),
        Collections.unmodifiableSet(payload(intent).sourceHeld))
    private fun intentPath(sequence: Long) = root.resolve("intent-${sequence.toString().padStart(19, '0')}.protected")
    private fun receiptPath(sequence: Long) = root.resolve("receipt-${sequence.toString().padStart(19, '0')}.protected")
    private fun payloadPath(transaction: String) = root.resolve("plan-$transaction.json")
    private fun sameAnchor(a: SyncGroupTrust, b: SyncGroupTrust) = a.group == b.group && a.genesis == b.genesis && a.ownerCertificate == b.ownerCertificate
    private fun snapshotBinding(snapshot: LanSyncSnapshot) = lanSyncHash(JsonObject(snapshot.hashes().mapValues { JsonPrimitive(it.value) }))
    private fun rawBinding(files: Map<String, String>) = lanSyncHashSequence(files.toSortedMap().asSequence().map { (path, hash) ->
        buildJsonObject { put("path", path); put("hash", hash) }
    })
    private fun stateBinding(units: Map<String, LanSyncGroupAppliedUnit>) = lanSyncHashSequence(units.toSortedMap().asSequence().map { (unit, value) ->
        buildJsonObject { put("unit", unit); put("hash", value.valueHash?.let(::JsonPrimitive) ?: JsonNull)
            put("frontiers", JsonArray(value.frontiers.sortedBy { it.id.value }.map { lanSyncJson.encodeToJsonElement(SyncVersionRef.serializer(), it) })) }
    })
    private fun <T> binding(serializer: KSerializer<T>, value: T) = lanSyncHash(lanSyncJson.encodeToJsonElement(serializer, value))

    private fun <T> read(path: Path, kind: String, serializer: KSerializer<T>): T? {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        PrivateSyncFile.checked(path); require(Files.size(path) in 1..MAX_PROTECTED.toLong()) { "SYNC_GROUP_NATIVE_CONTROL_BUDGET" }
        val encrypted = Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break
                require(output.size() <= MAX_PROTECTED - count) { "SYNC_GROUP_NATIVE_CONTROL_BUDGET" }; output.write(buffer, 0, count) }
            output.toByteArray()
        }
        val borrowed = encrypted.copyOf(); val plain = try { unprotect(borrowed) } catch (failure: Throwable) { borrowed.fill(0); throw failure }
        try {
            require(plain.size in 1..MAX_PLAIN) { "SYNC_GROUP_NATIVE_CONTROL_BUDGET" }
            val text = lanSyncUtf8(plain); var depth = 0; var quoted = false; var escaped = false
            text.forEach { char ->
                if (quoted) { if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false }
                else when (char) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 16) { "SYNC_GROUP_NATIVE_CONTROL_BUDGET" } }; '}', ']' -> depth-- }
                require(depth >= 0) { "SYNC_GROUP_NATIVE_PRIVATE_CONTEXT" }
            }
            require(!quoted && depth == 0) { "SYNC_GROUP_NATIVE_PRIVATE_CONTEXT" }
            val frame = lanSyncJson.decodeFromString(Frame.serializer(), text)
            require(frame.domain == DOMAIN && frame.group == trust.group && frame.device == actor.fingerprint && frame.kind == kind) { "SYNC_GROUP_NATIVE_PRIVATE_CONTEXT" }
            val value = lanSyncJson.decodeFromJsonElement(serializer, frame.value)
            if (kind == "ORIGIN") { require((value as Origin).incarnation == frame.incarnation) { "SYNC_GROUP_NATIVE_PRIVATE_CONTEXT" }; incarnation = frame.incarnation }
            else require(frame.incarnation == incarnation) { "SYNC_GROUP_NATIVE_PRIVATE_CONTEXT" }
            return value
        } finally { plain.fill(0); borrowed.fill(0) }
    }
    private fun <T> write(path: Path, kind: String, serializer: KSerializer<T>, value: T, replace: Boolean = false) {
        if (Files.exists(path, NOFOLLOW_LINKS)) { PrivateSyncFile.checked(path); require(replace) { "SYNC_GROUP_NATIVE_IMMUTABLE" } }
        val owner = if (kind == "ORIGIN") (value as Origin).incarnation else requireNotNull(incarnation) { "SYNC_GROUP_NATIVE_ORIGIN_MISSING" }
        val plain = lanSyncJson.encodeToString(Frame.serializer(), Frame(DOMAIN, trust.group, actor.fingerprint, owner, kind,
            lanSyncJson.encodeToJsonElement(serializer, value))).toByteArray(Charsets.UTF_8)
        require(plain.size in 1..MAX_PLAIN) { "SYNC_GROUP_NATIVE_CONTROL_BUDGET" }
        val encrypted = try { protect(plain).copyOf() } finally { plain.fill(0) }
        require(encrypted.size in 1..MAX_PROTECTED) { "SYNC_GROUP_NATIVE_CONTROL_BUDGET" }
        PrivateSyncFile.create(root).use { file ->
            FileChannel.open(file.path, WRITE, NOFOLLOW_LINKS).use { channel -> val bytes = ByteBuffer.wrap(encrypted)
                while (bytes.hasRemaining()) channel.write(bytes); channel.force(true) }
            if (replace) Files.move(file.path, path, ATOMIC_MOVE, REPLACE_EXISTING) else Files.move(file.path, path, ATOMIC_MOVE)
        }
    }
    private fun <T> guarded(work: () -> T): T {
        check(!lock.isHeldByCurrentThread) { "SYNC_GROUP_NATIVE_REENTRY" }; lock.lockInterruptibly()
        try { incarnation = null; originTrust = null; PrivateSyncFile.checked(root, true)
            val path = root.resolve("native.lock")
            FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS).use { channel -> PrivateSyncFile.checked(path); channel.lock().use { return work() } }
        } finally { lock.unlock() }
    }
    companion object {
        private const val DOMAIN = "CCB-SYNC-NATIVE-CONTROL/1"
        private const val MAX_PLAIN = 1024 * 1024
        private const val MAX_PROTECTED = MAX_PLAIN + 1024 * 1024
        private val locks = ConcurrentHashMap<Path, ReentrantLock>()
    }
}
