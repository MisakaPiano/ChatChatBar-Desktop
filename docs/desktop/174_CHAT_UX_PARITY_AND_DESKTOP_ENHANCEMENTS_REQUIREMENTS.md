# 174 — Chat UX Parity and Desktop Character Interaction Requirements

**2026-10-23 JST — USER-REQUESTED REQUIREMENTS REGISTERED / IMPLEMENTATION NOT AUTHORIZED / DESIGN DETAILS PROPOSED.**

本文件只登记用户在本 Project 会话明确提出、后续要求“你来登记一下”的对话 UX 需求。用户已表示 CHAT-U1 的 Codex 实施任务“先不发，下一轮再说”。因此本次授权范围仅为 `docs/desktop/` 文档登记；不批准任何 Kotlin/Gradle/Prompt/数据 schema 修改，不启动 CHAT-U1/U2/U3/U4、自动格式修复、G2C 或真实档案读取。

## 1. 权威、来源与状态

- Git 登记起点：`99909d1a7a3ca59d0f8cd9bf725133577f4bef69`，Fork `feature/phase8-fish-audio`。Desktop 已接受分支在登记前为 `5ef95428421f7c693b010ac88f51d470804ab07a`；本文件不移动 `desktop`。
- **正式 validated upstream baseline**：ChatBar 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。本轮源审查又观察到上游 `SaltyFishOTL/ChatChatBar master @ 58023edf48f40764c481d1367f62bc048c8a5059`（当时默认为 1.4.7）；这是**只读观察，不是 baseline promotion 或同步授权**。
- 上游实际入口：`ui/chat/ChatViewModel.kt`、`ui/chat/ChatScreen.kt`、`domain/chat/RoleplayContentSegments.kt`、`ui/components/ChatBubble.kt`、`domain/chat/PromptAssembler.kt`、`data/local/entity/{CharacterCard,ChatSession,ChatMessage}.kt`、`.agents/skills/{chatbar-feature-map,chatbar-prompt-pipeline,chatbar-message-format-repair}/SKILL.md`。
- Fork Desktop 实际入口：`DesktopFullComposer.kt`、`DesktopPrimaryChatPanel.kt`、`DesktopPrimaryChatController.kt`、`DesktopRealChatRuntime.kt`、`DesktopChatRequestPlanner.kt`、`DesktopPrimaryChatPresentation.kt`、`DesktopMarkdownText.kt`、`DesktopMessageFormatRepairController.kt`、`DesktopAppContainer.kt`、`DesktopCharacterEditor{Controller,Panel}.kt`。
- 状态用语：**USER REQUEST REGISTERED** 是产品需求事实；下文“建议/候选”只供单独设计验收。所有 CHAT-Ux 均 **IMPLEMENTATION NOT AUTHORIZED / PENDING**。不将未来规划当作已有功能或已获批准的具体数据设计。

## 2. CHAT-U1 — 空输入点击发送 → 官方续写 + Desktop 防误触确认

**用户要求**：CCB 本体在没有新文字时允许直接点击发送以继续生成；Desktop 恢复相同行为，但空输入栏点击发送按钮后必须先确认，避免误操作。

**已核对的源码事实：**

- 官方 1.4.1 **及**观察到的 1.4.7 均在 `ChatViewModel.sendMessage` 中对 `content.isBlank() && imagePaths.isEmpty()` 走 `PromptTemplates.continueGenerationUserPrompt()`，设置 `persistUserMessage = false`、`resumeLatestUser = true`。官方 `ChatScreen.ChatComposer` 发送按钮不因文本为空而禁用。
- Desktop `DesktopRealChatRuntime.sendTextOperation` 的无内容无附件分支及 `DesktopChatRequestPlanner.planContinuation` 已存在；`DesktopPrimaryChatController.continueReply()` 也已存在。UI 的 `DesktopFullComposer.DesktopComposerInput.canSend()` 要求 `input.text.isNotBlank() || hasAttachments`，使普通/全屏发送按钮空栏不可点击。**底层续写与 UI 可发现/可触发性不可混为同一条已完成 parity。**

