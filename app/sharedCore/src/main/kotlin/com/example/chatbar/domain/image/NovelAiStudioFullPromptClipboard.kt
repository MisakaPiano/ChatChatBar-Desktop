package com.example.chatbar.domain.image

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Text interchange only. These labels and metadata never become generation prompt fields. */
data class NovelAiFullPromptContent(
    val stylePrompt: String, val basePrompt: String, val extraPrompt: String, val negativePrompt: String,
    val characters: List<NovelAiPresetCharacter>, val naturalLanguageMode: Boolean, val selectedModel: NovelAiImageModel,
    val useCharacterPositions: Boolean,
) {
    fun validate() {
        require(characters.count { it.enabled } <= selectedModel.maxCharacters)
        require(characters.all { it.center == null || it.center.x.isFinite() && it.center.y.isFinite() &&
            it.center.x in 0f..1f && it.center.y in 0f..1f })
    }
    fun applyTo(target: NovelAiStudioDraft): NovelAiStudioDraft {
        validate()
        return target.copy(stylePrompt = stylePrompt, basePrompt = basePrompt, extraPrompt = extraPrompt,
            negativePrompt = negativePrompt, naturalLanguageMode = naturalLanguageMode, selectedModel = selectedModel,
            characters = characters.map { NovelAiCharacterPromptDraft(prompt = it.prompt, negativePrompt = it.negativePrompt,
                enabled = it.enabled, center = it.center) },
            v45Settings = if (selectedModel == NovelAiImageModel.V4_5_FULL)
                target.v45Settings.copy(useCharacterPositions = useCharacterPositions) else target.v45Settings,
            v5Settings = if (selectedModel == NovelAiImageModel.V5_FULL)
                target.v5Settings.copy(useCharacterPositions = useCharacterPositions) else target.v5Settings,
            followDefaultNovelAiImageModel = false, importedCharacterCardId = null,
            importedCharacterPromptSources = emptyList(), conversionSnapshot = null)
    }
}

/** Separate from the original positive-only clipboard contract and from resource-bearing preset files. */
object NovelAiStudioFullPromptClipboard {
    const val PREAMBLE = "CCB-FULL-PROMPT/1\n"
    private val heading = Regex("【[^\\r\\n]*】")
    private val sectionHeading = Regex("(?m)^【([^\\r\\n]*)】$")
    private val json = Json { encodeDefaults = true }
    private const val MAX_TEXT_CHARS = 16 * 1024 * 1024
    @Serializable private data class Role(val enabled: Boolean, val center: DesignedCharacterCenter?)
    @Serializable private data class Controls(val selectedModel: NovelAiImageModel, val naturalLanguageMode: Boolean,
        val useCharacterPositions: Boolean, val roles: List<Role>)

    fun capture(draft: NovelAiStudioDraft) = NovelAiFullPromptContent(draft.stylePrompt, draft.basePrompt, draft.extraPrompt,
        draft.negativePrompt, draft.characters.map { NovelAiPresetCharacter(it.prompt, it.negativePrompt, it.center, it.enabled) },
        draft.naturalLanguageMode, draft.selectedModel, draft.activeSettings.useCharacterPositions).also { it.validate() }

    fun encode(draft: NovelAiStudioDraft): String {
        val content = capture(draft)
        val controls = Controls(content.selectedModel, content.naturalLanguageMode, content.useCharacterPositions,
            content.characters.map { Role(it.enabled, it.center) })
        val sections = buildList {
            add("配置" to json.encodeToString(Controls.serializer(), controls))
            add("画风" to content.stylePrompt); add("基础正向" to content.basePrompt)
            add("补充正向" to content.extraPrompt); add("全局负向" to content.negativePrompt)
            content.characters.forEachIndexed { index, role ->
                add("角色 ${index + 1} 正向" to role.prompt); add("角色 ${index + 1} 负向" to role.negativePrompt)
            }
        }
        return (PREAMBLE + sections.joinToString("\n\n") { (label, value) ->
            "【$label】\n" + value.replace("\r\n", "\n").split('\n').joinToString("\n") { line ->
                if (line.startsWith("\\") || heading.matches(line)) "\\$line" else line
            }
        }).also { require(it.length <= MAX_TEXT_CHARS) { "完整 Prompt 文本超过解析预算" } }
    }

    fun decode(text: String): NovelAiFullPromptContent {
        require(text.length <= MAX_TEXT_CHARS) { "完整 Prompt 文本超过解析预算" }
        val source = text.replace("\r\n", "\n")
        require(source.startsWith(PREAMBLE)) { "不是受支持版本的完整 Prompt 文本，请使用完整文本复制入口" }
        val matches = sectionHeading.findAll(source).toList()
        require(matches.isNotEmpty() && matches.first().range.first == PREAMBLE.length)
        val values = linkedMapOf<String, String>()
        matches.forEachIndexed { index, match ->
            val label = match.groupValues[1]
            require(label !in values) { "完整 Prompt 分段重复" }
            val end = matches.getOrNull(index + 1)?.range?.first ?: source.length
            var value = source.substring(match.range.last + 1, end)
            require(value.startsWith('\n'))
            value = value.removePrefix("\n")
            if (index < matches.lastIndex) { require(value.endsWith("\n\n")); value = value.removeSuffix("\n\n") }
            values[label] = value.split('\n').joinToString("\n") { line ->
                if (line.startsWith("\\\\") || line.startsWith("\\") && heading.matches(line.drop(1))) line.drop(1) else line
            }
        }
        val controls = json.decodeFromString(Controls.serializer(), values.getValue("配置"))
        require(values.getValue("配置") == json.encodeToString(Controls.serializer(), controls)) {
            "完整 Prompt 配置必须保留复制时的规范格式，不得重复字段"
        }
        val expected = listOf("配置", "画风", "基础正向", "补充正向", "全局负向") +
            controls.roles.indices.flatMap { listOf("角色 ${it + 1} 正向", "角色 ${it + 1} 负向") }
        require(values.keys.toList() == expected) { "完整 Prompt 分段缺失、顺序错误或包含未知分段" }
        return NovelAiFullPromptContent(values.getValue("画风"), values.getValue("基础正向"), values.getValue("补充正向"),
            values.getValue("全局负向"), controls.roles.mapIndexed { index, role ->
                NovelAiPresetCharacter(values.getValue("角色 ${index + 1} 正向"), values.getValue("角色 ${index + 1} 负向"),
                    role.center, role.enabled)
            }, controls.naturalLanguageMode, controls.selectedModel, controls.useCharacterPositions).also { it.validate() }
    }
}
