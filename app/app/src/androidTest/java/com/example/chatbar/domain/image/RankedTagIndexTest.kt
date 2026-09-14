package com.example.chatbar.domain.image

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RankedTagIndexTest {
    @Test fun syntheticCatalogStreamsInRankOrderAcrossPhysicalPages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "ranked-index-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val sourceFile = File(directory, "source.sqlite")
            val indexFile = File(directory, "index.sqlite")
            SQLiteDatabase.openOrCreateDatabase(sourceFile, null).use { source ->
                source.execSQL("CREATE TABLE tags(name TEXT, cn_name TEXT, post_count INTEGER, category INTEGER)")
                repeat(1500) { index ->
                    source.execSQL("INSERT INTO tags VALUES (?, ?, ?, ?)",
                        arrayOf<Any>("blue_$index", "蓝 色$index", index, if (index % 2 == 0) 1 else 0))
                }
                source.execSQL("INSERT INTO tags VALUES ('50%_off', '五折', 999, 5)")
                RankedTagIndexStore(context, "danbooru").build(indexFile, source, "tags", "fixture")
            }
            SQLiteDatabase.openDatabase(indexFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { index ->
                suspend fun names(query: String): List<String> = buildList {
                    searchRankedIndex(index, query, dictionary = false) { add(it.name) }
                }
                assertEquals((1499 downTo 0).map { "blue_$it" }, names("blue"))
                assertEquals(names("blue"), names("蓝色"))
                assertEquals(listOf("50%_off"), names("50%"))
                assertTrue(names("不存在").isEmpty())
                var delivered = 0
                try {
                    searchRankedIndex(index, "blue", dictionary = false) {
                        delivered++
                        throw CancellationException("stop after first match")
                    }
                    fail("Cancellation must propagate")
                } catch (_: CancellationException) {
                    assertEquals(1, delivered)
                }
            }
        } finally { directory.deleteRecursively() }
    }
}
