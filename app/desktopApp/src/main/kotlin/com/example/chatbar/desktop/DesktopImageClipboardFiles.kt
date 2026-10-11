package com.example.chatbar.desktop

import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.nio.file.StandardCopyOption.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import com.example.chatbar.domain.image.ApngDisguiseCodec

/** File-paste needs durable encoded bytes, not an imageFlavor rendering of an animation.
 * Current clipboard references are retained indefinitely; seven days start at confirmed release.
 * Cleanup is lazy, on the next copy, and only accepts intact, hash-verified cache entries.
 * Unknown ownership/clipboard state retains data. Capacity exhaustion fails instead of evicting it.
 * No dialog disposal, JVM exit hook, or user-image directory participates in this cache. */
internal class DesktopImageClipboardFiles(
    private val root: Path,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = 32,
    private val maxBytes: Long = 256L * 1024 * 1024,
) {
    private data class Entry(val directory: Path, val file: Path, val created: Long, val size: Long,
        val digest: String, val released: Long?)

    private fun attributes(path: Path) = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    private fun plainDirectory(path: Path) = attributes(path).let { it.isDirectory && !it.isSymbolicLink && !it.isOther }
    private fun plainFile(path: Path) = attributes(path).let { it.isRegularFile && !it.isSymbolicLink && !it.isOther }

    @Synchronized
    fun copy(bytes: ByteArray, clipboard: Clipboard) {
        require(bytes.size.toLong() in 1..ApngDisguiseCodec.MAX_OUTPUT_BYTES)
        val extension = when {
            bytes.size >= 8 && bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> "png"
            bytes.size >= 6 && String(bytes, 0, 3, Charsets.US_ASCII) == "GIF" -> "gif"
            bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
            else -> error("Unsupported file-copy image")
        }
        Files.createDirectories(root)
        check(plainDirectory(root))
        FileChannel.open(root.resolve(".lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.tryLock().use { lock ->
                check(lock != null) { "Clipboard cache in use" }
                val directories = Files.list(root).use { stream -> stream.filter { it.fileName.toString() != ".lock" }.limit(maxEntries.toLong() + 1).toList() }
                check(directories.size <= maxEntries) { "Clipboard cache full" }
                // Unknown entries consume quota but are never rewritten, deleted or followed.
                val inventory = directories.associateWith { runCatching { inspect(it) }.getOrNull() }
                val entries = inventory.values.filterNotNull()
                val unknown = inventory.filterValues { it == null }.keys
                val unknownBytes = unknown.sumOf { unknownSize(it) }
                val current = runCatching {
                    val content = clipboard.getContents(null)
                    if (content == null || !content.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) emptySet()
                    else (content.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).map {
                        (it as java.io.File).toPath().toAbsolutePath().normalize()
                    }.toSet()
                }.getOrNull()
                val time = now()
                val retained = entries.filter { entry ->
                    val active = current?.contains(entry.file.toAbsolutePath().normalize())
                    val release = entry.released
                    if (active == true && release != null) recordRelease(entry, null)
                    // Never backdate an established release. A first release cannot precede creation.
                    if (active == false && release == null) recordRelease(entry, maxOf(time, entry.created))
                    // Inconsistent legacy timestamps are retained, not treated as proof of expiry.
                    val expired = release != null && release >= entry.created && time >= release && time - release >= RETENTION_MILLIS
                    if (active == false && expired) {
                        Files.delete(entry.file)
                        Files.delete(entry.directory.resolve("owner.txt"))
                        Files.delete(entry.directory)
                        false
                    } else true
                }
                check(retained.size + unknown.size < maxEntries && unknownBytes + retained.sumOf { it.size } + bytes.size <= maxBytes) { "Clipboard cache full; save the result instead" }
                val directory = Files.createDirectory(root.resolve("copy-${UUID.randomUUID()}"))
                val file = directory.resolve("image.$extension")
                // An interrupted write is deliberately retained as indeterminate, never guessed away.
                Files.write(file, bytes, CREATE_NEW, WRITE)
                Files.writeString(directory.resolve("owner.txt"), "CCB-clipboard-v1\n$time\n$extension\n${hash(bytes)}", CREATE_NEW, WRITE)
                // Even a publication failure can be indeterminate: retain the staged file, with quota.
                clipboard.setContents(desktopImageTransferable(bytes, file), null)
            }
        }
    }

    private fun inspect(directory: Path): Entry {
        check(directory.fileName.toString().matches(Regex("copy-[0-9a-f-]{36}")) && plainDirectory(directory))
        val record = directory.resolve("owner.txt")
        check(plainFile(record) && Files.size(record) <= 256)
        val fields = Files.readString(record).split('\n')
        check(((fields.size == 4 && fields[0] == "CCB-clipboard-v1") ||
            (fields.size == 5 && fields[0] == "CCB-clipboard-v2")) && fields[2] in listOf("png", "gif", "webp"))
        val created = fields[1].toLong()
        val file = directory.resolve("image.${fields[2]}")
        check(Files.list(directory).use { it.limit(3).count() } == 2L && plainFile(file))
        val size = Files.size(file)
        check(size in 1..ApngDisguiseCodec.MAX_OUTPUT_BYTES && hash(Files.readAllBytes(file)) == fields[3])
        return Entry(directory, file, created, size, fields[3], fields.getOrNull(4)?.takeUnless { it == "active" }?.toLong())
    }

    private fun recordRelease(entry: Entry, released: Long?) {
        val temporary = entry.directory.resolve("owner-${UUID.randomUUID()}.tmp")
        val extension = entry.file.fileName.toString().substringAfterLast('.')
        Files.writeString(temporary, "CCB-clipboard-v2\n${entry.created}\n$extension\n${entry.digest}\n${released ?: "active"}", CREATE_NEW, WRITE)
        // Atomic replacement or fail closed. A leftover temp file makes this entry unknown.
        Files.move(temporary, entry.directory.resolve("owner.txt"), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private fun unknownSize(path: Path): Long {
        var visited = 0
        fun size(candidate: Path, depth: Int): Long {
            if (++visited > 1024 || depth > 16) return maxBytes
            val attr = attributes(candidate)
            // Reserve one maximum output for an opaque link without visiting its target.
            if (attr.isSymbolicLink || attr.isOther) return ApngDisguiseCodec.MAX_OUTPUT_BYTES
            if (attr.isRegularFile) return attr.size().coerceAtMost(maxBytes)
            if (!attr.isDirectory) return maxBytes
            var total = 0L
            Files.newDirectoryStream(candidate).use { children ->
                for (child in children) {
                    total += size(child, depth + 1)
                    if (total >= maxBytes || visited > 1024) return maxBytes
                }
            }
            return total
        }
        return runCatching { size(path, 0) }.getOrDefault(maxBytes)
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object { const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000 }
}

private val desktopClipboardFiles by lazy {
    DesktopImageClipboardFiles(Path.of(System.getProperty("java.io.tmpdir"), "ccb-image-clipboard-v1"))
}

internal fun copyDesktopImageResult(bytes: ByteArray,
    clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard,
    files: DesktopImageClipboardFiles = desktopClipboardFiles) {
    if (runCatching { DesktopImageEditing.requireStatic(bytes) }.isSuccess) clipboard.setContents(desktopImageTransferable(bytes), null)
    else files.copy(bytes, clipboard)
}
