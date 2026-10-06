package com.example.chatbar.domain.image

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Platform SQLite writes only; ranking, filtering, grams and packing remain one authority. */
interface NovelAiIndexStatement : AutoCloseable {
    fun bindLong(index: Int, value: Long)
    fun bindString(index: Int, value: String)
    fun bindBlob(index: Int, value: ByteArray)
    fun executeInsert()
}
interface NovelAiIndexWriter : NovelAiSqlDatabase {
    fun execSQL(sql: String, args: Array<Any> = emptyArray())
    fun compileStatement(sql: String): NovelAiIndexStatement
    fun beginTransaction()
    fun setTransactionSuccessful()
    fun endTransaction()
}
suspend fun buildNovelAiRankedIndex(destination: NovelAiIndexWriter, source: NovelAiSqlDatabase,
    table: String, version: String, kind: String) {
    val FORMAT = 2
    // journal_mode assignments return a row; Android execSQL rejects row-producing SQL.
    destination.rawQuery("PRAGMA journal_mode=OFF", emptyArray()).use { cursor ->
        check(cursor.moveToFirst() && cursor.getString(0).equals("off", ignoreCase = true)) {
            "无法设置补全索引构建日志模式"
        }
    }
    destination.execSQL("PRAGMA synchronous=OFF")
    destination.execSQL("PRAGMA temp_store=FILE")
    destination.execSQL("CREATE TABLE entries(rank INTEGER PRIMARY KEY, name TEXT NOT NULL, " +
        "cn_name TEXT NOT NULL, post_count INTEGER NOT NULL, category INTEGER NOT NULL, a TEXT NOT NULL, b TEXT NOT NULL)")
    destination.execSQL("CREATE TABLE postings(gram INTEGER NOT NULL, rank INTEGER NOT NULL, PRIMARY KEY(gram, rank)) WITHOUT ROWID")
    destination.execSQL("CREATE TABLE grams(gram INTEGER PRIMARY KEY, n INTEGER NOT NULL, ranks BLOB NOT NULL)")
    destination.execSQL("CREATE TABLE metadata(version INTEGER NOT NULL, source TEXT NOT NULL)")
    destination.execSQL("INSERT INTO metadata VALUES (?, ?)", arrayOf<Any>(FORMAT, version))
    val quoted = "\"${table.replace("\"", "\"\"")}\""
    val sql = if (kind == "dictionary") {
        "SELECT word, meaning, 0, 0, lower(word), lower(meaning) FROM $quoted ORDER BY word"
    } else "SELECT name, coalesce(cn_name,''), coalesce(post_count,0), category, " +
        "lower(name), replace(lower(coalesce(cn_name,'')), ' ', '') FROM $quoted " +
        "ORDER BY post_count DESC, lower(name) ASC"
    destination.beginTransaction()
    try {
        destination.compileStatement("INSERT INTO entries VALUES (?, ?, ?, ?, ?, ?, ?)").use { entry ->
            destination.compileStatement("INSERT INTO postings VALUES (?, ?)").use { posting ->
                source.rawQuery(sql, emptyArray()).use { cursor ->
                    var rank = 0L
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val name = cursor.getString(0).orEmpty().trim()
                        if (kind != "dictionary" && (!validCompletionTag(name) || NovelAiTagCategory.fromCode(cursor.getInt(3)) == null)) continue
                        val a = cursor.getString(4).orEmpty()
                        val b = cursor.getString(5).orEmpty()
                        entry.bindLong(1, rank)
                        entry.bindString(2, name)
                        entry.bindString(3, cursor.getString(1).orEmpty())
                        entry.bindLong(4, cursor.getLong(2).coerceAtLeast(0))
                        entry.bindLong(5, cursor.getInt(3).toLong())
                        entry.bindString(6, a)
                        entry.bindString(7, b)
                        entry.executeInsert()
                        (completionGrams(a) + completionGrams(b)).forEach { gram ->
                            posting.bindLong(1, gram)
                            posting.bindLong(2, rank)
                            posting.executeInsert()
                        }
                        rank++
                    }
                }
            }
        }
        destination.compileStatement("INSERT INTO grams VALUES (?, ?, ?)").use { insert ->
            run {
                var gram = -1L
                var previous = 0L
                var count = 0L
                val packed = ByteArrayOutputStream()
                fun flush() {
                    if (count == 0L) return
                    insert.bindLong(1, gram)
                    insert.bindLong(2, count)
                    insert.bindBlob(3, packed.toByteArray())
                    insert.executeInsert()
                    packed.reset()
                    previous = 0
                    count = 0
                }
                var afterGram = -1L
                var afterRank = -1L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    var rows = 0
                    // SQLiteCursor refills otherwise rescan/count a multi-million-row
                    // result. Resume from its primary key instead of from row zero.
                    destination.rawQuery(
                        "SELECT gram,rank FROM postings WHERE (gram,rank)>(?,?) " +
                            "ORDER BY gram,rank LIMIT 4096",
                        arrayOf(afterGram.toString(), afterRank.toString())
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val nextGram = cursor.getLong(0)
                            if (nextGram != gram) { flush(); gram = nextGram }
                            val rank = cursor.getLong(1)
                            var delta = rank - previous
                            previous = rank
                            while (delta >= 128) {
                                packed.write((delta.toInt() and 127) or 128)
                                delta = delta ushr 7
                            }
                            packed.write(delta.toInt())
                            count++
                            rows++
                            afterGram = nextGram
                            afterRank = rank
                        }
                    }
                    if (rows < 4096) break
                }
                flush()
            }
        }
        destination.execSQL("DROP TABLE postings")
        // Completion reads entries by rank; no secondary name index is needed.
        destination.setTransactionSuccessful()
    } finally {
        destination.endTransaction()
    }
    destination.execSQL("VACUUM")
}
