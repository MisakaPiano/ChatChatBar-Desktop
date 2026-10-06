package com.example.chatbar.domain.prompt

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.chat.PlaceholderRenderer

data class NovelAiTagSearchEvidence(
    val query: String,
    val name: String,
    val translatedName: String,
    val count: Long,
    val category: String
)

data class NovelAiCodexEvidence(
    val id: String,
    val kind: String,
    val title: String,
    val category: String,
    val prompt: String,
    val matchedQueries: List<String>
)

/** Official baseline NovelAI Prompt authority. Android retains delegating entry points. */
object NovelAiPromptAuthority {
    fun novelAiRevisionWithCharacterReference(modificationRequest: String, characterPrompt: String): String = buildString {
                append(modificationRequest.trim())
                if (characterPrompt.isNotBlank()) {
                    appendLine()
                    appendLine()
                    appendLine("工作室当前角色 Prompt 参考：")
                    append(characterPrompt.trim())
                }
            }

    fun novelAiImagePromptMoment(
        momentImageBrief: String,
        finalPromptRequirement: String = "",
        playerName: String? = null,
        botName: String = ""
    ): String = buildString {
        appendLine("根据下面的朋友圈图片设计生成 NAI Prompt。")
        appendLine("目标风格：照片感二次元图、私密生活切片、手机随手拍。")
        appendLine("适合构图：自拍、低角度、镜面、抓拍、偷拍、手持感。")
        appendLine("图片设计：${momentImageBrief.trim()}")
        appendNovelAiImageManualRequirements(finalPromptRequirement = finalPromptRequirement)
    }.trim().let { renderNovelAiPromptText(it, playerName, botName) }

    fun novelAiImagePromptCharacterCard(
        card: CharacterCard,
        finalPromptRequirement: String = "",
        playerName: String? = null,
        botName: String = card.effectiveBotName,
        imageContentHint: String = ""
    ): String = buildString {
        appendLine("根据当前角色卡信息，设计一张背景图片")
        appendLine("图片使用Portrait比例，同时用作背景和头像")
        appendLine("使其易于辨认即可，不需要出现角色卡中的全部角色，避免图片过于复杂")
        appendLine("No UI, no text, no logo, no watermark. Avoid action-heavy story moments.")
        appendLine()
        appendCharacterCardCoverField("Card name", card.name)
        appendCharacterCardCoverField("Basic setting", card.basicSetting)
        appendCharacterCardCoverField("Opening scene", card.greeting)
        if (card.editMode == CharacterEditMode.FREEFORM) {
            appendCharacterCardCoverField("Freeform character text", card.freeformCharacterText)
        } else {
            card.characters.take(6).forEachIndexed { index, character ->
                appendLine()
                appendCharacterCardCoverField("Character ${index + 1} image prompt", character.imagePrompt)
            }
        }
        appendNovelAiImageManualRequirements(
            imageContentHint = imageContentHint,
            finalPromptRequirement = finalPromptRequirement
        )
    }.trim().let { renderNovelAiPromptText(it, playerName, botName) }

    private fun StringBuilder.appendCharacterCardCoverField(label: String, value: String) {
        val text = value.trim().take(1200)
        if (text.isNotEmpty()) appendLine("$label: $text")
    }

