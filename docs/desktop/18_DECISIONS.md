# CCB Desktop Decisions

**CURRENT USER G2A ENGINEERING AUTHORIZATION / 2026-10-23 (no new D-number):** [165](165_P11_S2_G2A_USER_AUTHORIZATION_RECEIPT.md) records explicit approval of **bounded new nonwired Kotlin/JVM `src/main` credential import plan/intent validation core**; [166](166_P11_S2_G2A_AUTHORIZED_TYPED_CREDENTIAL_PLAN_CODEX_TASK.md) is the executable Codex contract. This approves **only** typed classification/conflict/phase checking and synthetic tests, not data+SecretStore commit, removal of current credential guards, UI/Startup changes, live Moments seal, C2 advanced UX, real key/root testing or a release. User offered to provide real data on specific request, but no source/password/path or permission to read any existing file was given; see [167](167_P11_REAL_DATA_INTEROP_READONLY_PREPARATION_NOT_AUTHORIZED.md). [164](164_P11_S2_G2A_BOUNDED_IMPLEMENTATION_CODEX_CONTRACT_DRAFT_NOT_AUTHORIZED.md) remains an earlier draft, not the current authorization. R01/R02/R03/R07 directions and D-037/D-038/P11/Public Preview holds continue.

**CURRENT R07 USER DECISION / 2026-10-23 (no new D-number):** [158](158_P11_S2_G1_R07_DIRECT_IMPORT_PASSWORD_AND_CREDENTIAL_POLICY.md) records original `.cbbackup` custody A (user keeps file; no automatic copy) and direct secure import of supported data **and user-owned API Key/Token**. Mere key presence must not block the whole backup. Mature C2 multi-account transfer is future; default C1 stripping is not equivalent. Current `CREDENTIAL_APPROVAL_REQUIRED` is old production behavior until secure DPAPI/SecretStore identity mapping and coordinated data+secret rollback pass acceptance; do not simply remove checks. Ordinary shareable Desktop export remains without keys by default; password-encrypted credential-bearing export is distinct design scope. D-037/D-038/Preview/baseline/S2 holds unchanged.

**CURRENT 2026-10-23 / P11-S2-G1 PARTIAL USER DECISION (no new D-number):** [157_P11_S2_G1_USER_CONFIRMED_PARTIAL_POLICY_DIRECTIONS.md](157_P11_S2_G1_USER_CONFIRMED_PARTIAL_POLICY_DIRECTIONS.md) registers user-confirmed policy directions for R01/R04 C-A non-running Moment archival, R02 original JSON+independent media/no auto-delete/insufficient-space refusal, and R03 no unsafe old-binary downgrade writes. **R07 remains under user discussion**; S2 implementation, quota/retention details, legacy write-barrier verification, C1/C2, full D-038 and Preview Gate are not authorized by this partial decision. D-037/D-038/D-041 remain unchanged.

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

调度澄清：watch trigger 不自动等于 sync trigger。`HIGH` 必须立即做 Project impact audit；若不影响当前或下一 slice 的合同假设，可跨多个 milestone 保留在 compatibility backlog，并在后续 batch sync 一次吸收多个 commits / patch releases。实际 sync trigger 是 dependency impact、持续扩大的 reconciliation blast radius、release gate 或 `BLOCKING` drift；仅 commit lag 不构成强制同步理由。`BLOCKING` 仍是唯一默认 stop-work urgency。

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

---

## D-027：Desktop owned resource reference 使用 app-data root-relative 表示

CharacterCard、CharacterInfo、DocumentInfo 等 Desktop persisted Entity 中，属于 CCB Desktop 自己管理的图片、文档等资源引用，不使用绑定某一次 data-root 的 absolute filesystem path 作为长期持久化表示。

Desktop 使用 app-data root-relative owned resource reference，例如：

- `images/...`
- `documents/...`

实际文件访问通过 authoritative current `appDataRoot` + resource resolver 解析。

原因：

- absolute path 会使 Phase 2 root switch 在 raw-byte copy 后继续引用旧 source root；
- 会形成 split-root ownership；
- 会使旧 root retention 从 safety fallback 退化为长期运行依赖；
- 会破坏 Portable / whole-application relocation；
- 会使未来安全的 old-root cleanup 无法实现。

Android 当前 absolute-path persistence 不要求因此修改；平台差异通过窄 resource adapter / resolver 隔离。

`asset:` 之类 bundled logical resource identifier 属于独立 resolver path，不得解释为普通 Windows absolute path。

该决定不改变 Transfer Package resource ID contract。

---

## D-028：Character transfer 的 Prompt dependency 不复制 Prompt 文本

状态：**RESOLVED / IMPLEMENTED**（`f722703c33d8cd96728fc06ff617c9d7d79d9c7d`）。

Phase 3 Character transfer 所需的：

- `defaultCharacterNaiNegativePrompt()`
- `effectiveCharacterNaiNegativePrompt(value)`

等 Prompt-owned semantics，不允许通过在 Desktop/sharedCore 中复制官方 Prompt 文本或重新实现“近似逻辑”来解除依赖。

Phase 3 shared transfer core 通过窄 Prompt-owned policy / interface 依赖该语义。authoritative source 位于 sharedCore `domain/prompt/CharacterNaiPromptDefaults.kt`，shared production 使用 `AuthoritativeCharacterTransferPromptPolicy`；Android `PromptTemplates` 保留既有 public facade symbols 并委托同一 shared authority。Android 与 Desktop 因而不维护第二套 Prompt 文本或近似逻辑。

