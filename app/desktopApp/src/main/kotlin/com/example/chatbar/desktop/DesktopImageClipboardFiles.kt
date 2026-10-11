package com.example.chatbar.desktop

import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.UUID
import com.example.chatbar.domain.image.ApngDisguiseCodec

/** File-paste needs durable encoded bytes, not an imageFlavor rendering of an animation.
 * Current clipboard references are retained indefinitely; superseded copies live at least seven days.
 * Cleanup is lazy, on the next copy, and only accepts intact, hash-verified cache entries.
 * Unknown ownership/clipboard state retains data. Capacity exhaustion fails instead of evicting it.
 * No dialog disposal, JVM exit hook, or user-image directory participates in this cache. */
internal class DesktopImageClipboardFiles(
    private val root: Path,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = 32,
    private val maxBytes: Long = 256L * 1024 * 1024,
) {
    private data class Entry(val directory: Path, val file: Path, val created: Long, val size: Long)

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
        check(Files.isDirectory(root, NOFOLLOW_LINKS))
        FileChannel.open(root.resolve(".lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.tryLock().use { lock ->
                check(lock != null) { "Clipboard cache in use" }
                val directories = Files.list(root).use { stream -> stream.filter { it.fileName.toString() != ".lock" }.toList() }
                check(directories.size <= maxEntries) { "Clipboard cache full" }
                // Validate the entire owned inventory before attempting any removal.
                val entries = directories.map(::inspect)
                val current = runCatching {
                    val content = clipboard.getContents(null)
                    if (content == null || !content.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) emptySet()
                    else (content.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).map {
                        (it as java.io.File).toPath().toAbsolutePath().normalize()
                    }.toSet()
                }.getOrNull()
                val time = now()
                val retained = entries.filter { entry ->
                    val expired = time >= entry.created && time - entry.created >= RETENTION_MILLIS
                    if (current != null && expired && entry.file.toAbsolutePath().normalize() !in current) {
                        Files.delete(entry.file)
                        Files.delete(entry.directory.resolve("owner.txt"))
                        Files.delete(entry.directory)
                        false
                    } else true
                }
                check(retained.size < maxEntries && retained.sumOf { it.size } + bytes.size <= maxBytes) { "Clipboard cache full; save the result instead" }
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
        check(directory.fileName.toString().matches(Regex("copy-[0-9a-f-]{36}")) && Files.isDirectory(directory, NOFOLLOW_LINKS))
        val record = directory.resolve("owner.txt")
        check(Files.isRegularFile(record, NOFOLLOW_LINKS) && Files.size(record) <= 256)
        val fields = Files.readString(record).split('\n')
        check(fields.size == 4 && fields[0] == "CCB-clipboard-v1" && fields[2] in listOf("png", "gif", "webp"))
        val created = fields[1].toLong()
        val file = directory.resolve("image.${fields[2]}")
        check(Files.list(directory).use { it.count() } == 2L && Files.isRegularFile(file, NOFOLLOW_LINKS))
        val size = Files.size(file)
        check(size in 1..ApngDisguiseCodec.MAX_OUTPUT_BYTES && hash(Files.readAllBytes(file)) == fields[3])
        return Entry(directory, file, created, size)
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
