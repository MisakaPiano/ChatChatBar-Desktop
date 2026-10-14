# P9-S1 DOCUMENT RAG — candidate verification

**CODEX CANDIDATE / PROJECT REVIEW PENDING**（2026-10-15）。本文件不宣布完整 P9、P10 或 F4 parity，也不改变 Fish S2A、D-040/P11 或 Public Preview Gate。

## Git identity and authority

- 起点：`09331c07d3bbd5c9eac0a6c81682dcf680665e49`，local/tracking/live feature 一致，工作区和暂存区 clean。
- B5B 独立结案文档：`0d92c3a4df54ff3249d0241d135a37cc57508fb0`；只登记 Project 已作出的 QUALIFIED ENGINEERING ACCEPTED，没有重跑旧验证。
- P9-S1 源码候选：`372403b61736a4e8112afc506a04215f9add1fe8`，parent 为上述结案提交；4 文件 +482/-1（3 Kotlin 文件及必要 feature-map Skill 归属维护）。完整文件与 canonical Git blob、实际工作区 SHA-256 见 `source-manifest.json`。
- 正式 baseline 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8` 不变。共享 VectorSearchEngine blob `ba7a881dd53061dfb721e8c420c76ef0376f677e`、ChunkingEngine blob `06211ac148b4ed5a39897dbfa667d9dd52afe6cd` 均与正式 baseline 完全一致。核对 109、117 和 B5B 实际实现后采用它们，不改 Prompt 或 D-036/D-039。
- Android RagRepository/RagManager 不移动：其宽松查询、索引写入/清理职责不是本阶段的严格只读合同。不复制其写入实现，不调用 EmbeddingService。

## Implemented boundary

`DesktopAppContainer.documentRagReader` 使用同一 JsonFileStorage、同一 DesktopDataOperationCoordinator 和当前 root ownership。输入为完整预期 CharacterCard、显式查询向量、Embedding 配置身份（官方 baseUrl/modelName 的 SHA-256 key，加独立 dimension）、topK/threshold。身份不包含 API Key；查询向量的生成与其模型来源由调用方负责，本适配层不请求或伪造 embedding。

读取在顶层 exclusive 内完成，作为 cooperative root writers 的一致性读取屏障；没有 normal → exclusive 升级，没有写入、恢复、清理或 journal recovery。当前角色卡严格读回并比较。全 vector namespace 逐文件严格读取，验证 storage ID/Entity ID、字段、JSON、向量数值和安全路径，任何失败使整个结果 unavailable，不返回部分可信结果。

目标 DOCUMENT 必须能关联 customDocuments/originalDocId/documentId、fileName、sourceLabel、contentHash、embeddingKey。文档须为 INDEXED、完整 chunkCount，contentHash/indexedHash 与实际 owned UTF-8 文件一致；用官方 ChunkingEngine 重建分块，逐块核对正文/metadata 的多重集合，拒绝缺块、重复替代块、来源矛盾和旧索引。嵌入值必须与维度匹配且 Float 范数有效；拒绝非有限值、零范数、范数溢出/下溢，随后直接调用共享 cosineSimilarity，不另写余弦算法。

队列只保留 topK 输出正文/身份/分数，不保留全库 embedding。同分按 chunk ID 升序，等价于共享 search 对 chunk-ID 升序输入的稳定排序，已做 parity 对照。结果标明 chunk/card/document/sourceLabel/embeddingKey/similarity；CHAT_MEMORY 和 CHARACTER_SETTING 不进入 DOCUMENT 结果。

## Conservative limits / not implemented

- topK 0–1000、维度 1–65536、单实体 8 MiB、每份 owned document 1 MiB、每卡最多 64 documents；超限拒绝，不截断为成功。
- 同时只保留一个有界文档分块计划及一个解码向量文件；1200 文件测试峰值候选数为 7。算法以重新扫描换取有界内存，复杂度约 O(documents × vector files)；未声称大库性能或物理内存绝对峰值已验收。
- 首批只支持数据根 `documents/` 中直接 owned 文件。Android absolute path、assets、异常目录、reparse/symlink、ADS/traversal 等不安全引用拒绝；不自动迁移、修复或删除旧数据。尚不可判定的旧索引保持 fail-closed。
- exclusive 只协调当前 CCB 的 cooperative writers，不是操作系统文件系统事务；阻塞文件 I/O 无物理绝对超时保证。
- 未接入 PromptAssembler、MainChatPromptAuthority、chat request planner 或模型发送路径；planner 仍无 RAG 注入。真实 container planner → serializer 在读取前后完全相等，实际 localhost chat transport 回归也通过。
- 没有自动索引、CHAT_MEMORY 查询/重建、聊天历史来源变更、Memory runtime、RAG UI、真实 Embedding API、付费 Provider、schema/Package 改动。B5B production gate 原样保留。

## Commands, compilation and tests

Temurin 17.0.20.1、Gradle 9.1.0、现有 2GB heap；所有阶段从 app root 执行。同一时间只运行一个 Gradle build：

```text
--offline --no-daemon --no-parallel --max-workers=1 --priority=low
--console=plain --no-configuration-cache -Pkotlin.compiler.execution.strategy=in-process
```

每阶段完整参数数组、退出码和时间见 `gradle/<stage>.json`，原始 stdout/stderr 见同名 `.log`；没有 clean、rerun-tasks 或安装工具。

| Stage | Result | Evidence |
| --- | --- | --- |
| 01 compile | RED exit 1：测试夹具缺 SecretStore import、两个 required assembly args | gradle/01-compile.* |
| 02 reader | RED exit 1：夹具将非 nullable String 错填 null | gradle/02-reader-tests.* |
| 03 reader | RED exit 1：15 tests / 6 failures；payload snapshot 误读 Windows ownership lock，取消测试清理随后 CLOSING | junit-03-red / gradle/03-reader-tests.* |
| 04 reader | RED exit 1：15 tests / 1 failure；有限 Float 的平方范数可溢出并被误当零分 | junit-04-red / gradle/04-reader-tests.* |
| 05 reader | PASS exit 0：1 suite / 15 tests / 0 failures/errors/skips | junit-05-reader-green |
| 06 Desktop final | PASS exit 0：11 suites / 125 tests / 0 failures/errors/skips | junit-06-desktop-final |
| 07 SharedCore final | PASS exit 0：8 suites / 68 tests / 0 failures/errors/skips | junit-07-shared-final |

最终 Desktop main/test 编译在 05 实际执行成功，06 为 UP-TO-DATE。SharedCore main/test 编译检查为 UP-TO-DATE（没有共享源码改动），07 test 实际执行；不冒充重新编译。Android 无源码移动或接口改动，编译 **NOT RUN**。最终 B5B 原 8 Desktop suites/105 tests 仍 PASS；另加 RAG 15、main-chat assembly 1、real-chat transport 4，合计 125。

共保留 22 份历史/最终原始 XML，逐份统计与 SHA-256 见 `junit-manifest.json`；日志哈希见 `log-manifest.json`。XML/log scoped -text 禁止换行归一化；上传前检查仅含合成测试与 localhost/invalid.example 证据，无真实用户数据、凭据、私钥或完整敏感诊断日志。既有 B5B 44 份证据不改写。

首次 evidence staged diff-check 因原始 Windows CRLF 和 Gradle 日志空白返回 2；没有裁剪原始证据。仅此目录 XML/log 使用 scoped `-whitespace` 保全原始 transcript 字节，源码和 Markdown 仍执行正常 diff-check。最终 XML/log 的 Git blob SHA-256 逐项与原始文件/manifest 相等；此属性不改变任何测试或失败结果。

## Resource observations

预检可用物理内存约 2.49 GiB、Commit 余量 12.43 GiB、Pages/sec 0，无 Java 进程；H: 约 38.01 GiB 可用。执行期低频有效采样最低可用约 823.6 MiB，最低 Commit 余量约 10.34 GiB，最高观察 Pages/sec 1972，未达到持续严重保护阈值、未 OOM。`resources.jsonl` 只保存 PID/WS/Private Bytes 和系统指标，不保存 CommandLine、窗口或浏览器内容。执行时 JVM 含 wrapper、单用 Gradle JVM，测试时另有一个 test worker；实际总峰值 UNKNOWN，未使用高频探针。未操作 Chrome/NAI、真实用户数据或历史进程。

**FULL REGRESSION / WINDOWS MANUAL / PACKAGING：NOT RUN。P9-S1 PROJECT REVIEW：PENDING。**
