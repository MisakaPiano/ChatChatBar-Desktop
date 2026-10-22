# 161 — P11-S2-G1A SecretStore / Full-Backup Transaction Boundaries: Test-Only Codex Contract

**READY FOR CODEX — 2026-10-23 JST — HEADLESS SYNTHETIC OBSERVATION ONLY; NO PRODUCTION SECRET MIGRATION OR NEW SCHEMA AUTHORIZED.**

## 0. 唯一目标与来源权威

依据用户已批准的 [157](157_P11_S2_G1_USER_CONFIRMED_PARTIAL_POLICY_DIRECTIONS.md) 和 [158](158_P11_S2_G1_R07_DIRECT_IMPORT_PASSWORD_AND_CREDENTIAL_POLICY.md)：**标准全应用备份输入应在来源内容、身份、事务可安全整合时直接迁入用户自己的 API Key**，而不是因为发现 Key 就整包拒绝；完整 C2 多账户 UX 可后续建设。S2 同时须保存不执行的 Moment Post/Task 原 bytes 与独立媒体副本，不允许旧版写新 root，不自动清理已有封存。Project 架构蓝图 [160](160_P11_S2_G1_INTEGRATED_PRESERVATION_AND_CREDENTIAL_SAFETY_BLUEPRINT.md) 指出了当前**逐凭据原子写**与**跨数据/密钥统一恢复**之间尚未验收的缺口。请只做**test-only 现状探针**，给出真实代码路径与合成证据。

- 仓库 `MisakaPiano/ChatChatBar-Desktop`，分支 `feature/phase8-fish-audio`；G0R 已测试源码/证据 `ee13a5c57f5a7c44f91faa5c5df519732126c782`，交付回执 `caa70ae77bf597bb649ba61b2d54bd711458d8ee`；后续 Project R07 docs-only `41cf5f4136ce065a7adb5c013fb360f1a1f6c901`。本合同可能作为更晚 docs-only HEAD 出现，**开工前远端查实最新 HEAD**；只安全 FF，若分叉/脏工作树/有用户文件保留并 STOP，绝不 force/reset 覆盖。
- 按项目 authority 读取 00/10/21，固定 upstream baseline 1.4.1，官方 backup skill 与所选 1.4.5 AppBackupModels/credential-store 源码，Desktop backup/Secrets/Root skills（若存在），13/14、93/95、[157](157_P11_S2_G1_USER_CONFIRMED_PARTIAL_POLICY_DIRECTIONS.md)、[158](158_P11_S2_G1_R07_DIRECT_IMPORT_PASSWORD_AND_CREDENTIAL_POLICY.md)、[159](159_P11_S2_G0R_OWNED_MEDIA_PROJECT_EVIDENCE_ACCEPTANCE.md)、[160](160_P11_S2_G1_INTEGRATED_PRESERVATION_AND_CREDENTIAL_SAFETY_BLUEPRINT.md)。仅检查直接关联的 `WindowsSecretStore` / `DesktopSecretStore` / `DesktopCredentialKey`、`DesktopFullBackupArchive/Policy/Mapping/Service`、`DesktopFullBackupCommitJournal`、`DesktopDataOperationCoordinator`、`DesktopStartupGate` 及 existing `WindowsSecretStoreTest` /相关独立回归。不要全仓无差别扫描。

## 1. 允许与禁止改动

**唯一允许**：在 `app/desktopApp/src/test/**` 下新增 test-only `P11S2CredentialTransactionBoundaryProbeTest` 或等位测试 helper，及一个新 `docs/desktop/evidence/p11-s2-g1a-credential-boundary-YYYYMMDD/` 的原始证据/执行/矩阵。确需 sharedCore `src/test/**` 的辅助测试需先确认不牵涉 Android/生产依赖。**不得修改任何 `src/main`、Gradle、正式 Entity/schema、`DesktopSecretStore` 接口、Root、FullBackup 准入或 journal、Prompt、GUI、installer、upstream、P11 S0/S1 实现、CURRENT 政策或旧原始证据。**

只允许本轮创建的 Windows `java.io.tmpdir` 明确合成根、假 `DesktopCredentialKey` 与明显不是真实凭据的固定测试字符串，推荐沿用现有 `WindowsSecretStoreTest` 的内部 `DeterministicTestProtector` 思路及 injectable `SecretBlobWriter` 故障。**不要用真实 Windows 用户的 DPAPI / SecretStore root，不读/哈希真实 Token/档案，不安装旧 exe、不登录 Provider、不运行 Android/GUI**。本轮 fake protector 仅是故障注入，不证明真实 DPAPI portability 或 Windows identity 的运行行为。

## 2. 必须交付的独立测试/审计矩阵

