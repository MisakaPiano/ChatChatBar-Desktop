package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.LanSyncMetadataBudget
import com.example.chatbar.domain.lanSync.lanSyncJson
import com.example.chatbar.domain.lanSync.lanSyncUtf8
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Collections

/** Staged proof only. The caller must recheck local custody and explicitly apply it. */
class SyncVerifiedLedgerRecoveryFile internal constructor(
    val records: List<SyncDeviceLedgerRecord>,
    val recovery: SyncDeviceLedgerRecord,
    val keys: List<SyncAdmittedGroupKey>,
    val binding: String,
    val recipientRevoked: Boolean,
) {
    val primary: SyncVersionRef get() = requireNotNull(recovery.recovery).primary
}

/** Separate recovery control file. Native data and plaintext keysets never enter this format. */
object SyncLedgerRecoveryFileCodec {
    private val magic = "CCB-GROUP-LEDGER-RECOVERY\n".toByteArray(Charsets.US_ASCII)
    private const val format = 2
    const val MAX_BYTES = 16 * 1024 * 1024
    private const val maxRows = 16_384
    private const val maxCapsules = 8_192
    private val rowBudget = LanSyncMetadataBudget(maximumDepth = 16, maximumStringCharacters = 128L * 1024)

    fun write(out: OutputStream, trust: SyncGroupTrust, records: List<SyncDeviceLedgerRecord>, capsules: List<ByteArray>) {
        checkCounts(records.size, capsules.size)
        val graph = verifyRows(trust, records)
        var remaining = MAX_BYTES - magic.size - 1 - 8
        fun charge(size: Int) {
            require(size in 1..(remaining - 4)) { "SYNC_GROUP_LEDGER_RECOVERY_FILE_SIZE" }
            remaining -= size + 4
        }
        val copies = capsules.map { bytes ->
            require(bytes.size in 1..SyncKeyDeliveryCodec.MAX_BYTES) { "SYNC_GROUP_LEDGER_RECOVERY_FILE_SIZE" }
            charge(bytes.size)
            bytes.copyOf()
        }
        val encodedRecords = records.map { row ->
            val verified = requireNotNull(graph.record(row.reference()))
            val json = lanSyncJson.encodeToString(SyncDeviceLedgerRecord.serializer(), verified)
            rowBudget.validate(json.reader())
            json.toByteArray(Charsets.UTF_8).also { charge(it.size) }
        }
        verifyCapsules(trust, graph, copies)
        val output = DataOutputStream(out)
        output.write(magic); output.writeByte(format)
        output.writeInt(encodedRecords.size); output.writeInt(copies.size)
        (encodedRecords + copies).forEach { frame -> output.writeInt(frame.size); output.write(frame) }
    }

    fun read(input: InputStream, trust: SyncGroupTrust, recipient: SyncDeviceEncryptionKey,
        allowRevokedNotice: Boolean = false): SyncVerifiedLedgerRecoveryFile {
        val digest = MessageDigest.getInstance("SHA-256")
        val source = DataInputStream(DigestInputStream(input, digest))
        var remaining = MAX_BYTES - magic.size - 1 - 8
        fun frame(maximum: Int): ByteArray {
            val size = source.readInt()
            require(size in 1..minOf(maximum, remaining - 4)) { "SYNC_GROUP_LEDGER_RECOVERY_FILE_SIZE" }
            remaining -= size + 4
            return ByteArray(size).also(source::readFully)
        }
        try {
            require(ByteArray(magic.size).also(source::readFully).contentEquals(magic) && source.readUnsignedByte() == format) {
                "SYNC_GROUP_LEDGER_RECOVERY_FILE_FORMAT"
            }
            val rowCount = source.readInt()
            val capsuleCount = source.readInt()
            checkCounts(rowCount, capsuleCount)
            var decodedBudget = rowBudget.maximumDecodedCost
            val records = List(rowCount) {
                val bytes = frame(MAX_BYTES)
                val json = lanSyncUtf8(bytes)
                val cost = rowBudget.validate(json.reader())
                require(cost <= decodedBudget) { "SYNC_METADATA_WORKING_BUDGET" }
                decodedBudget -= cost
                lanSyncJson.decodeFromString(SyncDeviceLedgerRecord.serializer(), json).also {
                    // A single encoding also rejects duplicate JSON field or map keys.
                    require(lanSyncJson.encodeToString(SyncDeviceLedgerRecord.serializer(), it) == json) {
                        "SYNC_GROUP_LEDGER_RECOVERY_FILE_ROW_ENCODING"
                    }
                }
            }
            val graph = verifyRows(trust, records)
            val capsules = verifyCapsules(trust, graph, List(capsuleCount) { frame(SyncKeyDeliveryCodec.MAX_BYTES) })
            require(source.read() == -1) { "SYNC_GROUP_LEDGER_RECOVERY_FILE_TRAILING_BYTES" }
            val recovery = graph.current()
            val member = requireNotNull(recovery.members[recipient.device]) { "SYNC_GROUP_LEDGER_RECOVERY_FILE_RECIPIENT" }
            require(member.recipientKey == recipient.publicKey.encoded) { "SYNC_GROUP_LEDGER_RECOVERY_FILE_RECIPIENT" }
            require(member.active || allowRevokedNotice) { "SYNC_GROUP_LEDGER_RECOVERY_FILE_RECIPIENT_REVOKED" }
            val own = capsules.filter { it.context.recipient == recipient.device }
            val keys = own.map { capsule ->
                capsule.openTentative(trust, recipient, capsule.objectKey()).admit(graph)
            }
            return SyncVerifiedLedgerRecoveryFile(
                Collections.unmodifiableList(records.map { requireNotNull(graph.record(it.reference())) }),
                recovery, Collections.unmodifiableList(keys), digest.digest().joinToString("") { "%02x".format(it) }, !member.active,
            )
        } catch (failure: EOFException) {
            throw IllegalArgumentException("SYNC_GROUP_LEDGER_RECOVERY_FILE_TRUNCATED", failure)
        }
    }

