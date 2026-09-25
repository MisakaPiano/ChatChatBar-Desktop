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
- Phase 3 — Entities + Package + Import/Export
- Phase 2 已完成；Phase 3 尚未开始

---

## Phase 2 — Shared Storage Foundation

状态：**COMPLETE**

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
- Phase 2B：**COMPLETE**
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
- Phase 2B4 — Data Root / Portable / Migration：**COMPLETE**
- Phase 2B4A — Data Root Bootstrap Authority：**COMPLETE**
- Phase 2B4A implementation commit：`412e04422a2b65f14d280f38a3e44a8830226d78`
- Phase 2B4B — Portable Mode Root Resolution：**COMPLETE**
- Phase 2B4B implementation commit：`bb7ff31047af5f705a8ac5e12a9001d04e75e63e`
- Phase 2B4C — Global Data Operation Coordination：**COMPLETE**
- Phase 2B4C0 — Global Data Operation Coordination Audit：**COMPLETE**
- Phase 2B4C1 — Process-local Data Operation Coordinator：**COMPLETE**
- Phase 2B4C1 implementation commit：`1a535229c2a201f496b93e9a98362ff8037cafde`
- Phase 2B4C1 lifecycle ownership hardening：`38c8587080dbf715e60348fc2af3554c09004f63`
- Phase 2B4C2 — Per-root Process Ownership / Cross-process Single Writer：**COMPLETE**
- Phase 2B4C2 ownership primitive：`060897cb8eb3ebabc8d4c3a1c5202d43b8e949aa`
- Phase 2B4C2 snapshot/restore integration：`390fdc576e983e35768d27e438b0500e1eeab6f0`
- Phase 2B4C2 lifecycle integration：`5a16e3152b9bacdbf4e3159dc04798fec1055381`
- Phase 2B4C2 process-regression hardening：`8065f8ffa4a94c83159709056cc5497c94606431`
- Phase 2B4D — Safe Data-root Migration / Root Switch：**COMPLETE**
- Phase 2B4D0 — Migration / Root-switch Contract Audit：**COMPLETE**
- Phase 2B4D1 — Migration Destination + Bootstrap Authority Safety Foundation：**COMPLETE / PROJECT REVIEW PASS**
- Phase 2B4D1 implementation：`c0d3c005c302ebbbeecba906c579f589b70732ab`
- Phase 2B4D2 — Migration Materialization Transaction：**COMPLETE / PROJECT REVIEW PASS**
- Phase 2B4D2 implementation：`9205ce9b9cc3ee8d27e38fba26056ddd8611299d`
- Phase 2B4D2 baseline merge：`be3ff352500c0fdf04cf82b1447a68361af5340b`
- Phase 2B4D2 workspace provenance hardening：`ba847be0513d51f27f6bbfa1601d58038bf64602`
- Phase 2B4D3 — Migration Orchestration + Authority Commit + Restart Seal：**COMPLETE / PROJECT REVIEW PASS**
- Phase 2B4D3 implementation：`525f3c8fd6e4b93af25082a4f11c01629edbbe50`
- Phase 2B4D migration core：**COMPLETE**
- Phase 2B4 root-switch adapter：`4936353155dd78b6cb69ddf951851b6b8197e662`
- Phase 2B4 root-switch cancellation disposition fix：`cdaf0e510f4da9ab31bde1236be2caf27ec2834a`
- Phase 2B4 root-switch destination-label fix：`9deda566f672acb06e4fc84b144d1a48a3563921`
- user-facing root-switch action、Desktop adapter、packaged build 与 manual acceptance：**COMPLETE / PROJECT REVIEW PASS / MANUAL PASS**
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
- shared snapshot primitive 仍要求 caller 保证 quiescence；Desktop coordinated facade 与 per-root ownership 已分别覆盖 process-local admission 和 cooperative CCB processes，磁盘 revalidation 不宣称阻止 adversarial third-party mutation
- synchronous single-run automatic backup execution 已建立；eligibility 使用 service Clock
- execution 严格按 policy → create completed `AUTOMATIC` snapshot → prune 执行
- skipped execution 不 create / prune；creation failure 不 prune
- pruning failure 不会通过删除本次新 backup 进行补偿；post-commit cleanup warning 仍返回 successful execution
- 本次新建 snapshot 在同一次 retention 中受保护并占用一个 retention slot，包括 equal timestamp / `Duration.ZERO` 情况
- Desktop create / restore / prune / execute 已经由 process-local coordinator 串行进入 exclusive maintenance
- Desktop explicit-start scheduling capability 已建立；立即执行首次检查，随后按 check interval 串行运行，scheduled executions 不重叠
- per-run execution failure 与 observer / event reporting failure 均不会终止 scheduling loop
- `stop` / `close` 允许 in-flight synchronous filesystem transaction 安全完成，不强制中断
- scheduler execution callback 已改为 suspend，并通过 coordinated snapshot facade 进入 exclusive maintenance
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
- runtime-local mutex 继续负责 lifecycle；process-local data-operation coordinator 独立负责 app-data admission
- future manual snapshot / restore / prune 与 Desktop business writers 必须通过 shared operation gate 或 coordinated adapter 参与
- `DesktopAppContainer` construction 保持 zero-write，也不隐式 initialize/start runtime
- Desktop startup 已按 appDataRoot resolve → ownership acquisition → container construction → runtime initialize → Compose application 接入
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
- Phase 2B4C1 已建立 process-local maintenance-exclusive coordination；normal operations 保持并发，maintenance pending 后停止接纳新的 unrelated normal operations，并等待已登记 operations drain
- coordinator state model：`OPEN` → `MAINTENANCE_PENDING` → `EXCLUSIVE`；restore success 或 incomplete rollback 可 seal 为 `RESTART_REQUIRED`；shutdown 使用 `CLOSING` → `CLOSED`
- `JsonFileStorage` 通过 shared platform-neutral `AppDataOperationGate` 参与，顺序为 gate → existing per-entity mutex → IO/filesystem operation；Android/default shared wiring 使用 no-op gate
- same-gate nested normal operation 使用 coroutine-context identity marker 幂等参与；`withContext` 与 structured child work 继承 registration，detached work 不得借用外层 marker
- exclusive waiters 使用 FIFO；pending admission、drain、cancellation 与 exception cleanup 已验证，不使用 one global mutex 串行所有 Entity operations
- snapshot mutation 通过 suspend Desktop coordinated facade 进入 exclusive maintenance；raw snapshot primitive 不从 container public 暴露
- successful Desktop restore 与 incomplete rollback 会 seal restart-required；normal/exclusive queued work 不会在旧 container 上重新开放
- maintenance lifecycle 顺序固定为 runtime pause → scheduler stop/join → coordinator exclusive；禁止 exclusive → scheduler.stop，以避免 scheduler admission deadlock
- runtime paused / restart-required 时拒绝 settings mutation；mutable settings store 与 scheduler bypass surfaces 不从 container public 暴露
- Phase 2B4C1 validation：`:sharedCore` **9 suites / 94 tests PASS**；`:desktopApp` **10 suites / 108 tests PASS**；Android JVM **184 suites / 1141 tests PASS**；Desktop / Android compile 与 `git diff --check`：**PASS**
- lifecycle ownership hardening 后重验 `:desktopApp` **10 suites / 108 tests PASS**、Desktop compile 与 `git diff --check`：**PASS**；因未修改 sharedCore / Android source，未重复 Android full regression
- Phase 2B4C2 已建立 per-selected-root cooperative single-writer ownership；同一 `appDataRoot` 只允许一个 cooperative writable Desktop process，不同 roots 可同时使用
- ownership 使用 JDK `FileChannel` + `FileLock`，并在整个 application lifetime 保持；lock path 为 `<appDataRoot>/.ccb-desktop.lock`
- lock file existence 不代表 active ownership；stale zero-length lock file 是预期 infrastructure，normal shutdown 不删除它
- acquisition 在 `DesktopAppContainer` / runtime 构造前完成；same-root conflict explicit failure，绝不 fallback 到另一 root
- shutdown 顺序为 runtime → process-local coordinator → ownership；process ownership 与 C1 coordinator 是独立安全层
- root-level lock artifact 从 snapshot source / manifest 排除，crafted snapshot lock payload 被拒绝，restore 保留 live lock；snapshot formatVersion 仍为 1
- Windows child-JVM regression 已覆盖 same-root rejection、different-root coexistence、normal release/reacquire 与 forced termination/reacquire；同步使用 READY handshake，不依赖 arbitrary sleep
- Phase 2B4C2 final validation：`:sharedCore` **10 suites / 105 tests PASS**；`:desktopApp` **12 suites / 137 tests PASS**；Windows child-JVM **4 tests executed / 0 skipped / 0 failures**；Desktop compile 与 `git diff --check`：**PASS**
- C2-B Android JVM **184 suites / 1141 tests PASS**、Desktop / Android compile：**PASS**；C2-C/R1 仅修改 Desktop/test code，复用该 Android regression evidence
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

