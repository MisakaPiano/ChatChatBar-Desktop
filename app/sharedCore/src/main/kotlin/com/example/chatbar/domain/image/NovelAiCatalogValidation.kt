package com.example.chatbar.domain.image
import java.io.IOException
import java.util.Locale

data class NovelAiCatalogStructure(val tableName: String, val rowCount: Long)
private val REQUIRED_COLUMNS = setOf("name", "category", "cn_name", "post_count")
private fun quotedIdentifier(value: String) = "\"${value.replace("\"", "\"\"")}\""
fun validateNovelAiCatalogStructure(database: NovelAiSqlDatabase, minimumRows: Long = 10000): NovelAiCatalogStructure {
    return run {
            database.rawQuery("PRAGMA quick_check", emptyArray()).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != "ok") {
                    throw IOException("词库 SQLite 完整性检查失败")
                }
            }
            val tableName = discoverTagTable(database)
            val rowCount = database.rawQuery(
                "SELECT COUNT(*) FROM ${quotedIdentifier(tableName)}",
                emptyArray()
            ).use { cursor ->
                if (!cursor.moveToFirst()) 0L else cursor.getLong(0)
            }
            if (rowCount < minimumRows) {
                throw IOException("词库数据量异常：$rowCount 条")
            }
            val supportedCount = database.rawQuery(
                "SELECT COUNT(*) FROM ${quotedIdentifier(tableName)} WHERE category IN (0, 3, 4)",
                emptyArray()
            ).use { cursor ->
                if (!cursor.moveToFirst()) 0L else cursor.getLong(0)
            }
            if (supportedCount <= 0L) throw IOException("词库缺少可用 Danbooru 分类")
            NovelAiCatalogStructure(tableName, rowCount)

    }
}
    private fun discoverTagTable(database: NovelAiSqlDatabase): String {
        val candidates = mutableListOf<String>()
        database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
            emptyArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val table = cursor.getString(0).orEmpty()
                if (table.isBlank()) continue
                val columns = mutableSetOf<String>()
                database.rawQuery("PRAGMA table_info(${quotedIdentifier(table)})", emptyArray()).use { info ->
                    while (info.moveToNext()) columns += info.getString(1).lowercase(Locale.ROOT)
                }
                if (columns.containsAll(REQUIRED_COLUMNS)) candidates += table
            }
        }
        return candidates.firstOrNull { it.equals("tags", ignoreCase = true) }
            ?: candidates.singleOrNull()
            ?: throw IOException("词库结构不兼容：未找到唯一标签表")
    }

