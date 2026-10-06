package com.example.chatbar.domain.image

import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

fun queryNovelAiCandidates(
        database: NovelAiSqlDatabase,
        tableName: String,
        query: String,
        limit: Int
    ): List<NovelAiTagCandidate> {
        val lowercaseQuery = query.lowercase(Locale.ROOT)
        val escaped = escapeLike(lowercaseQuery)
        val exactChinese = query.replace(" ", "")
        val prefix = "$escaped%"
        val contains = "%$escaped%"
        val sql = """
            SELECT name, cn_name, post_count, category
            FROM ${quotedIdentifier(tableName)}
            WHERE category IN (0, 3, 4)
              AND (
                lower(name) LIKE ? ESCAPE '\'
                OR replace(lower(cn_name), ' ', '') LIKE ? ESCAPE '\'
              )
            ORDER BY CASE
                WHEN lower(name) = ? THEN 0
                WHEN replace(lower(cn_name), ' ', '') = ? THEN 0
                WHEN lower(name) LIKE ? ESCAPE '\' THEN 1
                WHEN replace(lower(cn_name), ' ', '') LIKE ? ESCAPE '\' THEN 1
                ELSE 2
              END,
              post_count DESC,
              lower(name) ASC
            LIMIT ?
        """.trimIndent()
        val args = arrayOf(
            contains,
            "%${escapeLike(exactChinese.lowercase(Locale.ROOT))}%",
            lowercaseQuery,
            exactChinese.lowercase(Locale.ROOT),
            prefix,
            "${escapeLike(exactChinese.lowercase(Locale.ROOT))}%",
            limit.coerceIn(1, MAX_TAG_CANDIDATES_PER_QUERY).toString()
        )
        return database.rawQuery(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) cursor.toCandidate()?.let(::add)
            }.distinctBy { it.name.lowercase(Locale.ROOT) }
        }
    }

fun NovelAiSqlCursor.toCandidate(): NovelAiTagCandidate? {
        val name = getString(0).orEmpty().trim()
        val category = NovelAiTagCategory.fromCode(getInt(3)) ?: return null
        if (!name.isValidDanbooruTagName()) return null
        return NovelAiTagCandidate(
            name = name,
            translatedName = getString(1).orEmpty().normalizeChineseName().take(200),
            count = getLong(2).coerceAtLeast(0L),
            category = category
        )
    }


private fun String.normalizeChineseName(): String = replace(Regex("\\s+"), " ").trim()
private fun String.isValidDanbooruTagName(): Boolean = validCompletionTag(this)
private fun escapeLike(value: String): String = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
private fun quotedIdentifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""

suspend fun queryNovelAiTranslations(database: NovelAiSqlDatabase, tableName: String, names: Collection<String>): Map<String, String> {
        val normalizedNames = names.asSequence()
            .map(String::normalizedTagQuery)
            .map { it.lowercase(Locale.ROOT) }
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (normalizedNames.isEmpty()) return emptyMap()
    return buildMap {
                normalizedNames.chunked(500).forEach { chunk ->
                    val placeholders = List(chunk.size) { "?" }.joinToString(",")
                    val sql = "SELECT name, cn_name FROM ${quotedIdentifier(tableName)} " +
                        "WHERE lower(name) IN ($placeholders)"
                    currentCoroutineContext().ensureActive()
                    database.rawQuery(sql, chunk.toTypedArray()).use { cursor ->
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(0).orEmpty().lowercase(Locale.ROOT)
                            val translated = cursor.getString(1).orEmpty().normalizeChineseName()
                            if (translated.isNotBlank()) put(name, translated)
                        }
                    }
                }
            }
}

fun queryNovelAiDictionary(database: NovelAiSqlDatabase, query: String): List<NovelAiTagCandidate> {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val pattern = "%$escaped%"
        return database.rawQuery(
            "SELECT word, meaning FROM words WHERE word LIKE ? ESCAPE '\\' " +
                "OR meaning LIKE ? ESCAPE '\\' ORDER BY word",
            arrayOf(pattern, pattern)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(NovelAiTagCandidate(
                        cursor.getString(0), cursor.getString(1), 0,
                        NovelAiTagCategory.GENERAL, fromDictionary = true
                    ))
                }
            }
        }
    }