Phase 2 收口：
- D1 destination/authority safety、D2 materialization、D3 orchestration 与 user-facing root-switch adapter 已完成并通过 Project review
- packaged positive / negative root-switch acceptance 与 R2 destination-label retest：**PASS**
- root switch 是 copy + authority commit；当前 process 始终保留 startup source identity，成功后要求用户退出并重新打开，下一次启动使用 committed `CUSTOM(destination)`
- migration v1 intentionally retains source；automatic old-root deletion / source cleanup 不属于 Phase 2 完成条件
- Portable persistent migration、CLI-override persistent migration、actual CLI parser 与 Portable ZIP release packaging：**DEFERRED**
- full Desktop settings center、automatic-backup settings UI、Task Center / tray：**DEFERRED**
- SecretStore migration、installer/updater 与 automatic process relaunch：**DEFERRED**
- JSON Entity persistence 与业务 parity 进入 Phase 3+，不因 Phase 2 infrastructure 完成而自动标记 EXACT
- Phase 2B4、Phase 2B 与 Phase 2：**COMPLETE**

下一步：
- **Phase 3 — Entities + Package + Import/Export**
- Phase 3：**NOT STARTED**

验收：
- save → exit → restart → restore
- Android persistence regression
- Desktop storage tests

---

## Upstream 1.4.0 synchronization

