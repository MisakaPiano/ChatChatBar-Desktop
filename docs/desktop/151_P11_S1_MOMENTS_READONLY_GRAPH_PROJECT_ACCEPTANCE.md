# 151 — P11-S1 Moments Read-Only Graph: Project Final Qualified Acceptance

**CURRENT PROJECT DECISION — 2026-10-23 JST — P11-S1 CLOSED / QUALIFIED ENGINEERING ACCEPTED WITH DECLARED LIMITS.**

仅关闭 P11-S1 全应用 `.cbbackup` 中 Moments Post/Task、三路径资源引用图的**有界只读验证**。已独立接受的 S0 仍由 [147](147_P11_FULL_BACKUP_V1_V2_READONLY_S0_PROJECT_ACCEPTANCE.md) 控制。[149](149_P11_S1_PROJECT_CODE_REVIEW_R1_REQUIRED.md) 是修复前历史问题和审查，[150](150_P11_S1_R1_GRAPH_ADMISSION_CODEX_CONTRACT.md) 是已完成的修复任务；不能再把它们的 HOLD/NOT IMPLEMENTED 当最新结论。

## 1. Git 与源码身份

- 初始 S1 已测试提交 `45da3251f74c85c29e36233ef70000f24f67e630`，交付回执 `dc7d8888f7f0267a9cd1ca3c2314d414fd953c12`：旧 8 suites/89 PASS，但 Project 审查发现结果准入问题未结案。
- R1 **已测试源码**：[c658b08ac6aecb859df2deeab282eb5db11bd835](https://github.com/MisakaPiano/ChatChatBar-Desktop/commit/c658b08ac6aecb859df2deeab282eb5db11bd835)；只补回执的最终提交：[cd5c2a129c434b4ce7d1960df859b64b7812c696](https://github.com/MisakaPiano/ChatChatBar-Desktop/commit/cd5c2a129c434b4ce7d1960df859b64b7812c696)。自 [149] 控制点 `3fc458135b68c5c03623d027bc3c1c5c0c06b594` 线性快进两笔。R1 只改 `MomentArchiveReadOnlyValidator.kt` 与对应测试；S0、Codec、Archive、Service、恢复事务、Startup、SecretStore、Entity、Prompt 和 Android 均未改。
- R1 原始 [交接](evidence/p11-s1-r1-graph-20261023/PROJECT_HANDOFF.md)、[测试](evidence/p11-s1-r1-graph-20261023/TEST_EVIDENCE.md)、[交付](evidence/p11-s1-r1-graph-20261023/DELIVERY.md)；旧 S1 [证据](evidence/p11-s1-moments-readonly-20261023/PROJECT_HANDOFF.md) 不改写。Project 审查时 fork `desktop` 仍 `5ef95428421f7c693b010ac88f51d470804ab07a`。

## 2. 独立 Code Review 与证据结论

| Gate | Project 观察 | 证据边界 |
|---|---|---|
| Git 精确差异 | PASS：生产 S1 validator +15/-5 行，六项测试新增；最终 commit 只写交付回执 | 无合并/新持久域 |
| 实际 RED | PASS：`raw/red.xml` 3 tests、3 assertion failures、exit1：错误 `SOURCE_GRAPH_VERIFIED_WITH_SCOPE`、`NOT_APPLICABLE`、sender `NOT_SELECTED` 均出现 | 历史 RED 保留，不假称零历史失败 |
| 当前 GREEN | PASS（提交证据核对）：8 份最终 JUnit XML 逐份核对 **95 tests / 0 failures / 0 errors / 0 skipped / exit0**；S1 16、S0 Inspector18、旧恢复拒绝1、Archive12、Service10、Journal4、RootOwnership14、Startup20。原 S1 十项与其他旧 suites 在本轮同一次测试中重跑，非借用旧 89 数字。 | Project 未在本机重新执行 Gradle |
| 源码身份 | PASS：4 个当前测试/夹具与 10 个 S0/恢复/根/秘密安全边界的 Git blob，**14/14** 与 raw source/boundary identities 匹配 | 不替代所有系统集成测试 |
| raw SHA | PASS（抽查）：按原始 bytes 独立 SHA-256 自检并复算 11 个代表性 RED、JUnit、结果、加密/明文合成 `.cbbackup` 文件，**11/11** 长度和 SHA-256 匹配。其余 20 条夹具、41 条 raw index entries 仍为 Codex 提交证据，**不宣称逐一独立重算**。 | 无真实档案 |
| 官方选定版本 | PASS（SOURCE-VERIFIED）：1.4.5/6/7 Moments Entity 定义同 blob；1.4.5/code84 与 code85/86 历史缺头像规则不同。正式 upstream validated baseline 仍 1.4.1。 | 未在本次运行官方新版 APK |

## 3. R1 修复闭合

**A / UNKNOWN_ENTITY_FAMILY：** S0 对不理解的 Entity 仍可报告 `formatReadable` 和 `uninterpretedEntities`，不放宽恢复；S1 在图验证、`NOT_APPLICABLE` 返回之前检查全部已解析实体家族。只有 S0 严格 serializer 已验证的 Desktop 登记类型和 S1 专门支持的 moment_posts/moment_tasks 可以进入 source graph 判断；其它类型固定返回 `BLOCKED / UNKNOWN_ENTITY_FAMILY`，不回显源类型名、字段、路径或正文。真实 RED 与最终 GREEN 都覆盖合法 Post+额外 unknown 及只有 unknown 的 v1/v2、明文/加密。

**B / sender 卡选择：** 无 scope 的 v1 全量输入缺卡时 CARD/SENDER 为 `MISSING`；v2 scope 数量为 0 才能标 `NOT_SELECTED`，聚合数量大于 0 但目标 card ID 缺失时标 `UNKNOWN_SELECTION`。二者仍为 `UNRESOLVED_DEPENDENCIES`，不能借本机同 ID 的卡来补齐。已有 sender identity 匹配/重复冲突/历史 SNAPSHOT_ONLY 测试保留。

## 4. 接受边界与持续 HOLD

- S1 只验证 S0 已完成认证、ZIP32 local/central/CRC、inventory/hash、manifest/scope 的有界来源：源与解密 ZIP 各 <=64 MiB，展开累计 <=128 MiB，单 JSON <=8 MiB，条目仍受 S0 限额。精确源 archive SHA、record 原 bytes SHA/length，三独立图片引用 senderAvatar/imagePath/generatedImageMetadata.imagePath 和 Post/Task/角色/会话图分类。
- `SOURCE_GRAPH_VERIFIED_WITH_SCOPE` 仅意味着**当前理解的、输入内可证的 Moments 来源图**，不表示任意正文无密钥、不证明未选资源/其他整个 archive、嵌套 SaveSlot、真实跨平台恢复。`OMITTED_BY_SCOPE` 只描述源导出的省略策略，不证明导出前一定有图片。外部 URI、未知结构、已知外来凭据仍拒绝晋升有效图。
- 所有 S1 报告 `restoreSupported=false`、`fullPortabilityAccepted=false`、`persistedSeal=false`、`taskRunnable=false`、`schedulerActivated=false`；未创建生产持久化封存、GC/备份/快照/root/Portable/启动恢复、C1/C2、可运行 Task 或 Android 官方回导能力。

**S1=CLOSED/限定工程接受；P11 整体=CLOSED/ACCEPTED PENDING；D-038=BLOCKED；Post-P8 HOLD 不变；Public Preview Gate A–G 未签；P8–P11 未合并 `desktop`；正式 baseline 仍 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8` (1.4.1)。**

下一步文档：[152 S2 投产前决策与安全门禁](152_P11_S2_PERSISTENT_PRESERVATION_READINESS_AND_DECISIONS.md)，[153 S2-G0 合成探针 Codex 合同](153_P11_S2_G0_SYNTHETIC_LIFECYCLE_PROBE_CODEX_CONTRACT.md)。两者均**不授权 S2 生产 schema/写入**；旧版本防写、配额与保留策略、Secrets 托管仍需明确产品决策。

**本轮 docs-only Project Review；无新的 Gradle/GUI/Android/真实用户数据测试。**
