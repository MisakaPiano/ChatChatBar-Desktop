package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.domain.budget.PromptBudgetEstimate
import com.example.chatbar.domain.budget.PromptBudgetSection

/** A partition of the existing estimate, not a second tokenizer or an injection prediction. */
internal data class DesktopEditorBudgetGroup(
    val id: String,
    val label: String,
    val sections: List<PromptBudgetSection> = emptyList(),
    val children: List<DesktopEditorBudgetGroup> = emptyList(),
) {
    val tokens: Long get() = sections.sumOf { it.estimate.tokens } + children.sumOf { it.tokens }
    val fields: List<PromptBudgetSection> get() = sections + children.flatMap { it.fields }
}

internal fun desktopEditorBudgetGroups(entity: Any, report: PromptBudgetEstimate): List<DesktopEditorBudgetGroup> {
    val remaining = report.sections.toMutableList()
    fun take(id: String, label: String, predicate: (PromptBudgetSection) -> Boolean): DesktopEditorBudgetGroup {
        val fields = remaining.filter(predicate)
        remaining.removeAll(fields.toSet())
        return DesktopEditorBudgetGroup(id, label, fields)
    }
    val groups = buildList {
        when (entity) {
            is CharacterCard -> {
                add(take("common", "卡片与共通设定") { it.id in setOf("name", "botName", "system", "basic", "post-history", "freeform") })
                add(take("greetings", "开场与对话示例") { it.id == "greeting" || it.id.startsWith("greeting-") || it.id == "examples" })
                val people = entity.characters.mapIndexed { index, person ->
                    val prefix = "person:${person.id}:$index:"
                    take(prefix, person.name.ifBlank { "未命名人物 ${index + 1}" }) { it.id.substringBeforeLast(':') == prefix.dropLast(1) }
                }
                if (people.isNotEmpty()) add(DesktopEditorBudgetGroup("people", "人物条目", children = people))
                add(take("image", "默认生图字段") { it.id in setOf("image", "negative") })
                add(take("notes", "作者备注") { it.id == "notes" })
            }
            is WorldBook -> {
                add(take("book", "世界书资料") { it.id in setOf("name", "description") })
                val entries = entity.entries.mapIndexed { index, entry ->
                    val prefix = "entry:${entry.id}:$index:"
                    take(prefix, entry.name.ifBlank { "未命名条目 ${index + 1}" }) { it.id.substringBeforeLast(':') == prefix.dropLast(1) }
                }
                if (entries.isNotEmpty()) add(DesktopEditorBudgetGroup("entries", "世界书条目", children = entries))
            }
            is FormatCard -> {
                add(take("format", "格式正文") { it.id == "content" })
                add(take("tools", "用户工具") { it.id.startsWith("tool-") })
            }
        }
        if (remaining.isNotEmpty()) add(DesktopEditorBudgetGroup("other", "其他已列字段", remaining.toList()))
    }.filter { it.fields.isNotEmpty() }
    check(groups.flatMap { it.fields }.map { it.id }.sorted() == report.sections.map { it.id }.sorted())
    check(groups.sumOf { it.tokens } == report.estimatedTextTokens)
    return groups
}
