# CCB Desktop Development Roadmap

目标：完成 CCB Desktop，同时建立可持续跟随 upstream 的开发机制。

---

## Phase 0 — Bootstrap / Audit

状态：**COMPLETE**

产物：
- Source Map
- Instructions
- baseline
- audit
- architecture
- parity
- compat
- sync playbook
- test matrix
- decisions
- data compat
- release checklist
- current state

完成状态：
- Fork `MisakaPiano/ChatChatBar-Desktop` 已建立
- `desktop` branch 已建立
- 新 ChatGPT Project 已完成首次接管
- upstream 1.3.49 baseline / schema / architecture / feature-skill consistency 已复核并合入 `desktop`
- 首轮只读审计与文档修正已完成

---

## Phase 1 — Desktop Bootstrap

状态：**COMPLETE**

目标：
```text
:app Android 不受影响
:desktopApp 可启动 Windows 原生窗口
```

内容：
- Compose Desktop plugin/module
- Main entry
- application window
- Desktop version/about
- data-root discovery skeleton
- build command
- native distribution smoke config

实现结果：
- 新增纯 Kotlin/JVM `:desktopApp`，使用 Kotlin 2.3.20、Compose Desktop 1.10.3、JVM 17
- 原生 `application` / `Window` 入口标题为 `ChatChatBar Desktop`
- 数据根目录只读解析为 `%LOCALAPPDATA%\ChatChatBarDesktop`；环境变量缺失时回退到 JVM `user.home\AppData\Local\ChatChatBarDesktop`
- EXE / MSI native distribution 已配置，未执行发布或签名
- Desktop compile / unit tests 与 Android compile / 1141 项 unit tests 均通过
- manual GUI acceptance：Windows native window **PASS**
- title：`ChatChatBar Desktop`
- bootstrap content：**PASS**
- displayed data directory：**PASS**
- run process clean exit：**PASS**

不做：
- 业务功能
- shared 大重构

验收：
- Android compile：PASS
- Android unit tests：PASS（1141 tests，0 failures）
- Desktop compile / unit tests：PASS
- Desktop run / manual GUI acceptance：PASS
- no browser/webview：PASS

下一步：
- Phase 2 — Shared Storage Foundation
- Phase 2 已进入实施阶段

---

## Phase 2 — Shared Storage Foundation

状态：**IN PROGRESS**

目标：
把 JsonFileStorage 的 root 从 Android Context 中抽离。

内容：
- shared/JVM storage core
- Android adapter
- Desktop adapter
- `%LOCALAPPDATA%`
- Portable Mode
- atomic write parity
- backups/snapshot framework
- Desktop settings

