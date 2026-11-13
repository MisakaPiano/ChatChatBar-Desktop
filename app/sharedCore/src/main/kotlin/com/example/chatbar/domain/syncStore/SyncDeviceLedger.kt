package com.example.chatbar.domain.syncStore

import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Collections
import kotlinx.serialization.Serializable

@Serializable data class SyncGroupMember(val certificate: String, val recipientKey: String, val active: Boolean = true) {
    init {
        val base64 = Regex("[A-Za-z0-9+/]+={0,2}")
        require(certificate.length in 1..8192 && recipientKey.length in 1..32 * 1024 &&
            certificate.matches(base64) && recipientKey.matches(base64)) { "SYNC_GROUP_MEMBER" }
    }
}
@Serializable data class SyncGroupTrust(val group: SyncStoreId, val genesis: SyncVersionRef, val ownerCertificate: String,
    val checkpoint: SyncVersionRef = genesis)
@Serializable data class SyncDeviceLedgerRecord(val group: SyncStoreId, val id: SyncStoreId, val parent: SyncVersionRef?,
    val sequence: Long, val epoch: SyncStoreId, val keyCommitment: String, val coveredTypes: Set<String>, val members: Map<String, SyncGroupMember>,
    val acceptedHeads: Set<SyncVersionRef>, val signature: String) {
    init {
        require(sequence > 0 && parent?.id != id && signature.length in 1..256 && keyCommitment.matches(Regex("[a-f0-9]{64}")) &&
            coveredTypes.isNotEmpty() && coveredTypes.all { it.matches(Regex("[a-z_]{1,80}")) } &&
            members.isNotEmpty() && members.all { (id, member) -> id.matches(Regex("[a-f0-9]{64}")) && member.certificate.length in 1..8192 } &&
            acceptedHeads.map { it.id }.toSet().size == acceptedHeads.size) { "SYNC_GROUP_LEDGER" }
    }
    fun reference() = SyncVersionRef(id, bodyHash())
    fun signingBytes() = ("CCB-SYNC-DEVICE-LEDGER-SIGNATURE/1\n" + bodyHash()).toByteArray(Charsets.US_ASCII)
    fun bodyHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() { override fun write(value: Int) {}; override fun write(bytes: ByteArray, offset: Int, length: Int) {} }
        DataOutputStream(DigestOutputStream(sink, digest)).use { out ->
            out.writeUTF("CCB-SYNC-DEVICE-LEDGER/3"); out.writeUTF(group.value); out.writeUTF(id.value)
            fun ref(value: SyncVersionRef) { out.writeUTF(value.id.value); out.writeUTF(value.bodyHash) }
            out.writeBoolean(parent != null); parent?.let(::ref); out.writeLong(sequence); out.writeUTF(epoch.value); out.writeUTF(keyCommitment)
            out.writeInt(coveredTypes.size); coveredTypes.sorted().forEach(out::writeUTF)
            out.writeInt(members.size)
            members.toSortedMap().forEach { (id, member) -> out.writeUTF(id); out.writeUTF(member.certificate); out.writeUTF(member.recipientKey); out.writeBoolean(member.active) }
            out.writeInt(acceptedHeads.size); acceptedHeads.sortedBy { it.id.value }.forEach(::ref)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Pinned owner admits/revokes identities; ordinary content authors do not need the owner online. */
class SyncDeviceLedger(private val trust: SyncGroupTrust) {
    private val owner = SyncDeviceSignatures.decode(trust.ownerCertificate)
    private val records = linkedMapOf<SyncStoreId, SyncDeviceLedgerRecord>()
    private val admitted = linkedSetOf<SyncStoreId>()
    private val rejected = linkedMapOf<SyncStoreId, String>()

    @Synchronized fun offer(input: SyncDeviceLedgerRecord): SyncVersionState {
        val record = input.copy(members = Collections.unmodifiableMap(input.members.toMap()),
            coveredTypes = Collections.unmodifiableSet(input.coveredTypes.toSet()),
            acceptedHeads = Collections.unmodifiableSet(input.acceptedHeads.toSet()))
        require(record.group == trust.group && record.sequence > 0 && record.signature.length in 1..256 &&
            record.parent?.bodyHash?.matches(HEX) != false && record.parent?.id != record.id &&
            record.acceptedHeads.all { it.bodyHash.matches(HEX) } &&
            record.acceptedHeads.map { it.id }.toSet().size == record.acceptedHeads.size) { "SYNC_GROUP_LEDGER" }
        record.members.forEach { (id, member) ->
            val certificate = SyncDeviceSignatures.decode(member.certificate)
            require(certificate.fingerprint == id && certificate.encoded == member.certificate) { "SYNC_GROUP_MEMBER" }
            require(SyncRecipientPublicKey.parse(member.recipientKey).encoded == member.recipientKey) { "SYNC_GROUP_RECIPIENT_KEY" }
        }
        require(record.members[owner.fingerprint]?.let { it.certificate == owner.encoded && it.active } == true &&
            owner.verifies(record.signingBytes(), record.signature)) { "SYNC_GROUP_OWNER_SIGNATURE" }
        records[record.id]?.let {
            require(it.reference() == record.reference()) { "SYNC_GROUP_LEDGER_ID_COLLISION" }
            return requireNotNull(state(record.id))
        }
        records[record.id] = record
        do {
            var progress = false
            for (row in records.values.toList()) {
                if (row.id in admitted || row.id in rejected) continue
                val parent = row.parent?.let { records[it.id] }
                val code = when {
                    row.parent == null -> if (row.reference() == trust.genesis && row.sequence == 1L && row.acceptedHeads.isEmpty()) null else "SYNC_GROUP_GENESIS_PIN"
                    parent != null && parent.reference() != row.parent -> "SYNC_GROUP_PARENT_BINDING"
                    row.parent.id in rejected -> "SYNC_GROUP_REJECTED_PARENT"
                    row.parent.id !in admitted -> continue
                    else -> try { validateChild(row, requireNotNull(parent)); null } catch (failure: IllegalArgumentException) {
                        requireNotNull(failure.message?.takeIf { it.matches(Regex("SYNC_GROUP_[A-Z_]+")) })
                    }
                }
                if (code == null) admitted += row.id else rejected[row.id] = code
                progress = true
            }
        } while (progress)
        return requireNotNull(state(record.id))
    }
    @Synchronized fun state(id: SyncStoreId): SyncVersionState? = when (id) {
        in rejected -> SyncVersionState.REJECTED; in admitted -> SyncVersionState.ADMITTED
        in records -> SyncVersionState.WAITING_FOR_PARENTS; else -> null
    }
    @Synchronized fun failure(id: SyncStoreId) = rejected[id]
    @Synchronized fun contains(reference: SyncVersionRef) = reference.id in admitted && records[reference.id]?.reference() == reference
    @Synchronized fun record(reference: SyncVersionRef): SyncDeviceLedgerRecord? = records[reference.id]?.takeIf { contains(reference) }
    @Synchronized fun current(): SyncDeviceLedgerRecord {
        val parents = admitted.mapNotNull { records.getValue(it).parent?.id }.toSet()
        val heads = admitted.filterNot(parents::contains)
        require(heads.size == 1) { "SYNC_GROUP_LEDGER_FORK_OR_MISSING" }
        val head = records.getValue(heads.single())
        var cursor: SyncDeviceLedgerRecord? = head
        while (cursor != null && cursor.reference() != trust.checkpoint) cursor = cursor.parent?.let { records.getValue(it.id) }
        require(cursor != null) { "SYNC_GROUP_KNOWN_CHECKPOINT_MISSING" }
        return head
    }
    private fun validateChild(row: SyncDeviceLedgerRecord, parent: SyncDeviceLedgerRecord) {
        require(parent.sequence < Long.MAX_VALUE && row.sequence == parent.sequence + 1 &&
            row.members.keys.containsAll(parent.members.keys)) { "SYNC_GROUP_LEDGER_SEQUENCE" }
        require(row.coveredTypes.containsAll(parent.coveredTypes)) { "SYNC_GROUP_SCOPE_SHRINK" }
        require(parent.members.all { (id, old) -> val next = row.members.getValue(id)
            next.certificate == old.certificate && (old.active || !next.active) }) { "SYNC_GROUP_MEMBER_REACTIVATION" }
        require(parent.members.all { (id, old) -> row.members.getValue(id).recipientKey == old.recipientKey }) { "SYNC_GROUP_MEMBER_KEY_CHANGED" }
        val revoked = parent.members.any { (id, member) -> member.active && !row.members.getValue(id).active }
        require(!revoked || row.epoch != parent.epoch) { "SYNC_GROUP_REVOCATION_EPOCH" }
        if (row.epoch == parent.epoch) {
            require(row.keyCommitment == parent.keyCommitment) { "SYNC_GROUP_KEY_COMMITMENT_CHANGED" }
            require(row.acceptedHeads == parent.acceptedHeads) { "SYNC_GROUP_FENCE_CHANGED" }
        }
        else {
            require(row.acceptedHeads.containsAll(parent.acceptedHeads)) { "SYNC_GROUP_FENCE_LOST" }
            var old: SyncDeviceLedgerRecord? = parent
            while (old != null) {
                require(row.epoch != old.epoch) { "SYNC_GROUP_EPOCH_REUSED" }
                old = old.parent?.let { records.getValue(it.id) }
            }
        }
    }

    /** New immutable authorization view after every membership update/discovery rescan. */
    @Synchronized fun admission(knownVersions: Collection<SyncCausalVersion>,
        authenticatedObjects: Collection<SyncAuthenticatedGroupObject>): SyncVersionAdmission {
        val latest = current()
        val ledgers = admitted.associate { id -> val row = records.getValue(id); row.reference() to row }
        val certificates = latest.members.mapValues { SyncDeviceSignatures.decode(it.value.certificate) }
        // Supplied only by complete AEAD envelope authentication; no claimed epoch from JSON is proof.
        val epochs = authenticatedObjects.filter { it.context.group == trust.group && it.envelope.version != null && it.keyCommitment == latest.keyCommitment }
            .groupBy { requireNotNull(it.envelope.version).reference() }.mapValues { (_, objects) -> objects.map { it.context.epoch }.toSet() }
        val known = knownVersions.filter { it.group == trust.group }.associate { version ->
            val frozen = version.copy(parents = version.parents.toSet(), changes = version.changes.mapValues { it.value.copy(previous = it.value.previous.toSet()) })
            frozen.reference() to frozen.parents
        }
        fun fenced(candidate: SyncVersionRef): Boolean {
            val queue = java.util.ArrayDeque<SyncVersionRef>(latest.acceptedHeads); val seen = hashSetOf<SyncVersionRef>()
            var missing = false
            while (queue.isNotEmpty()) {
                val reference = queue.removeFirst()
                if (reference == candidate) return true
                if (!seen.add(reference)) continue
                val parents = known[reference]
                if (parents == null) missing = true else queue.addAll(parents)
            }
            require(!missing) { "SYNC_GROUP_FENCE_CONTENT_MISSING" }
            return false
        }
        return SyncVersionAdmission { version ->
            require(version.group == trust.group) { "SYNC_GROUP_IDENTITY" }
            val ledger = requireNotNull(ledgers[version.deviceLedger]) { "SYNC_GROUP_LEDGER_MISSING" }
            require(ledger.members[version.author]?.active == true) { "SYNC_GROUP_AUTHOR_NOT_ADMITTED" }
            val certificate = requireNotNull(certificates[version.author]) { "SYNC_GROUP_AUTHOR_NOT_ADMITTED" }
            require(certificate.verifies(version.signingBytes(), version.signature)) { "SYNC_GROUP_VERSION_SIGNATURE" }
            val active = latest.members[version.author]?.active == true
            if (!active || latest.epoch !in epochs[version.reference()].orEmpty()) {
                require(fenced(version.reference())) { if (active) "SYNC_GROUP_CURRENT_EPOCH_REQUIRED" else "SYNC_GROUP_REVOKED_VERSION" }
            }
        }
    }
    companion object { private val HEX = Regex("[a-f0-9]{64}") }
}
