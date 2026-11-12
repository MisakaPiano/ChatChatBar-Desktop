package com.example.chatbar.domain.lanSync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.nio.file.*
import java.util.UUID

object LanSyncTransfer {
    fun send(wire: LanSyncWire, capture: LanSyncCapture) {
        capture.snapshot.validate()
        wire.sendText(lanSyncJson.encodeToString(capture.snapshot))
        capture.snapshot.resources.toSortedMap().forEach { (hash, size) ->
            val source = requireNotNull(capture.blobs[hash])
            require(LanSyncWorkspace.hashFile(source, size) == hash) { "SYNC_CAPTURE_CHANGED" }
            Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS).use { wire.sendBlob(it, size) }
        }
    }
    fun receive(wire: LanSyncWire, workspace: Path, authenticatedDevice: String): LanSyncCapture {
        val snapshot = lanSyncJson.decodeFromString<LanSyncSnapshot>(wire.text())
        require(snapshot.deviceId == authenticatedDevice) { "SYNC_SNAPSHOT_DEVICE" }
        snapshot.validate(); LanSyncScope.validateGraph(snapshot)
        require(snapshot.resources.values.sum() <= LanSyncWorkspace.MAX_TOTAL) { "SYNC_TOTAL_LIMIT" }
        val directory = workspace.resolve("received-${UUID.randomUUID()}")
        Files.createDirectories(directory); lanSyncSafe(directory, true)
        val blobs = linkedMapOf<String, Path>()
        snapshot.resources.toSortedMap().forEach { (hash, size) ->
            val target = directory.resolve(hash)
            Files.newOutputStream(target, StandardOpenOption.CREATE_NEW).use { wire.receiveBlob(it, size, hash) }
            java.nio.channels.FileChannel.open(target, StandardOpenOption.WRITE).use { it.force(true) }
            blobs[hash] = target
        }
        return LanSyncCapture(snapshot, blobs, emptyMap())
    }
}