完成状态：
- Phase 2A：**COMPLETE**
- Phase 2A1 shared JSON storage extraction：**COMPLETE**
- Phase 2A2 edge-case / fault-injection validation：**COMPLETE**
- Phase 2B：**IN PROGRESS**
- Phase 2B1 — App Data Snapshot Foundation：**COMPLETE**
- Phase 2B2 — Transactional Snapshot Restore Foundation：**COMPLETE**
- Phase 2B3：**COMPLETE**
- Phase 2B3A — Backup Provenance + Automatic Backup Policy：**COMPLETE**
- Phase 2B3B — Safe Automatic Backup Retention / Pruning：**COMPLETE**
- Phase 2B3C — Automatic Backup Execution Foundation：**COMPLETE**
- Phase 2B3D — Desktop Automatic Backup Scheduling Adapter：**COMPLETE**
- Phase 2B3D implementation commit：`08646ea3b1370054b7633e5669129638f0393bc9`
- Phase 2B3D event-sink robustness fix：`0e19d14a26004b498f90db79d7fba831906d5792`
- Phase 2B3E — Automatic Backup Settings + Startup Integration：**COMPLETE**
- Phase 2B3E settings/runtime commit：`7f202359887c8cb5271b50cd8e854a653a49e033`
- Phase 2B3E settings JSON robustness fix：`bce51e64cf54bc2567e33fc9f0c5284f43ad2932`
- Phase 2B3E Desktop lifecycle integration：`ce0ba7acfc79b54c5878c7086d07ffde6a99b48c`
- Phase 2B4 — Data Root / Portable / Migration：**IN PROGRESS**
- Phase 2B4A — Data Root Bootstrap Authority：**COMPLETE**
- Phase 2B4A implementation commit：`412e04422a2b65f14d280f38a3e44a8830226d78`
- Phase 2B4B — Portable Mode Root Resolution：**COMPLETE**
- Phase 2B4B implementation commit：`bb7ff31047af5f705a8ac5e12a9001d04e75e63e`
- `:sharedCore` 已建立
- `JsonFileStorage` 已改为 root-driven，并由 Android/Desktop 共享的纯 JVM core 提供
- Android data path preserved：仍为 `filesDir/entities/...`
- Desktop storage core wiring 已通过 `DesktopAppContainer` 建立；尚未启用 Desktop business persistence
- App data snapshot 已具备 create、list 与 validation
- snapshot 位于 `<appDataRoot>/backups/`，通过 staging → validation → completed install 建立
- self-describing manifest format v1 使用 root-relative paths，并记录 size 与 SHA-256
- snapshot 排除 `backups/` 递归，listing 隔离 malformed snapshot
- source quiescence 由 caller 在整个 snapshot creation 期间保证
- Transactional restore 会先验证 selected snapshot，创建 mandatory completed pre-restore safety snapshot，并在任何 active mutation 前完成 restore staging
- active payload 在安装前移入 recovery；安装后验证 restored payload，并以明确 restore commit point 分隔 pre-commit rollback 与 post-commit cleanup
- pre-commit failure 会 rollback；rollback 不完整时保留 recovery evidence
- post-commit cleanup failure 不会 rollback 已提交的 restore，并通过 `SnapshotRestoreResult` 暴露 cleanup warning 与 retained workspace
- `backups/` 始终位于 restored active payload 之外；empty snapshot replacement、existing backup history retention 与 restart persistence 已验证
- snapshot formatVersion 保持 1；新增 optional purpose metadata：`MANUAL`、`PRE_RESTORE`、`AUTOMATIC`
- old v1 manifest 缺少 purpose 时按 `MANUAL` 解释且不静默 rewrite；此前产生的 legacy pre-restore snapshot 也不推断 provenance
- restore safety snapshot 现在明确标记为 `PRE_RESTORE`
- `AutomaticBackupPolicy` minimum-interval decision foundation 已建立；只有 valid `AUTOMATIC` snapshot 影响 interval
- clock rollback protection 已建立；exact minimum-interval boundary 为 eligible
- maximum-count retention 已建立；只有 explicit valid `AUTOMATIC` snapshots 可被 pruning
- `MANUAL`、`PRE_RESTORE`、legacy missing-purpose、malformed / unknown snapshots 均受保护
- pruning 使用 `QUARANTINE_THEN_DELETE`；completed snapshot 不直接 recursive delete
- `.prune-*.tmp` move 成功即为 prune commit point；post-commit cleanup failure 不 rollback，并返回 retained workspace / warning
- orphan `.prune-*` workspace 从 listing 排除，但不会被自动清理
- create / restore / prune repository operations 仍要求 caller 串行化；磁盘 revalidation 不宣称消除任意外部并发 mutation 的最终 TOCTOU
- synchronous single-run automatic backup execution 已建立；eligibility 使用 service Clock
- execution 严格按 policy → create completed `AUTOMATIC` snapshot → prune 执行
- skipped execution 不 create / prune；creation failure 不 prune
- pruning failure 不会通过删除本次新 backup 进行补偿；post-commit cleanup warning 仍返回 successful execution
- 本次新建 snapshot 在同一次 retention 中受保护并占用一个 retention slot，包括 equal timestamp / `Duration.ZERO` 情况
- create / restore / prune / execute 仍要求 caller 串行化
- Desktop explicit-start scheduling capability 已建立；立即执行首次检查，随后按 check interval 串行运行，scheduled executions 不重叠
- per-run execution failure 与 observer / event reporting failure 均不会终止 scheduling loop
- `stop` / `close` 允许 in-flight synchronous filesystem transaction 安全完成，不强制中断
- scheduler 只串行化自身 runs；future manual create / restore / prune 必须与 scheduler 协调
- Desktop settings 持久化位于 `<appDataRoot>/desktop-settings.json`，formatVersion 为 1；它是 Desktop platform configuration，不属于 CCB Entity persistence
- Project defaults：automatic backup disabled、minimum interval 24 hours、maximum automatic snapshots 7、scheduler check interval 1 hour
- missing settings 只使用 in-memory defaults，不创建 app-data root 或 settings file
- corrupt / invalid / unsupported settings 保持原文件不变，不阻止 Desktop launch，automatic backup 保持停止
- unknown root / nested fields 在 explicit save 后继续保留
- settings save 使用 sibling temporary file、flush/close 与 atomic replace；仅在 `AtomicMoveNotSupportedException` 时 fallback ordinary replace
- settings reconfiguration 顺序为 stop scheduler → await in-flight backup → atomically save → enabled 时以新 schedule restart
- failed settings save 保留 prior file，并 best-effort 恢复 previous working schedule
- `DesktopAutomaticBackupRuntime` 管理 settings initialization、scheduler lifecycle/reconfiguration 与 runtime `StateFlow`
- runtime state 暴露 settings load result/failure、effective settings、scheduler status、latest event/execution failure、cleanup warnings 与 operation failure
- runtime-local mutex 只串行化 initialize / apply / close；它不是 global snapshot-operation coordinator
- future manual snapshot / restore / prune 与 Desktop business writers 仍须和 automatic backup operations 协调
- `DesktopAppContainer` construction 保持 zero-write，也不隐式 initialize/start runtime
- Desktop startup 已按 appDataRoot resolve → container construction → runtime initialize → Compose application 接入
- Compose 使用 `exitProcessOnExit = false`；window `exitApplication()` 后等待 runtime/scheduler close，再由 `main` 自然返回
- shutdown 不使用 force-cancel、`Thread.interrupt` 或 `exitProcess`，in-flight synchronous snapshot transaction 会安全完成
- automatic backup 只在 persisted settings 明确 `enabled = true` 时启动；default 仍为 disabled
- settings UI 与 Task Center：**NOT IMPLEMENTED**
- Phase 2B4A 建立的 precedence：optional explicit CLI override supplied by caller → external bootstrap selection → legacy/default LOCALAPPDATA root；Phase 2B4B 已在 CLI 与 bootstrap 之间加入 Portable authority
- external bootstrap 位于 `%LOCALAPPDATA%\ChatChatBarDesktop.bootstrap.json`；缺少 `LOCALAPPDATA` 时回退到 `<user.home>\AppData\Local\ChatChatBarDesktop.bootstrap.json`
- bootstrap formatVersion 为 1，支持 `DEFAULT` 与 `CUSTOM`；root provenance 区分 `CLI_OVERRIDE`、`BOOTSTRAP_DEFAULT`、`BOOTSTRAP_CUSTOM`、`MISSING_BOOTSTRAP_DEFAULT`
- missing bootstrap 保持 exact legacy/default root，且不创建 bootstrap 或 selected root
- corrupt / invalid / unsupported bootstrap 返回 structured failure，不 silent fallback，也不会针对 unintended default root 构造 `DesktopAppContainer`
- `CUSTOM` 仅接受 normalized absolute path；load / resolution 不创建该目录
- bootstrap save 使用 sibling temporary file、full write/flush/close 与 atomic replace；仅在 `AtomicMoveNotSupportedException` 时 fallback ordinary replace
- failed replace 保留 prior valid bootstrap；unknown root fields 在 explicit save 后继续保留
- root-location authority 位于 selected `appDataRoot` 外部，避免 bootstrap paradox 以及 snapshot/restore 错误改变根目录选择
- invalid `CUSTOM` selection 不得 silent fallback，避免打开空 default root 而表现为“用户数据丢失”
- default appDataRoot 本身未改变；successful root resolution 后 settings / automatic-backup runtime 行为不变
- packaged `ApplicationHome` authority 由 project-owned JVM property `chatbar.desktop.applicationHome` 提供，jpackage launcher 以 `$ROOTDIR` 在运行时展开；它不是 `user.dir`
- root precedence：explicit CLI temporary override → Portable → OS-local bootstrap → default LOCALAPPDATA；actual CLI parser 尚未实现
- Portable layout：`<ApplicationHome>/portable.flag` 与 `<ApplicationHome>/UserData/`；marker token 为 `CCB_DESKTOP_PORTABLE_V1`
- Portable paths 相对 `ApplicationHome` 解析，因此完整 application image 移动后仍可重新定位 `UserData/`
- pure Portable resolver 保持 zero-write；activation validator 独立执行 temporary write / flush / delete probe
- valid marker 一旦声明 Portable root authority，invalid / unusable `UserData/` 会 explicit failure，绝不 fallback 到 bootstrap/default root
- real jpackage `ApplicationHome` runtime expansion、whole-image relocation、positive Portable packaged smoke 与 invalid Portable no-fallback smoke：**PASS**
- user manual packaged UI acceptance：**PASS**；显示的数据目录为 relocated image 的 `<ApplicationHome>/UserData`
- production source 已为上述 bootstrap authority、fallback danger 与 compatibility trap 留下中文解释 + standard English technical term 的 developer KDoc；self-explanatory code 不增加冗余注释
- `:desktopApp` tests：**PASS（79 tests，0 failures）**
- `:sharedCore` tests：**PASS（92 tests，0 failures）**
- full Android JVM regression：**PASS（1141/1141）**
- Desktop / Android compile：**PASS**
- `git diff --check`：**PASS**
- raw/uncached behavior、cache isolation、file-set signature、`replaceWhere` 与 producer validation：**COVERED**
- deterministic installation rollback、restart persistence 与 read-side directory creation behavior：**COVERED**
- rollback restore failure branch：未自动覆盖；not deterministically testable without introducing a new production seam。该 test gap 不是当前 implementation blocker
- symlink implementation rejects / does not follow links；当前 Windows 环境无法创建可靠 symlink fixture。该 test coverage limitation 不是 implementation blocker
- deterministic copy-phase failure test deferred；without a new production filesystem seam 无法可靠触发。该 test coverage limitation 不是 implementation blocker
- install-stage-specific deterministic failure fixture deferred；without a new production filesystem seam / race 无法可靠触发。该 test gap 不是 implementation blocker
- rollback-restore-failure deterministic fixture deferred；without a new production filesystem seam / race 无法可靠触发。该 test gap 不是 implementation blocker
- symlink fixture 在当前 Windows 权限下不可用；junction/reparse deterministic fixture 与 exact policy→revalidation mutation fixture deferred。以上 test gaps 不是 implementation blockers

