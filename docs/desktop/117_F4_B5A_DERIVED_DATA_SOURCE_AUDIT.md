# F4-B5A — 历史消息变更的派生数据安全源码审计

日期：2026-10-15。**B5A SOURCE AUDIT COMPLETE / PROJECT REVIEW PENDING。** 本文提出待 Project 审查的 B5B 合同，没有实现生产协调或开放门禁。

## 1. 身份、authority 与证据边界

审计源码固定为 `feature/phase8-fish-audio @ e37b94495b01bdbc5b4162ce7ada06b42942fa1a`；local、tracking、GitHub live origin 一致，起始工作区/暂存区 clean。live `origin/desktop @ 5ef95428421f7c693b010ac88f51d470804ab07a`，本地 desktop 未更新。B4 结案见 [116](116_F4_B4_PROJECT_ACCEPTANCE.md)。

按 `00` → `10` → `21` → 正式 1.4.1 的 message-format-repair / long-term-memory / moments / save-slot Skill → `13` / `14` → `115` 与 B4/R1 源码读取。官方 Skill 使用 `git show 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8:.agents/skills/<name>/SKILL.md`；当前 Skill 仅辅助定位已移入 sharedCore 的路径及 Desktop ownership。正式 validated baseline 仍为 1.4.1，D-036 与 D-039 不变。

下文行号对应上述 **审计 HEAD** 的实际文件，不能当作未来 HEAD 的固定行号。Android MemoryRepository、LongTermMemoryService、MomentRepository、MomentGenerationService 在 baseline→审计 HEAD 比较中无文件差异；RagRepository 的差异是复用共享 VectorChunkStorageContract，不能误称新版 RAG runtime 已移植。共享策略迁移及 F3 候选身份以当前源码为准。

本轮只读取源码、Git 对象和既有工程证据；没有读取任何真实数据根、聊天、SecretStore、存档、备份，也没有调用 Repository、迁移、journal recovery、清理、重建、Gradle 或 Provider。**所有真实磁盘数据的存在、健康及来源均 UNKNOWN。** CONFIRMED 表示源码事实；B5B 方案是建议，不是运行验证或 VERIFIED_SAFE。

## 2. 结论与消费方矩阵

历史 Assistant 的 APPLIED、STOPPED 混合正文和 RESTORED 都可能改变真实来源。稳定 messageId/sourceTurnId/version ID 不等于来源正文不变。当前两个 safety check 与消息 CAS 之间没有跨派生存储的共同写入屏障。最小可先实施的是“严格证明没有受影响的可变派生数据，并在最终证明与 CAS 期间隔离所有相关写入”的子集；不能靠 P9/P10 未接通、设置关闭或没有活动页面证明安全。

