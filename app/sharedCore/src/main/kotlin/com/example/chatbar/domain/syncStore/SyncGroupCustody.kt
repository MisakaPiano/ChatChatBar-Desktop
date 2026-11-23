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
    private data class VerifiedGraph(val trust: SyncGroupTrust, val rows: List<SyncDeviceLedgerRecord>, val ledger: SyncDeviceLedger)
    private var verifiedGraph: VerifiedGraph? = null
    val device: String get() = recipient.device
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
        val state = read(); val ledger = build(state)
        // The locked protected read is already current; no write needs a second readback.
        if (state.trust.checkpoint == ledger.current().reference()) return@guarded ledger
        advance(state, ledger)
        build(read()).also { it.current() }
    }
    fun trust(): SyncGroupTrust = guarded { read().trust }

    /** Read preserved conflict evidence without advancing the protected checkpoint. */
    internal fun verifiedFork(): SyncVerifiedLedgerFork? = guarded { build(read()).verifiedFork() }

    internal fun recoveryGraph(): SyncDeviceLedger = guarded { build(read()) }

    internal fun inspectRecoveryFile(bytes: ByteArray): SyncVerifiedLedgerRecoveryFile = guarded {
        val trust = read().trust
        // A same-root package can provide independently signed fork evidence even when it does not cover our pin.
        SyncLedgerRecoveryFileCodec.read(bytes.inputStream(), trust.copy(checkpoint = trust.genesis), recipient, allowRevokedNotice = true)
    }
    internal fun inspectRetainedRecoveryFile(bytes: ByteArray): SyncVerifiedLedgerRecoveryFile = guarded {
        val trust = read().trust
        SyncLedgerRecoveryFileCodec.read(bytes.inputStream(), trust.copy(checkpoint = trust.genesis), recipient, allowRevokedNotice = true)
    }

    /** The confirmation is compared to current retained evidence under the same custody lock as installation. */
    internal fun commitMembershipRecovery(expected: Set<SyncVersionRef>, authoring: Boolean,
        prepare: () -> ByteArray, retain: (ByteArray, SyncVerifiedLedgerRecoveryFile) -> Unit): Boolean = guarded {
        val state = read()
        val graph = build(state)
        require(graph.admittedTips() == expected) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_REVIEW_CHANGED" }
        val bytes = prepare()
        val proofTrust = if (authoring) state.trust else state.trust.copy(checkpoint = state.trust.genesis)
        val verified = SyncLedgerRecoveryFileCodec.read(bytes.inputStream(), proofTrust, recipient, allowRevokedNotice = !authoring)
        val recovery = verified.recovery
        if (authoring) require(recovery.recovery?.parents == expected) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_REVIEW_CHANGED" }
        val merged = graph.verifiedCopy()
        verified.records.forEach(merged::offer)
        require(merged.contains(recovery.reference())) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_SIGNATURE" }
        val tips = merged.admittedTips()
        if (tips.size == 1) {
            require(merged.isAncestorOrSelf(state.trust.checkpoint, tips.single())) { "SYNC_GROUP_KNOWN_CHECKPOINT_MISSING" }
        }
        if (tips.size == 1 && tips.single() != recovery.reference()) {
            require(merged.ancestry().any { it.reference() == recovery.reference() }) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_REVIEW_CHANGED" }
            return@guarded true // An already installed checkpoint can be replayed without moving the pin.
        }
        retain(bytes, verified)
        if (tips.size != 1 || verified.recipientRevoked) {
            val rows = (state.ledgers + verified.records).distinctBy { it.id }
                .sortedWith(compareBy<SyncDeviceLedgerRecord> { it.sequence }.thenBy { it.id.value }).map(::freeze)
            val checkpoint = if (tips.size == 1) recovery.reference() else state.trust.checkpoint
            val preserved = state.copy(ledgers = rows, trust = state.trust.copy(checkpoint = checkpoint))
            if (preserved != state) {
                write(preserved)
                observe("GROUP_LEDGER_EVIDENCE_RETAINED")
                if (checkpoint != state.trust.checkpoint) observe("GROUP_CHECKPOINT_RETAINED")
            }
            return@guarded tips.size == 1
        }
        updateWithKeysOwned(state, verified.records, verified.keys, recovery.reference())
        true
    }

    /** Signed waiting/rejected/fork evidence persists before trying to select a new current head. */
    fun update(records: Collection<SyncDeviceLedgerRecord>): SyncDeviceLedger = guarded {
        val state = read(); val ledger = build(state)
        val retainedRecovery = state.ledgers.asSequence().filter { it.recovery != null }.map { it.reference() }.toSet()
        val admissible = records.filter { it.recovery == null || it.reference() in retainedRecovery }
        val rows = state.ledgers.associateByTo(linkedMapOf()) { it.id }
        var changed = false
        admissible.map(::freeze).forEach { row ->
            ledger.offer(row)
            rows[row.id]?.let { require(it.reference() == row.reference()) { "SYNC_GROUP_LEDGER_ID_COLLISION" } }
            if (rows.putIfAbsent(row.id, row) == null) changed = true
        }
        if (!changed) {
            require(admissible.size == records.size) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_EXPLICIT_REQUIRED" }
            advance(state, ledger)
            return@guarded build(read()).also { it.current() }
        }
        val retained = state.copy(ledgers = rows.values.sortedWith(compareBy<SyncDeviceLedgerRecord> { it.sequence }.thenBy { it.id.value }))
        write(retained)
        observe("GROUP_LEDGER_EVIDENCE_RETAINED")
        require(admissible.size == records.size) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_EXPLICIT_REQUIRED" }
        advance(retained, ledger)
        build(read()).also { it.current() }
    }

    /** A provisional LAN peer can only extend the exact retained checkpoint with owner-signed membership. */
    internal fun extendFromPeer(expected: SyncVersionRef, envelope: SyncGroupEnvelope,
        localCertificate: String, peerCertificate: String): Boolean = guarded {
        require(envelope.group == group && envelope.kind == SyncGroupObjectKind.DEVICE_LEDGER) {
            "SYNC_GROUP_LEDGER_RELAY_CONTEXT"
        }
        val state = read()
        val ledger = build(state)
        val before = ledger.current()
        val peer = SyncDeviceSignatures.decode(peerCertificate)
        if (before.reference() != expected || peer.fingerprint in before.members) return@guarded false
        val local = before.members[recipient.device]
        require(local?.let { it.active && it.certificate == localCertificate &&
            it.recipientKey == recipient.publicKey.encoded } == true) { "SYNC_GROUP_LEDGER_RELAY_LOCAL" }

        val tip = requireNotNull(envelope.deviceLedger)
        val suffix = envelope.ledgerHistory + tip
        var parent = expected
        for (row in suffix) {
            require(row.parent == parent && ledger.offer(row) == SyncVersionState.ADMITTED) {
                "SYNC_GROUP_LEDGER_RELAY_ANCESTRY"
            }
            parent = row.reference()
        }
        val latest = ledger.current()
        require(latest.reference() == tip.reference()) { "SYNC_GROUP_LEDGER_RELAY_TIP" }
        require(latest.members[recipient.device] == local) { "SYNC_GROUP_LEDGER_RELAY_LOCAL" }
        require(latest.members[peer.fingerprint]?.let { it.active && it.certificate == peer.encoded } == true) {
            "SYNC_GROUP_LEDGER_RELAY_PEER"
        }

        val retained = state.ledgers.associateByTo(linkedMapOf()) { it.id }
        suffix.forEach { retained.putIfAbsent(it.id, freeze(it)) }
        write(state.copy(trust = state.trust.copy(checkpoint = latest.reference()),
            ledgers = retained.values.sortedWith(compareBy<SyncDeviceLedgerRecord> { it.sequence }.thenBy { it.id.value })))
        observe("GROUP_LEDGER_EVIDENCE_RETAINED")
        observe("GROUP_CHECKPOINT_RETAINED")
        true
    }

    /** Explicit enrollment: signed ancestry, verified keys and checkpoint publish in one protected state. */
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
    private fun build(state: SyncGroupCustodyState): SyncDeviceLedger {
        // read()/unprotect and the custody locks still run on every call. Only identical complete
        // public graph inputs can reuse validation; a signature or even waiting-row change misses.
        verifiedGraph?.takeIf { it.trust == state.trust && it.rows == state.ledgers }?.let { return it.ledger.verifiedCopy() }
        val ledger = SyncDeviceLedger(state.trust).also { graph -> state.ledgers.forEach { graph.offer(it) } }
        verifiedGraph = VerifiedGraph(state.trust, state.ledgers.map(::freeze), ledger.verifiedCopy())
        return ledger
    }
    private fun advance(state: SyncGroupCustodyState, ledger: SyncDeviceLedger): SyncGroupCustodyState {
        val current = ledger.current()
        if (state.trust.checkpoint == current.reference()) return state
        val advanced = state.copy(trust = state.trust.copy(checkpoint = current.reference()))
        write(advanced); observe("GROUP_CHECKPOINT_RETAINED"); return advanced
    }
    private fun freeze(row: SyncDeviceLedgerRecord) = row.copy(members = row.members.toMap(), coveredTypes = row.coveredTypes.toSet(),
        acceptedHeads = row.acceptedHeads.toSet(), recovery = row.recovery?.let { it.copy(parents = it.parents.toSet()) })
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
