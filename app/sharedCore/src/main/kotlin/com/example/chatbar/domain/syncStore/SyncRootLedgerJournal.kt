package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.lanSyncJson
import com.example.chatbar.domain.lanSync.lanSyncUtf8
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class SyncRootLedgerRequest(val operation: SyncStoreId, val expectedParent: SyncVersionRef,
    val members: Map<String, SyncGroupMember>, val coveredTypes: Set<String>, val acceptedHeads: Set<SyncVersionRef>,
    val rotateKey: Boolean, val approvalBinding: String) {
    init { require(approvalBinding.matches(Regex("[a-f0-9]{64}"))) { "SYNC_ROOT_APPROVAL_BINDING" } }
}
fun interface SyncRootLedgerAdmission { fun requireApproved(request: SyncRootLedgerRequest) }
@Serializable data class SyncRootLedgerEntry(val request: SyncRootLedgerRequest, val record: SyncDeviceLedgerRecord,
    internal val protectedKey: String?)
data class SyncRootLedgerState(val completedSequence: Long, val last: SyncVersionRef?, val pendingOperation: SyncStoreId?)
class SyncRootLedgerCommit internal constructor(val entry: SyncRootLedgerEntry, val records: List<SyncDeviceLedgerRecord>,
    internal val newKey: SyncStreamingCipher?)

