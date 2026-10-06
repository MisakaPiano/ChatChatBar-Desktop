package com.example.chatbar.domain.image

/** Minimal read-only cursor seam for shared catalog SQL and ranked posting traversal. */
fun interface NovelAiSqlDatabase {
    fun rawQuery(sql: String, args: Array<String>): NovelAiSqlCursor
}

interface NovelAiSqlCursor : AutoCloseable {
    fun moveToFirst(): Boolean
    fun moveToNext(): Boolean
    fun getString(index: Int): String
    fun getLong(index: Int): Long
    fun getInt(index: Int): Int
    fun getBlob(index: Int): ByteArray
}