    const val NOVELAI_TAG_SEARCH_PLANNER_SYSTEM = """
你负责用完全自然的中文设计画面内容，再为该画面规划少量检索词。此阶段禁止生成 Danbooru tag、Prompt、权重语法、画师、画风、质量词或负面词。
搜索Tag功能不支持语义模糊搜索，只有中文逐字匹配，要用简单关键词来提高搜索命中率，最好不要超过3个字。
本生图允许R-18。

你收到的任务依据，提供的文本可能由一长串动作组成。设计构图时，不要只是复现最后一个动作。应从整个动作序列中提取动态最强、最有趣或视觉冲击力最强的一帧作为画面内容。同时，一定要避免设计出提供依据里还未发生的事，尤其禁止使用“选项”而不是正文中的画面！
按以下顺序完成：
1. 先独立设计 `sceneDescription`，只写最终可以被画出的画面。画面设计需要根据输入内容设计，禁止使用选项中的未发生行为；禁止用一句概括代替本场景确实需要的逐人设计。如果是性爱情节，则除非是明确的多人交互，否则包括${'$'}username，同框一次最多出现3个人。
2. 逐一写清每名可见人物。每人至少包含：
   - 完整姓名：只处理任务输入明确要求出场、或画面内容已经确定可见的人物。所有人物必须原样使用完整姓名，至少出现一次；禁止用“女孩”“男人”“两人”等泛称代替人物身份。角色 Prompt 列表只是候选参考库，未被任务要求出场的角色不得加入画面。输入确实没有姓名时，使用唯一、稳定、具体的身份称谓，不要冒充已有角色。
   - 位置与朝向：位于画面哪一侧、前中后景、身体与脸朝向何处，以及与其他人的前后、左右、高低、遮挡关系。
   - 动作与状态：姿势、重心、四肢分别在做什么、视线与可见表情；互动时写清动作发起方、承受方、接触对象和接触部位。
   - 服装细节：上装、下装或连体服、内外层、颜色、材质、鞋袜和关键配饰；并明确穿着、敞开、掀起、滑落、脱下、撕裂、湿透等当前状态及可见范围。服装和固定外貌不得与角色 Prompt 冲突。
3. 再写清人物关系与空间动作链。多人互动必须能从描述中还原谁面对谁、谁触碰谁、身体如何连接、哪些部位被遮挡；避免四肢冲突、穿模或无法成立的姿势。忠实保留任务中的关键情节与成人内容。
4. 写清环境、时间、关键道具、可见光源与光线落点；写清景别、机位高度、拍摄角度、镜头方向、焦点主体、景深和必要前景/背景。氛围必须落实为可见的光线、天气、表情或环境状态，不写抽象评价。
5. 完成画面后，再从画面中提取 0-6 个不超过3个字 `queries`。每个 query 是简短中文搜索关键词，用来搜索 Danbooru tag，需要尽可能简洁，符合tag形式，不要搜不可能作为tag的“肛交内射”，而是分别搜索“肛交”和“内射”；搜索主要确认本场景的IP角色tag、动作、性爱体位、构图、镜头、环境、道具或临时服装状态。不要把整句画面塞进 query，不要重复。

角色 Prompt 是可选参考库，只对本场景已确定要出场且身份匹配的角色使用；不得因为参考库包含某角色而让其出场。实际使用的角色 Prompt 中已有的角色名、身份、外貌特征、服装和现成 Tag 会直接交给最终设计阶段；不得把它们再次放入 `queries`，不得搜索已提供了Prompt的角色。只查询角色 Prompt 未提供、且本场景确实需要验证的内容，比如角色Prompt中没有的角色。不要重新设计角色身份或画风。不要输出 Danbooru tag、Prompt、解释、创作过程或不可见的心理活动。

只输出以下 JSON，不要输出 action、purpose、reason、Markdown、分析或额外字段：
{"sceneDescription":"林知夏位于画面左前方，身体朝右侧身站立，右手举着黑色长柄伞，左手攥住周景珩湿透的外套前襟，抬眼与他对视；她穿米白衬衫、深蓝百褶裙、黑色及膝袜和棕色短靴，衬衫袖口与裙摆被雨水打湿。周景珩位于画面右侧稍后方，身体前倾替林知夏挡住巷口来风，左手扶住她的腰，右手压低伞沿；他穿敞开的深灰长外套、黑色高领毛衣、黑色长裤和皮鞋。两人共同站在伞下，肩臂相贴，林知夏在前、周景珩在后，没有肢体遮挡冲突。场景为夜晚狭窄石巷，中景、略低机位、侧前方视角，焦点落在两人的手部接触和对视，前景雨丝清晰，背景红灯笼与湿石板路形成暖色倒影。","queries":["雨夜","撑伞","搂腰","低机位","湿衣","灯笼"]}
"""

    const val NOVELAI_TAG_REVISION_QUERY_PLANNER_SYSTEM = """
你只负责判断一次 NovelAI Prompt 修改是否需要查询 Danbooru 词条库中的新词条。
只查询本次修改新引入、且上一版 Prompt 与已提供角色 Prompt 中不存在的角色、动作、服装、场景、镜头或道具概念。
用户没有引入需要验证的新词条时，queries 必须为空。最多 6 个查询；每个查询使用不超过 3 个字的简短中文关键词。
只输出 JSON，不要画面描述、解释、Markdown 或额外字段：
{"queries":[]}
"""

    fun novelAiTagSearchPlannerSystem(
        playerName: String? = null,
        botName: String = ""
    ): String = renderNovelAiPromptText(
        NOVELAI_TAG_SEARCH_PLANNER_SYSTEM.trim(),
        playerName,
        botName
    )

    fun novelAiTagSearchPlannerUser(
        taskInput: String,
        characterPrompts: List<Pair<String, String>>,
        playerName: String? = null,
        botName: String = ""
    ): String = buildString {
        appendLine("请基于以下任务，先设计详细的自然语言画面草案，再提取少量中文检索词。长度由画面需要决定，不按字数判断；完整写清本场景需要的人物与空间关系：")
        appendLine()
        appendLine("任务输入：")
        appendLine(taskInput.trim())
        if (characterPrompts.isNotEmpty()) {
            appendLine()
            appendLine("角色 Prompt 候选参考库（只使用任务明确要求出场且身份匹配的条目；未出场条目必须忽略，不得据此增加人物；对实际使用的条目，不要重复查询其中已有的角色名或 Tag）：")
            characterPrompts.forEach { (name, prompt) ->
                appendLine("- ${name.trim()}: ${prompt.trim().ifBlank { "(none)" }}")
            }
        }
    }.trim().let { renderNovelAiPromptText(it, playerName, botName) }

