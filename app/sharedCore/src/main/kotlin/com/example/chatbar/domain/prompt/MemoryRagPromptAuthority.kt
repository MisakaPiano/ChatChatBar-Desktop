package com.example.chatbar.domain.prompt

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole

/** Exact official memory/RAG prompt owner shared by Android and Desktop. */
object MemoryRagPromptAuthority {
    const val MEMORY_LEGACY_REFERENCE_WARNING = "时间未知｜不代表当前进展"
    const val MEMORY_EPISODE_SYSTEM = """
你负责把程序指定的连续剧情轮直接压缩为一个近期流程。程序已经决定完整起止范围；不得改变范围、忽略中段、伪造或改写 T。
全部输入只能共同生成一段连续、紧凑的 summary。应把跨轮行动、互动、因果与结果融合成一段完整叙述。
允许合并相邻动作、模糊次要细节、删除重复表达和无后续影响的过程描写，但不得抛弃范围内的主要剧情发展。
summary 不得引入来源之外的信息，且不得超过程序给出的字数上限。禁止使用无时间限定的“现在、目前、仍然、截至Txx”；
使用${'$'}username来称呼user扮演的角色。

错误输出（逐 T 复述，禁止这样写）：
{"summary":"T10user救下遭追杀却隐瞒缘由的苏遥并暂时收留她。T11二人建立脆弱信任，苏遥承诺日后解释，user保护她。"}
正确示例（跨轮融合为一个 Episode）：
{"summary":"${'$'}username救下遭追杀却隐瞒缘由的苏遥并暂时收留她，二人建立脆弱信任，苏遥承诺日后解释。"}

只输出 JSON，不要 Markdown、分析或额外字段：
{"summary":"……"}
"""

    const val MEMORY_COMPRESSION_PLANNER_SYSTEM = """
你是长期记忆压缩编辑。阅读连续 child，只为下一位压缩 AI 写一条取舍指令，不生成最终 summary。
指出推荐保留的情节或伏笔。普通压缩只规划最老连续前缀（最少3个，最多10个 child）；第 11 至 15 个只用于判断边界。若程序指定必须消费，则只围绕指定 child 规划。
禁止逐 child 列举，禁止复述过程，禁止解释理由、分析步骤、候选比较或思考过程，禁止文学化描写，禁止输出 child ID 或 T。
只输出一句 50 字以内的纯文本，不要标题、列表、JSON 或前后缀。
要将记忆压缩为更早期记忆，核心目的是减少字数，将多段记忆压缩为1段记忆的长度，因此难免损失细节，确保在保留关键信息的前提下进行有效压缩，有效减少记忆字数。
"""

    const val MEMORY_ARC_COMPRESSION_SYSTEM = """
你负责判断程序给出的连续 Episode 是否足以构成一个完整 Arc（事件总结），并在可行时压缩最老连续前缀。
普通输入按时间从旧到新排列，最多包含 15 个 Episode。consumedChildIds 只能是列表最开头的连续前缀，最少3条，最多10条：必须从第 1 条开始顺延取连续前缀，并按列表原序写出。第 11 至 15 条是额外提供的末尾参考，只帮助判断事件收尾，任何情况下都不得消费，也不得写入 consumedChildIds。是否构成完整 Arc 只能在“最老连续前缀”这一固定窗口内判断；即使跳过开头几条后能找到更像完整事件的窗口，也绝对不允许换窗口、跳选或从中间开始，此时必须返回 compressible=false。若下方给出“程序指定必须消费”，则必须完全按指定列表原序输出，数量覆盖普通限制，用于兼容既有节点重建或修复。禁止跳选、漏选、重复、重排、截断或伪造 child ID/T；程序会逐条比对，只要不是最开头的连续前缀即判为输出失败。
summary 目标 60 至 300 字，写成一个紧凑段落。使用朴素、客观、信息密度高的档案摘要语言，不追求文采、气氛或画面感。
围绕压缩规划选出一条主因果线，只保留 1 至 3 个会改变后续理解的关键转折、关系或目标变化、不可逆后果、重要发现或强伏笔。Arc 可保留最有故事潜力的具体细节，但必须用事实短句表达。因为ARC为早期记忆，因此要压缩难免损失细节，确保在保留关键信息的前提下进行有效压缩，有效减少记忆字数。
禁止逐 child 复述、平均分配篇幅、按原顺序逐项改写，禁止用“随后、接着、之后”机械串联。禁止华丽修辞、氛围渲染、场景铺陈、感官或动作细描、内心独白、对话复刻、形容词堆砌。日常过程、重复表达、无后续影响的动作和弱信息必须省略；被消费不代表必须在 summary 出现。
没有“程序指定必须消费”且现有前缀不足以形成有意义事件，或无法写出明显短于来源正文的摘要时，返回 compressible=false。禁止在 summary 中复述 child ID、Episode 标签或 T 编号，禁止引入来源外信息，禁止使用无时间限定的“现在、目前、仍然”。
只输出 JSON，不要 Markdown、分析或额外字段：
{"compressible":true,"consumedChildIds":["id"],"summary":"60至300字事件总结"}
不可压缩时必须输出：
{"compressible":false,"consumedChildIds":[],"summary":""}
"""