状态：**COMPLETE**

- upstream：`1.4.0 @ e30096ed3585b5e2b1da18299a8ed21c434ce4b3`
- source merge / Desktop reconciliation：`9e6363027a6977727ee512a8e86882263277e248`
- delta：4 commits / 59 changed files
- high-risk reconciliation：sharedCore storage safety、Prompt text / START-END-BOTH order、retrieval-model retirement migration、streaming progress/watchdog、NovelAI Studio clipboard、memory partial-save journal contract
- validation：sharedCore **11 suites / 114 tests**、desktopApp **12 suites / 137 tests**、Android JVM **186 suites / 1158 tests**；Desktop / Android compile 与 `git diff --check` **PASS**
- Project review：**PASS**
- 在 1.4.0 sync checkpoint 当时，Phase 2B4、Phase 2B 与 Phase 2 状态为 **IN PROGRESS**，migration / root switching 尚未实现；其后已在 Phase 2B4 完成

---

## Upstream 1.4.1 synchronization

状态：**COMPLETE**

- upstream：`1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`
- source merge：`077286fd531eb794499c0bc3e8941b23fd3235b6`；validated sync HEAD：`c5fcac52c3b7249ac4d6ca51ef083c835b46b395`
- delta：3 commits / 10 changed files
- compatibility changes：reasoning-only interrupted draft persistence、latest-USER blank-continue reuse and deduplication、responding-gate cleanup ordering、temperature `1.0` 与 common `reasoning_effort = low`
- `PromptTemplates` text 未变化；Prompt pipeline/runtime semantics 已同步并验证
- validation：sharedCore **11 suites / 114 tests**、desktopApp **12 suites / 137 tests**、Android JVM **186 suites / 1161 tests**；Desktop / Android compile 与 `git diff --check` **PASS**
- Project review：**PASS**
- 当前 upstream baseline 上的 Phase 2 storage/data-root foundation、migration core 与 user-facing root switch 已完成；下一项为 Phase 3 — Entities + Package + Import/Export