**验收目标（以后独立授权）：** 非空文字或有附件保持正常发送，不弹确认；空/空白且无附件的按钮点击弹“继续生成？”并说明不新增用户消息，明确确认后调用既有续写控制器，取消不发请求、不改草稿/历史；确认时重检会话、模型及任务准入，防双击/跨会话误发；正常停止按钮与 IME 行为不回归。空输入键盘快捷键是否启用属于设计细节（初步建议不让空栏 Ctrl+Enter 绕过确认）。**不得改现有官方 PromptTemplates 文本**；用最终序列化 API request 与 User Entity 增量证明语义。

**调度**：用户明确 CHAT-U1 Codex 任务**暂不发送**，下轮再讨论。这里的登记不能被 Codex 当作执行命令。

## 3. CHAT-U2 — 正确格式的行动选项，点击仅填入编辑框

**用户要求**：回复中“行动选项”在格式正确时，每个选项的文本可点击，点击后自动放入当前会话输入框；**不自动发送**。

**源码事实**：官方 `RoleplayContentSegments` 把三反引号状态栏及 `---` 包围的块都归类为 `STATUS`；官方 `ChatBubble.RoleplayMarkdownText` 关闭 Markdown 链接点击，Desktop `DesktopMarkdownText` 同样未生成链接点击行为。行动选项交互属于 **Desktop-enhancement**，不是假定已经有的 Android 功能。

**待设计/验收**：在共享分段语义之上增加仅针对可信、正确闭合且结构清晰的行动选项识别；不得把状态信息、普通链接、代码块或不规范内容误当作选项。点击对当前草稿作非破坏性插入/填入，保留用户既存编辑内容、光标/撤销/持久草稿一致性；不建立 ChatMessage、不发 Provider 请求。选项编号/标点剥离、已有草稿如何插入是待 UI 验收的候选细节，未冻结为最终设计。

## 4. CHAT-U3 — 聊天区左侧人物列表、最新状态与头像快捷操作

**用户要求**：聊天消息框外左侧展示**当前角色卡内部的人物列表**（不是会话列表）；每人头像和身份与卡内条目对应，从聊天回复中提取该人物最新状态栏信息；鼠标悬浮或单击可查看完整最新状态。聊天气泡内的人物头像应提供相同查看能力。两处均支持右键进入**当前人物对应的编辑位置**。

**源事实/建议边界**：

- 结构化角色卡内 `CharacterCard.characters: List<CharacterInfo>`，每人有 `id/name/appearanceImage`；Desktop 已用 `RoleplaySpeakerCandidate` 对气泡说话者做展示匹配。**姓名匹配/头像渲染不等于可靠身份凭据**。
- 显示建议：较宽窗口独立左侧人物轨道，窄窗口可折叠；鼠标悬浮为预览、单击为详细状态。布局属 Desktop-only，不更改 Android。
- 状态从当前会话**已持久保存**的回复和当前选中的 Assistant 替代版本中找最近且可判定归属的状态块；分段 `STATUS` 可能是行动选项，不能把所有状态块当人物状态。不存在、重名、多人混合或格式不确定时显示“未识别”，不伪造值；编辑/重生/换候选/修复后失效并刷新展示来源。不重写原消息或模型输出。
- 右键以 **card ID + CharacterInfo.id** 定位编辑器的具体人物字段；不能只打开整卡却假装已定位人物。应尊重已有编辑草稿、未保存更改及只读/冲突保护。

## 5. CHAT-U4 — 会话级人物启用/关闭，不更改角色卡源数据

**用户要求**：人物列表和聊天头像右键除“编辑人物”外还应有“启用/关闭人物”。关闭某人物后，不改变原角色卡内容，但该人物对应的设定条目**不要再每轮主动注入模型 Prompt**。

**已核对依据**：上游 `PromptAssembler.buildCharacterSection` 在 `STRUCTURED` 模式按 `CharacterInfo` 分段组装姓名/简介/外貌/服装/能力/习惯/背景/关系/口癖；`FREEFORM` 模式使用整块 `freeformCharacterText`。原生人物名字也进入分段发言格式规则。只在 UI 隐藏人物不能改变模型实际输入。

**待设计/测试门禁**：

