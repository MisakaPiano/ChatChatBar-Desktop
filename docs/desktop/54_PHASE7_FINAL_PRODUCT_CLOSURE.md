# 54 — Phase 7 Final Product Closure

## 0. 目标

这是 Phase 7 的最后产品闭合，不重做已经通过审查的 runtime。

本轮解决第三次用户人工验收发现的剩余产品问题：

- AI Design 输入与失败 UX
- Studio 信息表达与视觉层级
- CCB 正式功能文案/行为展示
- 结果预览
- 聊天附件交互
- Session Settings 信息架构

本轮不解决 provider capability 漂移，也不做全应用 IA 重构。

---

# 1. AI Design — 必须闭合

## 1.1 IME / caret correctness

当前 AI Design 的“画面需求 / 需求编辑 / 额外要求”仍使用普通 String field。

用户已实际复现：
- 中文输入法输入后，caret 跑到正在输入文字前面；
- 输入体验明显异常。

要求：

- AI Design 的所有可编辑文本必须使用 IME-safe `TextFieldValue` / selection / composition state；
- 外部 authoritative update 时才安全重置本地 editor；
- 正常 Compose recomposition 不得把 selection/composition 打回；
- inline edit / edit-and-branch / new conversation / settings requirement 都覆盖；
- Enter/Shift+Enter 行为明确；
- 不改变 AI Design repository / Prompt protocol。

这是功能 bug，不是纯美化。

## 1.2 Failure diagnostics

当前所有异常被压成：

`设计失败，可重试`

要求至少区分安全类别：

- 未配置/不可用设计模型
- credential / auth problem
- network / transport
- provider error
- malformed/incomplete reply
- cancelled
- unknown safe fallback

必须：
- 脱敏；
- 不显示 API key / token / raw provider secret body；
- 失败不污染 Studio Prompt；
- Retry 保留原始 turn/context；
- fake transport 自动覆盖。

## 1.3 Structured result card

Formal CCB `NovelAiDesignScreen` 不是单一 `displayText`。

Desktop 应结构化呈现：

- target image model
- 基础 Prompt
- ordered character Prompt modules
- per-module Copy icon/action
- Apply to Studio
- Regenerate
- Edit & branch
- failed/cancelled retry
- current Studio attachment summary

Desktop 等价可用 card/panel，不要求手机气泡像素复制。

## 1.4 Copy semantics

Formal parity 本轮必须有：

- copy individual AI Design Prompt module
- Apply to Studio

不要把“完整 Studio config export/import”混进本轮；见 `55`.

---

# 2. CharacterCard Prompt Import — 恢复 CCB 正式表达

Formal CCB 1.4.1：

**导入角色卡 Prompt**

说明：

> 填充画风；角色 Prompt 仅供 AI 设计参考，不参与实际生图

Desktop 当前把内部 `importedCharacterPromptSources` 名称直接摊在主 UI。

要求：

- label 改为“导入角色卡 Prompt”
- 显示正式用途说明
- 选择控件展示卡名即可
- 不把内部角色来源列表作为主 UI 内容展示
- 内部 AI Design context 语义保持不变
- 不改变导入算法

---

# 3. Prompt Inspector — relocation

`Prompt 检查` 是 Desktop compatibility / diagnostic tool，不是 formal CCB NovelAI Studio sibling workflow。

保留功能，但移动到：

`工具 → 高级 / 诊断 → 主聊天 Prompt 检查器`

或等价清晰层级。

要求：

- 不和 `NovelAI Studio` 作为同级普通主入口并排；
- 名称明确是“主聊天 Prompt 检查器”；
- 保留后续 upstream parity 审计价值；
- runtime 不改。

---

# 4. Button hierarchy / icons

当前 ghost action 过多，用户无法区分文字与按钮。

建立三个明确层级：

## Primary
用于：
- Generate
- Apply to Studio
- destructive-confirm / main confirm when appropriate

