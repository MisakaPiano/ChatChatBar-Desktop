# 148 — P11-S1 Moments `.cbbackup` Raw Graph Read-Only Validation — Codex Contract

**2026-10-23 JST — TASK SPEC READY / IMPLEMENTATION NOT STARTED / PROJECT REVIEW GATE AFTER S1.**

## 0. 目标和起点

在既有 P11-S0 [147](147_P11_FULL_BACKUP_V1_V2_READONLY_S0_PROJECT_ACCEPTANCE.md) 已限定验收的条件下，实现一个与运行时/持久化分离的 **Moments 原字节和资源引用图只读验证器**。只处理已经完整认证的官方 `.cbbackup` 容器中的 `moment_posts` / `moment_tasks` 及其所引用的选中资源。输出为脱敏结构化验证结果，不是恢复、持久化封存或运行 Task 的授权。生产持久封存和备份/GC/事务/旧版本防写留待 S2 独立产品决策。

- 仓库 `MisakaPiano/ChatChatBar-Desktop`，开发分支 `feature/phase8-fish-audio`；S0+R1 冻结生产 SHA `b531ff0263ab70857c716730049771e8a103fd51`，R1 修复源码 `06d09959ef7aada304cd64069bcfd355011abc66`，R1 证据 `b4bdb3370d48d9ffd7c83964206c72d63a0aecf9`。本任务发出时 **先以远端实际 HEAD 为准**，核对其仅含随后的 docs-only 项；若本地脏工作树/分叉/非快进，停止提交而非 reset、force、丢弃工作。
- 不更新 `10_UPSTREAM_BASELINE.json`；formal baseline 为 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。选择性备份/封存格式参照官方 1.4.5 `1198a13cf3008de1358b8a69aa5e40b64797c8d9`、1.4.6 `9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d`、1.4.7 `b4fa75d296680f777be095f3121ad0c4db979f17`，不从浮动 master 推断版本合同。当前 D-038 最低目标依然 1.4.5，不因读懂 v2 升为 1.4.7 全应用兼容。
- 开工前依次阅读 00/10/21，官方 `.agents/skills/chatbar-app-backup`、`chatbar-moments`、`chatbar-save-slot`，Desktop `.agents/skills/chatbar-desktop-full-backup`，然后 13/14、93/95、[原 S1 slice](evidence/p11-moments-production-contract-20261022/IMPLEMENTATION_AND_TEST_SLICES.md)、[存储/资源草案](evidence/p11-moments-production-contract-20261022/MOMENTS_STORAGE_RECOVERY_CONTRACT_DRAFT.md)、[任务安全草案](evidence/p11-moments-production-contract-20261022/MOMENTS_TASK_ACTIVATION_CONTRACT_DRAFT.md)、S0 [证据](evidence/p11-s0-readonly-20261022/PROJECT_HANDOFF.md) 及 R1 [证据](evidence/p11-s0-r1-wire-20261022/PROJECT_HANDOFF.md)。只读取本 slice 关联文件，不扫整个仓库。

## 1. 固定官方源码事实（不是授权复制 Android 行为）

- [1.4.7 MomentEntities](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/data/local/entity/MomentEntities.kt)：`MomentPost` 有 id/card/session/sender 快照、senderAvatar、imagePath、generatedImageMetadata、生成历史；`MomentTask` 有 id/card/session/scheduledAt/status/postId 和 PENDING/RUNNING/COMPLETED/SKIPPED/FAILED。S1 不把 Post、Task 注册为 Desktop live Entity。
- [1.4.7 ChatMessage](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/data/local/entity/ChatMessage.kt) 定义 `GeneratedImageMetadata.imagePath`，与 Post 顶层 imagePath 可能不同。必须独立核对 **senderAvatar、imagePath、generatedImageMetadata.imagePath 三条边**，不能使用只统计前两条的 [MomentMediaRetention](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/moment/MomentMediaRetention.kt) 作为完整验证器。
- [1.4.7 BackupArchive](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/BackupArchive.kt) `BACKUP_PATH_PREFIX = chatbar-backup-file:`；引用必须与 archive `files/`、inventory、SHA/长度和安全路径映射闭合，不能把来源路径直接当 Windows 路径或访问任意本机/网络资源。
- [1.4.7 BackupHistoricalResourcePolicy](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/BackupHistoricalResourcePolicy.kt) 仅允许顶层 `moment_posts.senderAvatar` 的历史文件缺失；imagePath 与 metadata 独立路径不得同享该例外。1.4.5/1.4.6/1.4.7 差异需按固定版本重新验证，不可统一放宽。
- [1.4.7 BackupExportSelection](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/BackupExportSelection.kt)：v2 `scope.moments` / `momentsImages` 代表选择与正文图投影；即使 Moments 选中，也不自动纳入 card/session。不可把未被选择导出的依赖误判为可借用 live，同样不可把 scope 主动省略的媒体误报为丢失。

