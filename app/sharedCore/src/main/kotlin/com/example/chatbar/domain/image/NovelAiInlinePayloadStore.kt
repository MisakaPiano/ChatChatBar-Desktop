package com.example.chatbar.domain.image

import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.Writer
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.UUID

/** Official147 content-addressed Vibe layout. The caller owns its data-operation admission. */
class NovelAiInlinePayloadStore(filesDir: File) {
    private val filesRoot = filesDir.toPath().toAbsolutePath().normalize()
    private val root = filesRoot.resolve("novelai-vibe-payloads")

    private fun checkRoot() {
        require(Files.isDirectory(root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) { "氛围编码目录不可用" }
        require(root.toRealPath() == filesRoot.toRealPath().resolve("novelai-vibe-payloads")) { "氛围编码目录无效" }
    }

    fun resolve(value: String): String {
        if (!value.startsWith(PREFIX)) return value
        val hash = value.removePrefix(PREFIX)
        require(hash.matches(Regex("[a-f0-9]{64}"))) { "氛围编码引用无效" }
        checkRoot()
        val file = root.resolve(hash)
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)) { "氛围编码文件缺失或不可用" }
        val bytes = Files.readAllBytes(file)
        require(digest(bytes) == hash) { "氛围编码文件校验失败" }
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        require(text.isNotBlank() && !text.startsWith(PREFIX)) { "氛围编码文件内容无效" }
        return text
    }

    fun retain(value: String): String {
        if (value.startsWith(PREFIX)) { resolve(value); return value }
        if (value.length < INLINE_LIMIT) return value
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(value.isNotBlank()) { "氛围编码文件内容无效" }
        val temporary = newTemporary()
        try {
            Files.write(temporary, bytes, CREATE_NEW)
            return publish(temporary) {}
        } finally { Files.deleteIfExists(temporary) }
    }

    fun openCompactJson(file: File, checkActive: () -> Unit = {}): InputStream =
        compactJson(Files.newInputStream(file.toPath()), checkActive)

    /** Source opening belongs to the storage reader so only that failure can be Missing. */
    internal fun compactJson(source: InputStream, checkActive: () -> Unit): InputStream = try {
        NovelAiCompactJsonInput(source, this, checkActive)
    } catch (error: Throwable) {
        try { source.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
        throw error
    }

    private fun newTemporary(): java.nio.file.Path {
        if (!Files.exists(root, NOFOLLOW_LINKS)) Files.createDirectories(root)
        checkRoot()
        return root.resolve(".payload-${UUID.randomUUID()}.tmp")
    }

    private fun fileDigest(file: java.nio.file.Path, checkActive: () -> Unit): Pair<String, Long> {
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)) { "氛围编码文件不可用" }
        val digest = MessageDigest.getInstance("SHA-256"); var size = 0L
        Files.newInputStream(file).buffered().use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkActive(); val n = source.read(buffer); if (n < 0) break
                digest.update(buffer, 0, n); size += n
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } to size
    }

    private fun publish(temporary: java.nio.file.Path, checkActive: () -> Unit): String {
        checkRoot()
        val proof = fileDigest(temporary, checkActive); val target = root.resolve(proof.first)
        if (!Files.exists(target, NOFOLLOW_LINKS)) {
            try { Files.move(temporary, target) } catch (_: FileAlreadyExistsException) { }
        }
        require(fileDigest(target, checkActive) == proof) { "氛围编码文件校验失败" }
        return PREFIX + proof.first
    }

    internal fun retainDecodedJsonString(checkActive: () -> Unit, produce: (Writer) -> Unit): String {
        val buffered = StringBuilder(INLINE_LIMIT)
        var temporary: java.nio.file.Path? = null
        var output: Writer? = null
        var failure: Throwable? = null
        val sink = object : Writer() {
            override fun write(c: Int) {
                if (output == null && buffered.length < INLINE_LIMIT) buffered.append(c.toChar())
                else {
                    if (output == null) {
                        val path = newTemporary(); temporary = path
                        output = Files.newBufferedWriter(path, Charsets.UTF_8, CREATE_NEW)
                        output!!.write(buffered.toString()); buffered.setLength(0)
                    }
                    output!!.write(c)
                }
            }
            override fun write(chars: CharArray, off: Int, len: Int) { for (i in off until off + len) write(chars[i].code) }
            override fun flush() { output?.flush() }
            override fun close() { output?.close(); output = null }
        }
        try {
            produce(sink); sink.close()
            val path = temporary
            if (path != null) return publish(path, checkActive)
            val value = buffered.toString(); val bytes = value.toByteArray(Charsets.UTF_8)
            if (bytes.size < INLINE_LIMIT) return value
            val file = newTemporary(); temporary = file
            Files.write(file, bytes, CREATE_NEW)
            return publish(file, checkActive)
        } catch (cancelled: java.util.concurrent.CancellationException) { failure = cancelled; throw cancelled }
        catch (syntax: NovelAiCompactJsonSyntaxException) { failure = syntax; throw syntax }
        catch (error: Exception) { throw IOException("氛围编码派生文件读写失败", error).also { failure = it } }
        finally {
            val primary = failure
            var cleanupFailure: Exception? = null
            for (cleanup in listOf<() -> Unit>({ sink.close() }, { temporary?.let(Files::deleteIfExists); Unit })) {
                try { cleanup() } catch (error: Exception) {
                    if (primary != null) primary.addSuppressed(error)
                    else if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
                }
            }
            if (primary == null) cleanupFailure?.let { throw it }
        }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val PREFIX = "chatbar-vibe-file:"
        const val INLINE_LIMIT = 4096
    }
}
