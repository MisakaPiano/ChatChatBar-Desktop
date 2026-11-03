package com.example.chatbar.domain.image

import android.content.Context
import kotlinx.serialization.json.Json

class NovelAiStyleCatalogService(
    private val context: Context,
    json: Json
) {
    private val parser = NovelAiStyleCatalogParser(json)

    fun load(): NovelAiStyleCatalogLoadResult {
        val rawJson = try {
            context.assets.open(NOVEL_AI_STYLE_CATALOG_ASSET_PATH)
                .bufferedReader()
                .use { it.readText() }
        } catch (error: Exception) {
            return NovelAiStyleCatalogLoadResult(
                fatalError = "画风配置加载失败：${error.message ?: error::class.simpleName.orEmpty()}"
            )
        }

        return parser.parse(rawJson) { assetPath ->
            runCatching {
                context.assets.open(assetPath).use { }
            }.isSuccess
        }
    }
}
