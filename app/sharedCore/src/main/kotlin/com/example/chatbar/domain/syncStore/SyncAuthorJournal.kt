package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.lanSyncJson
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
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

@Serializable data class SyncAuthorHeadExpectation(val reference: SyncVersionRef?)
@Serializable data class SyncAuthorCommitRequest(val operation: SyncStoreId, val deviceLedger: SyncVersionRef,
    val parents: Set<SyncVersionRef>, val changes: Map<String, SyncUnitChange>, val sourceCheckpoint: String,
    val sourceRawBinding: String, val approvalBinding: String, val expectedAuthorHead: SyncAuthorHeadExpectation? = null) {
    init {
        require(sourceCheckpoint.matches(Regex("[a-f0-9-]{36}")) && sourceRawBinding.matches(Regex("[a-f0-9]{64}")) &&
            approvalBinding.matches(Regex("[a-f0-9]{64}")) && changes.isNotEmpty()) { "SYNC_AUTHOR_REQUEST" }
    }
}
/** Mandatory native owner: approved frozen selection, fresh source CAS, dependency graph and sealed originals. */
fun interface SyncAuthorCommitAdmission { fun requireApproved(request: SyncAuthorCommitRequest) }
@Serializable data class SyncAuthorJournalEntry(val request: SyncAuthorCommitRequest, val version: SyncCausalVersion)
data class SyncAuthorJournalState(val committedSequence: Long, val last: SyncAuthorJournalEntry?, val pendingApproved: SyncAuthorJournalEntry?)

