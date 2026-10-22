# 153 — P11-S2-G0 Synthetic Root/Backup/Recovery Participation Probe — Codex Contract

**READY FOR CODEX ENGINEERING PROBE / NO S2 PRODUCTION IMPLEMENTATION AUTHORIZED — 2026-10-23 JST.**

## 0. 目标、范围与读取顺序

在 S1 [151 限定验收](151_P11_S1_MOMENTS_READONLY_GRAPH_PROJECT_ACCEPTANCE.md) 后、[152 S2 产品门禁](152_P11_S2_PERSISTENT_PRESERVATION_READINESS_AND_DECISIONS.md) 尚未批准生产持久域前，仅以**测试源码和隔离合成根**调查既有 Root/Startup/Journal/Snapshot/AutomaticBackup/FullBackup/Portable/GC 能否保全尚未登记的附加域。产出逐入口能力矩阵和原始测试证据，**不是生产封存实现**。

- Repo：`MisakaPiano/ChatChatBar-Desktop`，branch `feature/phase8-fish-audio`。S1-R1 已测试源码 `c658b08ac6aecb859df2deeab282eb5db11bd835`，R1 证据 `cd5c2a129c434b4ce7d1960df859b64b7812c696`；Project docs-only 提交可能更新远端 HEAD，开工时先核对本地/远端和工作树，仅安全快进，禁止 reset/force/丢弃未跟踪文件。`desktop` 不合并，validated upstream baseline 仍 1.4.1。
- 按权威先读 00/10/21、相关 Desktop backup/root/snapshot Skills、13/14、93/95、151/152、[原 S2 草案](evidence/p11-moments-production-contract-20261022/MOMENTS_STORAGE_RECOVERY_CONTRACT_DRAFT.md)。只读对应生产文件及其直接测试，不全仓扫描；重点 `DesktopStartupGate`、`DesktopFullBackupCommitJournal`、`DesktopDataRootOwnership`、`DesktopDataOperationCoordinator`、`DesktopAutomaticBackupRuntime`、`DesktopDataRootMigrationService/Materializer`、`DesktopFullBackupArchive/Service`、SharedCore `AppDataSnapshotService` 及 `AppDataRootInfrastructure`。

## 1. 允许更改的文件

- **仅 `app/desktopApp/src/test/**` 与必要的 SharedCore `src/test/**` 测试专用合成辅助源文件，以及新 `docs/desktop/evidence/p11-s2-g0-root-probe-YYYYMMDD/` 的原始证据和交接。建议创建 `P11S2RootParticipationProbeTest` 或等位名称。**
- 禁止更改任何 `src/main`、正式 Entity/store/schema、root 选择器、startup/locks/journal、snapshots、automatic backup、GC、完整 `.cbbackup` 恢复/导出、SecretStore、Prompt、Android、GUI、Gradle/构建/安装器。
- 可以在**本轮专用临时合成根**构造明显虚构的 `synthetic-s2-sentinel/` 和已登记资源，收集原 bytes/hash 与生命周期；不可把 sentinel 家族/路径登记为正式生产域，也不可向现有真实数据 root 写任何文件。
- 如果无法不改生产代码安全运行某测试，则回报 `NOT_TESTED/BLOCKED` 并记录精确源码缺口，不强行绿测、不申请更高权限访问真实数据。

## 2. 逐入口 synthetic probe 与事实等级