/** Root-only approved ledger actions. App admission still owns real peer proof, frozen review and scheduling. */
class SyncRootLedgerJournal(private val root: Path, private val trust: SyncGroupTrust, actorCertificate: String,
    private val protect: (ByteArray) -> ByteArray, private val unprotect: (ByteArray) -> ByteArray,
    private val observe: (String) -> Unit = {}) {
    private val actor = SyncDeviceSignatures.decode(actorCertificate)
    private val lock: ReentrantLock
    private var incarnation: SyncStoreId? = null
    @Serializable private data class Origin(val trust: SyncGroupTrust, val records: List<SyncDeviceLedgerRecord>, val incarnation: SyncStoreId)
    @Serializable private data class Frame(val domain: String, val group: SyncStoreId, val actor: String, val incarnation: SyncStoreId,
        val kind: String, val payload: JsonElement)
    @Serializable private data class Head(val sequence: Long, val operation: SyncStoreId, val reference: SyncVersionRef)
    @Serializable private data class Index(val sequence: Long, val operation: SyncStoreId)
    private data class Loaded(val ledger: SyncDeviceLedger, val sequence: Long, val last: SyncRootLedgerEntry?, val pending: SyncRootLedgerEntry?)
    init {
        PrivateSyncFile.checked(root, true)
        require(actor.encoded == actorCertificate && actorCertificate == trust.ownerCertificate) { "SYNC_ROOT_NOT_OWNER" }
        lock = locks.computeIfAbsent(root) { ReentrantLock() }
    }

    fun initializeNewEnrollment(ledger: SyncDeviceLedger) = guarded {
        require(!Files.exists(root.resolve("origin.protected"), NOFOLLOW_LINKS) && Files.newDirectoryStream(root).use {
            it.all { path -> path.fileName.toString() == "root.lock" }
        }) { "SYNC_ROOT_EXISTING_EVIDENCE" }
        requireOwner(ledger)
        val origin = Origin(trust.copy(checkpoint = ledger.current().reference()), ledger.history(), SyncStoreId.random())
        write(root.resolve("origin.protected"), "ORIGIN", Origin.serializer(), origin)
    }
    fun state(): SyncRootLedgerState = guarded { load().let {
        SyncRootLedgerState(it.sequence, it.last?.record?.reference(), it.pending?.request?.operation)
    } }
    fun entry(operation: SyncStoreId): SyncRootLedgerEntry? = guarded {
        load(); val index = read(indexPath(operation), "INDEX", Index.serializer()) ?: return@guarded null
        require(index.operation == operation) { "SYNC_ROOT_INDEX" }
        read(signedPath(index.sequence), "SIGNED", SyncRootLedgerEntry.serializer())?.also {
            validateSigned(it, requireIntent(index.sequence))
        }
    }
    /** Next exact owned fact/approved intent, derived only from the retained signed lineage. */
    fun nextRecoveryRequest(custody: SyncGroupCustody): SyncRootLedgerRequest? = guarded {
        val loaded = load(); val authority = custody.ledger(); requireOwner(authority)
        val current = authority.current()
        require(loaded.ledger.contains(current.reference())) { "SYNC_ROOT_FOREIGN_LEDGER_PROGRESS" }
        val missing = loaded.ledger.current().sequence - current.sequence
        if (missing > 0L) {
            val next = loaded.sequence - missing + 1
            require(next in 1..loaded.sequence) { "SYNC_ROOT_ORIGIN_PREFIX_NOT_APPLIED" }
            return@guarded requireNotNull(read(signedPath(next), "SIGNED", SyncRootLedgerEntry.serializer())) { "SYNC_ROOT_SIGNED_MISSING" }.request
        }
        val owned = signedKeys(loaded.sequence).associateBy { it.record.epoch }
        loaded.ledger.history().distinctBy { it.epoch }.forEach { row ->
            val actual = try { custody.key(row.epoch) } catch (failure: IllegalArgumentException) {
                if (failure.message != "SYNC_GROUP_CUSTODY_KEY_MISSING") throw failure
                return@guarded requireNotNull(owned[row.epoch]) { "SYNC_ROOT_ORIGIN_KEY_RECOVERY_REQUIRED" }.request
            }
            require(actual.group == row.group && actual.epoch == row.epoch && actual.keyCommitment() == row.keyCommitment) { "SYNC_ROOT_RECOVERY_KEY_CHANGED" }
        }
        loaded.pending?.request
    }

    fun commit(request: SyncRootLedgerRequest, authority: () -> SyncDeviceLedger, admission: SyncRootLedgerAdmission,
        sign: (ByteArray) -> ByteArray): SyncRootLedgerCommit = guarded {
        val frozen = request.copy(members = Collections.unmodifiableMap(request.members.toSortedMap()),
            coveredTypes = Collections.unmodifiableSet(request.coveredTypes.toSortedSet()),
            acceptedHeads = Collections.unmodifiableSet(request.acceptedHeads.toSet()))
        val loaded = load(); requireOwner(authority())
        val index = read(indexPath(frozen.operation), "INDEX", Index.serializer())
        if (index != null) {
            require(index.operation == frozen.operation) { "SYNC_ROOT_INDEX" }
            val intent = requireIntent(index.sequence)
            require(intent.request == frozen) { "SYNC_ROOT_OPERATION_CHANGED" }
            read(signedPath(index.sequence), "SIGNED", SyncRootLedgerEntry.serializer())?.let {
                validateSigned(it, intent); return@guarded result(it, loaded.ledger)
            }
            return@guarded signAndRetain(index.sequence, intent, loaded.ledger, authority, sign)
        }
        require(loaded.pending == null) { "SYNC_ROOT_PENDING_APPROVED_ACTION" }
        requireCurrent(authority(), loaded.ledger, frozen.expectedParent)
        require(loaded.sequence < Long.MAX_VALUE && loaded.ledger.current().sequence < Long.MAX_VALUE) { "SYNC_ROOT_SEQUENCE_EXHAUSTED" }
        val parent = loaded.ledger.current()
        val nextKey = if (frozen.rotateKey) SyncStreamingCipher.create(trust.group) else null
        val row = SyncDeviceLedgerRecord(trust.group, SyncStoreId.random(), parent.reference(), parent.sequence + 1,
            nextKey?.epoch ?: parent.epoch, nextKey?.keyCommitment() ?: parent.keyCommitment, frozen.coveredTypes,
            frozen.members, frozen.acceptedHeads, "unsigned")
        loaded.ledger.validateProposedChild(row)
        admission.requireApproved(frozen)
        requireCurrent(authority(), loaded.ledger, frozen.expectedParent)
        val intent = SyncRootLedgerEntry(frozen, row, nextKey?.let { Base64.getEncoder().encodeToString(it.protectedKeyset(protect)) })
        write(intentPath(loaded.sequence + 1), "INTENT", SyncRootLedgerEntry.serializer(), intent)
        observe("APPROVED_ROOT_INTENT_RETAINED")
        ensureIndex(loaded.sequence + 1, intent)
        signAndRetain(loaded.sequence + 1, intent, loaded.ledger, authority, sign)
    }

    /** Native root recovery applies the retained complete signed prefix; no discovery can invoke this as enrollment. */
    fun applyToCustody(commit: SyncRootLedgerCommit, custody: SyncGroupCustody): SyncDeviceLedger = guarded {
        val loaded = load(); val index = requireNotNull(read(indexPath(commit.entry.request.operation), "INDEX", Index.serializer())) { "SYNC_ROOT_INDEX" }
        val retained = requireNotNull(read(signedPath(index.sequence), "SIGNED", SyncRootLedgerEntry.serializer())) { "SYNC_ROOT_SIGNED_MISSING" }
        require(retained == commit.entry && loaded.ledger.contains(retained.record.reference()) &&
            commit.records == loaded.ledger.history(retained.record.reference())) { "SYNC_ROOT_OPERATION_CHANGED" }
        requireOwner(custody.ledger())
        val keys = signedKeys(loaded.sequence).map { entry ->
            SyncAdmittedGroupKey(requireNotNull(key(entry)), actor.fingerprint, entry.record.reference())
        }
        // Apply all already-approved owned facts/keys together, including repair of an older split-write window.
        custody.updateWithKeys(loaded.ledger.history(), keys)
    }
    private fun signedKeys(sequence: Long): List<SyncRootLedgerEntry> {
        val result = mutableListOf<SyncRootLedgerEntry>(); var index = 0L
        while (index < sequence) { index++
            val entry = requireNotNull(read(signedPath(index), "SIGNED", SyncRootLedgerEntry.serializer())) { "SYNC_ROOT_SIGNED_MISSING" }
            if (entry.request.rotateKey) result += entry
        }
        return result
    }
    private fun signAndRetain(sequence: Long, intent: SyncRootLedgerEntry, ledger: SyncDeviceLedger,
        authority: () -> SyncDeviceLedger, sign: (ByteArray) -> ByteArray): SyncRootLedgerCommit {
        requireCurrent(authority(), ledger, intent.request.expectedParent); key(intent)
        val signed = intent.copy(record = intent.record.copy(signature = Base64.getEncoder().encodeToString(sign(intent.record.signingBytes()))))
        validateSigned(signed, intent); requireCurrent(authority(), ledger, intent.request.expectedParent)
        require(ledger.offer(signed.record) == SyncVersionState.ADMITTED) { "SYNC_ROOT_LEDGER_REJECTED" }
        write(signedPath(sequence), "SIGNED", SyncRootLedgerEntry.serializer(), signed)
        observe("ROOT_SIGNED_ENTRY_RETAINED")
        advance(sequence, signed)
        return result(signed, ledger)
    }
    private fun result(entry: SyncRootLedgerEntry, ledger: SyncDeviceLedger) = SyncRootLedgerCommit(entry,
        ledger.history(entry.record.reference()), key(entry))
    private fun key(entry: SyncRootLedgerEntry): SyncStreamingCipher? {
        require((entry.protectedKey != null) == entry.request.rotateKey) { "SYNC_ROOT_KEY_MISSING_OR_EXTRA" }
        return entry.protectedKey?.let { encoded ->
            require(encoded.length in 1..128 * 1024) { "SYNC_ROOT_KEY_BUDGET" }
            SyncStreamingCipher.open(trust.group, entry.record.epoch, Base64.getDecoder().decode(encoded), unprotect).also {
                require(it.keyCommitment() == entry.record.keyCommitment) { "SYNC_ROOT_KEY_CHANGED" }
            }
        }
    }
    private fun requireOwner(ledger: SyncDeviceLedger) {
        require(sameAnchor(ledger.trustAnchor, trust) && ledger.contains(trust.checkpoint) &&
            ledger.current().members[actor.fingerprint]?.let { it.active && it.certificate == actor.encoded } == true) { "SYNC_ROOT_AUTHORITY_CHANGED" }
    }
    private fun requireCurrent(live: SyncDeviceLedger, retained: SyncDeviceLedger, parent: SyncVersionRef) {
        requireOwner(live)
        require(live.current().reference() == parent && retained.current().reference() == parent) { "SYNC_ROOT_PARENT_CHANGED" }
    }
    private fun load(): Loaded {
        val origin = requireNotNull(read(root.resolve("origin.protected"), "ORIGIN", Origin.serializer())) { "SYNC_ROOT_ORIGIN_MISSING" }
        require(sameAnchor(origin.trust, trust)) { "SYNC_ROOT_TRUST_CHANGED" }
        val ledger = SyncDeviceLedger(origin.trust); origin.records.forEach { ledger.offer(it) }
        require(ledger.history() == origin.records && ledger.current().reference() == origin.trust.checkpoint) { "SYNC_ROOT_ORIGIN_CHANGED" }
        val intents = inventory("intent"); val signed = inventory("signed")
        require(signed <= intents && intents - signed <= 1) { "SYNC_ROOT_HISTORY_MISSING" }
        val head = read(root.resolve("head.protected"), "HEAD", Head.serializer())
        require(head == null || head.sequence in 1..signed) { "SYNC_ROOT_HEAD_CHANGED" }
        var last: SyncRootLedgerEntry? = null; var pending: SyncRootLedgerEntry? = null; var sequence = 0L
        while (sequence < intents) {
            sequence++
            val intent = requireIntent(sequence)
            require(intent.request.expectedParent == ledger.current().reference()) { "SYNC_ROOT_PARENT_CHANGED" }
            validateTransition(intent, ledger); ensureIndex(sequence, intent)
            if (sequence <= signed) {
                val entry = requireNotNull(read(signedPath(sequence), "SIGNED", SyncRootLedgerEntry.serializer())) { "SYNC_ROOT_SIGNED_MISSING" }
                validateSigned(entry, intent)
                require(ledger.offer(entry.record) == SyncVersionState.ADMITTED) { "SYNC_ROOT_LEDGER_REJECTED" }
                if (head?.sequence == sequence) require(head.operation == entry.request.operation && head.reference == entry.record.reference()) { "SYNC_ROOT_HEAD_CHANGED" }
                last = entry
            } else { key(intent); pending = intent }
        }
        require(ledger.contains(trust.checkpoint)) { "SYNC_GROUP_KNOWN_CHECKPOINT_MISSING" }
        requireIndexCount(intents)
        if (last != null && head?.sequence != signed) advance(signed, last)
        return Loaded(ledger, signed, last, pending)
    }
    private fun requireIntent(sequence: Long) = requireNotNull(read(intentPath(sequence), "INTENT", SyncRootLedgerEntry.serializer())) {
        "SYNC_ROOT_INTENT_MISSING"
    }.also { entry ->
        require(entry.record.signature == "unsigned" && entry.record.group == trust.group && entry.record.parent == entry.request.expectedParent &&
            entry.record.members == entry.request.members && entry.record.coveredTypes == entry.request.coveredTypes &&
            entry.record.acceptedHeads == entry.request.acceptedHeads && (entry.protectedKey != null) == entry.request.rotateKey) { "SYNC_ROOT_INTENT_CHANGED" }
    }
    private fun validateTransition(intent: SyncRootLedgerEntry, ledger: SyncDeviceLedger) {
        val parent = ledger.current()
        require(if (intent.request.rotateKey) intent.record.epoch != parent.epoch else
            intent.record.epoch == parent.epoch && intent.record.keyCommitment == parent.keyCommitment) { "SYNC_ROOT_KEY_POLICY" }
        ledger.validateProposedChild(intent.record)
    }
    private fun validateSigned(entry: SyncRootLedgerEntry, intent: SyncRootLedgerEntry) {
        require(entry.request == intent.request && entry.protectedKey == intent.protectedKey && entry.record.copy(signature = "unsigned") == intent.record &&
            actor.verifies(entry.record.signingBytes(), entry.record.signature)) { "SYNC_ROOT_SIGNATURE_OR_BODY_CHANGED" }
    }
    private fun ensureIndex(sequence: Long, entry: SyncRootLedgerEntry) {
        val expected = Index(sequence, entry.request.operation); val path = indexPath(expected.operation)
        val previous = read(path, "INDEX", Index.serializer())
        if (previous == null) write(path, "INDEX", Index.serializer(), expected)
        else require(previous == expected) { "SYNC_ROOT_OPERATION_CHANGED" }
    }
    private fun advance(sequence: Long, entry: SyncRootLedgerEntry) = write(root.resolve("head.protected"), "HEAD", Head.serializer(),
        Head(sequence, entry.request.operation, entry.record.reference()), true)
    private fun inventory(prefix: String): Long {
        var count = 0L; var maximum = 0L
        Files.newDirectoryStream(root, "$prefix-*.protected").use { files -> files.forEach { path ->
            PrivateSyncFile.checked(path); val name = path.fileName.toString()
            require(name.matches(Regex("$prefix-[0-9]{19}\\.protected"))) { "SYNC_ROOT_INVENTORY" }
            val sequence = requireNotNull(name.removePrefix("$prefix-").removeSuffix(".protected").toLongOrNull())
            require(sequence > 0 && count < Long.MAX_VALUE) { "SYNC_ROOT_INVENTORY" }; count++; maximum = maxOf(maximum, sequence)
        } }
        require(count == maximum) { "SYNC_ROOT_HISTORY_MISSING" }; return count
    }
    private fun requireIndexCount(expected: Long) {
        var count = 0L
        Files.newDirectoryStream(root, "operation-*.protected").use { files -> files.forEach { path ->
            PrivateSyncFile.checked(path)
            require(path.fileName.toString().matches(Regex("operation-[a-f0-9]{32}\\.protected")) && count < Long.MAX_VALUE) { "SYNC_ROOT_INDEX" }; count++
        } }
        require(count == expected) { "SYNC_ROOT_INDEX_MISSING_OR_EXTRA" }
    }
    private fun sameAnchor(a: SyncGroupTrust, b: SyncGroupTrust) = a.group == b.group && a.genesis == b.genesis && a.ownerCertificate == b.ownerCertificate
    private fun intentPath(sequence: Long) = root.resolve("intent-${sequence.toString().padStart(19, '0')}.protected")
    private fun signedPath(sequence: Long) = root.resolve("signed-${sequence.toString().padStart(19, '0')}.protected")
    private fun indexPath(operation: SyncStoreId) = root.resolve("operation-${operation.value}.protected")
    private fun <T> read(path: Path, kind: String, serializer: KSerializer<T>): T? {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        PrivateSyncFile.checked(path); require(Files.size(path) in 1..MAX_PROTECTED.toLong()) { "SYNC_ROOT_WORKING_BUDGET" }
        val encrypted = Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break
                require(output.size() <= MAX_PROTECTED - count) { "SYNC_ROOT_WORKING_BUDGET" }; output.write(buffer, 0, count) }
            output.toByteArray()
        }
        val borrowed = encrypted.copyOf(); val plain = try { unprotect(borrowed) } catch (failure: Throwable) { borrowed.fill(0); throw failure }
        try {
            require(plain.size in 1..MAX_PLAIN) { "SYNC_ROOT_WORKING_BUDGET" }
            val text = lanSyncUtf8(plain); var depth = 0; var quoted = false; var escaped = false
            text.forEach { char ->
                if (quoted) { if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false }
                else when (char) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 16) { "SYNC_ROOT_WORKING_BUDGET" } }; '}', ']' -> depth-- }
                require(depth >= 0) { "SYNC_ROOT_PRIVATE_CONTEXT" }
            }
            require(!quoted && depth == 0) { "SYNC_ROOT_PRIVATE_CONTEXT" }
            val frame = lanSyncJson.decodeFromString(Frame.serializer(), text)
            require(frame.domain == DOMAIN && frame.group == trust.group && frame.actor == actor.fingerprint && frame.kind == kind) { "SYNC_ROOT_PRIVATE_CONTEXT" }
            val value = lanSyncJson.decodeFromJsonElement(serializer, frame.payload)
            if (kind == "ORIGIN") {
                require((value as Origin).incarnation == frame.incarnation) { "SYNC_ROOT_PRIVATE_CONTEXT" }; incarnation = frame.incarnation
            } else require(incarnation == frame.incarnation) { "SYNC_ROOT_PRIVATE_CONTEXT" }
            return value
        } finally { borrowed.fill(0); plain.fill(0) }
    }
    private fun <T> write(path: Path, kind: String, serializer: KSerializer<T>, value: T, replace: Boolean = false) {
        if (Files.exists(path, NOFOLLOW_LINKS)) { PrivateSyncFile.checked(path); require(replace) { "SYNC_ROOT_IMMUTABLE" } }
        val owner = if (kind == "ORIGIN") (value as Origin).incarnation else requireNotNull(incarnation) { "SYNC_ROOT_ORIGIN_MISSING" }
        val plain = lanSyncJson.encodeToString(Frame.serializer(), Frame(DOMAIN, trust.group, actor.fingerprint, owner, kind,
            lanSyncJson.encodeToJsonElement(serializer, value))).toByteArray(Charsets.UTF_8)
        require(plain.size in 1..MAX_PLAIN) { "SYNC_ROOT_WORKING_BUDGET" }
        val encrypted = try { protect(plain).copyOf() } finally { plain.fill(0) }
        require(encrypted.size in 1..MAX_PROTECTED) { "SYNC_ROOT_WORKING_BUDGET" }
        PrivateSyncFile.create(root).use { file ->
            FileChannel.open(file.path, WRITE, NOFOLLOW_LINKS).use { channel -> val bytes = ByteBuffer.wrap(encrypted)
                while (bytes.hasRemaining()) channel.write(bytes); channel.force(true) }
            if (replace) Files.move(file.path, path, ATOMIC_MOVE, REPLACE_EXISTING) else Files.move(file.path, path, ATOMIC_MOVE)
        }
    }
    private fun <T> guarded(work: () -> T): T {
        check(!lock.isHeldByCurrentThread) { "SYNC_ROOT_REENTRY" }; lock.lockInterruptibly()
        try {
            incarnation = null; PrivateSyncFile.checked(root, true); val path = root.resolve("root.lock")
            FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS).use { channel -> PrivateSyncFile.checked(path); channel.lock().use { return work() } }
        } finally { lock.unlock() }
    }
    companion object {
        private const val DOMAIN = "CCB-SYNC-ROOT-JOURNAL/1"
        private const val MAX_PLAIN = 16 * 1024 * 1024
        private const val MAX_PROTECTED = MAX_PLAIN + 1024 * 1024
        private val locks = ConcurrentHashMap<Path, ReentrantLock>()
    }
}