/** Approved intent is forced before signing; an interrupted signer resumes the same body/ID/counter. */
class SyncAuthorJournal(private val root: Path, private val trust: SyncGroupTrust, private val actorCertificate: String,
    private val protect: (ByteArray) -> ByteArray, private val unprotect: (ByteArray) -> ByteArray,
    private val observe: (String) -> Unit = {}) {
    private val actor = SyncDeviceSignatures.decode(actorCertificate)
    private val group get() = trust.group
    private val lock: ReentrantLock
    private var incarnation: SyncStoreId? = null
    @Serializable private data class Origin(val format: Int, val trust: SyncGroupTrust, val certificate: String, val incarnation: SyncStoreId)
    @Serializable private data class Head(val sequence: Long, val operation: SyncStoreId, val reference: SyncVersionRef)
    @Serializable private data class Index(val sequence: Long, val operation: SyncStoreId)
    @Serializable private data class ProtectedFrame(val domain: String, val group: SyncStoreId, val actor: String, val incarnation: SyncStoreId,
        val kind: String, val payload: JsonElement)
    init {
        PrivateSyncFile.checked(root, true)
        require(actor.encoded == actorCertificate) { "SYNC_AUTHOR_CERTIFICATE" }
        require(SyncDeviceSignatures.decode(trust.ownerCertificate).encoded == trust.ownerCertificate) { "SYNC_AUTHOR_TRUST_CHANGED" }
        lock = locks.computeIfAbsent(root) { ReentrantLock() }
    }

    /** Explicit first enrollment only. Runtime/recovery must use state(), never recreate a missing origin. */
    fun initializeNewEnrollment() = guarded {
        require(!Files.exists(root.resolve("origin.protected"), NOFOLLOW_LINKS)) { "SYNC_AUTHOR_ALREADY_ENROLLED" }
        require(Files.newDirectoryStream(root).use { files -> files.all { it.fileName.toString() == "author.lock" } }) { "SYNC_AUTHOR_EXISTING_EVIDENCE" }
        write(root.resolve("origin.protected"), "ORIGIN", Origin.serializer(), Origin(1, trust, actorCertificate, SyncStoreId.random()))
        observe("AUTHOR_ORIGIN_RETAINED")
    }
    fun state(): SyncAuthorJournalState = guarded { recover() }
    fun entry(operation: SyncStoreId): SyncAuthorJournalEntry? = guarded {
        val state = recover()
        val index = read(indexPath(operation), "INDEX", Index.serializer()) ?: return@guarded null
        require(index.operation == operation) { "SYNC_AUTHOR_INDEX" }
        val entry = read(signedPath(index.sequence), "SIGNED", SyncAuthorJournalEntry.serializer())
        require(entry != null || index.sequence > state.committedSequence) { "SYNC_AUTHOR_SIGNED_ENTRY_MISSING" }
        entry?.also { validateSigned(it, requireIntent(index.sequence)) }
    }

    fun commitApproved(request: SyncAuthorCommitRequest, ledger: SyncDeviceLedger, admission: SyncAuthorCommitAdmission,
        sign: (ByteArray) -> ByteArray): SyncAuthorJournalEntry = commitApprovedLive(request, { ledger }, admission, sign)

    fun commitApprovedLive(request: SyncAuthorCommitRequest, ledger: () -> SyncDeviceLedger, admission: SyncAuthorCommitAdmission,
        sign: (ByteArray) -> ByteArray): SyncAuthorJournalEntry = guarded {
        val input = freeze(request); val state = recover()
        requireActive(ledger())
        val index = read(indexPath(input.operation), "INDEX", Index.serializer())
        if (index != null) {
            require(index.operation == input.operation) { "SYNC_AUTHOR_INDEX" }
            val intent = requireIntent(index.sequence)
            require(intent.request == input) { "SYNC_AUTHOR_OPERATION_CHANGED" }
            read(signedPath(index.sequence), "SIGNED", SyncAuthorJournalEntry.serializer())?.let { validateSigned(it, intent); return@guarded it }
            return@guarded signAndRetain(intent, ledger, sign)
        }
        require(state.pendingApproved == null) { "SYNC_AUTHOR_PENDING_APPROVED_COMMIT" }
        require(input.expectedAuthorHead == null || input.expectedAuthorHead.reference == state.last?.version?.reference()) { "SYNC_AUTHOR_HEAD_CHANGED" }
        require(ledger().current().reference() == input.deviceLedger) { "SYNC_AUTHOR_LEDGER_CHANGED" }
        admission.requireApproved(input)
        requireActive(ledger())
        require(ledger().current().reference() == input.deviceLedger) { "SYNC_AUTHOR_LEDGER_CHANGED" }
        require(state.committedSequence < Long.MAX_VALUE) { "SYNC_AUTHOR_SEQUENCE_EXHAUSTED" }
        val previous = state.last?.version?.reference()
        val version = SyncCausalVersion(group, SyncStoreId.random(), input.deviceLedger, actor.fingerprint, state.committedSequence + 1,
            previous, Collections.unmodifiableSet(input.parents + listOfNotNull(previous)), input.changes, "unsigned")
        val intent = SyncAuthorJournalEntry(input, version)
        write(intentPath(version.authorSequence), "INTENT", SyncAuthorJournalEntry.serializer(), intent)
        observe("APPROVED_AUTHOR_INTENT_RETAINED")
        ensureIndex(intent)
        signAndRetain(intent, ledger, sign)
    }

    private fun signAndRetain(intent: SyncAuthorJournalEntry, ledger: () -> SyncDeviceLedger, sign: (ByteArray) -> ByteArray): SyncAuthorJournalEntry {
        requireActive(ledger())
        require(ledger().record(intent.version.deviceLedger)?.members?.get(actor.fingerprint)?.active == true) { "SYNC_AUTHOR_LEDGER_MISSING" }
        val version = intent.version.copy(signature = Base64.getEncoder().encodeToString(sign(intent.version.signingBytes())))
        requireActive(ledger())
        val entry = intent.copy(version = version); validateSigned(entry, intent)
        write(signedPath(version.authorSequence), "SIGNED", SyncAuthorJournalEntry.serializer(), entry)
        observe("SIGNED_AUTHOR_ENTRY_RETAINED")
        advance(entry)
        return entry
    }
    private fun requireActive(ledger: SyncDeviceLedger) {
        require(sameAnchor(ledger.trustAnchor, trust)) { "SYNC_AUTHOR_TRUST_CHANGED" }
        require(ledger.contains(trust.checkpoint)) { "SYNC_GROUP_KNOWN_CHECKPOINT_MISSING" }
        val current = ledger.current()
        require(current.group == group && current.members[actor.fingerprint]?.let { it.active && it.certificate == actorCertificate } == true) { "SYNC_AUTHOR_REVOKED_OR_CHANGED" }
    }
    private fun recover(): SyncAuthorJournalState {
        val origin = requireNotNull(read(root.resolve("origin.protected"), "ORIGIN", Origin.serializer())) { "SYNC_AUTHOR_ORIGIN_MISSING" }
        require(origin.format == 1 && sameAnchor(origin.trust, trust) && origin.certificate == actorCertificate) { "SYNC_AUTHOR_ORIGIN_CHANGED" }
        var head = read(root.resolve("head.protected"), "HEAD", Head.serializer())
        require(head == null || head.sequence > 0) { "SYNC_AUTHOR_HEAD" }
        var last = head?.let {
            val intent = requireIntent(it.sequence)
            requireNotNull(read(signedPath(it.sequence), "SIGNED", SyncAuthorJournalEntry.serializer())).also { entry ->
                validateSigned(entry, intent)
                require(entry.request.operation == it.operation && entry.version.reference() == it.reference) { "SYNC_AUTHOR_HEAD" }
            }
        }
        // No approved/signed sequence may vanish behind a rolled-back mutable head.
        val intents = inventory("intent", "SYNC_AUTHOR_PARENT_MISSING")
        require((head?.sequence ?: 0) <= intents) { "SYNC_AUTHOR_PARENT_MISSING" }
        val signed = inventory("signed", "SYNC_AUTHOR_SIGNED_ENTRY_MISSING")
        require((head?.sequence ?: 0) <= signed && signed <= intents) { "SYNC_AUTHOR_SIGNED_ENTRY_MISSING" }
        while (true) {
            if (head?.sequence == Long.MAX_VALUE) break
            val sequence = (head?.sequence ?: 0) + 1
            if (sequence > intents) break
            val intent = requireIntent(sequence)
            require(intent.version.previousByAuthor == last?.version?.reference()) { "SYNC_AUTHOR_PARENT_CHANGED" }
            ensureIndex(intent)
            val signed = read(signedPath(sequence), "SIGNED", SyncAuthorJournalEntry.serializer())
            if (signed == null) {
                require(sequence == intents) { "SYNC_AUTHOR_PENDING_APPROVED_COMMIT" }
                requireIndexes(intents)
                return SyncAuthorJournalState(head?.sequence ?: 0, last, intent)
            }
            validateSigned(signed, intent); advance(signed); last = signed
            head = Head(sequence, signed.request.operation, signed.version.reference())
        }
        requireIndexes(intents)
        return SyncAuthorJournalState(head?.sequence ?: 0, last, null)
    }
    /** Fixed-width filenames are unique, so positive count == maximum proves a dense sequence without a list. */
    private fun inventory(prefix: String, missingCode: String): Long {
        var count = 0L; var maximum = 0L
        Files.newDirectoryStream(root, "$prefix-*.protected").use { files -> files.forEach { path ->
            PrivateSyncFile.checked(path)
            val name = path.fileName.toString(); require(name.matches(Regex("$prefix-[0-9]{19}\\.protected"))) { "SYNC_AUTHOR_INVENTORY" }
            val sequence = requireNotNull(name.removePrefix("$prefix-").removeSuffix(".protected").toLongOrNull()) { "SYNC_AUTHOR_INVENTORY" }
            require(sequence > 0 && count < Long.MAX_VALUE) { "SYNC_AUTHOR_INVENTORY" }; count++; maximum = maxOf(maximum, sequence)
        } }
        require(count == maximum) { missingCode }; return count
    }
    private fun requireIndexes(expected: Long) {
        var count = 0L
        Files.newDirectoryStream(root, "operation-*.protected").use { files -> files.forEach { path ->
            PrivateSyncFile.checked(path)
            require(path.fileName.toString().matches(Regex("operation-[a-f0-9]{32}\\.protected")) && count < Long.MAX_VALUE) { "SYNC_AUTHOR_INDEX" }; count++
        } }
        require(count == expected) { "SYNC_AUTHOR_INDEX_MISSING_OR_EXTRA" }
    }
    private fun requireIntent(sequence: Long): SyncAuthorJournalEntry = requireNotNull(read(intentPath(sequence), "INTENT", SyncAuthorJournalEntry.serializer())) {
        "SYNC_AUTHOR_INTENT_MISSING"
    }.also {
        require(it.version.group == group && it.version.author == actor.fingerprint && it.version.authorSequence == sequence &&
            it.version.signature == "unsigned" && it.version.deviceLedger == it.request.deviceLedger && it.version.changes == it.request.changes &&
            it.version.parents == it.request.parents + listOfNotNull(it.version.previousByAuthor)) { "SYNC_AUTHOR_INTENT_CHANGED" }
    }
    private fun validateSigned(entry: SyncAuthorJournalEntry, intent: SyncAuthorJournalEntry) {
        require(entry.request == intent.request && entry.version.copy(signature = "unsigned") == intent.version &&
            actor.verifies(entry.version.signingBytes(), entry.version.signature)) { "SYNC_AUTHOR_SIGNATURE_OR_BODY_CHANGED" }
    }
    private fun ensureIndex(intent: SyncAuthorJournalEntry) {
        val expected = Index(intent.version.authorSequence, intent.request.operation); val path = indexPath(intent.request.operation)
        val existing = read(path, "INDEX", Index.serializer())
        if (existing == null) write(path, "INDEX", Index.serializer(), expected)
        else require(existing == expected) { "SYNC_AUTHOR_OPERATION_CHANGED" }
    }
    private fun advance(entry: SyncAuthorJournalEntry) {
        write(root.resolve("head.protected"), "HEAD", Head.serializer(), Head(entry.version.authorSequence, entry.request.operation, entry.version.reference()), true)
        observe("AUTHOR_HEAD_RETAINED")
    }
    private fun freeze(request: SyncAuthorCommitRequest) = request.copy(parents = Collections.unmodifiableSet(request.parents.toSet()),
        changes = Collections.unmodifiableMap(request.changes.mapValues { it.value.copy(previous = Collections.unmodifiableSet(it.value.previous.toSet())) }))
    private fun sameAnchor(a: SyncGroupTrust, b: SyncGroupTrust) = a.group == b.group && a.genesis == b.genesis && a.ownerCertificate == b.ownerCertificate
    private fun intentPath(sequence: Long) = root.resolve("intent-${sequence.toString().padStart(19, '0')}.protected")
    private fun signedPath(sequence: Long) = root.resolve("signed-${sequence.toString().padStart(19, '0')}.protected")
    private fun indexPath(operation: SyncStoreId) = root.resolve("operation-${operation.value}.protected")
    private fun <T> read(path: Path, kind: String, serializer: KSerializer<T>): T? {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        PrivateSyncFile.checked(path); require(Files.size(path) in 1..MAX_PROTECTED_BYTES.toLong()) { "SYNC_AUTHOR_WORKING_BUDGET" }
        val protected = Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break
                require(output.size() <= MAX_PROTECTED_BYTES - count) { "SYNC_AUTHOR_WORKING_BUDGET" }; output.write(buffer, 0, count) }
            output.toByteArray()
        }
        val borrowed = protected.copyOf(); val plain = try { unprotect(borrowed) } catch (failure: Throwable) { borrowed.fill(0); throw failure }
        try {
            require(plain.size in 1..MAX_PLAIN_BYTES) { "SYNC_AUTHOR_WORKING_BUDGET" }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plain)).toString()
            var depth = 0; var quoted = false; var escaped = false
            text.forEach { char ->
                if (quoted) { if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false }
                else when (char) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 16) { "SYNC_AUTHOR_WORKING_BUDGET" } }; '}', ']' -> depth-- }
                require(depth >= 0) { "SYNC_AUTHOR_PRIVATE_CONTEXT" }
            }
            require(!quoted && depth == 0) { "SYNC_AUTHOR_PRIVATE_CONTEXT" }
            val frame = lanSyncJson.decodeFromString(ProtectedFrame.serializer(), text)
            require(frame.domain == DOMAIN && frame.group == group && frame.actor == actor.fingerprint && frame.kind == kind) { "SYNC_AUTHOR_PRIVATE_CONTEXT" }
            val value = lanSyncJson.decodeFromJsonElement(serializer, frame.payload)
            if (kind == "ORIGIN") {
                require((value as Origin).incarnation == frame.incarnation) { "SYNC_AUTHOR_PRIVATE_CONTEXT" }
                incarnation = frame.incarnation
            } else require(incarnation == frame.incarnation) { "SYNC_AUTHOR_PRIVATE_CONTEXT" }
            return value
        } finally { borrowed.fill(0); plain.fill(0) }
    }
    private fun <T> write(path: Path, kind: String, serializer: KSerializer<T>, value: T, replace: Boolean = false) {
        if (Files.exists(path, NOFOLLOW_LINKS)) { PrivateSyncFile.checked(path); require(replace) { "SYNC_AUTHOR_IMMUTABLE" } }
        val owner = if (kind == "ORIGIN") (value as Origin).incarnation else requireNotNull(incarnation) { "SYNC_AUTHOR_ORIGIN_MISSING" }
        val plain = lanSyncJson.encodeToString(ProtectedFrame.serializer(), ProtectedFrame(DOMAIN, group, actor.fingerprint, owner, kind,
            lanSyncJson.encodeToJsonElement(serializer, value))).toByteArray(Charsets.UTF_8)
        require(plain.size in 1..MAX_PLAIN_BYTES) { "SYNC_AUTHOR_WORKING_BUDGET" }
        val protected = try { protect(plain).copyOf() } finally { plain.fill(0) }
        require(protected.size in 1..MAX_PROTECTED_BYTES) { "SYNC_AUTHOR_WORKING_BUDGET" }
        PrivateSyncFile.create(root).use { file ->
            FileChannel.open(file.path, WRITE, NOFOLLOW_LINKS).use { channel -> val bytes = ByteBuffer.wrap(protected)
                while (bytes.hasRemaining()) channel.write(bytes); channel.force(true) }
            if (replace) Files.move(file.path, path, ATOMIC_MOVE, REPLACE_EXISTING) else Files.move(file.path, path, ATOMIC_MOVE)
        }
    }
    private fun <T> guarded(work: () -> T): T {
        check(!lock.isHeldByCurrentThread) { "SYNC_AUTHOR_REENTRY" }; lock.lockInterruptibly()
        try {
            incarnation = null
            PrivateSyncFile.checked(root, true); val path = root.resolve("author.lock")
            FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS).use { channel -> PrivateSyncFile.checked(path); channel.lock().use { return work() } }
        } finally { lock.unlock() }
    }
    companion object {
        private const val DOMAIN = "CCB-SYNC-AUTHOR-JOURNAL/1"
        private const val MAX_PLAIN_BYTES = 1024 * 1024
        private const val MAX_PROTECTED_BYTES = MAX_PLAIN_BYTES + 1024 * 1024
        private val locks = ConcurrentHashMap<Path, ReentrantLock>()
    }
}
