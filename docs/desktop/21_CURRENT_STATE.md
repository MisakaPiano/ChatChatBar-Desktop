# CCB Desktop Current State

更新时间：2026-09-23

## 当前阶段

**Phase 2 — IN PROGRESS**

Phase 0、Phase 1 与 upstream 1.3.49 integration 已完成。Phase 2A shared storage extraction 与 edge-case validation、Phase 2B1 app data snapshot、Phase 2B2 transactional restore、Phase 2B3 automatic backup settings/runtime/startup integration，以及 Phase 2B4A data-root bootstrap authority、Phase 2B4B Portable root resolution、Phase 2B4C1 process-local data-operation coordination 已完成。Phase 2B4、Phase 2B 与 Phase 2 整体仍为 IN PROGRESS，因为 cross-process per-root ownership、root switching 与 safe migration 尚未实现。

- Phase 0：**COMPLETE**
- Phase 1：**COMPLETE**
- Phase 2：**IN PROGRESS（2A COMPLETE；2B IN PROGRESS）**
- Phase 2A：**COMPLETE**
- Phase 2A1：**COMPLETE**
- Phase 2A2：**COMPLETE**
- Phase 2B：**IN PROGRESS**
- Phase 2B1：**COMPLETE**
- Phase 2B2：**COMPLETE**
- Phase 2B3：**COMPLETE**
- Phase 2B3A：**COMPLETE**
- Phase 2B3B：**COMPLETE**
- Phase 2B3C：**COMPLETE**
- Phase 2B3D：**COMPLETE**
- Phase 2B3E：**COMPLETE**
- Phase 2B4：**IN PROGRESS**
- Phase 2B4A：**COMPLETE**
- Phase 2B4B：**COMPLETE**
- Phase 2B4C0：**COMPLETE**
- Phase 2B4C1：**COMPLETE**
- Phase 2B4C2：**PENDING**

本 ChatGPT Project 自此作为 CCB Desktop 的长期控制中心。旧建项会话仅作为历史参考，不再维护 CURRENT 状态。

## Declared / validated Desktop baseline

- repo: `SaltyFishOTL/ChatChatBar`
- branch: `master`
- version: `1.3.49`
- commit: `6b1817cd2dc65e6509e6ae350bef1a8e1a1250de`
- validation status: **PASS**

## Currently observed upstream

- repo: `SaltyFishOTL/ChatChatBar`
- branch: `master`
- version: `1.3.49`
- commit: `6b1817cd2dc65e6509e6ae350bef1a8e1a1250de`
- commits ahead of baseline: 0
- changed files from baseline: 0
- upstream drift: **NONE**
- Desktop compatibility: **VALIDATED**

validated baseline 与 observed upstream 仍须分别报告。未来 upstream 再次前进时，observation 不会自动更新正式 baseline；必须重新完成 changed-file classification、高风险审查、parity impact assessment 和 compatibility validation。

## Fork

- repo: `MisakaPiano/ChatChatBar-Desktop`
- `master`：`6b1817cd2dc65e6509e6ae350bef1a8e1a1250de`，已验证与 upstream baseline 同 SHA，只作为 upstream mirror
- `desktop`：Desktop 集成主线；upstream 1.3.49 sync 已完成集成
- `sync/1.3.49`：已完成 upstream source merge、验证、文档 finalization 与 `desktop` integration
- `feature/phase1-desktop-bootstrap`：首个 Desktop 实现分支，已通过 review 并完成集成，分支保留
- `feature/phase2a-shared-storage`：Phase 2A1 shared storage extraction，已通过 review、完整回归验证与 `desktop` integration，分支保留
- `feature/phase2a2-storage-edge-tests`：Phase 2A2 storage parity tests，已通过 review、完整回归验证与 `desktop` integration，分支保留
- `feature/phase2b1-data-snapshot`：Phase 2B1 app data snapshot foundation，已通过 Project review 并完成 `desktop` integration，分支保留
- `feature/phase2b2-snapshot-restore`：Phase 2B2 transactional snapshot restore foundation，已通过 Project review 并完成 `desktop` integration，分支保留
- `feature/phase2b3a-backup-policy`：Phase 2B3A backup provenance / automatic backup policy foundation，已通过 Project review 并完成 `desktop` integration，分支保留
- `feature/phase2b3b-safe-pruning`：Phase 2B3B safe automatic backup retention / pruning，已通过 Project review 并完成 `desktop` integration，分支保留
- `feature/phase2b3c-backup-execution`：Phase 2B3C automatic backup execution foundation，已通过 Project review 并完成 `desktop` integration，分支保留
- `feature/phase2b3d-desktop-backup-scheduler`：Phase 2B3D Desktop automatic backup scheduling adapter，已通过 Project review 并完成 `desktop` integration，分支保留
- `feature/phase2b3e-backup-settings-runtime`：Phase 2B3E Desktop backup settings/runtime 与 startup/shutdown integration，已通过 Project review 并完成 `desktop` integration，分支保留
- `feature/phase2b4a-data-root-bootstrap`：Phase 2B4A Desktop data-root bootstrap authority，已通过 Project review 并完成 `desktop` integration，分支保留
- `feature/phase2b4b-portable-root-resolution`：Phase 2B4B ApplicationHome authority 与 Portable root resolution，已通过 implementation、packaged runtime 与 manual UI acceptance，完成本次 finalization 后集成，分支保留
- `feature/phase2b4c1-data-operation-coordinator`：Phase 2B4C1 process-local data-operation coordinator，已通过 implementation 与 lifecycle ownership hardening review，完成本次 finalization 后集成，分支保留

