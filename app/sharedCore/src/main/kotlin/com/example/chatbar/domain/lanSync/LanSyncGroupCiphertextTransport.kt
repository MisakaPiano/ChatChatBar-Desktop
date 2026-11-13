package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLSocket
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@Serializable private enum class GroupStoreCommand { METADATA, LIST, GET, PUT }
@Serializable private data class GroupStoreRequest(val protocol: String, val group: SyncStoreId, val command: GroupStoreCommand,
    val key: CiphertextObjectKey? = null, val operation: SyncStoreId? = null, val descriptor: CiphertextDescriptor? = null,
    val cursor: String? = null, val pageSize: Int = 100, val offset: Long = 0)
@Serializable private data class GroupStoreObject(val key: CiphertextObjectKey, val descriptor: CiphertextDescriptor) {
    fun metadata() = CiphertextObjectMetadata(key, descriptor)
}
@Serializable private data class GroupStoreReply(val protocol: String = GroupStoreWire.PROTOCOL, val error: String? = null,
    val item: GroupStoreObject? = null, val entries: List<GroupStoreObject> = emptyList(), val cursor: String? = null,
    val offset: Long = 0, val length: Int = 0, val hash: String? = null, val done: Boolean = false,
    val disposition: CiphertextPutDisposition? = null)
@Serializable private data class GroupStoreChunk(val offset: Long, val length: Int = 0, val hash: String? = null, val done: Boolean = false)

private object GroupStoreWire {
    const val PROTOCOL = "CCB_GROUP_STORE_1"
    const val CONTROL_BYTES = 512 * 1024
    const val CHUNK_BYTES = 1024 * 1024
    fun <T> send(wire: LanSyncWire, serializer: KSerializer<T>, value: T) {
        val bytes = lanSyncJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
        require(bytes.size <= CONTROL_BYTES) { "SYNC_GROUP_WIRE_CONTROL_BUDGET" }; wire.send(bytes)
    }
    fun <T> read(wire: LanSyncWire, serializer: KSerializer<T>): T {
        val text = wire.text(CONTROL_BYTES)
        var depth = 0; var quoted = false; var escaped = false; var length = 0
        text.forEach { char ->
            if (quoted) {
                length++; require(length <= 8192) { "SYNC_GROUP_WIRE_CONTROL_BUDGET" }
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> { quoted = true; length = 0 }
                '{', '[' -> { depth++; require(depth <= 8) { "SYNC_GROUP_WIRE_CONTROL_BUDGET" } }
                '}', ']' -> depth--
            }
            require(depth >= 0) { "SYNC_GROUP_WIRE_CONTROL_FORMAT" }
        }
        require(!quoted && depth == 0) { "SYNC_GROUP_WIRE_CONTROL_FORMAT" }
        return lanSyncJson.decodeFromString(serializer, text)
    }
    fun reply(wire: LanSyncWire, value: GroupStoreReply) = send(wire, GroupStoreReply.serializer(), value)
    fun reply(wire: LanSyncWire): GroupStoreReply = read(wire, GroupStoreReply.serializer()).also {
        require(it.protocol == PROTOCOL) { "SYNC_GROUP_WIRE_PROTOCOL" }
        it.error?.let { code ->
            if (code.startsWith("STORE_")) throw CiphertextStoreException(requireNotNull(
                CiphertextStoreFailure.entries.firstOrNull { failure -> failure.name == code.removePrefix("STORE_") }) { "SYNC_GROUP_WIRE_ERROR_FORMAT" })
            require(code.matches(Regex("SYNC_[A-Z0-9_]{1,100}"))) { "SYNC_GROUP_WIRE_ERROR_FORMAT" }; error(code)
        }
    }
    fun chunk(wire: LanSyncWire, offset: Long, bytes: ByteArray) {
        reply(wire, GroupStoreReply(offset = offset, length = bytes.size, hash = lanSyncHash(bytes)))
        wire.sendBlob(bytes.inputStream(), bytes.size.toLong())
    }
    fun bytes(wire: LanSyncWire, length: Int, hash: String?): ByteArray {
        require(length in 1..CHUNK_BYTES && hash?.matches(Regex("[a-f0-9]{64}")) == true) { "SYNC_GROUP_WIRE_CHUNK" }
        return ByteArrayOutputStream(length).also { wire.receiveBlob(it, length.toLong(), requireNotNull(hash)) }.toByteArray()
    }
    fun objectOf(metadata: CiphertextObjectMetadata) = GroupStoreObject(metadata.key, metadata.descriptor)
    fun errorCode(failure: Exception): String {
        if (failure is InterruptedIOException || failure is InterruptedException || failure is CancellationException) throw failure
        return if (failure is CiphertextStoreException) "STORE_${failure.failure}"
        else failure.message?.takeIf { it.matches(Regex("SYNC_[A-Z0-9_]{1,100}")) } ?: "STORE_IO"
    }
}

