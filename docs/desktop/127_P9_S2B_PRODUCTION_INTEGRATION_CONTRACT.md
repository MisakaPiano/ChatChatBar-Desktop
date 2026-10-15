# P9-S2B Production Integration Contract

日期：2026-10-16。**PRODUCTION INTEGRATION CONTRACT CANDIDATE / PROJECT REVIEW PENDING。PRODUCTION WRITES NOT AUTHORIZED。** 起点 `484894a15afbe126083f5d919084d7b61989b33e`；实验结案126不等于生产事务验收。本文均为候选接口／目录／预算，不创建任何正式schema或数据目录。

## Authority and exact-source ledger

正式validated baseline为ChatBar1.4.1 `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`，D-036不变。已读取125、120、当前Feature Map及Long-Term Memory Skill／相关不变量。以下路径别名：D=`app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/`；S=`app/sharedCore/src/main/kotlin/com/example/chatbar/`；A=`app/app/src/main/java/com/example/chatbar/`。行号以本任务起点源码为准，本轮不改Kotlin。

| Source / function | Confirmed current fact / integration consequence |
| --- | --- |
| D/DesktopDocumentRagIndexPreparation.kt:61–178 `prepare/snapshot` | 仅显式offline gateway；按card/doc登记structured child；网络／批次间不持exclusive，最终重新快照，返回摘要PREPARED_ONLY，不保留可发布的完整向量。不能把摘要当候选文件，不能为发布重复收费请求 |
| D/DesktopDocumentRagReader.kt:54–141 `retrieve/inspect` | 顶层exclusive、strict完整card／来源／向量证明及有界topK；不经事务代际指针，不负责恢复 |
| D/DesktopDocumentRagSource.kt:12–122 | owned路径／reparse验证、UTF-8、源文件1MiB；fileName256、heading128、label1024、sections/chunks2048、计划字符2MiB；这些是读取预算，不是事务空间或durability保证 |
| D/DesktopTaskRuntime.kt:88–110,113–185,417–435 | 准备任务只有card/doc重复准入和activeJobs drain；repair另有session/image准入。没有正式DOCUMENT writer、card写入lease或持久交接；不能以REAL_CHAT/NOVELAI冒充 |
| D/DesktopDataOperationCoordinator.kt:67–106 | 合作normal在exclusive中可重入；normal→exclusive及nested exclusive拒绝；只挡合作IO，不冻结已发布Flow或已经在内存中的来源 |
| S/data/local/JsonFileStorage.kt:98–153,165–195,257–324,328–373,783 | 每Entity mutex外围normal；常规读可mkdir、宽松load可跳过坏JSON；save会发布cache。visitEntitiesStrict不mkdir、逐文件／深度64／未知字段严格，但仍借storage gate，非通用preboot API。普通单文件atomic move有fallback，不能当跨Entity crash事务 |
| S/data/repository/CharacterRepository.kt:24–97 | 独立列表Flow和storage cache；save/update/transfer/delete均可能变card，refreshFromStorage用宽松loadAll，必须先strict proof且在业务屏障内才可刷新 |
| D/DesktopFormatRepairDerivedSafety.kt:26–49,51–77,185–203 | 最终顶层exclusive内inspect至message CAS；活动任务身份、严格namespace及派生证据共同决定准入。未知事务域当前拒绝；未来白名单不能代替事务状态检查 |
| S/data/snapshot/AppDataSnapshotService.kt:524–560；S/data/root/AppDataRootInfrastructure.kt:12–30 | walk复制所有非保留条目；仅backups subtree与root ownership lock保留。新增事务域会被复制，不是自动豁免缓存 |
| D/DesktopCoordinatedSnapshotService.kt:35–80；D/DesktopDataRootMigrationService.kt:155–315 | 快照／恢复exclusive；恢复成功或不完整rollback seal restart-required。迁移pause、双根准备／ownership、safety snapshot、materialize再commit bootstrap；不等于所有未来索引任务已登记或事务域已验证 |
| official1.4.1 A/domain/rag/RagManager.kt:36–68；RagRepository.kt:20–63 | indexDocument先按docId删旧块，再embedding/save；删doc未同时限定card。不得复制其破坏旧索引的失败语义，也不能把宽松查询当旧集合证明 |

## B1. Writer ownership matrix

`normal`只说明当前IO经过同一coordinator；不代表完整业务操作已获事务lease。所有表内入口的Controller直接调用也须覆盖，不能只禁按钮。

