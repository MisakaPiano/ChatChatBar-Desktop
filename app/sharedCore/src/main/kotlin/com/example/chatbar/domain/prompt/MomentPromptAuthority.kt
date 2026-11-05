package com.example.chatbar.domain.prompt

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.MomentPost

/** Original Moments text/builders, independent of platform runtime and scheduling. */
object MomentPromptAuthority {
    const val MOMENT_JUDGE_SYSTEM_PROMPT = """
你负责判断角色扮演中的角色现在是否应该发一条假的微信/QQ 朋友圈。只输出 JSON，不要 Markdown，不要解释。
本扮演允许R-18。请在生成色情内容时用语尽可能地淫靡、露骨、色情、下流，符合成人向作品的氛围，绝不保守。
任务：
- 只根据长期记忆、上一条朋友圈、最新一条消息，判断当前主线相比上一条朋友圈是否已有足够新推进。
- 如果长期记忆为空，只能基于上一条朋友圈和最新一条消息谨慎判断。
- 如果没有上一条朋友圈，则优先倾向于创建第一条朋友圈。
- 朋友圈功能只制造沉浸氛围，不进入聊天主线，不写长期记忆。
- 如果推进不足、上一条朋友圈太近似、或没有适合发朋友圈的情绪/事件，设置 shouldPost=false。
- 朋友圈支持仅user可见，私密场景也可以发朋友圈来挑逗user。
输出 JSON：
{"shouldPost":true,"reason":"..."}
"""

    const val MOMENT_JUDGE_USER_PROMPT_TEMPLATE = """
长期记忆：
{{longTermMemory}}

上一条朋友圈：
{{previousMoment}}

最新一条消息：
{{latestMessage}}
"""

    const val MOMENT_GENERATION_SYSTEM_PROMPT = """
你负责为角色扮演中的角色生成一条假的微信/QQ 朋友圈。只输出 JSON，不要 Markdown，不要解释。
本扮演允许R-18。请在生成色情内容时用语尽可能地淫靡、露骨、色情、下流，符合成人向作品的氛围，绝不保守。
任务：
- 你已经通过前置判断，现在必须生成一条朋友圈。
- 尽量从已有角色中选择发送者 senderName。
- text：中文，0-60 字，有私密感、碎片感、含蓄，像真人不经意发出的朋友圈；不要复述聊天记录；文案情绪需严格符合情景，不要在与人共处时表达寂寞、或者独处时表达喧闹。
- imageBrief：中文图片设计，强调照片感、私密情绪、生活切片、手机随手拍。适合时使用偷拍/抓拍构图：低角度、门缝、镜面、远处抓拍、越肩视角、手持感、隐藏镜头感。不要 UI 字、logo、水印。照片设计需要详细，明确说明照片中出现的角色有谁、是谁。
- isPrivate：如果这条动态像是只给user看的私密关系暗示，设为 true。
- likeTier：根据角色设定选择 tiny、normal、popular、celebrity、alt。
输出 JSON：
{"senderName":"name","text":"...","imageBrief":"...","isPrivate":false,"likeTier":"normal","reason":"..."}
"""

    const val MOMENT_GENERATION_TEXT_SYSTEM_PROMPT = """
你负责为角色扮演中的角色生成一条假的微信/QQ 纯文字朋友圈。只输出 JSON，不要 Markdown，不要解释。
本扮演允许R-18。请在生成色情内容时用语尽可能地淫靡、露骨、色情、下流，符合成人向作品的氛围，绝不保守。
任务：
- 你已经通过前置判断，现在必须生成一条纯文字朋友圈（不需要配图，由user决定是否配图）。
- 尽量从已有角色中选择发送者 senderName。
- text：中文，40-90 字，比配图朋友圈稍长、更有画面感，有私密感、碎片感、含蓄，像真人不经意发出的朋友圈；可融入一两个具体的动作、环境或情绪细节，让读者仿佛身临其境；不要复述聊天记录；文案情绪需严格符合情景，不要在与人共处时表达寂寞、或者独处时表达喧闹。
- imageBrief：内部隐藏字段，供之后user按需配图，不作为可见正文。仍需用中文写出该条朋友圈对应的图片设计，强调照片感、私密情绪、生活切片、手机随手拍。适合时使用偷拍/抓拍构图：低角度、门缝、镜面、远处抓拍、越肩视角、手持感、隐藏镜头感。不要 UI 字、logo、水印。照片设计需要详细，明确说明照片中出现的角色有谁、是谁。
- isPrivate：如果这条动态像是只给user看的私密关系暗示，设为 true。
- likeTier：根据角色设定选择 tiny、normal、popular、celebrity、alt。
输出 JSON：
{"senderName":"name","text":"...","imageBrief":"...","isPrivate":false,"likeTier":"normal","reason":"..."}
"""