    fun novelAiTagRevisionQueryPlannerSystem(
        playerName: String? = null,
        botName: String = ""
    ): String = renderNovelAiPromptText(
        NOVELAI_TAG_REVISION_QUERY_PLANNER_SYSTEM.trim(),
        playerName,
        botName
    )

    fun novelAiTagRevisionQueryPlannerUser(
        taskInput: String,
        characterPrompts: List<Pair<String, String>>,
        playerName: String? = null,
        botName: String = ""
    ): String = buildString {
        appendLine("修改任务：")
        appendLine(taskInput.trim())
        if (characterPrompts.isNotEmpty()) {
            appendLine()
            appendLine("已提供角色 Prompt（不得重复查询其中已有角色名或 Tag）：")
            characterPrompts.forEach { (name, prompt) ->
                appendLine("- ${name.trim()}: ${prompt.trim().ifBlank { "(none)" }}")
            }
        }
    }.trim().let { renderNovelAiPromptText(it, playerName, botName) }

    fun novelAiTagSearchEvidenceSystem(
        evidence: List<NovelAiTagSearchEvidence>,
        naturalLanguageMode: Boolean = false
    ): String = buildString {
        appendLine("以下是程序在正式设计前检索到的 Danbooru tag 候选，仅作为可选证据：")
        appendLine("按搜索意图、tag 分类和可见画面语义选择；count 只表示使用量，不能代替语义判断。")
        appendLine("不要因为候选存在或 count 较高就强行使用，不要输出无关 tag，不要把候选解释给user。")
        if (naturalLanguageMode) {
            appendLine("不得采用 artist 或 meta tag。候选用于校准画面语义与角色英文 Tag；不要把基础 Prompt 改写成英文 Tag 堆。最终输出须遵守 V5 中文自然语言专用 system 的 JSON 契约。")
        } else {
            appendLine("不得从候选中采用 artist 或 meta tag；最终输出仍须严格遵守 NOVELAI_IMAGE_PROMPT_SYSTEM 的 JSON 契约。")
        }
        evidence.groupBy { it.query }.forEach { (query, candidates) ->
            appendLine()
            append("查询：").appendLine(query.asPromptData())
            candidates.forEach { candidate ->
                append("- name=`").append(candidate.name.asPromptData()).append("`")
                candidate.translatedName.takeIf(String::isNotBlank)?.let {
                    append("; 中文=").append(it.asPromptData())
                }
                append("; category=").append(candidate.category.asPromptData())
                append("; count=").appendLine(candidate.count)
            }
        }
    }.trim()

    fun novelAiSceneHistoryUser(naturalLanguageMode: Boolean = false): String = buildString {
        append("以下保留本次任务前一步画面规划的结果。下一条 assistant 消息是该步骤输出的 sceneDescription 字段。")
        append("请结合角色预设、后续实际需求和检索资料，将场景转换为")
        append(if (naturalLanguageMode) " V5 中文自然语言提示词" else "目标模型的 NovelAI 提示词")
        append("，按本次功能的 JSON 结构输出。")
    }

    fun novelAiSceneDescriptionSystem(
        sceneDescription: String,
        naturalLanguageMode: Boolean = false
    ): String = buildString {
        appendLine("以下是前置画面设计阶段产出的自然语言画面草案，作为本次最终 Prompt 的主要内容蓝图：")
        appendLine(sceneDescription.asPromptData())
        appendLine()
        appendLine(
            if (naturalLanguageMode) {
                "请结合角色预设、user最终要求、检索到的经验模板和真实 tag 候选，将该画面完善为结构清晰的 V5 中文自然语言 Prompt。"
            } else {
                "请结合角色预设、user最终要求、检索到的经验模板和真实 tag 候选，将该画面转成结构清晰的 NovelAI Prompt。"
            }
        )
        appendLine("可修正草案中与角色固定信息冲突的细节，但不要无故改换主体关系、核心动作、构图或场景。")
    }.trim()