/**
 * One request per mutually authenticated TLS connection. The app owns the listener, routing and
 * socket lifecycle; resolve receives certificates extracted from the real session, never wire claims.
 */
class LanSyncGroupCiphertextServer(private val privateRoot: Path,
    private val resolve: (SyncStoreId, String, String) -> LanSyncGroupPeerStore,
    private val observe: (String, Long) -> Unit = { _, _ -> }) {
    private val cache = LanSyncResourceStore(privateRoot.resolve("ciphertext-cache"))
    fun serve(socket: SSLSocket) {
        socket.use {
            LanSyncTls.configure(socket); socket.startHandshake()
            LanSyncWire(socket).use { wire ->
                try {
                    val request = GroupStoreWire.read(wire, GroupStoreRequest.serializer())
                    require(request.protocol == GroupStoreWire.PROTOCOL) { "SYNC_GROUP_WIRE_PROTOCOL" }
                    val local = requireNotNull(socket.session.localCertificates).single().encoded
                    val peer = socket.session.peerCertificates.single().encoded
                    val store = resolve(request.group, Base64.getEncoder().encodeToString(local), Base64.getEncoder().encodeToString(peer))
                    require(store.group == request.group) { "SYNC_GROUP_WIRE_GROUP" }
                    when (request.command) {
                        GroupStoreCommand.METADATA -> GroupStoreWire.reply(wire, GroupStoreReply(item =
                            store.metadata(requireNotNull(request.key))?.let(GroupStoreWire::objectOf)))
                        GroupStoreCommand.LIST -> {
                            require(request.pageSize in 1..100) { "SYNC_GROUP_WIRE_PAGE_SIZE" }
                            val page = store.listCommits(request.cursor?.let(::CommitCursor), request.pageSize)
                            GroupStoreWire.reply(wire, GroupStoreReply(entries = page.entries.map(GroupStoreWire::objectOf), cursor = page.nextCursor?.token))
                        }
                        GroupStoreCommand.GET -> download(wire, request, store)
                        GroupStoreCommand.PUT -> upload(wire, request, store)
                    }
                } catch (failure: Exception) {
                    val code = GroupStoreWire.errorCode(failure); observe(code, 0)
                    GroupStoreWire.reply(wire, GroupStoreReply(error = code))
                }
            }
        }
    }
    private fun upload(wire: LanSyncWire, request: GroupStoreRequest, store: LanSyncGroupPeerStore) {
        val key = requireNotNull(request.key); val descriptor = requireNotNull(request.descriptor)
        val put = CiphertextPutRequest(key, requireNotNull(request.operation), descriptor)
        store.requireWriteAllowed(key)
        if (descriptor.length == 0L) {
            GroupStoreWire.reply(wire, GroupStoreReply())
            val done = GroupStoreWire.read(wire, GroupStoreChunk.serializer()); require(done.done && done.offset == 0L) { "SYNC_GROUP_WIRE_DONE" }
            val result = store.put(put, byteArrayOf().inputStream())
            GroupStoreWire.reply(wire, GroupStoreReply(item = GroupStoreWire.objectOf(result.metadata), done = true, disposition = result.disposition)); return
        }
        cache.open(descriptor.sha256.hex, descriptor.length).use { partial ->
            GroupStoreWire.reply(wire, GroupStoreReply(offset = partial.offset)); observe("CIPHERTEXT_UPLOAD_RESUME", partial.offset)
            while (partial.offset < descriptor.length) {
                val chunk = GroupStoreWire.read(wire, GroupStoreChunk.serializer())
                require(!chunk.done && chunk.offset == partial.offset && chunk.length.toLong() ==
                    minOf(GroupStoreWire.CHUNK_BYTES.toLong(), descriptor.length - partial.offset)) { "SYNC_GROUP_WIRE_CHUNK" }
                store.requireWriteAllowed(key)
                val bytes = GroupStoreWire.bytes(wire, chunk.length, chunk.hash)
                store.requireWriteAllowed(key); partial.append(chunk.offset, bytes, requireNotNull(chunk.hash))
                GroupStoreWire.reply(wire, GroupStoreReply(offset = partial.offset)); observe("CIPHERTEXT_CHUNK_RETAINED", partial.offset)
            }
            val done = GroupStoreWire.read(wire, GroupStoreChunk.serializer())
            require(done.done && done.offset == descriptor.length) { "SYNC_GROUP_WIRE_DONE" }
            store.requireWriteAllowed(key)
            val path = partial.finish()
            val result = Files.newInputStream(path, NOFOLLOW_LINKS).use { store.put(put, it) }
            GroupStoreWire.reply(wire, GroupStoreReply(item = GroupStoreWire.objectOf(result.metadata), done = true, disposition = result.disposition))
        }
    }
    private fun download(wire: LanSyncWire, request: GroupStoreRequest, store: LanSyncGroupPeerStore) {
        val key = requireNotNull(request.key); val expected = requireNotNull(request.descriptor)
        val metadata = store.metadata(key)
        if (metadata == null) { GroupStoreWire.reply(wire, GroupStoreReply(done = true)); return }
        require(metadata.descriptor == expected && request.offset in 0..expected.length &&
            (request.offset == expected.length || request.offset % GroupStoreWire.CHUNK_BYTES == 0L)) { "SYNC_GROUP_WIRE_OBJECT_CHANGED" }
        if (expected.length == 0L) { GroupStoreWire.reply(wire, GroupStoreReply(item = GroupStoreWire.objectOf(metadata), done = true)); return }
        val path = cached(store, metadata)
        observe("CIPHERTEXT_DOWNLOAD_RESUME", request.offset)
        GroupStoreWire.reply(wire, GroupStoreReply(item = GroupStoreWire.objectOf(metadata), offset = request.offset))
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            var offset = request.offset; channel.position(offset)
            while (offset < expected.length) {
                store.requireReadAllowed(key)
                val bytes = ByteArray(minOf(GroupStoreWire.CHUNK_BYTES.toLong(), expected.length - offset).toInt())
                val block = ByteBuffer.wrap(bytes); while (block.hasRemaining()) require(channel.read(block) > 0) { "SYNC_GROUP_WIRE_CACHE_CHANGED" }
                GroupStoreWire.chunk(wire, offset, bytes); offset += bytes.size
                observe("CIPHERTEXT_CHUNK_SENT", offset)
            }
        }
        store.requireReadAllowed(key)
        GroupStoreWire.reply(wire, GroupStoreReply(item = GroupStoreWire.objectOf(metadata), offset = expected.length, done = true))
    }
    private fun cached(store: LanSyncGroupPeerStore, metadata: CiphertextObjectMetadata): Path {
        val descriptor = metadata.descriptor
        cache.completed(descriptor.sha256.hex, descriptor.length)?.let { return it }
        LanSyncResourceBudget().requireSpace(privateRoot, descriptor.length, copies = 2)
        return PrivateSyncFile.create(privateRoot).use { file ->
            FileChannel.open(file.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                var count = 0L
                val output = object : OutputStream() {
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        require(length >= 0 && count <= descriptor.length - length) { "SYNC_GROUP_WIRE_OBJECT_LENGTH" }
                        val buffer = ByteBuffer.wrap(bytes, offset, length); while (buffer.hasRemaining()) channel.write(buffer); count += length
                    }
                }
                require(store.readTo(metadata.key, output, descriptor) == metadata && count == descriptor.length) { "SYNC_GROUP_WIRE_OBJECT_CHANGED" }
                channel.force(true)
            }
            cache.retain(file.path, descriptor.sha256.hex, descriptor.length)
        }
    }
}

