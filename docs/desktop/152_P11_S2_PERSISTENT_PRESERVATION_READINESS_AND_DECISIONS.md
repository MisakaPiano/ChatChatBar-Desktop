# 152 — P11-S2 Persistent Moments Preservation: Readiness & Product Decisions

**CURRENT 2026-10-23 JST — DESIGN CONTROL / NO PRODUCTION IMPLEMENTATION AUTHORIZED.**

**CURRENT PARTIAL USER DIRECTION (2026-10-23 JST):** [157](157_P11_S2_G1_USER_CONFIRMED_PARTIAL_POLICY_DIRECTIONS.md) confirms R01/R04 C-A, R02 raw JSON/owned media/no automatic deletion/space fail-closed, and R03 no unsafe downgrade writes. This resolves the policy **direction** for those topics but does not approve a production schema, quota number, downgrade enforcement mechanism or S2-G2 modification. **R07 source archive custody and secrets is not yet approved**; the below original proposal table's “待用户决定” cells must be read as historical/pre-157 where relevant. S2 production remains BLOCKED.

## 1. S1 已验收不等于可以安全持久化

[151](151_P11_S1_MOMENTS_READONLY_GRAPH_PROJECT_ACCEPTANCE.md) 验证的仅是有界、只读的 Moments 原始数据及来源图。S2 实际向 Desktop root 写入新域会同时影响数据完整性、自动备份、手动快照、崩溃重开、root switch、Portable、GC、旧二进制直接运行与普通用户备份的秘密边界。任何遗漏都可能使 Preview 玩家未来升级/恢复丢失生产价值数据。不能只上线新目录，剩余拥有权以后再补。

权威：D-037/038/041、[93 Preview](93_PUBLIC_PREVIEW_POLICY.md)、[95 Whole-App Gate](95_PREVIEW_FULL_APP_BACKUP_PORTABILITY_GATE.md)、[原 S2 切片](evidence/p11-moments-production-contract-20261022/IMPLEMENTATION_AND_TEST_SLICES.md)、[封存与事务草案](evidence/p11-moments-production-contract-20261022/MOMENTS_STORAGE_RECOVERY_CONTRACT_DRAFT.md)、[Task 运行准入草案](evidence/p11-moments-production-contract-20261022/MOMENTS_TASK_ACTIVATION_CONTRACT_DRAFT.md)、[R01–R10 决策提案](evidence/p11-moments-production-contract-20261022/DECISIONS_REQUIRED.md)。这些是已有 Draft/Program Contract，不应被本文件擅自升级为产品授权。

## 2. Project 建议（提案，不是批准）

| 领域 | 推荐拟议政策 | 当前权限 / 必须解决的冲突 |
|---|---|---|
| R01/R04 Task | P11 的首版采用 **C-A 原 bytes 隔离保全**：Post/Task/引用不可变保存，不注入 live MomentRepository/Scheduler/AI；任何官方 Android 可运行回导另批。 | 产品 C-A 未获新持久域生产授权；仅原 S1 只读验证通过 |
| R02 存储 schema、配额、保留 | 建议独立版本化封存容器与原来源 Digest 命名空间；原记录和媒体有独立 ownership/可重建 manifest；容量不足 fail closed，保留外部原包和旧数据；默认不自动清理用户封存档，需明确删除/恢复策略。 | **待用户明确批准 schema / 总配额 / 保留与删除政策**；S1 报告不能作为 durable seal |
| R03 旧版防写 | 旧程序不认识新 capability marker。方案候选为隔离新 root 与受控迁移，并实测所有允许旧版的直接开根/降级、旧快照恢复。仅新版 Launcher 检查不是旧 exe 的 write barrier。 | **Release blocker**：旧二进制实际行为与可接受降级政策尚未证明；不能现在写共享旧根 |
| R07 外来档案与 Secrets | 外来的原 `.cbbackup` 可能携带已知秘密或任意正文秘密，不能因为 known key 扫描为空便放进普通备份/诊断。建议当前由用户自行保管原包，未授权长期管理加密/访问/保留前不建立来源原包库；非空已知凭据继续整包拒绝。 | 不开 C1/C2、不写 SecretStore、不修改普通 Desktop 导出排除 Secrets |
| R05 官方目标 | D-038 最低目标仍 **官方 1.4.5 v1 全应用迁移**；1.4.6/1.4.7 v2 可读属于来源格式研究，非自动扩展正式恢复及 baseline。 | 1.4.1 validated baseline 不变 |
| R06/R08/R09 | 严格 ADD_ONLY、C1 可选主动排除已知 Secrets、C2 DPAPI/账号绑定和跨域回滚分别设计/批准，不与 S2 混做。 | PENDING/BLOCKED |

