package com.example.chatbar.desktop

import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.update.DanbooruCatalogUpdateInfo
import java.nio.file.*
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** Immutable DB/index first, atomic authority pointer last. Old generations remain safe for readers. */
internal class DesktopNovelAiCatalogUpdate(private val catalog: DesktopNovelAiTagCatalog,
    private val client: OkHttpClient = OkHttpClient()) {
    suspend fun install(info: DanbooruCatalogUpdateInfo, report: (String) -> Unit) = withContext(Dispatchers.IO) {
        val current = catalog.catalogMetadata() // A corrupt current authority is never overwritten.
        check(current.sourceSha == info.currentMetadata.sourceSha)
        catalog.requireDurableMetadata(current)
        require(info.latestSourceSha.matches(Regex("[a-f0-9]{40}")) && info.sizeBytes in 1..256_000_000)
        val url = java.net.URI(info.downloadUrl)
        require(url.scheme == "https" && url.host == "raw.githubusercontent.com" && url.userInfo == null)
        val root = catalog.updateRoot
        Files.createDirectories(root)
        val stage = root.resolve("catalog-${UUID.randomUUID()}.part")
        val stagedIndex = root.resolve("index-${UUID.randomUUID()}.part")
        val stagedManifest = root.resolve("manifest-${UUID.randomUUID()}.part")
        try {
            val call = client.newCall(Request.Builder().url(info.downloadUrl).build())
            coroutineScope {
                val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.execute().use { response ->
                        check(response.isSuccessful) { "词库下载失败" }
                        requireNotNull(response.body).byteStream().use { input -> Files.newOutputStream(stage).use { output ->
                            val buffer = ByteArray(65536); var bytes = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer); if (n < 0) break
                                bytes += n; check(bytes <= info.sizeBytes)
                                output.write(buffer, 0, n); report("下载词库 $bytes/${info.sizeBytes}")
                            }
                        } }
                    }
                } finally { watcher.cancel() }
            }
            verify(stage, info.sizeBytes, info.latestSourceSha)
            val structure = DesktopNovelAiSqlDatabase(stage).use { validateNovelAiCatalogStructure(it) }
            report("构建词条补全索引")
            DesktopNovelAiSqlDatabase(stage).use { source ->
                DesktopNovelAiIndexWriter(stagedIndex).use { writer ->
                    buildNovelAiRankedIndex(writer, source, structure.tableName, info.latestSourceSha, "danbooru")
                }
            }
            val metadata = DanbooruCatalogMetadata(info.latestSourceSha, info.latestCommitTime, info.sizeBytes,
                structure.rowCount, structure.tableName)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                catalog.requireDurableMetadata(current)
                fun publish(from: Path, to: Path) { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                val target = root.resolve("${metadata.sourceSha}.sqlite")
                if (Files.exists(target)) verify(target, metadata.sourceSizeBytes, metadata.sourceSha) else publish(stage, target)
                val index = root.resolve("index-${metadata.sourceSha}.sqlite")
                // Reuse only validated derived data; no live version is replaced in place.
                if (!Files.exists(index)) publish(stagedIndex, index)
                DesktopNovelAiSqlDatabase(index).use { db -> db.rawQuery("SELECT version,source FROM metadata", emptyArray()).use {
                    check(it.moveToFirst() && it.getInt(0) == 2 && it.getString(1) == metadata.sourceSha)
                } }
                val encoded = Json.encodeToString(DanbooruCatalogMetadata.serializer(), metadata)
                java.io.FileOutputStream(stagedManifest.toFile()).use { it.write(encoded.toByteArray()); it.fd.sync() }
                publish(stagedManifest, root.resolve("active-catalog.json"))
                catalog.activated()
            }
            report("词库已更新")
        } finally {
            Files.deleteIfExists(stage); Files.deleteIfExists(stagedIndex); Files.deleteIfExists(stagedManifest)
        }
    }
    companion object {
        fun verify(path: Path, bytes: Long, sha: String) {
            check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) == bytes)
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update("blob $bytes\u0000".toByteArray())
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == sha)
        }
    }
}

internal class DesktopNovelAiIndexWriter(path: Path) : NovelAiIndexWriter, AutoCloseable {
    private val connection = DriverManager.getConnection("jdbc:sqlite:$path")
    private var committed = false
    override fun execSQL(sql: String, args: Array<Any>) {
        connection.prepareStatement(sql).use { statement -> args.forEachIndexed { i, value -> statement.setObject(i + 1, value) }; statement.execute() }
    }
    override fun rawQuery(sql: String, args: Array<String>): NovelAiSqlCursor {
        val statement = connection.prepareStatement(sql)
        args.forEachIndexed { i, value -> statement.setString(i + 1, value) }
        val result = statement.executeQuery()
        return object : NovelAiSqlCursor {
            override fun moveToFirst() = result.next()
            override fun moveToNext() = result.next()
            override fun getString(index: Int) = result.getString(index + 1).orEmpty()
            override fun getInt(index: Int) = result.getInt(index + 1)
            override fun getLong(index: Int) = result.getLong(index + 1)
            override fun getBlob(index: Int): ByteArray = result.getBytes(index + 1)
            override fun close() { result.close(); statement.close() }
        }
    }
    override fun compileStatement(sql: String): NovelAiIndexStatement {
        val statement = connection.prepareStatement(sql)
        return object : NovelAiIndexStatement {
            override fun bindLong(index: Int, value: Long) = statement.setLong(index, value)
            override fun bindString(index: Int, value: String) = statement.setString(index, value)
            override fun bindBlob(index: Int, value: ByteArray) = statement.setBytes(index, value)
            override fun executeInsert() { statement.executeUpdate() }
            override fun close() = statement.close()
        }
    }
    override fun beginTransaction() { committed = false; connection.autoCommit = false }
    override fun setTransactionSuccessful() { committed = true }
    override fun endTransaction() { if (committed) connection.commit() else connection.rollback(); connection.autoCommit = true }
    override fun close() = connection.close()
}
