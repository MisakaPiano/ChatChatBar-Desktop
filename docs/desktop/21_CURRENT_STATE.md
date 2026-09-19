# CCB Desktop Current State

更新时间：2026-09-20

## 当前阶段

**Phase 0 — Bootstrap / Audit**

Bootstrap 文档已经统一到 Fork `desktop` 分支；尚未开始 Desktop 业务代码开发。

本 ChatGPT Project 自此作为 CCB Desktop 的长期控制中心。旧建项会话仅作为历史参考，不再维护 CURRENT 状态。

## Declared / validated Desktop baseline

- repo: `SaltyFishOTL/ChatChatBar`
- branch: `master`
- version: `1.3.48`
- commit: `4c8c1eac51dc632bf9042468819cb86091b7660c`
- validation status: **PASS**

## Currently observed upstream

- repo: `SaltyFishOTL/ChatChatBar`
- branch: `master`
- version: `1.3.49`
- commit: `6b1817cd2dc65e6509e6ae350bef1a8e1a1250de`
- commits ahead of baseline: 2
- changed files: 13
- upstream drift: **DETECTED**
- Desktop compatibility: **NOT YET VALIDATED**

observed upstream 不自动成为 Desktop baseline。完成 changed-file classification、高风险 Prompt/Moments review、parity impact assessment 和 compatibility validation 前，不更新正式 baseline，也不宣称兼容 1.3.49。

## Fork

- repo: `MisakaPiano/ChatChatBar-Desktop`
- `master`：已验证与 upstream baseline 同 SHA，只作为 upstream mirror
- `desktop`：Desktop 集成主线；当前仅含 `docs/desktop/*` 文档变更，尚无 Desktop 业务实现

## 首次接管复核

- ChatGPT Project takeover: **PASS**
- Codex first read-only audit: **PASS WITH DOC CORRECTIONS**
- schema check for declared baseline: **PASS**
- architecture direction: **PASS**
- architecture/docs factual precision: **CORRECTED**
- official baseline Skill inventory: **PASS (20/20)**
- current Skill inventory drift: **NONE OBSERVED (20)**
- current Skill content drift: **PRESENT**
- Phase 0 docs correction: **COMPLETE**

## 已确认 schema

- CharacterCardPackage：9，读取 3..9
- FormatCardPackage：2，读取 1..2
- WorldBookPackage：1
- `.cbsave` package：8；legacy 1–7 保留兼容路径

## 已确认架构

- Android Gradle 当前只有 `:app`
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

## 文档真源

- GitHub `MisakaPiano/ChatChatBar-Desktop` 的 `desktop` 分支是 CURRENT 真源。
- 本 Project Sources 用于提供 Bootstrap 详细内容与历史快照；与 GitHub CURRENT 冲突时，以 GitHub `desktop` 为准。
- 本 Project 负责长期架构、parity、upstream sync、Codex 任务规格与 diff review。
- Codex 以 GitHub 仓库真实工作树、commit SHA / PR 为实现交接点。

## 当前未完成

- `:desktopApp` 尚未建立
- shared storage 尚未抽离
- Desktop runtime parity 尚未实现
- upstream 1.3.49 compatibility validation 尚未完成

## 当前风险

1. upstream 当前未检测到 License；公开发行前必须确认许可。
2. QQ voice 依赖 Android Accessibility；Desktop 等位能力待单独调查。
3. Android 图像/音频/secret/background/update 等平台代码需要 adapter。
4. upstream 1.3.49 已发生 13-file drift，含高风险 `PromptAssembler.kt` / `PromptTemplates.kt`；不得自动宣布兼容。
5. 当前 4 个 Skill 内容发生变化，inventory 数量未变不代表语义兼容。
6. NovelAI/Danbooru 辅助 SQLite 需要单独的平台边界，但不改变核心 Entity 的 JSON storage 路线。

## 下一项任务

**Upstream 1.3.49 sync/read-only impact audit。**

该审计需要完成：
1. baseline → observed upstream 的 13-file changed-file classification；
2. `PromptAssembler.kt` / `PromptTemplates.kt` 的高风险 Prompt 语义审查；
3. Moments、Character editor 与 4 个 changed Skills 的影响审查；
4. parity impact assessment 与 compatibility validation。

完成前：
- 不更新 `UPSTREAM_BASELINE` 正式 baseline；
- 不宣称 Desktop 兼容 1.3.49；
- 不同步 upstream 源码；
- 不开始 Phase 1。
