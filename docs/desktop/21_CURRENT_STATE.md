## 2026-10-28 最新用户调度：先完成登记小修，暂停新大phase

用户已明确授权按[208小修总账](208_REGISTERED_SMALL_FIXES_EXECUTION_LEDGER.md)的SF01–SF14顺序实现、完成必要测试后发布更新。暂不推进新的大phase；原路线下一个P12 AI Authoring / Repair继续排在本批之后。旧需求“仅登记／暂不授权”是历史调度，本轮对应项目已有新的直接实施授权。1208公开和限定验收保持，剩余旧任务不删除。

## 2026-10-28 当前路线（用户最新直接指令）

以 [204 路线切换](204_STUDIO_NSIS_ROUTE_SWITCH_20261028.md) 为当前安装与引继入口。用户目标优先于审查助手建议：Studio式 NSIS 为 Preview 主线；MSI 无限期搁置，仅保留最小有价值档案并优先实际回收空间。**虚拟机若不再需要则彻底退役，不默认保留任何一台。** A 接管源码，B 回收废弃大产物，原执行者交接后停止。旧“继续MSI/完整失败VM保留/保持原执行者”安排已被本轮明确调整；其他 P11、数据与 Prompt 边界继续。新 NSIS 尚未验收，旧失败不改为 PASS。

执行分别见 [A 主线](205_NSIS_PRIMARY_HANDOFF_20261028.md)、[B 回收](206_MSI_RECLAIM_HANDOFF_20261028.md)。这次不增加 Project 审批，不重开无关审计；已有有效证据复用。云端 Project 文件同步须取得真实回执，不能用本地副本冒充。

---

# CCB Desktop Current State

最新实施起点与全部未交付项以活动分支 [207 持续总账](207_CONTINUOUS_START_AND_DELIVERY_LEDGER.md) 为查询入口；本文件下方保留各历史截点，不以旧状态覆盖新回执。

用户已于 2026-10-25 明确把**合并式迁移纳入当前 P11→Preview 任务**：保留电脑已有数据，将手机数据合并进来，冲突由用户选择。现有执行者在本轮连续实现、验证并推进到 Preview；不另开审计、合同或 Project 审批门槛。详见 [203](203_P11_TO_PUBLIC_PREVIEW_CONTINUOUS_CODEX_TASK.md)。

更新时间：2026-10-25 JST。本次为权限边界与文档同步，不是新的功能验收或 Preview 发布。

当前开发代码和任务以活动 `feature/phase8-fish-audio` 为准；`desktop` 同步这些文档不代表功能代码已集成。

## 当前执行方式

[用户权限边界](USER_AUTHORITY_AND_PROJECT_REVIEW_BOUNDARY.md)与[开发原则](01_PROJECT_INSTRUCTIONS.md)已按用户要求统一。现有 Codex 执行者按已有授权连续推进 [203 P11→Preview](203_P11_TO_PUBLIC_PREVIEW_CONTINUOUS_CODEX_TASK.md)；Project 只对已完成实现做阶段性审查，未经用户采纳的新限制不能成为全局停止或逐 phase 审批。

旧 LR 合同、未审计、未签字 0/7 不构成开发冻结；真实缺陷定位到字段/类型/调用链，只影响依赖动作。必要人工验收由 Codex 直接交用户。授权与事实分开，诊断不自动扩大为真实数据/凭据写入、合并或发布权限；已有许可不重复确认。

## 工程事实与剩余工作