下一步：
- **Phase 2B4C — Global Data Operation Coordination**
- global data-operation coordinator、data-root migration、root-switch UI、old-root deletion：**NOT IMPLEMENTED**
- actual CLI parser 与 Portable ZIP release task：**NOT IMPLEMENTED**
- Phase 2B4 与 Phase 2 整体仍为 **IN PROGRESS**

验收：
- save → exit → restart → restore
- Android persistence regression
- Desktop storage tests

---

## Phase 3 — Entities + Package + Import/Export

内容：
- CharacterCard/CharacterInfo/Documents
- FormatCard
- WorldBook
- Package models
- schema v3..9 character
- Format v1..2
- WorldBook v1
- Character transfer
- PNG payload
- ST compatibility
- Desktop file picker/drag-drop base

验收：
Android ↔ Desktop JSON/PNG bidirectional.

---

## Phase 4 — Core Chat / Prompt / WorldBook

内容：
- PromptTemplates
- PromptAssembler
- ContextWindowManager
- WorldBookEngine
- Session/Message repositories
- Prompt Inspector
- session creation/greeting

先用 fake transport 或 test model driver 验证最终 message list，再接真实 Provider。

---

## Phase 5 — Model Runtime + Real Chat

内容：
- ModelConfig
- model discovery
- auth
- ProxyAwareClient
- StreamingChatService
- SSE
- thinking
- local HTTP
- cancellation
- debug logs
- background TaskRuntime

