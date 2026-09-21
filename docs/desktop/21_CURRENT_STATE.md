# CCB Desktop Current State

更新时间：2026-09-22

## 当前阶段

**Phase 2 — IN PROGRESS**

Phase 0、Phase 1 与 upstream 1.3.49 integration 已完成。Phase 2A shared storage extraction 与 edge-case validation 已完成；Phase 2B1 app data snapshot foundation 与 Phase 2B2 transactional snapshot restore foundation 已完成，Phase 2 整体继续进行。

- Phase 0：**COMPLETE**
- Phase 1：**COMPLETE**
- Phase 2：**IN PROGRESS（2A COMPLETE；2B IN PROGRESS）**
- Phase 2A：**COMPLETE**
- Phase 2A1：**COMPLETE**
- Phase 2A2：**COMPLETE**
- Phase 2B：**IN PROGRESS**
- Phase 2B1：**COMPLETE**
- Phase 2B2：**COMPLETE**
- Phase 2B3：**NEXT**

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

## 文档真源

- GitHub `MisakaPiano/ChatChatBar-Desktop` 的 `desktop` 分支是 CURRENT 真源。
- 本 Project Sources 用于提供 Bootstrap 详细内容与历史快照；与 GitHub CURRENT 冲突时，以 GitHub `desktop` 为准。
- 本 Project 负责长期架构、parity、upstream sync、Codex 任务规格与 diff review。
- Codex 以 GitHub 仓库真实工作树、commit SHA / PR 为实现交接点。

## 当前未完成

- automatic backup policy/scheduling
- Portable Mode
- migration/root switching
- Desktop business persistence

## 当前风险

1. upstream 当前未检测到 License；公开发行前必须确认许可。
2. QQ voice 依赖 Android Accessibility；Desktop 等位能力待单独调查。
3. Android 图像/音频/secret/background/update 等平台代码需要 adapter。
4. 未来 upstream Prompt diff 仍须按高风险路径审查最终 logical messages / transport；不得维护 Desktop Prompt fork。
5. Skill inventory 数量一致不自动证明内容兼容；每次 upstream sync 仍须比较并核对源码。
6. NovelAI/Danbooru 辅助 SQLite 需要单独的平台边界，但不改变核心 Entity 的 JSON storage 路线。

## 下一项任务

**Phase 2B3 — Automatic Backup Policy Foundation**

Phase 2B3 尚未开始；下一轮建立 automatic backup policy foundation，不提前开始 Portable Mode 或 migration/root switching。
