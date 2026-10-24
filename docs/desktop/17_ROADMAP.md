# CCB Desktop Development Roadmap

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
| 11 | SaveSlot、Session Lifecycle、完整备份迁移 |
| Preview | 同一交付批次完成 P11 与 A–G 必要工程及实际验收；依用户已有发布授权交付 |
| 12 | AI Authoring / Repair |
| 13 | Moments |
| 14 | Community |
| 15 | OS Integration、完整 updater/diagnostics/tray 等 |
| 16 | Upstream Automation |
| 17 | 真实 Preview 反馈驱动的 Beta Hardening |
| 18 | 1.0 完整范围与最终发布目标 |

Preview 不要求先做完 P12–P17，但不能把未实现能力说成完成。A–G 见 [93](93_PUBLIC_PREVIEW_POLICY.md)和[20](20_RELEASE_CHECKLIST.md)，全应用迁移见 [95](95_PREVIEW_FULL_APP_BACKUP_PORTABILITY_GATE.md)。

## 状态和接续

工程状态见 [21](21_CURRENT_STATE.md)，实际评级见 [13](13_FEATURE_PARITY.md)。本次文档更新不提升任何 baseline、parity 或验收结果。旧完整阶段明细、时间线与合同在 Git `98cb65e522605c9eb3cdc5713660d8b015d0d0ba` 的本文件及对应 phase 文档中保留；按其日期引用，旧权限限制不能覆盖用户后续指令。

必要真人验收由 Codex 直接交用户；已有授权不重复确认。未经用户采纳的 Project 新限制只能作为建议。
