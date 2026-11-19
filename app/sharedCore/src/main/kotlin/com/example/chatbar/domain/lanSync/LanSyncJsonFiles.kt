package com.example.chatbar.domain.lanSync

import java.io.InputStreamReader
import java.io.InputStream
import java.io.FilterInputStream
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

/** Streaming local metadata; network frame budgets are not a limit on persisted JSON documents. */
@OptIn(ExperimentalSerializationApi::class)
internal object LanSyncJsonFiles {
    fun <T> read(path: Path, serializer: KSerializer<T>, expectedHash: String? = null): T? =
        readChecked(path, expectedHash, null, check = { reader ->
            val buffer = CharArray(8192)
            while (reader.read(buffer) >= 0) { /* Validate without retaining a complete text/byte copy. */ }
        }) { input, _ -> lanSyncJson.decodeFromStream(serializer, input) }

    fun readEntity(path: Path, maximumBytes: Int, budget: LanSyncMetadataBudget): Pair<JsonObject, String> {
        // Freeze this byte-bounded record before preflight, decode, and raw source hashing.
        val bytes = lanSyncRead(path, maximumBytes)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        try { InputStreamReader(bytes.inputStream(), text).use { budget.validate(it) } }
        catch (failure: CharacterCodingException) { throw IllegalArgumentException("SYNC_INVALID_UTF8", failure) }
        return lanSyncJson.decodeFromStream(JsonObject.serializer(), bytes.inputStream()) to lanSyncHash(bytes)
    }

    fun <T> readRecords(path: Path, serializer: KSerializer<T>, bodies: LanSyncRecordBodies, recordPath: List<String>,
        expectedHash: String? = null, expectedLength: Long? = null,
        acceptPreviousWholeDocumentBudget: Boolean = false, observe: (LanSyncRecordPreflight.Usage) -> Unit = {}): T? {
        val legacy = if (acceptPreviousWholeDocumentBudget) {
            require(expectedHash != null && expectedLength != null)
            val budget = bodies.budget
            val compatible = budget.copy(maximumIndexCost = lanSyncAddLength(budget.maximumDecodedCost, 512L * 100_000))
            PreviousDocumentBudget(budget) { LanSyncRecordPreflight(it, compatible, recordPath).validate() }
        } else null
        return readChecked(path, expectedHash, expectedLength, previous = legacy, check = { reader ->
            observe(LanSyncRecordPreflight(reader, bodies.budget, recordPath).validate())
        }) { input, _ ->
            LanSyncRecordSerializer.retaining(bodies) { lanSyncJson.decodeFromStream(serializer, input) }
        }
    }

    private data class PreviousDocumentBudget(val budget: LanSyncMetadataBudget, val checkRecords: (InputStreamReader) -> Unit)

    private fun <T> readChecked(path: Path, expectedHash: String?, expectedLength: Long?, previous: PreviousDocumentBudget? = null,
        check: (InputStreamReader) -> Unit, decode: (InputStream, String) -> T): T? {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        lanSyncSafe(path)
        return FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            require(expectedLength == null || channel.size() == expectedLength) { "SYNC_METADATA_DAMAGED" }
            fun validatedDigest(validate: (InputStreamReader) -> Unit): ByteArray {
                channel.position(0)
                val digest = MessageDigest.getInstance("SHA-256")
                val input = object : FilterInputStream(Channels.newInputStream(channel)) {
                    override fun close() = Unit // The outer file channel owns this stream.
                }
                try {
                    strictUtf8(DigestInputStream(input, digest)).use(validate)
                } catch (failure: CharacterCodingException) { throw IllegalArgumentException("SYNC_INVALID_UTF8", failure) }
                require(expectedLength == null || channel.size() == expectedLength) { "SYNC_METADATA_SOURCE_CHANGED" }
                return digest.digest()
            }
            val expected = try { validatedDigest(check) } catch (failure: IllegalArgumentException) {
                if (previous == null || failure.message != "SYNC_METADATA_INDEX_BUDGET") throw failure
                // Existing native/resolution documents were admitted under a whole-document
                // budget. Retain that budget and independently check the record structure.
                val oldDigest = validatedDigest { previous.budget.validate(it) }
                require(MessageDigest.isEqual(oldDigest, validatedDigest(previous.checkRecords))) { "SYNC_METADATA_SOURCE_CHANGED" }
                oldDigest
            }
            val hash = expected.joinToString("") { "%02x".format(it) }
            if (expectedHash != null) require(hash == expectedHash) { "SYNC_METADATA_DAMAGED" }
            channel.position(0)
            val secondDigest = MessageDigest.getInstance("SHA-256")
            val input = DigestInputStream(Channels.newInputStream(channel), secondDigest).buffered()
            val decoded = decode(input, hash)
            while (input.read() >= 0) { /* Complete the exact-file hash even if the decoder buffered EOF. */ }
            require(MessageDigest.isEqual(expected, secondDigest.digest())) { "SYNC_METADATA_SOURCE_CHANGED" }
            decoded
        }
    }

    private fun strictUtf8(input: InputStream) = InputStreamReader(input, Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))

    fun <T> write(path: Path, serializer: KSerializer<T>, value: T) = write(path) { lanSyncJson.encodeToStream(serializer, value, it) }

    fun <T> writeRecords(path: Path, serializer: KSerializer<T>, value: T, budget: LanSyncMetadataBudget,
        recordPath: List<String>) = writeChecked(path, check = { prepared ->
        try {
            strictUtf8(Files.newInputStream(prepared, LinkOption.NOFOLLOW_LINKS)).use {
                LanSyncRecordPreflight(it, budget, recordPath).validate()
            }
        } catch (failure: CharacterCodingException) { throw IllegalArgumentException("SYNC_INVALID_UTF8", failure) }
    }) { lanSyncJson.encodeToStream(serializer, value, it) }

    fun write(path: Path, encode: (OutputStream) -> Unit) = writeChecked(path, encode = encode)

    private fun writeChecked(path: Path, check: ((Path) -> Unit)? = null, encode: (OutputStream) -> Unit) {
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
            if (check != null) {
                lanSyncSafe(temporary)
                val before = Files.readAttributes(temporary, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                require(before.fileKey() == key) { "SYNC_METADATA_PREPARATION_CHANGED" }
                check(temporary)
                val after = Files.readAttributes(temporary, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                require(after.fileKey() == key && before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime()) {
                    "SYNC_METADATA_PREPARATION_CHANGED"
                }
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
