# 旧 Prompt 与支持行的定向归属复核

运行产品固定于 c615657fcf476312bf459d0b2b6dc4d7fc9eb5ad；本次只更新对照账和说明，不改产品、Prompt、1257 安装包或签名38，不提升正式 baseline。

314 的原25条 PENDING 是逐 commit/file 记录，不是25个漏功能。定向查看其原始 before/after blob 差异，并核对当前物理 owner 后，7条已有独立实现与采用依据，改记 EQUIVALENT；余18条保留 PENDING，但列出明确的未决子项和已闭部分。607条 delta ID、370条 behavior 及原有失败/证据保留，唯一未决 behavior 仍为 POST149_PROMPT，complete 仍 false。

## 不依赖315、复用原证据关闭的7条

| 原提交 | 实际差异 | 现用 owner / 依据 |
|---|---|---|
| ace632cc | 三项既有确认常量的已采纳改动 | MainChatPromptAuthority / AuxiliaryPromptAuthority 三个常量与原 after blob 精确一致；14_UPSTREAM_COMPAT 的原批准例外、bc2571e1原实现与当前 assembler/facade。不是删除身份提醒的后继方案。 |
| bbc4907c | 头像专用正负词、拼接 helper 与目录 | 258 的明确用户采用；CharacterCardAiPromptAuthority 两常量精确一致，CharacterAvatarImagePolicy 实际调用，原512正负词/非头像保全检查；314 APPROVED_AVATAR_NEGATIVE_HELPER_ONE_DELTA 和既有头像证据复用。 |
| 54c84c47 | 透明背景 opt-in tag | NovelAiPromptAuthority 与 NovelAiV5GenerationPolicy/TextPromptPolicy 实际消费者；NAI_SERVICE_POLICY_TEN_COMPLETE_DELTAS 原 text/wire 范围。 |
| 310d475c、3f6f1bb5 | Medium Heavy负词及 nsfw 条件 | 当前常量与原 helper 的 Heavy 原文精确一致；ImageService/TextPolicy 请求期调用，不改保存的 High 偏好；后一条为合并组合，不重复计功能或测试。 |
| c02f8b78 | Medium helper 后增加透明 tag | 同一 opt-in tag 和实际请求消费者，原文本/最终wire证据复用。 |
| b0fa1a42 | 合并 Medium helper 与透明 tag | 以上两项组合，无额外 Prompt 决定，不重复计数。 |

用户已在255采纳1.4.7幕后提醒措辞，并在258单独采纳头像正负词；两项继续有效，不重新索取授权。315提出后继删除幕后提醒，是另一项尚未采用的决定，不能覆盖原批准。

## 余18条的准确未决范围

- 两条 PromptTemplates 行：08fc 的最终身份 USER 删除、确认句号改冒号等现有文字/尾部改变仍按315；其中新实验协议与默认关闭传输已独立实现。fcfd 仅对应两个 system prefix 原句删除。主观写作风格增量本批排除，不是功能前置条件。
- AiTaskRequests 一条：辅助封装8→7、最终身份 USER 删除、幂等识别的长度/尾部/切片及开销同步。当前8条仍是已采用行为，不以旧8条通过证明新7条。
- 08fc 三条 skill：runtime 混合记录里的独立实验 producer、decoder、preview、inactivity 已有原证据；待决仅新尾部/原身份 USER 删除。NovelAI skill 的7条封装和末尾 assistant、pipeline skill 的新尾部与普通HTTP顺序均依赖315，不把支持文档当新增产品缺口。
- 286916 四条 skill：feature-map 的实际模式入口、runtime 的模式后缀、pipeline 的模式前置/后置/历史包装/缓存、UI skill 的实际模式控件与原文查看手势均须与315接线决定一致。Android的28dp、IME和手势布局不是要求 Desktop 逐像素照搬；现有 Desktop 图片/全屏/发送/响应式布局保留。
- 八条测试支持行：AuxiliaryTaskTransport、NovelAiSceneHistory、AiTaskRequests 的新7条尾部；两次 CurrentTurnMessageOrder 的最终身份 USER 删除或模式顺序/缓存；ExperimentalToolChat 的新辅助尾部与 open-string 混合期望；ChatNarrativeMode 的实际历史包装/模式接线；PromptAssemblerCharacterMode 的实际模式层。各独立协议、持久化、版本、copy/SaveSlot、原顺序检查继续复用原 scoped 证据，不拿 dormant helper 通过冒充新运行接线。

逐行的 `legacyPromptSupportReview` 保存原差异哈希、已闭子项和 pendingSubitems，原 PENDING 原因另留 priorPendingReason；已有细分支持审查证据不删除。

## 固定最终目标与验证限度

历史314/315目标为12e420a5，当前只读最终目标为41f6e0cc。既有 `PROMPT315_FIXED_TARGET_REFRESH_REVIEW.json` 已对这段 PromptTemplates 差异定点核实：只有模式参数 nullable、停用时不注入尾部、模式切换提示条件和目录说明，没有新增315原常量文字差异。当前 shared helpers 已保留 null 停用语义，不借此实施未采纳的模式/尾部接线。

本轮静态检查验证了原25行集合、7关闭/18未决、原607/370身份与其他条目不变、三确认和头像常量及Medium预设的精确对应；只复用此前实际运行证据，没有新增JVM/native/收费调用或重跑全套。没有发现这25行中需另做运行功能修复的独立漏接，不因此宣称整体上游追齐完成。
