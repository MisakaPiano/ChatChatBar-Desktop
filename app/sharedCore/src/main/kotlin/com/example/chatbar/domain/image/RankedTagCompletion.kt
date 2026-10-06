package com.example.chatbar.domain.image

import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

fun validCompletionTag(name: String): Boolean = name.length in 1..200 &&
    name.none { it.isWhitespace() || it == ',' || it.code !in 0x21..0x7e }

/** UTF-16 encoding shared with the offline compiler; singles and pairs cannot collide. */
fun completionGrams(text: String): Set<Long> = buildSet {
    text.forEachIndexed { index, char ->
        add(char.code + 1L)
        if (index > 0) add(((text[index - 1].code + 1L) shl 17) or (char.code + 1L))
    }
}

suspend fun searchSharedRankedIndex(
    database: NovelAiSqlDatabase,
    query: String,
    dictionary: Boolean,
    onCandidate: suspend (NovelAiTagCandidate) -> Unit
) {
    val grams = completionGrams(query).let { all ->
        if (query.length > 1) all.filter { it > 0x10000L } else all.toList()
    }
    if (grams.isEmpty()) return
    var rarest = 0L
    var minimum = Long.MAX_VALUE
    for (gram in grams) {
        currentCoroutineContext().ensureActive()
        val count = database.rawQuery("SELECT n FROM grams WHERE gram = ?", arrayOf(gram.toString())).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
        if (count == 0L) return
        if (count < minimum) { minimum = count; rarest = gram }
    }
    val encoded = database.rawQuery("SELECT ranks FROM grams WHERE gram=?", arrayOf(rarest.toString())).use {
        check(it.moveToFirst()) { "补全索引缺少候选列表" }
        it.getBlob(0)
    }
    val ranks = RankedPostingReader(encoded)
    // Android SQLiteCursor counts the whole SQL result on its first window fill.
    // Bound physical reads with ranked ID pages; each individual match is still emitted
    // immediately, without collecting a page of matches or waiting for its completion.
    while (ranks.hasNext()) {
        currentCoroutineContext().ensureActive()
        val page = ArrayList<String>(64)
        while (page.size < 64 && ranks.hasNext()) page.add(ranks.next().toString())
        val placeholders = page.joinToString(",") { "?" }
        // Only this bounded physical page is visited, in its precomputed rank order.
        val sql = "SELECT name,cn_name,post_count,category,a,b FROM entries WHERE rank IN ($placeholders) ORDER BY rank"
        database.rawQuery(sql, page.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                if (!cursor.getString(4).contains(query) && !cursor.getString(5).contains(query)) continue
                val translated = cursor.getString(1).orEmpty()
                onCandidate(NovelAiTagCandidate(
                    name = cursor.getString(0),
                    translatedName = if (dictionary) translated else translated.replace(translationWhitespace, " ").trim().take(200),
                    count = cursor.getLong(2),
                    category = NovelAiTagCategory.fromCode(cursor.getInt(3)) ?: continue,
                    fromDictionary = dictionary
                ))
            }
        }
    }
}

class RankedPostingReader(private val encoded: ByteArray) {
    private var offset = 0
    private var rank = 0L
    fun hasNext(): Boolean = offset < encoded.size
    fun next(): Long {
        var delta = 0L
        var shift = 0
        while (true) {
            check(offset < encoded.size && shift <= 56) { "补全索引候选编码损坏" }
            val byte = encoded[offset++].toInt() and 255
            delta = delta or ((byte and 127).toLong() shl shift)
            if (byte < 128) break
            shift += 7
        }
        rank += delta
        return rank
    }
}

private val translationWhitespace = Regex("\\s+")

class TagCompletionCache {
    private val values = LinkedHashMap<String, List<NovelAiTagCandidate>>(16, 0.75f, true)
    private var count = 0
    @Synchronized fun get(version: String, query: String): List<NovelAiTagCandidate>? = values["$version\n$query"]
    @Synchronized fun put(version: String, query: String, candidates: List<NovelAiTagCandidate>) {
        if (candidates.size > 10_000) return
        val key = "$version\n$query"
        values.remove(key)?.let { count -= it.size }
        values[key] = candidates.toList()
        count += candidates.size
        while (values.size > 128 || count > 10_000) {
            val iterator = values.entries.iterator()
            count -= iterator.next().value.size
            iterator.remove()
        }
    }
}

fun completionQueryKey(query: String): String = query.normalizeDanbooruTagQuery().lowercase(Locale.ROOT) +
    "\n" + query.trim().replace('_', ' ').lowercase(Locale.ROOT)