## 首次接管复核

- ChatGPT Project takeover: **PASS**
- Codex first read-only audit: **PASS WITH DOC CORRECTIONS**
- schema check for declared baseline: **PASS**
- architecture direction: **PASS**
- architecture/docs factual precision: **CORRECTED**
- official baseline Skill inventory: **PASS (20/20, baseline 1.3.49)**
- current Skill inventory drift: **NONE (20)**
- 1.3.49 changed Skill/source consistency: **PASS**
- Phase 0 docs correction: **COMPLETE**

## 1.3.49 sync validation

- impact audit: **PASS**
- user Prompt decision: **ACCEPTED**
- source merge: **PASS** (`cd49ec93e044d0278d66cb2b78991d6e62c61f8c`)
- source integrity: **PASS**
- `:app:compileDebugKotlin`: **PASS**
- `:app:testDebugUnitTest`: **PASS**（1141 tests，0 failures）
- schemas: **UNCHANGED**
- architecture blocker: **NONE**
- Prompt 1.3.49 semantics: fixed prefix + replaceable middle + fixed suffix；`{{original}}` = default middle

## 已确认 schema

- CharacterCardPackage：9，读取 3..9
- FormatCardPackage：2，读取 1..2
- WorldBookPackage：1
- `.cbsave` package：8；legacy 1–7 保留兼容路径

## 已确认架构

- Gradle 当前包含 Android `:app`、纯 Kotlin/JVM `:desktopApp` 与纯 Kotlin/JVM `:sharedCore`
- Kotlin 2.3.20
- JDK/JVM 17
- AGP 9.0.1
- compileSdk/targetSdk 36，minSdk 26
- core/business Entity persistence：`JsonFileStorage`
- no active Room/ObjectBox business DB identified
- auxiliary SQLite：NovelAI/Danbooru catalog、dictionary、completion indexes（`DanbooruTagCatalog`、`NovelAiBundledDictionary`、`RankedTagIndex` / `RankedTagIndexStore`）
- Desktop 目标：Windows-first Kotlin/JVM + Compose Desktop
- 不使用 browser/WebView runtime
- 初期不强制全 KMP
- Package / Entity / Prompt runtime 三层继续分离
- Prompt 真值以最终 serialized logical messages / transport request 为准

## Phase 1 Desktop bootstrap

- implementation status：**COMPLETE**
- module：`:desktopApp`
- package：`com.example.chatbar.desktop`
- UI runtime：Compose Desktop 1.10.3，Foundation/UI 原生窗口，无 browser/WebView
- Kotlin：2.3.20
- JVM：17
- entry：`application` + `Window`，标题 `ChatChatBar Desktop`
- data-root discovery：默认 `%LOCALAPPDATA%\ChatChatBarDesktop`；缺少 `LOCALAPPDATA` 时使用 JVM `user.home\AppData\Local\ChatChatBarDesktop`
- persistence behavior：仅解析并显示路径，不创建目录、不读写 Entity、不执行 migration
- native distribution：EXE / MSI smoke config 已加入；未配置签名、updater 或 installer UI
- `:desktopApp:compileKotlin`：**PASS**
- `:desktopApp:test`：**PASS**（2 tests，0 failures）
- `:app:compileDebugKotlin`：**PASS**
- `:app:testDebugUnitTest`：**PASS**（1141 tests，0 failures）
- manual GUI acceptance：Windows native window、title、bootstrap content、displayed data directory **PASS**
- `:desktopApp:run`：**BUILD SUCCESSFUL**，Desktop process clean exit **PASS**

