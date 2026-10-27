## 2026-10-28 当前路线（用户最新直接指令）

以 [204 路线切换](204_STUDIO_NSIS_ROUTE_SWITCH_20261028.md) 为当前安装与引继入口。用户目标优先于审查助手建议：Studio式 NSIS 为 Preview 主线；MSI 无限期搁置，仅保留最小有价值档案并优先实际回收空间。**虚拟机若不再需要则彻底退役，不默认保留任何一台。** A 接管源码，B 回收废弃大产物，原执行者交接后停止。旧“继续MSI/完整失败VM保留/保持原执行者”安排已被本轮明确调整；其他 P11、数据与 Prompt 边界继续。新 NSIS 尚未验收，旧失败不改为 PASS。

执行分别见 [A 主线](205_NSIS_PRIMARY_HANDOFF_20261028.md)、[B 回收](206_MSI_RECLAIM_HANDOFF_20261028.md)。这次不增加 Project 审批，不重开无关审计；已有有效证据复用。云端 Project 文件同步须取得真实回执，不能用本地副本冒充。

---

# CCB Desktop Project Source Map

用户已于 2026-10-25 明确把**合并式迁移纳入当前 P11→Preview 任务**：保留电脑已有数据，将手机数据合并进来，冲突由用户选择。现有执行者在本轮连续实现、验证并推进到 Preview；不另开审计、合同或 Project 审批门槛。详见 [203](203_P11_TO_PUBLIC_PREVIEW_CONTINUOUS_CODEX_TASK.md)。

更新：2026-10-25 JST。此文件负责定位资料；不产生执行权限。

## 开发与审查入口

1. [USER_AUTHORITY_AND_PROJECT_REVIEW_BOUNDARY.md](USER_AUTHORITY_AND_PROJECT_REVIEW_BOUNDARY.md)：用户授权与 Project 后置审查边界。
2. [01_PROJECT_INSTRUCTIONS.md](01_PROJECT_INSTRUCTIONS.md)：与 Project Settings 同步的开发原则。
3. [21_CURRENT_STATE.md](21_CURRENT_STATE.md)：工程状态与当前连续任务。
4. [203_P11_TO_PUBLIC_PREVIEW_CONTINUOUS_CODEX_TASK.md](203_P11_TO_PUBLIC_PREVIEW_CONTINUOUS_CODEX_TASK.md)：现有 P11→Preview 实施任务，内部 phase 不设 Project 前置批准。
5. [02_CHAT_START.md](02_CHAT_START.md)、[03_CODEX_START.md](03_CODEX_START.md)、[04_CODEX_TASK_TEMPLATE.md](04_CODEX_TASK_TEMPLATE.md)、[05_PROJECT_REVIEW_TEMPLATE.md](05_PROJECT_REVIEW_TEMPLATE.md)：接手、执行、可选任务描述及后置审查。
6. [137_STANDARD_CONVERSATION_HANDOFF.md](137_STANDARD_CONVERSATION_HANDOFF.md)：引继，不重复开审计或撤销授权。

## 技术真值与兼容性

- 官方对应 commit 的源码；先看官方 AGENTS.md 和 .agents/skills/chatbar-feature-map/SKILL.md，再读相关领域 Skill/源码。
- 实际活动 Fork 分支的实现；核对 Git HEAD，不把旧 desktop 或 Project Sources 当成当前源码。
- [10_UPSTREAM_BASELINE.json](10_UPSTREAM_BASELINE.json)：已验证 baseline 与观察到的漂移分开。
- [13_FEATURE_PARITY.md](13_FEATURE_PARITY.md)、[14_UPSTREAM_COMPAT.md](14_UPSTREAM_COMPAT.md)、[19_DATA_COMPAT.md](19_DATA_COMPAT.md)：功能与语义证据。
- [17_ROADMAP.md](17_ROADMAP.md)、[18_DECISIONS.md](18_DECISIONS.md)、[20_RELEASE_CHECKLIST.md](20_RELEASE_CHECKLIST.md)、[93_PUBLIC_PREVIEW_POLICY.md](93_PUBLIC_PREVIEW_POLICY.md)、[95_PREVIEW_FULL_APP_BACKUP_PORTABILITY_GATE.md](95_PREVIEW_FULL_APP_BACKUP_PORTABILITY_GATE.md)：目标、已有决定和实际验证内容；不是 Project 自行新增审批的入口。
- [23_CODEX_BUDGET.md](23_CODEX_BUDGET.md)、[24_CODEX_USAGE_EMPIRICAL_BASELINE.md](24_CODEX_USAGE_EMPIRICAL_BASELINE.md)：仅在需要时使用的计划参考，不是开工门槛。

权限依据用户直接指令，与技术事实排序分开。Package、Entity、最终 Prompt/request 始终区分，不永久硬编码历史 schemaVersion。

## 核心源码定位

JsonFileStorage、共享 Entity/Repository、CardTransferModels/CharacterCardTransferService、WorldBookEngine、PromptTemplates/PromptAssembler/ContextWindowManager、StreamingChatService、SaveSlotPackageStorage，以及 RAG、Memory、image、voice、moment、community、update 对应领域。Desktop 平台差异使用窄适配，优先共享官方 JVM-neutral 实现。

## 历史资料

既有 phase 审计、LR1–LR7、验收、旧交接和 Project Sources 只按其日期/commit 的事实范围引用。旧 NOT AUTHORIZED/HOLD/STOP、未签 Gate 或尚未审查，不自动成为后续禁令。此前冗长状态索引可在 Git `98cb65e522605c9eb3cdc5713660d8b015d0d0ba` 的本文件与 21_CURRENT_STATE.md 查看；历史证据不需复制或重新验收。

编辑器 REF 继续作为 UX/工作流参考，不作为上游 schema/Prompt 真值。真实原始数据报告仅保存在本地忽略目录，不进 Git 或可分享 Project 资料。