## 2. 允许的工程改动

- 优先新增 `app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/MomentArchiveReadOnlyValidator.kt`（实际命名可等位）与同包测试；允许在 `DesktopBackupReadOnlyInspector.kt` 内**仅作最小内部只读集成**，在认证/中央目录/inventory/CRC/Hash 严格通过的上下文里给 S1 受控读取原 record bytes 的能力；外部 `ReadOnlyInspection` 保持脱敏，不能暴露所有原 JSON/ZIP bytes。
- 可增加内部不可恢复的 S1 graph result/edge 类型、固定 upstream-version test-only DTO 或 golden 和少量安全预算工具。应复用现有 S0 已认证的扫描路径；避免重新发明加密/ZIP parser。任何仍不可验证的源端结构明确 return BLOCKED/UNVERIFIED，而不是自行猜测 schema。
- 如确需修改 `AppBackupCodec`、现有恢复 `DesktopFullBackupArchive` / Service / Controller / scheduleRestore、SecretStore、Main/GUI、Desktop live entity repository/scheduler、自动备份、snapshot、GC、root ownership / transaction / migration / Portable、官方 Android 源码或任何 Prompt，**停止并说明阻断**；不在本 S1 范围内扩大授权。

## 3. S1 验证功能与输出合同

1. **认证先于解释**：依赖 S0 全量加密流认证、ZIP32 central/local 与 CRC、inventory SHA、manifest/scope/version 成功后才生成结果。源文件和解密 ZIP 各 <=64 MiB、累计展开 <=128 MiB、每 JSON 及条目上限不大于 S0；不创建明文 scratch、数据根、SecretStore 或运行中 Entity。
2. **原字节**：对每个已选 `moment_posts` / `moment_tasks` JSON 保留内存原 bytes 的独立 SHA-256/length，并对精确 raw 身份和 typed extraction 分开验证。JSON 字段未知、类型异常、重复 key、非法 UTF-8、未知 Task status 不可返回完整 graph PASS。未知正文可能含 Secret，不得宣称 arbitrary text credential-free。原始字段次序、空白、未知字段不得被重新序列化后冒充来源 bytes。
3. **稳定身份/边**：分别统计 post/task ID 和来源名字空间内重复；明确区分 Task.postId 为 null、可解析、缺失、冲突；card/session/sender 关联分成 archive 内可证明、**未被选择导出**、缺失或不理解，不允许拿本机同 ID 对象填洞，也不能只靠名字判定同一角色。范围未含 card/session 时应返回有损风险或未闭合状态，不伪称完整可运行恢复。Post/Task 原顺序及状态不改变。
4. **三处媒体资源**：独立枚举 `senderAvatar`、顶层 `imagePath`、`generatedImageMetadata.imagePath`，每条保留字段来源身份及 raw 引用摘要，按官方 `chatbar-backup-file:` / `files/` 严格映射 archive 内资源，按源已认证清单核对 length+SHA。共享 blob 可以多边指向；不同 metadata 图片不能折叠到正文图。空、已选、省略、未理解外部 URI 四类必须单独表达；不进行网络访问/任意本地绝对路径读取。
5. **历史缺头像**：仅固定官方来源证据支持的顶层 senderAvatar 可标记 `MISSING_AT_SOURCE`，原路径字面量保留在内部，输出仅警示/计数；正文图及 metadata 图片失踪、任意别的资源或偷换字段路径均拒绝 graph pass。v2 `momentsImages=false` 的官方**主动投影为空**与意外资源失踪不同，分别测试。
6. **结果不授予使用许可**：返回 `SOURCE_GRAPH_VERIFIED_WITH_SCOPE` / `UNRESOLVED_DEPENDENCIES` / `BLOCKED` 等明确状态、counts、分类及 hash（不含路径/ID/正文/Prompt/Token）；`restoreSupported=false`、`fullPortabilityAccepted=false`、`persistedSeal=false`、`taskRunnable=false`、`schedulerActivated=false` 恒定为假。原档无 Moments 可返回 `NOT_APPLICABLE`，不能凭没有 Post 宣称全部设备数据可迁移。
7. **零运行副作用**：不构造/初始化 MomentRepository、Scheduler、alarm、Moments AI/NovelAI/Fish、GUI 或目标数据根；不用官方 `momentsEnabled` 等开关冒充安全隔离。S1 只读报告也不等于 S2 `VERIFIED_SEALED` 持久保全物。