## Phase 2A1 shared storage extraction

- branch：`feature/phase2a-shared-storage`
- implementation status：**COMPLETE**
- Project review：**PASS**
- extraction commit：`d37196ee199e5af4bfe34e8ccf74d3ff7f5c349b`
- `JsonFileStorage` 已移动到 `:sharedCore`；除构造入口改为 app data root `Path` 外，包名、类名与公共方法 API 保持不变
- Android wiring：`ChatBarApp` 传入 `filesDir.toPath()`，既有物理路径仍为 `filesDir/entities/...`
- Desktop wiring：`DesktopAppContainer` 以 `DesktopDataDirectory.resolve()` 构造 storage；仅构造不会创建目录或读写 Entity
- Portable Mode、backup、migration、Desktop business persistence：**NOT IMPLEMENTED**
- `:sharedCore:test`：**PASS**（9 tests，0 failures）
- `:desktopApp:compileKotlin`：**PASS**
- `:app:compileDebugKotlin`：**PASS**
- 受构造签名变化影响的 13 个 Android JVM 测试类：**PASS**
- full Android JVM regression：**PASS**（184 suites，1141 tests，0 failures，0 errors，0 skipped）
- Phase 2A1 foundation 本身不代表 JSON persistence / atomic writes 的整个 Desktop parity 已达到 EXACT；Phase 2A2 validation 结果见下节

## Phase 2A2 shared storage edge-case validation

- implementation status：**COMPLETE**
- Project review：**PASS**
- test commit：`b28614aebabb064926f3e46b3d3973bc6772a083`
- production behavior changed by Phase 2A2：**NO**
- new sharedCore tests：15
- sharedCore tests：**PASS**（24 tests，0 failures）
- Android JVM regression：**PASS**（184 suites，1141 tests，0 failures，0 errors，0 skipped）
- raw/uncached behavior、cache isolation、file-set signature、`replaceWhere`、producer validation：**COVERED**
- installation failure rollback：**DETERMINISTICALLY COVERED**
- restart persistence、read-side directory creation behavior：**COVERED**
- rollback restore failure branch：known/deferred test gap；not deterministically testable without introducing a new production seam；不是当前 implementation blocker
- Phase 2A 完成不直接把 JSON persistence、atomic writes 或 Desktop data directory 的整体 parity 提升为 EXACT/EQUIVALENT

## Phase 2B1 app data snapshot foundation

- implementation status：**COMPLETE**
- Project review：**PASS**
- implementation commit：`a1eb5709f36b22e59fff37dd3952dfedd4973f47`
- snapshot operations：create、list、validation
- snapshot root：`<appDataRoot>/backups/`
- installation：staging → validation → completed install
- snapshot format version：1；manifest 使用 root-relative paths，并记录 size 与 SHA-256
- `backups/` recursive exclusion、malformed snapshot isolation 与 source quiescence contract：**ESTABLISHED**
- snapshot suite：**PASS**（15 tests，0 failures）
- sharedCore tests：**PASS**（39 tests，0 failures）
- Android JVM regression：**PASS**（184 suites，1141 tests，0 failures，0 errors，0 skipped）
- symlink implementation rejects / does not follow links；当前 Windows 权限下无法创建可靠 symlink fixture。该 test coverage limitation 不是 implementation blocker
- deterministic copy-phase failure test：**DEFERRED**；without a new production filesystem seam 无法可靠触发，不是 implementation blocker
- restore：**NOT IMPLEMENTED**
- automatic backup scheduling：**NOT IMPLEMENTED**
- Portable Mode：**NOT IMPLEMENTED**
- migration/root switching：**NOT IMPLEMENTED**

## Phase 2B2 transactional snapshot restore foundation

