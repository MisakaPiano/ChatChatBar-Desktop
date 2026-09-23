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

---

## D-021：selected root 使用 cooperative cross-process single-writer ownership

Desktop 对每个 selected `appDataRoot` 建立 cooperative ownership，而不是强制整个 application 只能有一个 process。同一 root 只允许一个 writable CCB Desktop process，不同 roots 可以同时使用。首个 Windows 实现使用 JDK `FileChannel` + `FileLock`，并在整个 application lifetime 持有 `<appDataRoot>/.ccb-desktop.lock`。

lock file 是 persistent reserved infrastructure；文件存在本身不表示 active ownership，normal shutdown 不删除它。Windows 允许 held lock file 被 move/delete，因此 CCB 不能把 filesystem rename/delete denial 当作 ownership 正确性的依据，也不得在 ownership 存续期间主动移动或删除该 artifact。

FileLock ownership 与 process-local coordinator 是两个独立安全层。startup 顺序固定为 root authority resolution → ownership acquisition → data runtime；shutdown 先 drain/close runtime 与 coordinator，最后释放 ownership。snapshot / restore / migration 必须把 root lock artifact 当作 reserved infrastructure。该决策只服务 CCB Desktop 的 per-root safety，不扩展为 universal process-lock framework。

---

## D-022：固定基线 + 同步窗口 / Pinned Baseline + Sync Window

Desktop 开发以文档声明且完成 compatibility validation 的 formal baseline 为目标。observed upstream、相对 formal baseline 的 drift 与 sync urgency 必须分别记录；新的 upstream commit 不会自动使正在进行的 Desktop 工作失效，也不得被表述为已经兼容。

sync urgency 使用以下等级：

- `LOW`：docs、CI、presentation 或明确无关的变化。
- `NORMAL`：普通 upstream development / release changes，进入下一个自然 sync window。
- `HIGH`：Package、Entity、Prompt、WorldBook、Context、Provider、Storage、Memory、SaveSlot 或其他高风险语义变化；应提前安排 sync window，但不必自动中断与其无关的当前任务。
- `BLOCKING`：直接推翻当前 Desktop 任务假设、涉及 schema / data-loss / security-like 风险，或 Desktop 正准备基于已被不兼容合同取代的 baseline 发布；停止当前任务并交由 Project review。

Codex control point 应 fetch upstream，比较 formal baseline 与 observed upstream，并分类 drift。`LOW` / `NORMAL` 继续当前 milestone；`HIGH` 报告并排入同步窗口，除非当前工作确实受影响；只有 `BLOCKING` 才停止等待 Project 决策。ordinary upstream drift 不得自动阻塞 Desktop tasks。

自然同步窗口包括 major Desktop milestone 完成时、Alpha / Beta / Release 前、drift 累积到 reconciliation blast radius 不宜继续扩大时，以及 Project 主动触发的 high-risk sync。所有公开 compatibility claim 只绑定 formal validated baseline，绝不声称兼容 observed-but-unvalidated upstream。本策略只改变调度，不降低 parity、review 或 validation 标准。

---

## D-023：migration v1 只提交 bootstrap-controlled authority

migration v1 只支持 `MISSING_BOOTSTRAP_DEFAULT`、`BOOTSTRAP_DEFAULT` 与 `BOOTSTRAP_CUSTOM` source provenance；`PORTABLE` 与 `CLI_OVERRIDE` 不支持 persistent migration。bootstrap authority writers 通过 bootstrap directory sibling `ChatChatBarDesktop.bootstrap.lock` 序列化；该 lock 不等同于 `<appDataRoot>/.ccb-desktop.lock`，也不提供 application/root ownership。

authority transaction 必须在持锁期间 fresh-load bootstrap，重新验证 expected source authority 与 higher-priority Portable authority，从 fresh document 构造 `CUSTOM(destination)`，执行 atomic bootstrap save，并 read back 分类。结果必须区分 `Committed`、`Busy`、`AuthorityChanged`、`CommitFailedPreCommit` 与 `CommitIndeterminate`；bootstrap commit ambiguity 绝不能当作 rollback-safe。D1 建立 transaction foundation，D3 负责 migration core 的 authority commit 与 restart seal，Phase 2B4 final adapter 提供 user-facing root-switch action。