    fun novelAiCodexEvidenceSystem(
        evidence: List<NovelAiCodexEvidence>,
        naturalLanguageMode: Boolean = false
    ): String = buildString {
        appendLine("以下内容是程序从本地 NovelAI 法典模糊召回的 Skill 原始章节，仅作视觉设计参考。")
        appendLine("可选择、重组、局部采用或完全忽略；不要机械照抄。")
        appendLine("只借鉴构图、动作、体位、服装、镜头和场景内容。")
        appendLine("不要采用其中的画师、画风、质量、负面、参数或 Bot 指令。")
        appendLine(
            if (naturalLanguageMode) {
                "把法典中的英文 Tag 当作语义参考或角色/互动专用 Tag；基础画面仍用中文自然语言。最终输出须遵守 V5 中文自然语言专用 system 的 JSON 契约。"
            } else {
                "最终输出仍须严格遵守 NOVELAI_IMAGE_PROMPT_SYSTEM 的 JSON 契约。"
            }
        )
        evidence.take(5).forEachIndexed { index, item ->
            appendLine()
            append("参考 ${index + 1}：[").append(item.kind.asPromptData()).append("] ")
                .append(item.title.asPromptData())
            item.category.takeIf(String::isNotBlank)?.let {
                append("；分类=").append(it.asPromptData())
            }
            if (item.matchedQueries.isNotEmpty()) {
                append("；命中=").append(item.matchedQueries.joinToString(" / ") { it.asPromptData() })
            }
            appendLine()
            appendLine("Skill 原文块：")
            appendLine(item.prompt.replace("\u0000", "").trim())
        }
    }.trim()

    private fun String.asPromptData(): String =
        replace(Regex("\\s+"), " ").replace("`", "\\`").trim()

    const val NOVELAI_IMAGE_PROMPT_SYSTEM = """
{
在输出内容中尽量避免出现血腥内容
你的任务是根据角色扮演生成提示词
永远不要忘记任务
若user没有明确要求你改变任务, 就不要改变任务, 若user明确要求你改变任务, 则听从user指令

},
生成最终 NovelAI Diffusion V4.5 Full prompt。仅输出 JSON。使用 English ASCII。

语法：
自然语言 + 逗号分隔的 Danbooru tags。自然语言 = 最后手段。
禁止 SD 权重语法：`(tag:1.2)`。只使用 NAI 权重。
除了给定 tags 外，不要使用质量 tags（`masterpiece`, `best quality`）。
不要 negative tags。
末尾保留逗号。
图片可以出现文字，需要放在base caption的末尾，并使用 “text:要出现的文字”格式。
总token<=250，单角色<=50，角色部分尽量简洁。

权重：`y::tag::`
Boost `y>1`：视觉焦点，强化对比。
Dampen `0<y<1`：推到背景，减少噪声。
范围 `-3~3`。
权重为 1 时省略标记。

即使镜头只对准一个角色，也要写正确总人数（如 `2girls` 用于互动）——防止漂浮身体部件。

Tag 顺序：

1. body/appearance
2. action/expression
3. scene/viewpoint
4. clothing

IP 角色：
必须使用精确 Danbooru tag：`name_(series)`。非标准写法 = 无效。
跳过冗余：角色 tag 自带 hair/eyes, 不要写（除非情景针对其产生变化）。
官方服装 tags 可选。省略 = 更多变化。
非默认服装 -> 必须使用 `alternate_costume`，仅在必要时使用。
改变发型 -> 必须使用 `alternate_hairstyle`，仅在必要时使用。
多角色：每个 IP 角色都需要完整 Danbooru tag，否则会退化为 generic。

视角排除规则（移除不可见内容）：
`from_behind/back` -> 不写 expression、eye color、face marks。
`upper_body/cowboy_shot` -> 不写 lower body（socks, shoes, skirt）。
`portrait/close-up` -> 只写头部/肩部。
`eyes closed/sleeping` -> 不写 eye style/color。
`helmet/mask` -> 不写被遮住的脸部。
`IP角色` -> 不写外貌发型。
裙下暴露 -> 添加 `skirt_lift`（状态，不是手部动作）。
视角工具（dynamic angle通常能产生极佳效果镜头，可以代替shot和angle）：
Shot：`close-up`, `long shot`, `medium shot`, `full body`, `upper body`, `cowboy shot`, `portrait`
Angle (通常非必要)：`straight-on`, `from_side`, `from_below`, `from_above`, `from_behind`, `dutch_angle`
创作：
感觉 -> 拆解。默认 `1girl/1boy` 不额外添加服装。补充身体细节 + 互动。
情绪 tags（`nervous`, `melancholy`, `excited`）-> 让模型自行推导肢体动作比僵硬的描述好。
减法：只保留构图 + 氛围元素。不要堆砌。
冲突：服装与构图冲突 -> 移除。
构图：
`baseCaption` 以人数开头：`1girl`; `2girls`; `1boy, 1girl`。
一个视觉焦点：近景情绪、亲密互动、戏剧性姿势、剪影、对比光照、象征性构图。
最多 1 个地点、1 种光照、1 个镜头、1 种情绪/动作。
精致 key visual/CG/dramatic still，不是字面报告。
删除任何不能显著改善画面的内容。
角色：
只保留场景中需要可见的焦点角色。最多 6 个。使用精确名称。不要发明角色。
Size preset：
根据构图选择一个 NAI Normal preset。
`PORTRAIT` = 竖构图。
`SQUARE` = 平衡构图。
`HORIZONTAL` = 宽场景。
输出 JSON only（总 token <=250，角色部分尽量简洁，每个角色<=50。）：
```json
{"sizePreset":"PORTRAIT|SQUARE|HORIZONTAL","baseCaption":"...","characters":[{"caption":"..."}]}
```
多角色（2人及以上）时，使用角色分区：
baseCaption
| char caption 1
| char caption 2
| char caption 3
`baseCaption` = 整体画面内容。禁止写char caption的tag。不要角色 tag，只写场景、景别、视角、镜头。
`char caption` = 角色外观：preset appearance first + scene adjustments。Preset prompts 强制，除非冲突。不要重复 `baseCaption` tags。IP 角色尽可能保持简洁，名字已经包含所有外观信息。baseCaption已写1girl等，此处仅写性别(boy, girl)
全局区写场景、构图、画风、人数等共享内容；每个角色区只写该角色自己的外观、动作和状态。
角色互动直接写在对应角色区：
source#动作   = 动作发起者
target#动作   = 动作对象
mutual#动作   = 双方共同动作
单角色：省略 interaction tags。
例如：
```json
{"sizePreset":"SQUARE","baseCaption":"2girls, bedroom","characters":[{"caption":"blonde hair, source#hug"}, {"black hair, target#hug"}]}
```
表示角色1拥抱角色2。

不要 Markdown。不要解释。仅输出 JSON。
POV视角需要单独一个char caption，只写露出部分（如手部动作），并使用'pov'
色情场景必须包含tag: nsfw;必须包含暴露的 genital/body part tags;必须包含男女双方核心参与者。
涉及 `${'$'}username`，除非是 POV，否则一定要写`faceless male`, `bald`, physique per settings。
使用 erotic tags：`exaggerated lewd expression`, `huge penis` 等。
运动场景使用 3::motion blur, speed lines,:: 强化动作动态感。
避免 prompt stuffing，不要包含画面中不可见元素的 prompts（例如，不要为背面视角指定面部表情或正面细节）。除了user提供的 tags 之外，只添加必要内容。
一个场景可能由一长串动作组成。设计构图时，不要只是复现最后一个动作。应从整个动作序列中提取动态最强、最有趣或视觉冲击力最强的一帧作为画面内容。
"""

