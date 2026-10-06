package com.example.chatbar.domain.image

import android.content.Context
import kotlinx.serialization.json.Json

class NovelAiCodexCatalogService(
    private val context: Context,
    json: Json
) {
    private val parser = NovelAiCodexCatalogParser(json)

    fun load(): NovelAiCodexCatalogLoadResult {
        val raw = try {
            context.assets.open(NOVEL_AI_CODEX_ASSET_PATH).bufferedReader().use { it.readText() }
        } catch (error: Exception) {
            return NovelAiCodexCatalogLoadResult(
                fatalError = "NovelAI 法典加载失败：${error.message ?: error::class.simpleName.orEmpty()}"
            )
        }
        return parser.parse(raw)
    }
}
