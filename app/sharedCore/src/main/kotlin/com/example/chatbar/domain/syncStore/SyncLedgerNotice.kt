package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.lanSyncJson
import com.example.chatbar.domain.lanSync.lanSyncUtf8
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.Serializable

@Serializable data class SyncLedgerNoticeContext(val format: Int, val objectId: SyncStoreId, val group: SyncStoreId,
    val revocation: SyncVersionRef, val recipient: String, val recipientKeyHash: String, val plaintext: SyncPlaintextIdentity) {
    init { require(format == 1 && recipient.matches(Regex("[a-f0-9]{64}")) && recipientKeyHash.matches(Regex("[a-f0-9]{64}")) &&
        plaintext.length in 1..SyncLedgerNotice.MAX_PLAIN.toLong()) { "SYNC_LEDGER_NOTICE_CONTEXT" } }
    fun bytes(): ByteArray = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
        it.writeUTF("CCB-SYNC-LEDGER-NOTICE/1"); it.writeUTF(objectId.value); it.writeUTF(group.value)
        it.writeUTF(revocation.id.value); it.writeUTF(revocation.bodyHash); it.writeUTF(recipient); it.writeUTF(recipientKeyHash)
        it.writeLong(plaintext.length); it.writeUTF(plaintext.sha256)
    } }.toByteArray()
    internal fun cell(index: Int): ByteArray = bytes() + java.nio.ByteBuffer.allocate(4).putInt(index).array()
}
class SyncVerifiedLedgerNotice internal constructor(val recipient: String, val records: List<SyncDeviceLedgerRecord>)

