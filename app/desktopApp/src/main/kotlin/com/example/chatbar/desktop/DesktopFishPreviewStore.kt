package com.example.chatbar.desktop

import java.io.InputStream
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** One newly created picker directory, outside the business root. Only recorded own files
 * are deleted; unknown/replaced entries are retained and reported, never recursively removed.
 */
internal class DesktopFishPreviewStore(private val directory: Path = Files.createTempDirectory("ccb-fish-preview-"),
    private val maxClipBytes: Long = 16L * 1024 * 1024, private val maxSessionBytes: Long = 64L * 1024 * 1024,
) {
    private val directoryKey = identity(directory, true)
    private val files = linkedMapOf<Path, Any>()
    private val hashes = mutableMapOf<Path, String>()
    private var total = 0L
    init { require(maxClipBytes > 0 && maxSessionBytes >= maxClipBytes) }

    fun write(input: InputStream, expected: Long, active: () -> Unit): Path {
        verifyDirectory()
        check(files.size < 16) { "预览缓存数量已达上限" }
        check(expected <= maxClipBytes && expected <= maxSessionBytes - total) { "预览音频超过容量限制" }
        val part = directory.resolve("${UUID.randomUUID()}.part")
        Files.newOutputStream(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
            files[part] = identity(part, false)
            val buffer = ByteArray(16_384)
            var count = 0L
            while (true) {
                active()
                val size = input.read(buffer)
                if (size < 0) break
                check(size.toLong() <= maxClipBytes - count && size.toLong() <= maxSessionBytes - total) { "预览音频超过容量限制" }
                output.write(buffer, 0, size); count += size; total += size
            }
            check(count > 0 && (expected < 0 || expected == count)) { "预览音频不完整" }
        }
        active(); verify(part)
        DesktopMp3Validation.validate(part, maxClipBytes)
        active(); verifyDirectory()
        val final = directory.resolve("${UUID.randomUUID()}.mp3")
        // No non-atomic fallback and no replacement of another clip.
        Files.move(part, final, StandardCopyOption.ATOMIC_MOVE)
        files[final] = files.remove(part)!!
        hashes[final] = hash(final)
        verify(final)
        return final
    }

    fun verify(path: Path) {
        verifyDirectory()
        check(path.parent == directory && files[path] == identity(path, false)) { "预览文件身份发生变化" }
        hashes[path]?.let { check(it == hash(path)) { "预览音频内容发生变化" } }
    }
    fun close() {
        verifyDirectory()
        for ((path, key) in files.toMap()) {
            check(key == identity(path, false)) { "预览清理身份不一致；文件已保留" }
            Files.delete(path); files.remove(path); hashes.remove(path)
        }
        // Fails on unknown entries; never delete them to manufacture a clean cache.
        Files.delete(directory)
    }
    private fun verifyDirectory() { check(directoryKey == identity(directory, true)) }
    private fun hash(path: Path): String {
        check(Files.size(path) <= maxClipBytes)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { stream ->
            val buffer = ByteArray(16_384)
            while (true) {
                val n = stream.read(buffer); if (n < 0) break
                check(n.toLong() <= maxClipBytes - bytes); bytes += n; digest.update(buffer, 0, n)
            }
        }
        check(files[path] == identity(path, false))
        return java.util.HexFormat.of().formatHex(digest.digest())
    }

    private companion object {
        fun identity(path: Path, directory: Boolean): Any {
            val attrs = attributes(path, directory)
            return if (System.getProperty("os.name").startsWith("Windows"))
                DesktopIndexStartupPreflight.readWindowsIdentity(path, directory)
            else requireNotNull(attrs.fileKey())
        }
        fun attributes(path: Path, directory: Boolean): BasicFileAttributes {
            val absolute = path.toAbsolutePath().normalize()
            check(absolute == path && path.toRealPath() == absolute) { "不安全的预览路径" }
            var parent: Path? = absolute
            while (parent != null) {
                val a = Files.readAttributes(parent, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                check(!a.isSymbolicLink && !a.isOther) { "不安全的预览路径" }
                parent = parent.parent
            }
            return Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).also {
                check(if (directory) it.isDirectory else it.isRegularFile)
            }
        }
    }
}

/** Bounded structural MP3 Layer-III validation, followed by native decoder duration validation
 * before play. ID3 alone, sync-byte guesses, truncated frames and trailing junk are rejected.
 */
internal object DesktopMp3Validation {
    fun validate(path: Path, limit: Long, maximumDurationMs: Long = 120_000): Long {
        require(maximumDurationMs in 1..1_800_000)
        val size = Files.size(path)
        check(size in 8..limit) { "MP3 大小无效" }
        java.io.RandomAccessFile(path.toFile(), "r").use { input ->
            var position = 0L
            if (input.read() == 'I'.code && input.read() == 'D'.code && input.read() == '3'.code) {
                val version = input.read(); input.read(); val flags = input.read()
                check(version in 2..4)
                var tag = 0L
                repeat(4) { val b = input.read(); check(b in 0..127); tag = tag * 128 + b }
                position = 10 + tag + if (version == 4 && flags and 16 != 0) 10 else 0
                check(position <= size)
            }
            var frames = 0
            var duration = 0.0
            var signature: Int? = null
            while (position < size) {
                input.seek(position)
                if (size - position == 128L && input.read() == 'T'.code && input.read() == 'A'.code && input.read() == 'G'.code) break
                input.seek(position); check(size - position >= 4)
                val h = input.readInt()
                val version = (h ushr 19) and 3
                val layer = (h ushr 17) and 3
                val rateIndex = (h ushr 10) and 3
                val bitIndex = (h ushr 12) and 15
                check((h ushr 21) == 0x7ff && version != 1 && layer == 1 && rateIndex != 3 && bitIndex in 1..14) { "MP3 帧无效" }
                val sig = version * 4 + rateIndex
                check(signature == null || signature == sig); signature = sig
                val rate = intArrayOf(44100, 48000, 32000)[rateIndex] / when(version) { 3 -> 1; 2 -> 2; else -> 4 }
                val rates = if (version == 3) intArrayOf(0,32,40,48,56,64,80,96,112,128,160,192,224,256,320)
                    else intArrayOf(0,8,16,24,32,40,48,56,64,80,96,112,128,144,160)
                val length = (if (version == 3) 144000L else 72000L) * rates[bitIndex] / rate + ((h ushr 9) and 1)
                check(length >= 4 && length <= size - position) { "MP3 帧被截断" }
                position += length; frames++
                duration += (if (version == 3) 1152.0 else 576.0) / rate
                check(duration * 1000 <= maximumDurationMs) { "音频时长超过 $maximumDurationMs ms 限制" }
            }
            check(frames >= 2 && duration > 0) { "MP3 音频帧缺失" }
            return (duration * 1000).toLong()
        }
    }
}