| 消费方 / storage type | producer、reader、writer 与身份依赖 | 当前 Desktop 与失败/旧数据风险 | 严格证据、所需屏障与建议裁定 |
| --- | --- | --- | --- |
| RAG `vector_chunks`：DOCUMENT / CHARACTER_SETTING | Android RagManager 文档分块、EmbeddingService；RagRepository 检索。sourceId 为角色来源，DOCUMENT metadata 含 originalDocId/contentHash/embeddingKey；不是聊天候选派生 [R1–R4] | Desktop 有角色文档 RAG 删除辅助；完整 P9 索引/检索未接通。可存在导入块；枚举失败、类型不明、损坏不能跳过 | 对合法、明确独立的文档块保持不变；不能通过删除它们开放修复。严格分类不明则 UNKNOWN；与目标聊天来源的矛盾关联必须拒绝 |
| RAG `vector_chunks`：CHAT_MEMORY | sessionId 位于 sourceId；messageId 可是 turn 中最后 Assistant；metadata messageIds/sourceTurnId/order、indexMode/contentVersion、每块 sourceHash；一个 turn 可有多块 [R1–R4] | 当前 Desktop 请求 planner 不注入检索结果，但旧/导入数据仍可能存在。旧模式、孤儿、部分重建、缺 embeddingKey/hash、手动块及 legacy memory_node 都不能忽略 | 全目录严格解码并交叉核对 ID/来源；只检查目标 messageId 不够。B5B 首期保守拒绝目标 session 的所有 CHAT_MEMORY 或不能排除相关性的块。已有受影响块需 P9 写入协调、来源重建及读取屏障，不先删除用户数据 |
| Memory `memory_states` | MemoryRepository state/journal，LongTermMemoryService reconcile/compile；id 为 session，timeline、active pages、HEAD、staleSourcesByNodeId、sourceRepair 队列 [M1–M4] | Desktop 未实例化完整 MemoryRepository/service；仍可有导入状态。getState 会 recovery；空 state、旧 schema 或 cleared/disabled 标记不是自动安全证据 | 严格原始实体读，不调用 getState/loadLocked。首期目标 session 任一 state 均拒绝；后续需精确来源失效及 reader 阻断 |
| Memory `memory_nodes` | Episode/Arc/Era、旧 roots；sessionId/sourceTurnIds/childIds/sourceHashes/sourceFingerprints/staleSourceTurnIds [M1–M4] | 无 state 的孤儿 node、缺 child、用户编辑 node、旧 hash 仍是保护对象 | 不能只沿 state 可达图扫描或用跳过缺 node 的 getReachableNodes 证明不存在；严格全表发现孤儿。目标 session 或来源无法归属则拒绝 |
| Memory `memory_tier_revisions` | commitStateLast/applyJournal 写；sessionId、parent/transaction、snapshot/added/removed node IDs、affectedSourceTurnIds [M1–M2] | 孤立 revision、旧活动图、不完整压缩历史可能存在 | 严格全表与节点/事务关联；首期目标 session 任一记录拒绝，不删历史 |
| Memory `memory_compression_transactions` | commitStateLast 写；sessionId、consumed/result node、source/target revision [M1–M2] | state 已更新但事务/节点不完整，或中断 journal | 严格读及关联；不得靠当前 settings/空活动页抹去风险；首期拒绝相关记录 |
| Memory `memory_commits` | legacy commit 与旧 snapshot 路径；sessionId、父 commit、活动层/legacy roots、HEAD [M1–M2] | 旧存档可带 legacy lineage，缺现代 state 不表示无记忆 | 严格读保全；不强制迁移。首期拒绝相关 legacy commit |
| Memory `memory_commit_journals` | state-last 提交先 journal，getState recovery 再 apply/delete；内含 nextState、nodes/revisions/transactions/delete IDs [M2] | 未完成提交可能恢复出新节点；读取 convenience API 本身改变磁盘 | 原始严格只读与 nested session/ID 校验；任何目标或无法归属 journal 拒绝，不运行 recovery 来让 gate 绿 |
| Session 记忆镜像 / 历史与 Prompt | ChatSession.longTermMemory、updatedThrough、memoryStateRevision/schema/tombstones；Android compileAndCache 写镜像；ChatRepository 索引/preview 与消息历史 [M4,C1] | 即使六表没有记录，也可能有 legacy session 镜像。Desktop planner 当前 memoryArchive/head 为 null，不证明数据不存在或未来不会被消费 | 严格 session 读取并核验 legacy 来源标记。首期不接受非空镜像、矛盾/未知来源状态；不修改其正文或水位。消息 CAS 负责消息索引/preview，不负责六表及镜像协调 |
| Moments `moment_posts` | Scheduler/service 保存独立帖子；characterCardId/sessionId、独立文本/图片、generationCheckpoint [P1–P3] | Desktop 未组装 Moments scheduler/service；旧完成帖子仍是历史资源。无显式 messageId/sourceTurnId/candidate/hash，不能确定逐条源关系 | 完成且无可恢复 checkpoint 的合法帖子可按“历史保留、不追溯重写”合同处理；需 Project 批准此边界。损坏、placeholder/失败/checkpoint 不可当已完成。严格读而非 loadAll |
| Moments `moment_tasks` / 可恢复 post checkpoint | task→session/card/post，PENDING/RUNNING/FAILED 等；生成读取最近三个非空非 SYSTEM 消息及编译记忆；checkpoint 保存 decision/draft/imagePrompt [P1–P3] | task.session 还可能回退 card 的最新活跃 session；失败帖子可留派生中间结果，未绑定精确源版本 | 严格 tasks/posts 关联和候选 session/card 范围；首期拒绝可能影响目标的未完成/可恢复任务及 checkpoint，包括来源不确定者。后续 P13 要求 source snapshot/revision 与取消/恢复准入；不默认删除或重新生成已有帖 |
| 聊天图片、生成历史、owned media | ChatMessage.images/generatedImageMetadata/generatedFromMessageId；自动/手动图片任务读取聊天上下文，保存单独图像消息；有 eligibility 与 task admission [I1–I3] | 已保存图像是历史成果，没有正文 sourceHash 自动失效合同。Studio、重试 checkpoint 和运行任务另有生命周期 | 现有图像/recipe 保留，F4 不删 owned media、不改元数据；待运行来源任务排斥。上下文读取可能不只目标消息，需 session/global 任务屏障。未登记写入或未知资源引用拒绝 |
| Fish 音频 `generated_voice_messages` / `voice_anchor_states` | Android VoiceMessageRepository；session/message/version/anchor/sourceText、displayContentSnapshot [O1] | Desktop 只有共享策略/设置，生成播放未验收；可有导入旧语音。修复保留 version ID，单凭 visibleVoices 的 ID 匹配不能证明旧音频仍对应新正文 | 首期严格排除目标消息相关 voice/anchor；legacy 空 session/version 无法排除关联则拒绝。保留音频不删除；后续 Fish 要求锚点 reconcile/失效显示，不借本轮实现 |
| SaveSlot / backup 历史快照 | save_slots + packageRef；独立 messages/vectorChunks/memorySnapshot/voice/media；coordinated snapshot 保全根 payload [O2–O3] | Desktop P11 SaveSlot/Android backup transfer 尚未完成；当前根 snapshot 有真实 exclusive facade | 已完成不可变快照保留旧世界，不自动改写其嵌入副本；不能读用户 archive 内容做本次审计。未来新快照/导入/恢复须与变更屏障串行；恢复 restart-required 后旧 proof 失效 |