| 入口/场景 | 最低需要观察或明确 NOT_TESTED | 分类边界 |
|---|---|---|
| A / 逻辑密钥与身份 | 真实 `DesktopCredentialKey` 的 global NovelAI/Fish/SiliconFlow、model-ID、embedding-ID 的文件身份是否不碰撞；来源不同 ID/同名模型不自动映射；读取所有相应当前 DTO 映射事实 | `EXISTING_SUPPORTED` vs `UNMAPPED_DEPENDENCY`，不能把“文件名无碰撞”当成跨档账号身份完备 |
| B / 逐 key protected storage | 在 fake tmp SecretStore 使用真实 WindowsSecretStore 类与 fake `SecretProtector`：先保存旧 A/B，再尝试替换，故障注入第 1/第 2 写；检查每个旧密钥值与加密 blob、原字节、无 plaintext、失败是否保全已有值 | `SINGLE_KEY_ATOMIC` 与 `MULTI_KEY_PARTIAL` 分别报告；不要通过编写新的模拟 SecretStore 得出成功 |
| C / 现有 FullBackup 准入 | 用独立官方 wire DTO 的**合成**无密码/密码保护 v1 包分别含/不含 preferences、model、embedding Key；验证当前 `DesktopFullBackupArchive.inspect` 的 key present refusal 与 source unchanged、无 `Prepared`、target secrets unchanged；S0 只读 `inspect` 的 `formatReadable`/credential category 与正式 restore 区分 | 明确记录 `CURRENT_PRODUCTION_REJECTION`，不是产品批准的最终目标；不改/绕过检查让功能假 PASS |
| D / registered-row journal vs secret writes | 在 test-only temp root 用真实 `DesktopFullBackupCommitJournal` + `WindowsSecretStore`（fake protector）执行受控顺序：old data/old fake key → one side changed → injected exception/cancel → existing journal recovery；观察两侧是否可能不一致。**不得尝试让 Journal 处理未注册的 secret path 或绕过 path validation** | `CROSS_DOMAIN_ATOMICITY_NOT_PROVEN` / 若发现实际 partial state 用 `OBSERVED_NONATOMIC_SEQUENCE`；该序列是故障建模，不是正式生产备份曾执行凭据写入的证据 |
| E / restart/rollback feasibility | 精确源码审计 `DesktopStartupGate` 的 journal recovery 次序与 `WindowsSecretStore` root/DPAPI 生命周期；仅安全合成下对已暴露注入 seam 测故障恢复；无法测试 Windows 用户切换/Portable 的 OS 作用域则标 `NOT_TESTED` | 不能把单文件 AtomicMove 当作跨 SecretStore 和 live data 双阶段 commit |
| F / data safety + secrecy | 测试系统输出、error/exception/log 中不得打印假密钥值；任何 plaintext scratch 只在拥有权明确的临时合成根和故障测试允许的内存中；所有原 root 和 fake secret blob 前后 path/length/hash 严格记录，测试释放 root lock 和临时目录 | 不得把 fake protected bytes 写入 repo 的真实秘钥位置；Git evidence 仅合成数据 |

测试结果应诚实区分 **SOURCE_PROVEN、SYNTHETIC_OBSERVED、CURRENT_UNSUPPORTED、NEEDS_ADAPTER、NOT_TESTED**。无生产功能修复，不要求/伪造产品功能 RED；新增测试对观察事实断言并能 GREEN，同时在报告中标注真实部分写、拒绝和恢复缺口。旧测试覆盖矩阵另列，不能把前轮 G0R 的88加到本轮数字。

## 3. 提交证据与停止

- 限定运行 JDK17 Windows headless `:desktopApp:test`，运行新增 1 suite 与实际受影响 `WindowsSecretStoreTest`、`DesktopFullBackupArchiveTest`、`DesktopBackupReadOnlyInspectorTest`、`DesktopFullBackupService/CommitJournal/StartupGate` 等相关 suites；按真实依赖调整并记录完整命令、XML、原始 stdout/stderr、exit、source SHA/blob、fake fixtures SHA/长度、before/after/root lock、fault stage 与 `SECRETSTORE_TRANSACTION_GAP_MATRIX.md`。不要用替身类绕过生产准入来“证明可以导入”。
- 分离被证实的**单 key 安全存储能力**、未证实/已观察到的 **many-key+root transaction**、输入密码验证与用户未来直接导入政策。用户已经批准标准直接导入的产品目标，但**本轮没有授权生产迁移**，更不允许临时为了导入而去掉 `CREDENTIAL_APPROVAL_REQUIRED`。
- 禁止读取四份受保护文件、个人现有数据 root、任何真实 `.cbbackup`、Windows 用户 SecretStore、真实 API Key/NovelAI/Fish 调用，禁止 GUI/Android/旧 exe/安装器/主仓任意清理、upstream sync、正式 baseline 更新、`desktop` merge。
- 推送只含 test-only/新 evidence 的同一 feature，附真实远端 SHA、完整 diff、exact source evidence；停在 **P11-S2-G1A Project Evidence Review Gate**。G1A 的完成不能单独批准 S2-G2 生产域、跨密钥写入、R07 optional secrets export、C2 advanced UX、完整 D-038 或 Preview。后续由 Project 根据真实缺口写受限生产架构和新独立工程合同。

**P11/D-038/Post-P8/Public Preview HOLD 不变，正式 validated upstream 1.4.1、不合并 desktop、不修改 Prompt。**