验收纵向切片：

```text
import card
→ create session
→ greeting
→ user message
→ WorldBook
→ final CCB prompt
→ stream response
→ persist
→ restart
→ continue
```

里程碑：
**Desktop Alpha**

---

## Phase 6 — Desktop Primary UI / Editors

- session/card browser
- three-pane chat
- settings
- character editor
- FormatCard editor
- WorldBook editor
- model editor
- responsive narrow layout
- keyboard/mouse shortcuts
- file association/Open With

---

## Phase 7 — Image Resources + NovelAI

完整：
- image resources
- card cover renderer
- chat images
- NovelAI credentials
- generation
- models/size/seed
- metadata
- regeneration
- automatic images
- Prompt Designer
- tag research/suggestion
- V5 NL
- guidance/vibe/inpaint
- history/Studio
- APNG
- mosaic editor

---

## Phase 8 — Fish Audio + Audio

- Fish secrets
- bindings
- library
- tags
- generation
- batch
- anchors
- playback
- audiobook behavior
- SaveSlot audio

QQ voice：
单独做 Windows feasibility spike，结果进入 Decision。

---

## Phase 9 — RAG

- documents
- chunking
- embedding
- vector
- retrieval
- chat-memory RAG
- indexing status
- character research reference-doc paths

---

## Phase 10 — Long-Term Memory