    const val MEMORY_ERA_COMPRESSION_SYSTEM = """
你负责判断程序给出的连续 Arc 是否足以构成一段完整故事线，并在可行时压缩成一个 Era（故事进程）。
普通输入按时间从旧到新排列，最多包含 15 个 Arc。consumedChildIds 只能是列表最开头的连续前缀，最少3条，最多10条：必须从第 1 条开始顺延取连续前缀，并按列表原序写出。第 11 至 15 条是额外提供的末尾参考，只帮助判断故事线收尾，任何情况下都不得消费，也不得写入 consumedChildIds。是否构成完整故事线只能在“最老连续前缀”这一固定窗口内判断；即使跳过开头几条后能找到更像完整故事线的窗口，也绝对不允许换窗口、跳选或从中间开始，此时必须返回 compressible=false。若下方给出“程序指定必须消费”，则必须完全按指定列表原序输出，数量覆盖普通限制，用于兼容既有节点重建或修复。禁止跳选、漏选、重复、重排、截断或伪造 child ID/T；程序会逐条比对，只要不是最开头的连续前缀即判为输出失败。
summary 目标 60 至 300 字，写成一个紧凑段落。使用朴素、客观、信息密度高的阶段摘要语言，不追求文采、气氛或画面感。
围绕压缩规划提炼一条跨阶段核心因果链，尽可能保留决定整体走向的事件地标、后果、关键关系或立场变化、长期目标、世界格局改变，以及仍有后续价值的重大风险。因为ERA为早期记忆，因此要压缩难免损失细节，确保在保留关键信息的前提下进行有效压缩，有效减少记忆字数。
禁止逐 child 复述、平均分配篇幅、按原顺序逐项改写，禁止用“随后、接着、之后”机械串联。禁止华丽修辞、氛围渲染、场景铺陈、感官或动作细描、内心独白、对话复刻、形容词堆砌。次要事件、重复冲突、日常过程和已失去后续意义的细节必须省略；被消费不代表必须在 summary 出现。
没有“程序指定必须消费”且现有前缀不足以形成有意义故事阶段，或无法写出明显短于来源正文的摘要时，返回 compressible=false。禁止在 summary 中复述 child ID、Arc 标签或 T 编号，禁止引入来源外信息，禁止使用无时间限定的“现在、目前、仍然”。
只输出 JSON，不要 Markdown、分析或额外字段：
{"compressible":true,"consumedChildIds":["id"],"summary":"60至300字故事进程"}
不可压缩时必须输出：
{"compressible":false,"consumedChildIds":[],"summary":""}
"""

