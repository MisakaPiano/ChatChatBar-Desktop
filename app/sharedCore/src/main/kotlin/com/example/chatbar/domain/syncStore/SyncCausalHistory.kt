package com.example.chatbar.domain.syncStore

import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Collections

data class SyncVersionRef(val id: SyncStoreId, val bodyHash: String)
data class SyncVersionContent(val objectId: SyncStoreId, val descriptor: CiphertextDescriptor)
data class SyncUnitChange(val valueHash: String?, val content: SyncVersionContent?, val previous: Set<SyncVersionRef>)

/** References and hashes are inside the encrypted version envelope, never remote filenames. */
data class SyncCausalVersion(val group: SyncStoreId, val id: SyncStoreId, val deviceLedger: SyncVersionRef,
    val author: String, val authorSequence: Long, val previousByAuthor: SyncVersionRef?,
    val parents: Set<SyncVersionRef>, val changes: Map<String, SyncUnitChange>, val signature: String) {
    fun reference() = SyncVersionRef(id, bodyHash())
    fun signingBytes() = ("CCB-SYNC-VERSION-SIGNATURE/1\n" + bodyHash()).toByteArray(Charsets.US_ASCII)
    fun bodyHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() {
            override fun write(value: Int) {}
            override fun write(bytes: ByteArray, offset: Int, length: Int) {}
        }
        DataOutputStream(DigestOutputStream(sink, digest)).use { output ->
            output.writeUTF("CCB-SYNC-VERSION/1"); output.writeUTF(group.value); output.writeUTF(id.value)
            fun ref(value: SyncVersionRef) { output.writeUTF(value.id.value); output.writeUTF(value.bodyHash) }
            fun refs(values: Set<SyncVersionRef>) {
                output.writeInt(values.size); values.sortedBy { it.id.value }.forEach(::ref)
            }
            ref(deviceLedger); output.writeUTF(author); output.writeLong(authorSequence)
            output.writeBoolean(previousByAuthor != null); previousByAuthor?.let(::ref); refs(parents)
            output.writeInt(changes.size)
            changes.toSortedMap().forEach { (unit, change) ->
                output.writeUTF(unit); output.writeBoolean(change.valueHash != null)
                change.valueHash?.let { hash ->
                    output.writeUTF(hash)
                    val content = requireNotNull(change.content) { "SYNC_VERSION_CONTENT" }
                    output.writeUTF(content.objectId.value); output.writeLong(content.descriptor.length); output.writeUTF(content.descriptor.sha256.hex)
                }
                refs(change.previous)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Must verify the signature, pinned group/device ledger, epoch and current revocation fence. */
fun interface SyncVersionAdmission { fun requireAuthorized(version: SyncCausalVersion) }
enum class SyncVersionState { WAITING_FOR_PARENTS, ADMITTED, REJECTED }
data class SyncUnitFrontier(val version: SyncVersionRef, val change: SyncUnitChange)

/**
 * Causal content kernel only. No pair baseline or wall clock grants ancestry/deletion authority.
 * The mandatory admission owner supplies authenticated membership/revocation, not a shared-key test.
 * Admission is a fixed membership/fence view: rebuild from retained versions when that view changes.
 * Raw encrypted evidence, durable indexes, resource activation and native publication are separate.
 */
class SyncCausalHistory(private val group: SyncStoreId, private val admission: SyncVersionAdmission) {
    private val versions = linkedMapOf<SyncStoreId, SyncCausalVersion>()
    private val admitted = linkedSetOf<SyncStoreId>()
    private val rejected = linkedMapOf<SyncStoreId, String>()

    @Synchronized fun offer(input: SyncCausalVersion): SyncVersionState {
        val version = freeze(input)
        validate(version)
        val reference = version.reference()
        versions[version.id]?.let {
            require(it.reference() == reference) { "SYNC_VERSION_ID_COLLISION" }
            return requireNotNull(state(version.id))
        }
        admission.requireAuthorized(version)
        versions[version.id] = version
        drain()
        return requireNotNull(state(version.id))
    }
    @Synchronized fun state(id: SyncStoreId): SyncVersionState? = when (id) {
        in rejected -> SyncVersionState.REJECTED
        in admitted -> SyncVersionState.ADMITTED
        in versions -> SyncVersionState.WAITING_FOR_PARENTS
        else -> null
    }
    @Synchronized fun failure(id: SyncStoreId): String? = rejected[id]
    @Synchronized fun heads(): Set<SyncVersionRef> {
        val parents = admitted.flatMap { versions.getValue(it).parents }.map { it.id }.toSet()
        return admitted.filterNot(parents::contains).map { versions.getValue(it).reference() }.toSet()
    }
    @Synchronized fun frontier(unit: String, heads: Set<SyncVersionRef> = heads()): Set<SyncUnitFrontier> {
        val closure = closure(heads)
        val candidates = closure.filter { unit in versions.getValue(it).changes }.toSet()
        val hidden = hashSetOf<SyncStoreId>(); val visited = hashSetOf<SyncStoreId>()
        val pending = java.util.ArrayDeque<SyncStoreId>()
        candidates.forEach { id -> versions.getValue(id).parents.forEach { pending.add(it.id) } }
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (!visited.add(id)) continue
            if (id in candidates) hidden += id
            versions.getValue(id).parents.forEach { pending.add(it.id) }
        }
        return candidates.filterNot(hidden::contains).map { id ->
            val version = versions.getValue(id); SyncUnitFrontier(version.reference(), version.changes.getValue(unit))
        }.toSet()
    }
    @Synchronized fun authorForks(): Set<String> = admitted.map { versions.getValue(it) }
        .groupBy { it.author to it.authorSequence }.filterValues { it.size > 1 }.keys.map { it.first }.toSet()

    private fun drain() {
        do {
            var progressed = false
            for (version in versions.values.toList()) {
                if (version.id in admitted || version.id in rejected) continue
                val mismatch = version.parents.any { ref -> versions[ref.id]?.reference()?.let { it != ref } == true }
                val failedParent = version.parents.any { it.id in rejected }
                val code = when {
                    mismatch -> "SYNC_VERSION_PARENT_BINDING"
                    failedParent -> "SYNC_VERSION_REJECTED_PARENT"
                    version.parents.any { it.id !in admitted } -> continue
                    else -> try { validateCausality(version); null } catch (failure: IllegalArgumentException) {
                        requireNotNull(failure.message?.takeIf { it.matches(Regex("SYNC_VERSION_[A-Z_]+")) })
                    }
                }
                if (code == null) admitted += version.id else rejected[version.id] = code
                progressed = true
            }
        } while (progressed)
    }
    private fun validateCausality(version: SyncCausalVersion) {
        val ancestors = closure(version.parents)
        val previous = version.previousByAuthor
        if (previous == null) require(version.authorSequence == 1L) { "SYNC_VERSION_AUTHOR_CHAIN" }
        else {
            val predecessor = requireNotNull(versions[previous.id]) { "SYNC_VERSION_AUTHOR_CHAIN" }
            require(previous.id in ancestors && predecessor.reference() == previous && predecessor.author == version.author &&
                predecessor.authorSequence < Long.MAX_VALUE && predecessor.authorSequence + 1 == version.authorSequence) { "SYNC_VERSION_AUTHOR_CHAIN" }
        }
        // A device cannot reset its own sequence while acknowledging an earlier authored version.
        val earlier = ancestors.map { versions.getValue(it) }.filter { it.author == version.author }
        require(earlier.all { it.authorSequence < version.authorSequence }) { "SYNC_VERSION_AUTHOR_CHAIN" }
        version.changes.forEach { (unit, change) ->
            val expected = frontier(unit, version.parents).map { it.version }.toSet()
            require(change.previous == expected) { "SYNC_VERSION_UNIT_ANCESTOR" }
            require(change.valueHash != null || expected.isNotEmpty()) { "SYNC_VERSION_EMPTY_TOMBSTONE" }
        }
    }
    private fun closure(heads: Set<SyncVersionRef>): Set<SyncStoreId> {
        val result = linkedSetOf<SyncStoreId>(); val pending = java.util.ArrayDeque<SyncVersionRef>(heads)
        while (pending.isNotEmpty()) {
            val reference = pending.removeFirst(); val version = versions[reference.id]
            require(reference.id in admitted && version?.reference() == reference) { "SYNC_VERSION_UNKNOWN_PARENT" }
            if (result.add(reference.id)) pending.addAll(requireNotNull(version).parents)
        }
        return result
    }
    private fun validate(version: SyncCausalVersion) {
        require(version.group == group && version.author.matches(HEX) && version.authorSequence > 0 && version.signature.length in 1..1024) { "SYNC_VERSION_IDENTITY" }
        fun valid(ref: SyncVersionRef) = ref.bodyHash.matches(HEX)
        require(valid(version.deviceLedger) && version.parents.all(::valid) && version.previousByAuthor?.let(::valid) != false &&
            version.parents.none { it.id == version.id } && version.parents.map { it.id }.toSet().size == version.parents.size) { "SYNC_VERSION_PARENT_BINDING" }
        require(version.changes.keys.all { it.length in 1..600 && it.none { c -> c.code < 32 } }) { "SYNC_VERSION_UNIT" }
        version.changes.values.forEach { change ->
            require((change.valueHash == null) == (change.content == null) && (change.valueHash == null || change.valueHash.matches(HEX)) &&
                (change.content == null || change.content.descriptor.length > 0) && change.previous.all(::valid) &&
                change.previous.map { it.id }.toSet().size == change.previous.size) { "SYNC_VERSION_CONTENT" }
        }
    }
    private fun freeze(version: SyncCausalVersion) = version.copy(
        parents = Collections.unmodifiableSet(version.parents.toSet()),
        changes = Collections.unmodifiableMap(version.changes.mapValues { (_, change) ->
            change.copy(previous = Collections.unmodifiableSet(change.previous.toSet()))
        }))
    companion object { private val HEX = Regex("[a-f0-9]{64}") }
}
