package com.example.chatbar.domain.image

/** Request-only port of NovelAI's V5 quote expansion (web build 3102745, module 46278). */
object NovelAiV5TextPromptPolicy {
    // Match ECMAScript whitespace without Android's unsupported UNICODE_CHARACTER_CLASS flag.
    private const val WHITESPACE = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private val whitespace = Regex("[$WHITESPACE]")
    private val explicitTextBlock = Regex("(?:^|[$WHITESPACE,.:\\[\\]{}、。])text:(?!:)", RegexOption.IGNORE_CASE)
    private val cjk = Regex("[\\u3000-\\u303F\\u3040-\\u309F\\u30A0-\\u30FF\\uFF00-\\uFF9F\\u4E00-\\u9FAF\\u3400-\\u4DBF]")
    private val letterOrNumber = Regex("[\\p{L}\\p{N}]")
    private val closingQuotes = mapOf(
        '"' to '"',
        '“' to '”',
        '「' to '」',
        '\'' to '\'',
        '‘' to '’'
    )

    internal fun explicitTextStart(source: String): Int? = explicitTextBlock.find(source)?.range?.last?.minus(4)

    internal fun isRequestWhitespace(character: Char): Boolean = whitespace.matches(character.toString())

    /** Shared input for Studio token counting and ordinary final serialization. */
    fun prepareForRequest(prompt: NovelAiPromptPlan, settings: NovelAiGenerationSettings): NovelAiPromptPlan {
        val prepared = apply(NovelAiV5GenerationPolicy.transparentPrompt(
            NovelAiPromptDelimiterPolicy.normalizeForRequest(prompt), settings.effectiveTransparentBackground), settings.model)
        return if (!settings.isMedium) prepared else prepared.copy(
            negativePrompt = com.example.chatbar.domain.prompt.NovelAiPromptAuthority.novelAiV5MediumNegativePrompt(prepared.baseCaption),
            characterCaptions = prepared.characterCaptions.map { it.copy(negativePrompt = "") })
    }

    /** Exact option-shaped suffix only. Matching it does not prove who authored it;
     * studio PNG import must obtain an explicit choice before using this result.
     */
    fun withoutTransparentBackgroundSuffix(source: String): String? {
        val chunk = source.substring(0, firstChunkEnd(source))
        val textStart = explicitTextBlock.find(chunk)?.range?.last?.minus(4) ?: chunk.length
        val end = chunk.substring(0, textStart).trimEnd { it == ',' || it.isJsWhitespace() }.length
        val tag = com.example.chatbar.domain.prompt.NovelAiPromptAuthority.NOVELAI_TRANSPARENT_BACKGROUND_TAG
        val start = end - tag.length
        if (start < 0 || chunk.substring(start, end) != tag) return null
        if (start == 0) return source.substring(textStart)
        var separator = start
        while (separator > 0 && chunk[separator - 1].isJsWhitespace()) separator--
        if (separator == 0 || chunk[separator - 1] != ',') return null
        return source.removeRange(separator - 1, end)
    }

    fun apply(prompt: NovelAiPromptPlan, model: NovelAiImageModel): NovelAiPromptPlan {
        if (model != NovelAiImageModel.V5_FULL) return prompt
        val characterPrompts = prompt.characterCaptions.map { it.prompt }.filter(String::isNotEmpty)
        if (explicitTextBlock.containsMatchIn(prompt.baseCaption) ||
            characterPrompts.any(explicitTextBlock::containsMatchIn)
        ) return prompt

        val chunkEnd = firstChunkEnd(prompt.baseCaption)
        val base = prompt.baseCaption.substring(0, chunkEnd)
        // Requests use use_coords=false: preserve character order, not stored legacy centers.
        val groups = listOf(quotedTexts(base)) + characterPrompts.map(::quotedTexts)
        val combined = groups.flatten().joinToString("")
        if (combined.isEmpty()) return prompt
        val reverse = cjk.findAll(combined).count().toDouble() / combined.length > 0.3
        val renderedTexts = groups.flatMap { if (reverse) it.asReversed() else it }
        val textBlock = "teXt: " + renderedTexts.joinToString("\n\n")
        val trimmedBase = base.trimEnd { it == ',' || it.isJsWhitespace() }
        val expanded = if (trimmedBase.isEmpty()) textBlock else "$trimmedBase, $textBlock"
        return prompt.copy(baseCaption = expanded + prompt.baseCaption.substring(chunkEnd))
    }

    // A single | separates prompt chunks; || encloses randomizer alternatives.
    private fun firstChunkEnd(source: String): Int {
        var randomizer = false
        var index = 0
        while (index < source.length) {
            if (source.startsWith("||", index)) {
                randomizer = !randomizer
                index += 2
            } else {
                if (source[index] == '|' && !randomizer) return index
                index += 1
            }
        }
        return source.length
    }

    private fun quotedTexts(source: String): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < source.length) {
            val opener = source[index]
            val closer = closingQuotes[opener]
            val previous = source.getOrNull(index - 1)
            if (closer == null || opener == '\'' && previous != null &&
                !previous.isJsWhitespace() && previous != ',' && previous != '.'
            ) {
                index += 1
                continue
            }
            val singleQuote = closer == '\'' || closer == '’'
            var closingIndex = index + 1
            while (closingIndex < source.length) {
                val followedByLetterOrNumber = source.getOrNull(closingIndex + 1)
                    ?.let { letterOrNumber.matches(it.toString()) } == true
                if (source[closingIndex] == closer && !(singleQuote && followedByLetterOrNumber)) break
                closingIndex += 1
            }
            if (closingIndex >= source.length) {
                index += 1
                continue
            }
            source.substring(index + 1, closingIndex).trim { it.isJsWhitespace() }
                .takeIf(String::isNotEmpty)?.let(result::add)
            index = closingIndex + 1
        }
        return result
    }

    private fun Char.isJsWhitespace(): Boolean = isRequestWhitespace(this)
}