### RAG：不能用“删除一个块”代替 source-turn 协调

ChatMemoryIndexPolicy v7 按完整 turn 构造内容，USER 使用 displayContent，ASSISTANT 去除状态块；聚合角色与正文后再分块。因此 messageId 不足以界定受影响块，sourceTurnId、metadata.messageIds、legacy identity 都需比较。sourceHash 是该分块文本的 hash，不是完整候选 version ID。RagManager 先保存新块，再删同 turn/重叠消息的旧自动块；失败可形成部分重建，手动块不由该清理自动移除。

Android 历史修复/恢复通过 ChatViewModel.refreshMemoryAfterMessageEdit 重建目标 turn 的聊天 RAG，或在 active context/无模型/失败时清理相应自动块 [C2]；该函数不是六表长期记忆事务。检索过滤 embeddingKey、contentVersion、active context/legacy 模式，却没有在该过滤段逐块比较当前 sourceHash [C3]。不能假定旧 v7 块读取时一定自行失效。

### 记忆：失效证据、停止注入与 AI repair 是三件事

当前 MemorySourceFingerprint.semantic 对稳定 turn 的非 SYSTEM 消息，以角色和 modelFacingBody（displayContent 或图片/空占位）做长度框架 hash；不包含 candidate ID、updatedAt 或真实图片路径 [M3]。previousSemantic/legacy 的字段范围不同，不能只重算新 hash 为旧节点背书。RAG 忽略状态块不代表 Memory 也忽略。

refreshSourceStaleness 比较来源证据，传播 stale node/HEAD，记录 repair queue；safeFrontier 仅允许证据有效的子树进入 Archive，stale HEAD 不注入。compileAndCacheLocked 又写 ChatSession 的镜像 [M4]。这些功能仍主要位于 Android，Desktop 没有对应已验收 reader/writer 协调。仅打 UI stale 标签不足以阻断未来 Prompt、Moments 或存档消费者。

getState 会先 recoverJournals；loadLocked 还会 ensureSourceTurns、迁移旧 schema、重整水位/状态并保存。二者不能作为无副作用的安全探针 [M2,M4]。后续 source repair 是明确用户动作与来源证据重校验的 AI 重建，不应在手动 F4 后偷偷收费执行 [M5]。原始消息、用户写过的记忆和历史 lineage 必须保全。

### Moments / 图片 / 存档：保留历史，隔离正在派生的快照