---

## Phase 3 — Entities + Package + Import/Export

状态：**COMPLETE / PROJECT REVIEW PASS**

Phase 3A：
- `22_PHASE3_CONTRACT_AUDIT.md`：**PROJECT AUDIT COMPLETE**
- Package / Entity / Prompt boundary：verified
- Character schema 9 / read 3..9：verified
- Format schema 2 / read 1..2：verified
- WorldBook schema 1：verified
- PNG / SillyTavern / repository / materialization / failure paths：mapped
- D-027 / D-028 / D-029：**RESOLVED**

Implementation slices：
- **3B1 Shared Entity / Package Contract Core — COMPLETE / PROJECT REVIEW PASS**；implementation `367a8ce7432bafbb926a176e23886c765b12a8f7`；authoritative Entity / repository / Package / pure-policy contracts 已迁入 sharedCore，Android duplicate authority 已移除
- **3B2 FormatCard + WorldBook Transfer Core — COMPLETE / PROJECT REVIEW PASS**；implementation `8a213233dcce9db1b58afef50f7ee2fa14c9e0ad`；FormatCard / WorldBook transfer 与 ST World Info / Character Book codec 已成为 sharedCore authoritative implementation，Android duplicates 已移除
- **3C1 Character Resource / Materialization Core — COMPLETE / PROJECT REVIEW PASS**；implementation `783af9a10f6d95c618d7ffb81a7fa2949f65b9ab`，strict durable-delete R1 `74f9af25270f2bf893a4bd3699613b0037e71403`
- **3C2 ST Character + Classifier Split — COMPLETE / PROJECT REVIEW PASS**；implementation `d78d76df656fa3ce9fd309a6cbe52c6cfb379f30`
- **3P / Phase 4A Prompt Ownership Closure — COMPLETE / PROJECT REVIEW PASS / INTEGRATED**；implementation `f722703c33d8cd96728fc06ff617c9d7d79d9c7d`；D-028 authoritative Prompt dependency 已关闭，Prompt literal/runtime behavior 未改变
- **3D Desktop Typed Import/Export + PNG Renderer Equivalent — COMPLETE / PROJECT REVIEW PASS / PACKAGED PASS / MANUAL ACCEPTANCE PASS / INTEGRATED**；implementation `d8987605e733b07a5deac1901ee049011d47d153`
- observed upstream HIGH drift `354f15166d8bc0462cb87d62a0ba4613794560a3`：Project impact audit 后保留为 known compatibility debt；未直接推翻已审查的 3P/3D facts，进入后续 selective/batch sync window，formal baseline 暂不变
- **3F Android ↔ Desktop interoperability gate — COMPLETE / PROJECT REVIEW PASS / INTEGRATED**；checkpoint `717ec1a473660b5d186a4241778552bc6f12a80b`；API 34 / API 36 targeted device verification 覆盖 Android/Desktop 双向 JSON、CCB PNG、resources/documents/worldbooks、FREEFORM、STRUCTURED、multi-character、FormatCard、WorldBook、Fish binding、provider ingress 与失败原子性，未发现 production interoperability defect

3B1 validation：focused **6 suites / 21 tests**、sharedCore **17 suites / 135 tests**、desktopApp **19 suites / 232 tests**、Android JVM **182 suites / 1150 tests**；Desktop / Android compile 与 `git diff --check` 全部 **PASS**。3B1 未提前做 materialization、file picker、visual renderer、Prompt text/bridge 或 ST Prompt coupling。

3B2 validation：FormatCard transfer **7 tests**、WorldBook transfer **10 tests**、WorldBook reuse **3 tests**、sharedCore **19 suites / 152 tests**、desktopApp **19 suites / 232 tests**、Android scoped caller **1 suite / 6 tests**；sharedCore / Desktop / Android compile 与 `git diff --check` 全部 **PASS**。两个 production service 是 byte-identical sharedCore moves；Desktop user-facing file import/export 与 Character materialization 未进入 3B2。