视觉：
- 明确填充或强边界

## Secondary
用于：
- New Seed
- Metadata
- Edit Size
- Import
- Use as
- settings actions

视觉：
- 边框/浅背景，明确可点击

## Tertiary / Icon
用于：
- Undo
- Redo
- Copy
- Clear
- Expand
- Back
- Refresh

必须有典型 icon + tooltip/accessible label。

不允许普通功能继续表现成“和背景同色的一段文字”。

---

# 5. Orientation / common action icons

为以下加入明确图形提示：

- Portrait：竖矩形
- Square：正方形
- Landscape：横矩形
- Clear：eraser/trash
- Undo：undo arrow
- Redo：redo arrow
- Copy：copy icon
- Reset：reset/restore icon
- History：history/gallery icon
- Refresh：refresh icon

文字仍保留，不做 icon-only 难发现设计。

---

# 6. Dynamic Generate label — follow formal CCB

Formal CCB 1.4.1 generation button already owns dynamic cost wording.

Desktop 必须等价：

- account/config unavailable → clear unavailable text
- busy → Stop / current task state
- continuous → show progress where appropriate
- `V5_ALLOWANCE` → `生成免费`
- `FREE` → `生成免费`
- positive Anlas cost → `生成消耗 N Anlas`
- encoding / extra Vibe cost → expose equivalent cost detail

使用现有 `NovelAiImageCostEstimator` / reviewed state。

不得重新实现 pricing logic。

---

# 7. Account/status cluster

当前 model / Anlas / refresh / V5 remaining / actions 混在一个 FlowRow。

改成：

## Status cluster
按重要性聚合：
- current image model
- Anlas
- V5 approximate allowance
- account warning/error

## Actions cluster
- refresh icon
- AI Design
- Guidance
- Import
- History
- Settings

Refresh 是次级 icon，不夹在两个额度数字中间。

---

# 8. Token usage bars

不再只显示：

`+375/1471 -137/1471`

至少两条可视进度：

- Positive token usage
- Negative token usage

要求：
- 数字仍显示；
- bar 长度按 limit；
- approaching / exceeding limit 有 warning state；
- 使用现有 token authority；
- 不改变 tokenizer。

---

# 9. Copy / import / reuse parity map

本轮必须明确并易发现：

## Studio
- Copy positive Prompt
- Paste positive Prompt
- Clear except style
- PNG metadata selective import

## AI Design
- Copy individual module
- Apply to Studio
- Regenerate
- Edit & branch

## History
- Full reproduction where available
- New Seed
- Seed only
- Use as Guidance

不要新增新的完整 Studio config schema。

如果希望“复制完整 generation config / preset import-export”，见 `55` P17 owner。

---

# 10. Adaptive result preview + filmstrip

Formal CCB OutputPanel 已有：
- large current image
- current batch thumbnails
- recent history thumbnails
- click thumbnail to select
- click big image to viewer

Desktop 当前固定约 260dp preview + previous/next 不够。

要求：

- preview 填满可用 result pane，保持 aspect-fit；
- 随窗口大小自适应；
- 设置合理 min/max，不能在 1280/1600 宽时仍只有很小图片；
- click/double-click 打开 viewer；
- 下方 recent/current horizontal filmstrip；
- 选中 thumbnail 有明显 selection；
- 支持滚轮/shift-wheel 横向浏览；
- current batch 优先，空闲时展示 recent history；
- 不复制 History repository；
- action area 紧凑放在 preview/filmstrip 附近。

---

# 11. Chat composer — attachment only

第三次用户验收明确要求：

自动生图开关和背景设置不再占 composer 工具栏。

Composer 内只保留聊天编辑相关高频动作：

- Add image attachment
- fullscreen editor
- Send / Stop

图片按钮应在输入框内部或和 fullscreen/send 同一 action rail。

不要单独占一整行。

---

# 12. Pending attachment UX

要求类似主流 AI/chat UI：

