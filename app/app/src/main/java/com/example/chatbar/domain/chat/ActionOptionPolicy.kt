package com.example.chatbar.domain.chat

/** Explicitly labelled, numbered recommendations are UI affordances, not completed story events. */
object ActionOptionPolicy {
    data class Block(val start: Int, val endExclusive: Int, val choices: List<String>)
    data class Insertion(val text: String, val cursor: Int)
    private val number = Regex("^\\s*(?:\\*\\*)?([1-9][0-9]*)[.、)）]\\s*(?:\\*\\*)?(.+?)\\s*$")
    private val headingPattern = Regex(
        "^[\p{P}\p{S}\s]*(行动选项|可选行动|行动选择|选项|action choices|action options)[\p{P}\p{S}\s]*$",
        RegexOption.IGNORE_CASE
    )
    private fun heading(text: String): Boolean = headingPattern.matches(text.trim())

    private fun parse(text: String): Block? {
        val lines = Regex("[^\\n]*(?:\\n|$)").findAll(text).filter { it.value.isNotEmpty() }.toList()
        val header = lines.indexOfFirst { heading(it.value) }
        if (header < 0) return null
        val choices = mutableListOf<String>()
        var last = header
        for (index in header + 1 until lines.size) {
            val line = lines[index].value.trim()
            if (line.isEmpty()) continue
            val match = number.matchEntire(line) ?: break
            if (match.groupValues[1].toIntOrNull() != choices.size + 1) return null
            val choice = match.groupValues[2].trim().removePrefix("**").removeSuffix("**").trim()
            if (choice.isBlank()) return null
            choices += choice
            last = index
        }
        if (choices.isEmpty()) return null
        return Block(lines[header].range.first, lines[last].range.last + 1, choices)
    }

    /** Used only on an already bounded bubble segment; ordinary lists without a heading stay text. */
    fun choices(text: String): List<String> = parse(text)?.choices.orEmpty()

    fun surroundingText(text: String): String = parse(text)?.let {
        (text.substring(0, it.start) + text.substring(it.endExclusive)).trim()
    }.orEmpty()

    fun blocks(content: String): List<Block> = parseRoleplayTextSegments(content).mapNotNull { segment ->
        val parsed = parse(segment.rawText) ?: return@mapNotNull null
        // Strip only the labelled choices, preserving narrative before/after and unrelated state blocks.
        Block(segment.start + parsed.start, segment.start + parsed.endExclusive, parsed.choices)
    }

    fun strip(content: String): String {
        val blocks = blocks(content)
        if (blocks.isEmpty()) return content
        val stripped = blocks.asReversed().fold(content) { text, block ->
            text.removeRange(block.start, block.endExclusive)
        }.trim()
        val onlyFences = stripped.lines().all { line ->
            val value = line.trim()
            value.isEmpty() || value.startsWith("```") ||
                (value.length >= 3 && value.all { it in "-—―─－" })
        }
        return if (onlyFences) "" else stripped
    }

    /** Insertion never replaces a selection. Subsequent clicks append, including duplicate choices. */
    fun insert(input: String, cursor: Int?, option: String, append: Boolean): Insertion {
        val position = if (append) input.length else cursor?.coerceIn(0, input.length) ?: input.length
        val prefix = input.substring(0, position)
        val suffix = input.substring(position)
        val before = if (prefix.isNotEmpty() && !prefix.last().isWhitespace()) "\n" else ""
        val after = if (suffix.isNotEmpty() && !suffix.first().isWhitespace()) "\n" else ""
        val inserted = before + option + after
        return Insertion(prefix + inserted + suffix, position + inserted.length)
    }
}
