## 2026-11-01 最新总目标：补齐范围、持续验收和打磨，发布正式 1.0

用户已明确以正式版为目标，见 [216 功能缺口与执行路线](216_DESKTOP_1_0_SCOPE_GAPS_AND_EXECUTION_ROUTE.md)。先收尾当前小修与必要验证／正常交付，再按依赖推进原 P12–P18 的实际缺口；提前实现的格式修复、RAG、语音、更新／安装／诊断等直接复用，不因旧 parity PENDING 重做，也不据局部完成宣布整个 phase 关闭。各批持续验收，范围覆盖后整体验收与 Beta 打磨，最终按实际授权发布 1.0；本轮不宣称已完成或发布正式版。

此前“暂停新大 phase”作为总体调度已被本目标调整，不是小修后继续无限期等待的理由。具体暂缓／排除和外部依赖仍按原用户决定：T23 等上游、SF10 与自由正文绑定不擅自恢复、社区功能排最后；缺一个人工选择只挂对应动作，其他独立工作继续。既有 Prompt／数据选择及真实发布边界不变。

## 2026-11-01 用户专项调度：社区排在功能队列最后

用户明确要求“社区功能排在最后再做，我们先做前面的”。保留 P14 作为历史任务编号，但不按编号在 P13 后启动；社区移到其他已计划功能之后，届时再核对官方服务开放情况和实际接入范围。当前小修优先，后续按上方正式1.0总目标继续；不新增“社区完成才能交付现有版本”的前提。

## 2026-10-28 用户调度：先完成登记小修，暂停新大phase

历史调度：小修仍优先，但其中对后续所有大phase的暂停已由上方2026-11-01正式1.0目标调整，不能继续作为无限期停止依据。

用户已明确授权按[208小修总账](208_REGISTERED_SMALL_FIXES_EXECUTION_LEDGER.md)的SF01–SF14顺序实现、完成必要测试后发布更新。暂不推进新的大phase；原路线下一个P12 AI Authoring / Repair继续排在本批之后。旧需求“仅登记／暂不授权”是历史调度，本轮对应项目已有新的直接实施授权。1208公开和限定验收保持，剩余旧任务不删除。

## 2026-10-28 当前路线（用户最新直接指令）

以 [204 路线切换](204_STUDIO_NSIS_ROUTE_SWITCH_20261028.md) 为当前安装与引继入口。用户目标优先于审查助手建议：Studio式 NSIS 为 Preview 主线；MSI 无限期搁置，仅保留最小有价值档案并优先实际回收空间。**虚拟机若不再需要则彻底退役，不默认保留任何一台。** A 接管源码，B 回收废弃大产物，原执行者交接后停止。旧“继续MSI/完整失败VM保留/保持原执行者”安排已被本轮明确调整；其他 P11、数据与 Prompt 边界继续。新 NSIS 尚未验收，旧失败不改为 PASS。

执行分别见 [A 主线](205_NSIS_PRIMARY_HANDOFF_20261028.md)、[B 回收](206_MSI_RECLAIM_HANDOFF_20261028.md)。这次不增加 Project 审批，不重开无关审计；已有有效证据复用。云端 Project 文件同步须取得真实回执，不能用本地副本冒充。

---

# CCB Desktop Development Roadmap

用户已于 2026-10-25 明确把**合并式迁移纳入当前 P11→Preview 任务**：保留电脑已有数据，将手机数据合并进来，冲突由用户选择。现有执行者在本轮连续实现、验证并推进到 Preview；不另开审计、合同或 Project 审批门槛。详见 [203](203_P11_TO_PUBLIC_PREVIEW_CONTINUOUS_CODEX_TASK.md)。

更新：2026-10-25 JST。按[用户权限边界](USER_AUTHORITY_AND_PROJECT_REVIEW_BOUNDARY.md)和[开发原则](01_PROJECT_INSTRUCTIONS.md)推进；phase 是工程组织，不是逐阶段审批。

## 当前交付

现有 Codex 执行者按已有授权连续完成 P11 与 Preview A–G，详见 [203](203_P11_TO_PUBLIC_PREVIEW_CONTINUOUS_CODEX_TASK.md)；Project 后置审查已完成实现。已有技术缺陷直接定位、修复和必要验证，真实阻断仅影响依赖动作；不再把 LR 研究、Project 合同冻结或未签 Gate 作为下一 phase 的开工条件。

优先落实实际 .cbbackup/.cbsave 可用互通、受支持数据/媒体/历史状态和凭据处理、恢复重启，以及 Windows 安装升级、D-041 更新发现和最小支持诊断。D-040 已有会话资源重绑定等已登记产品目标继续按对应决定和 parity 跟踪，不因本次流程更新被删除。

## 长期功能地图

| Phase | 目标 |
| --- | --- |
| 0 | Bootstrap / Audit |
| 1 | Desktop Bootstrap |
| 2 | Shared Storage Foundation |
| 3 | Entity、Package、导入导出 |
| 4 | Core Chat、Prompt、WorldBook |
| 5 | Model Runtime、真实聊天 |
| 6 | Desktop 主要 UI 与编辑器 |
| 7 | 图片资源与 NovelAI |
| 8 | Fish Audio、音频存储/播放/生成/聊天语音 |
| 9 | RAG |
| 10 | Long-Term Memory |
| 11 | SaveSlot、Session Lifecycle、完整备份合并迁移（电脑数据保留、手机数据加入、冲突用户选择） |
| Preview | 同一交付批次完成 P11 与 A–G 必要工程及实际验收；依用户已有发布授权交付 |
| 12 | AI Authoring / Repair |
| 13 | Moments |
| 15 | OS Integration、完整 updater/diagnostics/tray 等 |
| 16 | Upstream Automation |
| 17 | 真实 Preview 反馈驱动的 Beta Hardening |
| 18 | 1.0 完整范围与最终发布目标 |
| 14（功能队列最后，暂缓） | Community；保留历史编号，其他计划功能之后再评估和实施，不作为当前交付前提 |

Preview 不要求先做完 P12–P17，但不能把未实现能力说成完成。A–G 见 [93](93_PUBLIC_PREVIEW_POLICY.md)和[20](20_RELEASE_CHECKLIST.md)，全应用迁移见 [95](95_PREVIEW_FULL_APP_BACKUP_PORTABILITY_GATE.md)。

## 状态和接续

工程状态见 [21](21_CURRENT_STATE.md)，实际评级见 [13](13_FEATURE_PARITY.md)。本次文档更新不提升任何 baseline、parity 或验收结果。旧完整阶段明细、时间线与合同在 Git `98cb65e522605c9eb3cdc5713660d8b015d0d0ba` 的本文件及对应 phase 文档中保留；按其日期引用，旧权限限制不能覆盖用户后续指令。

必要真人验收由 Codex 直接交用户；已有授权不重复确认。未经用户采纳的 Project 新限制只能作为建议。