| Producer / reader / writer | Persisted scope and actual entry | Coordinator now | TaskRuntime now | Required conflict / handoff |
| --- | --- | --- | --- | --- |
| 角色编辑／保存／另存 | D/DesktopCharacterEditorController.kt:348–395,468–594；S/CharacterRepository.save/update；character_cards、documents、images、editor_drafts | Entity写normal；编辑完整save无统一exclusive，materialize含raw文件IO | 独立draft Job，closeAndDrain；不是index/card lease | 同card发布前阻止／drain整次资源物化、保存和后清理；完整expected CAS，不能用旧source回写新索引metadata |
| DocumentInfo增改／重命名／删 | 上述editor及D/DesktopCharacterDraftResources.kt:23–105；DocumentInfo嵌入card，不是独立Entity | draft/resource helper本身无coordinator；持久card写经storage | 非TaskRuntime | 文档替换／删除必须交接来源身份与旧索引状态；已读旧源的任务作废并join，删除ID不得复用 |
| Package新导入／覆盖／复制／角色删除 | S/domain/card/CharacterCardTransferCore.kt:121–263,587–604；D/DesktopManagementController.confirmDeletion；character_cards、world_books、format_cards、owned resources、DOCUMENT vectors | Core整次normal，AppContainer.kt:396–407注入同coordinator；cleanup同lease | 无card/index任务登记 | lease必须覆盖提交及post-commit owned/RAG清理；不能在该normal内部升级exclusive，须重构外围准入后独立审批 |
| 现有DOCUMENT清理 | D/DesktopCharacterDocumentRagCleanup.kt:12–20 `deleteDocumentChunks` | storage normal／宽松deleteWhereUncached | 继承transfer调用，非独立writer | 不把DOCUMENT+card删除当严格事务；pending或未交接receipt拒绝清理，其他／未知块保全 |
| 预设首次初始化／文档修复／完整恢复 | D/DesktopPresetBootstrap.initialize/repairCharacterDocuments；D/DesktopPresetSuiteRestoreService.kt:60–64；preset_state、card、WorldBook与资源 | repair normal；suite restore exclusive；其他按各storage调用 | 非TaskRuntime | startup gate之前不得触发；运行期须card lease和receipt交接；保留已有post-commit warning及草稿恢复 |
| owned资源清理 | D/DesktopOwnedImageCleanup.kt:16–52；DesktopCharacterResourceStore.materializeDocument/deleteOwned | cleanup exclusive；resource store本身无gate | 非TaskRuntime | 阻止删除active intent所需源／备份；与card写入相同准入，不能在其他exclusive中再嵌套调用 |
| DOCUMENT读取／准备 | Reader.retrieve；IndexPreparation.prepare | strict snapshot exclusive；gateway在锁外 | 准备child已登记，Reader无writer lease | 发布期间隐藏旧视图、暂停新读取；准备与writer身份不同，旧prepared摘要不能授权提交 |
| 自动／手动本地快照、整根恢复 | CoordinatedSnapshotService.createSnapshot/executeAutomaticBackup/restoreSnapshot | 顶层exclusive；scheduler自身有pause/drain | 非TaskRuntime；automaticBackupRuntime拥有 | 拒绝active/uncertain；不能只等IO quiet而漏掉持有旧来源的网络任务；恢复后重启重建cache |
| data-root switch／migration | DataRootMigrationService.migrate；DesktopDataDirectory／PortableRoot解析 | migration exclusive并pause旧backup runtime；双根ownership | 不证明新index writer已drain | 先关闭新任务准入并drain，再验证source/destination域、snapshot/materialize/authority；不搬运行中的事务 |
| P11 .cbsave | D/DesktopSaveSlotInspector.kt:29–44；当前仅外部只读，无repository | inspector没有root写入／restore | 无生产SaveSlot writer | 未来export需一致视图；import/restore改变session/RAG需全域lease；本轮未实现，不能假称已覆盖 |
| D-038 .cbbackup | 当前Desktop无正式全应用archive import/export；目标official1.4.5 | 未实现 | 未实现 | 注册事务域及Secrets规则前拒绝迁移；见B5，不因整根snapshot可复制便称兼容 |
| F4-B5B | DerivedSafety.submit／TaskRuntime.launchFormatRepair；chat_messages及notice | 最终exclusive proof-to-CAS | repair session准入已存在，不覆盖document writer | 活跃writer／未确认事务／未交接域必须拒绝，网络前与最终提交都检查；不放宽现有UNKNOWN |
| 聊天／图像与将来P10 | TaskRuntime.launchChat/launchNovelAi；未来MemoryRepository/maintenance | 当前持久IO走storage或各已有coordinator；未来Memory未组装 | 聊天／图像已登记，P10 writer未集成 | card及来源相关任务按lease矩阵排斥；Memory六表、CHAT_MEMORY和journal不可被DOCUMENT事务删除／重写；P10独立所有权 |