- thumbnail 至少 96–120dp 或能清晰识别；
- click thumbnail → large image preview;
- hover thumbnail → upper-right `×`;
- click `×` → remove;
- 不显示“移除附件”文字按钮；
- 多图横向滚动；
- 无附件时零高度；
- 不改变多附件 persistence/request semantics。

APNG/GIF：
- 若静态 thumbnail 无法表达动画，可显示 animation badge；
- viewer 使用现有动画能力。

---

# 13. Automatic image / Background ownership

从 composer 移除：

- automatic-image toggle/menu
- background button

统一进入 Session Settings。

保留 Assistant message 上的：
- manual Generate Image
- per-run 生图要求
- regeneration actions

这是消息级动作，不移动。

---

# 14. Session Settings tabs

当前 `PrimaryUtilities` 全部纵向堆叠，已经不可维护。

本轮只重构 **Session Settings**，不做全应用 IA overhaul。

建议 tabs：

## 基础
- Chat Model
- Reply length
- Reply language
- supplementary setting
- player name override
- player setting override

## Prompt / 上下文
- FormatCard
- WorldBooks
- inherited/extra bindings

## 图片
- session background
- clear override
- global/effective opacity shortcut
- Prompt design model
- NovelAI image model
- V5 natural-language mode
- automatic chat image
- session image Prompt requirement
- Desktop character background management shortcut where appropriate

## 高级
- truly low-frequency session-specific settings if any

要求：
- sticky/save footer remains global
- dirty state跨 tab
- Cancel/Save semantics不变
- background 当前 immediate-save contract 不因 tab 重构被偷偷改成 draft
- tabs keyboard accessible

---

# 15. Dense row layout

“能合理放一排就不要无意义换行”作为正式规则。

适用：
- chips
- small actions
- account status
- result actions
- image tool actions
- AI Design toolbar

同时要 responsive：
- 小宽度自动 wrap；
- 不允许 page-wide horizontal scroll。

---

# 16. AI Design / Studio real functional manual path

本轮完成后必须支持 fake/local manual：

1. open AI Design
2. Chinese IME type request normally
3. fake successful reply appears as structured result
4. copy one module
5. Apply to Studio
6. edit & branch
7. fake failure shows meaningful safe category
8. Retry
9. Studio original Prompt unaffected on failure

不发送真实 external AI request。

---

# 17. Hard boundaries

本轮禁止：

- Prompt literal changes
- Package changes
- Entity schema changes
- NovelAI HTTP semantics changes
- sampler/model capability changes
- pricing algorithm rewrite
- automatic-image eligibility change
- History semantic change
- Guidance semantic change
- complete Studio preset schema invention
- Moments implementation
- global settings/editor IA overhaul

NovelAI real image generation：
**0 additional requests**。

Phase total remains 1/8.

---

# 18. Required validation

Focused:

- AI Design Chinese IME composition/selection
- external update vs local composition
- structured result rendering model
- module copy
- Apply to Studio
- error classification/redaction
- CharacterCard import description / no internal source-list presentation
- Prompt Inspector relocated hierarchy
- button styles + icons semantics
- dynamic Generate wording from existing cost state
- account cluster
- token progress calculation/render state
- adaptive result pane
- filmstrip selection/scroll
- attachment hover × / preview/remove
- empty attachment zero-height
- Session Settings tab state + dirty/save/cancel
- background immediate-save semantics survive tab refactor
- no runtime/request serialization diff

Then:
- Desktop full suite
- Desktop compile
- diff-check
- isolated distributable
- launch smoke

shared/Android tests only if production shared code changes; prefer none.

---

# 19. Completion

Report only:

`READY FOR PROJECT PHASE-7 FINAL PRODUCT REVIEW`

Do not claim Phase 7 ACCEPTED.

Project reviews source.

Then user performs `57`.

Only after user PASS:
- Phase 7 ACCEPTED
- final docs close
- FF-only merge feature → desktop
