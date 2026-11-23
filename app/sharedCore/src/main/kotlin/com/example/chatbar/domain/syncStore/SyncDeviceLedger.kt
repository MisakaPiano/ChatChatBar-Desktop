package com.example.chatbar.domain.syncStore

import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Collections
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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

internal data class SyncVerifiedLedgerFork(
    val common: SyncDeviceLedgerRecord,
    val branches: List<SyncDeviceLedgerRecord>,
    val headCount: Int,
)
@Serializable data class SyncGroupLedgerRecovery(val parents: Set<SyncVersionRef>, val primary: SyncVersionRef) {
    init {
        require(parents.size >= 2 && primary in parents && parents.map { it.id }.toSet().size == parents.size &&
            parents.all { it.bodyHash.matches(Regex("[a-f0-9]{64}")) }) { "SYNC_GROUP_RECOVERY_PARENTS" }
    }
}
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class SyncDeviceLedgerRecord(val group: SyncStoreId, val id: SyncStoreId, val parent: SyncVersionRef?,
    val sequence: Long, val epoch: SyncStoreId, val keyCommitment: String, val coveredTypes: Set<String>, val members: Map<String, SyncGroupMember>,
    val acceptedHeads: Set<SyncVersionRef>, val signature: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val recovery: SyncGroupLedgerRecovery? = null) {
    init {
        require(sequence > 0 && parent?.id != id && (recovery == null || parent == null && recovery.parents.none { it.id == id }) &&
            signature.length in 1..256 && keyCommitment.matches(Regex("[a-f0-9]{64}")) &&
            coveredTypes.isNotEmpty() && coveredTypes.all { it.matches(Regex("[a-z_]{1,80}")) } &&
            members.isNotEmpty() && members.all { (id, member) -> id.matches(Regex("[a-f0-9]{64}")) && member.certificate.length in 1..8192 } &&
            acceptedHeads.map { it.id }.toSet().size == acceptedHeads.size) { "SYNC_GROUP_LEDGER" }
    }
    val parents: Set<SyncVersionRef> get() = recovery?.parents ?: setOfNotNull(parent)
    fun reference() = SyncVersionRef(id, bodyHash())
    fun signingBytes() = ("CCB-SYNC-DEVICE-LEDGER-SIGNATURE/${if (recovery == null) 1 else 2}\n" + bodyHash()).toByteArray(Charsets.US_ASCII)
    fun bodyHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() { override fun write(value: Int) {}; override fun write(bytes: ByteArray, offset: Int, length: Int) {} }
        DataOutputStream(DigestOutputStream(sink, digest)).use { out ->
            out.writeUTF(if (recovery == null) "CCB-SYNC-DEVICE-LEDGER/3" else "CCB-SYNC-DEVICE-LEDGER/4")
            out.writeUTF(group.value); out.writeUTF(id.value)
            fun ref(value: SyncVersionRef) { out.writeUTF(value.id.value); out.writeUTF(value.bodyHash) }
            if (recovery == null) { out.writeBoolean(parent != null); parent?.let(::ref) }
            else {
                out.writeInt(recovery.parents.size)
                recovery.parents.sortedBy { it.id.value }.forEach(::ref)
                ref(recovery.primary)
            }
            out.writeLong(sequence); out.writeUTF(epoch.value); out.writeUTF(keyCommitment)
            out.writeInt(coveredTypes.size); coveredTypes.sorted().forEach(out::writeUTF)
            out.writeInt(members.size)
            members.toSortedMap().forEach { (id, member) -> out.writeUTF(id); out.writeUTF(member.certificate); out.writeUTF(member.recipientKey); out.writeBoolean(member.active) }
            out.writeInt(acceptedHeads.size); acceptedHeads.sortedBy { it.id.value }.forEach(::ref)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Pinned owner admits/revokes identities; ordinary content authors do not need the owner online. */
class SyncDeviceLedger private constructor(private val trust: SyncGroupTrust, private val owner: SyncPinnedDeviceCertificate) {
    constructor(trust: SyncGroupTrust) : this(trust, SyncDeviceSignatures.decode(trust.ownerCertificate))

    val trustAnchor: SyncGroupTrust get() = trust
    val ownerCertificate: String get() = trust.ownerCertificate
    private val records = linkedMapOf<SyncStoreId, SyncDeviceLedgerRecord>()
    private val admitted = linkedSetOf<SyncStoreId>()
    private val rejected = linkedMapOf<SyncStoreId, String>()

    /** Copy only a fully reconstructed graph; callers may offer new evidence to their own view. */
    @Synchronized internal fun verifiedCopy(): SyncDeviceLedger = SyncDeviceLedger(trust, owner).also { copy ->
        // offer() freezes every record's collections. Preserve waiting, rejected and fork evidence too.
        copy.records.putAll(records)
        copy.admitted.addAll(admitted)
        copy.rejected.putAll(rejected)
    }

    @Synchronized fun offer(input: SyncDeviceLedgerRecord): SyncVersionState {
        val record = input.copy(members = Collections.unmodifiableMap(input.members.toMap()),
            coveredTypes = Collections.unmodifiableSet(input.coveredTypes.toSet()),
            acceptedHeads = Collections.unmodifiableSet(input.acceptedHeads.toSet()),
            recovery = input.recovery?.let { it.copy(parents = Collections.unmodifiableSet(it.parents.toSet())) })
        require(record.group == trust.group && record.sequence > 0 && record.signature.length in 1..256 &&
            record.parents.all { it.id != record.id && it.bodyHash.matches(HEX) } &&
            record.acceptedHeads.all { it.bodyHash.matches(HEX) } &&
            record.acceptedHeads.map { it.id }.toSet().size == record.acceptedHeads.size) { "SYNC_GROUP_LEDGER" }
        validateMembers(record)
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
                val parents = row.parents
                val code = when {
                    parents.isEmpty() -> if (row.reference() == trust.genesis && row.sequence == 1L && row.acceptedHeads.isEmpty()) null else "SYNC_GROUP_GENESIS_PIN"
                    parents.any { parent -> records[parent.id]?.reference()?.let { it != parent } == true } -> "SYNC_GROUP_PARENT_BINDING"
                    parents.any { it.id in rejected } -> "SYNC_GROUP_REJECTED_PARENT"
                    parents.any { it.id !in admitted } -> continue
                    else -> try {
                        if (row.recovery == null) validateChild(row, records.getValue(parents.single().id))
                        else validateRecovery(row)
                        null
                    } catch (failure: IllegalArgumentException) {
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
    private fun ancestorIds(id: SyncStoreId): Set<SyncStoreId> {
        val pending = ArrayDeque<SyncStoreId>(); pending.add(id)
        val ids = linkedSetOf<SyncStoreId>()
        while (pending.isNotEmpty()) {
            val next = pending.removeLast()
            if (ids.add(next)) records.getValue(next).parents.forEach { pending.add(it.id) }
        }
        return ids
    }
    @Synchronized internal fun isAncestorOrSelf(ancestor: SyncVersionRef, descendant: SyncVersionRef): Boolean =
        contains(ancestor) && contains(descendant) && ancestor.id in ancestorIds(descendant.id)

    /** Genesis-to-tip order is unchanged for legacy linear histories. */
    @Synchronized fun history(reference: SyncVersionRef = current().reference()): List<SyncDeviceLedgerRecord> = ancestry(reference)
    @Synchronized internal fun ancestry(reference: SyncVersionRef = current().reference()): List<SyncDeviceLedgerRecord> {
        require(contains(reference)) { "SYNC_GROUP_LEDGER_MISSING" }
        return Collections.unmodifiableList(ancestorIds(reference.id).map(records::getValue).sortedWith(ancestryOrder))
    }
    /** Only the parent explicitly selected by each signed recovery remains on this authority spine. */
    @Synchronized internal fun authorityHistory(reference: SyncVersionRef = current().reference()): List<SyncDeviceLedgerRecord> {
        var row = requireNotNull(record(reference)) { "SYNC_GROUP_LEDGER_MISSING" }
        val result = mutableListOf<SyncDeviceLedgerRecord>()
        while (true) {
            result += row
            val primary = row.recovery?.primary ?: row.parent ?: break
            row = records.getValue(primary.id)
        }
        return Collections.unmodifiableList(result.asReversed())
    }

    @Synchronized internal fun latestRecovery(reference: SyncVersionRef = current().reference()): SyncVersionRef? =
        authorityHistory(reference).lastOrNull { it.recovery != null }?.reference()
    @Synchronized fun hasKey(epoch: SyncStoreId, commitment: String, at: SyncVersionRef = current().reference()): Boolean {
        return contains(at) && ancestorIds(at.id).any { records.getValue(it).let { row -> row.epoch == epoch && row.keyCommitment == commitment } }
    }
    private fun headsOf(rows: Collection<SyncStoreId>): List<SyncStoreId> {
        val parents = rows.flatMap { records.getValue(it).parents.map(SyncVersionRef::id) }.toSet()
        return rows.filterNot(parents::contains)
    }
    private fun admittedHeads(): List<SyncStoreId> = headsOf(admitted)
    @Synchronized internal fun admittedTips(): Set<SyncVersionRef> = Collections.unmodifiableSet(
        admittedHeads().sortedBy { it.value }.mapTo(linkedSetOf()) { records.getValue(it).reference() })

    private fun sharedAncestors(tips: List<SyncStoreId>): Set<SyncStoreId> =
        tips.map(::ancestorIds).reduce { common, next -> common.intersect(next) }

    /** Only fully admitted, root-signed records participate in this diagnostic view. */
    @Synchronized internal fun verifiedFork(): SyncVerifiedLedgerFork? {
        val heads = admittedHeads()
        if (heads.size < 2) return null
        val commonIds = sharedAncestors(heads)
        var commonTips = headsOf(commonIds)
        // Concurrent recoveries may share multiple incomparable tips. Use their shared baseline.
        while (commonTips.size > 1) commonTips = headsOf(sharedAncestors(commonTips))
        val common = records.getValue(commonTips.single())
        val branches = admitted.filterNot(commonIds::contains).map(records::getValue)
            .filter { row -> row.parents.all { it.id in commonIds } }.sortedBy { it.id.value }
        return SyncVerifiedLedgerFork(common, branches, heads.size)
    }

    @Synchronized fun current(): SyncDeviceLedgerRecord {
        val heads = admittedHeads()
        require(heads.size == 1) { "SYNC_GROUP_LEDGER_FORK_OR_MISSING" }
        val head = records.getValue(heads.single())
        require(contains(trust.checkpoint) && trust.checkpoint.id in ancestorIds(head.id)) { "SYNC_GROUP_KNOWN_CHECKPOINT_MISSING" }
        return head
    }
    /** Root preparation validation only. This never admits an unsigned record or changes authority. */
    @Synchronized fun validateProposedChild(row: SyncDeviceLedgerRecord) {
        val parent = current()
        require(row.recovery == null && row.group == trust.group && row.parent == parent.reference() && row.id !in records &&
            row.members[owner.fingerprint]?.let { it.active && it.certificate == owner.encoded } == true) { "SYNC_GROUP_LEDGER_PROPOSAL" }
        validateMembers(row, "SYNC_GROUP_MEMBER")
        validateChild(row, parent)
    }
    @Synchronized internal fun validateProposedRecovery(row: SyncDeviceLedgerRecord) {
        require(row.recovery != null && row.group == trust.group && row.id !in records && row.parents == admittedTips() &&
            row.members[owner.fingerprint]?.let { it.active && it.certificate == owner.encoded } == true) { "SYNC_GROUP_RECOVERY_PROPOSAL" }
        require(contains(trust.checkpoint)) { "SYNC_GROUP_KNOWN_CHECKPOINT_MISSING" }
        require(statesAreClosed()) { "SYNC_GROUP_RECOVERY_PARENTS" }
        validateMembers(row)
        validateRecovery(row)
    }
    private fun statesAreClosed() = records.keys.all { it in admitted || it in rejected }
    private fun validateMembers(row: SyncDeviceLedgerRecord, recipientError: String = "SYNC_GROUP_RECIPIENT_KEY") {
        row.members.forEach { (id, member) ->
            val certificate = SyncDeviceSignatures.decode(member.certificate)
            require(certificate.fingerprint == id && certificate.encoded == member.certificate) { "SYNC_GROUP_MEMBER" }
            require(SyncRecipientPublicKey.parse(member.recipientKey).encoded == member.recipientKey) { recipientError }
        }
    }
    private fun validateRecovery(row: SyncDeviceLedgerRecord) {
        val parents = row.parents.map { records.getValue(it.id) }
        val ancestorSets = parents.associate { it.id to ancestorIds(it.id) }
        require(parents.all { parent -> parents.none { it.id != parent.id && it.id in ancestorSets.getValue(parent.id) } }) { "SYNC_GROUP_RECOVERY_PARENTS" }
        val maxSequence = parents.maxOf { it.sequence }
        require(maxSequence < Long.MAX_VALUE && row.sequence == maxSequence + 1) { "SYNC_GROUP_LEDGER_SEQUENCE" }
        val recoveryAncestors = ancestorSets.values.flatten().toSet()
        val ancestorRows = recoveryAncestors.map(records::getValue)
        require(ancestorRows.none { it.epoch == row.epoch || it.keyCommitment == row.keyCommitment }) { "SYNC_GROUP_EPOCH_REUSED" }
        require(ancestorRows.groupBy { it.epoch }.values.all { rows -> rows.all { it.keyCommitment == rows.first().keyCommitment } }) { "SYNC_GROUP_RECOVERY_EPOCH_CONFLICT" }
        require(parents.all { row.coveredTypes.containsAll(it.coveredTypes) }) { "SYNC_GROUP_SCOPE_SHRINK" }
        require(parents.all { row.acceptedHeads.containsAll(it.acceptedHeads) }) { "SYNC_GROUP_FENCE_LOST" }
        require(row.acceptedHeads.none { it.id in recoveryAncestors && contains(it) }) { "SYNC_GROUP_RECOVERY_CONTENT_FENCE" }
        require(row.members.keys == parents.flatMap { it.members.keys }.toSet()) { "SYNC_GROUP_RECOVERY_MEMBERS" }
        require(row.members.values.map { it.recipientKey }.toSet().size == row.members.size) { "SYNC_GROUP_RECOVERY_MEMBER_KEY_CONFLICT" }
        for (parent in parents) parent.members.forEach { (id, old) ->
            val next = row.members.getValue(id)
            require(next.certificate == old.certificate && next.recipientKey == old.recipientKey) { "SYNC_GROUP_RECOVERY_MEMBER_KEY_CONFLICT" }
            require(old.active || !next.active) { "SYNC_GROUP_MEMBER_REACTIVATION" }
        }
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
            require(ancestorIds(parent.id).none { records.getValue(it).epoch == row.epoch }) { "SYNC_GROUP_EPOCH_REUSED" }
        }
    }

    /** Preserve every independent first-revocation edge in the authenticated ancestry. */
    @Synchronized internal fun revocationCuts(device: String, reference: SyncVersionRef = current().reference()): List<SyncDeviceLedgerRecord> =
        ancestry(reference).filter { startsRevocation(it, device) }

    private fun startsRevocation(row: SyncDeviceLedgerRecord, device: String): Boolean {
        if (row.members[device]?.active != false) return false
        val parentStates = row.parents.mapNotNull { records.getValue(it.id).members[device]?.active }
        return true in parentStates && false !in parentStates
    }

    /** A discarded membership path remains evidence; it cannot grant business ancestry. */
    @Synchronized internal fun excludedLedgerCuts(ledger: SyncVersionRef, through: SyncVersionRef = current().reference()): List<SyncDeviceLedgerRecord> {
        if (!contains(ledger)) return emptyList()
        val authority = authorityHistory(through)
        if (authority.any { it.reference() == ledger }) return emptyList()
        return authority.filter { row -> row.recovery?.let { recovery ->
            ledger.id in ancestorIds(row.id) && ledger.id !in ancestorIds(recovery.primary.id)
        } == true }
    }

    internal fun isExcludedSource(version: SyncCausalVersion, history: SyncCausalHistory): Boolean =
        !history.contains(version.reference()) && excludedLedgerCuts(version.deviceLedger).any { cut ->
            cut.acceptedHeads.all(history::contains)
        }

    /** The start is scoped to the last root-signed recovery at the version's own signing checkpoint. */
    @Synchronized internal fun authorRecoveryAt(through: SyncVersionRef, author: String): SyncVersionRef? =
        record(through)?.let { authorizingRecovery(it, author, ::record) }?.reference()

    private fun authorizingRecovery(at: SyncDeviceLedgerRecord, author: String,
        recordAt: (SyncVersionRef) -> SyncDeviceLedgerRecord?): SyncDeviceLedgerRecord? {
        val member = at.members[author]?.takeIf { it.active } ?: return null
        val recovery = generateSequence(at) { row -> row.parent?.let(recordAt) }.firstOrNull { it.recovery != null }
        return recovery?.takeIf { it.members[author]?.let { retained -> retained.active && retained.certificate == member.certificate } == true }
    }

    /** New immutable authorization view after every membership update/discovery rescan. */
    @Synchronized fun admission(knownVersions: Collection<SyncCausalVersion>,
        authenticatedObjects: Collection<SyncAuthenticatedGroupObject>): SyncVersionAdmission {
        val latest = current()
        val ledgers = admitted.associate { id -> val row = records.getValue(id); row.reference() to row }
        val certificates = latest.members.mapValues { SyncDeviceSignatures.decode(it.value.certificate) }
        val recovered = ledgers.values.any { it.recovery != null }
        val fullAncestry = if (recovered) ancestry(latest.reference()) else emptyList()
        val revocationFences = if (recovered) latest.members.filterValues { !it.active }.keys.associateWith { device ->
            fullAncestry.filter { startsRevocation(it, device) }.map { it.acceptedHeads }.distinct()
        } else emptyMap()
        val evidenceFences = if (recovered) {
            val authority = authorityHistory(latest.reference()).mapTo(hashSetOf()) { it.id }
            val fences = mutableMapOf<SyncStoreId, MutableSet<Set<SyncVersionRef>>>()
            fullAncestry.forEach { row -> row.recovery?.let { recovery ->
                val primaryAncestry = ancestorIds(recovery.primary.id)
                ancestorIds(row.id).forEach { id ->
                    if (id != row.id && id !in primaryAncestry && id !in authority) {
                        fences.getOrPut(id) { linkedSetOf() }.add(row.acceptedHeads)
                    }
                }
            } }
            fences
        } else emptyMap()
        // Supplied only by complete AEAD envelope authentication; no claimed epoch from JSON is proof.
        val epochs = authenticatedObjects.filter { it.context.group == trust.group && it.envelope.version != null && it.keyCommitment == latest.keyCommitment }
            .groupBy { requireNotNull(it.envelope.version).reference() }.mapValues { (_, objects) -> objects.map { it.context.epoch }.toSet() }
        val known = knownVersions.filter { it.group == trust.group }.associate { version ->
            val frozen = version.copy(parents = version.parents.toSet(), changes = version.changes.mapValues { it.value.copy(previous = it.value.previous.toSet()) })
            frozen.reference() to frozen.parents
        }
        fun fenced(candidate: SyncVersionRef, acceptedHeads: Set<SyncVersionRef>): Boolean {
            val queue = java.util.ArrayDeque<SyncVersionRef>(acceptedHeads); val seen = hashSetOf<SyncVersionRef>()
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
            version.authorRecovery?.let { recovery ->
                val cut = authorizingRecovery(ledger, version.author, ledgers::get)
                require(cut?.reference() == recovery && cut.acceptedHeads.all { fenced(it, version.parents) }) {
                    "SYNC_GROUP_AUTHOR_RECOVERY_AUTHORITY"
                }
            }
            val active = latest.members[version.author]?.active == true
            val reference = version.reference()
            if (!active || latest.epoch !in epochs[reference].orEmpty()) {
                require(fenced(reference, latest.acceptedHeads)) { if (active) "SYNC_GROUP_CURRENT_EPOCH_REQUIRED" else "SYNC_GROUP_REVOKED_VERSION" }
            }
            require(revocationFences[version.author].orEmpty().all { fenced(reference, it) }) { "SYNC_GROUP_REVOKED_VERSION" }
            require(evidenceFences[version.deviceLedger.id].orEmpty().all { fenced(reference, it) }) { "SYNC_GROUP_RECOVERY_VERSION_NOT_REVIEWED" }
        }
    }
    companion object {
        private val HEX = Regex("[a-f0-9]{64}")
        private val ancestryOrder = compareBy<SyncDeviceLedgerRecord> { it.sequence }.thenBy { it.id.value }
    }
}

/** Nearby relay after explicit recovery may extend an epoch only with an ordinary single-device approval. */
internal fun SyncDeviceLedgerRecord.isApprovalAfter(previous: SyncDeviceLedgerRecord): Boolean =
    recovery == null && parent == previous.reference() && epoch == previous.epoch &&
        keyCommitment == previous.keyCommitment && acceptedHeads == previous.acceptedHeads &&
        coveredTypes == previous.coveredTypes && members.size == previous.members.size + 1 &&
        previous.members.all { (id, row) -> members[id] == row } &&
        members.filterKeys { it !in previous.members }.values.single().active
