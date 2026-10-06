package com.example.chatbar.desktop

import com.example.chatbar.domain.image.*
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Installs immutable, verified auxiliary databases. No entity/package schema or credentials. */
internal class DesktopNovelAiCatalogAssets(
    val root: Path,
    val open: (String) -> InputStream,
) {
    private val json = Json { ignoreUnknownKeys = true }
    fun metadata(name: String): JsonObject = open(name).bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }

    @Synchronized fun install(asset: String, hash: String, bytes: Long, gitBlob: Boolean = false): Path {
        require(hash.matches(Regex(if (gitBlob) "[a-f0-9]{40}" else "[a-f0-9]{64}")))
        require(bytes in 1..256_000_000)
        Files.createDirectories(root)
        val target = root.resolve("$hash.sqlite")
        fun digest(path: Path): String {
            val md = MessageDigest.getInstance(if (gitBlob) "SHA-1" else "SHA-256")
            if (gitBlob) md.update("blob $bytes\u0000".toByteArray())
            Files.newInputStream(path).use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = stream.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        if (Files.exists(target)) {
            check(Files.size(target) == bytes && digest(target) == hash) { "NovelAI 辅助数据库校验失败" }
            return target
        }
        val temporary = root.resolve("$hash.${UUID.randomUUID()}.tmp")
        try {
            GZIPInputStream(open(asset)).use { input ->
                Files.newOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        written += n; check(written <= bytes) { "辅助数据库大小超出声明" }
                        output.write(buffer, 0, n)
                    }
                }
            }
            check(Files.size(temporary) == bytes && digest(temporary) == hash) { "NovelAI 辅助数据库完整性校验失败" }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporary) }
        return target
    }

    fun index(kind: String, source: String): Path {
        val meta = metadata("tag_completion/$kind.json")
        check(meta.getValue("format").jsonPrimitive.int == 2 && meta.getValue("source").jsonPrimitive.content == source)
        return install("tag_completion/$kind.sqlite.binz", meta.getValue("sha256").jsonPrimitive.content,
            meta.getValue("bytes").jsonPrimitive.long)
    }
}

internal class DesktopNovelAiSqlDatabase(path: Path) : NovelAiSqlDatabase, AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${path.toUri()}?mode=ro")
    init { connection.createStatement().use { it.execute("PRAGMA query_only=ON") } }
    override fun rawQuery(sql: String, args: Array<String>): NovelAiSqlCursor {
        val statement = connection.prepareStatement(sql)
        try {
            statement.queryTimeout = 30
            args.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            val result = statement.executeQuery()
            return object : NovelAiSqlCursor {
                override fun moveToFirst() = result.next()
                override fun moveToNext() = result.next()
                override fun getString(index: Int) = result.getString(index + 1).orEmpty()
                override fun getLong(index: Int) = result.getLong(index + 1)
                override fun getInt(index: Int) = result.getInt(index + 1)
                override fun getBlob(index: Int): ByteArray = result.getBytes(index + 1)
                override fun close() { try { result.close() } finally { statement.close() } }
            }
        } catch (failure: Throwable) { statement.close(); throw failure }
    }
    override fun close() = connection.close()
}

