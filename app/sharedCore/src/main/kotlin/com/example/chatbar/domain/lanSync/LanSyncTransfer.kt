package com.example.chatbar.domain.lanSync

import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

object LanSyncTransfer {
    @Serializable private data class Need(val hash: String, val length: Long, val offset: Long, val chunkBytes: Int)
    @Serializable private data class Chunk(val hash: String, val offset: Long, val length: Int, val sha256: String)

    fun send(wire: LanSyncWire, parent: Path, capture: LanSyncCapture, observe: (String, Long) -> Unit = { _, _ -> }) {
        capture.snapshot.validate(); LanSyncScope.validateGraph(capture.snapshot)
        LanSyncFramedJson.send(wire, parent, LanSyncSnapshot.serializer(), capture.snapshot)
        var sent = 0L
        for ((hash, size) in capture.snapshot.resources.toSortedMap()) {
            val need = lanSyncJson.decodeFromString(Need.serializer(), wire.text(1024))
            require(need.hash == hash && need.length == size && need.offset in 0..size && need.chunkBytes in 1..1024 * 1024 &&
                (need.offset == size || need.offset % need.chunkBytes == 0L)) { "SYNC_RESOURCE_NEGOTIATION" }
            val source = requireNotNull(capture.blobs[hash]) { "SYNC_RESOURCE_MISSING" }
            require(LanSyncResourceStore.hashFile(source, size) == hash) { "SYNC_CAPTURE_CHANGED" }
            observe("RESOURCE_REUSED_BYTES", need.offset)
            FileChannel.open(source, StandardOpenOption.READ).use { channel ->
                var offset = need.offset; channel.position(offset)
                while (offset < size) {
                    val bytes = ByteArray(minOf(need.chunkBytes.toLong(), size - offset).toInt())
                    val block = ByteBuffer.wrap(bytes)
                    while (block.hasRemaining()) require(channel.read(block) > 0) { "SYNC_SOURCE_TRUNCATED" }
                    wire.sendText(lanSyncJson.encodeToString(Chunk.serializer(), Chunk(hash, offset, bytes.size, lanSyncHash(bytes))))
                    wire.send(bytes)
                    offset = lanSyncAddLength(offset, bytes.size.toLong())
                    sent = lanSyncAddLength(sent, bytes.size.toLong()); observe("RESOURCE_BYTES_SENT", sent)
                    observe("RESOURCE_CHUNK_SENT_BYTES", bytes.size.toLong())
                }
            }
            require(wire.text(1024) == "RESOURCE_COMPLETE:$hash:$size") { "SYNC_RESOURCE_RECEIPT" }
        }
        require(wire.text(1024) == "SNAPSHOT_READY") { "SYNC_RESOURCE_RECEIPT" }
    }

    fun receive(wire: LanSyncWire, parent: Path, authenticatedDevice: String,
        resourceStore: LanSyncResourceStore = LanSyncResourceStore(parent.resolve("received-resources")),
        observe: (String, Long) -> Unit = { _, _ -> }): LanSyncCapture {
        val snapshot = LanSyncFramedJson.receive(wire, parent, LanSyncSnapshot.serializer())
        require(snapshot.deviceId == authenticatedDevice) { "SYNC_SNAPSHOT_DEVICE" }
        snapshot.validate(); LanSyncScope.validateGraph(snapshot)
        LanSyncResourceBudget().total(snapshot.resources.values)
        val blobs = linkedMapOf<String, Path>()
        var received = 0L
        for ((hash, size) in snapshot.resources.toSortedMap()) {
            resourceStore.open(hash, size).use { partial ->
                wire.sendText(lanSyncJson.encodeToString(Need.serializer(), Need(hash, size, partial.offset, resourceStore.chunkBytes)))
                while (partial.offset < size) {
                    val chunk = lanSyncJson.decodeFromString(Chunk.serializer(), wire.text(1024))
                    val expected = minOf(resourceStore.chunkBytes.toLong(), size - partial.offset).toInt()
                    require(chunk.hash == hash && chunk.offset == partial.offset && chunk.length == expected &&
                        chunk.sha256.matches(Regex("[a-f0-9]{64}"))) { "SYNC_CHUNK_OFFSET" }
                    val bytes = wire.receive(expected)
                    require(bytes.size == expected) { "SYNC_CHUNK_LENGTH" }
                    partial.append(chunk.offset, bytes, chunk.sha256)
                    received = lanSyncAddLength(received, bytes.size.toLong()); observe("RESOURCE_BYTES_RECEIVED", received)
                    observe("RESOURCE_CHUNK_RECEIVED_BYTES", bytes.size.toLong())
                }
                blobs[hash] = partial.finish()
                wire.sendText("RESOURCE_COMPLETE:$hash:$size")
            }
        }
        wire.sendText("SNAPSHOT_READY")
        return LanSyncCapture(snapshot, blobs, emptyMap())
    }
}