3C1 validation：sharedCore **21 suites / 170 tests**、desktopApp **22 suites / 238 tests**、Android JVM **181 suites / 1147 tests**；Desktop / Android compile 与 `git diff --check` 全部 **PASS**。shared Character materialization authority、Android/Desktop resource adapters、D-027 relative refs、whole-transfer gate、D-029 rollback/commit boundaries 与 strict durable-delete R1 已通过 Project review；user-facing typed transfer、PNG renderer、ST Character 及 final Prompt/RAG/asset wiring 仍属后续范围。

3C2 validation：ST parser **5 PASS**、ST mapper **5 PASS**、shared classifier **7 PASS**、Android ModelTemplate facade **2 PASS**；sharedCore **24 suites / 187 tests**、desktopApp **22 suites / 238 tests**、Android JVM **181 suites / 1141 tests**，Desktop / Android compile 与 `git diff --check` 全部 **PASS**。shared ST parser/mapper/classifier authority 已完成；user-facing ingress 与 Android device/provider acceptance 仍属 3D/3F。

3P validation：implementation `f722703c33d8cd96728fc06ff617c9d7d79d9c7d` 已通过 Project review 并集成；sharedCore `CharacterNaiPromptDefaults` 与 `AuthoritativeCharacterTransferPromptPolicy` 成为一个 Prompt-domain authority，Android `PromptTemplates` 保留 facade；无 Prompt literal、Prompt runtime、assembler/context/final-order 变化。

3D validation：sharedCore **27 suites / 196 tests**、desktopApp **27 suites / 251 tests**、Android JVM **181 suites / 1142 tests**，均为 **0 failures / 0 errors / 0 skipped**；Desktop compile、Android compile 与 `git diff --check` **PASS**。`:desktopApp:createDistributable`、packaged launch smoke 与 user manual acceptance **PASS**。typed Character CCB JSON/PNG、ST V1/V2 JSON/Chara PNG、FormatCard JSON、WorldBook ChatBar/ST transfer 与 conflict New/Overwrite/Cancel 已验收；global SharedImport ingress/FIFO、drag/drop/Open With、ModelTemplate import、完整 management UI 与 full RAG runtime 仍属后续独立范围。

3F validation：targeted `Phase3FAndroidInteropTest` 在 API 34 与 API 36 均 **PASS**（API 36：**1/1，0 failed，0 skipped**）；双向 Package/resources/provider ingress/invalid atomicity 已验证，未发现 production defect。完整 API 36 connected suite 因已知且与 3F 无关的 instrumented debt 按 stop rule 未跑到结束，不构成全套绿色声明。

Phase 3 业务 contract extraction、materialization、typed Desktop transfer、Prompt ownership 与最终验收要求“Android ↔ Desktop JSON/PNG bidirectional compatibility”均已完成并通过 Project review。下一阶段：**Phase 4 — Core Chat / Prompt / WorldBook**；尚未实现的 global SharedImport、完整 management UI、full RAG runtime 等继续按各自 roadmap 状态管理。

D-027 governs Desktop root-relative owned resources. D-028 governs authoritative Prompt dependency. D-029 permits narrow destructive-failure hardening without changing normal success semantics.

Program budget:
- original Phase 3 envelope: **~0.50 weekly**
- Phase 3A audit was completed by Project with **Codex cost = 0**
- audit saving is favorable variance and does not justify lowering safety/validation
- detailed tracking rules: `23_CODEX_BUDGET.md`

最终验收：
**Android ↔ Desktop JSON/PNG bidirectional compatibility**，且 Package/schema/Prompt ownership 与 Desktop resource/data-safety decisions 全部通过 Project review。

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

Reference consumption pointer：`27_EDITOR_REFERENCE_ADOPTION.md` records the adopted mature editor UX / regression oracles. `Desktop Image Workspace / Image Editing Foundation` is a future architecture/design candidate spanning editor/image-bearing features; it does **not** create a new Phase/slice or change the existing Phase order.

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