internal class DesktopNovelAiTagCatalog(private val assets: DesktopNovelAiCatalogAssets) : NovelAiTagLookup, NovelAiCompletionCatalog {
    private val bundled by lazy {
        Json { ignoreUnknownKeys = true }.decodeFromJsonElement<DanbooruCatalogMetadata>(assets.metadata("danbooru/catalog.json"))
    }
    private data class Snapshot(val metadata: DanbooruCatalogMetadata, val database: Path, val index: Path)
    @Volatile private var active: Snapshot? = null
    @Synchronized private fun snapshot(): Snapshot {
        active?.let { return it }
        val manifest = assets.root.resolve("active-catalog.json")
        val next = if (Files.exists(manifest)) {
            val metadata = Json.decodeFromString<DanbooruCatalogMetadata>(Files.readString(manifest))
            require(metadata.sourceSha.matches(Regex("[a-f0-9]{40}")))
            val database = assets.root.resolve("${metadata.sourceSha}.sqlite")
            val index = assets.root.resolve("index-${metadata.sourceSha}.sqlite")
            DesktopNovelAiCatalogUpdate.verify(database, metadata.sourceSizeBytes, metadata.sourceSha)
            DesktopNovelAiSqlDatabase(index).use { db -> db.rawQuery("SELECT version,source FROM metadata", emptyArray()).use {
                check(it.moveToFirst() && it.getInt(0) == 2 && it.getString(1) == metadata.sourceSha)
            } }
            Snapshot(metadata, database, index)
        } else Snapshot(bundled,
            assets.install("danbooru/tag.sqlite.bundle", bundled.sourceSha, bundled.sourceSizeBytes, gitBlob = true),
            assets.index("danbooru", bundled.sourceSha))
        active = next
        return next
    }
    fun requireDurableMetadata(expected: DanbooruCatalogMetadata) {
        val manifest = assets.root.resolve("active-catalog.json")
        val durable = if (Files.exists(manifest)) Json.decodeFromString<DanbooruCatalogMetadata>(Files.readString(manifest)) else bundled
        check(durable == expected) { "词库版本已更改" }
    }
    val updateRoot get() = assets.root
    @Synchronized fun activated() { active = null; synchronized(cache) { cache.clear() }; version.value = snapshot().metadata.sourceSha }
    private val version = MutableStateFlow("")
    override val completionVersion = version.asStateFlow()
    private val cache = object : LinkedHashMap<String, List<NovelAiTagCandidate>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<NovelAiTagCandidate>>?) = size > 128
    }
    override suspend fun catalogMetadata() = withContext(Dispatchers.IO) { snapshot().metadata }
    override suspend fun search(query: String): NovelAiTagSearchOutcome = withContext(Dispatchers.IO) {
        val normalized = query.normalizeDanbooruTagQuery()
        require(normalized.length in 2..80) { "Danbooru 词条查询长度必须在 2..80 之间" }
        val snapshot = snapshot()
        val key = snapshot.metadata.sourceSha + ":" + normalized.lowercase(Locale.ROOT)
        synchronized(cache) { cache[key] }?.let { return@withContext NovelAiTagSearchOutcome(normalized, it, fromCache = true) }
        val result = DesktopNovelAiSqlDatabase(snapshot.database).use { queryNovelAiCandidates(it, snapshot.metadata.tableName, normalized, MAX_TAG_CANDIDATES_PER_QUERY) }
        currentCoroutineContext().ensureActive()
        synchronized(cache) { cache[key] = result }
        NovelAiTagSearchOutcome(normalized, result)
    }
    override suspend fun exactChineseTranslations(names: Collection<String>): Map<String, String> = withContext(Dispatchers.IO) {
        val snapshot = snapshot()
        DesktopNovelAiSqlDatabase(snapshot.database).use { queryNovelAiTranslations(it, snapshot.metadata.tableName, names) }
    }
    override suspend fun prepareCompletion() = withContext(Dispatchers.IO) { version.value = snapshot().metadata.sourceSha }
    override suspend fun streamCompletion(query: String, onCandidate: suspend (NovelAiTagCandidate) -> Unit, onWarning: (String) -> Unit) = withContext(Dispatchers.IO) {
        val normalized = query.normalizeDanbooruTagQuery().lowercase(Locale.ROOT)
        if (normalized.isNotBlank()) DesktopNovelAiSqlDatabase(snapshot().index).use { searchSharedRankedIndex(it, normalized, false, onCandidate) }
    }
}

internal class DesktopNovelAiDictionary(private val assets: DesktopNovelAiCatalogAssets) : NovelAiDictionarySource {
    private val metadata by lazy { assets.metadata("prompt_dictionary/metadata.json") }
    private val source by lazy { metadata.getValue("databaseSha256").jsonPrimitive.content }
    private val database by lazy { assets.install("prompt_dictionary/ecdict.sqlite.binz", source, metadata.getValue("databaseBytes").jsonPrimitive.long) }
    private val index by lazy { assets.index("dictionary", source) }
    override fun lookup(word: String): String? = DesktopNovelAiSqlDatabase(database).use { db ->
        db.rawQuery("SELECT meaning FROM words WHERE word = ?", arrayOf(word)).use { if (it.moveToFirst()) it.getString(0) else null }
    }
    override fun search(query: String): List<NovelAiTagCandidate> = DesktopNovelAiSqlDatabase(database).use { db ->
        queryNovelAiDictionary(db, query)
    }
    override suspend fun prepareCompletion() = withContext(Dispatchers.IO) { index; Unit }
    override suspend fun streamCompletion(query: String, onCandidate: suspend (NovelAiTagCandidate) -> Unit, onWarning: (String) -> Unit) = withContext(Dispatchers.IO) {
        DesktopNovelAiSqlDatabase(index).use { searchSharedRankedIndex(it, query, true, onCandidate) }
    }
}
