# P9-S1 Project Acceptance

日期：2026-10-15。**DOCUMENT RAG READ-ONLY FOUNDATION — QUALIFIED ENGINEERING ACCEPTED**，由 Project 独立裁定。本文件登记既有验收，不重跑已验收测试。

## Exact evidence

- 初始源码 `372403b61736a4e8112afc506a04215f9add1fe8`，证据 `e7a8ce5a3f0394eaea3a3e1240333a7294f27b8e`。
- 初始 Desktop 11 suites/125 tests、SharedCore 8 suites/68 tests PASS；22/22 原始 XML 与 7/7 日志哈希由 Project 核对。
- R1 源码 `f3284c52b8327aa6eeca3a8d8cf6d7377d415d63`，测试/证据 `ac029e18e4a9d3cb9ac2414eb5389070b5dae3f2`；限定 Desktop 4 suites/38 tests PASS，0 failures/errors/skips。
- R1 在共享分块调用前检查文件名、标题/来源标签、分节与保守 chunk 数、输出字符预算，解决异常标签的计划内存放大。合法1200-chunk用例、共享排序/阈值规则与只读文件保全通过。
- 原始编译RED、测试RED、最终PASS及XML/log字节证据保留在 `evidence/p9-s1-document-rag-20261015/` 和 `evidence/p9-s1-r1-budget-20261015/`，不覆盖或改写。

## Qualified boundary

同一AppContainer/storage/coordinator/root ownership 的严格 DOCUMENT-only 读取，owned原文、hash、完整分块多重集合与embedding身份核对、有界topK及exclusive读取屏障获得限定工程验收。共享ChunkingEngine/VectorSearchEngine保持正式1.4.1权威，不增加另一套算法。

仅支持可严格证明的owned文档及索引。旧路径/assets、损坏/未知字段、来源矛盾、缺块/不完整索引及预算超限保持fail-closed；保守预算可能拒绝合法长文档，不截断后冒充成功。文本payload预算不是整个JVM heap绝对上限，阻塞I/O也无物理绝对超时保证。

**自动索引、历史CHAT_MEMORY重建、Prompt注入、Windows人工、完整P9及正式集成仍PENDING。** F4-B5B门禁、D-040/P11 APPROVED/NOT IMPLEMENTED、Fish/P10/Public Preview状态不提升。正式validated baseline仍为1.4.1，D-036 Prompt例外不变。后续写入需独立安全合同，不照搬先删除旧索引的Android流程。
