package com.example.chatbar.domain.syncStore.webdav

import com.example.chatbar.domain.syncStore.*
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Framing of already encrypted bytes, not an encryption format or authenticated trust witness. */
internal object WebDavStoreRecord {
    const val HEADER_BYTES = 78L
    private const val MAGIC = 0x43434456

    fun header(metadata: CiphertextObjectMetadata): ByteArray {
        requireLength(metadata.descriptor.length)
        return ByteBuffer.allocate(HEADER_BYTES.toInt()).apply {
            putInt(MAGIC); put(1.toByte())
            put(if (metadata.key.kind == CiphertextObjectKind.BLOB) 1.toByte() else 2.toByte())
            put(metadata.key.id.value.toByteArray(Charsets.US_ASCII))
            putLong(metadata.descriptor.length)
            put(decodeHash(metadata.descriptor.sha256.hex))
        }.array()
    }

    fun readHeader(source: InputStream, expectedKey: CiphertextObjectKey): CiphertextObjectMetadata {
        try {
            val input = DataInputStream(source)
            if (input.readInt() != MAGIC || input.readUnsignedByte() != 1) fail()
            val kind = when (input.readUnsignedByte()) { 1 -> CiphertextObjectKind.BLOB; 2 -> CiphertextObjectKind.COMMIT; else -> fail() }
            val id = ByteArray(32).also { input.readFully(it) }
            val key = try { CiphertextObjectKey(kind, SyncStoreId.parse(String(id, Charsets.US_ASCII))) }
                catch (_: IllegalArgumentException) { fail() }
            if (key != expectedKey) fail()
            val length = input.readLong()
            if (length < 0 || length > Long.MAX_VALUE - HEADER_BYTES) fail()
            val digest = ByteArray(32).also { input.readFully(it) }
            return CiphertextObjectMetadata(key, CiphertextDescriptor(length, CiphertextSha256.parse(hashHex(digest))))
        } catch (_: EOFException) { fail() }
    }

    fun requireLength(length: Long): Long {
        if (length < 0 || length > Long.MAX_VALUE - HEADER_BYTES) {
            WebDavHttpTransport.fail(CiphertextStoreFailure.UNSUPPORTED)
        }
        return length + HEADER_BYTES
    }

    fun transfer(source: InputStream, destination: OutputStream, descriptor: CiphertextDescriptor, bufferBytes: Int) {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(bufferBytes)
        var remaining = descriptor.length
        while (remaining > 0) {
            interrupted()
            val wanted = minOf(remaining, buffer.size.toLong()).toInt()
            var count = source.read(buffer, 0, wanted)
            if (count == 0) {
                val single = source.read()
                if (single < 0) fail()
                buffer[0] = single.toByte(); count = 1
            }
            if (count < 0 || count > wanted) fail()
            destination.write(buffer, 0, count); hash.update(buffer, 0, count); remaining -= count.toLong()
        }
        interrupted()
        if (source.read() != -1 || hashHex(hash.digest()) != descriptor.sha256.hex) fail()
    }

    fun checksum(bytes: ByteArray): CiphertextDescriptor = CiphertextDescriptor(bytes.size.toLong(),
        CiphertextSha256.parse(hashHex(MessageDigest.getInstance("SHA-256").digest(bytes))))

    fun operationKey(domain: String, operation: SyncStoreId): SyncStoreId = SyncStoreId.parse(hashHex(
        MessageDigest.getInstance("SHA-256").digest("CCB-DAV-$domain/1\n${operation.value}".toByteArray(Charsets.US_ASCII)),
    ).take(32))

    fun opaqueKey(domain: String, value: String): SyncStoreId = SyncStoreId.parse(hashHex(
        MessageDigest.getInstance("SHA-256").digest("CCB-DAV-$domain/1\n$value".toByteArray(Charsets.UTF_8)),
    ).take(32))

    fun interrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SYNC_DAV_INTERRUPTED")
    }

    private fun hashHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun decodeHash(hex: String) = ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun fail(): Nothing = WebDavHttpTransport.fail(CiphertextStoreFailure.INTEGRITY)
    val discard = object : OutputStream() { override fun write(value: Int) {}; override fun write(bytes: ByteArray, offset: Int, length: Int) {} }
}
