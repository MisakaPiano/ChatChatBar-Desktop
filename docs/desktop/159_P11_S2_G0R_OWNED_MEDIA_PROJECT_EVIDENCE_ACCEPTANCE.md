# 159 — P11-S2-G0R Owned Media & Deletion Probe: Project Acceptance

**CURRENT PROJECT EVIDENCE DECISION — 2026-10-23 JST — CLOSED / QUALIFIED EXPERIMENTAL EVIDENCE ACCEPTED WITH DECLARED LIMITS.**

本文件关闭 **G0R 合成媒体拥有权和删除链调查任务**，不关闭 S2 生产持久化、Moments 完整迁移、D-038 或 Public Preview。[154 G0 Project Acceptance](154_P11_S2_G0_ROOT_BACKUP_RECOVERY_PROJECT_EVIDENCE_ACCEPTANCE.md) 仍有效。[156 G0R Codex Contract](156_P11_S2_G0R_OWNED_MEDIA_AND_CLEANUP_AUDIT_CODEX_CONTRACT.md) 为已完成的工程探针指令。

## 1. Git、源码及证据身份

- G0R 测试前控制 `e0923fc625052c1454663d429d1520b794c022bf`；并行 Project `157` docs-only `eca86192c8b5ab9df8ac7779fc0217f2ddde252a` 已被 Codex 保留；实际**已测试 test-source 与原始证据提交** [ee13a5c57f5a7c44f91faa5c5df519732126c782](https://github.com/MisakaPiano/ChatChatBar-Desktop/commit/ee13a5c57f5a7c44f91faa5c5df519732126c782)，后续交付回执 [caa70ae77bf597bb649ba61b2d54bd711458d8ee](https://github.com/MisakaPiano/ChatChatBar-Desktop/commit/caa70ae77bf597bb649ba61b2d54bd711458d8ee)。两笔线性快进；没有 reset/force。
- 当前 Project 审查时远端 `feature/phase8-fish-audio @ 41cf5f4136ce065a7adb5c013fb360f1a1f6c901`，额外提交为 Project R07 **docs-only** [158](158_P11_S2_G1_R07_DIRECT_IMPORT_PASSWORD_AND_CREDENTIAL_POLICY.md)。从交付控制到 G0R 最终 SHA 的 diff 仅新增 `app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/P11S2OwnedMediaSafetyProbeTest.kt` 和 `docs/desktop/evidence/p11-s2-g0r-media-delete-20261023/`；`desktop` 未合并。`app/desktopApp/src/main`、`app/sharedCore/src/main`、`app/app/src/main` 均保持同一 Git tree。
- [原 G0R 交接](evidence/p11-s2-g0r-media-delete-20261023/PROJECT_HANDOFF.md)、[观察矩阵](evidence/p11-s2-g0r-media-delete-20261023/S2_G0R_MEDIA_OWNERSHIP_MATRIX.md)、[源码审计](evidence/p11-s2-g0r-media-delete-20261023/SOURCE_AUDIT.md)、[测试](evidence/p11-s2-g0r-media-delete-20261023/TEST_EXECUTION.md) 与 [交付](evidence/p11-s2-g0r-media-delete-20261023/DELIVERY.md) 原件未被 Project 修改。G0R 自行提交的 235 raw 文件校验通过记录作为 Codex 运行回执保存，不冒充 Project 全量重算。

## 2. Project 独立证据核查

| 检查点 | 判定 | 证据限制 |
|---|---|---|
| 实际测试 | **PASS**：独立逐份读取最终 10 份 JUnit XML，**88 tests / 0 fail / 0 error / 0 skipped / exit0**，全部同一最终运行；12 项新探针和现有资源/Archive/Service/S0/S1 回归。之前单独的初始 12 测试不累计，前置 PowerShell 工作目录报错不是产品 RED。 | Project 未在本机重新运行 Gradle，也不声称新功能 RED=GREEN；G0R 是 observation-only 测试。 |
| 源码身份 | **PASS**：从远端 Git tree 独立核对 18/18 生产边界 blob 与 13/13 测试/Corpus/Codec blob，另三棵 `src/main` 生产 tree 与控制点相同；官方固定 1.4.5 三份源文件 blob 也匹配。 | 只覆盖选定源码及关联执行，不是整个 repo 功能验收。 |
| 原始字节 | **PASS (sample)**：本 Project 从远端 raw 原始 bytes 重算 15/15 代表性文件 SHA-256 与 size（含两份真实合成导出 `.cbbackup`、XML、结果、GC/备份/独立副本 receipts）。**Codex 报告 235/235 Git archive 校验，本 Project 独立重算只有 15/15。** | 未将这 15 份抽查写成 235/235 Project 全核。 |
| 产品行为 | **PASS (observational)**：直接调用当前 `JsonFileStorage`、`DesktopOwnedImageCleanup`、`DesktopCharacterResourceStore`、`DesktopFullBackupMapping/Service/Archive`；测试保留临时根 before/after 清单并清理 own temp root。 | 没有连接真实新 Seal schema、Persistent Queue、真实数据根/Token 或 Android。 |

## 3. 从真实行为得到的安全结论

- **F1 / live owner**：已持久化一条或两条 `ChatMessage` 对图片引用，现有 `deleteUnreferenced` 在最后一条 live 引用消失前保留图片，之后允许删除 candidate。这仅验证已测实体/字段组合。
- **F2 / external-only S2 manifest**：只由独立 synthetic manifest 引用的 live `images/` 图片被现有 GC 删除，manifest 字节仍保留。是实际的 `S2_DOMAIN_UNPROTECTED` 反例，未来 Seal **不能只是记录一个 live 路径**。
- **F3 / physical copy**：对源媒体做独立 `Files.copy` 后，live GC、底层 `deleteOwned` 和一次部分 unlink 失败均未修改这份合成副本；`Files.isSameFile=false` 与 SHA/length 能证明本轮文件独立，但**不能**证明正式 copy-on-seal 原子提交、GC 阻断、hardlink/symlink、旧程序安全或全应用备份覆盖。
- **F4 / non-atomic cleanup**：第二 candidate 拒绝时第一 candidate 已删除且不可由此次 cleanup 自动回滚；取消等待独占锁时 source 未改变；重复 exact candidate 在测试范围内可重试。不得将这些同一 cleanup 行为冒称完整 pending-deletion queue 的 replay/crash safety。
- **F5 / backup coverage**：普通 `images/synthetic.png` 进入已认证 v1 archive；未知 top-level synthetic copy 域明确报 `UNCLASSIFIED_ROOT_ENTRY`、不生成包；`images/.synthetic-seal-fixture/copy.bin` 却在**导出成功**时被忽略。新域必须拥有明确**可枚举的正式注册与 coverage**，不能靠 dot-path 保全或成功导出消息宣称全部备份完成。
- **F6 / real pending-deletions**：固定官方 1.4.5 `DeletionCoordinator.kt` / `MomentRepository.kt` / `NovelAiImageStorage.kt` 源码审计通过；它们并不等于当前 Desktop 存在可注入的相同消费链。Android-only 依赖导致在本轮只允许 Desktop test-only 的前提下**实际 queued→processed、retry、crash、cancel 均 NOT_TESTED / NEEDS_ADAPTER**。官方删除源码对 live Moment 读取路径的限定不应冒称覆盖未来外部 sealed manifest。

## 4. 继续有效的产品决定和 HOLD

- 用户于 [157](157_P11_S2_G1_USER_CONFIRMED_PARTIAL_POLICY_DIRECTIONS.md) 批准 **C-A 不运行封存、原始 JSON + 物理独立媒体、不自动删、空间不足不清旧档、不允许旧程序对新根不安全写**；[158](158_P11_S2_G1_R07_DIRECT_IMPORT_PASSWORD_AND_CREDENTIAL_POLICY.md) 后续又批准 **用户自持来源 `.cbbackup`、默认安全直接导入用户自有 API 凭据，不因凭据存在本身整包拒绝**。旧 G0R 交接中 `R07 OPEN` 为交付时的历史时点，不能覆盖 158。
- **没有**用户授权 S2-G2 生产 persisted schema、root 写入、GC/备份改造、SecretStore 跨域写事务、旧二进制试验、C2 高级 UX、Task 激活、S3–S6、upstream sync、`desktop` merge 或 Preview 发布。容量上限数字、删除确认和旧版强制防写机制仍待工程方案/证据。普通可分享 Desktop 备份默认不携密钥的限制仍有效。
- **G0R=CLOSED / limited experimental evidence accepted；S0/S1/G0 先前已按各自范围限定接受；P11 总体=PENDING，D-038 full-app migration=BLOCKED，Post-P8 HOLD，Public Preview A–G 未签，formal upstream baseline 1.4.1。**

下一步：[160 S2-G1 完整安全设计控制](160_P11_S2_G1_INTEGRATED_PRESERVATION_AND_CREDENTIAL_SAFETY_BLUEPRINT.md)（Project docs-only）与 [161 G1A SecretStore/恢复边界 test-only probe](161_P11_S2_G1A_SECRETSTORE_TRANSACTION_PROBE_CODEX_CONTRACT.md)（可交 Codex，生产源码不修改）。二者都不是 S2 持久化实施授权。

本文件是 Project 对 GitHub 只读源码和证据的审查记录，未运行 Android/GUI、真实数据/Token 或新的本机 JVM 测试。