- implementation status：**COMPLETE**
- Project review：**PASS**
- transactional restore commit：`62c2a21e10b72884fc58cc9a53f7a8deb247dd07`
- commit-point cleanup safety fix：`071ab83ffa631d0cfd088a7e86476d6bcdf9ee7e`
- restore transaction：selected snapshot validation → mandatory completed pre-restore safety snapshot → staging → active payload recovery → install → installed payload validation
- explicit restore commit point：pre-commit failures rollback；post-commit cleanup failure 不会 rollback 已提交的 restore
- incomplete rollback preserves recovery evidence：**ESTABLISHED**
- `SnapshotRestoreResult` 可报告 retained workspace 与 cleanup warning
- restore success 后 pre-restore safety snapshot 与 current `backups/` history 均保留
- empty snapshot replacement、reserved `backups/` protection 与 restart persistence：**VALIDATED**
- sharedCore tests：**PASS**（54 tests，0 failures）
- Android JVM regression：**PASS**（184 suites，1141 tests，0 failures，0 errors，0 skipped）
- install-stage-specific deterministic failure fixture、rollback-restore-failure deterministic fixture：**DEFERRED**；without a new production filesystem seam / race 无法可靠触发，不是 implementation blocker
- symlink fixture：当前 Windows 权限下不可用；不是 implementation blocker
- automatic backup scheduling：**NOT IMPLEMENTED**
- Portable Mode：**NOT IMPLEMENTED**
- migration/root switching：**NOT IMPLEMENTED**

## Phase 2B3A backup provenance and automatic backup policy

- implementation status：**COMPLETE**
- Project review：**PASS**
- implementation commit：`999d9a7ab698b1dbe5001fe33ec13595e9190c3e`
- snapshot formatVersion：**1（UNCHANGED）**
- optional purpose metadata：`MANUAL`、`PRE_RESTORE`、`AUTOMATIC`
- `createSnapshot()` default purpose：`MANUAL`
- restore-created safety snapshot：`PRE_RESTORE`
- legacy v1 compatibility：missing purpose → `MANUAL`；validation、listing 与 restore 保持兼容，manifest 不会被静默 rewrite
- legacy provenance limitation：purpose 字段引入前的 snapshot 无法可靠恢复历史 provenance；这包括旧 pre-restore safety snapshot，未来 retention 不得将其推断为 `AUTOMATIC`
- `AutomaticBackupPolicy`：只依据 valid `AUTOMATIC` snapshot 的最新 timestamp 与 minimum interval；manual、pre-restore 与 malformed snapshot 不刷新 interval
- clock rollback：保守判定 not eligible；exact minimum-interval boundary：eligible
- sharedCore tests：**PASS**（64 tests，0 failures）
- Android JVM regression：**PASS**（184 suites，1141 tests，0 failures，0 errors，0 skipped）
- completed snapshot deletion：**NOT IMPLEMENTED**
- retention/pruning：**NOT IMPLEMENTED**
- automatic execution/scheduling：**NOT IMPLEMENTED**
- Portable Mode：**NOT IMPLEMENTED**
- migration/root switching：**NOT IMPLEMENTED**

## Phase 2B3B safe automatic backup retention and pruning

- implementation status：**COMPLETE**
- Project review：**PASS**
- implementation commit：`f9c172334339edc880f21ab12ccf6cd359d7a019`
- retention scope：maximum-count only；只有 explicit valid `AUTOMATIC` snapshots 可被 pruning
- protected snapshots：`MANUAL`、`PRE_RESTORE`、legacy missing-purpose、malformed / unknown purpose
- deterministic ordering：keep newest by createdAt/name；oldest candidates first
- public deletion surface：`pruneAutomaticSnapshots(maximumCount)`
- deletion safety：candidate disk revalidation + no-follow/reparse-safe preflight + `QUARANTINE_THEN_DELETE`
- prune commit point：completed snapshot move 到 `.prune-*.tmp` 成功；completed snapshot 不直接 recursive delete
- post-commit cleanup failure：不 rollback，保留 quarantine residue，并返回 retained workspace / warning
- orphan `.prune-*` listing isolation：**ESTABLISHED**；automatic orphan cleanup：**NOT IMPLEMENTED**
- caller serialization contract：create / restore / prune 必须串行；当前 revalidation 不宣称消除任意外部并发 mutation 的最终 TOCTOU
- sharedCore tests：**PASS**（82 tests，0 failures）
- Android JVM regression：**PASS**（184 suites，1141 tests，0 failures，0 errors，0 skipped）
- Windows `NOSHARE_DELETE` move-failure fixture、DOS read-only cleanup-failure fixture：**PASS**
- symlink fixture：当前 Windows 权限下不可用；junction/reparse deterministic fixture 与 exact policy→revalidation mutation fixture：**DEFERRED**，不是 implementation blocker
- automatic execution/orchestration：**NOT IMPLEMENTED**
- background scheduling：**NOT IMPLEMENTED**
- Portable Mode：**NOT IMPLEMENTED**
- migration/root switching：**NOT IMPLEMENTED**

