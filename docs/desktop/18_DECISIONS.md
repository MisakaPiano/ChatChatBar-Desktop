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

## D-016：公开 Release 的授权依据

事实状态：upstream repository 尚未观察到标准 `LICENSE` 文件；repository license metadata 与项目授权依据是两个不同事项。

决定：upstream 作者已直接授权用户开发与发布 CCB Desktop。该直接授权是本项目继续开发与公开发行的授权依据，标准 `LICENSE` 元数据缺失本身不再构成 development / public-release blocker。

Release packaging 前应保留并确认原始授权记录，作为适当证据。不得据此虚构授权原文、日期、URL、截图或额外法律条款，也不得擅自添加或伪造 license file。

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

---

## D-018：ApplicationHome 与 Portable root authority

`ApplicationHome` 是 packaged application-image root，不是 current working directory，也不得从 `user.dir` 推导。packaged authority 由 project-owned JVM property `chatbar.desktop.applicationHome` 承载，并由 jpackage `$ROOTDIR` 在 launcher runtime 展开。

Portable 路径始终相对 `ApplicationHome` 解析，以保证完整 distribution 移动后仍可定位 `portable.flag` 与 `UserData/`。marker 一旦声明 Portable data-root authority，invalid / unusable Portable setup 必须 explicit failure，不能 silent fallback 到 bootstrap/default root。pure resolver 与 writable activation validation 保持职责分离。

---

## D-019：Quota 是调度约束，不是架构约束

Codex quota 可以作为拆分任务、延期执行或调整运行时机与验证强度的调度依据，但不能成为明知会削弱 data safety、compatibility、failure recovery、required verification 或 maintainability 的理由。

该原则不授权 speculative architecture 或 scope expansion。项目继续采用 minimum sufficient architecture：避免不必要的泛化，同时保留长期 upstream compatibility 与必要 extension seams。

---

## D-020：app-data consistency 使用 process-local maintenance-exclusive coordination

Desktop root consistency 使用 process-local maintenance-exclusive coordinator，而不是用一个 global mutex 串行每个操作。normal operations 保持并发；maintenance pending 后停止接纳新的 unrelated normal operations，等待已登记 operations drain，再授予 snapshot / restore / migration 所需的 exclusive maintenance。successful restore 与 incomplete rollback 必须 seal `RESTART_REQUIRED`，旧 container 不再恢复 admission。

coordinator 不提供 cross-process ownership。未来任何写入 selected `appDataRoot` 的 writer 都必须通过 shared `AppDataOperationGate` 或等价 coordinated adapter 参与。生命周期顺序固定为先 pause/stop scheduler 并等待 in-flight run，再申请 exclusive maintenance；禁止在持有 exclusive 时调用 `scheduler.stop()`，否则可能形成 admission deadlock。该边界只服务 CCB Desktop 的 root consistency，不扩展为 universal transaction framework。