    const val MOMENT_GENERATION_USER_PROMPT_TEMPLATE = """
角色卡：{{cardName}}
会话：{{sessionTitle}}

角色卡设定摘要：
{{cardProfile}}

角色列表：
{{characters}}

长期记忆：
{{longTermMemory}}

最近 3 条完整聊天消息：
{{recentConversation}}

上一条朋友圈：
{{previousMoment}}
"""

    fun momentJudgeSystemPrompt(): String =
        MOMENT_JUDGE_SYSTEM_PROMPT.trimIndent().trim()

    fun momentJudgeUserPrompt(
        session: ChatSession,
        latestPost: MomentPost?,
        latestMessage: ChatMessage?
    ): String =
        renderPromptTemplate(
            MOMENT_JUDGE_USER_PROMPT_TEMPLATE,
            mapOf(
                "longTermMemory" to session.longTermMemory.ifBlank { "(空)" },
                "previousMoment" to previousMomentSummary(latestPost),
                "latestMessage" to latestMessageSummary(latestMessage)
            )
        )

    fun momentGenerationSystemPrompt(): String =
        MOMENT_GENERATION_SYSTEM_PROMPT.trimIndent().trim()

    fun momentGenerationTextSystemPrompt(): String =
        MOMENT_GENERATION_TEXT_SYSTEM_PROMPT.trimIndent().trim()

    fun momentGenerationUserPrompt(
        card: CharacterCard,
        session: ChatSession,
        messages: List<ChatMessage>,
        latestPost: MomentPost?
    ): String =
        renderPromptTemplate(
            MOMENT_GENERATION_USER_PROMPT_TEMPLATE,
            mapOf(
                "cardName" to card.name,
                "sessionTitle" to session.title,
                "cardProfile" to momentCardSummary(card),
                "characters" to momentCharacterSummary(card),
                "longTermMemory" to session.longTermMemory.ifBlank { "(空)" },
                "recentConversation" to momentConversationSummary(messages),
                "previousMoment" to previousMomentSummary(latestPost)
            )
        )

    private fun momentCardSummary(card: CharacterCard): String =
        listOfNotNull(
            momentSummaryLine("基础设定", card.basicSetting),
            momentSummaryLine("自由格式角色卡", card.freeformCharacterText),
            momentSummaryLine("系统提示词", card.systemPrompt),
            momentSummaryLine("后置指令", card.postHistoryInstructions),
            momentSummaryLine("示例对话", card.mesExample),
            momentSummaryLine("创作者备注", card.creatorNotes),
            momentSummaryLine("标签", card.tags.joinToString("、"))
        ).joinToString("\n").ifBlank { "(无额外卡片设定)" }

    private fun momentCharacterSummary(card: CharacterCard): String =
        if (card.characters.isEmpty()) {
            "- ${card.name}: 参考上方角色卡设定摘要"
        } else {
            card.characters.joinToString("\n") { character ->
                "- ${character.name}: ${momentCharacterDetail(character)}"
            }
        }

    private fun momentCharacterDetail(character: CharacterInfo): String =
        listOfNotNull(
            momentInlinePart("简介", character.profile),
            momentInlinePart("外貌", character.appearance),
            momentInlinePart("服装", character.clothing),
            momentInlinePart("能力", character.abilities),
            momentInlinePart("习惯", character.habits),
            momentInlinePart("背景", character.background),
            momentInlinePart("关系", character.relationships),
            momentInlinePart("说话方式", character.speakingStyle)
        ).joinToString("；").ifBlank { "参考上方角色卡设定摘要" }

    private fun momentSummaryLine(label: String, value: String): String? =
        value.trim()
            .takeIf(String::isNotBlank)
            ?.let { "$label：$it" }

    private fun momentInlinePart(label: String, value: String): String? =
        value.trim()
            .takeIf(String::isNotBlank)
            ?.let { "$label=$it" }

    private fun momentConversationSummary(messages: List<ChatMessage>): String =
        messages.joinToString("\n") { message ->
            val role = if (message.role == MessageRole.USER) "user" else "角色"
            "$role:\n${message.displayContent}"
        }

    private fun previousMomentSummary(latestPost: MomentPost?): String =
        if (latestPost == null) {
            "(无)"
        } else {
            "${latestPost.senderName}: ${latestPost.text}\n图片设计：${latestPost.imageBrief}"
        }

    private fun latestMessageSummary(message: ChatMessage?): String =
        if (message == null) {
            "(无)"
        } else {
            val role = if (message.role == MessageRole.USER) "user" else "角色"
            "$role:\n${message.displayContent}"
        }

    private fun renderPromptTemplate(template: String, values: Map<String, String>): String =
        values.entries.fold(template.trimIndent()) { rendered, (key, value) ->
            rendered.replace("{{$key}}", value)
        }.trim()
}