MomentPost/Task 不存在直接 message/source-turn hash；生成读最近消息和 memory，checkpoint 留 decision/draft/imagePrompt，可能在正文修改前形成。已完成历史帖子不是可重新生成缓存；但未完成/失败可恢复工作缺精确源版本证据，不能因 Desktop 没有 scheduler 而忽略。

DesktopAutomaticChatImages 多次严格比较原始完整消息；手动生成与保存也校验，persistDesktopChatImages 在 exclusive maintenance 下新增图像消息。这些是已存在保护，但不是所有未来派生写入的统一证明。格式修复不需撤销历史图片，Stop 部分正文也不应触发自动生图。SaveSlot 与备份的旧快照不追溯更新；新快照需捕获一致的消息/派生状态。

## 3. 源码定位索引

路径前缀：`S = app/sharedCore/src/main/kotlin/com/example/chatbar/`；`A = app/app/src/main/java/com/example/chatbar/`；`D = app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/`。以下每项给出真实路径、函数和审计 HEAD 行号，供 GitHub blob 定位。

| 编号 | 文件 / 函数 / 行号 |
| --- | --- |
| R1 | `S/data/local/entity/VectorChunk.kt:11–33`，ChunkSourceType、VectorChunkStorageContract、VectorChunk |
| R2 | `S/domain/rag/ChatMemoryIndexPolicy.kt:9–106,120–149`，buildTurns/buildIndexableTurns/contentForIndex、index modes/version、splitLongTurnContent |
| R3 | `A/domain/rag/RagRepository.kt:20–22,68–105,131–171,183–195,207–262`，saveChunks、message/session 查询、superseded/orphan 清理与 metadata 关联 |
| R4 | `A/domain/rag/RagManager.kt:36–68,83–130,136–188`，indexDocument、indexTimelineTurnMemory、search/deleteMemoryForMessage、chatMemoryChunkId |
| R5 | `D/DesktopCharacterDocumentRagCleanup.kt:9–20`；`D/DesktopChatRequestPlanner.kt:219–229`，DOCUMENT cleanup 与空 ragResults/null memory layers |
| M1 | `S/data/local/entity/LongTermMemory.kt:87–125,150–165,260–293,317–351,410–436,473–493`，source refs/nodes/HEAD/state/revisions/transactions/journal/snapshot/legacy commit |
| M2 | `A/data/repository/MemoryRepository.kt:22–31,43–112,200,285–290`，getReachableNodes、getState、commitStateLast、recoverJournals/applyJournal、snapshot 与六个 storage type |
| M3 | `S/domain/memory/MemorySourceFingerprint.kt:9–77`，semantic/previousSemantic/legacy/modelFacingBody |
| M4 | `A/domain/memory/LongTermMemoryService.kt:2434–2524,3021–3136,3140–3170,3186–3338`，loadLocked、refreshSourceStaleness、effectiveArchiveFrontier、compileAndCacheLocked |
| M5 | `A/domain/memory/MemorySourceRepairPolicy.kt:17–47,106–160`，startOrResume/pause、safeFrontier；`A/domain/memory/LongTermMemoryAutoMaintenanceCoordinator.kt:244–256`，enqueueSourceRepair/runnerMutex；service `:1180–1370` source repair/HEAD 校验 |
| M6 | `S/data/local/entity/ChatSession.kt:54–72`，legacy longTermMemory/水位、memoryStateRevision、schema 与墓碑 |
| P1 | `A/data/local/entity/MomentEntities.kt:8–30,107–116`，MomentPost/Task；`A/data/repository/MomentRepository.kt:13–14,46–55,86–119`，storage types、写入及 pending 查询 |
| P2 | `A/domain/moment/MomentGenerationService.kt:66–70,190–225,679–684`，checkpoint、recent messages/compiledMemory、编码解码 |
| P3 | `A/domain/moment/MomentScheduler.kt:139–214`，processTask 的 session fallback、RUNNING/生成/完成/失败 placeholder |
| I1 | `S/data/local/entity/ChatMessage.kt:46–93`，图片 metadata、候选、generatedFromMessageId、notice、source turn、displayContent |
| I2 | `D/DesktopAutomaticChatImages.kt:27–75`，completed/eligible；`D/DesktopChatImageRegeneration.kt:36–125,132–151`，preflight、generation、persistDesktopChatImages |
| I3 | `D/DesktopAppContainer.kt:258–271`，automatic images/task/controller 生产组装；`:42–43` 同一 storage/coordinator |
| O1 | `S/data/local/entity/FishAudioEntities.kt:7–29,81–100`；`S/domain/voice/VoiceMessageVersionPolicy.kt:12–19,100–116`；`A/data/repository/VoiceMessageRepository.kt:46–49,120–164,180–204,404–405`，音频、锚点、版本过滤/reconcile/storage types |
| O2 | `A/data/local/entity/SaveSlot.kt:12–58`；`A/data/repository/SaveSlotRepository.kt:28,43–83`，独立 snapshot/package 与原始导出 |
| O3 | `D/DesktopCoordinatedSnapshotService.kt:37–86`，create/restore/backup 的 exclusive 与 validate/list normal；`S/data/snapshot/AppDataSnapshotService.kt:123–146`，primitive 不提供跨 repository 全局锁 |
| C1 | `S/data/repository/ChatRepository.kt:604–717`，notice/body CAS、完整快照/ASSISTANT/版本检查、WRITE_MESSAGE/INDEX/PREVIEW、2500ms 异常读回和 cache invalidation |
| C2 | `A/ui/chat/ChatViewModel.kt:2069–2098,3431–3492`，manual/restore 与 refreshMemoryAfterMessageEdit；`:3021–3093` automatic repair 在 image/memory/Moments/RAG 下游前 |
| C3 | `A/ui/chat/ChatViewModel.kt:2508–2549`，RAG reader 过滤范围 |
| C4 | `D/DesktopMessageFormatRepairController.kt:17–21,72,143–164,271–291`，默认 UNKNOWN、两次 check、提交分类；`:88–103` 显式 confirmCommitReload |
| C5 | `S/data/local/JsonFileStorage.kt:94–107,129–146,198–251,279–303`，normal/per-entity mutex、临时文件替换、strict entity read/scan 与宽松 loadAll |
| C6 | `D/DesktopTaskRuntime.kt:83–148,166–168,245–253,379`，repair/chat/image/mutation 准入及 closeAndDrain |
| C7 | `D/DesktopDataOperationCoordinator.kt:38–48,67–106,109–132`，并发 normal、exclusive context、关闭 drain；`D/DesktopDataRootOwnership.kt:68–83`，cooperative cross-process ownership |

