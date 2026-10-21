package com.example.chatbar.desktop

import com.example.chatbar.domain.voice.FishAudioStoredAudio
import java.io.InputStream
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Durable audio, never a preview directory. Unknown/changed files are preserved.
 * No startup orphan sweep: an incomplete entity inventory is not deletion authority.
 * Immutable UUID artifacts have a small ownership receipt; a crash between publication
 * and entity commit leaves recoverable evidence rather than deleting uncertain audio.
 */
internal class DesktopVoiceAudioStore(dataRoot: Path, private val extendedPaths: Boolean = false,
    // Optional invocation-local owner for isolated backup staging; ordinary audio retention is unchanged.
    private val onCreated: (Path) -> Unit = {},
) {
    private val dataRoot = dataRoot.toAbsolutePath().normalize()
    private val root = this.dataRoot.resolve("audio/generated")
    private val mutex = Mutex()
    private val limit = 16L * 1024 * 1024

    suspend fun persist(sessionId: String, artifactId: String, input: InputStream,
        expected: Long = -1, onBytes: (Long) -> Unit = {},
    ): FishAudioStoredAudio = mutex.withLock { withContext(Dispatchers.IO) {
        val dir = root.resolve(segment(sessionId))
        ensureDirectory(dir)
        val directoryIdentity = identity(dir)
        val path = dir.resolve("${segment(artifactId)}.mp3")
        check(!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { "音频身份冲突；旧文件已保留" }
        require(expected in -1..limit)
        val part = dir.resolve(".${UUID.randomUUID()}.part")
        var published = false
        var partIdentity: Any? = null
        try {
            var size = 0L
            Files.newOutputStream(part, StandardOpenOption.CREATE_NEW).use { output ->
                partIdentity = identity(part)
                onCreated(part)
                val buffer = ByteArray(16384)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    check(count <= limit - size) { "音频超过 16 MiB" }
                    output.write(buffer, 0, count); size += count; onBytes(size)
                }
            }
            check(size > 0 && (expected < 0 || expected == size)) { "音频下载不完整" }
            check(partIdentity == identity(part)) { "下载文件身份变化；已保留" }
            val duration = DesktopMp3Validation.validate(part, limit, 1_800_000)
            currentCoroutineContext().ensureActive(); verifyPath(dir, true)
            check(directoryIdentity == identity(dir) && partIdentity == identity(part))
            // No replacement or non-atomic fallback. Receipt publication precedes entity admission.
            Files.move(part, path, StandardCopyOption.ATOMIC_MOVE); published = true
            onCreated(path)
            Files.newOutputStream(receipt(path), StandardOpenOption.CREATE_NEW).use { output ->
                onCreated(receipt(path))
                output.write("$size\n${hash(path)}\n".toByteArray())
            }
            FishAudioStoredAudio(path.toString(), duration, size)
        } finally {
            // Only this invocation's uniquely created partial file; published artifacts survive errors.
            if (!published && Files.exists(part, LinkOption.NOFOLLOW_LINKS)) {
                verifyPath(part, false)
                if (partIdentity == identity(part) && directoryIdentity == identity(dir)) Files.delete(part)
            }
        }
    } }

    suspend fun verify(raw: String): Path = mutex.withLock { withContext(Dispatchers.IO) { verifyOwned(raw) } }

    suspend fun deleteIfOwned(raw: String): Boolean = mutex.withLock { withContext(Dispatchers.IO) {
        val path = Path.of(raw).toAbsolutePath().normalize()
        if (!path.startsWith(root) || path.parent?.parent != root) return@withContext false
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@withContext !Files.exists(receipt(path))
        verifyOwned(raw) // Ambiguous identity/hash/receipt fails closed, never deletes external media.
        Files.delete(path); Files.delete(receipt(path)); true
    } }

    private fun verifyOwned(raw: String): Path {
        val path = Path.of(raw)
        check(path.isAbsolute && path.normalize() == path && path.parent?.parent == root && path.startsWith(root))
        check(path.fileName.toString().matches(Regex("[a-zA-Z0-9_-]+\\.mp3")))
        verifyPath(path, false); verifyPath(receipt(path), false)
        check(Files.size(receipt(path)) < 128)
        val proof = Files.readAllLines(receipt(path))
        check(proof.size == 2 && proof[0].toLongOrNull() == Files.size(path) && proof[1] == hash(path)) {
            "音频身份无法确认；文件已保留"
        }
        DesktopMp3Validation.validate(path, limit, 1_800_000)
        return path
    }
    private fun receipt(path: Path) = path.resolveSibling("${path.fileName}.owner")
    private fun segment(id: String): String {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) { "音频资源 ID 无效" }; return id
    }
    private fun hash(path: Path): String {
        check(Files.size(path) <= limit)
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { stream ->
            val buf = ByteArray(16384); var total = 0L
            while (true) { val n = stream.read(buf); if (n < 0) break
                check(n <= limit - total); total += n; digest.update(buf, 0, n) }
        }
        return java.util.HexFormat.of().formatHex(digest.digest())
    }
    private fun identity(path: Path): Any {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        return if (System.getProperty("os.name").startsWith("Windows"))
            DesktopIndexStartupPreflight.readWindowsIdentity(path, attrs.isDirectory, extendedPaths)
        else checkNotNull(attrs.fileKey())
    }
    private fun ensureDirectory(directory: Path) {
        verifyPath(dataRoot, true)
        var current = dataRoot
        dataRoot.relativize(directory).forEach { name ->
            current = current.resolve(name)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) { Files.createDirectory(current); onCreated(current) }
            verifyPath(current, true)
        }
    }
    private fun verifyPath(path: Path, directory: Boolean) {
        check(path.toRealPath() == path)
        var current: Path? = path
        while (current != null) {
            val attrs = Files.readAttributes(current, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            check(!attrs.isSymbolicLink && !attrs.isOther)
            if (current == path) check(if (directory) attrs.isDirectory else attrs.isRegularFile)
            current = current.parent
        }
    }
}
