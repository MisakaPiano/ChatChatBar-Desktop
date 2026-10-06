package com.example.chatbar.domain.image

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Versioned, derived data. Posting order is final source order, not query-time sorting. */
internal class RankedTagIndexStore(private val context: Context, private val kind: String) {
    private var current: Handle? = null
    private val preparationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preparation = TagIndexPreparation<File>()

    internal class Handle(val file: File, val database: SQLiteDatabase) {
        var readers = 0
        var retired = false
    }

    internal class Lease internal constructor(
        private val store: RankedTagIndexStore,
        internal val handle: Handle,
        val version: String
    ) : AutoCloseable {
        val database: SQLiteDatabase get() = handle.database
        override fun close() = store.release(handle)
    }

    suspend fun prepare(source: String, database: SQLiteDatabase, table: String): File {
        val directory = File(context.filesDir, "tag_completion").apply { mkdirs() }
        require(source.matches(Regex("[a-fA-F0-9]+"))) { "词库版本无效" }
        val target = File(directory, "$kind-v$FORMAT-$source.sqlite")
        if (target.isFile) return target
        return preparation.await(source) {
            // Retain before dispatch: the caller may cancel its own wait or a catalog
            // update may retire the source while this independent build is still running.
            database.acquireReference()
            preparationScope.async(start = CoroutineStart.LAZY) {
                prepareFile(source, database, table, target)
            }.also { task -> task.invokeOnCompletion { database.releaseReference() } }
        }
    }

    private suspend fun prepareFile(source: String, database: SQLiteDatabase, table: String, target: File): File {
        if (target.isFile) return target
        val directory = target.parentFile!!
        val manifest = context.assets.open("tag_completion/$kind.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        if (manifest.getInt("format") == FORMAT && manifest.getString("source") == source) {
            val staged = File(directory, "${target.name}.part")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                FileOutputStream(staged).use { output ->
                    GZIPInputStream(context.assets.open("tag_completion/$kind.sqlite.binz")).use { input ->
                        val hashing = DigestOutputStream(output, digest)
                        input.copyTo(hashing)
                        hashing.flush()
                    }
                    output.fd.sync()
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                check(actual == manifest.getString("sha256")) { "补全索引完整性校验失败" }
                check(staged.length() == manifest.getLong("bytes")) { "补全索引大小不匹配" }
                check(staged.renameTo(target)) { "无法安装补全索引" }
            } finally { staged.delete() }
        } else {
            val staged = File(directory, "${target.name}.part")
            staged.delete()
            try {
                build(staged, database, table, source)
                check(staged.renameTo(target)) { "无法安装补全索引" }
            } finally {
                staged.delete()
            }
        }
        return target
    }

    @Synchronized
    fun acquire(file: File, version: String): Lease {
        var handle = current
        if (handle == null || handle.file != file) {
            val database = SQLiteDatabase.openDatabase(
                file.absolutePath, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            try {
                database.rawQuery("SELECT version,source FROM metadata", null).use {
                    check(it.moveToFirst() && it.getInt(0) == FORMAT && it.getString(1) == version) {
                        "补全索引版本不匹配"
                    }
                }
            } catch (error: Throwable) {
                database.close()
                file.delete()
                throw error
            }
            val next = Handle(file, database)
            current = next
            handle?.let { old ->
                old.retired = true
                if (old.readers == 0) retire(old)
            }
            handle = next
        }
        handle.readers++
        return Lease(this, handle, version)
    }

    @Synchronized
    private fun release(handle: Handle) {
        handle.readers--
        if (handle.readers == 0 && handle.retired) retire(handle)
    }

    private fun retire(handle: Handle) {
        handle.database.close()
        // Version files are immutable; a later rollback can rebuild this derived data.
        handle.file.delete()
    }

    internal suspend fun build(file: File, source: SQLiteDatabase, table: String, version: String) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val writer = object : NovelAiIndexWriter, NovelAiSqlDatabase by AndroidNovelAiSqlDatabase(db) {
                override fun execSQL(sql: String, args: Array<Any>) { if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args) }
                override fun beginTransaction() = db.beginTransaction()
                override fun setTransactionSuccessful() = db.setTransactionSuccessful()
                override fun endTransaction() = db.endTransaction()
                override fun compileStatement(sql: String): NovelAiIndexStatement {
                    val statement = db.compileStatement(sql)
                    return object : NovelAiIndexStatement {
                        override fun bindLong(index: Int, value: Long) = statement.bindLong(index, value)
                        override fun bindString(index: Int, value: String) = statement.bindString(index, value)
                        override fun bindBlob(index: Int, value: ByteArray) = statement.bindBlob(index, value)
                        override fun executeInsert() { statement.executeInsert() }
                        override fun close() = statement.close()
                    }
                }
            }
            buildNovelAiRankedIndex(writer, AndroidNovelAiSqlDatabase(source), table, version, kind)
        }
    }

    companion object { const val FORMAT = 2 }
}

internal suspend fun <T> withCompletionCancellation(block: suspend (CancellationSignal) -> T): T = coroutineScope {
    val signal = CancellationSignal()
    val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { signal.cancel() }
    }
    try { block(signal) } finally { watcher.cancel() }
}

internal suspend fun searchRankedIndex(database: SQLiteDatabase, query: String, dictionary: Boolean,
    onCandidate: suspend (NovelAiTagCandidate) -> Unit) = withCompletionCancellation { signal ->
    searchSharedRankedIndex(AndroidNovelAiSqlDatabase(database, signal), query, dictionary, onCandidate)
}
