package com.example.chatbar.domain.image

import java.io.File
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
        val hash = digest(bytes)
        if (!Files.exists(root, NOFOLLOW_LINKS)) Files.createDirectories(root)
        checkRoot()
        val reference = PREFIX + hash
        val target = root.resolve(hash)
        if (Files.exists(target, NOFOLLOW_LINKS)) { require(resolve(reference) == value); return reference }
        val temporary = root.resolve(".payload-${UUID.randomUUID()}.tmp")
        try {
            Files.write(temporary, bytes, CREATE_NEW)
            try {
                // No REPLACE_EXISTING: an unknown collision must never be overwritten.
                Files.move(temporary, target)
            } catch (_: FileAlreadyExistsException) {
                require(resolve(reference) == value)
            }
            require(resolve(reference) == value)
            return reference
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val PREFIX = "chatbar-vibe-file:"
        const val INLINE_LIMIT = 4096
    }
}