## 3. 同一生产投产门禁的系统清单

1. **来源与内容**：按 `(sourceArchiveDigest,type,sourceId)` 或经批准等位方案区分不同来源同 ID；Post/Task/raw JSON/资源 bytes、三独立图片路径和缺失状态全部保全。未知字段/资源依赖与泄漏风险不许通过重新序列化丢弃。
2. **root authority / downgrade**：启动所有权、进程锁、独占维护租约、旧版程序直接打开、老快照或升级回退。新 root marker 不约束旧 binary；必须通过旧版实际测试或明确安全隔离/拒写政策。**未闭合前不投产。**
3. **snapshot/restore transaction**：新域纳入 `DesktopDataOperationCoordinator`、`DesktopStartupGate`、`DesktopFullBackupCommitJournal` 等现有隔离恢复机制；可证明每个持久 journal 边界崩溃后是完整旧状态或完整新状态，绝不让半恢复 root 打开业务。
4. **自动备份与根迁移**：`DesktopAutomaticBackupRuntime`、SharedCore `AppDataSnapshotService`、`DesktopDataRootMigrationService/Materializer`、Portable/new root 与全应用 `.cbbackup` 必须验证新域清单/拥有权，不能漏数据还报“全量成功”。
5. **GC/删除/保留**：live pending deletions/改卡/删会话不会清除封存资源；引用数、跨包资源共享、快照/回滚持有与明确用户删除均能在崩溃后重建，未知目录不得随清理误删。
6. **预算和隐私**：S0/S1 的 64 MiB 容器/ZIP32 和 128 MiB 展开只是只读能力上限，**不覆盖大规模真实全量恢复**；生产目标须另经容量/峰值和流式证据。原包携密钥不能作为普通可分享备份自动包含。

## 4. 切片分界与运行判据

| Gate | 当前可做的事 | 禁止越过 |
|---|---|---|
| **S2-G0 — READY FOR CODEX** | 纯 test-only、隔离合成临时根探针与精确源码审计，验证现有 Startup/Root/Snapshot/Backup/Migration/Journal 的未知域处理及中断；产出证据级别矩阵。详见 [153](153_P11_S2_G0_SYNTHETIC_LIFECYCLE_PROBE_CODEX_CONTRACT.md)。 | 不改 `src/main`，不造真实持久域/正式 Schema，不声称旧二进制防写 PASS |
| S2-G1 — PRODUCT DECISION REQUIRED | 明确 R01/R02/R03/R07 能力/配额/保留/秘钥托管/降级策略，再冻结真实 S2 生产实现与破坏性负例合同。 | 用户未批准不得代签 |
| S2-G2 — FUTURE CODEX IMPLEMENTATION | 一次完成新域+owned media+快照/备份+恢复/Root/Portable+旧版防写+故障/GC 的实际生产闭环。 | 不把只完成目录创建的“半 S2”投入真实运行 |
| S2-G3 — INDEPENDENT GATE | 工程源码/隔离测试之外，按独立批准执行的 Windows 与官方 Android/真实设备迁移及 Release Gate A–G。 | 不把 G0/S1 headless tests 冒充平台/用户验收 |

## 5. G0 可以解决什么，不能解决什么

当前已有 `DesktopStartupGate`、`DesktopFullBackupCommitJournal`、`DesktopDataRootOwnership`、`DesktopAutomaticBackupRuntime`、`DesktopDataRootMigrationService`、`DesktopDataRootMigrationMaterializer` 等入口，G0 能用 synthetic root 中的明确哨兵资源验证它们**目前**如何处理未知域、恢复 journal、完整备份和根移动。每项结果应标记 `CURRENT_SOURCE_PROVEN`、`SYNTHETIC_TESTED`、`NEEDS_ADAPTER`、`NOT_TESTED`、`BLOCKED`，而非概括“一定自动支持”。

G0 不能证明真实旧二进制不会破坏新 root，因为不允许启动/安装旧版程序；R03 仍须后续独立决策及实际验证。若 API 无法用隔离根安全调用，则 G0 应回报 NOT_TESTED，不读取真实用户目录补测。

**状态：S1 已限定结案，S2-G0 可进入独立工程探针；S2 生产持久化域 IMPLEMENTATION NOT AUTHORIZED，P11/D-038/Preview/Post-P8 HOLD 均不变。**