## Phase 2B3C automatic backup execution foundation

- implementation status：**COMPLETE**
- Project review：**PASS**
- implementation commit：`ce126f6660c06065a04cf105040af7e63bcceab7`
- protected-new-snapshot fix：`ced5c05f498bf0058c179ea7b5e1c90be1f8e2dc`
- execution API：synchronous single-run `executeAutomaticBackup(minimumInterval, maximumCount)`
- Clock authority：eligibility 与新 snapshot `createdAt` 使用同一 service Clock
- execution order：policy → create completed `AUTOMATIC` snapshot → prune
- skipped result：不 create / prune；creation failure：不 prune
- pruning pre-commit failure：保留新 snapshot，并通过 structured execution failure 暴露
- pruning post-commit cleanup warning：仍为 successful `Created` result
- same-execution retention：新 snapshot 显式受保护并占用一个 retention slot，包括 equal timestamp / `Duration.ZERO`
- public standalone pruning semantics：**UNCHANGED**
- caller serialization contract：create / restore / prune / execute 必须串行
- sharedCore tests：**PASS**（92 tests，0 failures）
- Android JVM regression：**PASS**（184 suites，1141 tests，0 failures，0 errors，0 skipped）
- Desktop / Android compile：**PASS**
- Phase 2B3C 完成时 background scheduler 尚未实现；已在 Phase 2B3D 建立
- Phase 2B3C 完成时 startup hook 与 persisted Desktop backup settings 尚未实现；已在 Phase 2B3E 建立
- Portable Mode：**NOT IMPLEMENTED**
- migration/root switching：**NOT IMPLEMENTED**

## Phase 2B3D Desktop automatic backup scheduling adapter

- implementation status：**COMPLETE**
- Project review：**PASS**
- implementation commit：`08646ea3b1370054b7633e5669129638f0393bc9`
- event-sink robustness fix：`0e19d14a26004b498f90db79d7fba831906d5792`
- runtime capability：explicit `start` / `stop` / `close`；construction 不启动 scheduler，也不产生 filesystem writes
- schedule behavior：首次检查立即执行，随后按 check interval 串行运行；scheduled executions 不重叠
- failure behavior：per-run execution failure 产生 `Failed` 并在下一 tick retry；observer / event reporting failure 不终止 loop
- stop / close behavior：允许 in-flight synchronous filesystem transaction 安全完成，不强制中断
- serialization boundary：scheduler 只串行化自身 runs；future manual create / restore / prune 必须与 scheduler 协调
- `DesktopAppContainer`：构造 snapshot service 与 scheduling capability，但不自动启动
- Phase 2B3D 完成时 `Main.kt` 尚未接入；startup activation 已在 Phase 2B3E 完成
- user-facing settings UI：**NOT IMPLEMENTED**
- `:desktopApp:test`：**PASS**（13 tests，0 failures）
- `:sharedCore:test`：**PASS**（92 tests，0 failures）
- Android JVM regression：**PASS**（184 suites，1141 tests，0 failures，0 errors，0 skipped）
- Portable Mode：**NOT IMPLEMENTED**
- migration/root switching：**NOT IMPLEMENTED**

## Phase 2B3E automatic backup settings and startup integration

