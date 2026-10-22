# 150 — P11-S1 R1 Unknown-Entity Graph Admission — Codex Task

**TASK READY / 2026-10-23 JST — S1 FINAL ACCEPTANCE HOLD / R1 FIX ONLY.**

## 任务目标与权威

修复 Project [149 S1 Code Review](149_P11_S1_PROJECT_CODE_REVIEW_R1_REQUIRED.md) 所确认的 **S1 假阳性图验证准入**，并同时修正 sender 缺卡时的原因归类。维持所有已通过的 S1/S0 安全合同；不得开启 S2 或任何恢复功能。

- Repo：`MisakaPiano/ChatChatBar-Desktop`，分支 `feature/phase8-fish-audio`，本任务发出前远端实查 `dc7d8888f7f0267a9cd1ca3c2314d414fd953c12`。**开始前先检查新的远端 HEAD、本地工作树和所有未提交文件**；若分叉/变更未授权，保留并 STOP，不 reset/force/覆盖文件。Project 的后续 docs-only 提交如已进入同一线性分支，保留并只作安全快进。
- 已测试 S1 源码：`45da3251f74c85c29e36233ef70000f24f67e630`；交付回执：`dc7d8888...`。S0 已验收的 [147](147_P11_FULL_BACKUP_V1_V2_READONLY_S0_PROJECT_ACCEPTANCE.md)、原 S1 工程合同 [148](148_P11_S1_MOMENTS_ARCHIVE_READONLY_VALIDATION_CODEX_CONTRACT.md)、Project Review [149](149_P11_S1_PROJECT_CODE_REVIEW_R1_REQUIRED.md) 和原 [S1 证据](evidence/p11-s1-moments-readonly-20261023/PROJECT_HANDOFF.md) 均必须读取。按 00/10/21、相关官方 backup/Moments/SaveSlot skills、Desktop backup skill、13/14 与 93/95 的顺序做最小 preflight。
- 正式 upstream validated baseline 仍为 ChatBar 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`；官方 1.4.5/1.4.6/1.4.7 仅固定格式来源，不自动同步。

## 限定允许修改

- 首选仅更改 `app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/MomentArchiveReadOnlyValidator.kt` 和 `app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/backup/MomentArchiveReadOnlyValidatorTest.kt`；必要时极小调整私有 S1-only 结果枚举或测试辅助。S0 `DesktopBackupReadOnlyInspector.inspect` 的格式读取准入、`AppBackupCodec` 和全部恢复链不得修改。
- 原 8 suite/89 tests 的负例、已知图、三处媒体边、缺头像版本条件、认证/ZIP/inventory/hash、SourceHash、随机测试凭据和无落盘性质全部保留；不重写 Prompt、正式 Entity、SecretStore、Moment Repository/Scheduler、Main/GUI/Android、root/GC/journal/Portable。
- 若发现必须改 S0 主扫描/正式恢复/未审 schema 才能解决，报告 BLOCKED 和最小必要 diff 建议，**不超范围实施**。

## R1 必须 RED → GREEN 的测试

**A — 未知 Entity 家族安全图准入（blocking）**

1. 构造官方 R1 精确 wire 合成 `.cbbackup`：合法 `entities/moment_posts/p.json` + 完整合成卡/会话 + 额外 `entities/moment_extension/x.json`，其中包含未知嵌套路径/行为字段。正确生成 inventory/manifest/counts/sha；输入只用合成无秘密数据。
2. **修复前**先证明：`DesktopBackupReadOnlyInspector().inspect(...)` 给 `formatReadable=true`、`uninterpretedEntities>=1`；而 `inspectMoments(...)` 错误给 `SOURCE_GRAPH_VERIFIED_WITH_SCOPE`。保留真正的 JUnit RED、测试源码、退出码；如实际行为不同，应报告真实行为，不能人为伪造 RED。
3. **修复后**：S0 原样继续只读报告 unknown count；S1 对任何本轮未登记/无法理解的 Entity 家族返回 `BLOCKED`（或明确不得晋升的 UNVERIFIED 状态），输出仅固定安全错误类别、不泄露任何原始字段/路径/正文；不能仅忽略 extra family 继续图通过。
4. 无已知 Post/Task、仅 `entities/moment_future/x.json` 时不能以 `NOT_APPLICABLE` 隐藏未知潜在 Moments 结构；含 Moment 的 v1/code84、v2/code85/86，明文/加密至少覆盖代表性矩阵。
5. 与之相反，**当前已知且严格解析的非 Moments Entity 类型**（如 CharacterCard、ChatSession、AppSettings 的无秘密合成合法字段）不得被误标 UNKNOWN；全部合法 Moment/媒体/Task 结果维持旧 GREEN。含已知模型/偏好凭据继续 BLOCKED；`arbitraryTextCredentialFree=false` 语义不改。

**B — Sender 缺卡归类一致性**

6. Post 的 `senderCharacterId` 非空、卡在 v1/code84 全量档案缺失时，CARD/SENDER 不应同时出现互相矛盾的 “MISSING” 与 “NOT_SELECTED”。对于 v2，scope 只有数量，不一定能证明具体 card ID 已被选择，必要时保留 `UNRESOLVED/UNKNOWN_SELECTION` 等保守分类；不能借目标本机同 ID 实体填洞。
7. 没有 senderCharacterId 的历史快照按既有合同标 `SNAPSHOT_ONLY`；card 已存在且 sender ID 解析/冲突测试继续通过。分类调整不能将原来 UNRESOLVED/BLOCKED 的案例提升为 VERIFIED。

**C — 稳定性/保全**

8. 运行完整受影响的原 8 suites（S1 10 + S0 18 + old restore denial1 + Archive12 + Service10 + Journal4 + RootOwnership14 + Startup20）；记录每个 suite 的当前真实测试数，新增测试后总数允许增长，不能将历史测试数算作本轮执行。原始失败、编译错误和老材料不覆盖。
9. 额外未知字段不能改变原档案字节、生成明文文件、Task/网络请求或 SecretStore 访问；取消、认证失败、硬退出和既有 v2 Prepared 拒绝必须保留。支持的范围仍是 S0 64 MiB 输入/解密 ZIP、128 MiB 展开、ZIP32；不扩大预算。

## 交付和停止

- 新独立证据目录：`docs/desktop/evidence/p11-s1-r1-graph-20261023/`，包含 Project handoff、RED/GREEN log+原始 XML+exit、受影响回归列表、source/fixture SHA+长度/准确 Git blob、test preserved delta 和确切不变的生产链边界。注意本 R1 属 S1 生产源码的**最小纠错**，不能以“仅测试变更”假装没有代码 diff。
- 只运行本机隔离 headless Windows/JDK17 JVM tests。无用户真实 root、真实 `.cbbackup`/`.cbsave`、四份受保护用户文件、Android 模拟器/ADB/设备、Windows GUI、真实 API/Fish/NovelAI/第三方网络、数据迁移、安装或大规模磁盘空间实验。
- 清楚记录 SOURCE REVIEW ISSUE A/B 已纠正的证据和限制，不宣称 P11、D-038、Preview 兼容完成；不修改 `UPSTREAM_BASELINE.json`，不同步 upstream、不合并 `desktop`、不开始 S2–S6、C1/C2 或 Task 激活。
- 推送原 feature 分支后停在 **P11-S1 R1 Project Code Review Gate**，提供**真实远端 SHA**、从本任务前控制点的完整 diff、证据链接；Project 决定是否限定接受 S1。
