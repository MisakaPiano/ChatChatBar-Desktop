# 149 — P11-S1 Project Code Review: R1 Required (Unknown Entity Admission)

**CURRENT PROJECT REVIEW — 2026-10-23 JST — S1 ENGINEERING TESTS PASS / FINAL QUALIFIED ACCEPTANCE HOLD / R1 REQUIRED.**

本文件只审查 S1 只读图验证器，不改变 [147 S0 CLOSED](147_P11_FULL_BACKUP_V1_V2_READONLY_S0_PROJECT_ACCEPTANCE.md)；不能将 S1 测试通过升级为 S1 正式结案、Moments 生产封存、完整 D-038 或 Public Preview 验收。

## 精确提交与来源

- 控制父提交：`3ec2a3239a296713b79303aff7e1aa41928d8e53`。
- [S1 已测试源码/证据提交](https://github.com/MisakaPiano/ChatChatBar-Desktop/commit/45da3251f74c85c29e36233ef70000f24f67e630) `45da3251f74c85c29e36233ef70000f24f67e630`；[最终仅回执提交](https://github.com/MisakaPiano/ChatChatBar-Desktop/commit/dc7d8888f7f0267a9cd1ca3c2314d414fd953c12) `dc7d8888f7f0267a9cd1ca3c2314d414fd953c12`。
- [交接](evidence/p11-s1-moments-readonly-20261023/PROJECT_HANDOFF.md)、[测试](evidence/p11-s1-moments-readonly-20261023/TEST_EXECUTION.md)、[限制](evidence/p11-s1-moments-readonly-20261023/COVERAGE_AND_LIMITS.md)、[交付身份](evidence/p11-s1-moments-readonly-20261023/DELIVERY.md)。原材料只读，未改写历史 RED/日志/夹具。
- 独立 Project 读到 `MomentArchiveReadOnlyValidator.kt` Git blob `507811ae75d151354d1de354f2ded70ae8542381`，`MomentArchiveSnapshots.kt` `fdb5707833dbbfa747b356b84c753ce49127114e`，`DesktopBackupReadOnlyInspector.kt` `083739f7c2136b04da8b5cda93990cffb4f127ff`。对照固定官方 1.4.5/6/7：MomentEntities blob 在三版均为 `7d605028aca4f3c3cdb1bd4259b05c8d9b043a21`；BackupHistoricalResourcePolicy 于 1.4.5 不存在、1.4.6/1.4.7 为 `128602f71359aa676a36844f160b1904aec893ff`。
- GitHub feature HEAD 于审查开始实际为 `dc7d8888...`，`desktop` 仍 `5ef95428421f7c693b010ac88f51d470804ab07a`；未发生上游同步或合并。

## 已通过的范围

- Source seam：`inspectMoments` 在原 S0 完整读取/认证/ZIP32/CRC/inventory/manifest/scope 检验后的私有回调调用 S1；正常 `inspect` 依旧不证明资源图，原恢复 `Prepared` / `scheduleRestore` 未放宽。
- 记录：独立临时 DTO、Post/Task 固定字段和状态枚举、source SHA + 原 JSON SHA/长度、不重序列化原档，不生成存储域。
- 图：senderAvatar、imagePath、generatedImageMetadata.imagePath 分别枚举；共享资源不折叠边，支持声明过的 code85/86 顶层历史缺头像，code84 保持严格，外来路径/未知嵌套字段拒绝；Post/Task 所有权与关联在已知结构下归类。
- 运行能力：`restoreSupported` / `fullPortabilityAccepted` / `persistedSeal` / `taskRunnable` / `schedulerActivated` 均固定 false；不接 SecretStore、Moments Repository、Scheduler、GUI、root 或正式事务。
- 已逐个读取最终 8 份 JUnit XML，测试数依次为 18+1+10+14+12+4+10+20 = **89，0 failures/errors/skips**；`green-final-exit.txt` 为 0。该结论是提交证据审查，**不是 Project 再次本机运行测试**。RED 记录 S0 不具有 Moments 图证明能力；较早 compilation/fixture 失败仍按原时点保留。

## R1 blocking finding A — 未登记 Entity 家族造成图通过的错误准入

**结论：静态源码证明存在假阳性路径；未独立运行该特定反例。**

1. `DesktopBackupReadOnlyInspector.scan` 对 `entities/` 下 **不在 `DesktopBackupPolicy.schemas` 中的类型**只进行 JSON 解析、凭据已知键扫描、`uninterpreted++`；它仍会校验 inventory/hash/counts 并允许返回格式只读成功。这是 S0 的既有、应保留的行为。
2. `MomentArchiveReadOnlyValidator.validate` 只实际处理 type == `moment_posts` / `moment_tasks`；未拒绝 S0 识别为 uninterpreted 的其他 Entity 家族。若合法 Post/Task、已知依赖/三条图均闭合且没有已知凭据字段，最后可返回 `SOURCE_GRAPH_VERIFIED_WITH_SCOPE`。
3. 合成反例：现有绿色 owners + `entities/moment_posts/p.json` + 额外 `entities/moment_extension/x.json`（严格有效 JSON，含无法解释的新引用字段）；manifest counts/inventory/hash/scope 均按真实数据构造。预期：S0 的 `inspect` 仍为 `formatReadable=true`、`uninterpretedEntities>=1`，但 **S1 必须为 BLOCKED/UNVERIFIED**，不得给出 source graph verified。另一反例：只有 `entities/moment_future/x.json`、无 Post/Task，不可在未知相关家族存在时给出可信的 `NOT_APPLICABLE`。
4. 原 [148](148_P11_S1_MOMENTS_ARCHIVE_READONLY_VALIDATION_CODEX_CONTRACT.md) 第 2/3/4 节要求未知源结构不能返回完整图通过；原 S1 测试仅验证 Moment 内部未知字段、类型和 metadata，而非额外未知 Entity 家族。这里的失败是**图验证结果准入错误**，不是已证实秘密泄漏、写入或恢复绕过。

**最小修复：** S1 验证前或最终成功判定前扫描 S0 已解析的 entities family；允许当前已经严格解析的 `DesktopBackupPolicy.schemas` 类型，以及 S1 明确支持的 `moment_posts` / `moment_tasks`；其他家族 fail-closed 为固定类别 BLOCKED。不得改变 S0 正常 `inspect` 对未知家族的格式只读计数行为，不放宽现有 restore。

## R1 finding B — sender 缺卡的原因归类（同次修复）

`MomentArchiveReadOnlyValidator.owners` 在 `senderCharacterId != null && card == null` 时，无条件将 SENDER 归为 `NOT_SELECTED`；但 v1/code84 的无 scope 全量包缺少被引用卡时，CARD 已被归为 `MISSING`，SENDER 并不能被认定为“未选”。修复需继承/显式表达 CARD 的未选/缺失分类，且对 v2 仅有 scope 计数时不可臆造特定 ID 是否被选中的事实。分类精度修复不能使未知依赖升级为 VERIFIED，不能回填 live 卡。

## 非 blocker 的明确解释边界

- `OMITTED_BY_SCOPE` 对 v2 momentsImages=false 的空投影仅表示**源备份策略省略该字段的可能性/选项语义**，不能证明导出前原始 Post 曾有一张实际图片。今后 UI 或 seal 不得把它叙述成可恢复的原图片证据。
- 已知来源图可读不等于全部 archive 内容已理解；任意正文仍不宣称无秘密。只有未知类型 fail-closed 后的 **已知所选图** 才有资格标 `SOURCE_GRAPH_VERIFIED_WITH_SCOPE`，依然不可恢复、封存或执行。

## 停止条件与下一步

**S1 工程结果：TESTS PASS / SOURCE REVIEW FINDING / FINAL ACCEPTANCE HOLD — R1 REQUIRED**。按 [150 R1 Codex 指令](150_P11_S1_R1_GRAPH_ADMISSION_CODEX_CONTRACT.md) 最小修复并提交新的真实源码/测试/原始证据；Project 再次审查后才决定 S1 限定结案。

没有授权 S2 持久化封存/GC/快照/降级防写、S3 再导出、S4 合并、S5 凭据、S6 Android/GUI/真机、S1 GUI、外来凭据放行、Task 激活、正式 baseline 更改、`desktop` 合并或 Public Preview。P11 总体 PENDING、D-038 BLOCKED、Post-P8 HOLD、Preview A–G 未签保持不变。本轮仅 docs-only Project Review，**没有新执行代码测试**。