/** Recipient-only signed ledger ancestry, never a new group data key. */
@Serializable data class SyncLedgerNotice(val context: SyncLedgerNoticeContext, val cells: List<String>, val signature: String) {
    init {
        require(cells.size.toLong() == (context.plaintext.length + CELL - 1) / CELL && signature.length in 1..256 &&
            cells.all { it.length in 1..48 * 1024 && it.matches(Regex("[A-Za-z0-9+/]+={0,2}")) }) { "SYNC_LEDGER_NOTICE_BUDGET" }
    }
    fun signingBytes(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256"); digest.update(context.bytes())
        cells.forEach { cell -> val bytes = Base64.getDecoder().decode(cell)
            require(bytes.size <= 64 * 1024) { "SYNC_LEDGER_NOTICE_BUDGET" }
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
        }
        return ("CCB-SYNC-LEDGER-NOTICE-SIGNATURE/1\n" + digest.digest().joinToString("") { "%02x".format(it) }).toByteArray(Charsets.US_ASCII)
    }
    fun verifyOwner(trust: SyncGroupTrust, key: CiphertextObjectKey) {
        require(context.group == trust.group && key == CiphertextObjectKey(CiphertextObjectKind.COMMIT, context.objectId)) { "SYNC_LEDGER_NOTICE_CONTEXT" }
        require(SyncDeviceSignatures.decode(trust.ownerCertificate).verifies(signingBytes(), signature)) { "SYNC_LEDGER_NOTICE_SIGNATURE" }
    }
    fun open(trust: SyncGroupTrust, recipient: SyncDeviceEncryptionKey, key: CiphertextObjectKey): SyncVerifiedLedgerNotice {
        verifyOwner(trust, key)
        require(context.recipient == recipient.device && context.recipientKeyHash == recipient.publicKey.hash) { "SYNC_LEDGER_NOTICE_RECIPIENT" }
        val plain = ByteArray(context.plaintext.length.toInt())
        try {
            var offset = 0
            cells.forEachIndexed { index, cell ->
                val block = recipient.decrypt(Base64.getDecoder().decode(cell), context.cell(index))
                try {
                    require(block.size == minOf(CELL, plain.size - offset)) { "SYNC_LEDGER_NOTICE_LENGTH" }
                    block.copyInto(plain, offset); offset += block.size
                } finally { block.fill(0) }
            }
            require(identity(plain) == context.plaintext) { "SYNC_LEDGER_NOTICE_CHANGED" }
            val document = lanSyncJson.decodeFromString(Document.serializer(), lanSyncUtf8(plain))
            require(document.format == 1 && document.records.isNotEmpty()) { "SYNC_LEDGER_NOTICE_FORMAT" }
            // Validate the historical prefix from pinned genesis; custody still enforces its newer checkpoint.
            val ledger = SyncDeviceLedger(trust.copy(checkpoint = trust.genesis))
            document.records.forEach { ledger.offer(it) }
            require(ledger.current().reference() == context.revocation && ledger.history() == document.records &&
                revocations(ledger)[recipient.device] == context.revocation &&
                ledger.current().members[recipient.device]?.let { SyncRecipientPublicKey.parse(it.recipientKey).hash == context.recipientKeyHash } == true) { "SYNC_LEDGER_NOTICE_REVOCATION" }
            return SyncVerifiedLedgerNotice(recipient.device, ledger.history())
        } finally { plain.fill(0) }
    }
    companion object {
        const val MAX_PLAIN = 16 * 1024 * 1024
        private const val CELL = 24 * 1024
        @Serializable private data class Document(val format: Int, val records: List<SyncDeviceLedgerRecord>)
        /** First revocation only: later notices must not disclose future roster changes to removed devices. */
        fun revocations(ledger: SyncDeviceLedger): Map<String, SyncVersionRef> {
            val result = linkedMapOf<String, SyncVersionRef>(); val history = ledger.history()
            history.zipWithNext().forEach { (before, after) -> after.members.forEach { (device, member) ->
                if (!member.active && before.members[device]?.active == true) result.putIfAbsent(device, after.reference())
            } }
            return result
        }
        fun create(ledger: SyncDeviceLedger, recipient: String, objectId: SyncStoreId, sign: (ByteArray) -> ByteArray): SyncLedgerNotice {
            val revision = requireNotNull(revocations(ledger)[recipient]) { "SYNC_LEDGER_NOTICE_NOT_REVOKED" }
            val rows = ledger.history(revision).map { row -> row.copy(members = row.members.toSortedMap(), coveredTypes = row.coveredTypes.toSortedSet(),
                acceptedHeads = row.acceptedHeads.sortedBy { it.id.value }.toCollection(linkedSetOf())) }
            val public = SyncRecipientPublicKey.parse(rows.last().members.getValue(recipient).recipientKey)
            val plain = lanSyncJson.encodeToString(Document.serializer(), Document(1, rows)).toByteArray(Charsets.UTF_8)
            try {
                require(plain.size in 1..MAX_PLAIN) { "SYNC_LEDGER_NOTICE_BUDGET" }
                val context = SyncLedgerNoticeContext(1, objectId, ledger.trustAnchor.group, revision, recipient, public.hash, identity(plain))
                val cells = mutableListOf<String>(); var offset = 0
                while (offset < plain.size) {
                    val bytes = plain.copyOfRange(offset, minOf(offset + CELL, plain.size))
                    try { cells += Base64.getEncoder().encodeToString(public.encrypt(bytes, context.cell(cells.size))) } finally { bytes.fill(0) }
                    offset += bytes.size
                }
                val unsigned = SyncLedgerNotice(context, cells, "unsigned")
                return unsigned.copy(signature = Base64.getEncoder().encodeToString(sign(unsigned.signingBytes()))).also {
                    it.verifyOwner(ledger.trustAnchor, CiphertextObjectKey(CiphertextObjectKind.COMMIT, objectId))
                }
            } finally { plain.fill(0) }
        }
        private fun identity(bytes: ByteArray) = SyncPlaintextIdentity(bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    }
}

object SyncLedgerNoticeCodec {
    private val magic = "CCB-SYNC-LEDGER-NOTICE/1\n".toByteArray(Charsets.US_ASCII)
    const val MAX_BYTES = 24 * 1024 * 1024
    internal fun hasPrefix(bytes: ByteArray) = bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }
    fun encode(notice: SyncLedgerNotice): ByteArray = (magic + lanSyncJson.encodeToString(SyncLedgerNotice.serializer(), notice).toByteArray(Charsets.UTF_8)).also {
        require(it.size <= MAX_BYTES) { "SYNC_LEDGER_NOTICE_BUDGET" }
    }
    fun decode(bytes: ByteArray): SyncLedgerNotice {
        require(bytes.size in magic.size + 1..MAX_BYTES && hasPrefix(bytes)) { "SYNC_LEDGER_NOTICE_FORMAT" }
        val text = lanSyncUtf8(bytes.copyOfRange(magic.size, bytes.size))
        var depth = 0; var quoted = false; var escaped = false; var strings = 0; var characters = 0
        text.forEach { char ->
            if (quoted) {
                characters++; require(characters <= 64 * 1024) { "SYNC_LEDGER_NOTICE_BUDGET" }
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> { quoted = true; characters = 0; strings++; require(strings <= 1024) { "SYNC_LEDGER_NOTICE_BUDGET" } }
                '{', '[' -> { depth++; require(depth <= 8) { "SYNC_LEDGER_NOTICE_BUDGET" } }
                '}', ']' -> depth--
            }
            require(depth >= 0) { "SYNC_LEDGER_NOTICE_FORMAT" }
        }
        require(!quoted && depth == 0) { "SYNC_LEDGER_NOTICE_FORMAT" }
        return lanSyncJson.decodeFromString(SyncLedgerNotice.serializer(), text)
    }
    internal fun read(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val count = input.read(buffer); if (count < 0) return output.toByteArray()
            require(output.size() <= MAX_BYTES - count) { "SYNC_LEDGER_NOTICE_BUDGET" }; output.write(buffer, 0, count) }
    }
}