- implementation status：**COMPLETE**
- Project review：**PASS**
- settings/runtime commit：`7f202359887c8cb5271b50cd8e854a653a49e033`
- settings JSON robustness fix：`bce51e64cf54bc2567e33fc9f0c5284f43ad2932`
- Desktop lifecycle integration：`ce0ba7acfc79b54c5878c7086d07ffde6a99b48c`
- persisted settings：`<appDataRoot>/desktop-settings.json`；formatVersion：1
- Project defaults：`enabled = false`、minimum backup interval 24 hours、maximum automatic snapshots 7、scheduler check interval 1 hour
- missing settings：只使用 in-memory defaults，不创建 app-data root 或 settings file，也不启动 scheduler
- corrupt / invalid / unsupported settings：保留原文件，不阻止 Desktop launch，automatic backup 保持停止
- unknown root / nested fields：explicit save 后保留
- settings write：sibling temporary file → flush/close → atomic replace；只对 `AtomicMoveNotSupportedException` fallback ordinary replace
- settings reconfiguration：stop scheduler → await in-flight backup → atomically save settings → enabled 时以新 schedule restart
- failed settings save：prior file 保持完整，并 best-effort restart previous working schedule
- `DesktopAutomaticBackupRuntime`：拥有 settings initialization、scheduler start/stop/reconfiguration 与 runtime `StateFlow`
- runtime state：settings load state/failure、effective settings、scheduler-running state、latest backup event、latest execution failure、cleanup warnings、operation failure
- runtime-local mutex：串行 initialize / apply / close；data-operation admission 已在 Phase 2B4C1 由独立 process-local coordinator 建立
- future manual snapshot / restore / prune 与 Desktop business writers 必须通过 shared operation gate 或 coordinated adapter 参与
- container construction：zero-write，不隐式 initialize 或 start runtime
- Desktop startup：resolve appDataRoot → construct `DesktopAppContainer` → initialize runtime → enter Compose application
- Compose lifecycle：`exitProcessOnExit = false`；window `exitApplication()` → Compose returns → runtime close → scheduler awaits in-flight synchronous snapshot transaction → `main` returns naturally
- force-cancel / `Thread.interrupt` / `exitProcess`：**NOT USED**
- automatic backup startup integration：**COMPLETE**；仅 persisted settings `enabled = true` 时启动，default 仍 disabled
- settings UI / Task Center：**NOT IMPLEMENTED**
- `:desktopApp:test`：**PASS**（5 suites，44 tests，0 failures，0 errors，0 skipped）
- `:sharedCore:test`：**PASS**（8 suites，92 tests，0 failures，0 errors，0 skipped）
- Android JVM regression：**PASS**（184 suites，1141 tests，0 failures，0 errors，0 skipped）
- Desktop / Android compile：**PASS**
- `git diff --check`：**PASS**
- Windows symlink fixture limitation：当前权限下仍不可可靠创建；该 coverage limitation 非 blocker

## Phase 2B4A data-root bootstrap authority

- implementation status：**COMPLETE**
- Project review：**PASS**
- implementation commit：`412e04422a2b65f14d280f38a3e44a8830226d78`
- Phase 2B4A completion-time precedence：optional explicit CLI override supplied by caller → external bootstrap selection → legacy/default LOCALAPPDATA root；Phase 2B4B 已在 CLI 与 bootstrap 之间加入 Portable authority
- actual command-line argument parsing：**DEFERRED**；CLI override 为 temporary，且不自动持久化
- external bootstrap：`%LOCALAPPDATA%\ChatChatBarDesktop.bootstrap.json`；缺少 `LOCALAPPDATA` 时回退到 `<user.home>\AppData\Local\ChatChatBarDesktop.bootstrap.json`
- bootstrap authority deliberately 位于 selected `appDataRoot` 外部，避免 bootstrap paradox 以及 snapshot/restore 错误改变根目录选择
- formatVersion：1；modes：`DEFAULT`、`CUSTOM`
- root provenance：`CLI_OVERRIDE`、`BOOTSTRAP_DEFAULT`、`BOOTSTRAP_CUSTOM`、`MISSING_BOOTSTRAP_DEFAULT`
- missing bootstrap：解析 exact legacy/default root；zero-write，不创建 bootstrap 或 selected root
- corrupt / invalid / unsupported bootstrap：structured failure；不 silent fallback，也不针对 unintended default root 构造 `DesktopAppContainer`
- `CUSTOM`：只接受 normalized absolute path；load / resolution 不创建 custom root
- persistence：sibling temporary file → full write/flush/close → atomic move + replace；仅在 `AtomicMoveNotSupportedException` 时 fallback ordinary replace
- failed replace：prior valid bootstrap 保持完整；unknown root fields 在 explicit save 后保留
- `Main.kt`：在构造 `DesktopAppContainer` 前解析 root authority；broken bootstrap 在 container construction 前产生 explicit bootstrap failure
- successful root resolution 后，`desktop-settings.json` 与 automatic-backup runtime 行为保持不变；default appDataRoot 未改变
- developer KDoc：以中文解释 + standard English technical term 记录 authority-outside-root、bootstrap paradox、invalid `CUSTOM` no-fallback 与 apparent user-data-loss compatibility trap
- `:desktopApp:test`：**PASS**（6 suites，57 tests，0 failures，0 errors，0 skipped）
- `:sharedCore:test`：**PASS**（8 suites，92 tests，0 failures，0 errors，0 skipped）
- Android JVM regression：**PASS**（184 suites，1141 tests，0 failures，0 errors，0 skipped）
- Desktop / Android compile：**PASS**
- `git diff --check`：**PASS**
- Windows symlink fixture：当前权限下仍为 permission-dependent；该 limitation 非 blocker
- Phase 2B4A 完成时 Portable marker 尚未实现；已在 Phase 2B4B 建立。process-local coordinator 已在 Phase 2B4C1 建立；actual CLI parser、cross-process ownership、root migration、root-switch UI：**NOT IMPLEMENTED**