## 4. 存储证明与 TOCTOU 审计

1. `scanEntitiesStrict` 对 `.json` 解码、读取错误保留结果，目录枚举错误传播；比 loadAll/query 安全。但它只枚举 `.json`，没有 NOFOLLOW/reparse 验证，也不证明 storageId 等于实体 id、不检查跨表关联/未来字段、不覆盖 `.tmp`、替换备份及未知旁文件。B5B 必须结合限定目录的只读元数据 inventory、路径/身份验证；未知内容保留并拒绝，不清理以取得绿灯。
2. 所有六种 memory 表、voice/anchor、posts/tasks 都要发现不在便利查询中的孤儿/旧状态。非空目录不等于受影响，但无法严格归属相关性的记录必须 UNKNOWN。真正 Files.notExists 且父路径可安全枚举才是“缺失”；权限错误/断链不算空。
3. Repository mutex 在严格读消息、完整 expected/候选校验、写消息/索引/预览之间有效；不同 entity type 的 storage mutex 不能组成跨表事务。B3B 第二次 check 返回后，其他 normal operation 仍可写派生表，目标消息完全匹配也无法发现新增 chunk/node/task。
4. TaskRuntime 已排斥同会话 chat/repair/image/message mutation；并不自动覆盖 Android services、导入、恢复、直接 storage writer、未来 memory/Moments worker 或未登记任务。root ownership 只防合作进程双开，不能防同进程线程竞争或外部不合作写入。
5. 现有 coordinator 可复用：顶层 exclusive 排队后 drain normal，exclusive context 中嵌套 normal 可安全复用自身准入。必须从尚未持有 normal 的位置取得；normal→exclusive 升级明确拒绝。不能在 normal/mutex 内申请维护锁，也不能 detached coroutine 借用 context。应在终态短临界区内持有证明+CAS，不把长达模型流的整个任务锁住；准入期间阻断可能读取旧快照后迟到发布的派生任务。
6. CAS 的 `CommitUncertain` 保留 stage/cause/attempted/durable，异常读回预算 2500ms，索引内存缓存失效在 finally。B4 的显式消息读回核对不是“派生数据全部一致”的证明；也没有保证 preview/index 已修复。阻塞式文件 I/O 仍无物理绝对超时保证，不能释放仍使用的数据根锁。

