# CCB Desktop Current State

更新时间：2026-09-20

## 当前阶段

**Phase 0 — sync validation complete / ready for integration**

Bootstrap、首轮审计和 upstream 1.3.49 sync validation 已完成；尚未开始 Desktop 业务代码开发。

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
- `desktop`：Desktop 集成主线；当前仅含 `docs/desktop/*` 文档变更，尚无 Desktop 业务实现
- `sync/1.3.49`：已完成 upstream source merge、验证与 sync-finalization 文档，等待 Project review 后合入 `desktop`

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
- `sync/1.3.49` 尚待 Project review 并合入 `desktop`

## 当前风险

1. upstream 当前未检测到 License；公开发行前必须确认许可。
2. QQ voice 依赖 Android Accessibility；Desktop 等位能力待单独调查。
3. Android 图像/音频/secret/background/update 等平台代码需要 adapter。
4. 未来 upstream Prompt diff 仍须按高风险路径审查最终 logical messages / transport；不得维护 Desktop Prompt fork。
5. Skill inventory 数量一致不自动证明内容兼容；每次 upstream sync 仍须比较并核对源码。
6. NovelAI/Danbooru 辅助 SQLite 需要单独的平台边界，但不改变核心 Entity 的 JSON storage 路线。

## 下一项任务

**Project review sync-finalization commit → merge `sync/1.3.49` into `desktop`.**

合入 `desktop` 后，下一项实现任务是 **Phase 1 `:desktopApp` bootstrap**。Phase 1 尚未开始；在 Project review 与 sync merge 完成前不创建该模块。