## Phase 2B4B Portable root resolution

- implementation status：**COMPLETE**
- Project review / packaged verification / manual acceptance：**PASS**
- implementation commit：`bb7ff31047af5f705a8ac5e12a9001d04e75e63e`
- `ApplicationHome` result：`Available` / `Unavailable` / `Failure`；provenance：`PACKAGED_LAUNCHER_PROPERTY` / `INJECTED_DEVELOPMENT_TEST`；unavailable reason 区分 property absent 与 unexpanded jpackage macro；不使用 `user.dir` fallback
- packaged launcher property：`-Dchatbar.desktop.applicationHome=$ROOTDIR`
- final precedence：explicit CLI temporary override → Portable → OS-local bootstrap → default LOCALAPPDATA；first version 不使用 environment-variable root override
- Portable contract：`<ApplicationHome>/portable.flag`，UTF-8 exact token `CCB_DESKTOP_PORTABLE_V1`；data root 为 existing `<ApplicationHome>/UserData/`
- pure resolver：zero-write；不创建 marker、`UserData/` 或 probe
- activation validator：在 `UserData/` 内执行 unique temporary write → flush/close → delete probe；成功后无 residue
- authority invariant：valid marker 一旦声明 Portable authority，missing / invalid / unusable `UserData/` 必须 explicit failure，绝不 fallback 到 bootstrap/default root
- relocatability：Portable paths 不持久化 absolute `ApplicationHome`；完整 application image 移动后从新 root 重新解析
- `:desktopApp:test`：**PASS**（79 tests，0 failures）
- `:sharedCore:test`：**PASS**（92 tests，0 failures）
- Android JVM regression：**PASS**（1141 tests，0 failures）
- Desktop / Android compile：**PASS**
- `git diff --check`：**PASS**
- real packaged runtime：**PASS**；jpackage launcher runtime 将 `$ROOTDIR` 展开为 actual application-image root，且与 `user.dir`、packaged `java.home` 相互独立
- whole-image relocation：**PASS**；移动完整 image 后 `ApplicationHome` 跟随新位置
- positive Portable packaged smoke：**PASS**；write probe 无 residue，未创建 isolated default root/bootstrap
- invalid Portable no-fallback smoke：**PASS**；`UserData` 为 ordinary file 时进程以 code 1 失败，未 fallback 到 LOCALAPPDATA
- user manual packaged UI acceptance：**PASS**；窗口正常打开，显示 relocated image 的 `<ApplicationHome>/UserData`
- symlink fixture 仍受当前 Windows permissions 限制；junction/reparse detection 受 public JDK 17 NIO 能力边界约束，均为已知非阻塞 coverage limitation
- actual CLI parser、Portable ZIP release task、cross-process ownership、data migration、root-switch UI、old-root deletion：**NOT IMPLEMENTED**；process-local coordinator 已在 Phase 2B4C1 建立

## Phase 2B4C1 process-local data-operation coordination