/** Already encrypted objects only. connect must enforce LAN address policy, pinning and client TLS identity. */
class LanSyncGroupRemoteStore(private val group: SyncStoreId, private val privateRoot: Path,
    private val connect: () -> SSLSocket, private val observe: (String, Long) -> Unit = { _, _ -> }) : CiphertextStore {
    private val cache = LanSyncResourceStore(privateRoot.resolve("ciphertext-cache"))
    override val capabilities = CiphertextStoreCapabilities(CommitListingSemantics.KEYSET_RESCAN_REQUIRED, resumableByteUpload = true)
    private fun request(command: GroupStoreCommand, key: CiphertextObjectKey? = null, operation: SyncStoreId? = null,
        descriptor: CiphertextDescriptor? = null, cursor: String? = null, pageSize: Int = 100, offset: Long = 0) =
        GroupStoreRequest(GroupStoreWire.PROTOCOL, group, command, key, operation, descriptor, cursor, pageSize, offset)
    private fun <T> exchange(request: GroupStoreRequest, work: (LanSyncWire) -> T): T = connect().use { socket ->
        LanSyncTls.configure(socket); socket.startHandshake()
        LanSyncWire(socket).use { wire -> GroupStoreWire.send(wire, GroupStoreRequest.serializer(), request); work(wire) }
    }
    override fun metadata(key: CiphertextObjectKey): CiphertextObjectMetadata? = exchange(request(GroupStoreCommand.METADATA, key)) { wire ->
        GroupStoreWire.reply(wire).item?.metadata()?.also { require(it.key == key) { "SYNC_GROUP_WIRE_OBJECT_CHANGED" } }
    }
    override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage {
        require(pageSize in 1..1000) { "SYNC_GROUP_WIRE_PAGE_SIZE" }
        return exchange(request(GroupStoreCommand.LIST, cursor = cursor?.token, pageSize = minOf(pageSize, 100))) { wire ->
            val reply = GroupStoreWire.reply(wire)
            require(reply.entries.size <= minOf(pageSize, 100) && reply.entries.all { it.key.kind == CiphertextObjectKind.COMMIT } &&
                reply.entries.map { it.key }.toSet().size == reply.entries.size && reply.cursor?.length?.let { it <= 4096 } != false) { "SYNC_GROUP_WIRE_LIST" }
            CommitPage(reply.entries.map { it.metadata() }, reply.cursor?.let(::CommitCursor))
        }
    }
    override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult =
        exchange(this.request(GroupStoreCommand.PUT, request.key, request.operationId, request.descriptor)) { wire ->
            val resume = GroupStoreWire.reply(wire).offset
            val length = request.descriptor.length
            require(resume in 0..length && (resume == length || resume % GroupStoreWire.CHUNK_BYTES == 0L)) { "SYNC_GROUP_WIRE_RESUME" }
            val digest = MessageDigest.getInstance("SHA-256"); var offset = 0L
            while (offset < length) {
                val bytes = ByteArray(minOf(GroupStoreWire.CHUNK_BYTES.toLong(), length - offset).toInt()); var count = 0
                while (count < bytes.size) { val read = source.read(bytes, count, bytes.size - count); require(read > 0) { "SYNC_GROUP_WIRE_SOURCE_LENGTH" }; count += read }
                digest.update(bytes)
                if (offset >= resume) {
                    GroupStoreWire.send(wire, GroupStoreChunk.serializer(), GroupStoreChunk(offset, bytes.size, lanSyncHash(bytes)))
                    wire.sendBlob(bytes.inputStream(), bytes.size.toLong())
                    require(GroupStoreWire.reply(wire).offset == offset + bytes.size) { "SYNC_GROUP_WIRE_RESUME" }
                    observe("CIPHERTEXT_CHUNK_SENT", offset + bytes.size)
                }
                offset += bytes.size
            }
            require(source.read() == -1 && digest.digest().joinToString("") { "%02x".format(it) } == request.descriptor.sha256.hex) { "SYNC_GROUP_WIRE_SOURCE_CHANGED" }
            GroupStoreWire.send(wire, GroupStoreChunk.serializer(), GroupStoreChunk(length, done = true))
            val result = GroupStoreWire.reply(wire)
            require(result.done && result.item?.metadata() == CiphertextObjectMetadata(request.key, request.descriptor)) { "SYNC_GROUP_WIRE_RECEIPT" }
            CiphertextPutResult(requireNotNull(result.item).metadata(), requireNotNull(result.disposition))
        }
    override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
        // Even an existing cache object cannot bypass the peer's current live access policy.
        val metadata = metadata(key) ?: return null
        require(expected == null || metadata.descriptor == expected) { "SYNC_GROUP_WIRE_OBJECT_CHANGED" }
        val descriptor = metadata.descriptor
        if (descriptor.length == 0L) {
            require(descriptor.sha256.hex == lanSyncHash(byteArrayOf())) { "SYNC_GROUP_WIRE_OBJECT_CHANGED" }; return metadata
        }
        val path = cache.open(descriptor.sha256.hex, descriptor.length).use { partial ->
            exchange(request(GroupStoreCommand.GET, key, descriptor = descriptor, offset = partial.offset)) { wire ->
                val first = GroupStoreWire.reply(wire)
                require(first.item?.metadata() == metadata && first.offset == partial.offset) { "SYNC_GROUP_WIRE_OBJECT_CHANGED" }
                while (partial.offset < descriptor.length) {
                    val chunk = GroupStoreWire.reply(wire)
                    require(!chunk.done && chunk.offset == partial.offset && chunk.length.toLong() ==
                        minOf(GroupStoreWire.CHUNK_BYTES.toLong(), descriptor.length - partial.offset)) { "SYNC_GROUP_WIRE_CHUNK" }
                    val bytes = GroupStoreWire.bytes(wire, chunk.length, chunk.hash)
                    partial.append(chunk.offset, bytes, requireNotNull(chunk.hash)); observe("CIPHERTEXT_CHUNK_RETAINED", partial.offset)
                }
                val done = GroupStoreWire.reply(wire)
                require(done.done && done.offset == descriptor.length && done.item?.metadata() == metadata) { "SYNC_GROUP_WIRE_DONE" }
            }
            partial.finish()
        }
        Files.newInputStream(path, NOFOLLOW_LINKS).use { it.copyTo(destination, 64 * 1024) }
        return metadata
    }
}