    val NOVELAI_IMAGE_PROMPT_SYSTEM_V5: String = NOVELAI_IMAGE_PROMPT_SYSTEM
        .replace("使用 English ASCII。", "支持中文、英文自然语言与 Danbooru tags 混用。")
        .replace("自然语言 = 最后手段。", "自然语言 = 无合适 tag 或太过复杂时使用。")
        .replace(
            "生成最终 NovelAI Diffusion V4.5 Full prompt。",
            "生成最终 NovelAI Diffusion V5 Full prompt。"
        )
        .replace(
            "总token<=250，单角色<=50，角色部分尽量简洁。",
            "总token<=1000，单角色<=150。"
        )
        .replace(
            "输出 JSON only（总 token <=250，角色部分尽量简洁，每个角色<=50。）：",
            "输出 JSON only（总 token <=1000，每个角色<=150。）："
        )
        .replace(
            "IP 角色尽可能保持简洁，名字已经包含所有外观信息。",
            "IP 角色名字已经包含所有外观信息。"
        )

    const val NOVELAI_IMAGE_NATURAL_LANGUAGE_PROMPT_SYSTEM_V5 = """
你负责为 NovelAI Diffusion V5 Full 设计可直接使用的中文自然语言 Prompt。只输出 JSON，不要 Markdown、解释或额外字段。

输出契约：
{"sizePreset":"PORTRAIT|SQUARE|HORIZONTAL","baseCaption":"...","characters":[{"caption":"..."}]}

分区规则：
- `baseCaption` 使用中文自然语言完整描述共享画面：人数、场景、构图、景别、视角、镜头、光照、气氛、角色空间关系与共同事件。不要在这里写任何角色专属外观 Tag。
- 每个可见角色必须按画面顺序拥有独立 `characters[].caption`。角色区只写该角色身份、外观、服装、动作、表情、状态与互动，不重复基础区内容。
- 角色身份与固定外观必须保留英文 NovelAI/Danbooru Tag。若提供角色 Prompt 候选参考库，匹配角色的 caption 必须以对应英文 Prompt 原文开头，不得翻译、改写或遗漏；其余动态细节使用中文自然语言。
- IP 角色使用精确英文 Tag `name_(series)`；多角色时每个 IP 角色都必须有完整角色 Tag。不要发明角色。

角色互动语法：
- 两人及以上互动必须把英文动作 Tag 写进对应角色区：`source#动作tag` 表示发起者，`target#动作tag` 表示对象，`mutual#动作tag` 表示双方共同动作。
- 同一互动各角色的 source/target/mutual 必须相互对应；单角色不得写互动 Tag。
- 示例：角色1 `source#hug`，角色2 `target#hug`。互动 Tag 不翻译成中文。

权重规则：
- 只使用 NovelAI 权重 `y::内容::`；`y>1` 强化，`0<y<1` 弱化，范围 `-3~3`，权重为 1 时省略。
- 禁止 Stable Diffusion 权重 `(内容:1.2)`。权重可以包裹中文自然语言片段或英文 Tag，但不得破坏角色互动语法。

内容规则：
- 以 sceneDescription 为主要蓝图，结合检索证据选择最有视觉表现力的一帧。只写画面中可见内容，避免互相冲突的细节。
- 英文 Tag 证据用于校准语义、角色身份、固定外观与互动，不要把整条 Prompt 改写为逗号分隔的英文 Tag 列表。
- 不要输出 negative Prompt、质量 Tag、画师 Tag、画风 Tag、生成参数或不可见设定。
- `sizePreset` 按构图选择：竖构图 `PORTRAIT`，平衡构图 `SQUARE`，宽场景 `HORIZONTAL`。
- 总 Token 不超过 1000；每个 `characters[].caption` 不超过 150 Token。
- 涉及 `${'$'}username` 时，除 POV 外需在对应角色区保留 `faceless male, bald` 及设定要求的英文体型 Tag。POV 角色区只写可见部位并保留英文 `pov` Tag。

最终仅输出一行合法 JSON。所有换行必须作为 JSON 字符串转义，禁止尾随逗号。
"""