本次 closure 未重写 official Prompt literal，未改变 Prompt behavior，也未触及 `PromptAssembler`、`ContextWindowManager` 或 final message ordering。

禁止：

- 复制 `DEFAULT_CHARACTER_NAI_NEGATIVE_PROMPT`
- 自行重写 default negative prompt
- 简化 `effectiveCharacterNaiNegativePrompt`
- 为解除依赖而修改官方 Prompt 文本
- 顺手改变 SillyTavern mapper 当前 schema contract

目标是保持一个 authoritative Prompt source，而不是为了阶段边界制造 Prompt fork。

---

## D-029：成功语义保持 parity，destructive failure path 允许窄 data-safety hardening

当 upstream 正常成功路径与 Package / Entity observable semantics 明确时，Desktop 继续以 EXACT parity 为目标。

但如果 upstream failure ordering 存在明确的数据损坏风险，Desktop 不要求为了 bug-for-bug parity 复制 destructive failure behavior。

允许的 downstream divergence 必须满足：

- 正常成功结果与 upstream 保持一致；
- Package/schema/Entity 成功语义不改变；
- hardening 仅针对 destructive / partial failure recovery；
- 不借机重写 domain semantics；
- 明确记录为 downstream safety divergence；
- 向 upstream 报告对应问题；
- upstream 修复后优先收敛回共享/统一实现，避免永久维护第二套 guard。

当前 Phase 3 已识别的案例包括：

1. Character import / overwrite 失败时，先前已持久化的新 WorldBook / FormatCard 可能成为 orphan；
2. `deleteCard()` 当前先删除 owned resources / RAG，再删除 Character Entity；后续删除失败可能留下仍存在但资源已丢失的 Entity。

项目优先级在此类冲突中固定为：

**data safety / recoverability > schedule / quota > bug-for-bug failure parity**

该决定不授权对普通成功路径做“更合理”的 downstream redesign。

---

## D-030：Main-chat Prompt authority shared closure

状态：**APPROVED / IMPLEMENTED**（2026-09-26 用户明确批准；4P implementation `e927277dabb206aa34b2374e3596c67a31f0f7db` 已通过 Project review 并集成）。

Phase 4 的 main-chat Prompt literals/builders 允许进行一次窄的 physical ownership move：把 4P / 4C 所必需的主聊天 Prompt authority 从 Android `PromptTemplates.kt` 移入 sharedCore，使 Android 与 Desktop 消费同一 authoritative source。

批准范围严格限定为：

- Prompt 文本 character-for-character 不改；
- Prompt runtime behavior 不改；
- Android `PromptTemplates` 保留既有 public symbols / facade，并委托 shared authority；
- Desktop 与后续 shared Prompt/logical assembler 使用同一 authority；
- 只移动 Phase 4 main-chat pipeline 实际需要的 Prompt literals/builders；
- Character AI、WorldBook AI、NovelAI/image、memory maintenance、message repair 等非 Phase 4 Prompt families 不顺手迁移；
- 不借 4P 修改 `PromptAssembler`、final logical message order、Provider/network/SSE、Package/schema 或 persisted chat contract。

