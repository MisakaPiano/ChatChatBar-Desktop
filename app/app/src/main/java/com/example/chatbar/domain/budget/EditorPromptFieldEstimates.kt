package com.example.chatbar.domain.budget

import com.example.chatbar.data.local.entity.*

/** Same descriptive character heuristic as Desktop; never changes request parameters. */
data class EditorPromptFieldEstimate(val id: String, val label: String, val tokens: Long, val note: String = "")

object EditorPromptFieldEstimates {
    fun tokens(text: String): Long {
        if (text.isEmpty()) return 0
        val weight = text.sumOf { if (it.code in 0x4E00..0x9FA5) 2L else 1L }
        return (weight * 2 / 5).coerceAtLeast(1)
    }
    private fun field(id: String, label: String, text: String, note: String = "") =
        EditorPromptFieldEstimate(id, label, tokens(text), note)

    fun character(card: CharacterCard): List<EditorPromptFieldEstimate> = buildList {
        add(field("system", "系统提示词", card.systemPrompt))
        add(field("basic", "基本设定", card.basicSetting))
        add(field("post-history", "历史后指令", card.postHistoryInstructions))
        add(field("examples", "对话示例", card.mesExample))
        if (card.editMode == CharacterEditMode.FREEFORM) add(field("freeform", "自由人物正文", card.freeformCharacterText))
        else card.characters.filter { it.enabled }.forEachIndexed { index, person ->
            val prefix = "person:${person.id}:$index"
            val note = "会话人物开关仍会影响实际注入"
            listOf("name" to ("姓名" to person.name), "profile" to ("简介" to person.profile),
                "appearance" to ("外貌" to person.appearance), "clothing" to ("服装" to person.clothing),
                "abilities" to ("能力" to person.abilities), "habits" to ("习惯" to person.habits),
                "background" to ("背景" to person.background), "relationships" to ("关系" to person.relationships),
                "speaking" to ("说话风格" to person.speakingStyle)).forEach { (id, value) ->
                add(field("$prefix:$id", "${person.name} · ${value.first}", value.second, note))
            }
        }
    }.filter { it.tokens > 0 }

    fun worldBook(book: WorldBook): List<EditorPromptFieldEstimate> = book.entries.mapIndexedNotNull { index, entry ->
        if (!entry.enabled || entry.content.isEmpty()) null
        else field("entry:${entry.id}:$index", "${entry.name} · 正文", entry.content,
            "仅启用候选；缺少本轮触发、概率和预算结果，不代表已激活")
    }
}