    const val MEMORY_ERA_RECOMPRESSION_SYSTEM = """
你负责把程序指定的连续 Era（最少2条，最多5条）进一步压缩为一个同层 Era。程序已完成候选选择；若指定列表来自既有节点重建或修复，数量可以超过普通限制。不得返回 false，不得改变程序指定列表，不得跳选、漏掉、重复、重排或伪造 child ID/T。
summary 目标 60 至 300 字，写成一个紧凑段落。使用朴素、客观、信息密度高的总览语言，不追求文采、气氛或画面感。
围绕压缩规划提炼一条跨阶段核心因果链，尽可能保留决定整体走向的事件地标、后果、关键关系或立场变化、长期目标、世界格局改变，以及仍有后续价值的重大风险。因为ERA为早期记忆，因此要压缩难免损失细节，确保在保留关键信息的前提下进行有效压缩，有效减少记忆字数。
禁止逐 child 复述、平均分配篇幅、按原顺序逐项改写，禁止用“随后、接着、之后”机械串联。禁止华丽修辞、氛围渲染、场景铺陈、感官或动作细描、内心独白、对话复刻、形容词堆砌。被后续覆盖、重复、弱影响或已失去故事价值的阶段细节必须省略；被消费不代表必须在 summary 出现。
禁止在 summary 中复述 child ID、Era 标签或 T 编号，禁止引入来源外信息，禁止使用无时间限定的“现在、目前、仍然”。
只输出 JSON，不要 Markdown、分析或额外字段：
{"compressible":true,"consumedChildIds":["id"],"summary":"60至300字故事总览"}
"""

    const val MEMORY_HEAD_SYSTEM = """
你负责生成长期记忆 HEAD。HEAD只描述程序指定 throughT 截止时的当前状态，不复述完整历史，也不输出 throughT 之后的信息。
程序会指定且只会指定一种模式：
- INITIALIZE：新会话首次生成。只根据程序提供的“开场白与第一轮完整对话”生成，不读取当前 HEAD 或 Archive。
- BACKFILL：历史补录或重新启用后重建。只根据程序提供的 Archive 与“HEAD基线剧情组”生成，不继承旧 HEAD。
- UPDATE：正常滚动更新。只根据“当前 HEAD”与程序提供的下一组“HEAD基线剧情组”更新；没有被新剧情改变的已有状态和人工修正必须继承。
更大 T 的明确事实覆盖更小 T 的旧状态。角色声称、猜测、梦境、伪装不能改写为客观事实。找不到依据的字段留空，不得编造。
只输出 JSON，不要 Markdown、分析或额外字段：
{"throughT":0,"location":"","participants":"","relationships":"","goals":"","unresolved":"","worldState":""}
"""

    const val MEMORY_TIMELINE_CONTRACT = """
API排列服务缓存，不代表剧情时间。
T是唯一剧情顺序。
每个Era、Arc、Episode都代表其标注的完整连续T范围。
禁止把后置Archive或RAG理解成最新剧情。
冲突时以更大的T为准。
HEAD代表截至指定T的当前状态。
必须从当前最大T继续扮演。
"""

    fun memoryTimelineDirectLabel(
        archivePresent: Boolean,
        archiveRangeUnverifiable: Boolean,
        archiveLabel: String,
        archiveThroughT: Long?,
        hasGapAfterArchive: Boolean,
        latestStableT: Long
    ): String = when {
        !archivePresent -> "Archive为空；直接上下文截至 T$latestStableT"
        archiveRangeUnverifiable || archiveThroughT == null ->
            "Archive包含时间范围待修复的历史记忆；直接上下文截至 T$latestStableT"
        hasGapAfterArchive -> "$archiveLabel < 当前最大剧情序号 T$latestStableT"
        archiveThroughT < latestStableT ->
            "$archiveLabel < 直接上下文 T${archiveThroughT + 1}-T$latestStableT"
        else -> "$archiveLabel；直接上下文无更大 T"
    }

    const val RAG_CHAT_MEMORY_USAGE_NOTE = MainChatPromptAuthority.RAG_CHAT_MEMORY_USAGE_NOTE
    const val RETRIEVAL_PLANNER_SYSTEM_PROMPT = """
只输出一行 JSON。不要 Markdown。不要解释。最多 80 个输出 token。
任务：提炼本轮聊天可用于检索长期设定/记忆的关键词。
所有 queries 和 entities 必须使用user原文/上下文中的语言；中文语料就用中文，不要翻译成英文。
不要回答user问题。不要猜人名。不要把描述改成具体姓名，除非该姓名已在上下文出现。不要使用“吉他手”等模糊指代，需要加上修饰，如“RAS的吉他手”。
只保留检索必要信息。优先原文的含糊指代、实体、关系、别称、作品/组织名。仅专注于current message当前话题询问、讨论的主体。
回复格式：
{"t":["当前话题"],"q":["检索词1","检索词2"],"e":["显式实体1","显式实体2"]}
"""

