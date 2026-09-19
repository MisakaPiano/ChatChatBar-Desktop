# CCB Desktop Decisions

用来避免以后重复讨论已经确定的问题。

---

## D-001：Desktop 是 downstream port

决定：
不是重写一个“类似 CCB”的应用，而是官方 CCB 的 Windows 下游移植。

后果：
- 上游源码是语义真源。
- 功能追平必须可追踪。

---

## D-002：建立自己的 Fork，不修改官方仓库

`master` 作为 upstream mirror，Desktop 在 `desktop`。

---

## D-003：GitHub 是 Project 与 Codex 的共同事实源

不靠手工 ZIP 维持两份代码。

---

## D-004：Windows-first + Kotlin/JVM + Compose Desktop

不使用 Electron/WebView 作为核心。

---

## D-005：第一阶段不强制全项目 KMP

先使用 JVM shared library，减少上游侵入。

未来是否 KMP 另行评估。

---

## D-006：功能等价，不像素等价

Android 手机 UI 可以重构为桌面三栏。

核心 runtime 不可因 UI 改变。

---

## D-007：每项官方功能必须 EXACT 或 EQUIVALENT

Android 专属能力不静默删除。

无法可靠实现时必须 BLOCKED 并说明。

---

## D-008：Package / Entity / Prompt 三层分开

这是长期最高不变量之一。

---

## D-009：Prompt 最终 serialized messages/request 是真值

Preview 不能作为 parity 证据。

---

## D-010：保留官方 JSON storage 路线

MVP 不改 SQLite。

抽成 root-driven JVM storage。

---

## D-011：Desktop 增加数据安全能力

- 可见数据目录
- portable
- backup
- migration snapshot

这是 Desktop 增强，不改变 CCB Package 语义。

---

## D-012：不复制第二份 domain

优先抽 shared。

短期不得为“快速跑起来”复制 Prompt/WorldBook/Memory 后各自发展。

---

## D-013：NovelAI/Fish/RAG/Memory/Moments/Community 都属于 1.0 parity

它们可以分阶段实现，但不是永久删减项。

---

## D-014：QQ Voice 单独平台 feasibility

Android Accessibility 实现不直接移植。

不能用脆弱坐标自动化假装 EXACT。

---

## D-015：上游 baseline 可验证

每个 Desktop Release 明确记录：
- Desktop version
- upstream version
- upstream commit
- schema versions

---

## D-016：公开 Release 前设 License Gate

当前 upstream 未检测到 license。

私人开发/技术工作继续；公开分发前重新确认许可。

---

## D-017：官方 Prompt 随 upstream 原样同步

CCB Desktop 是长期 downstream。官方 upstream 对 Prompt 文本和运行语义的变更原则上原样吸收。

后果：
- 每次 Prompt 变化仍必须执行高风险 impact review，并验证最终 logical messages、serialized transport、parity 与相关测试。
- 不因 Desktop 移植保留旧 Prompt，也不维护第二套 Desktop Prompt。
- 本决定不授权 Desktop 自行重写官方 Prompt。
- downstream 自行提出的 Prompt 修改、merge conflict 中改变 Prompt、或故意偏离 upstream 时，必须单独说明原文、diff、原因和预期行为变化，并取得用户明确同意。

1.3.49 已确认语义：
- `systemPrompt` = fixed prefix + replaceable middle + fixed suffix。
- `{{original}}` = default middle。