该批准**不等于 upstream sync**。formal baseline 仍为 ChatChatBar 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`；observed upstream `354f15166d8bc0462cb87d62a0ba4613794560a3` 的 Prompt drift 继续保持 HIGH / NO SYNC，4P 不吸收该 drift。

4P 完成并通过 Project review 后，4C 才可开始共享 PromptAssembler / logical request assembly。

---

## D-031：Desktop Secure Model Credential Boundary

状态：**APPROVED / IMPLEMENTED / INTEGRATED**。

- upstream/runtime `ModelConfig` semantics 继续 authoritative；本决定不改变当前 `ModelConfig.apiKey` runtime contract。
- Desktop 不得把实际 API keys/tokens 作为 ordinary plaintext JSON 持久化。
- 后续 Desktop 使用 secure credential persistence boundary / `DesktopSecretStore`；Windows implementation 必须使用 OS protection。
- runtime 可以接收 hydrated `ModelConfig.apiKey`，以保持 resolver/provider semantics 与 upstream compatible。
- SecretStore unavailable 时不得 silently fall back 到 plaintext persistence。
- P5-S1 没有实现 SecretStore，也没有新增 Desktop plaintext-secret production persistence。

实现记录：Windows protected credential store foundation 始于 `2a2b54f05f12053f87acb17372ae8eda6884bb8b`；model/settings secure-persistence integration 与 embedding credential closure 经 reviewed Phase-5 chain 至 `fec997a4c3d90ea55515734cea96b89fa8b97059`。不允许 plaintext fallback；D-031 仍为 governing contract。

---

## D-032：Shared Provider Transport Authority Without Auxiliary Prompt Migration

状态：**APPROVED / IMPLEMENTED / INTEGRATED**。

- Phase 5 建立一个 JVM-shared provider transport / serialization / SSE authority。
- Android 可以为 Android/non-main-chat callers 保留 thin facade。
- 不得仅为移动 `StreamingChatService` 而迁移或复制 auxiliary Prompt families。
- main-chat transport 消费 Phase 4 authoritative shared logical messages。
- provider-specific serialization 可以调整 transport representation，但不得修改 logical Prompt authority、message order 或 cache authority。
- Prompt literals 继续受保护；任何既有 Prompt text change 仍需单独明确批准。

实现记录：shared provider authority 于 `244ea66b276f3824f06412d02c5a75a56f97b6de` 建立，construction/boundary hardening 于 `44f42702f646dfe5491b5f398b6e52328818bce4` 完成；Phase-5 vertical acceptance 于 `c6a3805698faceb8ed7e7ce36af6f49263b7b517` 完成。Auxiliary Prompt families 未仅为 transport 迁移；D-032 仍禁止 transport 成为第二套 logical Prompt authority。

---

## D-033：Phase 5 Alpha Is a Text Real-Chat Vertical

状态：**APPROVED AS PHASE 5 CONTRACT**。

Phase 5 Alpha text-chat scope 包含：

- normal text send；
- blank continue；
- streaming content/reasoning；
- user stop/cancellation；
- model resolution/auth；
- local HTTP behavior；
- thinking controls；
- assistant persistence；
- restart and continue；
- required Desktop task lifetime。

明确排除：

- RAG/Embedding feature implementation（current request semantics 所必需的 dependency 除外）；
- Long-Term Memory feature implementation；
- format repair / AI authoring；
- NovelAI/image；
- Fish/audio；
- full Phase 6 model/chat production UX。

---

## D-034：Logical Model ID Is Separate From Desktop Physical Storage Key

状态：**APPROVED / IMPLEMENTED / INTEGRATED**（implementation `dd68b714c58254b7ef994b106dafebf649623bf6`）。

- upstream logical `ModelConfig.id` 保持 authoritative 且不变；serialized Entity 继续写 logical ID。
- `PRESET_MODEL_ID_PREFIX` 保持 `preset:`；Android persistence 保留 upstream identity filename behavior。
- Windows 不能在 filename 中直接使用 `:`，因此 Desktop 使用窄 `ModelRepository` storage-key adapter。
- current Desktop physical key format：`ccb-model-v1-<lowercase UTF-8 hex>`。
- logical `preset:vision` physical mapping：`ccb-model-v1-7072657365743a766973696f6e`。
- encoded physical key 不得成为 Entity ID、Package ID、settings ID、relationship ID、UI ID、Prompt value 或 provider identifier。
- `visionModelId` 与其他 references/relationships 继续使用 logical IDs。
- global `JsonFileStorage` filename behavior 保持不变。
- P5-S1 前不存在 Desktop model persistence，因此无需 Desktop user-data migration。
- 本决定不引入 raw Android app-data-root → Desktop filesystem migration。
- snapshots/root migration 把 Desktop physical files 作为普通 raw payload 保留。
- 这是 platform **EQUIVALENT** persistence representation，不是 Model semantic divergence。

---

## D-035：Cross-device Full Data Portability / BYOC Sync

状态：**APPROVED DESIGN DIRECTION / NOT IMPLEMENTED**（Phase 6 finalization，2026-10-05）。

仅冻结以下原则，不冻结 archive schema、协议版本或最终 phase/slice number：

- Android ↔ Desktop ↔ Desktop/Android 的完整数据可携带性。
- 优先 shared/upstream protocol，不建立 Desktop-private schema。
- 首步为 complete archive export / validate / transactional import；不是 live shared data-root synchronization。
- 优先 user-owned transport；Desktop local/sync folder 与 Android SAF first。
- cloud client 可独立同步 archive files；不需要 developer-operated server。
- ordinary archive 排除 API keys / tokens / secrets。
- 优先 committed immutable archive exchange；automatic multi-master merge 为后续独立问题。
- 后续数据域，特别 SaveSlot，成熟前不冻结 formal schema；约 P11 后重访架构。
- 最终 phase/slice number intentionally unfrozen；本轮无实现授权。

Phase 6 分类补充：Automatic Backup runtime/settings 为 Desktop-only data-safety enhancement，不是 Android parity；Virtual Conversation Scrollbar 待 realistic long/cross-device histories 后评估，不是 Phase 6 defect。P15 持有 AI request-log user surface 与 OS integration；P17 持有 Tutorial/onboarding、beta hardening 与 Backup/Recovery Center。


---

## D-036：Narrow official-upstream Prompt safety forward-port

状态：**PROJECT APPROVED / IMPLEMENTED**（P7, `bc2571e`）。

仅提前采用 upstream `ace632cce58a3b5a57711e31990165d6a14e1c0f` 对 GENERAL_CONTRACT_CONFIRMATION_ASSISTANT_PROMPT、CCB_CONTRACT_CONFIRMATION_ASSISTANT_PROMPT 和 CCB_CONTEXT_APPROVAL_ASSISTANT_PROMPT 的三个精确修改。当前 shared main-chat authority / Android auxiliary owner 与 facade 保持不变；不记录或重复原不安全句子。三 literal 不再声称与 1.4.1 byte-identical。此例外解决 formal-baseline auxiliary path 的直接 Prompt blocker，不提升 validated baseline，不吸收其他 1.4.2–1.4.4 drift，不触碰 parked sync。细节见 `14_UPSTREAM_COMPAT.md` 与 `38_PHASE7_FINAL_REVIEW.md`。

---

## D-037：P11 后 Public Preview 与 production-valued user data

状态：**APPROVED PROGRAM POLICY / NOT YET REACHED**（2026-10-12，用户明确同意）。

决定：

- 第一个面向普通玩家的公开预发布版本不等待 P17/P18；在 **P11 SaveSlot / Session Lifecycle CLOSED / ACCEPTED** 后，先执行独立的 **Public Preview Data-Safety Gate**。
- Gate 通过后允许发布 CCB Desktop Public Preview，并开始系统性积累外部玩家反馈；P12–P16 在 Preview 期间继续开发。
- P17 定位为基于真实 Preview 反馈的 **Public Beta hardening**；P18 仍是 1.0 parity/release gate。
- 从第一个 Public Preview 开始，用户持久化数据视为 **production-valued data**。不得因为仍是 Preview/Beta 而在普通升级中要求例行清空数据目录。
- 任何 persisted Entity / resource / SaveSlot representation 变化，都必须优先提供兼容读取、迁移或可验证的保全/恢复路径；若无法安全迁移，应阻止该版本公开发布，而不是自动清档。
- Public Preview **不等于 1.0 parity**。P12–P17 的既有 owner 不因 Preview 自动改为完成；未完成功能和已知限制必须明确披露。
- Preview Gate 只前置公开使用所必需的窄安全子集：installer/manual upgrade 数据保留、backup/restore/corrupt-data/crash-restart/cancel evidence、单对话 `.cbsave` Android ↔ Desktop round-trip、secret exclusion 与最小 diagnostics/support evidence。完整 auto-updater/OS shell integration 仍归 P15；完整 Backup-Recovery Center、Win10/11/IME/large-data/network 等 hardening 仍归 P17。
- D-035 的 full-data portability / BYOC sync 方向不被替代。Public Preview blocker 是单对话 SaveSlot portability 与本机数据安全，不要求先完成 live multi-master 全局同步。
- Public Preview 前必须重新评估 latest upstream drift；兼容声明仍只绑定当时正式 validated baseline，不能因公开 Preview 自动提升 baseline。
- public Preview packaging 同样受 D-016 授权证据约束；公开预发布不绕过作者授权记录要求。

详细 gate、证据分类与允许 deferred 范围见 `93_PUBLIC_PREVIEW_POLICY.md`。

---

## D-038：Public Preview 前必须支持官方 1.4.5 全量应用迁移存档

状态：**APPROVED PROGRAM CONTROL / P11 OWNER / PUBLIC PREVIEW BLOCKER / NOT IMPLEMENTED**（2026-10-12，用户明确同意）。

决定：

- 作为 **D-037 的追加增强**，在 P11 SaveSlot / Session Lifecycle 的实施范围中加入官方 ChatBar **v1.4.5 @ `1198a13cf3008de1358b8a69aa5e40b64797c8d9` 全应用 `.cbbackup`** 可兼容导入/导出、校验、可恢复事务和独立数据根迁移能力；不能只凭单对话 `.cbsave` 通过首次 Public Preview Gate。
- P11 在技术上完成 full-app archive export / inspect / transactional import / rollback；Public Preview Data-Safety Gate 必须有 Android 1.4.5 → Desktop 真实迁移、Desktop → Desktop 完整恢复，并验证 Desktop 输出的支持范围能够被 Android 1.4.5 接收。每项必须区分自动化与真人/真机证据；不能将未支持数据静默丢弃后仍称“全量”。
- 正式选择的 full-app archive 协议以 **官方 1.4.5** 为先例，并需对照精确数据结构/算法和测试。这个**选择性兼容目标不代表正式 validated upstream baseline 从 1.4.1 提升**，也不许可直接同步 `master`、`sync/*` 或改写官方 Prompt。
- 与 D-035 保持分层：完整应用**档案迁移**前置；云端存储、live shared root、自动 multi-master 双向同步不前置。与 D-037 保持分层：单会话 `.cbsave` 仍需独立通过，且 P11 CLOSED / ACCEPTED 以后才可启动独立 Public Preview Data-Safety Gate。
- 保留官方登录/凭据相关字段的格式识别能力，但 **普通可分享的 Desktop 备份/导出默认不得携带 Secrets**；在没有单独批准的 encrypted credential-transfer 设计前，不得静默导入、导出或记录 Fish/NovelAI/OAuth 等私密凭据。官方 1.4.5 存在无密码导出兼容模式，不等于 Desktop 可以直接继承该不安全默认。对带 Secrets 输入应明确区分可读、可保留、可导入与需用户确认/拒绝的状态；若完整迁移无法安全实现则 Preview 继续 BLOCKED。
- P8、P9、P10 必须为 P11 提供其 voice/RAG/memory owned-media、持久化引用和迁移契约的交接；**不提前在 P8 重写全应用备份系统**。P17 仍拥有完整 Backup-Recovery Center/beta UX hardening；P18 仍为 1.0 parity/release Gate。
- 现行 D-037 / `93_PUBLIC_PREVIEW_POLICY.md` 的数据保护承诺始终有效；后续普通 Preview/Beta 升级不得要求常规清档，也不得把 loss/corrupt/unknown data 静默忽略。

详细数据合同、风险与 Preview 阻断验收项以 `95_PREVIEW_FULL_APP_BACKUP_PORTABILITY_GATE.md` 为准。此决定不修改当前数据、schema、代码或 P7 验收记录。

---

## D-039：Desktop F4-B3 ERROR 恢复点保护策略 B

状态：**USER APPROVED / PROJECT FROZEN / NOT IMPLEMENTED**。

用户已批准：AI 消息格式修复失败或失败后的重试，不得摧毁仍适用于当前候选正文的有效恢复点。这是 Desktop 有意的数据安全增强，不宣称与官方现有 ERROR 覆盖行为完全等价，也不改变正式 1.4.1 基线或 D-036 Prompt 例外。

本次 F4-B3A 只提供完整快照与候选身份校验后的 repository 条件更新基础能力；ERROR/STOPPED coordinator、恢复点策略 B、用户 Stop 与 UI 尚未实施，留待独立 F4-B3B 合同与审查。官方共享 MessageFormatRepairPolicy 的纯语义保持不变。

### 实施状态补记（2026-10-14）

以上 `NOT IMPLEMENTED` 状态及 B3A 说明保留为原决定时的历史记录。Project 已独立审查 F4-B3B R1 源码 `e1099f91b13068852eb60045c99312929695fc53`、证据 `c78bc8c350a0afd66ecae3c56ac20e27a824908e` 和七份原始 JUnit XML，裁定 **F4-B3B R1 QUALIFIED ENGINEERING ACCEPTED**。六文件 +1025/-4；八阶段退出码全部为 0，7 suites/104 tests PASS，0 failures/errors/skips。D-039 策略 B 已在无新增 UI 的 Desktop 修复生命周期中实施并获得限定工程验收：模型配置或传输失败及失败重试保留仍适用的 APPLIED/STOPPED 恢复点；无适用旧恢复点时使用 notice-only CAS 提交 ERROR notice，独立报告存储失败、冲突和提交不确定。

该增强仍是 Desktop 有意的数据安全决定，不宣称与官方 ERROR 覆盖语义完全等价。生产派生门禁默认 UNKNOWN → REJECTED，阻塞 I/O 无物理绝对超时保证；UI、自动修复和完整用户可用性仍 PENDING。Windows GUI 人工、全量回归、打包及实际付费 Provider 均 NOT RUN。完整验收边界见 `115_F4_B3B_R1_PROJECT_ACCEPTANCE.md`；不改写候选证据、不提升 `13` 完整 parity，不扩展 Fish/P9/P10/P11/Public Preview 状态，也不改变 1.4.1 baseline 或 D-036 Prompt。

---

## D-040：Existing Session Resource Rebinding

状态：**USER APPROVED / P11 OWNER / NOT IMPLEMENTED**（2026-10-15）。

用户批准将已有会话资源重新绑定作为 P11 的独立功能项；本次只登记产品方向，不授权实现或迁移数据。

- 支持已有会话从旧 CharacterCard ID 切换到新版 CharacterCard ID，即使旧卡仍存在也可明确选择切换。区分原 ID 的覆盖更新与新 ID 导入，不将二者当作同一种迁移。
- 支持会话附加 WorldBook 的重新绑定；对角色卡继承 WorldBook 的独立覆盖，须先审查数据合同，不直接假设 `extraWorldBookIds` 已足够。
- 保持 session ID、聊天正文、alternatives、source-turn、历史资源和存档；默认单会话操作，要求明确确认、迁移前快照、严格资源身份校验及可验证的失败恢复。
- 新角色卡的默认格式卡不得静默覆盖原会话设置。P11 实施前须评估 WorldBook `timedWorldInfo` 的保留/失效规则，以及资源重新绑定对历史气泡实时渲染的影响。
- SaveSlot 中角色卡资源绑定与恢复语义交由 P11 正式审计，不据此擅自修改 Package 或持久化合同。

本决定不修改 Prompt 文本或正式 validated upstream 1.4.1 baseline，不改变 D-036/D-039；F4-B5B、P8 Fish、P9/P10、P11 及 Public Preview Gate 的既有验收状态均不提升。功能实施与验收需后续独立合同和 Project 审查。

---

## D-041：首次 Public Preview 应用内检查更新与安全手动升级

状态：**USER APPROVED / PREVIEW GATE B NEW BLOCKER / NOT IMPLEMENTED**（2026-10-20 正式登记用户既有同意）。

第一版公开 Preview 前提供应用内手动版本检查、新版通知/版本号/发布时间/更新说明、可信官方 Desktop 下载页外部浏览器跳转，并能识别 Preview/prerelease 与 stable release 通道。比较的是 Desktop build/version identity，不得错用 Android upstream 版本。离线、非法/不可信元数据或版本无法验证时 fail closed，不能假称更新成功或阻碍原程序使用。可信发行源及 release URL/asset 身份在实施时冻结并验证，不臆造链接。

普通 Windows 用户可安装 EXE/MSI、干净安装和 Preview N→N+1 保留选中数据目录、设置、SecretStore 凭据、语音/媒体和 SaveSlot 的覆盖升级证据必须通过；卸载不会默认销毁 production-valued 数据。无 full automatic updater 时仍可手动安装，但仅有外部下载页、应用内没有最低检查能力已**不再足够通过 Gate B**。

P15 仍持有完整应用内自动下载安装、下载真实性/校验、退出旧进程、启动安装器、重启/恢复以及 OS 关联；D-037/D-038、P11 前置与 Preview 数据安全承诺不变。不得在实现前提升 parity 或宣称 Preview Gate PASS。详见 `93_PUBLIC_PREVIEW_POLICY.md`。

**2026-10-22 跨项目参考（仅审查来源，不是 CCB Desktop 实施或 Gate PASS）：** [CCB Studio Suite Windows 安装与更新参考](https://github.com/MisakaPiano/CCB-Studio-Suite/blob/14f32b32046206ff40843b9d214c24e33e151848/docs/rc/WINDOWS_UPDATE_REFERENCE.md)；固定实现源码 [`271eef55fcd7d3421f3cd93eb227f8d54889e78d`](https://github.com/MisakaPiano/CCB-Studio-Suite/tree/271eef55fcd7d3421f3cd93eb227f8d54889e78d)。

- Suite 已以**隔离合成资料**验证真实 GitHub Release 下载、测试 Preview **100 → 101** 安装/退出/重启、新版启动和再次检查 `UP_TO_DATE`；10 条合成资产、工作区状态和安装清单核对一致。该跨项目证据不替代 Desktop 自身的 Windows 安装升级、凭据保全或 Preview 验收。
- 值得借鉴的是程序目录与用户资料目录分离、TUF 签名元数据/固定 GitHub 发行源与下载 hash、退出并排空写入后由外部辅助进程安装、安装前后清单校验、新版启动回执绑定版本/nonce/数据快照，以及失败保留旧程序和用户资料。实现必须适配 Desktop 的 Kotlin/JVM、Compose、SecretStore 和数据根事务，不能整套照搬 Suite Python runtime。
- **安全限制**：该测试使用一次性签名根，长期密钥托管、备份和续签尚未完成；测试凭据在升级前已缺失，不能证明有效凭据的升级保全；未完成 Windows 发布者代码签名或长期发行验收。不得复用 Suite 的签名密钥、信任根、产品/仓库/频道身份和发布 URL。
- **D-041 范围不变**：首个 Desktop Public Preview 仍只强制应用内可信手动检查/版本说明/浏览器打开下载页及 Windows 安全手动升级的真实数据保留证据；完整自动下载、安装与重启继续归 P15，须另行决定和验收。不提升 P11 / Preview Gate / parity。


---

## D-042：Token Budget & Usage Analytics（后期增强）

状态：**USER APPROVED FEATURE PLAN / PENDING / NOT FIRST PREVIEW BLOCKER**（2026-10-20）。

P12 附近在 CharacterCard、FormatCard、WorldBook 各字段/条目显示可解释的**估算 Token 预算**，并展示目标模型上下文预算、当前 WorldBook 激活、变量替换、记忆/RAG/历史截断等按请求实际构造层的占用；不能把 Package 字段文本、内部 Entity 或 Preview 误当最终 Prompt。Prompt 真值取**最终序列化 API messages/request**，官方 Prompt ownership 与原文不为计数而修改。

P15 的 AI request-log surface 增设独立统计面板，按 provider/model/session/request/time（天/周/月）、请求类型分组实际输入/输出/缓存/重试用量；使用供应商报告的 usage（现有 `OpenAiSseParser`/`ProviderStreamEvent.Usage` 可复用），缺失标记为 UNREPORTED，估算值与实测值明确分隔，不能记成0或虚构计费。P17 验证不同 tokenizer/provider、缓存与多模态精度。默认只持久化必要聚合/请求元数据，不默认存完整 Prompt/chat body/Secrets。Fish TTS 音频时长/字符与 LLM Token 不混算。不阻塞首个 Preview，另行实现和验收。

---

## D-043：P8 后续聊天上下文菜单、语音播放器及截图补缺

状态：**USER REQUIREMENTS FROZEN FOR NEXT UX IMPLEMENTATION / NOT IMPLEMENTED**（2026-10-20）。

权威需求见 [145](145_CHAT_CONTEXT_VOICE_UX_AND_SCREENSHOT_CONTRACT.md)：右键消息正文和该消息底部左对齐「…」共享三行紧凑图标+文字菜单（行1 回复级重新生成/修复格式；行2 本段复制/编辑/删除/截图/生成语音；行3 整条复制/编辑/删除/截图/生成语音；行内不换行；非最新合法回复禁重新生成；点击外部/Esc 关闭）。

消息底部必须**左侧 [复制][重新生成][…][喇叭]、右侧 [生成图片][生图要求]**；喇叭是**左侧组最右**，不是整个工具栏的最右。没录音默认色触发整条生成，有录音主题色从该条第一条有效语音播放。两个生图按钮恢复右对齐，User 头像/名称/气泡对齐右侧。

对话视口内最下端单行低高度生成 Deck；有效听书开时「角色音色」标签与 dropdown 同一行右侧，关时隐藏常驻选择而保留官方按需旁白选择。播放状态为底部中央不占布局的浮窗，首次停止暂停保存真位置，再播放原位继续，再停止关闭；跨 User 发言依次播放后续 Assistant 已有音频（不播放 User、不自动 TTS），需新的 Windows pause/position 合同及安全队列；永久提示改为有明确生命周期的可关闭通知。

官方 Android 现有 chat segment/full-message/multi-select 长截图 Desktop 漏项单列 **CHAT-LONG-SCREENSHOT-001 / EQUIVALENT target / PENDING**，与本次 UI 共用菜单入口但分域测试/验收。上述新增 UI/播放增强未纳入冻结 R3 包，不修改官方 Prompt/Entity/Package；保留 QQ BLOCKED、Studio 异常处理 OPEN、P11/Preview Gate。

---

## D-044：多 API 账户档案与真实 Provider 身份展示

状态：**USER REQUESTED / REQUIREMENTS CAPTURED / DESIGN AUDIT PENDING / NOT IMPLEMENTED**（2026-10-20）。

用户明确提出：LLM、NovelAI 生图、Fish Audio 分别支持保存并快速切换**多个 API/Token**，每个账号可自定义别名（备注账号名、主号/副号等）；**LLM 在调用入口及运行中明确展示本次实际服务平台**。同一平台也可能有多个独立账号，协议模板 OpenAI-compatible 不等于 OpenAI 平台。

候选实现需将平台/账户身份、模型配置、Endpoint 和受 Windows SecretStore 保护的真实凭据分离；当前 LLM 已有逐模型 Key，NovelAI/Fish 主要采用 singleton secret 槽。账户选择、模型/会话绑定、已有 fallback 优先级、切换中的请求身份冻结、跨账号禁止隐式轮换、旧凭据无损升级、Portable/backup/restore、Fish 个人音色授权及 NovelAI 配额均须先独立审计。**本次不批准 persisted schema、Prompt 改动或具体迁移算法。**

未来跨域候选：P17 统一账户设置 UX、P15 请求身份显示/日志统计与 D-042 对齐，P7/P8 对应平台适配。**不启动实施，不阻塞已准备的 P8 Windows Gate，不自动提升 parity 或新增 Public Preview release blocker**。详细条款：[146_MULTI_API_ACCOUNT_PROFILES_CONTRACT.md](146_MULTI_API_ACCOUNT_PROFILES_CONTRACT.md)。

---

## D-045：P11 生产方向 B+新根、历史录音无损休眠封存与 C-A Moment 原字节封存

状态：**USER APPROVED PRODUCT DIRECTIONS / BOUNDED LR4 ENGINEERING AUTHORIZED / PRODUCTION IMPLEMENTATION AND P11 GATE PENDING**（2026-10-24）。

用户明确批准 [188](188_P11_PRODUCTION_DIRECTION_USER_DECISION_CANDIDATE.md) 的三项产品方向，授权边界正式登记在 [189](189_P11_LR4_USER_DIRECTION_AND_BOUNDED_ENGINEERING_AUTHORIZATION.md)，具体可执行 Codex 任务为 [190](190_P11_LR4_AUTHORIZED_UNWIRED_ENGINEERING_CODEX_TASK.md)。本次决定选择**产品目标方向**，不是确认可部署的持久 schema、Windows SecretStore/DPAPI 移交、安全恢复事务或旧 EXE 隔离机制已经可行。

- **G1B：优先 B + inactive fresh root。** 独立新数据根及根范围受保护凭据参与者先完整准备，之后仅在正式安全验证、用户确认及可恢复 authority 成立时才考虑切换；旧根、原 Key 必须保全。默认不跨账户 fallback、覆盖、删除。Community protected sink、旧版防写、Portable/clone/DPAPI、离线未知 consumer 与真实 crash recovery 仍阻塞生产实现。
- **历史录音：无损而非猜测修复。** 无法匹配当前版本的非空显式录音版本 ID 保留原始记录和媒体，未来仅作为不自动播放/不运行的独立保护对象；不能默默映射至当前消息版本或删除。若无法证明完整、可恢复、可重新导出的保全，则导入提交前拒绝**整包**。LR3 的 199/36 是第一个失败内嵌 SaveSlot 的分类，不能当成完整全备验证或证明历史剪枝原因。
- **C-A：MomentPost/Task 原始 JSON + 三路径独立媒体副本。** 保留 senderAvatar、imagePath、generatedImageMetadata.imagePath 关系和原字节；不自动启动 Task/Scheduler、不调用模型、不自动删除；容量不足、未知来源与不能证明资源所有权时拒绝新封存。FullBackup、Snapshot、GC、Portable、root-switch、P13 接管/回滚仍要另行设计和验收。

**LR4 授权仅限**新建内部未接线 typed preparation contract、合成隔离根测试及真正只读的大备份全流验证（语义不完整仍 BLOCKED），并允许此前明确授权的 `H:\手机CCB导出` 目录只读读取。**不授权**写真实 SecretStore、实际 DPAPI、持久身份/namespace/intent schema、正式恢复链/Startup/GC 接线、G2C、旧版 EXE 和安装/Android 实机执行、Prompt 改动、合并、发布或提升 upstream baseline。

下次门禁：先由 Codex 将 [190](190_P11_LR4_AUTHORIZED_UNWIRED_ENGINEERING_CODEX_TASK.md) 的工程交付为 `P11-LR4 PROJECT CODE REVIEW CANDIDATE`，Project 审查真实 source/test/证据；若拟进入持久化和 G2C，再由 Project 提出准确 diff、事务协议、真实平台验证要求取得**额外明确批准**。

`G1B OPEN / G2C NOT AUTHORIZED / P11 PENDING / D-038 BLOCKED / Post-P8 HOLD / Public Preview HOLD / A–G 0/7 signed`。正式 validated upstream 仍 1.4.1，既有 EXACT/EQUIVALENT/PENDING 不因本决定自动改变。

---

## D-046：P11 + Public Preview A–G 一次性连续实现；Project 仅在实现后审计

**状态：用户明确批准的当前开发执行原则 / 2026-10-24 / 立即生效。**

用户明确要求停止 LR1–LR7 演变出的重复预审、层层批准和不必要的 Broker/VM/凭据事务设计循环。目标是直接完成 Android CCB → Windows Native Desktop 的实际功能移植、P11 和首个 Public Preview **A–G 七项工程与验收**，尽快发布可用 Preview。

**本决定调整开发流程，不伪造已经通过的测试，不解除实际数据损失或安全问题的最终公开发布阻断。**

1. **Codex 是连续实施者。** 当前任务从 `feature/phase8-fish-audio` 现行 HEAD 直接开始。Codex 自主规划多个实质工程阶段，在一个长任务中连续完成实现、修复、构建、测试和提交推送；阶段结束只需简单回报 SHA、功能和下阶段，随后继续。**不得把 Project 的逐阶段审计、下一份合同、下一次用户审批当作普通开发的启动条件。**
2. **Project 负责后置审计。** Project 对已完成的 Git commit/PR、官方运行语义、真实测试结果和最终 Preview 完整兼容性进行审查；发现问题给 Codex 直接修复，不妨碍其继续不依赖该问题的阶段。Project 不得以更多设计报告/权限回执代替用户所要求的实际功能实现。最终公开发布仍以真实 A–G 证据、作者授权证据和可安装构建为准。
3. **首先核实真实数据。** 对已经存在的 11 条不能解析 Entity，明确 type/path/完整原始 JSON/错误字段，直接生成私有本地报告供用户查看；对 5 个内嵌 SaveSlot 的 108 条历史语音，提供录音→所属消息→当前及历史版本原始 ID/文本/音频存在性对应关系。不得因为检查器报 `SCHEMA_UNSUPPORTED` 或版本 ID 不在最多 5 个现存替代版本中就猜测备份损坏。修复真实 schema 适配，或无损保全仍不能解释的数据。
4. **真正实现 Preview 完整范围。** P11 `.cbbackup` 官方1.4.5和实际 code86/v2 兼容恢复及导出、`.cbsave` 双向交换、Messages/WorldBook/RAG/Memory/voice/Moments及资源、受保护凭据映射、可用 Windows 安装/更新、备份/恢复/最小诊断，和 A–G 的真实跨端证据。P12–P17 非 Preview 必备项不前置。Codex 可以跨既有内部 phase 文件直接修改所需生产代码，**不再禁止正式恢复链/SecretStore/Startup/GC 的正常工程实现**；只要求经过适当隔离副本和数据安全测试。
5. **使用已有授权资源。** 用户自有手机导出资料、已明确标记的一次性本地测试根可用于真实互通诊断和重复恢复试验。原备份/原数据根默认保留、必要时快照；可以按已标记的 disposable test root 规则重建测试副本。用户提供的密码/API Key/Token 可经屏蔽输入、ignored local 配置或 OS SecretStore 用于请求的真实连接测试，**不需要反复抽象讨论是否“许可使用”**；没有拿到真实凭据时只询问这一项。不得提交或在公共日志/云端暴露密钥。不能为了测试清空未标记的数据目录。
6. **最小充分安全设计。** 保留 JsonFileStorage 原子写、来源保全、失败拒绝、日志隐私和 Windows OS 保护机制。D-045 的历史录音/Moments 无损目标有效，但 LR6 的 Broker/`ProgramData`/独立主体架构和 LR7 云端 VM 草案不再是预设必经技术路线。只有实际受支持的产品实现证据表明简单方案不足时，才引入更复杂的基础设施。
7. **真实验收直接找用户。** Codex 按模块提供安装包、启动步骤、预期行为和简短操作指引，由用户做必要的人工 GUI/Android 设备验收；不要通过 Project 转发人工测试。自动化测试失败应立即修复，不能在没必要的前置架构研究里空转。
8. **七项 Gate 是最后的公开版本验收，不是七次编码许可。** A–G 各自的具体工程可并行/按依赖顺序实现。当前正式0/7签署不能冒充七项全部失败，也不能阻断编码。真实数据丢失、凭据外泄、未恢复的支持数据、错称兼容、未获确认的 D-016 作者发布授权仍是最终 release blocker；Codex 应在同一实施任务中修复或标出真正的外部依赖。用户已要求尽快发布 Preview，不能为了 P17/P18 完整性延期。

**过程覆盖关系：** 本条覆盖历史 `03_CODEX_START.md`、`04_CODEX_TASK_TEMPLATE.md`、`05_PROJECT_REVIEW_TEMPLATE.md` 以及 LR1–LR7 文档中“每一步必须等 Project 单独批准才可开始下一步”的**流程性限制**。不覆盖上游源码权威、已验证兼容性事实、Prompt 原文修改审批、Secrets 不入 Git、Git 分支保护与真正数据安全承诺。旧文档保留作历史证据，不自动作为 Codex 阻断条件。

**下一动作：** 以一次可持续多阶段 Codex 实现任务完成 P11 与正式 Preview A–G；Project 在完成后的交付节点审核，不再继续生成纯研究 LR 阶段。当前 release 状态仍按实际证据记为 `P11 PENDING / Public Preview A–G 尚未正式签署`，直到真实工程实现与复核通过。

### D-046 用户补充：本地使用与 GitHub 隐私边界（2026-10-25 JST）

用户明确补充：「api别给我发github里就行了。包括cbsave和cbback，不上github就可以了」。

**D-046 用户补充 / 2026-10-25 JST：** API、密码、真实 `.cbsave`、`.cbbackup`（含用户称作 `.cbback` 的备份）及解密/解包内容可以在已授权任务内本地使用，包括对应 API 调用、导入、迁移和测试；**不得上传 GitHub**，涵盖提交历史、PR/Issue、评论、附件、Actions 日志/cache/artifact、Release 和支持包。放本地忽略目录并在提交/打包前检查；GitHub 仅放代码、脱敏结果和人工生成的测试样本。不得回显凭据，不自动向其他云服务转移这些私密内容。

Project 仅在实现后阶段性审计，不是 Codex 跨 phase、测试、常规修复、已授权 Fork 集成或 Preview 发布的前置签字人。七项 Gate 放进同一轮工程交付；复用有效证据，普通缺陷和未覆盖环境可如实登记后续。必要真人验收直接交用户，等待时继续独立工作；不伪造测试通过。用户已提供的凭据不重复索取使用许可，手机导出原件保留，覆盖测试数据前自动留可用恢复副本。此补充覆盖下方相冲突的历史流程要求，不撤销已有实现或验证事实。
