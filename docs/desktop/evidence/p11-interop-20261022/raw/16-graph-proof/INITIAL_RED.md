# 首次图对照 RED（终端结果补记）

首次 `verify_roundtrip.py` 在消息、媒体、录音、RAG 对照后报 `AssertionError: current Memory snapshot`。
当时输出在任务终端，未重定向成文件；此处如实补记，非伪造原始日志。

差异路径：`nodes[0].id`、`nodes[0].sessionId`、`state.legacyReferenceNodeIds[0]`、
`state.memoryWasEnabled`（true→false）、`state.timeline`（空→5 个 source turns）。

追踪官方 `LongTermMemoryService.loadSnapshot` / `MemorySnapshotImportPolicy.rebind` 及读取/续聊协调后，
最终比较对节点身份做一一映射并检查根/子节点引用；timeline 单独严格对照返回消息的 source-turn/order 集合；
启用状态单独对照会话开关，其余快照字段仍全量比较。原档案未改，生产源码未为通过而改。