## 4. 合成测试与验收矩阵（headless JVM）

- 基线：记录至少一个此前 S0 不能提供完整三路径资源图报告的真实 RED（如无法自然构造则如实记录 NOT_APPLICABLE，绝不制造失败），保留退出码、测试源码、原始 XML。
- v1/code84 与 v2/code85/86、明文/加密；独立 wire 构造器沿用 R1 官方精确 DTO/夹具，不使用 Inspector DTO 生成正例。v2 `moments=false` 不允许混入 Post/Task；`momentsImages=false` 与默认 true、旧 `momentsImages` 省略的选择差异必须覆盖。
- 三条图片路径分别单独存在、相同资源共享、metadata 与 imagePath 不同、缺少各一条、历史顶层头像缺失但 metadata 完整、1.4.5 与新版允许缺失的差异均生成实际 archive/inventory/file bytes 并逐边断言。
- Post/Task ID 正常与重复、Task.postId 有/无/孤儿，非默认 status/未知 status、Post-card/session 缺选 vs 硬缺失、跨包同 ID 不自动归并；JSON 严格解码、未知字段/嵌套路径、重复 key、path traversal/绝对/UNC/ADS/混合大小写、CRC/sha/hash/size mismatch 全部 fail-closed。
- 错密码、加密末块篡改/截断、大小/ZIP32/条目预算、取消、认证后失败与隔离子 JVM 硬退出；输入、隔离合成 live journal、目标 roots 等完整文件清单与 SHA 前后一致，无新临时明文落盘，无触发任务/Provider/网络。
- 回归至少运行原 `DesktopBackupReadOnlyInspectorTest`、`DesktopReadOnlyBackupRedTest` 和 S1 专项测试；对任何更改的 S0 内部路径跑相关旧 `DesktopFullBackupArchiveTest`、Service/Journal/Root/Startup 有针对性的受影响 suites。明确每个 suite 真实测试数与 skipped，不用历史 81 + 本轮重复测试叠加冒充新实跑。

## 5. 执行与证据要求

- 使用现有 Windows Kotlin/JVM JDK17 工程。在 `app/` 下用 `gradlew.bat :desktopApp:test --tests '*MomentArchiveReadOnlyValidatorTest' --tests '*DesktopBackupReadOnlyInspectorTest' --tests '*DesktopReadOnlyBackupRedTest' --no-configuration-cache --console=plain` 或按实际类名调整；列出真实运行命令，Gradle cache/environment 失败独立标记，不能作功能 RED。
- 仅隔离合成测试；禁止读取/哈希真实 `.cbbackup`/`.cbsave`、四份受保护用户文件、生产 root/SecretStore，禁止 Windows GUI、Android 模拟器/ADB/物理设备、任何真实 Provider、安装、网络外联或大规模磁盘填充。不得运行旧实验脚本去生成 live Moments Task。
- 产物包含 source/fixture SHA+length、精确 upstream commit/blob、原始 RED/GREEN log+JUnit+退出码、独立 child exit/cleanup 及负例矩阵；生成新 `docs/desktop/evidence/p11-s1-moments-readonly-YYYYMMDD/`，不改写历史原始证据。普通公开 log/诊断不包含真实或合成秘密哨兵值、路径、正文或 Prompt。
- 每个改变真实源码的实现提交须保留清楚的 diff 和父 SHA。运行前/推送前确认 HEAD、工作树与远端一致，严禁覆盖并行 docs、强推、合并 `desktop`、同步 upstream 或更改正式 baseline。任何跨 S1 范围或负例风险未闭合，明确 BLOCKED 而不是自称 GREEN。

## 6. 停止与 Project Gate

停在 **P11-S1 Project Code Review Gate**，提交实际 commit SHA、完整 diff 与原始证据链接。不得开始 S2–S6、新持久化域/配额、备份与 root 迁移、C1/C2、严格合并、Task 激活或 Public Preview。**S1 即使通过也只表示已选格式与所见来源图的限定只读验证，不自动关闭 P11、D-038 或 Post-P8 HOLD。**
