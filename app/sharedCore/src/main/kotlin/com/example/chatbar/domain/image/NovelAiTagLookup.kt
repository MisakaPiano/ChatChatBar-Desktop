package com.example.chatbar.domain.image

import kotlinx.serialization.Serializable

@Serializable
data class DanbooruCatalogMetadata(
    val sourceSha: String,
    val sourceCommitTime: String,
    val sourceSizeBytes: Long,
    val rowCount: Long = 0L,
    val tableName: String = "tags"
)

data class DanbooruCatalogValidation(
    val tableName: String,
    val rowCount: Long,
    val sourceSizeBytes: Long,
    val sourceSha: String
)

interface NovelAiTagLookup : NovelAiTagSearchClient {
    suspend fun exactChineseTranslations(names: Collection<String>): Map<String, String>
    suspend fun catalogMetadata(): DanbooruCatalogMetadata
}
