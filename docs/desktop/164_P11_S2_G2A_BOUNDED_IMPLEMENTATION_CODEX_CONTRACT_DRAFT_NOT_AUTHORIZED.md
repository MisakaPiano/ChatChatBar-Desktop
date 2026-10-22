# 164 — P11-S2-G2A Typed Credential/Transaction Core — Codex Contract DRAFT (NOT AUTHORIZED)

**2026-10-23 JST — IMPLEMENTATION SPEC CANDIDATE / NOT A TASK TO EXECUTE UNTIL USER EXPLICITLY AUTHORIZES PRODUCTION CODE CHANGE.**

本规范是从 [162 G1A accepted evidence](162_P11_S2_G1A_CREDENTIAL_TRANSACTION_PROJECT_ACCEPTANCE.md)、[163 G1B design gate](163_P11_S2_G1B_UNIFIED_DATA_SECRET_TRANSACTION_DESIGN_GATE.md) 与 [158 user R07 direct import policy](158_P11_S2_G1_R07_DIRECT_IMPORT_PASSWORD_AND_CREDENTIAL_POLICY.md) 导出的首个**有限生产源码切片候选**。它尚不具备执行授权，**Codex 不应根据 Project 索引看到此文件就启动**。用户明确批准 `G2A production source` 后，Project 必须再次核对最新 Git HEAD/官方 skills 和冲突情况，才能将此 DRAFT 升级为可执行合同。

## 0. 产品目标，不等于本 slice 会全部交付

标准官方 1.4.5 `.cbbackup` 用户输入（可选择密码解密）里包含自己的 Token/API Key 时，在能够完整认证、正确映射、统一可恢复地提交的情况下，应直接导入数据与凭据，不得仅因凭据存在而整包拒绝。C-A Moments Post/Task 原始 bytes+独立媒体不启动任务。普通可分享 Desktop 导出仍不包含 SecretStore。S0/S1 读取与 v2 恢复语义不被此阶段越权改变。

**G2A 最多交付非激活的 typed import credential plan / 统一事务参与者 contract、可注入 synthetic intent 验证模块。即使测试全绿，既有 GUI/Service 不进入此新路径、`CREDENTIAL_APPROVAL_REQUIRED` 不移除，`restoreSupported` 不放宽；后续 G2B/G2C 才可提交真正数据。**

## 1. 尚待授权的允许改动提案

- 以 Kotlin/JVM JDK17、新增 `app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/` 下极少数**内部、默认不可被 GUI/Service 使用**的契约组件为主，可拆 `FullBackupCredentialSourceIdentity`、`CredentialImportConflict`、`CredentialImportPlan`、`RecoverableDataSecretIntent` 之类纯结构或验证器；test 在 `app/desktopApp/src/test/**`，证据独立目录 `docs/desktop/evidence/p11-s2-g2a-YYYYMMDD/`。名称仅示意，不硬编码历史 schemaVersion 或 provider Prompt。
- 源类型映射必须以选定官方 1.4.5 strict `backupJson` + ModelConfig/EmbeddingConfig/BackupPreferences/AppSettings 为真值；对 NovelAI、Fish、SiliconFlow、Model ID/Endpoint、Legacy Embedding、Singleton Embedding、CommunitySession 均分类；Community 无 Windows 正式 sink 必须返回明确 UNMAPPED，不得猜测目标；同 ID 异 Endpoint、同名异 ID、既存目标不同凭据必须记录冲突、不能自动覆盖。
- 全部候选日志/report/Plan 摘要为 **redacted metadata**，绝不包含 Key 明文、密码、源原始 JSON、完整 profile 路径或密钥值哈希（避免不必要关联）；输入源不更改。单独 sealed media ownership/schema 与实际 C-A 封存提交在后续另批。
- 可在独立内部 synthetic tests 建立**不触碰生产 live data 或 SecretStore root**的 durable intent 数据合同/验证器，但不得在本 G2A 早期切片使用 `WindowsSecretStore.create` 或真实 DPAPI。不要写第二套假生产 SecretStore；测试可以通过注入现有 `WindowsSecretStore` 实类+fake protector 的安全 seam。
- 如发现需要改变既有 `WindowsSecretStore` API/`DesktopFullBackupArchive` 正式守卫/`DesktopFullBackupCommitJournal`/`DesktopStartupGate`/root resolution 才能实现，请停止并把精确 diff 建议交 Project 另行审查，不得偷连 live/启动链。

## 2. 验收测试/故障注入草案

1. 对官方 v1/code84 plaintext/password wire goldens：类型/来源 ID/目标 key scope 的**strict** typed 计划正确；错误密码/auth、未知 family、重复 key、冲突、不兼容 CommunitySession → fail closed；不影响 S0/S1 原有只读报告。
2. 对同名模型不同 ID、同 ID 不同 Endpoint、目标已存不同 Key、legacy Embedding vs singleton，给出稳定 redacted conflict categories，不能将未证明的映射视为无冲突。
3. 根据 G1A `A_NEW/B_OLD`、`DATA_NEW/KEY_OLD`、`DATA_OLD/KEY_NEW`、postpublication ambiguous、rollback-failed 等真实负例建立可验证的 future Intent/phase invariants，报告**本 slice 哪些只有 Contract、哪些已有真实可恢复实现**，严禁因为测试纯模型模拟通过而标跨卷事务 production PASS。
4. 所有与现有 `AppBackupCodec`、`DesktopFullBackupArchive`、WindowsSecretStoreTest、Model/Embedding hydration、Root ownership/Startup/Journal 相关受影响 JVM regressions 真实运行；S0/ S1 非映射版本的负例全部保留；对异常/取消/硬退出/安全删除要求由未来 G2B 独立再检。
5. G2A 当前不运行真 DPAPI、旧 exe、Android/GUI/真 Token/用户原包/真实 root；不要求生成 Public Preview installer。合成 tmp roots/secret roots 全路径/inventory/hash 与退出码/JUnit/源 SHA 必须保留；同一用户 root 以外的 SecretStore 并发风险明确记为未闭合。

## 3. 保留的不可越过边界

- **不移除 `CREDENTIAL_APPROVAL_REQUIRED`**；不能仅因批准 R07 就默许把 Key 明文 JSON 写回或清空已有密钥；不能把现有 `DesktopFullBackupCommitJournal` 的注册 root path 扩成任意绝对 SecretStore 路径；不能自称跨两个物理 root 已原子提交。
- 不发布新 persistent Moments seal、GC 和自动备份/完整 `.cbbackup` scope adapter，不启用外来 Task、同步 upstream、改官方 Prompt、动 Android、默认可分享备份携 Key、更新 formal baseline 1.4.1 或合并 `desktop`。
- 完成（在未来已授权条件下）只会停在 **P11-S2-G2A Project Code Review Gate**；G2B/G2C 统一 durable data/secret txn、credential sink/恢复接线、OS DPAPI/Portable/旧 exe 真实平台门禁分别按权限独立实施与验收，P11/D-038/Public Preview 仍 HOLD。

## 4. 唯一启动条件

**当前 `APPROVAL_STATUS = NOT_AUTHORIZED`。** 此文档是供 Project 预审与用户决策的候选技术规格，不能作为现有阶段授权。用户之后若批准“开始 G2A 生产源码切片”，须 Project 确认来源 commit/目标 feature HEAD、G1B 详细设计与风险边界没有漂移，再单独冻结 executable Codex task。若设计不能在 G2A 这些限制内成立，Project 应先改范围而不是让 Codex 擅自扩权。