**现状：未覆盖的editor raw IO、后清理、预设及未来writer均为生产blocker。** ownership是合作单进程保护，不授权外部编辑／第二storage实例绕过协议。不得因UI关闭、设置关闭或P9/P10未验收就宣称没有writer。

## B2. Candidate persistent domain and proof

继续选J，保留现有flat `vector_chunks` 和CharacterCard布局；不复制固定test-only原型。候选域为 **`entities/document_index_transactions/`**，实际名称／协议版本须Project批准后才可创建。它不是已注册Entity；当前B5B会因未知namespace拒绝。需要单独的严格domain parser，不能把嵌套目录塞入普通visitEntitiesStrict。

- 候选记录分为：domain版本／owner；`active/<txId>/`不可变intent、seal、stage、old backup和单独commit marker；`heads/<cardId>`当前权威交接；`history/<txId>/`退役历史收据。路径、版本及每种合法条目由闭集定义，不接收绝对路径、符号链接、junction、重解析点、硬链接身份冲突或未知旁文件。
- Intent绑定root归属、tx/task/card/doc ID、完整expected card字节hash、owned document引用和实际hash、embedding的非秘密配置身份、旧target完整ID/hash清单、完整新集合、相关未修改数据签名、expected head/revision及前事务关系。JSONL清单逐条读取，限制ID／总项数；正文、向量、旧card留在受保护payload文件，不嵌入journal元数据。日志／诊断不包含这些payload、密钥或Prompt。
- `pending`表示sealed意图已存在且active；`committed`由绑定txId/intent hash/new card/new set的有效marker证明；`completed`需要payload重证、缓存屏障内更新及持久head交接。`uncertain`是证据分类，不能靠catch文字设置一个成功flag。没有可信证据时不写“已回滚”／不自动retry；仅保全并拒绝开放。
- 先完整验证新stage及全old备份，再安装旧／新文件和card，最后marker。marker前恢复旧态须证明完整旧副本及当前文件均属精确允许域；marker后须证明完整新态，不能因一次INDEXED状态就认定提交。不能清理未知文件；cleanup失败仍保留active证据，不使半完成事务看似无事务。
- 标记写入／取消交付竞争保留attempted阶段、cause、marker及durable evidence；读回不确定仍COMMIT_UNCERTAIN，不在UI／后台自动启动第二次模型请求。单文件move fallback、超时取消或返回异常均不能证明物理回滚。
- 准备／网络不持exclusive；新批次增量写入staging且有writer lease。当前prepare只产摘要，因此生产spool／Gateway变更需另行批准，不可重复调用prepare把第二次收费请求当发布。恢复只用本地证据，永不重新Embedding。

### Candidate bounded first subset

候选限一个owned DOCUMENT、同card只有一个活跃writer、全根一次只能处于一个最终安装／恢复区间；网络可按批准后的不同card准入并行，但首实现建议串行。只处理严格可解释的旧集合；手动、孤儿、损坏及未知关联保留／拒绝。

预算候选：沿用Source1MiB／计划2048块与2MiB字符；单card或vector JSON≤8MiB；header≤64KiB、JSON深度≤64、单ID≤256字符、当前域≤1 active；新及旧目标各≤2048块／64MiB；全库扫描≤100,000条／4GiB读取预算，compact ID/hash状态≤64MiB，其余逐文件／逐批。历史收据超过批准的保留预算只拒绝新任务，不自动删除；首协议建议128份上限。以上是待评审上限，不要求给既有更大数据迁移或截断。

空间检查使用checked Long：保守额外需求 `2*oldBytes + 2*newBytes + 3*cardBytes + metadataBytes + max(512MiB, 10%前述需求)`；计入已有占用、临时替换及同卷约束。溢出、大小未知、预算不足或磁盘写入失败均拒绝／保全，不回收用户文件。64MiB向量预算可能拒绝高维大文档，即使Reader可读也不能假称可重建。目录fsync／Windows电源故障顺序仍UNKNOWN，生产承诺需独立平台证据。

## B3. Multiple updates and receipt handoff — candidate, not implemented

