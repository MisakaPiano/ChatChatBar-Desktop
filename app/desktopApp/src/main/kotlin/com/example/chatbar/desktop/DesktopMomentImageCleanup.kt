package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.MomentPost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Write-before-delete exact candidates. Startup retries only receipts whose post is provably absent. */
internal class DesktopMomentImageCleanup(
    private val root: Path,
    private val storage: JsonFileStorage,
    private val gate: DesktopDataOperationCoordinator,
    private val cleaner: DesktopOwnedImageCleanup,
) {
    private val home = root.resolve("backups/.moment-image-cleanup")

    suspend fun prepare(post: MomentPost): String? = withContext(Dispatchers.IO) {
        requireMomentStorageId(post.id)
        val candidates = (DesktopMomentImageVersions.retained(post).map { it.imagePath } +
            listOfNotNull(post.imagePath, post.senderAvatar)).distinct().filter { it.startsWith("images/") }
        if (candidates.isEmpty()) return@withContext null
        writeReceipt(post.id, candidates)
    }

    /** Called synchronously on IO before CREATE_NEW materializes the exact generated image. */
    fun prepareCandidate(reference: String): String {
        require(Regex("images/[A-Za-z0-9][A-Za-z0-9_.-]{0,179}").matches(reference))
        return writeReceipt(null, listOf(reference))
    }

    private fun writeReceipt(postId: String?, candidates: Collection<String>): String {
        require(candidates.size in 1..DesktopMomentImageVersions.MAX_CANDIDATES + 1 &&
            candidates.distinct().size == candidates.size && candidates.all { it.startsWith("images/") && it.length <= 1024 })
        DesktopArchiveFiles.safe(root, true)
        for (folder in listOf(root.resolve("backups"), home)) {
            if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(folder)
            DesktopArchiveFiles.safe(folder, true)
        }
        val id = UUID.randomUUID().toString()
        val content = buildJsonObject {
            put("version", if (candidates.size > 2) 2 else 1); put("postId", postId?.let(::JsonPrimitive) ?: JsonNull)
            put("candidates", buildJsonArray { candidates.forEach { add(JsonPrimitive(it)) } })
        }.toString().toByteArray()
        java.nio.channels.FileChannel.open(home.resolve("$id.json"), StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE).use { file ->
            val buffer = java.nio.ByteBuffer.wrap(content)
            while (buffer.hasRemaining()) file.write(buffer)
            file.force(true)
        }
        return id
    }

    suspend fun finish(id: String) = withContext(Dispatchers.IO) {
        require(Regex("[a-f0-9-]{36}").matches(id))
        val path = home.resolve("$id.json")
        val receipt = gate.withNormalOperation {
            DesktopArchiveFiles.safe(home, true); DesktopArchiveFiles.safe(path, false)
            check(Files.size(path) <= (DesktopMomentImageVersions.MAX_CANDIDATES + 1L) * 1040 + 4096)
            val bytes = Files.readAllBytes(path)
            val value = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            check(value.keys == setOf("version", "postId", "candidates"))
            val version = value.getValue("version").jsonPrimitive.int
            check(version in 1..2)
            if (version == 1) check(bytes.size <= 16384)
            val postId = value.getValue("postId").takeUnless { it == JsonNull }?.jsonPrimitive?.content
            if (postId != null) {
                requireMomentStorageId(postId)
                val read = storage.readEntityStrict("moment_posts", postId, MomentPost.serializer())
                when (read) {
                    JsonFileStorage.EntityReadResult.Missing -> Unit
                    is JsonFileStorage.EntityReadResult.Valid -> return@withNormalOperation null
                    else -> error("动态删除清理尚未确认；原文件和记录保留")
                }
            }
            val candidates = value.getValue("candidates").jsonArray.map { it.jsonPrimitive.content }
            check(candidates.size in 1..(if (version == 1) 2 else DesktopMomentImageVersions.MAX_CANDIDATES + 1) &&
                candidates.distinct().size == candidates.size && candidates.all { it.startsWith("images/") && it.length <= 1024 })
            bytes to candidates
        } ?: return@withContext
        cleaner.deleteUnreferenced(receipt.second)
        gate.withNormalOperation {
            DesktopArchiveFiles.safe(path, false)
            check(Files.readAllBytes(path).contentEquals(receipt.first))
            Files.delete(path)
        }
    }

    suspend fun resume(): Boolean = withContext(Dispatchers.IO) {
        if (!Files.exists(home, LinkOption.NOFOLLOW_LINKS)) return@withContext true
        DesktopArchiveFiles.safe(home, true)
        val ids = Files.newDirectoryStream(home, "*.json").use { files -> files.map {
            it.fileName.toString().removeSuffix(".json")
        }.toList() }
        var complete = true
        for (id in ids) try { finish(id) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { complete = false }
        complete
    }
}