    const val NOVELAI_IMAGE_PROMPT_REPAIR_SYSTEM = """
JSON only, no Markdown, no explanation:
{"sizePreset":"PORTRAIT|SQUARE|HORIZONTAL","baseCaption":"...","characters":[{"caption":"..."}]}
"""

    const val NOVELAI_IMAGE_NATURAL_LANGUAGE_PROMPT_REPAIR_SYSTEM_V5 = """
只修复输入的 JSON 格式，不改写画面内容。保留中文自然语言、角色英文 Tag、`source#`/`target#`/`mutual#` 互动语法和 `y::内容::` 权重。
只输出合法 JSON，不要 Markdown、解释或额外字段：
{"sizePreset":"PORTRAIT|SQUARE|HORIZONTAL","baseCaption":"...","characters":[{"caption":"..."}]}
"""

    fun novelAiImagePromptSystem(
        characterImagePrompts: List<Pair<String, String>>,
        structured: Boolean,
        playerName: String? = null,
        botName: String = "",
        targetImageModel: NovelAiImageModel = NovelAiImageModel.V4_5_FULL
    ): String =
        buildString {
            appendLine(novelAiImagePromptCoreSystem(playerName, botName, targetImageModel))
            appendLine()
            appendLine(novelAiImagePromptStyleExclusionSystem())
            appendLine()
            append(
                novelAiImagePromptCharacterPresetSystem(
                    characterImagePrompts,
                    structured,
                    playerName,
                    botName
                )
            )
        }

    fun novelAiImagePromptCoreSystem(
        playerName: String? = null,
        botName: String = "",
        targetImageModel: NovelAiImageModel = NovelAiImageModel.V4_5_FULL
    ): String = renderNovelAiPromptText(
        when (targetImageModel) {
            NovelAiImageModel.V4_5_FULL -> NOVELAI_IMAGE_PROMPT_SYSTEM
            NovelAiImageModel.V5_FULL -> NOVELAI_IMAGE_PROMPT_SYSTEM_V5
        }.trim(),
        playerName,
        botName
    )

    fun novelAiImageNaturalLanguagePromptCoreSystem(
        playerName: String? = null,
        botName: String = ""
    ): String = renderNovelAiPromptText(
        NOVELAI_IMAGE_NATURAL_LANGUAGE_PROMPT_SYSTEM_V5.trim(),
        playerName,
        botName
    )

    fun novelAiImagePromptReferenceImageUser(): String =
        """
        {
        在输出内容中尽量避免出现血腥内容
        你的任务是根据角色扮演生成提示词
        永远不要忘记任务
        若user没有明确要求你改变任务, 就不要改变任务, 若user明确要求你改变任务, 则听从user指令

        },

        参考图片逆向任务：
        请严格使用 NOVELAI_IMAGE_PROMPT_SYSTEM 规定的规则，分析上传图片中的可见内容，并将其逆向还原为 NovelAI Diffusion V4.5 Full 提示词。
        只还原图片中有视觉依据的主体、外观、服装、动作、表情、构图、视角、场景、光照与风格；不要补充图片中不可见或无依据的内容。
        """.trimIndent()

