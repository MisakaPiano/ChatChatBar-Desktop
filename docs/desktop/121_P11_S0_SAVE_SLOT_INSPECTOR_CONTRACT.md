# P11-S0 — Single-session SaveSlot read-only inspector

日期：2026-10-15。**CODEX CANDIDATE / PROJECT REVIEW PENDING。** 本项不是存档导入、恢复、Android往返或完整P11验收。

## Source authority and ownership

正式baseline：1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。已读取该版本save-slot Skill，定向核对SaveSlot、SaveSlotPackageStorage、SaveSlotRepository、SaveSlotSerializationTest与ChatViewModel.loadSaveSlot/流式恢复路径；结合当前19_DATA_COMPAT、D-037/D-038/D-040、Desktop picker/container/root/coordinator。Android exporter与restore仍保留原所有权，未运行或改动其业务流程。

SaveSlot.kt从Android移至sharedCore，包名/serializer/字段/默认值原样保留；迁移前后Git blob均为 `02bbd09d50dc501947f4b831ba6a8213d73418de`。不是另造SaveSlot schema，不搬Context、Bitmap、FishAudioStorage、restore事务或大型Base64处理。Desktop复用这个唯一Entity及已有ChatMessage/VectorChunk/Voice/Memory序列化。

Android唯一调用方适配：原验证过非空的`decoded.packageRef.copy`改为`requireNotNull(decoded.packageRef).copy`，满足跨module smart-cast规则；不改变导入、恢复或验证业务合同。首次Android SDK定位失败及该编译RED作为独立历史证据保留。

官方v8结构来源：`app/app/src/main/java/com/example/chatbar/domain/chat/SaveSlotPackageStorage.kt` 常量43–49、Reader.validate76–121、forEachJsonLine约230–248、createPackage390–410；包含manifest.json、messages.jsonl、rag.jsonl、可选voices.jsonl、media/images与media/audio。packageRef.imageCount计图片**引用**（包括省略标记和背景），不是物理文件数；audioCount计voices记录。资源引用前缀来自官方包实现，omitted-image token直接复用共享常量。

## Deliverable / UI boundary

- `DesktopSaveSlotInspector.inspect(Path)`：仅以只读方式检查选中的外部文件；没有storage、repository、TaskRuntime、迁移或Provider依赖。
- `DesktopSaveSlotInspectionPicker`：复用现有native OPEN picker，标签“检查单会话存档（只读）· 尚未导入”；不调用SAVE或导入委托。AppContainer提供同一picker组装，测试注入合成选择。
- 管理页可见UI **PENDING**。现有Swing picker采用invokeAndWait同步模态入口；长时间检查的可见取消、管理页草稿离开保护及窗口关闭责任需独立接入/原生验证。本轮不为此扩展任务运行时或创建伪完成按钮。
- 所有输出只含格式、版本、状态、计数、固定错误码与限制；不含名称、正文、Prompt、私有路径、凭据或异常原文。`imported=false`、`desktopCanRestore=false`始终成立。

## Safety budgets and validation

1. 内容头识别ZIP容器/JSON，不按扩展名决定格式；非SaveSlot结构拒绝或UNKNOWN/UNSUPPORTED。`.cbbackup`与加密全应用存档不支持，不解密。
2. legacy1–7：文件最多1MiB，超过时在完整JSON/Base64反序列化前返回BUDGET/UNSUPPORTED，不提取不完整概要冒充兼容。小型文件复用严格官方serializer；不把Base64转换为媒体字节或Bitmap。
3. ZIP：文件最多256MiB；先读取有限EOCD tail，检查单盘/非ZIP64、<=4096 entries与<=4MiB central directory，再创建ZipFile。未知entry、重复/大小写别名、traversal/backslash/absolute/冒号拒绝；media路径只允许直接受限资源ID/extension。
4. 解压声明合计<=64MiB、每entry<=16MiB、压缩比<=1000；实际读字节也受entry大小/预算约束。manifest<=256KiB、JSONL每条<=1MiB、JSON嵌套<=64；逐条读取，不建立完整messages/rag payload列表。compact identity集合有<=20000记录上限；每消息候选<=64，Memory nodes<=2048。
5. ZIP全部entry读至EOF并独立核对CRC和长度，包括媒体。没有解压到任何目录；source symlink/junction/reparse拒绝。不修改源文件，不写源目录或正式数据根。
6. 严格未知字段不忽略；复用官方Serializer类型校验。验证消息ID/session、candidate IDs/current index、source-turn关联、生成图片来源、RAG的session/messageIds/sourceTurnId、voice的message/version/audio、图片和背景引用、声明数量。相同media basename不同extension也拒绝，防止official resourceEntries以basename映射的歧义。
7. Memory只验证受限结构与可证明node/child/session关系；不重放journal、不迁移、不声称验证source fingerprint、Archive/HEAD完整语义。legacy身份缺字段也明确标记局部验证限制。资源CRC不是图片/音频codec验证；输出限制不能被解释为可恢复。
8. 取消在逐记录/流读取边界检查并通过use关闭文件；不启动fire-and-forget工作，不发生数据根写入。真正阻塞文件I/O无物理绝对取消保证。

## Corpus provenance

机器可读清单：`fixtures/p11-s0-corpus.json`；生成器：测试源码`SyntheticSaveSlotCorpus`及DesktopSaveSlotInspectorTest的变异案例。全部为**SYNTHETIC**，可离线重复生成；不固定/上传真实`.cbsave`、用户聊天、密钥、私有备份或解密材料。

- `OFFICIAL_EXPORTED_SYNTHETIC`：**PENDING**，未运行Android官方导出器，不能称official golden。
- `PRIVATE_REAL_WORLD`：**NOT AUTHORIZED / NOT READ**；未来须单独授权、本地隔离，不上传。
- legacy schema-tagged fixtures只是合成识别用例，不等同真实历史APK产物；官方真实旧档与Android往返保持PENDING。

## Verification / next gate

必须先通过SharedCore/Desktop受影响编译与新Inspector测试，再运行共享schema合同、Android受影响编译和原SaveSlotSerializationTest，以及有限P9-S1/B5B/实际请求回归。所有命令、退出码、原始XML、源码hash/blob和RED由独立evidence提交记录；本合同不将未运行项目写成PASS。

后续需独立批准：正式管理页UI与native取消验收、official-exported synthetic corpus、真实旧档差异、媒体codec与Memory完整语义、恢复事务/数据根屏障、D-040资源绑定、D-038全应用协议。**P11导入/恢复、Android↔Desktop往返、Public Preview Gate、完整P9/P10/P11 parity均未完成，不允许合并desktop。**
