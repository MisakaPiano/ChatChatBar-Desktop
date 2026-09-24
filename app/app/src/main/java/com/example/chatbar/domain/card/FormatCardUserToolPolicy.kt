package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.FormatCardUserToolConfig
import com.example.chatbar.data.local.entity.FormatCardUserToolType
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlin.random.Random

object FormatCardUserToolPolicy {
    fun validate(tool: FormatCardUserToolConfig): FormatCardUserToolValidation =
        FormatCardUserToolValidator.validate(tool)

    fun firstValidationError(tools: List<FormatCardUserToolConfig>): String? =
        FormatCardUserToolValidator.firstValidationError(tools)

    fun requireValid(tools: List<FormatCardUserToolConfig>) =
        FormatCardUserToolValidator.requireValid(tools)

    fun appendRequestSuffix(
        userContent: String,
        tools: List<FormatCardUserToolConfig>,
        nextIntInclusive: (minimum: Int, maximum: Int) -> Int = ::randomIntInclusive
    ): String {
        if (tools.isEmpty()) return userContent
        requireValid(tools)

        val fragments = buildList {
            var index = 0
            while (index < tools.size) {
                val tool = tools[index]
                when (tool.type) {
                    FormatCardUserToolType.STRONG_PROMPT_SUFFIX -> {
                        index += 1
                    }

                    FormatCardUserToolType.RANDOM_NUMBER -> {
                        val values = mutableListOf<Int>()
                        while (
                            index < tools.size &&
                            tools[index].type == FormatCardUserToolType.RANDOM_NUMBER
                        ) {
                            val randomTool = tools[index]
                            values += nextIntInclusive(
                                randomTool.minimum.toInt(),
                                randomTool.maximum.toInt()
                            )
                            index += 1
                        }
                        add(PromptTemplates.randomNumberUserToolSuffix(values))
                    }
                }
            }
        }
        return PromptTemplates.appendUserToolSuffixBlock(userContent, fragments)
    }

    fun strongPromptSystemSuffix(tools: List<FormatCardUserToolConfig>): String {
        if (tools.isEmpty()) return ""
        requireValid(tools)
        return tools
            .filter { it.type == FormatCardUserToolType.STRONG_PROMPT_SUFFIX }
            .joinToString("\n\n") { it.text }
    }

    private fun randomIntInclusive(minimum: Int, maximum: Int): Int =
        Random.nextLong(minimum.toLong(), maximum.toLong() + 1L).toInt()
}