- implementation status：**COMPLETE**
- Project review：**PASS**
- branch：`feature/phase2b4c1-data-operation-coordinator`
- implementation commit：`1a535229c2a201f496b93e9a98362ff8037cafde`
- lifecycle ownership hardening：`38c8587080dbf715e60348fc2af3554c09004f63`
- coordinator state model：`OPEN`、`MAINTENANCE_PENDING`、`EXCLUSIVE`、`RESTART_REQUIRED`、`CLOSING`、`CLOSED`
- shared seam：`AppDataOperationGate` 保持 platform-neutral；Android/default shared callers 使用 no-op gate
- `JsonFileStorage` ordering：gate → existing per-entity mutex → IO/filesystem operation；不同 Entity types 保留现有并发
- nested normal contract：same-gate coroutine-context identity marker 使 direct suspend、`withContext` 与 structured child work 幂等参与；detached work 不得借用 outer registration
- maintenance admission：第一个 exclusive waiter 到达后停止新的 unrelated normal admission，等待 existing operations drain；exclusive waiters FIFO，cancellation / exception cleanup 不泄漏 state
- snapshot facade：Desktop create / restore / prune / automatic execution 使用 suspend coordinated facade；raw `AppDataSnapshotService` 不从 container public 暴露
- restore invariant：successful restore 与 incomplete rollback seal `RESTART_REQUIRED`；cleanup-warning success 同样 seal，pre-mutation / rollback-complete failure 不 seal
- runtime maintenance：pause → scheduler stop/join → coordinator exclusive；paused / restart-required 时拒绝 settings mutation，resume 不得绕过 restart seal
- scheduler integration：execution callback 为 suspend，并通过 coordinated automatic execution；既有 immediate-first-run、retry、event 与 safe stop semantics 保持
- lifecycle ownership hardening：container 不 public expose mutable settings store / scheduler bypass surface，只暴露 lifecycle-owning runtime 与 coordinated services
- process boundary：coordinator 只提供 process-local admission；cross-process ownership 不属于其职责
- validation：`:sharedCore:test` **9 suites / 94 tests PASS**；`:desktopApp:test` **10 suites / 108 tests PASS**；Android JVM **184 suites / 1141 tests PASS**；Desktop / Android compile 与 `git diff --check`：**PASS**
- review hardening validation：`:desktopApp:test` **10 suites / 108 tests PASS**、Desktop compile 与 `git diff --check`：**PASS**；因未改 sharedCore / Android source，未重复 Android full regression
- Phase 2B4C2 planned direction：one writable Desktop process per selected `appDataRoot`；不同 roots 可由不同 processes 使用；首个 Windows implementation 预计使用 JDK `FileLock`，并作为独立 lifetime guard 与 process-local coordinator 分离
- cross-process ownership、migration、root switching：**NOT IMPLEMENTED**

## 文档真源

- GitHub `MisakaPiano/ChatChatBar-Desktop` 的 `desktop` 分支是 CURRENT 真源。
- 本 Project Sources 用于提供 Bootstrap 详细内容与历史快照；与 GitHub CURRENT 冲突时，以 GitHub `desktop` 为准。
- 本 Project 负责长期架构、parity、upstream sync、Codex 任务规格与 diff review。
- Codex 以 GitHub 仓库真实工作树、commit SHA / PR 为实现交接点。

## 当前未完成

- Phase 2B4C2 per-root process ownership / cross-process single writer
- data-root switching
- safe migration
- Desktop business persistence

## 授权与发布依据

- factual observation：upstream repository 尚未观察到标准 `LICENSE` 文件。
- authorization status：upstream 作者已直接授权用户开发与发布 CCB Desktop；该直接授权是项目 development / public release 的授权依据。
- 标准 `LICENSE` 元数据缺失本身不再是 development / public-release blocker。
- Release packaging 前应保留并确认原始授权记录；不据此虚构授权原文、日期、URL、截图或额外法律条款，也不添加或伪造 license file。

## 当前风险

1. QQ voice 依赖 Android Accessibility；Desktop 等位能力待单独调查。
2. Android 图像/音频/secret/background/update 等平台代码需要 adapter。
3. 未来 upstream Prompt diff 仍须按高风险路径审查最终 logical messages / transport；不得维护 Desktop Prompt fork。
4. Skill inventory 数量一致不自动证明内容兼容；每次 upstream sync 仍须比较并核对源码。
5. NovelAI/Danbooru 辅助 SQLite 需要单独的平台边界，但不改变核心 Entity 的 JSON storage 路线。

## 下一项任务

**Phase 2B4C2 — Per-root Process Ownership / Cross-process Single Writer**

Phase 2B4A、Phase 2B4B 与 Phase 2B4C1 已完成。Phase 2B4C2 尚未实现；下一步将建立 selected `appDataRoot` 的 cross-process single-writer ownership。本次 finalization 不实现 C2、migration 或 root switching。
