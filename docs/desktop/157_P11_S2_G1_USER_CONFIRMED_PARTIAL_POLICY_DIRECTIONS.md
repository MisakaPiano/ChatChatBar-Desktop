# 157 — P11-S2-G1 User-Confirmed Partial Product Directions (R01/R02/R03)

**CURRENT 2026-10-23 JST — USER-CONFIRMED POLICY DIRECTION ONLY / R07 OPEN / S2 PRODUCTION NOT AUTHORIZED.**

## 1. 本次明确同意的产品原则

用户在 Project 对话中针对 [155 候选产品决策包](155_P11_S2_G1_DECISION_PACKAGE_AND_PORTABILITY_SAFETY.md) 和 [152 S2 readiness](152_P11_S2_PERSISTENT_PRESERVATION_READINESS_AND_DECISIONS.md) 明确表示基本同意，并逐条选择：

| 项目 | 用户确认的方向 | 具有约束力的安全界限 |
|---|---|---|
| **R01/R04 — C-A** | **P11 先采用 C-A**，原始 MomentPost/MomentTask/引用保全，不把导入 Task 作为可运行任务自动调度。 | 保全不等于官方 Android 可运行全量导入；不能仅重置 momentsEnabled/确认标志就把任务恢复到 live Scheduler；C-B/C-C 未获批准。 |
| **R02.1 原始数据** | **保留原始 JSON 字节及独立媒体文件副本**。 | 不以重新序列化丢字段、复用易被 live GC 删除的共享图片路径代替实际拥有权；具体正式 schema/事务尚未批准。 |
| **R02.2 删除政策** | 采用 **A：不自动删除封存内容**。 | 用户明确删除须另有可追踪确认/恢复机制；普通自动快照的滚动保留策略与封存本体删除不是同一项，本次未决定快照 retention 细则。 |
| **R02.3 空间不足** | **容量不足时安全拒绝新导入，已有数据不自动删除**。 | 确切容量数值、配额配置、峰值预算、流式/回滚资源计量需独立实验与新决策，未默许无限容量。 |
| **R03 旧版防写** | 采用 **A：不支持旧版程序直接对新版数据根进行不安全降级写入**。 | 优先保留可恢复的迁移前旧根/快照、设计强制隔离或拒写；capability 标记/新版 Launcher 提示无法约束旧 binary；旧版直开/Portable 等发布前实测依旧 BLOCKED。 |

这里是用户对**原则方向**的同意，不是批准已经拟议的精确文件布局、持久 schemaVersion、磁盘配额 MB/GB、Windows ACL、启动改造、root switch 实施步骤或任何可对真实数据根写入的生产 patch。

## 2. 明确保留的开放决策

- **R07 — 来源 `.cbbackup` 原档案是否自动托管，以及输入内外来凭据如何准入/迁移：PENDING USER DISCUSSION。** 用户要求进一步详细解释，而非表示已同意由程序不自动保存原包或持续整包拒绝作为最终产品政策。
- 讨论中应区分来源原始 ZIP 与 S2 已验证封存的 Moment JSON/媒体：用户批准 R02 对后者的保全，**不自动意味着**同意将可能含 API Key/Token 的整包原 ZIP 加入普通 Desktop data root、snapshot、可分享导出。也不自动同意/拒绝未来显式加密原包保险库。
- 现有安全准入在 R07 作出明确政策决定前**保持现状**：非空外来已知凭据整包拒绝，普通可分享 Desktop 导出不携带 SecretStore；C1 主动排除已知凭据及 C2 受 OS 保护的凭据迁移都需独立合同和授权。不可将 R07 的沉默视作允许复制/清除/导入 Token。
- R05/06/08/09/10 依原决策文档继续分别审计/授权。D-038 仍要求官方 1.4.5 全量应用迁移；不能用只读/有限排除直接宣称完整迁移 Gate 已过。

## 3. 下一关和禁止事项

- [154 G0 合成证据](154_P11_S2_G0_ROOT_BACKUP_RECOVERY_PROJECT_EVIDENCE_ACCEPTANCE.md) 已限定接受；[156 G0R test-only 删除/拥有权合同](156_P11_S2_G0R_OWNED_MEDIA_AND_CLEANUP_AUDIT_CODEX_CONTRACT.md) 可独立继续执行，仍不获生产写权限。
- S2-G1 完整产品合同 **PARTIAL POLICY DIRECTION / OPEN R07 + DETAILED CONTRACT GATES**。S2-G2 正式封存域、自动备份/快照/GC/Journal/Portable 改造、真实旧版降级试验、任何凭据迁移或 SecretStore 写入均未授权。
- S0 [147](147_P11_FULL_BACKUP_V1_V2_READONLY_S0_PROJECT_ACCEPTANCE.md)、S1 [151](151_P11_S1_MOMENTS_READONLY_GRAPH_PROJECT_ACCEPTANCE.md) 限定关闭不变；P11 全部 PENDING、D-038 BLOCKED、Post-P8 HOLD、Public Preview A–G 未签、validated upstream baseline 1.4.1 和 `desktop` 未合并状态不变。

本文件是 Project 对用户明确决定的 docs-only 登记，不执行 Gradle/JVM、GUI/Android、真实数据或任何代码修改。