## 5. 待审 F4-B5B 最小实施合同

### 第一子集：证明没有受影响的可变派生状态

建议先只支持本地完整可读、身份一致的目标 ASSISTANT 当前候选，且同时满足：

- 严格 session/messages 读取，无未知 legacy source 身份；相关 metadata、候选、notice 和 expected 与最终 CAS 保持完整一致。
- vector_chunks 全表严格分类，无目标 session CHAT_MEMORY、矛盾 message/turn 关联或无法排除关联的 legacy/损坏记录；明确独立 DOCUMENT 保留。
- 六种 Memory 表无目标 session/无法归属记录；ChatSession 没有未解释的 legacy memory 镜像/来源水位。首期不尝试证明一个复杂 state 的空图安全，不进行 journal recovery。
- voice/anchor 无目标消息依赖或无法归属的 legacy 记录。只有不可变图片及合法独立历史帖子时，按 Project 明确认可的历史保留合同处理；未完成/可恢复 Moments checkpoint/task 拒绝。
- root ownership 持续有效；修复任务准入覆盖 session/global 派生工作及所有 root writer；最终 inventory/check/CAS 在同一顶层 exclusive context 完成。导入/恢复/备份串行且不借用过期 proof。若某 writer 无法纳入屏障，拒绝，不伪造证明。

proof 必须绑定 data-root generation、session/message、完整 expected/candidate、严格扫描证据及持有中的 admission；不是可缓存的布尔设置。开始前可预检避免收费；终态必须重新证明。流期间新增相关数据导致终态拒绝，预览不落盘，错误明确。内存 UNKNOWN 默认保留，只有通过新合同与测试的生产路径才可返回 VERIFIED_SAFE。

建议实施文件：新增紧邻 Controller 的 `DesktopFormatRepairDerivedSafety.kt`（严格 inventory/分类及带作用域 proof），必要的 Controller/AppContainer 组装与终态 scope 适配、TaskRuntime/coordinator 直接准入测试。优先复用 shared serializers 与 scanEntitiesStrict，不移动完整 Android Memory/Moments runtime；如果跨目录 inventory/租约需改共享 storage 接口，应先交 Project 批准最小接口，不在既有 safety.check 布尔接口内假装持锁成功。具体允许文件由正式 B5B 任务冻结。

### 已存在派生数据的路径：暂不纳入第一子集

| 情形 | 必须合同 / 首期处理 |
| --- | --- |
| 受影响 RAG | P9 需按完整 turn/legacy message 集合确定影响范围、持久化失效/重建状态和 reader 屏障，覆盖部分重建与旧块。首期 REJECT，不删除块以开放功能 |
| 受影响 Memory | P10 需跨 active roots/children/HEAD/镜像/commit journal 记录 source mutation，停止 stale 注入，保全用户内容；AI source repair 必须用户显式授权。首期 REJECT |
| 未完成 Moments / voice | P13/Fish 需快照来源、任务所有权与迟到发布/恢复校验；保留已完成资源，不自动重生成。首期 REJECT |
| 不可读、不完整、旧版本不明 | UNKNOWN → REJECT，保留原文件、错误分类与可诊断 storage type，不自动 recovery/迁移/删除 |
| 消息提交成功后协调失败 | 若未来开放已有数据路径，需与消息提交关联的 durable 协调意图/reader fail-closed 状态与崩溃恢复合同。不能只写消息再尽力清索引；不能谎称回滚、完整成功或自动收费重试。该路径首期不开放，涉及 journal/schema 必须另审 |
| 并发、候选切换、异常退出 | expected CAS 拒绝目标变化；proof lease 拒绝派生变化。进程重开所有内存 proof 失效；严格磁盘核对、未完成协调记录拒绝。不得恢复未完成的内存预览 |
| COMMIT_UNCERTAIN | 保留原证据，禁止自动二次写入/恢复。显式 B4 核对只能确认消息快照；需重新证明派生与索引/preview 状态，不能据其 confirmedMessage 自动授予安全 |
| STOPPED / RESTORED | STOPPED 是实际混合正文提交，使用同一完整影响证明；RESTORED 也重新证明，原文恢复不意味着旧派生数据恰好有效。无有效 notice 不恢复。生命周期取消不持久化预览 |
| unchanged / notice-only ERROR | D-039 及官方 Policy 不变；正文真正不变可能不需重建，但现有统一 gate 不在本审计中调整。notice-only 保留基础 content/候选，不能用它洗掉未确认状态 |

