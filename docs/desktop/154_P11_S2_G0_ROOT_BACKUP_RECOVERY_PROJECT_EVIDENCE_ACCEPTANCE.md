# 154 — P11-S2-G0 Root/Backup/Recovery Participation: Project Evidence Acceptance

**CURRENT PROJECT DECISION — 2026-10-23 JST — G0 CLOSED / QUALIFIED EXPERIMENTAL EVIDENCE ACCEPTED WITH DECLARED LIMITS.**

本文件只接受 G0 对现有生命周期/未知持久域能力的**合成实验观察**，不是 S2 生产域实现验收。既有 S0/S1 限定验收 [147](147_P11_FULL_BACKUP_V1_V2_READONLY_S0_PROJECT_ACCEPTANCE.md) / [151](151_P11_S1_MOMENTS_READONLY_GRAPH_PROJECT_ACCEPTANCE.md) 不变；[152](152_P11_S2_PERSISTENT_PRESERVATION_READINESS_AND_DECISIONS.md) 的 R01/R02/R03/R07 待批准政策仍有效；[153](153_P11_S2_G0_SYNTHETIC_LIFECYCLE_PROBE_CODEX_CONTRACT.md) 作为历史执行令。

## 1. 冻结身份及证据

- 控制起点 `a73ce170137ce084add3f7b59eddead0c57b469d`；**已测试**源码/主证据 `42a7269a1e21a2ad1cf1b1fb3ece447d8b702d9a`；最终只补交付回执的远端 HEAD `cbdfaa85ffc24de707504512450a8e135c82adbd`。两次线性快进；`desktop` 仍 `5ef95428421f7c693b010ac88f51d470804ab07a`。完整范围仅新增 `P11S2RootParticipationProbeTest.kt` 和独立证据目录；没有生产 `src/main`、schema、Prompt、SecretStore、Root、Backup、GC、Android/GUI 修改。
- [G0 原交接](evidence/p11-s2-g0-root-probe-20261023/PROJECT_HANDOFF.md)、[S2_PARTICIPATION_MATRIX](evidence/p11-s2-g0-root-probe-20261023/S2_PARTICIPATION_MATRIX.md)、[TEST_EXECUTION](evidence/p11-s2-g0-root-probe-20261023/TEST_EXECUTION.md)、[DELIVERY](evidence/p11-s2-g0-root-probe-20261023/DELIVERY.md) 与保留的 raw XML/快照/哈希清单为原始证据；本 Project 不倒改。

## 2. 独立复核 Gate

| 证据项 | 判定 | 限定 |
|---|---|---|
| Git 与 production scope | PASS：两次提交，第二次仅交付回执；`src/main` 三个生产子树未变化；从 GitHub 最终树独立核对 24/24 生产 source blob 和 4/4 test/fixture blob。 | 不等于跨版本兼容运行 |
| JUnit 原始执行 | PASS：逐份核对 **12 suite /158 tests /0 failures /0 errors /0 JUnit skips**；final Desktop **9 suite/109** + SharedCore **3 suite/49** 为两条实际命令，退出码0/0；未把旧 9/109 探索运行或 S1 95 重复累计。 | 无 Project 本机重跑；没有功能 RED，`RED=NOT_APPLICABLE` 是此 test-only probe 的诚实分类 |
| raw 文件身份 | PASS (sample)：独立从 GitHub 读取原始 bytes、SHA-256 自检并复算 **14/14** 代表性长度+哈希（7项测试结果/XML/源码树身份，7项合成 snapshot/GC/migration/archive 记录/fixture）。 | 完整证据索引另有 98 个 raw 条目与 60 个 fixture/receipt 条目，未逐条独立重哈希 |
| 生命周期探针 | PASS (scope)：真实调用 snapshot/auto scheduler/migration/full backup/journal/startup/portable/GC 的**合成 JVM 实现**，保留 before/intermediate/after SHA 清单。 | 普通文件保留不是 Moments seal、数据权限、完整事务或长期保留能力 |
| 旧版本、安全路径 | NOT TESTED / INCONCLUSIVE：旧 Windows exe/installer 未运行，SharedCore 的两条 symlink 分支报告 UNAVAILABLE，两项 MigrationMaterializer 分支没有可证执行标记。 | 0 JUnit skipped **不能**当作符号链接或降级防写通过 |

