# P9-S2 DOCUMENT Index Write — Source Audit

日期：2026-10-15。**SOURCE AUDIT COMPLETE / PROJECT REVIEW PENDING；INDEX WRITE NOT IMPLEMENTED。** 不读取用户数据，不调用Embedding，不修改生产vector_chunks。

## Authority and read scope

正式baseline `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`（1.4.1）不变。当前读取起点为P9-S1结案检查点的父链 `ac029e18`；R1 reader 已限定验收，B5B gate 保持。下表路径前缀：A=`app/app/src/main/java/com/example/chatbar/`，S=`app/sharedCore/src/main/kotlin/com/example/chatbar/`，D=`app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/`。

| Source | Actual contract / evidence |
| --- | --- |
| A/domain/rag/RagManager.kt:36–68 indexDocument | 先deleteChunksByDocumentId，再分块、收费embedding、saveChunks；任一后续失败会失去旧索引。返回contentHash/count，本方法不原子更新CharacterCard |
| A/domain/rag/RagRepository.kt:20–63 | saveAllUncached逐文件保存；删除按originalDocId，不同时限定角色卡。旧/孤儿/跨卡关联不能仅凭docId猜测删除 |
| S/data/local/JsonFileStorage.kt:94–109 OperationMutex | normal admission在per-entity mutex外层；同一storage/coordinator的合作调用受维护屏障约束，锁不是跨进程事务 |
| S/data/local/JsonFileStorage.kt:588–681 replaceWhereStreamingUncached / replaceMatchingFilesStreamingLocked | producer写`.replace-UUID`，旧匹配文件移`.backup-UUID`，新文件逐条REPLACE_EXISTING安装。catch尝试删除installed并恢复backup；恢复失败才preserveBackup |
| S/data/local/JsonFileStorage.kt:129–153 writeJsonFile | 单文件tmp + ATOMIC_MOVE（不支持时fallback普通move）；不是跨Entity原子提交，不提供文件集/目录fsync证明 |
| S/data/local/JsonFileStorage.kt:257–333 visitEntitiesStrict | uncached逐文件严格字段/身份外围校验、未知旁文件/目录拒绝；P9读取与B5B可发现残留但不恢复 |
| D/DesktopDocumentRagReader.kt inspect / checkPlanBudget | root ownership、顶层exclusive读取、完整card比较、owned源文件hash、完整分块多重集合/embeddingKey、预算核对；不重建/删除旧块 |
| D/DesktopCharacterDocumentRagCleanup.kt:9–20 | 现有角色删除cleanup仅按DOCUMENT+sourceId筛选，deleteWhereUncached宽松解码；不是安全重建事务或全面inventory证明 |
| S/data/local/entity/CharacterCard.kt:21,134–166 | customDocuments以及contentHash/indexedHash/ragStatus/ragChunkCount/ragIndexedAt/ragError；状态与vector_chunks是不同Entity文件 |
| A/ui/character/CharacterEditViewModel.kt:2182–2259 | 以现有chunks首块hash/key推断状态、请求后逐批更新doc状态；不能继承为Desktop完整来源证明或无迟到写入的CAS |
| D/DesktopFormatRepairDerivedSafety.kt check / submit / inspect | 最终exclusive内严格重证至message CAS；未知派生、异常目录/旁文件拒绝。新增writer必须被协调器与任务准入覆盖 |
| D/DesktopDataOperationCoordinator.kt:67–106 | 顶层exclusive禁止normal升级；合作normal可drain，exclusive结束释放；重启required/close状态不能绕过 |

已核对正式1.4.1对应RagManager/RagRepository和当前fork的以上实际代码。所谓“先删除旧索引”是原Android行为，不授权移植该风险；formal baseline不因此提升。

## Crash / cancellation matrix

| Stage | Observed source semantics | Required Desktop disposition |
| --- | --- | --- |
| Embedding前删旧块 | 旧文件已被逐条删除，模型拒绝/超时/取消不恢复 | 禁止采用；旧索引在成功发布前保留 |
| producer写staging | 在目标Entity目录创建暂存子目录；正常异常finally尝试删除，返回值未严格确认 | 可严格识别残留为UNKNOWN，不自动清理 |
| 旧文件移动中 | root与backup各保留旧集合一部分 | 同进程合作读取被同storage entity锁挡住；崩溃后磁盘是部分集合，不可宣称原子 |
| 新文件安装中 | root有新/旧混合，文件逐条移动，不是单一generation pointer发布 | 进程异常时必须有持久化提交阶段和可验证恢复证据；当前没有 |
| catch rollback | installed逐条delete，再恢复backup；restore失败保留backup并附suppressed error | 仅尽力进程内rollback；installed删除/cleanup返回值也可能失败。失败不能称“原数据完整恢复” |
| 正常完成、清理残留 | backup/staging递归删除返回值未检查 | 残留会使strict readers拒绝，不能把它当不存在或擅自删除 |
| Context退出时取消 | 文件IO/move循环没有挂起取消点；withContext返回时可能取消结果交付，磁盘已经改变 | 必须用durable receipt/严格读回分类CommitUncertain；不得自动重试第二次写入 |
| 崩溃/断电/restart | 本文件没有扫描`.replace-*`/`.backup-*`的startup recovery，也无日志/pointer/commit marker | 不能用rollback代码证明crash-safe；相关路径保持BLOCKED |

