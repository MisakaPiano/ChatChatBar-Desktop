package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.DesktopArchiveFiles
import com.example.chatbar.desktop.DesktopIndexStartupPreflight
import java.io.OutputStream
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal class DesktopBackupScratchCleanupException : IllegalStateException("BACKUP_TEMP_CLEANUP_UNCONFIRMED")

/** Invocation-local ownership, never a recursive sweep. Journals and original archives are not members. */
internal class DesktopBackupScratch private constructor(val directory: Path) : AutoCloseable {
    private val identities = linkedMapOf<Path, Pair<Any, Boolean>>()

    init { recordCreated(directory) }

    /** Only the writer that just created the path may call this (including the audio publisher). */
    fun recordCreated(path: Path) {
        check(path.normalize() == path && path.startsWith(directory))
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        DesktopArchiveFiles.safe(path, attrs.isDirectory)
        val identity = identity(path, attrs.isDirectory)
        val existing = identities[path]
        check(existing == null || existing == (identity to attrs.isDirectory))
        identities[path] = identity to attrs.isDirectory
    }

    fun directories(path: Path) {
        check(path.startsWith(directory) && path.normalize() == path)
        var current = directory
        directory.relativize(path).forEach { name ->
            current = current.resolve(name)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(current); recordCreated(current)
            } else check(identities[current]?.second == true) { "UNOWNED_BACKUP_DIRECTORY" }
        }
    }

    fun output(path: Path): OutputStream {
        directories(path.parent)
        val stream = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        try { recordCreated(path) } catch (error: Throwable) { stream.close(); throw error }
        return stream
    }

    fun write(path: Path, bytes: ByteArray) { output(path).use { it.write(bytes) } }

    suspend fun copy(source: Path, target: Path, limit: Long): String = output(target).use { output ->
        DesktopArchiveFiles.read(source, limit) { bytes, count -> output.write(bytes, 0, count) }
    }

    fun remove(path: Path) {
        val expected = checkNotNull(identities[path])
        // Validate every owned ancestor too: directory replacement must not grant deletion authority.
        identities.filterKeys { path.startsWith(it) }.forEach { (ancestor, stamp) ->
            if (Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) check(identity(ancestor, stamp.second) == stamp.first)
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            check(identity(path, expected.second) == expected.first)
            Files.delete(path) // Unknown children cause DirectoryNotEmpty; they are never traversed/deleted.
        }
        identities.remove(path)
    }

    override fun close() {
        var failed = false
        identities.keys.sortedByDescending { it.nameCount }.forEach { path ->
            try { remove(path) } catch (_: Exception) { failed = true }
        }
        if (failed) throw DesktopBackupScratchCleanupException()
    }

    private fun identity(path: Path, directory: Boolean): Any {
        DesktopArchiveFiles.safe(path, directory)
        return if (System.getProperty("os.name").startsWith("Windows"))
            DesktopIndexStartupPreflight.readWindowsIdentity(path, directory, extendedPaths = true)
        else checkNotNull(Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey())
    }

    companion object {
        fun create(parent: Path, prefix: String): DesktopBackupScratch {
            DesktopArchiveFiles.safe(parent, true)
            require(prefix.matches(Regex("[a-z-]+")))
            return DesktopBackupScratch(Files.createDirectory(parent.resolve("$prefix${UUID.randomUUID()}")))
        }
    }
}