## 3. S2 必须保留的实质发现

**F1 — 通用 snapshot/automatic backup/root migration：RAW PRESERVED，仅有限证据。** 测试将 `synthetic-s2-sentinel/raw.bin` 放在独立临时 root；manual snapshot/restore、真实 automatic scheduler 调用的 snapshot、迁移 service + 隔离 bootstrap/安全快照均逐字节保留。这个结果不能证明新域 schema/Seal 可恢复；新域若存 Token，自动快照的通用拷贝可能违反源包秘密政策。

**F2 — FullBackup 覆盖不能靠偶然目录布局。** 顶层未知 `synthetic-s2-sentinel/` 触发 `UNCLASSIFIED_ROOT_ENTRY`，不生成档案（安全拒绝）。然而 `images/.synthetic-s2-sentinel/raw.bin` 在导出成功时被跳过，原 bytes 仍在源根但新 `.cbbackup` 缺失。源码 [DesktopFullBackupMapping.inventory](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/cbdfaa85ffc24de707504512450a8e135c82adbd/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/DesktopFullBackupMapping.kt) 显式略过路径中以点开头的片段及 `.part/.tmp/.backup` 等候选。**隐蔽目录或文件名不是持久化封存权威，也不能以“成功导出”证明保全了全部新域数据。**

**F3 — live GC 不认识外部封存的图片引用，存在可复现遗失风险。** 测试的 synthetic manifest 指向 `images/synthetic.png`，但 [DesktopOwnedImageCleanup.deleteUnreferenced](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/cbdfaa85ffc24de707504512450a8e135c82adbd/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopOwnedImageCleanup.kt) 未将这个新来源算入 retained，删除了被引用的 live image；独立 sentinel 文件仍存活。**未来 Seal 必须拥有媒体独立副本或以完整统一拥有权合同保护所有共享资源，不能只保存路径/manifest。** 推荐首版 C-A 使用内容复制、物理隔离于 live GC；这是设计提案，非生产授权。

**F4 — Transaction/Startup/Portable 仅现有已登记域受保护。** `DesktopFullBackupCommitJournal` 拒绝 sentinel path，注册设置行子进程 exit `0→71→0` 回滚，sentinel 在 journal 范围外保持不变；这不等于跨 live/sealed 多域原子恢复。Startup/Portable 在当前版本接受普通 sentinel，更不说明旧 exe 知晓新域或降级写入安全。root migration 的 post-authority 旧版本回退、业务正式重开仍未测。

## 4. 不得转化为完成项的门槛

- 无新的 Moments persistent schema、owned media GC、全量 `.cbbackup` registry/manifest 与 restore journal、pre-start downgrade gate、用户配额/保留/Secrets 方案；生产 S2-G1/G2 **未授权**。
- 不给旧二进制/symlink、完整 pending deletion queue、真实跨版本 root、真实 Android/Windows GUI、人类验收、网络或真实用户档案签 PASS。
- **P11 CLOSED/ACCEPTED 仍 PENDING；D-038 全应用迁移 BLOCKED；Post-P8 HOLD；Public Preview A–G 未签；正式 baseline 1.4.1 不变；P8–P11 未合并 desktop。**

下一步参见 [155 G1 design decisions](155_P11_S2_G1_DECISION_PACKAGE_AND_PORTABILITY_SAFETY.md)。另设 [156 G0R Codex test-only 证据补齐](156_P11_S2_G0R_OWNED_MEDIA_AND_CLEANUP_AUDIT_CODEX_CONTRACT.md) 以聚焦删除队列和媒体归属，而非启动 S2 生产写入。

本轮 Project docs-only 阅读与核对 GitHub，没有本机编译/运行任何测试、没有启动 GUI、Android、旧 exe 或读取用户实际根。
