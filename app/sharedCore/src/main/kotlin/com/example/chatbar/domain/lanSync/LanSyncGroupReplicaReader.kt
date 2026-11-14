package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.CancellationException
import kotlinx.serialization.Serializable

/** Raw metadata candidates stay separate until callers authenticate them. Cache hits are not delivery receipts. */
class LanSyncGroupReplicaReader(private val local: CiphertextStore, private val work: Path,
    candidateRoot: Path = work.resolve("commit-candidates"),
    private val failedRoute: (String, Exception) -> Unit) : CiphertextStore {
    class Route(val id: String, val store: CiphertextStore) { override fun toString() = "GroupReadRoute(REDACTED)" }
    @Volatile private var routes: List<Route> = emptyList()
    init { Files.createDirectories(work); lanSyncSafe(work, true) }
    private val commits = SyncCiphertextCandidates(candidateRoot, local)
    private val listingId = SyncStoreId.random().value
    @Serializable private data class Incoming(val key: CiphertextObjectKey, val descriptor: CiphertextDescriptor)
    fun routes(value: List<Route>) { require(value.map { it.id }.toSet().size == value.size); routes = value.toList() }
    override val capabilities get() = local.capabilities.copy(untrustedCandidateVariants = true)
    override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult {
        if (request.key.kind != CiphertextObjectKind.COMMIT) return local.put(request, source)
        val metadata = CiphertextObjectMetadata(request.key, request.descriptor)
        java.nio.channels.FileChannel.open(work.resolve("incoming.lock"), java.nio.file.StandardOpenOption.CREATE,
            WRITE, NOFOLLOW_LINKS).use { channel -> synchronized(incomingLocks.computeIfAbsent(work) { Any() }) { channel.lock().use {
            val binding = work.resolve("incoming-${request.operationId.value}.json")
            val expected = Incoming(request.key, request.descriptor)
            if (Files.exists(binding, NOFOLLOW_LINKS)) {
                lanSyncSafe(binding)
                if (LanSyncJsonFiles.read(binding, Incoming.serializer()) != expected) throw CiphertextStoreException(CiphertextStoreFailure.OPERATION_CONFLICT)
            } else lanSyncAtomicWrite(binding, lanSyncJson.encodeToString(Incoming.serializer(), expected).toByteArray())
            val present = commits.exact(metadata).metadata(request.key) != null
            val borrowed = object : CiphertextStore by local {
                override fun metadata(key: CiphertextObjectKey) = if (key == request.key) metadata else null
                override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
                    require(key == request.key && expected == request.descriptor)
                    source.copyTo(destination, 64 * 1024); return metadata
                }
            }
            commits.retain(metadata, borrowed)
            return CiphertextPutResult(metadata, if (present) CiphertextPutDisposition.ALREADY_PRESENT else CiphertextPutDisposition.CREATED)
        } } }
    }
    private fun failed(route: Route, failure: Exception) {
        if (failure is CancellationException || failure is InterruptedException || failure is java.io.InterruptedIOException) throw failure
        failedRoute(route.id, failure)
    }
    override fun metadata(key: CiphertextObjectKey): CiphertextObjectMetadata? {
        local.metadata(key)?.let { return it }
        if (key.kind == CiphertextObjectKind.COMMIT) commits.entries().firstOrNull { it.key == key }?.let { return it }
        var last: Exception? = null
        for (route in routes) try { route.store.metadata(key)?.let { return it } }
        catch (failure: Exception) { failed(route, failure); last = failure }
        last?.let { throw it }; return null
    }
    /** Exact raw candidate lookup. It is not an authenticated canonical-object replacement. */
    fun metadataExact(key: CiphertextObjectKey, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
        if (expected == null) return local.metadata(key) ?: if (key.kind == CiphertextObjectKind.COMMIT)
            commits.entries().firstOrNull { it.key == key } else null
        val wanted = CiphertextObjectMetadata(key, expected)
        if (key.kind == CiphertextObjectKind.COMMIT && commits.exact(wanted).metadata(key) != null) return wanted
        return local.metadata(key)?.also { require(it == wanted) { "SYNC_GROUP_REPLICA_OBJECT_CHANGED" } }
    }
    override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
        if (key.kind == CiphertextObjectKind.COMMIT) return readCommit(key, destination, expected, routes)
        local.metadata(key)?.let { return local.readTo(key, destination, expected) }
        val metadata = metadata(key) ?: return null
        require(expected == null || expected == metadata.descriptor) { "SYNC_GROUP_REPLICA_OBJECT_CHANGED" }
        LanSyncResourceBudget().requireSpace(work, metadata.descriptor.length, copies = 2)
        var last: Exception? = null
        for (route in routes) {
            val copied = LanSyncScratch(work, "replica-read").use { scratch ->
                val file = scratch.directory.resolve("ciphertext.bin")
                var count = 0L
                val received = try {
                    val result = FileChannel.open(file, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                        scratch.own(file)
                        val output = object : OutputStream() {
                            private val single = ByteArray(1)
                            override fun write(value: Int) { single[0] = value.toByte(); write(single, 0, 1) }
                            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                                require(length >= 0 && count <= metadata.descriptor.length - length) { "SYNC_GROUP_REPLICA_OBJECT_CHANGED" }
                                val buffer = ByteBuffer.wrap(bytes, offset, length); while (buffer.hasRemaining()) channel.write(buffer)
                                count += length
                            }
                        }
                        route.store.readTo(key, output, metadata.descriptor).also { channel.force(true) }
                    }
                    if (result == null) false else {
                        require(result == metadata && count == metadata.descriptor.length &&
                            LanSyncResourceStore.hashFile(file, count) == metadata.descriptor.sha256.hex) { "SYNC_GROUP_REPLICA_OBJECT_CHANGED" }
                        true
                    }
                } catch (failure: Exception) { failed(route, failure); last = failure; false }
                if (!received) return@use false
                // Local publication/sink failures are not remote route failures and cannot retry into a partial sink.
                Files.newInputStream(file, NOFOLLOW_LINKS).use { input ->
                    local.put(CiphertextPutRequest(key, SyncStoreId.random(), metadata.descriptor), input)
                }
                true
            }
            if (copied) return local.readTo(key, destination, metadata.descriptor)
        }
        last?.let { throw it }; return null
    }
    private fun readCommit(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?, sources: List<Route>): CiphertextObjectMetadata? {
        val wanted = expected?.let { CiphertextObjectMetadata(key, it) } ?: metadata(key) ?: return null
        commits.exact(wanted).metadata(key)?.let { return commits.exact(wanted).readTo(key, destination, wanted.descriptor) }
        var last: Exception? = null
        for (route in sources) {
            val retained = try { commits.retain(wanted, route.store); true }
            catch (failure: Exception) { failed(route, failure); last = failure; false }
            // Caller output failure must not retry into the already-partial sink.
            if (retained) return commits.exact(wanted).readTo(key, destination, wanted.descriptor)
        }
        last?.let { throw it }; return null
    }
    override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage {
        require(pageSize in 1..1000) { "SYNC_GROUP_REPLICA_PAGE_SIZE" }
        val prefix = "candidate1:$listingId:"
        val after = cursor?.token?.let {
            require(it.startsWith(prefix)) { "SYNC_GROUP_REPLICA_CURSOR" }
            it.removePrefix(prefix).also { value -> require(value.matches(Regex("[a-f0-9]{32}:[0-9]{19}:[a-f0-9]{64}"))) { "SYNC_GROUP_REPLICA_CURSOR" } }
        }
        fun position(value: CiphertextObjectMetadata) = "${value.key.id.value}:${value.descriptor.length.toString().padStart(19, '0')}:${value.descriptor.sha256.hex}"
        val remaining = commits.entries().filter { after == null || position(it) > after }
        val rows = remaining.take(pageSize)
        return CommitPage(rows, if (remaining.size > rows.size) CommitCursor(prefix + position(rows.last())) else null)
    }
    /** Discovery still names the actual source route; all retained bytes use the same immutable cache. */
    fun discoveryFrom(route: CiphertextStore): CiphertextStore = object : CiphertextStore by this {
        override fun listCommits(cursor: CommitCursor?, pageSize: Int) = route.listCommits(cursor, pageSize)
        override fun metadata(key: CiphertextObjectKey) = route.metadata(key)
        override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? =
            if (key.kind == CiphertextObjectKind.COMMIT) readCommit(key, destination, expected,
                listOf(Route("DISCOVERY", route)) + routes.filter { it.store !== route })
            else this@LanSyncGroupReplicaReader.readTo(key, destination, expected)
    }
    companion object { private val incomingLocks = java.util.concurrent.ConcurrentHashMap<Path, Any>() }
}
