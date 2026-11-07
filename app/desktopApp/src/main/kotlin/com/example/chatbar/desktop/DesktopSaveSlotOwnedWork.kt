package com.example.chatbar.desktop

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Capability for one newly created archive workspace, never an old-directory sweep. */
internal class DesktopSaveSlotOwnedWork private constructor(val directory: Path, private val key: Any) {
    private data class Stamp(val key: Any, val directory: Boolean, val hash: String?)
    internal class Receipt private constructor(private val home: Path, private val files: Map<Path, Stamp>) {
        suspend fun release() {
            check(inventory(home) == files) { "SAVE_SLOT_WORK_CHANGED" }
            for ((path, stamp) in files.entries.sortedByDescending { it.key.nameCount }) {
                check(identity(path, stamp.directory) == stamp.key) { "SAVE_SLOT_WORK_REPLACED" }
                Files.delete(path) // Unknown children refuse deletion; no follow-links recursive delete.
            }
        }
        companion object {
            internal suspend fun capture(home: Path): Receipt = Receipt(home, inventory(home))
        }
    }
    suspend fun seal(): Receipt = withContext(Dispatchers.IO) {
        check(identity(directory, true) == key) { "SAVE_SLOT_WORK_REPLACED" }
        val receipt = Receipt.capture(directory)
        check(identity(directory, true) == key) { "SAVE_SLOT_WORK_REPLACED" }
        receipt
    }
    companion object {
        fun create(parent: Path): DesktopSaveSlotOwnedWork {
            DesktopArchiveFiles.safe(parent, true)
            val directory = Files.createDirectory(parent.toAbsolutePath().normalize().resolve("slot-${UUID.randomUUID()}"))
            return DesktopSaveSlotOwnedWork(directory, identity(directory, true))
        }
        internal fun identity(path: Path, directory: Boolean): Any {
            DesktopArchiveFiles.safe(path, directory)
            return if (System.getProperty("os.name").startsWith("Windows"))
                DesktopIndexStartupPreflight.readWindowsIdentity(path, directory, extendedPaths = true)
            else checkNotNull(Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey())
        }
        private suspend fun inventory(home: Path): Map<Path, Stamp> {
            val result = linkedMapOf<Path, Stamp>()
            val context = currentCoroutineContext()
            Files.walk(home).use { paths ->
                val iterator = paths.iterator()
                while (iterator.hasNext()) {
                    context.ensureActive()
                    val path = iterator.next()
                    check(path.normalize() == path && path.startsWith(home) && result.size < 160000)
                    val directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    val key = identity(path, directory)
                    val hash = if (directory) null else DesktopArchiveFiles.hash(path, DesktopSaveSlotArchive.MAX_ARCHIVE)
                    check(identity(path, directory) == key) { "SAVE_SLOT_WORK_REPLACED" }
                    result[path] = Stamp(key, directory, hash)
                }
            }
            return result
        }
    }
}

/** One asset: copy created for a single capture; original/bundled/user images are never members. */
internal class DesktopSaveSlotTemporaryAsset private constructor(val file: Path, private val parentKey: Any,
    private val fileKey: Any, private val hash: String) {
    suspend fun release() {
        check(DesktopSaveSlotOwnedWork.identity(file.parent, true) == parentKey &&
            DesktopSaveSlotOwnedWork.identity(file, false) == fileKey)
        check(DesktopArchiveFiles.hash(file, DesktopImageEditing.MAX_BYTES.toLong()) == hash) { "SAVE_SLOT_TEMP_ASSET_CHANGED" }
        check(DesktopSaveSlotOwnedWork.identity(file, false) == fileKey)
        Files.delete(file)
    }
    companion object {
        suspend fun create(root: Path, bytes: ByteArray, extension: String): DesktopSaveSlotTemporaryAsset = withContext(Dispatchers.IO) {
            require(bytes.size <= DesktopImageEditing.MAX_BYTES && extension in setOf("png", "jpg", "jpeg", "gif", "webp"))
            val parent = root.resolve("backups/.save-slot-assets")
            DesktopArchiveFiles.safe(parent.parent, true); Files.createDirectories(parent)
            val parentKey = DesktopSaveSlotOwnedWork.identity(parent, true)
            val file = parent.resolve("${UUID.randomUUID()}.$extension")
            Files.write(file, bytes, StandardOpenOption.CREATE_NEW)
            val fileKey = DesktopSaveSlotOwnedWork.identity(file, false)
            val hash = com.example.chatbar.desktop.backup.DesktopBackupPolicy.digest(bytes)
            check(DesktopArchiveFiles.hash(file, DesktopImageEditing.MAX_BYTES.toLong()) == hash &&
                DesktopSaveSlotOwnedWork.identity(parent, true) == parentKey && DesktopSaveSlotOwnedWork.identity(file, false) == fileKey)
            DesktopSaveSlotTemporaryAsset(file, parentKey, fileKey, hash)
        }
    }
}
