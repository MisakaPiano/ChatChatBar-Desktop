package com.example.chatbar.domain.image

import com.example.chatbar.domain.prompt.NovelAiPromptAuthority

/** Request-only V5 options. Original Studio/card/history Prompt text remains untouched. */
object NovelAiV5GenerationPolicy {
    private val textMarker = Regex("(?:^|[\\s,.:\\[\\]{}、。])text:(?!:)", RegexOption.IGNORE_CASE)

    fun transparentPrompt(prompt: NovelAiPromptPlan, enabled: Boolean): NovelAiPromptPlan {
        if (!enabled) return prompt
        val source = prompt.baseCaption
        var randomizer = false
        var boundary = source.length
        var index = 0
        while (index < source.length) {
            if (source.startsWith("||", index)) { randomizer = !randomizer; index += 2 }
            else if (source[index] == '|' && !randomizer) { boundary = index; break }
            else index++
        }
        val first = source.substring(0, boundary)
        val text = textMarker.find(first)?.range?.first ?: first.length
        val beforeText = first.substring(0, text)
        val tag = NovelAiPromptAuthority.NOVELAI_TRANSPARENT_BACKGROUND_TAG
        val prefix = if (beforeText.isEmpty()) tag else "$beforeText, $tag"
        val textSeparator = if (text == 0 && first.isNotEmpty()) ", " else ""
        return prompt.copy(baseCaption = prefix + textSeparator + first.substring(text) + source.substring(boundary))
    }
}
