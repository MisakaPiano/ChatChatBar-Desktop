package com.example.chatbar.domain.budget

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.CleartextHttpChatTemplatePolicy
import com.example.chatbar.domain.chat.MainChatLogicalMessageTrace
import com.example.chatbar.domain.worldbook.WorldBookRequestActivity
import kotlinx.serialization.json.*

enum class PromptEstimateScope { STORED_EDITOR_FIELDS, FINAL_REQUEST_TEXT }

/** This profile is descriptive only. It never changes request limits or provider/model settings. */
data class PromptEstimateTarget(val modelLabel: String, val contextWindowTokens: Long? = null) {
    init { require(contextWindowTokens == null || contextWindowTokens > 0) }
}

/** Only the explicit model field is authority; output caps and message-window counts are unrelated. */
fun ModelConfig.promptEstimateTarget(label: String = displayName) = PromptEstimateTarget(label, desktopContextWindowTokens)

data class EstimatedTextTokens(val tokens: Long, val utf16Units: Int)
data class PromptBudgetSection(val id: String, val label: String, val estimate: EstimatedTextTokens, val note: String = "")

enum class PromptRenderSource { CORE, CHARACTER, SETTING_REFERENCE, PLAYER, MEMORY_RAG, SUPPLEMENTARY, REPLY_CONSTRAINTS }

/** Numeric provenance of one original render; stages overlap and must never be added together. */
data class PromptRenderMeasurement(
    val source: PromptRenderSource,
    val raw: EstimatedTextTokens,
    val afterOutlets: EstimatedTextTokens,
    val afterPlaceholders: EstimatedTextTokens,
    val resolvedOutletOccurrences: Int,
    val unresolvedOutletOccurrences: Int,
)

/** Counts come from this plan's bounded pool, not the session's complete stored history. */
data class PromptRuntimeBudgetSelection(
    val loadedCandidateMessages: Int,
    val directContextMessages: Int,
    val earlierInputMessages: Int,
    val previousInputMessages: Int,
    val earlierSentMessages: Int,
    val previousSentMessages: Int,
    val selectedRagCards: Int,
    val selectedMemoryRagCards: Int,
    val worldBook: WorldBookRequestActivity?,
    val renders: List<PromptRenderMeasurement>,
    val stablePrefixCacheable: Boolean,
)
data class PromptBudgetEstimate(
    val scope: PromptEstimateScope,
    val target: PromptEstimateTarget,
    val methodId: String = WeightedCharacterTokenEstimator.METHOD_ID,
    val sections: List<PromptBudgetSection>,
    val excludedNonTextParts: Int = 0,
    val attributedToLogicalSections: Boolean = false,
    val additionalMessageFieldsExcluded: Boolean = false,
    val runtimeSelection: PromptRuntimeBudgetSelection? = null,
    val stablePrefixEstimatedTextTokens: Long? = null,
) {
    val estimatedTextTokens: Long get() = sections.sumOf { it.estimate.tokens }
    val estimatedUtf16Units: Long get() = sections.sumOf { it.estimate.utf16Units.toLong() }
    val estimatedTextContextPercent: Double? get() = target.contextWindowTokens?.let {
        estimatedTextTokens.toDouble() / it.toDouble() * 100.0
    }
}

/** Explicit heuristic, NOT a model tokenizer: Han UTF-16 units weigh .8, other units .4. */
object WeightedCharacterTokenEstimator {
    const val METHOD_ID = "han-weighted-utf16-v1"
    fun estimate(text: String): EstimatedTextTokens {
        if (text.isEmpty()) return EstimatedTextTokens(0, 0)
        val weight = text.sumOf { if (it.code in 0x4E00..0x9FA5) 2L else 1L }
        return EstimatedTextTokens((weight * 2 / 5).coerceAtLeast(1), text.length)
    }
}

/** Counts only listed stored text; activation, placeholders, RAG, history and request envelopes are absent. */
object EditorPromptBudgetEstimator {
    private fun field(id: String, label: String, text: String, note: String = "") =
        PromptBudgetSection(id, label, WeightedCharacterTokenEstimator.estimate(text), note)

    fun character(card: CharacterCard, target: PromptEstimateTarget): PromptBudgetEstimate = PromptBudgetEstimate(
        PromptEstimateScope.STORED_EDITOR_FIELDS, target, sections = buildList {
            add(field("name", "卡名", card.name)); add(field("botName", "对话称呼", card.botName))
            add(field("system", "系统提示词", card.systemPrompt)); add(field("basic", "基本设定", card.basicSetting))
            add(field("post-history", "历史后指令", card.postHistoryInstructions)); add(field("greeting", "开场白", card.greeting, "不代表每轮重复注入"))
            card.alternateGreetings.forEachIndexed { index, text -> add(field("greeting-$index", "备选开场白 ${index + 1}", text, "仅静态字段")) }
            add(field("examples", "对话示例", card.mesExample)); add(field("notes", "作者备注", card.creatorNotes, "仅静态字段"))
            if (card.editMode == CharacterEditMode.FREEFORM) add(field("freeform", "自由人物正文", card.freeformCharacterText))
            card.characters.forEachIndexed { index, person ->
                val prefix = "person:${person.id}:$index"
                val note = if (card.editMode == CharacterEditMode.FREEFORM) "自由卡人物名册；不重复计入自由正文" else
                    if (!person.desktopEnabled) "卡内已停用；仍保留原字段，不代表发送" else "会话开关与实际请求仍会影响注入"
                add(field("$prefix:name", "${person.name} · 名称", person.name, note))
                if (card.editMode == CharacterEditMode.STRUCTURED) {
                    listOf("profile" to ("简介" to person.profile), "appearance" to ("外貌" to person.appearance),
                        "clothing" to ("服装" to person.clothing), "abilities" to ("能力" to person.abilities),
                        "habits" to ("习惯" to person.habits), "background" to ("背景" to person.background),
                        "relationships" to ("关系" to person.relationships), "speaking" to ("说话方式" to person.speakingStyle))
                        .forEach { (key, value) -> add(field("$prefix:$key", "${person.name} · ${value.first}", value.second, note)) }
                }
                add(field("$prefix:image", "${person.name} · 生图提示词", person.imagePrompt, "生图字段；此处不是 NovelAI tokenizer 计数"))
            }
            add(field("image", "默认生图提示词", card.defaultImagePrompt, "生图字段；不代表对话输入"))
            add(field("negative", "默认生图负面词", card.defaultImageNegativePrompt, "生图字段；不代表对话输入"))
        })