    private fun checkCounts(rows: Int, capsules: Int) {
        require(rows in 4..maxRows && capsules in 1..maxCapsules) { "SYNC_GROUP_LEDGER_RECOVERY_FILE_COUNT" }
    }

    private fun verifyRows(trust: SyncGroupTrust, records: List<SyncDeviceLedgerRecord>): SyncDeviceLedger {
        val graph = SyncDeviceLedger(trust)
        val seen = hashSetOf<SyncStoreId>()
        val frontier = linkedSetOf<SyncVersionRef>()
        for (row in records) {
            require(seen.add(row.id)) { "SYNC_GROUP_LEDGER_RECOVERY_FILE_DUPLICATE_ROW" }
            val parents = row.recovery?.parents ?: setOfNotNull(row.parent)
            require(parents.isNotEmpty() || seen.size == 1 && row.reference() == trust.genesis) {
                "SYNC_GROUP_LEDGER_RECOVERY_FILE_ANCESTRY"
            }
            require(parents.all(graph::contains) && graph.offer(row) == SyncVersionState.ADMITTED) {
                "SYNC_GROUP_LEDGER_RECOVERY_FILE_ANCESTRY"
            }
            frontier.removeAll(parents)
            frontier.add(row.reference())
        }
        val recovery = records.last()
        require(recovery.recovery != null && recovery.parent == null && frontier.singleOrNull() == recovery.reference() &&
            records.dropLast(1).none { it.epoch == recovery.epoch } && graph.contains(trust.checkpoint) &&
            graph.current().reference() == recovery.reference()) { "SYNC_GROUP_LEDGER_RECOVERY_FILE_PROOF" }
        return graph
    }

    private fun verifyCapsules(trust: SyncGroupTrust, graph: SyncDeviceLedger,
        bytes: List<ByteArray>): List<SyncGroupKeyDelivery> {
        val recovery = graph.current()
        val recoveryRef = recovery.reference()
        val epochs = linkedMapOf<SyncStoreId, String>()
        graph.ancestry(recoveryRef).forEach { row ->
            val previous = epochs.putIfAbsent(row.epoch, row.keyCommitment)
            require(previous == null || previous == row.keyCommitment) { "SYNC_GROUP_LEDGER_RECOVERY_FILE_EPOCH_BINDING" }
        }
        val recipients = recovery.members.filterValues { it.active }
        require(bytes.size.toLong() == recipients.size.toLong() * epochs.size) { "SYNC_GROUP_LEDGER_RECOVERY_FILE_CAPSULE_MATRIX" }
        val recipientPins = recipients.mapValues { (_, member) -> SyncRecipientPublicKey.parse(member.recipientKey).hash }
        val objectIds = hashSetOf<SyncStoreId>()
        val slots = hashSetOf<Pair<String, SyncStoreId>>()
        val result = bytes.map { frame ->
            val capsule = SyncKeyDeliveryCodec.decode(frame)
            require(SyncKeyDeliveryCodec.encode(capsule).contentEquals(frame)) { "SYNC_GROUP_LEDGER_RECOVERY_FILE_CAPSULE_ENCODING" }
            val context = capsule.context
            require(objectIds.add(context.objectId) && slots.add(context.recipient to context.epoch)) {
                "SYNC_GROUP_LEDGER_RECOVERY_FILE_DUPLICATE_CAPSULE"
            }
            capsule.verifyOwner(trust, capsule.objectKey())
            require(recipientPins[context.recipient] == context.recipientKeyHash &&
                epochs[context.epoch] == context.keyCommitment && context.authorizingLedger == recoveryRef) {
                "SYNC_GROUP_LEDGER_RECOVERY_FILE_CAPSULE_AUTHORITY"
            }
            capsule
        }
        return result
    }

    private fun SyncGroupKeyDelivery.objectKey() = CiphertextObjectKey(CiphertextObjectKind.COMMIT, context.objectId)
}
