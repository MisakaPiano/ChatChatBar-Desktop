package com.example.chatbar.domain.lanSync

import java.nio.file.*

/** Fully hash and flush a unique owned temporary before exposing an immutable final filename. */
object LanSyncBlobs {
    fun publish(source: Path, target: Path, length: Long, hash: String, scratchParent: Path) {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        Files.createDirectories(target.parent); lanSyncSafe(target.parent, true)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            require(LanSyncWorkspace.hashFile(target, length) == hash) { "SYNC_RESOURCE_COLLISION" }; return
        }
        val scratch = LanSyncScratch(scratchParent, "blob-publication")
        var moved = false
        try {
            val temporary = scratch.directory.resolve(hash)
            require(LanSyncWorkspace.copyHash(source, temporary, length, scratch::own) == hash) { "SYNC_RESOURCE_DAMAGED" }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            moved = true
        } finally {
            if (moved) Files.delete(scratch.directory) else scratch.close()
        }
    }
}
