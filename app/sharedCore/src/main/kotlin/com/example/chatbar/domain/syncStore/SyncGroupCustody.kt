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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlinx.serialization.Serializable

@Serializable private data class SyncGroupCustodyState(val format: Int, val device: String, val recipientKey: String,
    val trust: SyncGroupTrust, val ledgers: List<SyncDeviceLedgerRecord>, val keys: Map<String, String>)

/**
 * Device-local protected trust/checkpoint and key custody. Enrollment must supply independently
 * admitted trust; opaque backend discovery can never create it. This is not the native data journal.
 */
class SyncGroupCustody(private val root: Path, private val group: SyncStoreId,
    private val recipient: SyncDeviceEncryptionKey, private val protect: (ByteArray) -> ByteArray,
    private val unprotect: (ByteArray) -> ByteArray, private val maximumStateBytes: Int = 16 * 1024 * 1024,
    private val observe: (String) -> Unit = {}) {
    private val lock: ReentrantLock
    private val file get() = root.resolve("group.protected")
    init {
        PrivateSyncFile.checked(root, true)
        require(maximumStateBytes >= 4096) { "SYNC_GROUP_CUSTODY_BUDGET" }
        lock = locks.computeIfAbsent(root) { ReentrantLock() }
    }

    /** Explicit native enrollment only. Repeated enrollment cannot replace an existing trust anchor. */
    fun enroll(trust: SyncGroupTrust, records: Collection<SyncDeviceLedgerRecord>): SyncDeviceLedger = guarded {
        require(trust.group == group) { "SYNC_GROUP_IDENTITY" }
        if (Files.exists(file, NOFOLLOW_LINKS)) {
            val existing = read(); require(sameAnchor(existing.trust, trust)) { "SYNC_GROUP_CUSTODY_TRUST_CHANGED" }
            val ledger = build(existing)
            require(ledger.contains(trust.checkpoint)) { "SYNC_GROUP_KNOWN_CHECKPOINT_MISSING" }
            advance(existing, ledger); return@guarded build(read()).also { it.current() }
        }
        val rows = records.map(::freeze)
        val state = SyncGroupCustodyState(1, recipient.device, recipient.publicKey.encoded, trust, rows, emptyMap())
        val ledger = build(state); val current = ledger.current()
        require(current.members[recipient.device]?.let { it.active && it.recipientKey == recipient.publicKey.encoded } == true) { "SYNC_GROUP_CUSTODY_RECIPIENT" }
        write(state.copy(trust = trust.copy(checkpoint = current.reference())))
        observe("GROUP_TRUST_RETAINED")
        build(read()).also { it.current() }
    }

    fun ledger(): SyncDeviceLedger = guarded {
        val state = read(); val ledger = build(state); advance(state, ledger)
        build(read()).also { it.current() }
    }
    fun trust(): SyncGroupTrust = guarded { read().trust }

    /** Signed waiting/rejected/fork evidence persists before trying to select a new current head. */
    fun update(records: Collection<SyncDeviceLedgerRecord>): SyncDeviceLedger = guarded {
        val state = read(); val ledger = build(state)
        val rows = state.ledgers.associateByTo(linkedMapOf()) { it.id }
        var changed = false
        records.map(::freeze).forEach { row ->
            ledger.offer(row)
            rows[row.id]?.let { require(it.reference() == row.reference()) { "SYNC_GROUP_LEDGER_ID_COLLISION" } }
            if (rows.putIfAbsent(row.id, row) == null) changed = true
        }
        if (!changed) { advance(state, ledger); return@guarded build(read()).also { it.current() } }
        val retained = state.copy(ledgers = rows.values.sortedWith(compareBy<SyncDeviceLedgerRecord> { it.sequence }.thenBy { it.id.value }))
        write(retained)
        observe("GROUP_LEDGER_EVIDENCE_RETAINED")
        advance(retained, ledger)
        build(read()).also { it.current() }
    }

    /** Explicit creator enrollment: signed ancestry, generated keys and checkpoint publish in one protected state. */
    fun enrollWithKeys(trust: SyncGroupTrust, records: Collection<SyncDeviceLedgerRecord>, keys: Collection<SyncAdmittedGroupKey>): SyncDeviceLedger = guarded {
        require(trust.group == group) { "SYNC_GROUP_IDENTITY" }
        val state = if (Files.exists(file, NOFOLLOW_LINKS)) read().also {
            require(sameAnchor(it.trust, trust)) { "SYNC_GROUP_CUSTODY_TRUST_CHANGED" }
        } else SyncGroupCustodyState(1, recipient.device, recipient.publicKey.encoded, trust, emptyList(), emptyMap())
        updateWithKeysOwned(state, records, keys, trust.checkpoint)
    }

    /** Root recovery never exposes a newer epoch checkpoint without its verified local key. */
    fun updateWithKeys(records: Collection<SyncDeviceLedgerRecord>, keys: Collection<SyncAdmittedGroupKey>): SyncDeviceLedger = guarded {
        updateWithKeysOwned(read(), records, keys)
    }
    private fun updateWithKeysOwned(state: SyncGroupCustodyState, records: Collection<SyncDeviceLedgerRecord>,
        admittedKeys: Collection<SyncAdmittedGroupKey>, requiredCheckpoint: SyncVersionRef? = null): SyncDeviceLedger {
        val ledger = build(state); val rows = state.ledgers.associateByTo(linkedMapOf()) { it.id }
        records.map(::freeze).forEach { row ->
            ledger.offer(row)
            rows[row.id]?.let { require(it.reference() == row.reference()) { "SYNC_GROUP_LEDGER_ID_COLLISION" } }
            rows.putIfAbsent(row.id, row)
        }
        val retained = state.copy(ledgers = rows.values.sortedWith(compareBy<SyncDeviceLedgerRecord> { it.sequence }.thenBy { it.id.value }))
        val current = try { ledger.current() } catch (failure: IllegalArgumentException) {
            // Preserve independently valid signed fork evidence without advancing the checkpoint or keys.
            if (failure.message == "SYNC_GROUP_LEDGER_FORK_OR_MISSING" && state.ledgers.isNotEmpty() && retained != state) {
                write(retained); observe("GROUP_LEDGER_EVIDENCE_RETAINED")
            }
            throw failure
        }
        require(requiredCheckpoint == null || ledger.contains(requiredCheckpoint)) { "SYNC_GROUP_KNOWN_CHECKPOINT_MISSING" }
        require(current.members[recipient.device]?.let { it.active && it.recipientKey == recipient.publicKey.encoded } == true) { "SYNC_GROUP_CUSTODY_RECIPIENT" }
        val keys = state.keys.toMutableMap()
        admittedKeys.forEach { admitted ->
            val cipher = admitted.cipher
            require(admitted.recipient == recipient.device && cipher.group == group && ledger.contains(admitted.authorizingLedger) &&
                ledger.hasKey(cipher.epoch, cipher.keyCommitment(), admitted.authorizingLedger)) { "SYNC_GROUP_CUSTODY_KEY" }
            val previous = keys[cipher.epoch.value]
            if (previous != null) {
                require(openEnvelope(cipher.epoch, previous).keyCommitment() == cipher.keyCommitment()) { "SYNC_GROUP_CUSTODY_KEY_CHANGED" }
            } else {
                val protected = cipher.protectedKeyset(protect)
                require(SyncStreamingCipher.open(group, cipher.epoch, protected, unprotect).keyCommitment() == cipher.keyCommitment()) { "SYNC_GROUP_CUSTODY_KEY_CHANGED" }
                keys[cipher.epoch.value] = Base64.getEncoder().encodeToString(protected)
            }
        }
        val currentKey = requireNotNull(keys[current.epoch.value]) { "SYNC_GROUP_CUSTODY_KEY_MISSING" }
        require(openEnvelope(current.epoch, currentKey).keyCommitment() == current.keyCommitment) { "SYNC_GROUP_CUSTODY_KEY_CHANGED" }
        val complete = retained.copy(trust = state.trust.copy(checkpoint = current.reference()), keys = keys.toMap())
        if (complete != state) {
            // All protection/round-trip checks finish before this single atomic replacement.
            write(complete)
            if (retained.ledgers != state.ledgers) observe("GROUP_LEDGER_EVIDENCE_RETAINED")
            if (complete.trust.checkpoint != state.trust.checkpoint) observe("GROUP_CHECKPOINT_RETAINED")
            if (complete.keys != state.keys) observe("GROUP_KEY_RETAINED")
        }
        return build(read()).also { it.current() }
    }

    fun rememberKey(admitted: SyncAdmittedGroupKey) = guarded {
        val initial = read(); val ledger = build(initial); val state = advance(initial, ledger)
        val current = ledger.current(); val cipher = admitted.cipher
        require(admitted.recipient == recipient.device && cipher.group == group && ledger.contains(admitted.authorizingLedger) &&
            ledger.hasKey(cipher.epoch, cipher.keyCommitment(), admitted.authorizingLedger)) { "SYNC_GROUP_CUSTODY_KEY" }
        require(current.members[recipient.device]?.let { it.active && it.recipientKey == recipient.publicKey.encoded } == true) { "SYNC_GROUP_CUSTODY_RECIPIENT" }
        val previous = state.keys[cipher.epoch.value]
        if (previous != null) {
            require(openKey(state, cipher.epoch).keyCommitment() == cipher.keyCommitment()) { "SYNC_GROUP_CUSTODY_KEY_CHANGED" }
            return@guarded
        }
        val protected = cipher.protectedKeyset(protect)
        require(SyncStreamingCipher.open(group, cipher.epoch, protected, unprotect).keyCommitment() == cipher.keyCommitment()) { "SYNC_GROUP_CUSTODY_KEY_CHANGED" }
        write(state.copy(keys = state.keys + (cipher.epoch.value to Base64.getEncoder().encodeToString(protected))))
        observe("GROUP_KEY_RETAINED")
    }

    /** Retained old keys remain readable after revocation for local evidence; this grants no upload authority. */
    fun key(epoch: SyncStoreId): SyncStreamingCipher = guarded { openKey(read(), epoch) }

    private fun openKey(state: SyncGroupCustodyState, epoch: SyncStoreId): SyncStreamingCipher {
        val encoded = requireNotNull(state.keys[epoch.value]) { "SYNC_GROUP_CUSTODY_KEY_MISSING" }
        val cipher = openEnvelope(epoch, encoded)
        require(build(state).hasKey(epoch, cipher.keyCommitment(), state.trust.checkpoint)) { "SYNC_GROUP_CUSTODY_KEY_CHANGED" }
        return cipher
    }
    private fun openEnvelope(epoch: SyncStoreId, encoded: String): SyncStreamingCipher {
        require(encoded.length in 1..128 * 1024) { "SYNC_GROUP_CUSTODY_BUDGET" }
        return SyncStreamingCipher.open(group, epoch, Base64.getDecoder().decode(encoded), unprotect)
    }
    private fun build(state: SyncGroupCustodyState) = SyncDeviceLedger(state.trust).also { ledger -> state.ledgers.forEach { ledger.offer(it) } }
    private fun advance(state: SyncGroupCustodyState, ledger: SyncDeviceLedger): SyncGroupCustodyState {
        val current = ledger.current()
        if (state.trust.checkpoint == current.reference()) return state
        val advanced = state.copy(trust = state.trust.copy(checkpoint = current.reference()))
        write(advanced); observe("GROUP_CHECKPOINT_RETAINED"); return advanced
    }
    private fun freeze(row: SyncDeviceLedgerRecord) = row.copy(members = row.members.toMap(), coveredTypes = row.coveredTypes.toSet(), acceptedHeads = row.acceptedHeads.toSet())
    private fun sameAnchor(a: SyncGroupTrust, b: SyncGroupTrust) = a.group == b.group && a.genesis == b.genesis && a.ownerCertificate == b.ownerCertificate
    private fun read(): SyncGroupCustodyState {
        require(Files.exists(file, NOFOLLOW_LINKS)) { "SYNC_GROUP_CUSTODY_MISSING" }
        PrivateSyncFile.checked(file)
        require(Files.size(file) in 1..maximumStateBytes.toLong() + 1024 * 1024) { "SYNC_GROUP_CUSTODY_BUDGET" }
        val protected = Files.newInputStream(file, NOFOLLOW_LINKS).use { input ->
            val bytes = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(bytes.size().toLong() + count <= maximumStateBytes.toLong() + 1024 * 1024) { "SYNC_GROUP_CUSTODY_BUDGET" }
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        }
        val borrowed = protected.copyOf()
        val plain = try { unprotect(borrowed) } catch (failure: Throwable) { borrowed.fill(0); throw failure }
        try {
            require(plain.size in 1..maximumStateBytes) { "SYNC_GROUP_CUSTODY_BUDGET" }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plain)).toString()
            var depth = 0; var quoted = false; var escaped = false
            text.forEach { char ->
                if (quoted) { if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false }
                else when (char) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 16) { "SYNC_GROUP_CUSTODY_DEPTH" } }; '}', ']' -> depth-- }
                require(depth >= 0) { "SYNC_GROUP_CUSTODY_DEPTH" }
            }
            require(!quoted && depth == 0) { "SYNC_GROUP_CUSTODY_FORMAT" }
            val state = lanSyncJson.decodeFromString(SyncGroupCustodyState.serializer(), text)
            require(state.format == 1 && state.device == recipient.device && state.recipientKey == recipient.publicKey.encoded && state.trust.group == group &&
                state.ledgers.map { it.id }.toSet().size == state.ledgers.size && state.keys.keys.all { it.matches(Regex("[a-f0-9]{32}")) }) { "SYNC_GROUP_CUSTODY_CONTEXT" }
            return state
        } finally { borrowed.fill(0); plain.fill(0) }
    }
    private fun write(state: SyncGroupCustodyState) {
        val plain = lanSyncJson.encodeToString(SyncGroupCustodyState.serializer(), state).toByteArray(Charsets.UTF_8)
        require(plain.size in 1..maximumStateBytes) { "SYNC_GROUP_CUSTODY_BUDGET" }
        val protected = try { protect(plain).copyOf() } finally { plain.fill(0) }
        require(protected.size.toLong() in 1..maximumStateBytes.toLong() + 1024 * 1024) { "SYNC_GROUP_CUSTODY_BUDGET" }
        PrivateSyncFile.create(root).use { preparation ->
            FileChannel.open(preparation.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                val bytes = ByteBuffer.wrap(protected); while (bytes.hasRemaining()) channel.write(bytes); channel.force(true)
            }
            if (Files.exists(file, NOFOLLOW_LINKS)) PrivateSyncFile.checked(file)
            Files.move(preparation.path, file, ATOMIC_MOVE, REPLACE_EXISTING)
        }
    }
    private fun <T> guarded(work: () -> T): T {
        check(!lock.isHeldByCurrentThread) { "SYNC_GROUP_CUSTODY_REENTRY" }; lock.lockInterruptibly()
        try {
            PrivateSyncFile.checked(root, true)
            val lockPath = root.resolve("group.lock")
            FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                PrivateSyncFile.checked(lockPath); channel.lock().use { return work() }
            }
        } finally { lock.unlock() }
    }
    companion object { private val locks = ConcurrentHashMap<Path, ReentrantLock>() }
}
