package com.example.chatbar.domain.lanSync

import java.io.Closeable
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Invocation-owned, depth-one temporary files. Unknown/replaced children are never swept. */
internal class LanSyncScratch(parent: Path, prefix: String) : Closeable {
    val directory: Path = parent.resolve("$prefix-${UUID.randomUUID()}")
    private val files = linkedMapOf<Path, Any?>()
    private val directoryKey: Any?
    init { Files.createDirectories(parent); lanSyncSafe(parent, true); Files.createDirectory(directory)
        directoryKey = Files.readAttributes(directory, BasicFileAttributes::class.java).fileKey() }
    fun own(path: Path): Path {
        require(path.parent == directory); lanSyncSafe(path)
        files[path] = Files.readAttributes(path, BasicFileAttributes::class.java).fileKey()
        return path
    }
    fun remove(path: Path) {
        require(path in files) { "SYNC_SCRATCH_UNKNOWN_CHILD" }
        lanSyncSafe(path)
        require(Files.readAttributes(path, BasicFileAttributes::class.java).fileKey() == files[path]) { "SYNC_SCRATCH_CHANGED" }
        Files.delete(path); files.remove(path)
    }
    override fun close() {
        lanSyncSafe(directory, true)
        require(Files.readAttributes(directory, BasicFileAttributes::class.java).fileKey() == directoryKey) { "SYNC_SCRATCH_CHANGED" }
        val actual = Files.newDirectoryStream(directory).use { it.toSet() }
        require(actual == files.keys) { "SYNC_SCRATCH_UNKNOWN_CHILD" }
        files.forEach { (path, key) -> lanSyncSafe(path); require(Files.readAttributes(path, BasicFileAttributes::class.java).fileKey() == key) { "SYNC_SCRATCH_CHANGED" } }
        files.keys.forEach { Files.delete(it) }; Files.delete(directory)
    }
}
