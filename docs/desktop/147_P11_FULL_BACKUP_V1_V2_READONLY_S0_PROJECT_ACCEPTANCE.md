# 147 — P11 Full-App `.cbbackup` v1/v2 Read-Only S0 Project Acceptance

**CURRENT PROJECT DECISION — 2026-10-23 JST — CLOSED / QUALIFIED ENGINEERING ACCEPTED WITH DECLARED LIMITS.**

本文件仅关闭 P11 全应用 `.cbbackup` 只读检查切片 S0。与 [122_P11_S0_PROJECT_ACCEPTANCE.md](122_P11_S0_PROJECT_ACCEPTANCE.md) 的 SaveSlot 独立只读检查器是两项不同工程范围。**P11 整体、D-038 全应用迁移及 Public Preview Gate 仍未通过。**

## 1. 固定提交与审查边界

- S0 生产和测试初始实现：[b531ff0263ab70857c716730049771e8a103fd51](https://github.com/MisakaPiano/ChatChatBar-Desktop/commit/b531ff0263ab70857c716730049771e8a103fd51)；父提交 `adcdd99cfb6f3583902e5ab013185d1a8393072e`。S0 初始证据在 [p11-s0-readonly-20261022](evidence/p11-s0-readonly-20261022/PROJECT_HANDOFF.md)。
- R1 测试真实性修复的**已测试源码**：[06d09959ef7aada304cd64069bcfd355011abc66](https://github.com/MisakaPiano/ChatChatBar-Desktop/commit/06d09959ef7aada304cd64069bcfd355011abc66)。最终交付/原始 CRLF 保全：[b4bdb3370d48d9ffd7c83964206c72d63a0aecf9](https://github.com/MisakaPiano/ChatChatBar-Desktop/commit/b4bdb3370d48d9ffd7c83964206c72d63a0aecf9)；R1 [交接](evidence/p11-s0-r1-wire-20261022/PROJECT_HANDOFF.md) / [TEST_EVIDENCE](evidence/p11-s0-r1-wire-20261022/TEST_EVIDENCE.md) / [DELIVERY](evidence/p11-s0-r1-wire-20261022/DELIVERY.md)。最终证据提交没有生产/测试源码变化。
- R1 相对 S0 的全部修改限于两份测试源码和 R1 新证据目录；`app/desktopApp/src/main`、`app/sharedCore/src/main`、`app/app/src/main` 的提交树身份与 S0 一致。GitHub feature 于项目审查时指向 `b4bdb337...`，`desktop` 仍为 `5ef95428421f7c693b010ac88f51d470804ab07a`。
- 固定官方 transport 参照：1.4.5 `1198a13cf3008de1358b8a69aa5e40b64797c8d9`，1.4.6 `9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d`，1.4.7 `b4fa75d296680f777be095f3121ad0c4db979f17`。本轮比较的是**所选格式源码**，不是整版 upstream sync。正式 validated baseline 始终 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。

## 2. Project 独立审查结果

| Gate | 结论 | 范围限定 |
|---|---|---|
| Git 线性提交 / 无生产漂移 | PASS | 初始 S0 实现后仅 R1 测试与证据变化；远端 HEAD、两个 R1 commits、`desktop` 不变已分别核对 |
| 官方 wire 来源 | PASS | 三个固定官方 AppBackupModels blob 身份匹配；`backupJson.ignoreUnknownKeys=false` |
| R1 v1 真实正例 | PASS | code84 的 9-field Manifest 和 5-field Preferences 不包含 `scope` / `retainedPreferences`，且旧严格 DTO 会拒绝两个非法新字段 |
| R1 现代默认值 | PASS | `scope`、`sessionChoices`、`sessionDefaultChoice`、`retainedPreferences` 的 `EncodeDefault.NEVER` 与 `momentsImages=true` 规则对齐 |
| S0 只读隔离 | PASS (scoped) | `ReadOnlyInspection` 不等于 `Prepared`；旧 `AppBackupCodec.decrypting()` 和 Service/Archive 的 v1 恢复准入未放宽 |
| 当前受影响 JVM 测试 | PASS (submitted evidence reviewed) | R1 最终 18 Inspector + 1 restore-rejection = 19/19，XML 0 failure/error/skip，exit0；不是 Project 本机重跑 |
| 先前恢复等回归 | REUSED BY SOURCE IDENTITY | 81 项 S0 历史已通过测试按未修改的生产源码复用，不宣称本轮实跑 100 项 |
| RED / 原始 SHA | PASS (scoped) | R1 RED 1 断言失败/exit1 历史保持；独立重算 9 份代表性 raw 文件 SHA-256 与清单匹配，不宣称 48/48 全部复算 |

历史 `BACKUP_V1_V2_COMPAT_MATRIX.md` 的“官方宽松解码”推论已由固定源码和 S0 的 SOURCE_AUDIT_AND_ERRATA 否定：官方 backup transport 是 `ignoreUnknownKeys=false`；不能与单独 `BackupEntityValidator.entityJson` 宽松实例混淆。保留该历史 DRAFT 原件，今后以本结论和新证据覆盖其过时的格式读取/解码描述。

## 3. 接受的能力

- 独立 `DesktopBackupReadOnlyInspector.inspect(Path, CharArray?)`，限定大小范围内只读检查 v1/code84–86 与 v2/code85–86、明文及加密，完整消费/认证、检验 ZIP32 目录、CRC、inventory、清单、Hash 和已知 schema/scope。
- 返回版本、数量、摘要、已知凭据**类别**、未解释实体计数和安全警示；不返回正文、原始 Prompt、原始凭据、Entity bytes、完整路径、恢复计划或源档案可写句柄。
- v2、未解释 Moments/其他类型、含外来非空凭据、v1/code85–86 均不获当前恢复准入。无危险项的 v1/code84 也仅提示 **REQUIRES_EXISTING_V1_PREPARATION**，仍必须走原先完整 prepare + 确认 + 安全提交。
- Source/解密 ZIP 分别不超过 64 MiB、展开累计不超过 128 MiB，ZIP32/store/deflate；每 JSON 的原有限额以及现有条目/路径限制保留。超限与 ZIP64 不自动拓展支持。

## 4. 未接受 / 后续阻断

- **FORMAT_READABLE ≠ RESTORE_SUPPORTED ≠ FULL_PORTABILITY_ACCEPTED**。S0 不能恢复 v2、Moments、外来凭据或未知 schema；没有持久封存域、完整资源图/依赖闭包、嵌套 SaveSlot 可用性或安全 Task 准入证明。
- 没有 S0 GUI 展示、Android 1.4.6/1.4.7 APK 真运行、物理 Android、真实用户档案/Token、完整备份跨平台生产互操作、安装升级或 Public Preview Gate 证据。
- S0 的 P11 子范围 CLOSED；**P11 总体 CLOSED/ACCEPTED = PENDING；D-038 = BLOCKED；Post-P8 HOLD = 保持；Public Preview A–G = 未签；P8–P11 = 未合并 `desktop`**。不更新正式 baseline，不自行批准 C1/C2、Moments S2、新持久域、Task 激活或发布。

## 5. 后续工程交接

下一候选仅为 [148 — P11-S1 Moments archive graph read-only validation Codex contract](148_P11_S1_MOMENTS_ARCHIVE_READONLY_VALIDATION_CODEX_CONTRACT.md)。它在内存中验证原字节、三路径与关系图，不注册生产 Moments 域、不写数据根、不执行 Scheduler；其实现需单独交由 Codex，并在独立 Project Review Gate 停止。S1 成功不会自动解除此处列出的阻断。

**本文件为 docs-only Project 接受登记；未运行新的 Gradle、GUI、Android、Provider 或真实数据测试。**