- 2026-10-27 用户选定 MSI operation1 修订，替换组件库存/普遍客户端排他准入；[P0 源码及测试候选证据](evidence/windows-msi-operation-contract-20261027/README.md)记录有限资源合同、最终目标准入和回滚门禁。新生产 guard 无全组件/客户端枚举导入；61 项限定测试通过不等于真实安装通过。接续先生成全新 synthetic 包并在独立 Win10 19045 guest 完成真实首装，保留1102失败现场；随后连续完成生命周期、实际写入后回滚和有限旧包兼容。普通 Desktop Shortcut 适配及新设计原生验收尚未完成，不因 Project 尚未复核停止独立工程。
- 首轮完整1110候选在交付前的 restricted Installer 会话检查中暴露了 costing 临时表/成本记录误判，已保留 RED 并暂扣该包。[会话适配修复](evidence/windows-msi-operation-contract-20261027/SESSION_ADAPTER_FIX.md)按精确内部结构保留原资源严格校验，并验证实际子目录目标逃逸仍拒绝；该检查点实际复跑17+38项通过。后续已冻结完整1112首装包及1113升级包，尚无新设计完整原生安装 PASS；[升级准备证据](evidence/windows-msi-operation-contract-20261027/UPGRADE_1112_1113_PREPARATION.md)不能替代 DL1 实际结果。
- 2026-10-27 DD1 继续修复[普通包快捷方式资源图](evidence/windows-msi-operation-contract-20261027/SHORTCUT_RESOURCE_GRAPH_CHECKPOINT.md)：匹配实际 JDK17 条件组件和独立菜单目录，保留严格的固定链接与删除目标边界。最终37项限定测试通过；普通包的条件组件维护、原生目标/已登记所有权和实际生命周期仍未实现/验收，现有拒绝不放宽。已发1112及准备1113哈希不变；本轮没有运行安装器、虚拟机或真实资料操作。

- 正式 validated upstream baseline 仍为 ChatBar 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`；具体观察及构建配置查 10_UPSTREAM_BASELINE.json。本次不提升 baseline 或 parity。
- D-038 采用的官方 1.4.5 backup 参考为 `1198a13cf3008de1358b8a69aa5e40b64797c8d9`；用户实际 code86/v2 输入与其分开，不冒称生产 APK 身份或新版完整兼容。
- 已有 P8–P10、SaveSlot、备份扫描/准备、凭据及恢复实验按各自已提交证据范围引用，不等同于完整 P11 正式恢复已完成。
- 最近已有 LR6 结论查 [200](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/feature/phase8-fish-audio/docs/desktop/200_P11_LR6_BOUNDED_PROJECT_ACCEPTANCE.md)：源码/设计和局部工程证据有范围限定；原生 Windows/DPAPI/断电证据仍不能用 Python/JVM 结果代替。Broker/VM/LR7 是可选方法，不是后续实现必经研究阶段。
- 11 条 Entity 的泛化拒绝需要准确字段兼容诊断；权限报告已记录此前报告不足。108 条历史录音的成因与兼容策略必须区分已证实和推测。真实 JSON/ID/媒体和原始诊断仅在本地私有报告中，不进入 GitHub。
- 本次用户新增的合并式迁移已纳入当前任务，功能实现与验证仍待当前 Codex 完成，不能因文档同步宣称已经可用。
- 剩余交付集中于真实可用的 .cbbackup/.cbsave 导入导出/恢复/重启、受支持媒体与历史状态保全、受保护凭据适配，以及安装升级、更新发现、恢复诊断和 A–G 实际验证。
- 正式 Preview/完整 P11 和未覆盖真人步骤按实际证据更新；本次规则同步没有把任何 FAIL/NOT_TESTED 改成 PASS，没有发布 Preview。

## 接续与进度

接手只核对活动分支、HEAD、工作区、当前任务和相关增量；不重开已完成阶段，不清理其他执行者的源码或私有资料。报告已完成、当前工作、下一步、确切需用户动作。合并/发布执行实际已有授权；Project 不另设签字门槛。

## 证据索引与历史

以 13_FEATURE_PARITY.md、14_UPSTREAM_COMPAT.md、18_DECISIONS.md、20_RELEASE_CHECKLIST.md、93_PUBLIC_PREVIEW_POLICY.md、95_PREVIEW_FULL_APP_BACKUP_PORTABILITY_GATE.md 及相应原始 commit/证据为准。之前的累积时间线保存在 Git `98cb65e522605c9eb3cdc5713660d8b015d0d0ba` 的本文件；需要时定向查询，不把历史 NEXT/HOLD/NOT AUTHORIZED 当当前权限。

真实数据、存档、凭据及解包内容不上 GitHub（包括 Actions Secrets、日志和产物）；本地按已有授权使用。
