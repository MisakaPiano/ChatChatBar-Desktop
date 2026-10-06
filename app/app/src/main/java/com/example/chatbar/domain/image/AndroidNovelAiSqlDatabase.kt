package com.example.chatbar.domain.image

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal

internal class AndroidNovelAiSqlDatabase(
    private val database: SQLiteDatabase,
    private val signal: CancellationSignal? = null,
) : NovelAiSqlDatabase {
    override fun rawQuery(sql: String, args: Array<String>) =
        AndroidNovelAiSqlCursor(database.rawQuery(sql, args, signal))
}

internal class AndroidNovelAiSqlCursor(private val cursor: Cursor) : NovelAiSqlCursor {
    override fun moveToFirst() = cursor.moveToFirst()
    override fun moveToNext() = cursor.moveToNext()
    override fun getString(index: Int) = cursor.getString(index).orEmpty()
    override fun getLong(index: Int) = cursor.getLong(index)
    override fun getInt(index: Int) = cursor.getInt(index)
    override fun getBlob(index: Int): ByteArray = cursor.getBlob(index)
    override fun close() = cursor.close()
}