区分三种权威：**active意图/marker决定未结束事务；history receipt只证明过去；current head加严格live校验和writer交接证明当前状态。** retired receipt不再要求live永远等于过去。completed文件存在既不能自动开放，也不能永久禁止正常编辑。

候选交接协议：所有可能影响同card/doc/config来源的writer先登记lease，等待active结束并strict核对当前head与live。其变更先记录sealed transition（expected head、完整前后card、受影响资源／向量身份、允许的字段变化及新head），再执行自身有恢复证据的提交；验证后原子推进head并附supersedes/retired历史关系，最后释放业务屏障。新head未确认前旧receipt不能被删除或忽略。崩溃按该writer transition恢复，而不是从任意历史receipt回滚今天的数据。未知writer或绕过交接的变化保持BLOCKED／COMMIT_UNCERTAIN。

| Normal future operation | Required handoff |
| --- | --- |
| 同文档连续重建 | 新tx引用当前head与当前vector集合；新的marker/head证实后前receipt退役；不复用旧expected、不误删上一代未交接文件 |
| 完成后只改角色名等字段 | card writer验证字段级差异，保留DocumentInfo／向量，并生成新card hash/head；不能拿旧完成收据与新card不等就自动回滚 |
| 文档替换／删除 | 先drain旧来源任务，验证新引用／删除身份；将该doc索引标为不可用或在独立已批准事务中处理旧块，其他doc不变；不是本轮授权自动清理 |
| 同card第二文档 | whole-card CAS及每doc证明；串行推进同card head，保留第一文档索引，不凭docId全库删除 |
| 其他card或普通聊天更新 | 只更新其实际归属scope；chat/source-turn数据不由DOCUMENT ledger覆盖。无关消息追加不强迫历史card receipt永久绑定整个root所有字节 |
| 历史receipt保留／清理 | 非active、可证明已交接、无恢复／备份引用且有明确保留策略后才可退役清理；删除需独立授权及幂等协议，不按日期／状态猜测 |

这是待评审的sidecar协议，不更改CharacterCard/VectorChunk Entity schema，不等于已经解决持久交接。**CARD_EDIT／DOCUMENT_REMOVE／IMPORT等transition、非秘密配置身份版本及receipt GC均未设计到可编码程度，BLOCKED，须先获得独立合同。** 不能把test原型“拒绝第二笔”当产品方案，也不能在这些writer未协调时先开放生产索引。

## B4. Task and view barrier sequence

1. Root ownership有效；注册独立DOCUMENT_INDEX writer（新接口待批），capture task/root/card/doc/expected来源。TaskRuntime覆盖从读源至最终receipt，包括网络间隔和停止／关闭；不是一轮IO normal lease。
2. 在不持normal的位置获取initial exclusive读取快照；释放后批次网络／staging，仅在短IO区间走normal。新writer admission阻止同card编辑／导入／删除及相关F4、backup、migration任务晚到发布旧结果。
3. 提交前先将应用级readiness置MAINTENANCE并等待活跃Reader／业务source consumers完成。UI禁止新查询、清除／隐藏旧结果和card列表引用；Flow subscriber必须经过同一readiness/epoch门禁，不能仅设置busy按钮。禁止直接订阅绕过屏障。
4. 从无normal的上下文进入唯一顶层exclusive；重验lease、完整source/card/config/head/old-set，安装／marker／恢复判定。下层storage只借normal重入，不调用nested exclusive cleanup／snapshot facade。
5. strict核对完整结果后，在屏障内更新storage vector cache、CharacterRepository及依赖的查询缓存；当前loadAll会跳坏JSON，只可在同一exclusive的strict proof之后作为刷新工具。需要一次逻辑epoch发布；若现有逐Flow发布无法阻断观察，则保持BLOCKED／restart-required，不能先开放半套cache。
6. 持久交接head／receipt、退出exclusive，再以同一有效epoch开放Reader/UI，最后释放writer lease。COMMIT_UNCERTAIN／cache失败不开放，不自动re-run网络、不把task标成功。完成态历史与当前head适用性按B3判定。
7. 用户取消网络与应用关闭不同；取消未提交stage不能伪造回滚。写入已开始时先分类并保全证据，真实IO未退出不释放ownership。沿用TaskRuntime drain→draft drain→backup runtime→coordinator→migration的现有关闭序，不引入无限NonCancellable或detached recovery。无法证明排水则保留锁并报阻断。

## B5. Backup and cross-platform matrix

