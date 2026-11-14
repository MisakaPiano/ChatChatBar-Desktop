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

/** Immutable read-through custody only. It never turns a cache hit into an outgoing route receipt. */
class LanSyncGroupReplicaReader(private val local: CiphertextStore, private val work: Path,
    private val failedRoute: (String, Exception) -> Unit) : CiphertextStore {
    class Route(val id: String, val store: CiphertextStore) { override fun toString() = "GroupReadRoute(REDACTED)" }
    @Volatile private var routes: List<Route> = emptyList()
    init { Files.createDirectories(work); lanSyncSafe(work, true) }
    fun routes(value: List<Route>) { require(value.map { it.id }.toSet().size == value.size); routes = value.toList() }
    override val capabilities get() = local.capabilities
    override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult = throw CiphertextStoreException(CiphertextStoreFailure.UNSUPPORTED)
    private fun failed(route: Route, failure: Exception) {
        if (failure is CancellationException || failure is InterruptedException || failure is java.io.InterruptedIOException) throw failure
        failedRoute(route.id, failure)
    }
    override fun metadata(key: CiphertextObjectKey): CiphertextObjectMetadata? {
        local.metadata(key)?.let { return it }
        var last: Exception? = null
        for (route in routes) try { route.store.metadata(key)?.let { return it } }
        catch (failure: Exception) { failed(route, failure); last = failure }
        last?.let { throw it }; return null
    }
    override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
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
    override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage = local.listCommits(cursor, pageSize)
    /** Discovery still names the actual source route; all retained bytes use the same immutable cache. */
    fun discoveryFrom(route: CiphertextStore): CiphertextStore = object : CiphertextStore by this {
        override fun listCommits(cursor: CommitCursor?, pageSize: Int) = route.listCommits(cursor, pageSize)
    }
}