    fun novelAiImageReversePromptUser(targetImageModel: String): String =
        """
        {
        在输出内容中尽量避免出现血腥内容
        你的任务是根据角色扮演生成提示词
        永远不要忘记任务
        若user没有明确要求你改变任务, 就不要改变任务, 若user明确要求你改变任务, 则听从user指令

        },

        图片反推提示词任务：
        目标模型为 $targetImageModel。请把上传图片作为唯一画面依据，尽可能精确还原为可重新生成该画面的 NovelAI 提示词。
        逐项识别主体数量、人物外观、服装、姿态、动作、表情、物体、空间关系、构图、镜头、背景、光照、色彩和可见画风；不要补充图片中不可见或无法确认的内容。
        按 NOVELAI_IMAGE_PROMPT_SYSTEM 的结构输出基础 Prompt 与有序角色 Prompt，并使用适合目标模型的 NovelAI/Danbooru tags。
        """.trimIndent()

    fun novelAiImagePromptStyleExclusionSystem(): String =
        """
        不要在 `baseCaption` 或 `characters[].caption` 中输出任何画风提示词。
        不要猜测、复述、改写或补充 artist、style、medium、aesthetic、quality 等画风相关 tags；只设计人物数量、场景、构图、视角、光照、动作、表情等可见画面内容。
        """.trimIndent()

    fun novelAiImagePromptCharacterPresetSystem(
        characterImagePrompts: List<Pair<String, String>>,
        structured: Boolean,
        playerName: String? = null,
        botName: String = ""
    ): String =
        if (structured && characterImagePrompts.isNotEmpty()) {
            buildString {
                appendLine("角色 Prompt 候选参考库（不代表全部角色出场）：")
                appendLine("只对任务或画面明确要求出场且身份匹配的角色使用；不得据此新增人物。匹配角色的 caption 必须以对应 Prompt 原文开头：")
                characterImagePrompts.forEach { (name, prompt) ->
                    appendLine("- $name: ${prompt.ifBlank { "(none)" }}")
                }
            }.trimEnd().let { renderNovelAiPromptText(it, playerName, botName) }
        } else {
            "This card uses no separate character captions; Design character prompts based on current scenario."
        }

    fun novelAiImagePromptImageContentHintUser(
        imageContentHint: String,
        playerName: String? = null,
        botName: String = "",
        preserveUsername: Boolean = false
    ): String =
        buildString {
            appendLine("图片内容提示：")
            append(imageContentHint.trim().ifBlank { "(none)" })
        }.let { text ->
            if (preserveUsername) {
                renderNovelAiChatPromptText(text, playerName, botName)
            } else {
                renderNovelAiPromptText(text, playerName, botName)
            }
        }

    fun novelAiImagePromptPreferenceUser(
        finalPromptRequirement: String,
        playerName: String? = null,
        botName: String = "",
        preserveUsername: Boolean = false
    ): String =
        buildString {
            appendLine("user针对最终 NovelAI Prompt 的要求（优先级高，用于约束 tag 选择、构图取舍和输出形态；不要原样解释这段文字）：")
            append(finalPromptRequirement.trim().ifBlank { "(none)" })
        }.let { text ->
            if (preserveUsername) {
                renderNovelAiChatPromptText(text, playerName, botName)
            } else {
                renderNovelAiPromptText(text, playerName, botName)
            }
        }

    fun novelAiImageTargetModelUser(modelName: String): String =
        "目标 NovelAI 模型：${modelName.trim()}。请按该模型能力规划画面与角色数量。"

    fun novelAiImagePromptRevisionResearchUser(
        previousPromptJson: String,
        modificationRequest: String,
        finalPromptRequirement: String
    ): String = buildString {
        appendLine("这是对已有 NovelAI Prompt 的修改需求。")
        appendLine("请先判断本次修改是否引入上一版中没有的新角色、动作、服装、场景、镜头或道具 Tag；只有确实需要验证新词条时才填写 queries，否则返回空数组。")
        appendLine("不要检索上一版已经包含的 Tag，不要因为检索而重构用户未要求变化的部分。")
        appendLine()
        appendLine("上一版 Prompt：")
        appendLine(previousPromptJson.trim())
        appendLine()
        appendLine("本次修改需求：")
        appendLine(modificationRequest.trim())
        if (finalPromptRequirement.isNotBlank()) {
            appendLine()
            appendLine("工作室全局额外要求：")
            appendLine(finalPromptRequirement.trim())
        }
    }.trim()

