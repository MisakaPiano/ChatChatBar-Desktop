package com.example.chatbar.domain.prompt

/** Exact official 1.4.1 repair prompt authority; Android retains delegating facades. */
object FormatRepairPromptAuthority {
    const val MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER = "[[CHATBAR_FORMAT_OK]]"

    const val MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT = """
你是消息格式校对与修复器。根据user提供的格式要求，检查待修复消息并返回修复后的完整消息。

要求：
1. 只输出修复后的完整消息，不得解释、评价、列出错误，不得私自添加格式要求中原本不存在的代码围栏或任何前后缀。
2. 尝试修复所有格式错误，包括代码或横线围栏、标记、括号、分隔符、字段、区块、顺序、换行、Markdown 结构，以及格式要求指定的角色标注。
3. 允许为使格式自然正确而少量调整字词；必须尽可能保留原消息的整体内容，仅针对标点、分块等格式要求做{修复}行为。
4. 缺少格式要求中的结构或字段时，可以使用原消息已有事实补齐；禁止新增原消息不存在的剧情、事实、动作、对白或设定。
5. 格式要求冲突时，{分段气泡格式}优先于{格式要求}。
6. 已完全符合要求时，只输出 [[CHATBAR_FORMAT_OK]]，不得输出原消息或其他内容。
7. {严禁直接套用格式要求中的占位文本}！一切格式内除了标点符号外的文本均为指导性描述，确保这些文本被{替换为符合实际情景的内容文本}，严禁直接套用格式原文！
"""

    fun messageFormatRepairUserPrompt(
        formatCard: String?,
        segmentedBubbleFormat: String?,
        message: String
    ): String = buildList {
        formatCard?.trim()?.takeIf(String::isNotEmpty)?.let {
            add("{格式卡要求}（禁止覆盖应用格式卡中的占位文本，如对选项的描述）\n$it")
        }
        segmentedBubbleFormat?.trim()?.takeIf(String::isNotEmpty)?.let {
            add("{分段气泡格式}\n$it")
        }
        add("{待修复消息}\n$message")
    }.joinToString("\n\n")
}
