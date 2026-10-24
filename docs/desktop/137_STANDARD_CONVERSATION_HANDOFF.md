# CCB Desktop 对话引继指南

用户已于 2026-10-25 明确把**合并式迁移纳入当前 P11→Preview 任务**：保留电脑已有数据，将手机数据合并进来，冲突由用户选择。现有执行者在本轮连续实现、验证并推进到 Preview；不另开审计、合同或 Project 审批门槛。详见 [203](203_P11_TO_PUBLIC_PREVIEW_CONTINUOUS_CODEX_TASK.md)。

更新：2026-10-25 JST。依据 [用户权限边界](USER_AUTHORITY_AND_PROJECT_REVIEW_BOUNDARY.md)和[开发原则](01_PROJECT_INSTRUCTIONS.md)。

## 引继目的

让现有工作继续。换 Project 对话不撤销已有授权，不要求 Codex 等新 Project 重新批准，不默认重开全项目审计、LR 研究或有效测试。保留原 Codex 执行者，不重复创建/下发同一任务。

## 最小交接内容

| 内容 | 记录 |
| --- | --- |
| 检查点 | 活动 branch/HEAD、实际查询时间、validated baseline |
| 当前任务 | 用户目标、实际已有授权、执行者、正在做什么 |
| 事实 | 已实现/已测试/Project 审查/真人验收/集成/发布分别记录 |
| 增量 | 新决定、相关 commit/证据，不复制全部历史 |
| 剩余 | 已知问题、真实阻断所影响的动作、下一步和必要用户动作 |

无法取到旧聊天只列确切缺口；不要把缺失摘要当成已拒绝授权。用户后续明确指令可以调整合同，旧 STOP/HOLD 不成为永久禁令。

## 新对话接手

先读权限边界和开发原则，再核对交接点、CURRENT 和相关增量。Project 后置审查真实实现，输出具体缺陷/证据/建议，未经用户采纳的新限制不能暂停或收窄已授权工作。诊断与修复本身也不自动授权真实数据/凭据写入、自动合并或公开发布；依用户实际许可执行，不重复确认已有许可。

如果原 Codex 正在执行，明确下一次报告接收者，继续其原任务。必要人工验收由 Codex 直接交用户，不由 Project 转批。出现真实技术阻断，只暂停其依赖动作，其他独立工作继续。

## 可复制接手文字

继续 CCB Desktop。先读 USER_AUTHORITY_AND_PROJECT_REVIEW_BOUNDARY.md、01_PROJECT_INSTRUCTIONS.md 和本轮交接；核对活动 branch/HEAD、CURRENT 和相关增量。保留已有用户授权与现有执行者，让 Codex 连续推进 P11 到 Preview。Project 只后置审查已完成实现，具体缺陷给出字段/类型/调用链和最小修复；不以未审计、未签 Gate 或旧 slice 合同新增审批。已有有效证据复用，必要真人步骤直接交用户，真实数据和凭据不上 GitHub。

历史交接和审计仅保留原日期/commit 的事实范围；不凭本指南宣称新功能通过验收或自动获得额外操作许可。