    fun format(card: FormatCard, target: PromptEstimateTarget): PromptBudgetEstimate = PromptBudgetEstimate(
        PromptEstimateScope.STORED_EDITOR_FIELDS, target, sections = buildList {
            add(field("content", "格式正文", card.content))
            card.userTools.forEachIndexed { index, tool ->
                if (tool.type == FormatCardUserToolType.STRONG_PROMPT_SUFFIX)
                    add(field("tool-$index", "用户工具 ${index + 1} · 强提示尾缀", tool.text, "实际位置由请求组装决定"))
            }
        })

    fun worldBook(book: WorldBook, target: PromptEstimateTarget): PromptBudgetEstimate = PromptBudgetEstimate(
        PromptEstimateScope.STORED_EDITOR_FIELDS, target, sections = buildList {
            add(field("name", "世界书名称", book.name)); add(field("description", "世界书描述", book.description))
            book.entries.forEachIndexed { index, entry ->
                val prefix = "entry:${entry.id}:$index"
                val note = if (!entry.enabled) "条目已停用；保留字段，不代表激活" else "是否激活与实际注入依本轮请求决定"
                add(field("$prefix:name", "${entry.name} · 名称", entry.name, note))
                add(field("$prefix:keys", "${entry.name} · 触发词", entry.keys.joinToString("\n"), "触发用字段，非正文注入量"))
                add(field("$prefix:secondary", "${entry.name} · 辅助触发词", entry.secondaryKeys.joinToString("\n"), "触发用字段，非正文注入量"))
                add(field("$prefix:content", "${entry.name} · 正文", entry.content, note))
            }
        })
}

/** Final wire is authoritative. Logical traces label sections only when adapted messages match exactly. */
object WirePromptBudgetEstimator {
    private data class TextParts(val text: String, val excluded: Int)
    private fun textParts(content: JsonElement): TextParts = when (content) {
        is JsonPrimitive -> if (content.isString) TextParts(content.content, 0) else TextParts("", 1)
        is JsonArray -> {
            val parts = content.map { part ->
                val obj = part as? JsonObject
                if ((obj?.get("type") as? JsonPrimitive)?.contentOrNull == "text")
                    (obj?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.let { TextParts(it.content, 0) } ?: TextParts("", 1)
                else TextParts("", 1)
            }
            TextParts(parts.filter { it.text.isNotEmpty() }.joinToString("\n") { it.text }, parts.sumOf { it.excluded })
        }
        else -> TextParts("", 1)
    }

    fun estimate(serializedRequest: String, target: PromptEstimateTarget,
        trace: List<MainChatLogicalMessageTrace> = emptyList(), allowCleartextHttp: Boolean = false,
        baseUrl: String = "", runtimeSelection: PromptRuntimeBudgetSelection? = null): PromptBudgetEstimate {
        val body = Json.parseToJsonElement(serializedRequest).jsonObject
        val messages = body["messages"] as? JsonArray ?: error("请求没有可检查的 messages 数组")
        val additionalFields = messages.any { it.jsonObject.keys.any { key -> key != "role" && key != "content" } }
        val wire = messages.map { element ->
            val message = element.jsonObject
            ChatApiMessage(message.getValue("role").jsonPrimitive.content, message.getValue("content"))
        }
        val expected = CleartextHttpChatTemplatePolicy.adaptMessages(trace.map { it.message }, allowCleartextHttp, baseUrl)
        val attributable = !additionalFields && trace.isNotEmpty() && trace.size == wire.size && expected == wire
        var excluded = 0
        val sections = wire.mapIndexed { index, message ->
            val parts = textParts(message.content); excluded += parts.excluded
            PromptBudgetSection(if (attributable) trace[index].source.name else "wire:$index", "${index + 1} · ${message.role}",
                WeightedCharacterTokenEstimator.estimate(parts.text), if (attributable) trace[index].source.name else "实际消息；逻辑分区未匹配")
        }
        return PromptBudgetEstimate(PromptEstimateScope.FINAL_REQUEST_TEXT, target, sections = sections,
            excludedNonTextParts = excluded, attributedToLogicalSections = attributable, additionalMessageFieldsExcluded = additionalFields,
            runtimeSelection = runtimeSelection.takeIf { attributable },
            stablePrefixEstimatedTextTokens = if (attributable && runtimeSelection?.stablePrefixCacheable == true)
                sections.filterIndexed { index, _ -> trace[index].inStableCachePrefix }.sumOf { it.estimate.tokens } else null)
    }
}