### 最小验证矩阵（建议，不是已执行）

| 合成场景 | 必须真实证据 |
| --- | --- |
| 全表为空、合法独立 DOCUMENT/完成帖子/图片 | 同一 container、storage、root 的 Controller→TaskRuntime→严格 proof→真实 CAS→重新打开；资源字节/元数据不变 |
| 目标/其他 session 的 turn 块、多块/legacy/孤儿；六 Memory 表分别有记录 | 精确相关拒绝、无关记录仅在身份可证时不误拒绝；逐文件写前后 hash，无收费请求/无清理 |
| 镜像记忆、待提交 journal、missing child、损坏 JSON、目录不可读、reparse/未知旁文件 | 失败可见、UNKNOWN、没有调用 getState/loadLocked 或触发迁移；不跟随不安全路径 |
| Moments 任务/失败 checkpoint、voice/anchor legacy | 可恢复状态不能绕过；已完成历史保全；源关联不明拒绝 |
| 扫描后 writer、CAS 前新增 chunk/node/task、snapshot/restore 排队 | CompletableDeferred 明确挂起点证明屏障，无固定 sleep；无检查到写入窗口；context 嵌套不死锁、取消可靠释放 |
| Stop/Done/Error/关闭取消、候选切换、删除、A/B 切换 | 沿用 B3B 首终态、D-039、CAS；STOPPED/RESTORED 真正重新检查，不误写其他会话 |
| WRITE_MESSAGE/INDEX/PREVIEW 故障、异常读回超时、进程终止 | CommitUncertain cause/stage/durable 保全；不自动重试或回滚；重开检查持久化状态及后续屏障，不伪造完全事务成功 |

可复用测试入口：`JsonFileStorageStrictScanTest`、`ChatRepositoryConditionalUpdateTest`、`ChatRepositoryFoundationTest`、`ChatMemoryIndexPolicyTest`、`MemorySourceFingerprintTest`、Android `MemorySourceRepairPolicyTest` / `MomentGenerationServiceTest` / `SaveSlotSerializationTest`，以及 Desktop `DesktopDataOperationCoordinatorTest`、`DesktopTaskRuntimeTest`、`DesktopMessageFormatRepairTest`、`DesktopFormatRepairInteractionTest`、`DesktopFormatRepairPresentationTest`。新增 B5B 同根集成不能以故障专用第二 storage 拓扑冒充生产链路。全部使用合成临时根/localhost，不读取真实数据。

## 6. 未确认事项与交接

本轮没有观测真实数据是否含上述实体，也没有运行准入、CAS、恢复、Provider、Windows UI 或测试。完成帖子/历史图片的保留边界、跨 writer 的 exclusive scope 设计、未知旁文件处理及 proof 生命周期仍须 Project 审核；它们不是已验收实现。非合作外部写入与不可取消阻塞 I/O 无绝对安全/超时承诺。

可先实施的建议仅为“严格无受影响派生数据 + 终态写入屏障”子集。已有 RAG/Memory 的失效与读取协调、Moments 恢复任务、voice source reconcile、P11 archive transfer 和 F4 自动链路须等待对应运行机制/单独合同；不删除数据或使用 no-op 来代替协调。生产 **UNKNOWN → REJECTED** 保持，`13` 完整格式修复仍 PENDING。

**B5A SOURCE AUDIT COMPLETE / PROJECT REVIEW PENDING。B5B：NOT IMPLEMENTED。** 本轮只提交本审计、B4 结案及两份 CURRENT 顶部登记；编译、自动化测试、Windows 人工、打包、真实 API：NOT RUN；不合并 desktop、不同步 upstream、不扩大任何 acceptance。
