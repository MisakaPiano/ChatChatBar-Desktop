package com.example.chatbar.domain.image

import java.util.Calendar
import java.util.TimeZone
import kotlinx.serialization.Serializable

@Serializable
data class NovelAiHistoryFoldPreference(
    val depth: Int = 0,
    val enabled: Boolean = false,
    val type: NovelAiHistoryFoldType = NovelAiHistoryFoldType.FULL
)

@Serializable
enum class NovelAiHistoryFoldType(val label: String, val description: String) {
    FULL("完整", "画风、基础、额外及有序角色的正负面提示词完全相同"),
    BASE("基础", "基础 Prompt 相同，不比较画风、额外或角色"),
    CONTENT("内容", "除画风外，基础、额外及有序角色的正负面提示词相同"),
    STYLE("画风", "画风 Prompt 相同"),
    DAY("日", "同一天生成的图片"),
    MONTH("月", "同一个月生成的图片"),
    YEAR("年", "同一年生成的图片")
}

/** Structured keys preserve exact text and role order without delimiter collisions or role IDs. */
object NovelAiHistoryFolding {
    fun key(
        entry: NovelAiGenerationHistoryEntry,
        type: NovelAiHistoryFoldType,
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<String> = with(entry.recipe) {
        when (type) {
            NovelAiHistoryFoldType.FULL -> listOf(stylePrompt) + contentKey(this)
            NovelAiHistoryFoldType.BASE -> listOf(basePrompt)
            NovelAiHistoryFoldType.CONTENT -> contentKey(this)
            NovelAiHistoryFoldType.STYLE -> listOf(stylePrompt)
            else -> Calendar.getInstance(timeZone).run {
                timeInMillis = entry.createdAt
                val year = get(Calendar.YEAR).toString()
                val month = (get(Calendar.MONTH) + 1).toString()
                val day = get(Calendar.DAY_OF_MONTH).toString()
                buildList {
                    add(year)
                    if (type != NovelAiHistoryFoldType.YEAR) add(month)
                    if (type == NovelAiHistoryFoldType.DAY) add(day)
                }
            }
        }
    }

    private fun contentKey(recipe: NovelAiGenerationRecipe): List<String> =
        listOf(recipe.basePrompt, recipe.extraPrompt, recipe.negativePrompt) +
            recipe.characters.flatMap { listOf(it.prompt, it.negativePrompt) }
}
