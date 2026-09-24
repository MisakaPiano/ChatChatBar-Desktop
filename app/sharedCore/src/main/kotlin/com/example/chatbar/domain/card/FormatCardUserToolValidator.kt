package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.FormatCardUserToolConfig
import com.example.chatbar.data.local.entity.FormatCardUserToolType

data class FormatCardUserToolValidation(
    val minimumError: String? = null,
    val maximumError: String? = null,
    val textError: String? = null
) {
    val firstError: String?
        get() = minimumError ?: maximumError ?: textError

    val isValid: Boolean
        get() = firstError == null
}

/** Package/entity contract validation only. Prompt/runtime behavior remains platform-owned. */
object FormatCardUserToolValidator {
    fun validate(tool: FormatCardUserToolConfig): FormatCardUserToolValidation = when (tool.type) {
        FormatCardUserToolType.RANDOM_NUMBER -> validateRandomNumber(tool)
        FormatCardUserToolType.STRONG_PROMPT_SUFFIX -> FormatCardUserToolValidation(
            textError = "请输入强提示词尾缀".takeIf { tool.text.isBlank() }
        )
    }

    fun firstValidationError(tools: List<FormatCardUserToolConfig>): String? =
        tools.mapIndexedNotNull { index, tool ->
            validate(tool).firstError?.let { "第 ${index + 1} 个用户工具：$it" }
        }.firstOrNull()

    fun requireValid(tools: List<FormatCardUserToolConfig>) {
        firstValidationError(tools)?.let { error -> throw IllegalArgumentException(error) }
    }

    private fun validateRandomNumber(tool: FormatCardUserToolConfig): FormatCardUserToolValidation {
        val minimum = tool.minimum.toIntOrNull()
        val maximum = tool.maximum.toIntOrNull()
        return FormatCardUserToolValidation(
            minimumError = "最小值必须是 32 位整数".takeIf { minimum == null },
            maximumError = when {
                maximum == null -> "最大值必须是 32 位整数"
                minimum != null && maximum < minimum -> "最大值不能小于最小值"
                else -> null
            }
        )
    }
}