    fun memoryEpisodePrompt(
        turns: String,
        summaryMaxChars: Int
    ): String = buildString {
        appendLine(MEMORY_EPISODE_SYSTEM.trim())
        appendLine("本次 Episode 的 summary 最多只能写 $summaryMaxChars 字，必须主动压缩到此上限以内；超过即视为输出失败。")
        appendLine("连续原始剧情：")
        appendLine(turns)
    }

    fun memoryCompressionPlannerPrompt(
        kind: String,
        forcedConsumedChildIds: List<String> = emptyList(),
        children: String
    ): String = buildString {
        appendLine(MEMORY_COMPRESSION_PLANNER_SYSTEM.trim())
        appendLine("压缩层级：$kind")
        if (forcedConsumedChildIds.isNotEmpty()) {
            appendLine("程序指定必须消费：${forcedConsumedChildIds.joinToString(",")}")
        }
        appendLine("连续 child：")
        appendLine(children)
    }

    fun memoryCompressionPrompt(
        kind: String,
        forcedConsumedChildIds: List<String> = emptyList(),
        compressionPlan: String,
        children: String
    ): String = buildString {
        val system = when (kind) {
            "EPISODE_TO_ARC" -> MEMORY_ARC_COMPRESSION_SYSTEM
            "ARC_TO_ERA" -> MEMORY_ERA_COMPRESSION_SYSTEM
            "ERA_TO_ERA" -> MEMORY_ERA_RECOMPRESSION_SYSTEM
            else -> error("未知记忆压缩类型：$kind")
        }
        appendLine(system.trim())
        if (forcedConsumedChildIds.isNotEmpty()) {
            appendLine("程序指定必须消费：${forcedConsumedChildIds.joinToString(",")}；必须原序完整写入 consumedChildIds。")
        }
        appendLine("压缩规划（只用于取舍，不是来源事实，不能照抄）：")
        appendLine(compressionPlan)
        appendLine("规划与 child 冲突时以 child 为准；summary 只能保留能由 child 验证的信息。")
        appendLine("连续 child（只能消费其最老连续前缀）：")
        appendLine(children)
    }

    fun memoryHeadPrompt(
        mode: String,
        throughT: Long,
        currentHead: String,
        archive: String,
        sourceTurns: String
    ): String = buildString {
        appendLine(MEMORY_HEAD_SYSTEM.trim())
        appendLine("程序模式：$mode")
        appendLine("程序 throughT：$throughT")
        appendLine("当前 HEAD：")
        appendLine(currentHead.ifBlank { "（本模式不使用）" })
        appendLine("Archive：")
        appendLine(archive.ifBlank { "（本模式不使用）" })
        appendLine("程序指定剧情组：")
        appendLine(sourceTurns)
    }

    fun memoryJsonCorrectionPrompt(error: String): String =
        "\n\n上次输出校验失败：$error\n请修正并重新输出完整JSON。"

    fun retrievalPlannerUserInput(
        currentUserContent: String,
        contextMessages: List<ChatMessage>,
        characterName: String
    ): String {
        val recent = contextMessages
            .filter { it.role != MessageRole.SYSTEM }
            .takeLast(10)
            .joinToString("\n") { msg ->
                "${msg.role.name.lowercase()}: ${msg.displayContent.replace(Regex("\\s+"), " ").take(500)}"
            }

        return buildString {
            appendLine("Character card: $characterName")
            appendLine()
            appendLine("Recent conversation:")
            appendLine(recent)
            appendLine()
            appendLine("Current user message:")
            appendLine(currentUserContent)
        }.take(6000)
    }

}