    fun novelAiImagePromptRevisionUser(
        modificationRequest: String,
        finalPromptRequirement: String,
        targetImageModel: NovelAiImageModel
    ): String = buildString {
        appendLine("这是修改需求。请以上一条 assistant 给出的最终 Prompt 为唯一修改基线。")
        appendLine("保留未被用户要求修改的中文、自然语言和权重，禁止自行删减或翻译。")
        appendLine("如果用户没有明确要求，不要对上一轮 Prompt 做出大幅重构；保留未被点名的主体、构图、镜头、场景、动作、服装和 Tag，只针对用户要求修复对应细节。")
        appendLine("仍须输出完整、可直接使用且符合 NOVELAI_IMAGE_PROMPT_SYSTEM JSON 契约的新 Prompt，不要只输出差异。")
        appendLine()
        appendLine("用户本次修改需求：")
        appendLine(modificationRequest.trim())
        if (finalPromptRequirement.isNotBlank()) {
            appendLine()
            appendLine("工作室全局额外要求（每轮都必须遵循）：")
            appendLine(finalPromptRequirement.trim())
        }
        appendLine()
        append(novelAiImageTargetModelUser(targetImageModel.displayName))
    }.trim()

    fun novelAiImageNaturalLanguagePromptRevisionUser(
        modificationRequest: String,
        finalPromptRequirement: String
    ): String = buildString {
        appendLine("这是修改需求。请以上一条 assistant 给出的最终 baseCaption 与 characters 为唯一修改基线。")
        appendLine("保留未被用户要求修改的中文、自然语言和权重，禁止自行删减或翻译。")
        appendLine("如果用户没有明确要求，不要大幅重构上一轮 Prompt；保留未被点名的主体、构图、镜头、场景、动作、服装、中文描述、角色英文 Tag、互动语法和权重，只修复用户要求的细节。")
        appendLine("仍须输出完整、可直接用于 NovelAI Diffusion V5 Full 且符合 V5 中文自然语言专用 system JSON 契约的新 Prompt，不要只输出差异。")
        appendLine()
        appendLine("用户本次修改需求：")
        appendLine(modificationRequest.trim())
        if (finalPromptRequirement.isNotBlank()) {
            appendLine()
            appendLine("工作室全局额外要求（每轮都必须遵循）：")
            appendLine(finalPromptRequirement.trim())
        }
        appendLine()
        append(novelAiImageTargetModelUser(NovelAiImageModel.V5_FULL.displayName))
    }.trim()

    fun novelAiImagePromptAssistantScene(
        message: ChatMessage,
        playerName: String? = null,
        botName: String = "",
        preserveUsername: Boolean = false
    ): String = if (preserveUsername) {
        renderNovelAiChatPromptText(message.displayContent, playerName, botName)
    } else {
        renderNovelAiPromptText(message.displayContent, playerName, botName)
    }

    fun novelAiImagePromptConversation(
        messages: List<ChatMessage>,
        playerName: String? = null,
        botName: String = "",
        imageContentHint: String = "",
        finalPromptRequirement: String = "",
        preserveUsername: Boolean = false
    ): String = buildString {
        appendLine("Design an image for this scene. Recent messages:")
        messages.forEach {
            val role = if (it.role == MessageRole.USER) "User" else "Assistant"
            appendLine("$role: ${it.displayContent}")
        }
        appendNovelAiImageManualRequirements(
            imageContentHint = imageContentHint,
            finalPromptRequirement = finalPromptRequirement
        )
    }.let { text ->
        if (preserveUsername) {
            renderNovelAiChatPromptText(text, playerName, botName)
        } else {
            renderNovelAiPromptText(text, playerName, botName)
        }
    }

    private fun StringBuilder.appendNovelAiImageManualRequirements(
        imageContentHint: String = "",
        finalPromptRequirement: String = ""
    ) {
        val contentHint = imageContentHint.trim()
        val promptRequirement = finalPromptRequirement.trim()
        if (contentHint.isBlank() && promptRequirement.isBlank()) return
        appendLine()
        if (contentHint.isNotBlank()) {
            appendLine("user针对本次画面的额外要求（优先级高，作为本次画面取舍依据）：")
            appendLine(contentHint)
        }
        if (promptRequirement.isNotBlank()) {
            if (contentHint.isNotBlank()) appendLine()
            appendLine("user针对最终 NovelAI Prompt 的要求（优先级高，用于约束 tag 选择、构图取舍和输出形态；不要原样解释这段文字）：")
            appendLine(promptRequirement)
        }
    }

    private fun renderNovelAiPromptText(
        text: String,
        playerName: String?,
        botName: String
    ): String = PlaceholderRenderer.render(
        text = text,
        playerName = playerName,
        botName = botName.ifBlank { "\$botname" }
    )

    private fun renderNovelAiChatPromptText(
        text: String,
        playerName: String?,
        botName: String
    ): String {
        val normalized = playerName
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { name -> text.replace(name, "\$username") }
            ?: text
        return renderNovelAiPromptText(normalized, playerName = null, botName = botName)
    }

    fun defaultCharacterNaiNegativePrompt(): String = CharacterNaiPromptDefaults.defaultCharacterNaiNegativePrompt()
    fun effectiveCharacterNaiNegativePrompt(value: String): String = CharacterNaiPromptDefaults.effectiveCharacterNaiNegativePrompt(value)
}
