package com.example.chatbar.domain.image

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import org.json.JSONObject

/** Read-only disk-backed vocabulary; initialization and queries belong on a worker dispatcher. */
internal class NovelAiBundledDictionary(context: Context) {
    private val app = context.applicationContext
    private val database: SQLiteDatabase by lazy {
        val metadata = app.assets.open("prompt_dictionary/metadata.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        val sha = metadata.getString("databaseSha256")
        require(sha.matches(Regex("[a-f0-9]{64}"))) { "词典校验信息无效" }
        val directory = File(app.filesDir, "prompt_dictionary")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建词典目录" }
        val file = File(directory, "ecdict-$sha.sqlite")
        if (!file.isFile || file.length() != metadata.getLong("databaseBytes")) {
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                GZIPInputStream(app.assets.open("prompt_dictionary/ecdict.sqlite.binz")).use { input ->
                    val hashingOutput = DigestOutputStream(output, digest)
                    input.copyTo(hashingOutput)
                    hashingOutput.flush()
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                check(actual == sha) { "内置词典完整性校验失败" }
                atomic.finishWrite(output)
            } catch (error: Throwable) {
                atomic.failWrite(output)
                throw error
            }
        }
        SQLiteDatabase.openDatabase(
            file.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )
    }

    fun lookup(word: String): String? = database.rawQuery(
        "SELECT meaning FROM words WHERE word = ?", arrayOf(word)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun search(query: String): List<NovelAiTagCandidate> {
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
}