真实crash/restart、磁盘不足、Windows文件锁和断电耐久性本轮**NOT TESTED**。以上是静态风险定位，不声称已复现死锁或数据损坏。

## Critical protection gaps

1. **静默跳过损坏匹配记录。** currentMatches使用runCatching(...).getOrDefault(false)，解码不可读/损坏记录既未备份也未明确拒绝；若producer发出同storage ID，REPLACE_EXISTING仍可覆盖未确认旧文件，rollback还可能删掉该替换结果而无原文件backup。宽松Json(ignoreUnknownKeys=true)不能作为数据可信证明。
2. **缺少全局文件集原子发布。** per-entity mutex仅保护同实例调用；其他storage实例/外部读者不受它约束。root exclusive可屏蔽同coordinator合作读写，但不会改变磁盘逐文件移动的崩溃中间状态。
3. **跨Entity提交。** 向量集先变而card仍旧，或card提前INDEXED但向量未完成，都会导致P9-S1拒绝。当前没有同时覆盖card+vectors的持久化恢复协议；必须保留原快照与真实提交阶段，不仅更新ragStatus假装完成。
4. **集合与身份内存。** replace使用emittedIds集合、currentFiles数组、installed列表；不是无界库的常量内存primitive。S1的单文档预算不等于已有全库替换预算。
5. **来源保全。** 只能替换精确cardId+docId+源hash+embedding身份的完整确认集合。其他卡DOCUMENT、手动/旧索引、CHAT_MEMORY、未知metadata、孤儿或坏文件一律保护/拒绝，不能用宽松cleanup达成绿色。
6. **迟到结果。** 请求前捕获完整card/document内容hash、资源引用、embedding配置身份；网络流不占exclusive。最终从未持normal的位置进入exclusive，重读card与源hash，并验证未删除/改绑/换模型；冲突不发布、不回写旧card。
7. **F4/P10并发。** 文档索引任务需专用任务所有权与source snapshot；已读旧来源但未发布的任务必须登记。F4最终proof-to-CAS、S1读取、快照/恢复与后续P10共享顶层屏障，不能仅在HTTP后调用无条件VERIFIED_SAFE。

## Proposed next P9-S2 implementation contract

可以先实施：严格只读index plan/preflight、同一根中的任务准入、合成embedding+隔离staging、取消/错误分类和来源冲突测试。**生产发布仍需Project明确批准一个可恢复的跨card/vector提交方案。** 可供独立设计选择的方案包括持久化journal与阶段证据，或受版本化generation/pointer管理的发布；二者都不能本轮默认为现有schema/layout已经支持。

最小允许发布子集应仅为一个已严格确认的owned文档，沿用S1预算。未确认已有集合、损坏/未知/残留事务必须fail-closed保全。准备新完整集合成功前不删除旧索引；中间card状态不得覆盖用户并发草稿或在失败后伪造INDEXED。正式发布在一个顶层exclusive中重证完整expected身份，保存提交receipt，并在任何失败/取消后严格分类NOT_COMMITTED、COMMITTED、COMMIT_UNCERTAIN。后续恢复不得触发新的收费embedding。

提交前必须有：全部阶段故障注入、真实独立进程crash/restart、取消发生在每个move/card写入/cleanup边界、空间不足、未知文件保全、跨卡/手动块保全、源文件/角色删除后的迟到提交拒绝、S1读取无混合视图、B5B排斥和后续协调接口。无法证明完整恢复或读取屏障时不提交伪完成发布实现。

**STOP blockers:** 无持久化提交/恢复证据；无法安全处理坏JSON/残留；无法复核旧集合或来源；需要未批准schema/journal/跨模块重构；存在未纳入屏障writer。P9-S2 NOT IMPLEMENTED；不清理、不迁移、不修改生产向量文件。