---

## D-024：migration materialization 与 root-authority commit 分离

D2 在 destination ownership 持有期间使用 destination-local staging，将 source active payload 按 raw bytes 复制，保留 unknown safe entries 与 empty directories；仅迁移 valid completed backup snapshots，并以 SHA-256 manifest/tree validation 验证 staging 与 installed destination。install 不覆盖既有 entry，使用 explicit installed-entry ledger；install / validate / rollback 位于 `NonCancellable` boundary。source 始终只读并保留，migration v1 不删除 old root。

`MATERIALIZATION_COMMITTED` 只表示 destination payload 已完成安装和验证，不是 bootstrap/root-authority commit；materialization failure 不能改变 authority。CCB migration workspace provenance 必须同时满足 recognized `.migration-*.tmp` name、marker `.ccb-desktop-migration-workspace` 与 exact token `CCB_DESKTOP_MIGRATION_WORKSPACE_V1`。名称本身不足以证明 infrastructure；缺少或损坏 marker 的 safe directory 按 ordinary source payload 迁移，绝不静默丢弃。D3 在此边界之上编排 pause/exclusive、mandatory safety snapshot、authority commit 与 restart seal。

---

## D-025：migration authority commit 与 restart seal 是不可分割边界

D3 固定顺序为 destination prepare → automatic-backup runtime pause / scheduler stop-join → coordinator exclusive → preflight → mandatory `MANUAL` safety snapshot → D2 materialization → bootstrap authority transaction → restart seal / runtime disposition。Safety snapshot 直接使用已由 exclusive 保护的 authoritative snapshot primitive，不能递归进入 coordinated facade；`SnapshotPurpose` 不新增 migration 值，以保持 format v1 enum compatibility。

Source ownership 由 application outer lifetime 持有；destination ownership 由 D1 prepared handle 持有。Authority `Committed` 或 `CommitIndeterminate` 的 classification、coordinator `requireRestart()`、service restart seal 与 destination ownership retention 必须位于同一 `NonCancellable` critical boundary；旧 source runtime 此后绝不 resume。`CommitIndeterminate` 不 rollback、不自动 retry，destination ownership 保持到 shutdown。

只有能证明 authority 未提交的 outcome 才释放 destination ownership并恢复 source runtime；已 materialize 的 destination 作为 validated non-authoritative copy 保留，不自动删除。Cancellation 在 pause 后必须稳定到 source resumed 或 restart-required。Shutdown 顺序固定为 runtime close → coordinator drain/close → migration service release destination ownership → outer lifecycle release source ownership。Migration success 始终保留 source，不实现 automatic old-root deletion。

---

## D-026：root-switch v1 是 copy + authority commit + restart boundary

User-facing root switch 只接受 bootstrap-controlled source provenance：`MISSING_BOOTSTRAP_DEFAULT`、`BOOTSTRAP_DEFAULT`、`BOOTSTRAP_CUSTOM`；`PORTABLE` 与 `CLI_OVERRIDE` 不支持 persistent switch。当前窄 Desktop adapter 使用 `JFileChooser.DIRECTORIES_ONLY`，但最终仍由 D1 destination validator 决定 identity、relation、local-path、ownership 与 emptiness；non-empty destination 必须拒绝且不得覆盖。

Root switch 不 hot-swap 当前 `DesktopAppContainer`。迁移成功后，当前 process 的 running/source root 仍是 startup source；bootstrap authority 指向 validated destination，controller 进入 terminal restart-required，用户退出并重新打开后才从 destination 启动。实现不自动 relaunch、不调用 `exitProcess`，window close 在 migration 中 defer；source 始终保留，不做 automatic deletion。

UI 只能在 materialization result 为 `Materialized` 时把 destination 称为 “Destination copy”；其他 retryable stage 只称 “Attempted destination”。Cancellation disposition 以既有 coordinator/runtime restart seal 为权威：pre-authority cancellation 且 source 已恢复时可回到 `Idle`；authority commit 附近若已 seal，则 controller 必须保持 terminal `RestartRequired`、不得重试或重新选目录，并在缺少 definite result 时保持 `nextStartRoot = null`，随后原样传播同一个 `CancellationException`。
