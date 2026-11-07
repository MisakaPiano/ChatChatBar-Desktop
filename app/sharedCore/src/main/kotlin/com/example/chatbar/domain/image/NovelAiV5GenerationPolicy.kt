package com.example.chatbar.domain.image

import com.example.chatbar.domain.prompt.NovelAiPromptAuthority

/** Request-only V5 options. Original Studio/card/history Prompt text remains untouched. */
object NovelAiV5GenerationPolicy {
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
        val textStart = NovelAiV5TextPromptPolicy.explicitTextStart(first) ?: first.length
        val beforeText = first.substring(0, textStart).trimEnd { NovelAiV5TextPromptPolicy.isRequestWhitespace(it) || it == ',' }
        val tag = NovelAiPromptAuthority.NOVELAI_TRANSPARENT_BACKGROUND_TAG
        // Imported PNG captions can already include the derived hint. Retain their original text.
        if (beforeText.split(',').any { it.trim(NovelAiV5TextPromptPolicy::isRequestWhitespace).equals(tag, ignoreCase = true) }) return prompt
        val prefix = if (beforeText.isEmpty()) tag else "$beforeText, $tag"
        val text = if (textStart < first.length) ", " + first.substring(textStart) else ""
        return prompt.copy(baseCaption = prefix + text + source.substring(boundary))
    }
}
