# 162 — P11-S2-G1A Credential/Full-Backup Transaction: Project Evidence Acceptance

**CURRENT PROJECT DECISION — 2026-10-23 JST — G1A CLOSED / QUALIFIED EXPERIMENTAL EVIDENCE ACCEPTED WITH DECLARED LIMITS.**

仅关闭 G1A 对**现存** WindowsSecretStore、用户导出备份凭据字段与 Desktop 根 journal 之间安全边界的合成调查；不签 S2 生产持久化、不证明用户凭据已经能安全直接迁入，也不替代 D-038/P11/Public Preview Gate。用户已批准的最终产品目标为 [158 R07](158_P11_S2_G1_R07_DIRECT_IMPORT_PASSWORD_AND_CREDENTIAL_POLICY.md)：不能仅因来源 `.cbbackup` 含用户自己的 API Key 整包拒绝，正常兼容时应与数据一致、安全迁入受保护 SecretStore。

## 1. 冻结 Git/证据身份

- 起点 `ed8f54236cd29b822f3a8875218b3ba7043eab5f`；真正**已测试源码/主证据** [c3f6cc4deff2b0c710f908b0e85dcb036336daba](https://github.com/MisakaPiano/ChatChatBar-Desktop/commit/c3f6cc4deff2b0c710f908b0e85dcb036336daba)，仅交付回执的最终 HEAD [5fd54c405734ab39c0df5fd11a72ddbef56c6439](https://github.com/MisakaPiano/ChatChatBar-Desktop/commit/5fd54c405734ab39c0df5fd11a72ddbef56c6439)。两笔线性快进，新增 398 行 `P11S2CredentialTransactionBoundaryProbeTest.kt` 与本轮证据目录；三个 `src/main` 生产子树、Codec/Archive/SecretStore/Journal/GUI/Gradle/Prompt 均未修改。`desktop` 仍 `5ef95428421f7c693b010ac88f51d470804ab07a`。
- 原始 [交接](evidence/p11-s2-g1a-credential-boundary-20261023/PROJECT_HANDOFF.md)、[故障矩阵](evidence/p11-s2-g1a-credential-boundary-20261023/SECRETSTORE_TRANSACTION_GAP_MATRIX.md)、[源码审计](evidence/p11-s2-g1a-credential-boundary-20261023/SOURCE_AUDIT.md)、[测试](evidence/p11-s2-g1a-credential-boundary-20261023/TEST_EXECUTION.md)、[交付回执](evidence/p11-s2-g1a-credential-boundary-20261023/DELIVERY.md) 及其 raw 保持原状。初始17、扩展112、最终验证112分开保存；只能取**最后一次112**作为最终测试。此前一次长路径导致的**证据复制**不完整被明显标记 partial；其后短路径 `raw/green/` 保留同一次最终完整执行，不据此声称另有测试运行。

## 2. Project 独立复核

| Gate | 结论 | 不得升级的边界 |
|---|---|---|
| Diff/生产漂移 | PASS：仅新增 test-only 类与原始证据；当前生产树与起点一致；原 feature 没被合并 `desktop` | 没有生产行为修复。 |
| 原始 JUnit | PASS：Project 独立读取最后一次全部 **12 suites/112 tests、0 failure/error/skipped，exit0**；17 项新 G1A 探针，其余 95 项既有 Windows SecretStore/Model/Embedding/Archive/Journal/Startup 等回归均在**同次最终执行**。 | Project 未在本机重新执行 JDK/Gradle；测试名中的 RED 是已接受旧门禁拒绝测试，不代表新增失败。 |
| Git blob 身份 | PASS：实际 Git tree 对照 **21/21** 生产边界、**15/15** 测试/fixture 及 **9/9** 固定官方 1.4.5 源码/技能 blob 全部一致；三棵 `src/main` 生产 tree Identity 与起点一致。 | 只审查相关源码，不是全仓行为验收。 |
| 原始 SHA | PASS（Project sampling）：GitHub 上按原始 byte 独立重算 **18/18** 代表性 log/XML、source identities、目录/密钥状态、认证负例与退出码的 SHA-256/size；Codex 报告其 Git archive 对 **1773/1773** raw 文件全量校验，**Project 没有重复执行全部1773项**。 | fake protected bytes 使用可逆 fake protector，不证明 Windows DPAPI/真实密钥机密性。 |

## 3. 确认的技术缺口（观察属于合成故障注入）

- **每把 key 的原子性不等于多 key 事务**：注入第二个 SecretBlobWriter pre-publication 失败后，真正的 WindowsSecretStore 可留下 `A_NEW/B_OLD`；在第一把 key 已经完整发布后再注入异常，调用方会看到失败，但新密钥事实上已持久化（此为注入回调语义，不宣称底层 Windows rename 原生故障已实测）。
- **原有单 Entity 回滚不保证全根一致**：Model/Embedding 等 Repository 策略有单 key + Entity callback 失败回滚，在此接口的回滚写本身失败时明确报告 `DesktopCredentialConsistencyException`，但旧实体与新 key 仍可能并存；不允许用 try/catch 作为完整恢复依据。
- **Root 数据 journal 不覆盖独立 SecretStore**：合成 key-first 失败留下 `DATA_OLD/KEY_NEW`；data-first 失败留下 `DATA_NEW/KEY_OLD`；journal row 异常/取消回滚 registered data 后独立 key 不会同步回滚。StartupGate 实际执行数据 row 恢复但不参与 SecretStore 的统一 intent。由此 **CROSS_DOMAIN_ATOMICITY_NOT_PROVEN / OBSERVED_NONATOMIC_SEQUENCE**。
- **官方 v1 直接导入仍是代码缺口**：独立官方 wire DTO/codec 生成的 plain/password 两模式 × 八个类别（含 EMPTY）共 16 个来源档；两份空密钥输入可获得可关闭的既有 Prepared，其余 14 份虽可被 S0 认证、分类，却由正式 Archive 因 `CREDENTIAL_APPROVAL_REQUIRED` 拒绝。密码错误另独立拒绝并保留原始输入。接受产品 [158] 不等于允许未实现事务时简单移除这个门禁。
- **身份迁移缺口**：当前 ModelApiKey 仅按逻辑 modelId 绑定，同 ID/不同 Endpoint 仍会读到原目标 Key；名称相同/ID不同不会自动映射。Embedding legacy 与 singleton 必须分别冻结来源/目标 scope。官方 Preferences 的 `communitySession` 尚无 DesktopCredentialKey 等位保护存储目标；属于实质未映射结构，而非“含凭据本身不允许”。

## 4. 尚未运行及保留的门禁

- 新探针全部使用 WindowsSecretStore 实类和确定性**非加密 fake protector**、自行创建的 temp root/secret root；不运行真实 DPAPI、Windows 账户/LOCALAPPDATA/Portable 跨用户、强制断电、secret participant 子进程崩溃、真实安装器、GUI/Android/Provider/真实用户档案。密码/私密值没有通过新测试出现在日志的扫描由原始回执支持；不可推导全链任意未知文本无 secret。
- User [157] 与 [158] 的 C-A/nonrunning Moments、原始 JSON+独立媒体副本、不自动清除、空间不足拒新导入、不允许旧程序不安全降级、用户原来源包自持及合法 API keys 应安全直接导入的**产品政策方向保持有效**。普通 Desktop 可分享备份默认不带 SecretStore；加密含密钥导出是未来另立范围。
- **G1A=CLOSED/QUALIFIED EXPERIMENTAL EVIDENCE ACCEPTED**，而 **S2-G1B 设计验证与 S2-G2 生产实现仍未获准，P11=PENDING、D-038=BLOCKED、Post-P8 HOLD、Preview A–G 未签、formal upstream baseline 1.4.1、不合并 desktop**。

下一步 Project 文档 [163](163_P11_S2_G1B_UNIFIED_DATA_SECRET_TRANSACTION_DESIGN_GATE.md) 汇总跨数据+密钥状态机设计；[164](164_P11_S2_G2A_BOUNDED_IMPLEMENTATION_CODEX_CONTRACT_DRAFT_NOT_AUTHORIZED.md) 预备 Codex 的最小生产切片合同，**禁止在未取得用户明确工程实施批准前发送为可执行任务**。

本文件 docs-only Project Review，没有本地运行测试、实际文件恢复、SecretStore 写入或改变任何生产源码。
