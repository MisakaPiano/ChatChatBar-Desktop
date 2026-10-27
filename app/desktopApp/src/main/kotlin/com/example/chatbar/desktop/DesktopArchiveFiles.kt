package com.example.chatbar.desktop

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded archive IO. No replacement, recursive deletion, credentials or repository mutation. */
internal object DesktopArchiveFiles {
    fun safe(path: Path, directory: Boolean) {
        val absolute = path.toAbsolutePath().normalize()
        var current = absolute.root
        for (part in absolute) {
            current = current.resolve(part)
            val attributes = Files.readAttributes(current, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            check(!attributes.isSymbolicLink && !attributes.isOther)
            check(if (current == absolute && !directory) attributes.isRegularFile else attributes.isDirectory)
            if (System.getProperty("os.name").startsWith("Windows")) {
                val flags = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetFileAttributes(nativePath(current))
                check(flags != -1 && flags and 0x400 == 0) { "存档路径身份无法确认" }
            }
        }
    }

    suspend fun read(path: Path, maximum: Long, consume: suspend (ByteArray, Int) -> Unit): String {
        safe(path, false)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        suspend fun accept(bytes: ByteArray, count: Int) {
            currentCoroutineContext().ensureActive()
            check(count <= maximum - total) { "存档文件超过安全大小限制" }
            digest.update(bytes, 0, count); consume(bytes, count); total += count
        }
        if (System.getProperty("os.name").startsWith("Windows")) {
            val evidence = DesktopDocumentIndexReadEvidence({}, extendedPaths = true)
            val stamp = evidence.observe(path, directory = false)
            check(stamp.size <= maximum)
            evidence.read(path, stamp, ::accept)
        } else {
            val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            check(before.size() <= maximum)
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(32768)
                while (true) { val count = input.read(buffer); if (count < 0) break; accept(buffer, count) }
            }
            val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            check(before.fileKey() == after.fileKey() && before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun hash(path: Path, maximum: Long): String = read(path, maximum) { _, _ -> }

    internal fun nativePath(path: Path): String {
        val absolute = path.toAbsolutePath().normalize().toString()
        require(absolute.matches(Regex("[A-Za-z]:\\\\.*")) && absolute.length <= 1024) { "仅接受可验证的本机存档路径" }
        return "\\\\?\\$absolute"
    }

    suspend fun copyNew(source: Path, target: Path, maximum: Long): String {
        safe(target.parent, true)
        FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
            val hash = read(source, maximum) { bytes, count ->
                val buffer = ByteBuffer.wrap(bytes, 0, count)
                while (buffer.hasRemaining()) output.write(buffer)
            }
            output.force(true)
            return hash
        }
    }
}
