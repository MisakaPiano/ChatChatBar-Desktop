package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.SyncPlaintextIdentity
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Small streamed native record-identity list, separate from historical text/media payloads. */
object LanSyncGroupMembership {
    private val magic = "CCB-SYNC-NATIVE-MEMBERS/1\n".toByteArray(Charsets.US_ASCII)
    private fun encode(records: List<LanSyncRecord>, output: OutputStream) {
        output.write(magic)
        records.map { it.key }.sorted().forEach { key -> output.write((JsonPrimitive(key).toString() + "\n").toByteArray(Charsets.UTF_8)) }
    }
    fun identity(records: List<LanSyncRecord>): SyncPlaintextIdentity {
        val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
        val output = object : OutputStream() {
            override fun write(value: Int) { digest.update(value.toByte()); count = lanSyncAddLength(count, 1) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { digest.update(bytes, offset, length); count = lanSyncAddLength(count, length.toLong()) }
        }
        encode(records, output)
        return SyncPlaintextIdentity(count, digest.digest().joinToString("") { "%02x".format(it) })
    }
    internal fun write(path: Path, records: List<LanSyncRecord>): SyncPlaintextIdentity {
        lanSyncSafe(path)
        FileChannel.open(path, WRITE, NOFOLLOW_LINKS).use { channel ->
            val output = Channels.newOutputStream(channel).buffered(64 * 1024); encode(records, output); output.flush(); channel.force(true)
        }
        val expected = identity(records)
        require(Files.size(path) == expected.length && LanSyncResourceStore.hashFile(path, expected.length) == expected.sha256) { "SYNC_GROUP_MEMBERSHIP_CHANGED" }
        return expected
    }
    /** Caller supplies a complete authenticated/hash-verified object; this also verifies its exact identity. */
    fun visit(path: Path, expected: SyncPlaintextIdentity, onKey: (String) -> Unit) {
        require(Files.size(path) == expected.length && LanSyncResourceStore.hashFile(path, expected.length) == expected.sha256) { "SYNC_GROUP_MEMBERSHIP_CHANGED" }
        Files.newInputStream(path, NOFOLLOW_LINKS).buffered(64 * 1024).use { input ->
            val header = ByteArray(magic.size); java.io.DataInputStream(input).readFully(header)
            require(header.contentEquals(magic)) { "SYNC_GROUP_MEMBERSHIP_FORMAT" }
            var previous: String? = null; var count = 0L
            while (true) {
                val line = java.io.ByteArrayOutputStream(); var end = false
                while (true) {
                    val byte = input.read()
                    if (byte < 0) { require(line.size() == 0) { "SYNC_GROUP_MEMBERSHIP_TRUNCATED" }; end = true; break }
                    if (byte == 10) break
                    require(line.size() < 8192) { "SYNC_GROUP_MEMBERSHIP_KEY_BUDGET" }; line.write(byte)
                }
                if (end) break
                val value = lanSyncJson.parseToJsonElement(lanSyncUtf8(line.toByteArray())).jsonPrimitive
                require(value.isString) { "SYNC_GROUP_MEMBERSHIP_KEY" }; val key = value.content
                require(key.length in 1..600 && key.substringBefore('/') in LanSyncScope.schemas && key.substringAfter('/', "").isNotBlank() &&
                    key.none { it.code < 32 } && previous?.let { it < key } != false) { "SYNC_GROUP_MEMBERSHIP_KEY" }
                previous = key; count = lanSyncAddLength(count, 1); onKey(key)
            }
            require(count > 0) { "SYNC_GROUP_MEMBERSHIP_EMPTY" }
        }
    }
}