完整移植官方 state machines：
- Episode
- Arc
- Era
- Archive
- HEAD
- Gap/backfill
- compression
- repair
- regeneration
- coordinator

不得简化为单摘要实现。

---

## Phase 11 — SaveSlot / Session Lifecycle

- v1–7 legacy
- `.cbsave` v8
- images/audio
- transactional restore
- session duplicate
- cross-platform archive tests

---

## Phase 12 — AI Authoring / Repair

- Character AI
- Rewrite
- image-to-appearance
- card cover/avatar
- FormatCard AI
- WorldBook AI
- research
- message format repair

---

## Phase 13 — Moments

- timeline
- scheduler
- generation
- text-only
- image
- likes/private
- retry/edit/delete
- unread

Desktop runtime scheduler，保持“App 关闭不生成”的官方产品语义。

---

## Phase 14 — Community

- browse
- download/import
- upload
- Discord OAuth
- owner actions
- remote update
- runtime switch
- preview cache

---

## Phase 15 — OS Integration

- drag/drop
- file association
- Open With
- Explorer reveal
- clipboard
- tray/task center
- notifications
- crash diagnostics
- installer
- updater

---

## Phase 16 — Upstream Automation

可以更早逐步加入，但最终完成：
- upstream watcher GitHub Action
- changed-file classification
- high-risk labels
- sync issue/report
- baseline check

自动化不自动宣布兼容。

---

## Phase 17 — Beta Hardening

- data migration
- backup restore
- corrupted data behavior
- cancellation
- long-running tasks
- high DPI
- IME
- large cards/worldbooks
- huge SaveSlot
- large images
- network/proxy/VPN
- Windows 10/11

---

## Phase 18 — 1.0 Gate

要求：
- parity matrix 完整
- selected upstream baseline 全部功能有 EXACT/EQUIVALENT
- Android/Desktop package interoperability
- prompt/worldbook parity
- SaveSlot interoperability
- migration/backup
- updater
- release packaging
- direct upstream-author authorization record retained/confirmed for public release packaging；repository license metadata status documented separately

里程碑：
**CCB Desktop 1.0**