| 目标入口 | 需要取得的实证 | 不能宣称的结果 |
|---|---|---|
| Startup/RootOwnership | 根选择、ownership 锁与 repo 前 journal recovery 顺序；从源码/旧历史记录识别直接打开不同版本数据根风险 | 未启动真实旧 exe，则旧版防写只能 `NOT_TESTED`，marker/Launcher 不是证明 |
| Snapshot + automatic backup | temp root 中已有正式 fake 数据与未知 sentinel 时，当前服务如何分类：`PRESERVED`、`EXPLICITLY_REJECTED`、`SILENTLY_SKIPPED`、`UNTESTED`；复核还原的文件清单/哈希 | 不能把 sentinel 被忽略还称全量数据安全 |
| Full `.cbbackup` / S0-S1 | 旧 Archive/Service 是否明确拒绝未知内容，S0/S1 是否隔离、是否曾产生 Prepared；识别未来新域适配点和现有 64 MiB/ZIP32 边界 | 不放宽 v2/Moments/外来凭据的恢复/运行准入 |
| Root Migration / Portable | 单独两份 synthetic root 的迁移、snapshot 保留、root authority、切换/回退后原/目标 bytes；不支持未知域则报告 | 禁止修改用户真实 root 或清空/移动已有历史目录 |
| Journal / interrupted operation | 原事务对**未注册域**的覆盖与当前证明空缺；在已有测试的隔离子进程按合理规模做崩溃/取消反例 | 不是新域 crash-restart 通过；未接入时 `NEEDS_DOMAIN_ADAPTER` |
| GC / deletion queues | 已有 live 清理行为是否能影响独立 sentinel；证明不了则只记录 ownership/GC 缺口 | 不运行未知系统清理、不用当前 live GC 替代未来封存 GC |

必须将 `STATIC_SOURCE_PROVEN`、`SYNTHETIC_RUNTIME_OBSERVED`、`CURRENT_UNSUPPORTED`、`NEEDS_ADAPTER`、`NOT_TESTED`、`BLOCKED` 明确分开。源码推测不能替代一次执行的 XML/exit；当前不支持不是本测试应篡改生产代码以修复的缺陷。不得加入永久失败的 CI 测试；可以通过**断言真实观测分类**绿色运行，同时在报告中诚实保留不支持/阻断项。若 test-only probe 无法安全观测某入口，就记录 NOT_TESTED 而不是模仿成功。

## 3. 证据和受影响回归

- 仅本机 Windows/JDK17 headless Gradle JVM，运行新增 probe 和确实涉及的既有 RootOwnership/Startup/Backup/Journal/Migration/Snapshot 等 suites；列准确命令及全部实际 suite 数，不把旧 S1 95 追加到新 test 数中。保留 RED（若真实存在）、GREEN、JUnit XML、日志、退出码、准确 Git blob/工作字节 SHA、fixture SHA+长度、合成根前后完整路径/bytes digest、未访问真实数据的边界。
- 每个用例先创建独立 temp root、fake 资源与明确 sentinel；调用前后列出完整清单/SHA，禁止自动清理已有真实 root、禁止读写四份受保护文件、现有本机普通安装、用户 `.cbbackup`/`.cbsave`、真实 API Token/SecretStore。模拟容量问题禁止填满系统盘，进程中断只结束本测试启动且持有 PID 的子 JVM，不能终止未知进程。
- 新增 `S2_PARTICIPATION_MATRIX.md`，对每项列 `file + method + blob/SHA`、`expected contract`、`observed result`、`evidence`、`needed adapter`、`legacy binary concern`、`data safety effect`。尽量复用官方数据结构和现有 Kotlin/JVM 逻辑，不维护第二套模拟运行时。测试专用 probe 代码不要构建成发行功能。

## 4. 一票否决与停止点

以下事项本轮全部不授权：生产 `desktop.moments.preservation` 域与 schema、元数据写入、旧版启动阻断改造、备份/快照/迁移生产写入路径、新 GC/删除策略、Secrets C1/C2、Task 激活、S3/S4/S5/S6、官方 Android 或 Windows GUI、真实用户数据/账号/网络/安装器测试、`desktop` merge、upstream sync、baseline 提升、Public Preview 签署。

完成后推送**仅 test-source + raw evidence** 至同一 feature，提供远端精确 HEAD、完整 diff、复核声明与安全限制；停止 **P11-S2-G0 Project Evidence Review Gate**。R01/R02/R03/R07 用户产品决策另行取得，G0 的测试结果**不允许自动授权 S2-G1/G2 生产持久封存**。