D-038仅选择official1.4.5 `1198a13cf3008de1358b8a69aa5e40b64797c8d9` 的全应用合同。已从精确SHA读取 [app-backup Skill](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/.agents/skills/chatbar-app-backup/SKILL.md)、`domain/backup/BackupDataRegistry.kt:7–48`、`BackupEntityValidator.kt:16,52–57`和`AppBackupBootstrap.kt:24–54`。本地无该Git对象，使用只读精确URL获取，没有fetch/sync。

重要区别：Registry只登记既定root并跳过隐藏／tmp／part文件；未知root拒绝。Validator对未知Entity type拒绝，但已知Entity采用ignoreUnknownKeys=true，不能据此证明未知字段安全。官方备份默认含凭据，与Desktop普通共享导出默认不含Secrets的D-038目标不同。不能把Android维护lease、boot recovery或目录sync直接当成Windows已实现。

| Operation | Active / uncertain | Completed history / handed-off current | Current disposition |
| --- | --- | --- | --- |
| Desktop自动／手动本地snapshot | 先drain并受控消解；无法证明则拒绝，不能复制半份card/vector或漏journal | 在同一exclusive复制完整域与payload，恢复时重新分类；不单独丢弃receipt | 当前copy确会含新域，但domain-aware gate未实施；不声称已有兼容 |
| Desktop整根restore | 目标active时拒绝；外部snapshot有pending只可在隔离域验证／消解后恢复 | head/history与entities必须同一完整快照，恢复后seal restart-required并先过128 | 不在业务运行中重放；域注册／恢复验证BLOCKED |
| root switch／migration | 停准入、drain源与目标、消解active；无法处理就拒绝，保全双根 | 相对引用随整根搬迁；root身份转换需sealed handoff，保留原根／安全snapshot | 现有backup pause不等于index drain；需新增owner集成 |
| Portable Mode | 不是旁路；解析出的根也执行同样ownership及startup gate | 不能仅凭路径搬到USB重写root身份；待验证同卷与Windows支持条件 | Portable解析只决定根，不保证journal兼容 |
| Android1.4.5 .cbbackup→Desktop | 未知事务／恢复checkpoint拒绝；不猜测恢复Android运行时 | legacy无Desktop域时走独立P11验证，保留向量／Memory／SaveSlot；不生成伪receipt | P11未实现；非秘密资源映射／路径与不支持域需正式合同 |
| Desktop→Android支持范围 | pending/uncertain禁止普通导出 | 官方validator不识别候选域；不得静默剥离收据。只有Project批准可验证导出投影且不丢数据时才支持，否则拒绝 | BLOCKED；普通导出不得含SecretStore或ModelConfig等内嵌apiKey，不复制官方默认凭据模式 |
| P11单会话 .cbsave | 活跃源或session依赖时拒绝新export／restore；不得夹带未结束root事务 | DOCUMENT根事务不塞进单会话schema；已有历史snapshot不删除。导出仅已冻结session数据，导入需独立来源审查 | 当前Inspector只读；导入／恢复／安全资源绑定仍PENDING |

本地安全snapshot与可分享export是不同authority：原样本地恢复资产不能被临时“脱敏”后冒充完整备份；普通分享则必须有经过批准的Secret排除／引用一致性合同。不能自动导出凭据，不能为Android兼容删除Desktop证据。

## Decisions required before implementation

- **BLOCKED:** 所有writer/card/document/resource/post-cleanup的lease与持久交接、sidecar注册与D-038双向兼容、view/Flow屏障、pre-container恢复owner及共享coordinator注入方式。
- **BLOCKED:** 现有EmbeddingConfig（S/data/local/entity/ModelConfig.kt:97–104）含apiKey。preboot不得用完整serializer读取秘密配置；非秘密source identity／版本交接如何与全部配置writer绑定须先裁定，不能悄悄hash或读取凭据文件作为“只读无Secrets”证明。
- **UNKNOWN:** Windows目录持久性、掉电顺序、真实launcher恢复／锁寿命、full-disk/权限组合及大型旧库资源成本；进程实验不替代这些证据。
- 建议下一最小工作仅为128的无副作用preflight和合成启动故障测试；只对无事务根开放原有业务，对无法证明的候选域拒绝。真正writer／多代交接／网络Gateway须独立批准后分批实现，不通过先写入再补恢复倒置门禁。

本轮无编译／测试／GUI／打包／用户数据或Provider操作，未制造新PASS。完整P9、P10、P11、D-040和Public Preview不改变。**STOP；Project独立复审后再授权任何生产写入。**
