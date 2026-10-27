# P9-S2A Document Index Preparation — narrow implementation contract

日期：2026-10-15。用户已批准限定实现，**CODEX CANDIDATE / PROJECT REVIEW PENDING**；本合同不授权索引发布。正式 validated baseline 仍是 1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。前置 authority：119 P9-S1 验收、120 P9-S2 写入安全审计、118 B5B 验收、官方 RagManager/RagRepository 与共享 ChunkingEngine/VectorChunk。

## Read-only pipeline

新增 DesktopDocumentRagIndexPreparation，由 AppContainer 显式传入离线 gateway 建立，使用同一 root/storage/coordinator/task runtime，无默认 HTTP 实现、不初始化或读取 SecretStore。输入是严格的 card/document ID 与明确的 persisted Embedding 配置引用（当前 singleton `embedding_model_config/default` 或 legacy `embedding_configs/{id}`），不混淆两类来源。

1. 进入顶层 exclusive read scope，验证 ownership/coordinator 可用；uncached `visitEntitiesStrict` 逐文件读取 CharacterCard 和选定配置 namespace，拒绝损坏 JSON、未知字段、非法 storageId/实体身份。目标 DocumentInfo 必须唯一，card 文档集 <=64。
2. 复用 P9-S1 owned `documents/` 路径、普通文件、祖先和 Windows reparse 检查、严格 UTF-8 与 1MiB 读取预算。用实际内容 SHA-256 建立来源快照，不把 DocumentInfo 的旧 contentHash/indexedHash 当成当前证明。
3. PENDING 与 INDEXED 文档均可准备；INDEXING/未知状态等保守拒绝。保留完整 card/document/config 快照；报告 recorded hash 是否匹配，不写回任何状态。
4. 同一内部 helper 原样复用 P9-S1 分块前预算：文件名256字符、标题128、来源标签1024、分节/保守chunk数2048、预计输出2Mi UTF-16字符；先拒绝再调用唯一共享 ChunkingEngine。不是第二套 chunker，不截断后冒充成功。空计划拒绝。
5. 离开 exclusive 后按顺序短生命周期批次调用离线 gateway。每批最多16个向量、65536个浮点元素（按配置维度缩小 batch），维度1..65536；要求数量完全一致、有限非零且符合共享Float范数有效域（拒绝溢出/下溢零范数）。前一批向量校验后只保留摘要，不积累全库 embedding。
6. 最终再次进入同一个顶层 exclusive，重读完整 card/document/config，重新验证 owned 路径、实际内容 SHA-256、ownership 和 coordinator 状态；有变化则 STALE/REJECTED，无旧结果发布。源文件读取/同步阻塞IO没有物理绝对超时保证。

## Ownership and results

TaskRuntime 仅增加结构化准备任务登记：caller 的 coroutineScope 拥有子任务，应用 activeJobs 同时登记并纳入 closeAndDrain；相同 card/document pair 禁止重复，取消/异常/预分派取消释放登记。应用关闭取消并 join 登记任务，不取消无关 caller/浏览器，不借用 REAL_CHAT/NOVELAI，不改变既有会话任务准入。模型/Embedding 阶段绝不持有 exclusive，不使用 GlobalScope 或脱离任务的 callback。

结果 PREPARED_ONLY 只含来源/配置身份、真实 hash、顺序摘要、数量与批次上界；没有可自动提交的持久候选、正文、API key 或 embedding 明文。REJECTED 只有固定错误类别，STALE 不返回有效计划。调用者取消传播 CancellationException；应用取消准备但 caller 仍有效时可以返回明确 CANCELLED。无自动重试、旧候选缓存或部分成功。

这只是最终来源重证，**不是 proof-to-CAS 事务、INDEXED 或 COMMITTED**。既有生产向量不需要存在，也不删除/替换/修复/迁移。合法旧索引及未知/损坏向量均原样保留；本阶段不读取它们来推断发布资格。存储准入复用现有 coordinator，不增加 root writer 锁。

## Files and boundaries

预计修改 DesktopDocumentRagReader（仅 helper 抽取）、新增 DesktopDocumentRagSource 与 DesktopDocumentRagIndexPreparation、AppContainer 工厂、TaskRuntime 最小登记/关闭适配、准备测试及直接相关回归；如稳定入口变动令 feature-map 过时，则精确更新技能路径。不得修改 sharedCore/Android 业务逻辑、Prompt/最终请求、Entity/schema、SaveSlot Inspector、B5B gate 或 Memory journal。

## Verification

合成同-root AppContainer fixture；真实共享 chunk metadata/顺序对照；PENDING/INDEXED、hash差异和已有索引保全；全部预算前置拒绝；向量数量/维度/NaN/Infinity/零/溢出；批次故障；card/document/config变更与删除；normal writer/exclusive 协调；重复准入；caller取消和应用close/drain；跨会话既有任务准入不变。CompletableDeferred 控制竞态，不靠 sleep。每个成功/失败/取消路径核对持久文件 SHA-256，预期测试自身修改另设基线。

执行 Desktop main/test 编译、准备/TaskRuntime、P9-S1/B5B 和最终请求序列化聚焦回归。JDK17、offline、no-daemon、no-parallel、单Worker、低优先级、in-process、现有2GB heap，不clean/rerun-tasks；按实际连续严重换页/极低内存/提交余量/OOM/退出风险保护性STOP，资源采样不是绝对峰值。保留所有RED，不改断言或预算以掩盖故障。

源码候选与原始证据独立提交、正常推送同feature。生产发布/持久化staging/跨Entity崩溃恢复属于未授权的P9-S2B；RAG Prompt注入、CHAT_MEMORY、完整P9、P11导入、D-040与Public Preview不提升。Windows人工、打包和真实Embedding API NOT RUN。
