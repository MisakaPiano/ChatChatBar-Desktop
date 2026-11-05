package com.example.chatbar.domain.chat

/** Explicit labels in closed status blocks only; prose mentions never establish ownership. */
object RoleplayStatusOwnership {
    private val leadingDecoration = Regex("^[\\p{So}\\p{Sk}\\p{M}\\s\\u200D•●▪-]+")
    private val nameField = Regex(
        "(?:姓名|人物|角色|Name|Character)\\s*[:：]\\s*(.+)", RegexOption.IGNORE_CASE)
    private val heading = Regex("(?:【|\\[)([^]】]+?)(?:状态栏|状态)(?:】|])")

    fun ownerNames(segment: RoleplayTextSegment): List<String> {
        if (segment.kind != RoleplaySegmentKind.STATUS) return emptyList()
        val raw = segment.rawText.trim().replace("\r\n", "\n").lines()
        val closed = raw.size >= 3 && ((raw.first() == "---" && raw.last() == "---") ||
            (raw.first().trim().lowercase() in setOf("```", "```状态", "```状态栏", "```status",
                "```text", "```plaintext", "```txt") && raw.last() == "```"))
        if (!closed) return emptyList()
        val lines = segment.displayText.lines().map(String::trim).filter(String::isNotEmpty)
        if (lines.size < 2 || lines.any { it.contains("行动选项") || it.contains("可选行动") ||
                it.equals("action choices", true) || it.equals("action options", true) }) return emptyList()
        return lines.mapNotNull { line ->
            // Emoji/bullet prefixes and Markdown emphasis decorate a label, not its identity.
            val label = line.replaceFirst(leadingDecoration, "").replace("**", "").replace("__", "").trim()
            (nameField.matchEntire(label) ?: heading.matchEntire(label))?.groupValues?.get(1)?.trim()
        }.distinctBy { it.lowercase() }
    }
}
