# CCB Desktop Current State

更新时间：2026-09-19

## 当前阶段

**Phase 0 — Bootstrap / Audit**

Bootstrap 文档已经统一到 Fork `desktop` 分支；尚未开始 Desktop 业务代码开发。

本 ChatGPT Project 自此作为 CCB Desktop 的长期控制中心。旧建项会话仅作为历史参考，不再维护 CURRENT 状态。

## Upstream Baseline

- repo: `SaltyFishOTL/ChatChatBar`
- branch: `master`
- version: `1.3.48`
- commit: `4c8c1eac51dc632bf9042468819cb86091b7660c`
- baseline recheck: **PASS**

## Fork

- repo: `MisakaPiano/ChatChatBar-Desktop`
- `master`：已验证与 upstream baseline 同 SHA，只作为 upstream mirror
- `desktop`：Desktop 集成主线；当前仅含 `docs/desktop/*` 文档变更，尚无 Desktop 业务实现

## 首次接管复核

- new ChatGPT Project takeover: **PASS**
- upstream baseline recheck: **PASS**
- schema check: **PASS**
- architecture facts: **PASS**
- feature/skill drift: **NONE**
- docs reconciliation: **COMPLETE**

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
- persistence：`JsonFileStorage`
- no active SQL database
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

- Codex 尚未完成本地首次只读复核
- `:desktopApp` 尚未建立
- shared storage 尚未抽离
- 所有 Desktop runtime parity 功能仍未实现

## 当前风险

1. upstream 当前未检测到 License；公开发行前必须确认许可。
2. QQ voice 依赖 Android Accessibility；Desktop 等位能力待单独调查。
3. Android 图像/音频/secret/background/update 等平台代码需要 adapter。
4. upstream 可能继续变化；每次进入新开发任务前先比较 baseline 与 upstream/master。
5. 高风险路径（Package/Prompt/WorldBook/Model/SaveSlot/Memory/RAG/Image/Voice）发生 upstream diff 时必须人工语义审查。

## 下一项任务

**Codex read-only audit。**

Codex 按 `docs/desktop/03_CODEX_START.md`：
1. 读取 root `AGENTS.md`、官方 Feature Map、baseline/CURRENT/parity/compat/roadmap；
2. 报告本地 git status、HEAD、remotes 与 branch 关系；
3. fetch/比较 upstream baseline；
4. 复核 Gradle/module/build facts；
5. 复核 Package schema、JsonFileStorage、Prompt、WorldBook、Model、RAG/Memory、SaveSlot、NovelAI、Fish、Moments、Community、Shared Import、Update、QQ Voice；
6. 核对官方 20 Skill inventory；
7. 仅报告文档错误/遗漏和最小 Phase 1 建议，不修改任何文件。

Project 审查 Codex 报告通过后，才下发 Phase 1 `:desktopApp` bootstrap。
