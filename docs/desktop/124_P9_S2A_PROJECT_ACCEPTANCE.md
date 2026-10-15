# P9-S2A Project Acceptance

日期：2026-10-16。**DOCUMENT INDEX PREPARATION — QUALIFIED ENGINEERING ACCEPTED**，由 Project 独立裁定。本文件仅登记既有验收，不重新运行旧测试或扩大范围。

## Verified source and evidence

- 源码 `a3bf3ab7229d8fcfae96c7af36022560fc7665c3`，证据 `2058ebc65fd88a8565ff1b4cd7cd0bc1dcb24144`。
- Project 独立核对七份源码 Git blob、七份 JUnit XML 和三份 Gradle 日志 SHA-256，全部匹配。
- Desktop 最终 **5 suites / 65 tests PASS**，0 failures/errors/skips；包括准备层21、P9-S1 Reader18、F4-B5B15、TaskRuntime10、最终请求装配1。
- 首轮21 tests/1 failure的 SOURCE-vs-INVENTORY 分类 RED、原始 XML/日志和窄修复历史保留于 `evidence/p9-s2a-preparation-20261015/`。不覆盖或重分类旧失败。

## Qualified boundary

严格 owned 来源读取、共用 P9-S1 有界预算、官方分块正文/metadata/顺序、注入式离线 Gateway 的有界批次向量校验、最终 exclusive 内来源重证，以及结构化任务登记、重复准入、取消/应用关闭 drain 获限定工程验收。实际文件 hash 与 DocumentInfo 旧索引 hash 分开记录；迟到结果不能被当成有效计划。

输出仅 **PREPARED_ONLY** 摘要：无持久向量候选、无生产索引写入、无跨 Entity 事务、无实际 Embedding API、无 Prompt 注入。最终来源重证不是 proof-to-CAS 事务，不授予未来发布资格。旧向量、文档和角色状态保持原样，F4-B5B生产安全门禁不放宽。

P9-S2B 发布/崩溃恢复设计及实现须独立审查；完整 P9、CHAT_MEMORY、RAG Prompt 注入、P10、P11 导入恢复、D-040实施与 Public Preview 不提升。Windows人工、打包、全量回归和真实收费Provider未验收。正式 validated baseline 仍为 ChatBar1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`，D-036/Prompt不变。
