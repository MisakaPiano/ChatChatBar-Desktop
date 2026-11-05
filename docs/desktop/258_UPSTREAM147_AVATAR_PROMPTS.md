# 258 用户批准的 1.4.7 头像正负词

用户在协调对话对展示的原文／差异／行为明确回复“采纳此项头像正负词”，问题 `call_ad54a240b17f49128f411a8ee4973856/1`。本地具体回执 `20261102-coordinator-upstream147-remaining-prompt-diff.json/decisionRequest/B`。与A幕后提醒（255）是两个独立批准；本项不更改公开1230冻结包、不自动提升正式baseline，不发起真实生图。

固定正式上游 `b4fa75d296680f777be095f3121ad0c4db979f17`：

```diff
-portrait, upper body
+3::portrait, upper body::, face, -1::nsfw::, simple background
```

头像专用负词追加在原卡片／默认负词之后：

```text
multiple views, split screen, collage, reference sheet, character sheet, inset, extreme close-up, eye focus, cropped head, out of frame
```

唯一物理owner仍为共享 `CharacterCardAiPromptAuthority`：新增上游正／负常量和negative builder，旧 `CHARACTER_AVATAR_NAI_COMPOSITION_TAGS`保留为正词兼容别名，不复制字面量。Android `PromptTemplates`保留委托并更新头部目录。实际 `CharacterAvatarImagePolicy.promptPlan` 调用negative builder，不能只增加无调用常量。

结构化：原卡画风 → 原人物词 → 固定头像正词；自由头像：原手填正词 → 固定头像正词。负词：原显式值（空白按原 `CharacterNaiPromptDefaults`）→ 固定头像负词。只规范原拼接边界逗号／空白，不删原词、不去重，不写回角色卡。保持512×512、空人物caption、原NAI账户／模型、无AI设计、候选／显式应用／普通保存；封面仍使用原设计plan／尺寸和负词，不加头像词。

## 待精确源云验

- 新共享 `CharacterAvatarPromptPlanTest`两项：结构化／手填／空白默认负词，原词顺序／分隔边界／兼容别名和512尺寸；不锁死可编辑自然语言。
- Android原avatar policy／Prompt facade测试改为权威常量相对顺序及委托行为，去掉旧词句快照，其他角色发言协议断言保持。
- Desktop原controller测试核实际结构化／freeform头像plan及封面原plan不变；原候选／裁剪／保存／重试结果继续回归。
- `DesktopCharacterImageGenerationTest`新增实际loopback最终请求：V4.5／V5各三种输入，核input／v4 caption正词和两份negative的完整顺序、512尺寸；非头像768×1024和原正负词不变，物理请求数不增加。有效合成frame，不访问真实NAI，不声称实际画面效果已验。

本机只做源文本核对，不编译／运行测试／渲染。108/7621不含本源码，不能代验；成熟源码按原云端、普通候选、隔离生命周期和实际授权交付。
