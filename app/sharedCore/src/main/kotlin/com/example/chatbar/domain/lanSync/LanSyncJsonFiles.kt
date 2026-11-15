package com.example.chatbar.domain.lanSync

import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

/** Streaming local metadata; network frame budgets are not a limit on persisted JSON documents. */
@OptIn(ExperimentalSerializationApi::class)
internal object LanSyncJsonFiles {
    fun <T> read(path: Path, serializer: KSerializer<T>, expectedHash: String? = null): T? {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        lanSyncSafe(path)
        return FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val firstDigest = MessageDigest.getInstance("SHA-256")
            val reader = InputStreamReader(DigestInputStream(Channels.newInputStream(channel), firstDigest),
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
            try {
                try {
                    val buffer = CharArray(8192)
                    while (reader.read(buffer) >= 0) { /* Validate without retaining a complete text/byte copy. */ }
                } catch (failure: CharacterCodingException) { throw IllegalArgumentException("SYNC_INVALID_UTF8", failure) }
                val expected = firstDigest.digest()
                if (expectedHash != null) require(expected.joinToString("") { "%02x".format(it) } == expectedHash) { "SYNC_METADATA_DAMAGED" }
                channel.position(0)
                val secondDigest = MessageDigest.getInstance("SHA-256")
                val input = DigestInputStream(Channels.newInputStream(channel), secondDigest).buffered()
                val decoded = lanSyncJson.decodeFromStream(serializer, input)
                while (input.read() >= 0) { /* Complete the exact-file hash even if the decoder buffered EOF. */ }
                require(MessageDigest.isEqual(expected, secondDigest.digest())) { "SYNC_METADATA_SOURCE_CHANGED" }
                decoded
            } finally { reader.close() }
        }
    }

    fun <T> write(path: Path, serializer: KSerializer<T>, value: T) = write(path) { lanSyncJson.encodeToStream(serializer, value, it) }

    fun write(path: Path, encode: (OutputStream) -> Unit) {
        lanSyncSafe(requireNotNull(path.parent), true)
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) lanSyncSafe(path)
        val temporary = path.resolveSibling(".${UUID.randomUUID()}.json-preparation")
        var key: Any? = null
        var moved = false
        try {
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                key = Files.readAttributes(temporary, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
                val destination = Channels.newOutputStream(channel)
                var count = 0L; var checkedAt = 0L
                val budget = LanSyncResourceBudget()
                budget.requireSpace(path.parent, 0)
                val checked = object : OutputStream() {
                    private val single = ByteArray(1)
                    override fun write(value: Int) { single[0] = value.toByte(); write(single, 0, 1) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                        count = lanSyncAddLength(count, length.toLong())
                        if (count - checkedAt >= 1024 * 1024) { budget.requireSpace(path.parent, 1024 * 1024); checkedAt = count }
                        destination.write(bytes, offset, length)
                    }
                }
                val output = checked.buffered(64 * 1024)
                encode(output); output.flush(); channel.force(true)
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            moved = true
        } finally {
            if (!moved && key != null && Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                lanSyncSafe(temporary)
                require(Files.readAttributes(temporary, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey() == key) { "SYNC_METADATA_PREPARATION_CHANGED" }
                Files.delete(temporary)
            }
        }
    }
}