- 推荐**会话级**启用覆盖，默认全人物启用；身份以 card ID + CharacterInfo.id 为基础，作用域、存储结构和 SaveSlot / full-app backup / 旧版兼容读取需先审查。禁止直接删改 `CharacterCard.characters`、持久 Entity、传输 Package 或历史消息。
- 结构化模式以一次请求的**临时有效人物视图**过滤对应 `CharacterInfo` 设定及每轮生成的 speaker-name roster；不修改官方已有模型 Prompt 文本。最终判断必须检查**已序列化 API messages / request JSON**，而非仅预览。
- 关闭不等于抹除历史剧情、禁止被提及或自动清理历史 Memory/RAG/WorldBook；但这些其他来源可能仍主动包含人物信息，因此在产品语义冻结前须审计官方 WorldBook 命中与内容、Context 历史、Memory/HEAD、RAG、FormatCard、mesExample、basicSetting、timed effects 等所有会暴露信息的通道；不得宣称能屏蔽全部提及。
- FREEFORM 不能安全地通过正则删除一个人的人物设定。第一版是否仅对 STRUCTURED 生效、FREEFORM 如何告知不可用/待支持，均需单独产品决定。
- 未来变更须通过 Prompt ownership、数据迁移前 snapshot、旧存档/无新字段默认行为、会话切换、卡内角色改名/删除、崩溃重启、备份/SaveSlot restore 与角色切换兼容测试。**不得为角色开关顺手修改官方 systemPrompt、格式卡 Prompt 或原文**。

## 6. FORMAT-PARITY — 分段显示 ≠ AI 格式修复全等

**已核对事实：** Desktop 使用共享 `parseRoleplayTextSegments`，对旁白/对白/心理/状态块及 speaker/avatar 的可视化已有受限 EQUIVALENT 记录。此项不等于完整的“格式化功能”。

原版 `ChatViewModel` 在开启 `automaticFormatCheckEnabled` 时会在回复保存后自动执行 `performMessageFormatRepair(automatic=true)`。Desktop 现有独立的手动修复、恢复原文、安全提交子集，但 `DesktopAppContainer` 的聊天完成回调未呈现同等自动修复管线；`13_FEATURE_PARITY.md` 中 **message format repair = PENDING**，保持不变。

后续对齐必须逐项覆盖格式规则选择、自动/手动触发、辅助模型请求、原文恢复、替代候选、停止/取消、错误/持久化不确定性、Memory/RAG/Moments 派生状态，以及最终 API 行为；不得以“状态栏渲染正常”关闭格式修复 parity。Prompt 修改仍需列原文/diff/原因/行为变化并获得用户单独同意。

## 7. 优先级与工程验收隔离

| Track | 性质 | 目前状态 | 推荐下一门禁 |
|---|---|---|---|
| CHAT-U1 | 官方空栏发送 UI parity + Desktop 安全确认 | **PENDING / DEFERRED BY USER** | 小范围 UI/续写合成测试；用户以后再授权 |
| CHAT-U2 | Desktop-only 行动选项草稿插入 | **PENDING / NOT AUTHORIZED** | 选项语法和草稿交互合同 |
| CHAT-U3 | Desktop-only 人物轨道/最新状态/头像右键 | **PENDING / NOT AUTHORIZED** | 角色身份与状态来源审查 |
| CHAT-U4 | Desktop-only 会话级人物启用覆写 | **PENDING / NOT AUTHORIZED** | 正式数据/Prompt/WorldBook/Memory 影响与兼容合同 |
| FORMAT-PARITY | 官方自动/手动 AI 格式修复 | **PENDING / NOT AUTHORIZED** | 生命周期与派生数据一致性专项审计 |

需求可以在后续独立 feature 分支分切片处理，但**本次不创建分支、不执行测试、不合并任何代码**。`G2C NOT AUTHORIZED`、P11 PENDING、D-038 BLOCKED、Post-P8 HOLD、Public Preview HOLD 和正式 baseline 1.4.1 全部保留。

**STOP: CHAT UX REQUIREMENTS REGISTERED / IMPLEMENTATION NOT AUTHORIZED.**
