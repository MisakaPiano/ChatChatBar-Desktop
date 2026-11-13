package com.example.chatbar.domain.syncStore

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream

/** Discovery hints only; none of these public opaque fields grants authenticity or freshness. */
data class SyncCiphertextHint(val context: SyncEncryptionContext, val keyCommitment: String)

object SyncCiphertextHeader {
    private val MAGIC = "CCB-SYNC-AEAD/2\n".toByteArray(Charsets.US_ASCII)
    val size: Int get() = MAGIC.size + 1 + 32 * 3 + 64

    internal fun encode(context: SyncEncryptionContext, keyCommitment: String): ByteArray {
        require(keyCommitment.matches(Regex("[a-f0-9]{64}"))) { "SYNC_AEAD_HEADER" }
        return ByteArrayOutputStream(size).also { output ->
            output.write(MAGIC); output.write(context.key.kind.storageCode)
            for (id in listOf(context.group, context.epoch, context.key.id)) output.write(id.value.toByteArray(Charsets.US_ASCII))
            output.write(keyCommitment.toByteArray(Charsets.US_ASCII))
        }.toByteArray()
    }

    /** Consumes exactly the fixed header; does not close input or claim that its bytes authenticate. */
    fun peek(input: InputStream): SyncCiphertextHint = parse(ByteArray(size).also { DataInputStream(input).readFully(it) })

    internal fun requireExpected(input: InputStream, context: SyncEncryptionContext, commitment: String): ByteArray {
        val actual = ByteArray(size).also { DataInputStream(input).readFully(it) }
        parse(actual)
        require(actual.contentEquals(encode(context, commitment))) { "SYNC_AEAD_CONTEXT" }
        return actual
    }

    private fun parse(bytes: ByteArray): SyncCiphertextHint {
        require(bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "SYNC_AEAD_HEADER" }
        var offset = MAGIC.size
        val code = bytes[offset++].toInt() and 255
        val kind = requireNotNull(CiphertextObjectKind.entries.firstOrNull { it.storageCode == code }) { "SYNC_AEAD_HEADER" }
        fun text(size: Int) = String(bytes, offset, size, Charsets.US_ASCII).also { offset += size }
        val group = SyncStoreId.parse(text(32)); val epoch = SyncStoreId.parse(text(32)); val id = SyncStoreId.parse(text(32))
        val commitment = text(64); require(commitment.matches(Regex("[a-f0-9]{64}"))) { "SYNC_AEAD_HEADER" }
        return SyncCiphertextHint(SyncEncryptionContext(group, epoch, CiphertextObjectKey(kind, id)), commitment)
    }
}
