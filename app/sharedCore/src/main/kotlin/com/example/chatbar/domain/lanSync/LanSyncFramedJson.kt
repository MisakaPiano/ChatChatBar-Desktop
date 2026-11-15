package com.example.chatbar.domain.lanSync

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/** Large metadata is spooled and framed, never reassembled into a document-sized byte array. */
internal object LanSyncFramedJson {
    private const val PREFIX = "CCB-JSON6:"
    const val FRAME_BYTES = 1024 * 1024
    @Serializable private data class Header(val bytes: Long, val sha256: String)

    fun <T> send(wire: LanSyncWire, parent: Path, serializer: KSerializer<T>, value: T) {
        LanSyncScratch(parent, "metadata-send").use { scratch ->
            val file = scratch.directory.resolve("document.json")
            LanSyncJsonFiles.write(file, serializer, value); scratch.own(file)
            val size = Files.size(file)
            val hash = LanSyncResourceStore.hashFile(file, size)
            wire.sendText(PREFIX + lanSyncJson.encodeToString(Header.serializer(), Header(size, hash)))
            Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
                var count = 0L
                while (count < size) {
                    val bytes = ByteArray(minOf(FRAME_BYTES.toLong(), size - count).toInt())
                    java.io.DataInputStream(input).readFully(bytes)
                    wire.send(bytes)
                    count = lanSyncAddLength(count, bytes.size.toLong())
                }
                require(input.read() == -1) { "SYNC_METADATA_SOURCE_CHANGED" }
            }
        }
    }

    fun <T> receive(wire: LanSyncWire, parent: Path, serializer: KSerializer<T>, initialHeader: String? = null,
        budget: LanSyncMetadataBudget = LanSyncMetadataBudget()): T {
        val text = initialHeader ?: wire.text(1024)
        require(text.startsWith(PREFIX) && text.length < 1024) { "SYNC_METADATA_HEADER" }
        val header = lanSyncJson.decodeFromString(Header.serializer(), text.removePrefix(PREFIX))
        require(header.bytes > 0 && header.sha256.matches(Regex("[a-f0-9]{64}"))) { "SYNC_METADATA_HEADER" }
        LanSyncScratch(parent, "metadata-receive").use { scratch ->
            LanSyncResourceBudget().requireSpace(scratch.directory, header.bytes)
            val file = scratch.directory.resolve("document.json")
            FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                scratch.own(file)
                var count = 0L
                while (count < header.bytes) {
                    val expected = minOf(FRAME_BYTES.toLong(), header.bytes - count).toInt()
                    val bytes = wire.receive(expected)
                    require(bytes.size == expected) { "SYNC_METADATA_FRAME" }
                    val block = ByteBuffer.wrap(bytes); while (block.hasRemaining()) channel.write(block)
                    count = lanSyncAddLength(count, bytes.size.toLong())
                }
                channel.force(true)
            }
            require(LanSyncResourceStore.hashFile(file, header.bytes) == header.sha256) { "SYNC_METADATA_DAMAGED" }
            budget.validate(file)
            return requireNotNull(LanSyncJsonFiles.read(file, serializer))
        }
    }
}
