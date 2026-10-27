# P9-S2B-G2-S0 Production Authority Audit

日期：2026-10-17。模式：**EXACT-SOURCE AUDIT + DOCS-ONLY**。审计对象为 feature `e66dd6a782b13c118e13d0515409121f0f3d5604` 的真实源码；本文件是供 Project 审批的合同候选，不是生产协议注册、实现或验收。本轮未运行 Gradle、JUnit、GUI、打包、恢复、索引或 Provider 请求，未访问真实用户数据。

## 1. 控制点、权威与结论

起始 local HEAD、tracking origin feature、GitHub live feature 均为上述 SHA；index 为空、tracked worktree clean。三个既有外部未跟踪 JPEG 仅核对 Git 状态，不读取、不纳入证据、不处理。只新增本文档。正式 validated upstream baseline 保持 **ChatBar 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`**。

按 Source Map → baseline → CURRENT → 正式 1.4.1 Feature Map / Long-Term Memory Skill → parity / compatibility → 120、125–128、130–132 的顺序定向查阅，再核对下文 Kotlin。正式 Skill 中的旧 Android 路径按当前共享迁移后的实际 ownership 定位，不推断 Android 仍保留第二套 Repository。技能与文档的精确 blob 见附录。

用户提供的最新 upstream 观察为 `979cb505958dffb1b6d64a8de657434f198ffa96`，已发布 1.4.6 指定 SHA 为 `9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d`。本地 `upstream/master` 实际仍指向 `550409689df8c51f459fb50b4e04c8ac2fa4bf35`，不能冒充最新观察。1.4.5/1.4.6 对比通过只读 GitHub 文件接口按精确 SHA 获取；未 fetch、同步、移动任何 upstream ref，也未将观察版本变成 validated baseline。1.4.5 使用 D-038 指定 `1198a13cf3008de1358b8a69aa5e40b64797c8d9`。

**结论：可以先审批一个不接 Main、不放行业务、不写盘的 G2-A 严格只读识别器；当前不足以批准生产 Writer 或自动 Recovery。** J 仍为首选候选；全部完成态在 current head、配置 writer 交接、live 证明尚不成立时保持 BLOCKED，不能凭 receipt 存在返回 READY/COMMITTED 或 businessOpen=true。

本文分类含义：

| 分类 | 含义 |
| --- | --- |
| ACCEPT | 源码事实可确认，或建议作为下一合同不可降低的约束；不是 Project 新验收 |
| REJECT | 方案违反现有边界，不应采用 |
| BLOCKED | 缺少必要机制或证据；当前操作不得开放 |
| DECISION REQUIRED | 必须由 Project 冻结字段、顺序、限额或产品行为后才可编码相应协议 |

## 2. 当前事实与 127/128 的差异

| 项目 | 当前 exact source / 已验收事实 | 历史候选不能外推的部分 |
| --- | --- | --- |
| 启动入口 | C01 `main → runDesktopMain → DesktopStartupGate.run`；C02 在 pickers/container/settings/secrets/备份之前检查 G0。Portable 同一 ownership 下先检查、activation、再全新检查 | 128 当时“无 Main 接入”的历史状态已由 132 的限定 G1B 接入取代；128 的广义恢复方案仍未实施 |
| G0 | C03 仅 metadata，任何存在的候选域都拒绝；预算可先产生 BUDGET/READ/UNSAFE | 不解释 journal，不证明旧 `vector_chunks`、`.replace-*`、`.backup-*` 安全 |
| root ownership | C04 持续 FileChannel/FileLock；`isHeld` 检查本 JVM owner/channel/lock 有效 | 不是持久 root UUID、writer epoch、文件系统不可替换保证或跨端身份 |
| 准备任务 | C18 两次短 exclusive 严格快照，网络期间不持 normal；返回摘要 `PREPARED_ONLY`，不发布向量，批次正文/向量不从服务返回 | 127 的 staged spool、生产 writer lease、最终发布、head、receipt、缓存 epoch 均不存在 |
| 只读检索 | C19 同根 storage/coordinator、短 exclusive、严格文件扫描及来源重证，按 shared cosine 排序；未接聊天 Prompt | 不能凭 Reader 成功证明其他 writer 已持久交接；没有跨代发布 epoch |
| 通用存储 | C27 单类型 mutex 外 normal；单 JSON temp→atomic move，平台不支持时 fallback move；save 后发 cache | 不是 card+vectors+head 的事务，不是断电耐久性承诺；streaming replace 的旧机制不等于新 J |
| test-only 实验 | 125/126 固定集合 crash 原型；R1 completed live 重证、第二次 publish 拒绝 | 不迁入生产，不把“永远匹配首张 card hash”或“永拒第二笔”变成产品规则 |
| F4 安全证明 | C26 final exclusive 内重新 inventory+CAS，保全 Repository receipt，未知域仍拒绝 | 不自动识别新事务域；不能为了 G2 接入把该闭集放宽或注入 VERIFIED_SAFE |

C03:46 的“无 production caller”注释仍是 G0 原始历史文字；调用事实由 C01/C02 和 132 确认。仅记录差异，本轮不改已验收代码或注释。

## 3. Production Writer authority matrix

下表“最小修改点”均为**后续候选**，本轮不实施。normal 是同一个 coordinator 的 cooperative operation；exclusive 排斥这些已登记调用，但不覆盖任意原始 Files 写入、独立 storage/coordinator、外部编辑或未登记的排队任务。持久交接必须覆盖直接方法入口，而非 UI enabled 状态。

| 入口 / 调用链 | 文件 / Entity 及现有保护 | 尚未覆盖的生命周期 | 应有 durable handoff / 最小修改点 | 裁定 |
| --- | --- | --- | --- | --- |
| C08 `addTextDocument/editDocument/renameDocument/removeDocument/clearDocuments → save/saveAndLeave` | `edit_drafts`、`draft_assets`、`documents`、完整 `character_cards`；draftMutex/debounce；实际 entity 操作各有 storage normal；C05 注入 save，obsolete cleanup 另走 exclusive | 编辑器 source/read→资源 materialize→card commit→cache/改会话标题→草稿清理跨多阶段；无 index writer lease，原始资源写不受整段 exclusive 保护；直接 save 不检查文档准备任务 | 在 editor commit 入口获得 card mutation admission；重读 current head/source；将 doc 替换/删除、仅改名字、草稿 materialization 与对应 transition 分类；提交后 head/cache 确认前不释放可发布资格，旧资源留存到交接完成 | BLOCKED |
| C09 draft resource `stageBytes/materialize/rollback/discardSession`，C10 `materializeDocument/createOwnedFile/deleteOwned` | 原始 Files 创建/删除，局部 NOFOLLOW/路径检查；不是 coordinator API | 同步调用没有 TaskRuntime lease；snapshot/maintenance 不能仅靠 storage gate 排斥这些写入；尚未持久 materialization ledger | 调用者的资源 lease 必须覆盖创建、引用提交、失败核实与清理；非 index 临时 draft 也必须纳入 root quiescence；不可在持 normal 后升级 cleanup exclusive | BLOCKED |
| C11 `CharacterRepository.save/saveObserved/update/delete/saveForTransfer/deleteForTransfer` | `character_cards/<id>.json`；storage normal+类型 mutex；save 后刷新 Repository Flow | Repository initialize/宽松 cache 读不是严格 source CAS；公开方法无 head/预期完整字节参数；storage 写与后续 refresh 不是独立持久提交 | 在 Repository/统一 mutation authority 边界约束所有 card writer，而非只改 editor 按钮；支持 expected generation、已批准字段差异和 receipt 交接；未知调用拒绝 | BLOCKED |
| C13 Management `import/resolveConflict/duplicate/confirmDeletion` → C12 Transfer | card、format/world、owned documents/images、DOCUMENT chunks；management 本地 operations mutex；C05 给 transfer 注入同 coordinator，C12 全流程 normal | Controller mutex 不等于 card/index lease；normal 之间可并发；已有 overwrite/delete 的 card commit 后资源/RAG cleanup，crash 未有新 head 接力 | 在 Transfer 的直接 overwrite/delete/import 入口增加 card generation transition；保留已存在 post-commit warning/资源保留语义。duplicate/new ID 明确无旧 head 或绑定新 lineage，不继承旧 receipt | BLOCKED |
| C12 `overwrite/deleteCard → cleanupOwnedResources → C14.deleteDocumentChunks` | transfer normal 覆盖；旧资源删除后按 DOCUMENT+card sourceId 清 chunks；C14 用 `deleteWhereUncached` | 现有清理按整卡 DOCUMENT，非 doc 事务；C27 宽松 decode 失败跳过，不是严格证明；其他来源与损坏旁文件不能据此算清理完成 | pending/未交接 head 时禁止清理；未来 strict 枚举明确归属集合，先持久交接再精确清理；旧文件/未知块保全，不原地改通用清理假造原子性 | BLOCKED |
| C15 `deleteUnreferenced` | 自身顶层 exclusive，扫描 entities 引用后删除 images/documents；原始 JSON 引用扫描，不递归新事务侧车 | 扫描只看 singleton / 一层 collection JSON；新 J 的 stage/old/history 引用不在现有语义中；也不能 nested exclusive | 将 sealed intent/backup/history 引用纳入独立资源保留 authority；定义 ungated primitive 的受控调用层或统一 owner，未批准前拒绝与事务并行的 cleanup | BLOCKED |
| C16 preset `initialize/importOrReconcile/bindPresetWorldBooks/repairCharacterDocuments` | preset ledger、card/world/format、资源；transfer normal；文档 repair 显式 normal；binding 直接 characters.save；循环与 ledger 各次 storage gate | 初始化在 G1B 放行后可能执行；repair 改路径及 rag 状态；无持久 head handoff，部分失败靠原有 warning/下次重试 | 新域存在不启动这些路径；运行期恢复预设/自动文档补齐也需 card transition。preset ledger 不是 index receipt，不据此删除 backup | BLOCKED |
| C17 Preset Suite `restore` | 整段 exclusive，严格 durable card/world 扫描及 observed commit，取消保全未决目标 | process-local unresolved 不等于 crash journal；绑定 worldIds 改整 card hash；无 index history supersession | 必须以显式 PRESET_BIND/CREATE 等 transition 接 current head；不可因已 exclusive 跳过 receipt；需避免调用自身 exclusive cleanup | BLOCKED |
| C34 WorldBook `confirmClearSourceCharacters → characters.update` | 修改 CharacterCard 的 imported character sections；公开 Controller 方法，storage normal | 不在 Character editor 或 index preparation admission 中；即使 customDocuments 不变也改变整卡 hash | 加入统一 CARD_EDIT 接力，验证允许字段/保留 doc generation，不从旧 receipt 自动回滚 | BLOCKED |
| C07 TaskRuntime `withDocumentPreparation` → C18 preparation | 同 card/doc 去重、structured child、登记 activeJobs，close/drain 取消并 join；两个 snapshot exclusive | 网络期间可发生 card/config edit，第二 snapshot 只检测当次旧输入；没有生产 spool/publish lease，也不排斥 editor/transfer/snapshot | 新 DOCUMENT_INDEX lease 需读源前登记、覆盖网络/等待/最终提交与 receipt；维护先关新准入、等已读旧源者退出或使其 epoch 失效，再 exclusive；迟到结果不得发布 | BLOCKED |
| C19 Reader / C20 source | 同一 coordinator exclusive；ownership；逐文件严格扫描、预算、shared cosine；仅结果 topK 有界 | 无持久 head 校验、无新 generation publication/cache barrier；独立 storage 的 cache 不共享 | 未来 Reader 校验 current head/generation 与 source/config，publish 前后 epoch 全隔离；旧 epoch 不得在 UI/cache 继续作为当前结果 | BLOCKED（新协议接入）；现有只读合同保持 |
| C06 coordinator / C27 storage | normal admission 在 per-entity mutex 外；维护 pending 阻新 normal；顶层 exclusive，禁止 normal→exclusive 与 nested exclusive；closeAndDrain | 无 durable journal / head；raw resources、cached source 及 network gap 不自动排斥；新建第二实例可绕过同一 coordinator | 保持 app root 单 authority；长网络在 exclusive 外；最终 proof/install/head/cache 同一 scope；未交接或 commit uncertain seal restart-required，真实 I/O 不假装排水 | ACCEPT 现有边界 / BLOCKED durable 层 |
| C21 snapshot / C22 automatic backup | create/restore/prune/automatic execution exclusive；自动 scheduler 有 lifecycle pause/stop/resume；restore requireRestart | snapshot 只知字节，不知 J 语义；pending 事务完整副本不等于可移植可恢复；原始资源写可在 lease 外 | 所有 writer 纳入后才可声明 quiescent；live pending/uncertain 默认拒绝新全量操作；完成态同 scope复制整个域和依赖，restore 先识别验证并重新建立 root/head authority | BLOCKED domain-aware 集成 |
| C23/C24 data-root migration / Portable | source outer ownership+destination ownership，暂停自动备份，exclusive 内 preflight→safety snapshot→copy/hash→bootstrap commit；restart seal，目的 ownership 留到关闭 | 目前没有 J root lineage/adoption；复制旧 ownerId 不能证明新 root 等同；Portable selector 只改变 root authority，不转换 receipt | pending 拒绝迁移；后续完成态需完整域复制与 source→destination adoption receipt，旧根保全；不能迁移 lock artifact/持锁 token 或把物理文件 ID 当跨根身份 | BLOCKED |
| C26 F4 final safety / C05 装配 | 同 container storage/coordinator，final exclusive→strict inventory→CAS；仅已证明第一类场景 | 新事务目录不在 F4 namespace 闭集中；并无 index producer lease 或事务解释 | 保留 UNKNOWN→REJECTED；future domain adoption 未批准前拒绝，不能靠 P9 启动放宽 F4 | ACCEPT 保持拒绝 |
| C28/C29/C30/C31 配置、C32/C33 Android 迁移/编辑，或 whole-root restore | embedding singleton/collection、SecretStore、缓存；各方法局部 mutex/storage normal/凭据 transaction | 无持久配置 epoch；密钥可能先变、实体后失败；整根恢复绕过 ModelRepository；见第 7 节 | 所有配置写入都需同一 config-writer authority 和 nonsecret epoch/intent；未知旧来源不能建立可信 attestation | BLOCKED |

C05 `close` 顺序为辅助服务 → TaskRuntime drain → 各草稿 controller → 自动备份 → coordinator → migration retained destination。现有顺序不自动覆盖未来 DOCUMENT writer；新 writer 必须登记到相同 drain，并保持 G1A 未确认排水时保留 ownership。不能把编辑器 `closeAndDrain` 的 flush/cancel 或 task timeout 返回外推为所有原始 I/O 已终止。

## 4. J 协议候选：字段、路径与版本

以下名称是 **J-v1-draft 候选标识，不是已有 schemaVersion / Entity 类型**。Project 需逐项冻结，G2-A 只能针对获批的只读 schema 编码；不能生成它们或按该草案写入生产。不同版本/缺字段不自动迁移，未知 enum/字段/路径一律拒绝。

| 候选记录 / 合法路径闭集（相对 root） | 必需内容与验证 | 状态 |
| --- | --- | --- |
| `entities/document_index_transactions/domain.json` | 固定 magic、major/minor、协议/producer capability版本、logical root lineage、域 owner namespace；不能存用户名、完整绝对路径或 secret | DECISION REQUIRED：字面、schema、版本拒绝规则、root lineage 创建/迁移 authority |
| `…/active/<txId>/intent.json` | 不可变 txId、task generation、cardId、docId、操作种类、expected head/revision、config epoch、文档引用及精确 hash、完整 old/new card hash/length、old/new集合描述及 unrelated scope 指纹 | DECISION REQUIRED：字段闭集、操作 enum、canonical 编码；IDs 有界且 path-safe |
| `…/active/<txId>/old/card.json`、`stage/card.json`、`old/vectors/<storageId>.json`、`stage/vectors/<storageId>.json` | 完整原始旧字节和待提交新字节，不从反序列化对象重编码冒充 old backup；引用每文件 length/SHA-256。vectors 只含明确 target DOCUMENT，禁止 CHAT_MEMORY 混入 | ACCEPT 保全原则；DECISION REQUIRED 精确路径/授权来源/文件限额 |
| `…/active/<txId>/old-set.jsonl`、`new-set.jsonl`、`unrelated.jsonl`、`seal.json` | JSONL 每项固定字段、唯一 storageId、严格顺序与累计 hash；seal 绑定 exact intent/list/payload 字节及数量，无缺项/额外文件；immutable seal 后不得修改 plan | DECISION REQUIRED canonical 顺序、hash framing、空集合、seal publication/fsync |
| `…/active/<txId>/commit.json` | txId+intent/seal digest、完整 new-card/new-set hash、前 head、新 head proposal；marker 只能证明已批准协议中定义的 commit point，不能凭文件存在成功 | DECISION REQUIRED commit point 与 marker/head 次序、撕裂 marker 处理 |
| `…/heads/<cardId>.json` | 当前整卡 revision/headId、predecessor、由哪笔 tx/transition 产生、当前 card hash、每 doc 的 source/index generation、config epoch、receipt引用 | DECISION REQUIRED head/receipt 原子接力和 reader epoch；不增加 CharacterCard/VectorChunk 持久字段 |
| `…/history/<txId>/receipt.json` | 当时已验证的意图/结果/commit/发布证据及 predecessor/supersededBy 关联；immutable receipt 不等于当前 live authority | DECISION REQUIRED append/退役表达法、未完成 head 接力、history 边界 |
| 后续非 index writer 的 transition | card-edit/rename、doc替换/删除、preset/import/delete、config改变、root restore/adoption 各自 sealed expected→result/head | DECISION REQUIRED：不能只给一种 index tx 设计恢复，再允许其他 writer 绕开 |
| 其他文件、别名、额外 active、未知 history/临时旁文件 | 没有批准路径不可当不存在；保留原件并阻断。包括 generic `.replace-*`/`.backup-*`，不收编为 J | REJECT 自动忽略、清理或猜测迁移 |

同一 root 初版建议至多一个 active、同 card 串行推进 whole-card head；这只是简化恢复的候选，不表示可忽略其他 card 的 writer。逻辑 rootId、当前进程 ownership generation、Windows volume/file ID 是三个不同概念：前者是持久 lineage，第二项仅当前 lease，第三项仅本次物理身份观察；不得互相替代。未注册新域前 G1B 仍拒绝其存在。

### 4.1 不变量与精确保全

- old backup 必须在任何 live mutation **之前**完整存在、sealed 并读回确认；保留旧 card、旧目标向量与依赖资源的原字节。新集合全部预算检查、与 current source/config/head 重证后才进入 install。
- 不因解析成功就重写未知字段或“规范化”旧文件；read-only 识别器要先拒绝未知 schema/重复 JSON key，不能用宽松 `ignoreUnknownKeys` 或普通对象相等证明字节相等。
- unrelated 向量是独立 inventory：区分 DOCUMENT/CHARACTER_SETTING/CHAT_MEMORY、cardId/docId/metadata；不由 docId 全库删除。既有损坏或来源不明的数据保持 BLOCKED。
- SHA-256 必须配合文件字节长度、闭集路径、唯一 storage identity、owner/generation 和前后文件身份复核；hash 不是认证签名，不能防御绕过 ownership 的恶意外部写入。保留该威胁边界。
- 通用 C27 单文件 move/fallback 没有提供跨文件 fsync/directory durability 协议。J 的 crash 顺序和电源故障承诺必须单独批准并有平台证据，不能复制 test fixture 后声称生产耐久。
- 缺失的 old、seal、marker/head/history 或相互矛盾均拒绝；不能通过删 active/旧 receipt、补写 marker、重跑 Embedding 让状态变绿。

### 4.2 证据驱动状态与中断

| 持久证据组合 / 事件 | 可报告的候选分类 | 当前允许行为 |
| --- | --- | --- |
| 可信无域 | 沿用 G0 NO_DOMAIN 的有限含义 | 仅现有 G1B 继续原业务；不检查全库后声称安全 |
| 只有目录、未知版本、未 seal intent、缺 old 或额外文件 | UNKNOWN / INCOMPLETE | BLOCKED，原样保留；不是“空事务可清理” |
| sealed active，old 完整，live 匹配预期 old，尚无 marker | PENDING_PRECOMMIT_EVIDENCE | 识别事实，不自动回滚/放行业务；G2-B 再批准处理 |
| active 安装中，live 为批准 plan 的混合 old/new，marker 未确认 | PENDING_INSTALL / COMMIT_UNCERTAIN | 不猜成功、不得继续普通 writer；恢复决策待 G2-B |
| marker 有效且 live 完全匹配 new，但 head/receipt/cache 接力缺失 | COMMITTED_EVIDENCE / HANDOFF_INCOMPLETE | 只能报告磁盘证据，不是 operation Success，不开放业务 |
| head、receipt、完整 live/source/config/writer 证明全成立 | COMPLETED_APPLICABLE 候选 | 在生产 authority 尚未实现前仍 BLOCKED；未来业务准入需另批 |
| 历史 receipt 被后继合法 head/transition 接替 | HISTORICAL_SUPERSEDED | 验证其当时事实与交接链，不要求今天的 card 永远等于旧 hash；不允许旧 receipt 回滚今天 |
| completed 后数据损坏/删除、head倒退、重复或分叉 receipt、无法解释来源 | COMMIT_UNCERTAIN / CORRUPT | 拒绝；不自动回滚已完成事务、不删损坏文件 |
| 网络/取消发生于持久 intent 前 | 未发布准备结果作废 | 未来 writer 可报告取消，不自动再次收费；不推定已写 |
| 取消/JVM crash/恢复再次中断发生于持久 intent 后 | 按磁盘证据重新分类 | 不以 Job.cancel/超时作为已回滚证明；不释放仍有真实 I/O 的 ownership |
| 写入后读回失败/超时或异常发生于 head/cache发布阶段 | COMMIT_UNCERTAIN，保留 cause、stage、attempted 与可得 durable evidence | 无自动 retry/rollback；不得抹平成 StorageFailure 或完整 Success |

state-last 的“state”需明确是哪个 durable head、何时越过 commit point。**DECISION REQUIRED**：marker→head→history，或 history→head 的具体顺序与每个 crash gap 的可验证解析；禁止假定多文件 rename 是一次原子提交。G2-A 可识别缺口并拒绝，但不能补齐它。真正恢复写入及恢复再次崩溃的幂等性属于独立 G2-B。

### 4.3 后续合法变更与多代接力

| 后续操作 | 候选交接要求 | 现在的结论 |
| --- | --- | --- |
| 同卡第二文档 | expected 当前 whole-card head+各 doc generation；串行更新 head，只改变指定 doc 集合，第一份文档与 unrelated 集合完整保留 | DECISION REQUIRED / Writer BLOCKED |
| 同文档再次重建 | 新 tx 显式 predecessor、config/source epoch；完整保留上一代 old；新 head 生效后旧 receipt 只作历史事实 | DECISION REQUIRED；不能沿用实验“第二笔永拒” |
| 角色改名/WorldBook清空导入角色/绑定变更 | CARD_EDIT transition 验证仅批准字段差异；doc/source未变时保留 doc generation，推进 whole-card hash/head | DECISION REQUIRED；旧 hash不匹配不是自动回滚理由 |
| 文档改名 | 官方 sourceLabel 依赖 fileName，不能视为无关显示字段；需失效/重建或明确 unavailable transition | BLOCKED，不能只改 head 后沿用旧块 |
| 替换/删除文档、覆盖/删除卡 | 精确受影响集合、旧资源保全、head tombstone/retirement 与清理交接，保留其他来源；删除的 crash 协议同样需要批准 | BLOCKED |
| config更改/删除/重建、restore/root复制 | 旧 generation 的在途任务失效，不允许迟到 publish；新 config/root adoption 证据后再启用 reader | BLOCKED，见第 7/8 节 |
| history 到限 / retirement / GC | 不自动删除；先拒绝新 publish。历史、备份/恢复引用及保全期需独立策略 | DECISION REQUIRED；G2-A/G2-B均无GC暗授权 |

## 5. G0 预算冲突与入口交接

C03 精确预算：累计 `entries≤256`（父目录枚举也计数）、每次域递归增加 depth，`depth≤4`；name≤255字符、累计 name≤8192、累计 path≤32768、ancestors≤64；普通文件 **metadata size 累计≤64 MiB**。不是实际内容读取上限，更不是 J payload 预算。`scanDomain` 先扫描整个存在域，再决定 `DOMAIN_PRESENT_UNSUPPORTED`；因此大域会先返回 `BUDGET_EXCEEDED`。

127/128 的候选为 old/new 各2048块/64MiB、全库100,000项/4GiB、history建议128份，明显不能硬塞 G0。即使单个合法 active + stage/old 也可超过256项、64MiB或depth4。不得只在 G0 返回 DOMAIN_PRESENT 后调用 G2，然后宣称所有合法域可恢复；预算错误路径将永远到不了该分支。

| 方案 | 评价 |
| --- | --- |
| 将任何 G0 BUDGET/READ_ERROR/UNSAFE_PATH 当已知域合法入口 | REJECT：错误不能变成身份/版本证明 |
| 放大 G0 固定预算、令它直接 parse/recover | REJECT 于 G2-A 范围；改变已验收 G0 元数据合同 |
| G2-A 独立显式调用，读取合成根，不接 Main；真实 Main 继续现行 G1B 阻断全部域 | ACCEPT 下一最小切片，解决识别器测试而不冒充生产恢复 |
| 后续生产 dispatch：在持 ownership、业务未构造时，先用独立的有界 metadata locator 证明 root/entities/domain 的身份与闭集版本入口；域缺失仍走现有 G0，域存在进入有独立预算的 G2。身份/版本读取任一失败仍拒绝 | DECISION REQUIRED：需要单独审查 G0/G1B 调用链修订；本轮及 G2-A不实施 |
| locator 只检查域文件名/魔数便放行业务 | REJECT：locator仅选择解析路径，不提供 READY；G2证据不足照样 BLOCK |

后续 locator 不可先递归全部 payload 才 dispatch，也不能复用 G0 某次失败中得到的半份快照；应在同一实际 ownership/启动 generation 下全新观察祖先、entities、domain及入口文件，读后复核。目录枚举自身有预算，旁文件过多、别名碰撞和访问错误仍拒绝，合法但超限不保证可恢复。Portable 第一次/activation后第二次检查的现有顺序不在 G2-A 更改范围。

### 5.1 G2-A 拟议独立预算（待 Project 冻结）

| 范围 | 候选上限及理由 |
| --- | --- |
| 控制记录 | 单件≤64KiB；严格UTF-8/JSON depth≤64、ID≤256字符；路径组件还须 Windows安全限制，不能仅因为ID长度合法就允许255以上文件名 |
| 当前 active / 路径 | active≤1；域树≤8192 entries、depth≤8、history receipts≤128；8192为一组old/new各2048块加card/控制/历史保留余量，非无界增长许可 |
| card/vector与目标集合 | 单JSON≤8MiB；old/new各≤2048块、各≤64MiB；完整域累计读取≤512MiB（含历史保全payload），超限只拒绝；不截断、抽样或清理 |
| 来源 | 继承C20 source≤1MiB、fileName256、heading128、label1024、sections/chunks2048、plan≤2Mi字符，先预算再调用shared chunker |
| live applicability inventory | 独立累计≤100,000条/4GiB，逐文件；compact身份/hash预算≤64MiB，单次decoded payload≤8MiB；history若保留多代完整旧payload仍计域总预算 |
| 算术与时间 | checked Long加/乘/计数，增长前拒绝；decoder和数据结构额外开销应实测，但不声明精确heap峰值；逻辑 deadline仅约束可取消工作，不承诺阻塞IO物理超时 |

8192/8/512MiB 是本 S0 **新候选**，不是 G0 参数，也未获实现批准；应在批准前用边界计算验证路径总长/累计名称/字符预算（建议沿128有限约束另定），不接受“无限长路径由 OS 处理”。若旧历史数据超限，识别器返回 BUDGET/BLOCKED 并保全；不可承诺所有大库都受支持。全库/域/单件预算分开，不让多次扫描、双读 hash 或 history 跨文件重置累计预算。

## 6. 最终 proof、Writer admission 与发布顺序候选

1. 读源之前登记 root+card+doc+config generation 的生产 writer lease；禁止同一card的无交接突变。当前 `withDocumentPreparation` 不能改个名字就算完成。
2. 长网络在所有 exclusive 外；即使迟到成功也必须重新检查 lease/epoch/current head/完整源。取消不自动再收费、不保留可发布资格。
3. 申请维护时先阻止新冲突任务，并使已经读旧正文但尚未发布的任务退出或失效；等待这些任务的真实结构化排水。不能在持 normal 时等待 exclusive，也不能在 exclusive 里等待一个需要 normal 才能完成的任务。
4. 顶层 exclusive 内最终 proof→已批准 sealed intent/old→安装→commit marker及读回→head/history交接→同代cache/Flow发布。任何调用到 snapshot/cleanup 的 nested exclusive 需要先重构 ownership，不能运行时偷绕门禁。
5. operation Success 只有全部批准的持久交接与缓存发布均确认后可报告；磁盘已提交而cache失败，保留 CommitUncertain/committed-evidence，不回滚，不重新请求。应用重启用磁盘证据重新证明，不相信进程内Flow。
6. 维护退出与 TaskRuntime drain 保留真实I/O ownership。不可用 detached coroutine、无期限NonCancellable或超时返回伪造已排水。C05同根DI、C06coordinator与C07任务所有权必须统一。

以上为后续 Writer/Recovery 的门槛，不授权 G2-A 执行其中写入步骤。未能覆盖 matrix 任一路径时，相关 root/domain 保持拒绝，而非以“用户不会点它”推断安全。

## 7. Secrets-free 配置身份及 Writer 闭环

### 7.1 当前真实合同

C28 `EmbeddingConfig` 含 `id/displayName/baseUrl/apiKey/modelName/dimensions`，没有持久 revision/epoch。C19 `DesktopRagEmbeddingIdentity.from` 只计算 `SHA-256(trimEnd(baseUrl)+\"|\"+modelName)`，dimensions另验：这是官方检索兼容 key，**不是配置写入代际**。改displayName、dimensions、apiKey，删除再建同ID或restore都不必改变此key。

C18 正常运行期 snapshot 会严格反序列化完整 EmbeddingConfig并比较对象；这是已验收 S2A 的运行输入检查，**不允许移植为 preboot 无Secrets证明**。即使当前Desktop持久文件由C31清空apiKey，也不能假定所有旧/导入数据都无秘密，更不能为证明“为空”先读取完整文件。

| 实际入口 | 写入/影响 | 交接要求 |
| --- | --- | --- |
| C29 `saveEmbedding/deleteEmbedding` | legacy collection `embedding_configs`，可公开直接调用；policy+entity写/delete+cache | legacy storeKind+id epoch；delete tombstone；删除重建必须新generation |
| C29 `saveEmbeddingModel/deleteEmbeddingModel` | singleton `embedding_model_config/default`，强制id=default；credentials persist/delete及cache | singleton独立epoch，同一config writer admission，保留部分失败证据 |
| C29 `ensurePresetEmbeddingModel/restorePresetEmbeddingModel → upsertPresetEmbeddingModel` | 缺失时创建 / 显式覆盖；保留现有API key但改变其他字段 | 两种入口都纳入，不以presets“可信”跳过head交接 |
| C30 Desktop `restoreBundledModels` | 直接触发上述restore；普通chat模型设置不等于唯一embedding入口 | direct Controller校验，不能只控制界面selector |
| C32 Android `ModelConfigurationMigration.run` / C33 ManageViewModel | legacy→singleton迁移，再逐条删legacy；ensure/restore/save/delete，均可能改变配置来源 | Android/导入根不能伪造Desktop epoch；迁移/跨端adoption单独审批。不是本轮修改Android |
| C31 Desktop credential policy | hydrate拒绝plaintext再读SecretStore；persist先credentialTransaction再存apiKey空串实体；失败尝试恢复旧Secret，仍可不确定 | Key不入journal/hash/日志；即便public projection完全相同，认证变化也需不透明credential generation作废旧任务 |
| local snapshot restore、root migration/Portable复制、未来.cbbackup import | 整树/多Entity写可绕过ModelRepository；Android/旧文件可能含apiKey | whole-root/config authority接力；新root lineage及config epoch adoption；无adoption保持BLOCK |
| 原始JsonFileStorage/独立存储实例、外部程序或文件手改 | 不必经过ModelRepository或SecretStore policy | 不得声称已覆盖；只允许已证明的cooperative writer集合，来源未知/漂移拒绝 |

### 7.2 可审批的 nonsecret authority 方案

建议分离一个**新候选 nonsecret 配置 attestation**，只能由统一 config-writer 发布，字段包含 protocol/version、logical root lineage、storeKind、configId、单调 revision或不可重用generation、public projection（不含apiKey）身份、opaque credential generation、predecessor、writer capability版本及完成证据。精确存放路径/JSON/版本算法为 **DECISION REQUIRED**，不复用或改变现有 EmbeddingConfig schema。

- preboot只读取该明确无Secrets的闭集记录，不构造ModelRepository/SecretStore/DPAPI，不读取、反序列化或hash完整秘密配置；不以Key的SHA-256/HMAC作为“脱敏”标识。
- public projection只能用于模型/维度匹配，不承担“所有writer未改动”的证明。根内所有配置变更，包括凭据失败/回滚、preset、migration、restore，都必须先封闭writer admission，再以可恢复的transition接力attestation。
- 旧配置第一次adoption只能在单独获批的正常运行期经过安全凭据owner处理，再生成attestation；G2-A不能在preboot“补登记”。来源未知/缺attestation/只剩baseURL+model key：BLOCKED。
- 两文件或SecretStore+JSON+attestation并非原子事务；先写哪个、crash gap、credential-only更新、失败回滚仍推进还是作废epoch，都须独立合同。缺交接时不得让旧epoch继续证明当前配置。
- cooperative ownership不证明外部writer不存在；仅靠mtime/size/public projection无法识别同大小秘密变化。若要求在不读秘密的前提下证明任意外部修改，当前没有机制，**BLOCKED**。后续需明确受支持writer/threat model、可信配置存储分层或认证机制，不能伪造证明。

**当前结论：尚无完整 secrets-free config-writer 闭环。** G2-A可解释“缺配置证明/epoch不符”的拒绝原因，不能对真实 completed/pending 放行，也不能以“此配置不会再编辑”代替实现。

## 8. Backup、Restore、Migration 与 D-038

### 8.1 精确上游对照

下面路径均在官方 `app/app/src/main/java/com/example/chatbar/domain/backup/`，完整blob及固定链接见附录。

| 权威 | 1.4.5 实际行为 | 1.4.6 实际行为 / 差异 |
| --- | --- | --- |
| app-backup Skill | fixed v1 envelope/ZIP、registered roots、全量替换；prepared/rollback/restore.json；未知存储拒绝export；默认可含credentials | scope/export/merge及v2，同时读v1；selected projection、依赖、预览到确认期间maintenance；默认仍可含credentials；不是Desktop已批准能力 |
| BackupDataRegistry | 7–13 roots/preferences；25–51枚举filesDir直接子项，未知非忽略根拒绝，再遍历注册root | 55–92不再遍历filesDir siblings；**94–117 entityFiles仅枚举entitySerializers注册的singleton/collection**，119–130 isSelectedFile亦按注册集合；未注册事务sidecar不会自然进入export selection |
| BackupEntityValidator | 16 ignoreUnknownKeys=true；52–61遍历entities JSON并要求type已注册，strictUTF8；新事务JSON不自动支持 | 17–44：selection/exportSelectionOnly可仅验证选中Entity；默认incoming full tree仍遍历全部并拒绝未知type。不能把“导出不选中”理解为“导入认可未知事务” |
| BackupRestoreTransaction | 39–43 schedule PREPARED；46–110启动应用、每root移动前journal/安装后记录；112commit；121+可重入rollback；prepared固定 | journal30新stageDirectory默认prepared；39–43只接受prepared/incoming；50及106/126按该字段读取；203+cleanup两stage；旧journal默认值兼容，不是Desktop J journal |
| 1.4.6 AppBackupService | 不作为1.4.5合同依据 | 145–150按catalog重读plan并selection验证；233–262 scoped/incremental preview持maintenance、MergeEngine构造prepared并写seal；266–295按needsPreview选择prepared，否则incoming，持久pending时保留lease |

关键后果：1.4.5把未注册sidecar置于entities后，其JSON可能被validator拒绝；1.4.6的选择性导出可能根本不选中它。**两者都没有提供Desktop J的生产兼容交接**。1.4.6注册40类Entity不含document_index_transactions；不能仅因为官方工具能导出就声称保全了Desktop完整状态。

1.4.6 Skill记载范围选择、部分资源投影、模型依赖、合并身份与Memory proof等更多语义；这里仅作兼容影响观察，不实施或继承它们。具体合并算法/跨端可用性未在本轮全面验收。

### 8.2 分层合同与拒绝条件

| 操作 | 现状与保全 | 未来最小要求 / 当前分类 |
| --- | --- | --- |
| Desktop本地 safety snapshot | C25 recursive walk复制非reserved文件、SHA-256 manifest；C35仅排除backups子树与root lock。因此事务sidecar一般会被复制，不是按官方Entity白名单丢弃 | 字节完整不等于语义一致。先解决所有raw writer与pending admission；完整域/old/stage/head/receipt及依赖同一exclusive。active/uncertain默认BLOCK，不能以copy成功称事务安全 |
| Desktop恢复原根 | C21 exclusive→C25 staging/validation→移动旧root payload→安装→读回；成功/部分失败需restart，保留恢复workspace | snapshot内root/head/config lineage必须重新验证；不能先恢复半份域再启动writer；不得把根lock当payload。G2-B/独立restore合同之前BLOCK |
| Data-root migration | C23 pause backup→exclusive→snapshot→C24复制/hash→bootstrap authority→restart；source保留、destination ownership独立 | 不复制运行lock；完整域连同证据保留，但新physicalroot需adoption。pending/uncertain或来源未交接REJECT，不自动清理 |
| Portable原根重开 | G1B同root ownership及两次G0，不复制/重写receipt | domain存在仍阻断；更换程序位置/复制UserData不是自动获得新root身份 |
| Android1.4.5 .cbbackup→Desktop | D-038选定兼容目标；Desktop正式full-app import/export未完成 | 保留vector/Memory/SaveSlot，不构造伪J receipt；未知运行checkpoint/secret范围不明拒绝。真实跨端迁移证据仍PENDING |
| Desktop→Android1.4.5 supported export | sidecar未注册，秘密与Windows路径另有安全约束 | **未经批准的分享投影继续REJECT**。不能删/忽略receipt后称全量；若未来批准projection，须明示范围、保全原根证据、证明导出的Entity与所需资源一致并由Android接收 |
| 观察到的Android1.4.6 scope/export/merge | 按注册Entity选择，部分字段投影与prepared/incoming切换 | 独立影响审查；不取代D-038、不扩大正式承诺，未选sidecar必须显式披露/拒绝，不能冒充完整备份 |

D-038（18_DECISIONS:470–482）保持：官方1.4.5目标、Android→Desktop与Desktop→Desktop及支持范围Desktop→Android实证、普通分享默认无Secrets、无受控凭据迁移设计不得静默带出Key。官方默认含credentials不构成Desktop授权。D-036/037/038/040状态不变。

## 9. G2-A 最小实施合同草案（等待独立批准）

### 9.1 明确范围

拟议独立 `DesktopDocumentIndexTransactionInspector.inspect(root, approvedBudget)`，要求调用者已经持有真实C04 ownership，输出只含固定分类与有界计数/缺失证明种类；**没有 businessOpen/READY true**。可区分 known draft schema的结构证据、pending/marker/head/history不一致、unknown/corrupt/unsafe/budget/config-authority缺失；不读取秘密、不生成任何域、不恢复、不删文件。

G2-A不调用Main、不修改StartupGate分流；真正生产域继续G1B全部阻断。即使合成fixture提供了完整attestation/head，测试也只证明只读识别能力，不代表真实生产writer闭环。待批准的schema字段未冻结时不能“先猜一个实现”。

建议允许文件闭集：

| 文件 | 可做内容 |
| --- | --- |
| 新 `app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopDocumentIndexTransactionInspector.kt` | 独立只读编排、ownership/路径/预算/前后身份、有限证据分类 |
| 新同目录 `DesktopDocumentIndexTransactionReadContract.kt`（必要时） | 待批版本的纯类型/closed parser/预算；不是注册Entity，不得带writer/cleanup接口 |
| 新 `app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/DesktopDocumentIndexTransactionInspectorTest.kt` | 合成正常/损坏/未知/缺失/多代/预算/零写入fixture |
| 新同测试目录 `DesktopDocumentIndexTransactionInspectorProcessTest.kt` | 自有子JVM、真实ownership、退出/重开/取消/进程证据 |
| 新同测试目录窄 `DesktopDocumentIndexTransactionSyntheticFixture.kt`（若避免测试重复确有必要） | 仅测试生成有界fixture，不迁入生产，不读取preset/seed/用户数据 |

直接复用C03 companion已公开为internal的 `readWindowsIdentity` 与 `safeWindowsName`，不另造第二套Windows文件身份实现。该helper的已有异常需fail-closed映射；不根据异常原文推断合法性。不修改C03私有Probe算法、budgets或结果语义。只有identity工具复用不足时先回报缺口，不自行改G0。

**禁止修改**：Main、DesktopStartupGate/BlockedPresenter/ProcessLifecycle、DesktopIndexStartupPreflight及其旧测试、DataRootOwnership、AppContainer、TaskRuntime、DataOperationCoordinator、JsonFileStorage、Repository、CharacterCard/VectorChunk/EmbeddingConfig、Reader/Preparation、F4 safety、Prompt、Android及Gradle依赖。G2-A无新增schema注册、persisted writer、head推进、真实root adoption、backup投影。若需上述变更，必须另发审批，不能夹带进识别器任务。

### 9.2 严格读取要求

- 只读NIO/open-existing；不构造Container、Repository、SecretStore、自动备份/预设。现有`visitEntitiesStrict`只复用其设计约束，不调用会mkdir/cache/recover的普通storage方法来探测事务。
- 严格UTF-8（malformed/unmappable拒绝）；JSON闭集、duplicate key拒绝、必填/enum/数字范围验证、有限深度/字符串/数组预算；路径/文件名与storageId逐项比对。不用默认值接受缺失权威字段。
- 域/祖先/每个文件读取前后核对Windows identity、属性、长度、containment及同卷；大小增长在有限readNBytes后拒绝，累计hash读取也计预算；禁reparse/junction/symlink/ADS/大小写冲突/短名别名/重复文件身份。G0 helper只提供部分检查，不冒称完整TOCTOU证明。
- 外部硬链接不能仅从本次树内无重复推出不存在；如所需single-link证明无法取得，明确UNSAFE/UNKNOWN或NOT TESTED，不开放。真实root replacement/ACL不足同样保留边界。
- 每次调用全新扫描，不缓存VERIFIED_SAFE；stream单文件，compact集合有上限；不把全部embedding或历史payload常驻内存。
- Cancellation传播，DirectoryStream/input/native handles在结构化finally关闭；真正阻塞文件I/O未结束不得通过deadline/子协程脱离伪装已释放root；没有物理绝对超时承诺。
- diagnostics只含固定码、合成计数与阶段，不含正文、原始异常、绝对路径、用户身份、secret、payload。识别器没有retry/收费请求/清理副作用。

### 9.3 验收矩阵与测试计划（本轮全部 NOT RUN）

| 拟议测试组 | 必须证明 |
| --- | --- |
| authority / no-domain / lifecycle | 无owner拒绝；真实owner方可inspect；无域有限事实；释放后下次独立进程能重获；不构造业务服务、不制造domain或lock以外写入（lock只由夹具显式ownership获取） |
| known structure / evidence | 完整合成sealed-old、pending、有效marker但缺head、head缺configwriter、live不符，输出准确且全部business closed；completed无current proof仍BLOCK |
| 多代与第二笔 | history≠current、正常predecessor链、两doc、rename transition、合法supersession的合成读取；重复receipt、同revision分叉、循环/回退、多个active/来源不明拒绝，不强行永久匹配初代card |
| closed schema / budgets | malformedUTF8、未知字段/enum、重复key、truncatedJSON/JSONL、ID与filename冲突、额外旁文件、缺seal/old、deep/多entry/超size、checked溢出、累计多轮扫描预算，拒绝前不构造巨量字符串 |
| filesystem / Windows | 只在合成根验证FILE_ID_INFO、junction/reparse/硬链接/大小写/ADS/链接/身份漂移、读后替换seam；真实不可执行分支单列SKIPPED/NOT TESTED，不继承历史绿色 |
| zero-write / cancellation | 前后完整文件与目录集合、大小/hash一致；取消/读错/ACL拒绝/句柄close错误均保全；挂起点用CompletableDeferred/注入gate，不用sleep猜竞态；不自动删除坏fixture |
| fresh JVM | 已知域/未知域/损坏/多代各新进程inspect后退出；只操作确切自有进程。watchdog超时记录UNKNOWN/BLOCKED，不强杀伪排水，记录实际退出与锁重获 |
| existing regression | 必要Desktop main/test编译；原G0、ownership、Portable、Application/ProcessLifecycle、StartupGate聚焦测试不改断言；不以旧XML替代新执行，保留全部RED |
| secrets / compatibility | 不打开embedding秘密文件、SecretStore或触发journal recovery；config只凭批准nonsecret记录，缺writeradoption拒绝；所有fixture inline/临时根，无真实API |

资源仍沿既有单Worker、offline、低优先级、Kotlin in-process、2GB heap；低空闲内存本身不自动STOP。测试/编译执行权限须由正式G2-A任务给出，本S0不运行。新schema/serializer不触及sharedCore时不声称重编Android；若未来必须影响共享模块，先扩大审批再定受影响验证。

独立 **G2-B** 才讨论恢复写入、恢复重入、crash中断安装、marker/head交接与是否可开放业务；G2-C/生产Writer也不能由G2-A绿色自动授权。

## 10. 待审批清单与当前裁定

| ID | 项目 | 裁定 / 关闭条件 |
| --- | --- | --- |
| S0-A | G0/G1A/G1B限定事实、现行Main域存在必阻断 | ACCEPT 保持；不修改既有实现或历史证据 |
| S0-B | J作为首选；独立只读G2-A而非恢复/Writer | ACCEPT 候选方向；PROJECT REVIEW PENDING |
| S0-C | J-v1字段/路径/canonical seal、marker→head→history每个crash gap | DECISION REQUIRED；未冻结禁止生产创建 |
| S0-D | persistent root lineage、owner capability及restore/migration adoption | BLOCKED；C04物理锁不能替代，需批准持久交接 |
| S0-E | 所有card/document/resource/config writer lifecycle与reader/cache epoch | BLOCKED；矩阵覆盖和测试闭环后才可publish |
| S0-F | secrets-free config writer/credential epoch及旧数据adoption | BLOCKED；public keyhash不够，不能preboot读完整config |
| S0-G | 单独预算与locator dispatch；G0→G2入口改动 | DECISION REQUIRED；G2-A独立不接Main，G0任何错误仍拒绝 |
| S0-H | 多代/second doc/rename/remove/import/preset transitions | BLOCKED；只读可识别缺证据，不允许按实验固定集合产品化 |
| S0-I | full backup域注册/完整保全、restore lineage及D-038跨端支持 | BLOCKED；1.4.6只选注册Entity不等于支持sidecar |
| S0-J | 未批准的分享投影、跳过未知字段/坏数据、清理receipt造绿、自动Embedding | REJECT |
| S0-K | history payload保全/限额/GC、disk durability顺序、Windows完整TOCTOU/外部硬链接/ACL | DECISION REQUIRED；证据不足路径BLOCKED，GC/物理保证不在G2-A |
| S0-L | 一份receipt存在即COMMITTED/READY；整个root安全、完整P9/Public Preview提升 | REJECT |
| S0-M | 独立纯识别器复用C03 internal Windows helper的文件范围与测试矩阵 | DECISION REQUIRED：Project冻结9节及只读schema后另发G2-A任务 |

历史132的1项JUnit symlink SKIPPED与四次fixture early-return NOT TESTED继续区分；132保留的BV-B4退出码UNKNOWN、180/600秒观察超时及人工签署也不因本文改变。

**Tests / builds / GUI / packaging executed in S0: NONE。** 不新增测试PASS声明，不复用旧G0/G1B绿色冒充G2证据。D-036/D-037/D-038/D-040、正式baseline、Feature Parity、F4、P8 Fish、P10/P11和Public Preview状态不变。

## 附录 A. Exact-source ledger

以下表格由冻结HEAD的Git对象获取blob，行号按对应LF源码文本；不是文件系统原始CRLF SHA-256，也不含真实用户文件。D=`app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/`；S=`app/sharedCore/src/main/kotlin/com/example/chatbar/`；A=`app/app/src/main/java/com/example/chatbar/`。每个C编号对应正文引用的唯一源码文件。

| ID | Exact path | 行号 / 函数锚点 | Git blob（冻结 feature HEAD） |
| --- | --- | --- | --- |
| C01 | `D/Main.kt` | 24–43,139–结束；runDesktopMain / ownership lifetime | `bdd1b68d95df64b6044ab1799ba59ed453db5311` |
| C02 | `D/DesktopStartupGate.kt` | 30–123；run / requireNoDomain | `20c609bb0ac116b556165b057cab5a46c8246cbd` |
| C03 | `D/DesktopIndexStartupPreflight.kt` | 22–58,70–231；预算 / Probe / Windows identity | `03febf66c0022a22aec41a30b3d903e4d90a634c` |
| C04 | `D/DesktopDataRootOwnership.kt` | 68–148；isHeld / acquire / close | `b732c26ecdcb3125f323dc85d4e62a8df2e1c1e8` |
| C05 | `D/DesktopAppContainer.kt` | 34–49,97–172,193–219,281–288,388–428；同根DI / bootstrap / close | `dc8e89d18d5370bd496b43e8ceba67d26cf87cd2` |
| C06 | `D/DesktopDataOperationCoordinator.kt` | 67–132,174–250；normal / exclusive / drain | `13724cea96b6c484136c891610c5ebfa8811b8a0` |
| C07 | `D/DesktopTaskRuntime.kt` | 86–110,417–435；document preparation / closeAndDrain | `1d886b6a1547f73dee5c77787d528b6253ee5fc6` |
| C08 | `D/DesktopCharacterEditorController.kt` | 348–419,468–594,638–693,713–732,799–805；Document edit / save / cleanup | `67fb33112be5097c673e5a40314dd54d1e107a44` |
| C09 | `D/DesktopCharacterDraftResources.kt` | 23–89,107–160；stage / materialize / cleanup | `d56f4ca28dbe4401ffdfecda0fcb5f189fda2844` |
| C10 | `D/DesktopCharacterResourceStore.kt` | 79–177；owned document create / delete | `a099aadf0eccb574876bfb2ada5a1859521c1425` |
| C11 | `S/data/repository/CharacterRepository.kt` | 30–89；save / readDurable / transfer / update | `6973b2dde2db9af07e88ab48a5c6b0e0f7606915` |
| C12 | `S/domain/card/CharacterCardTransferCore.kt` | 121–266,436–542,587–614；transfer / overwrite / deletion | `e75c979f5aa13b3b537f6e1da2e864ee0239f270` |
| C13 | `D/DesktopManagementController.kt` | 96–115,146–198,211–242；direct management entry points | `5d1c96f0b7bbe8b60f400ad9946a241982e62d61` |
| C14 | `D/DesktopCharacterDocumentRagCleanup.kt` | 13–19；DOCUMENT cleanup | `a2997134fe85b8ac15fc56fea108039aa86b5baa` |
| C15 | `D/DesktopOwnedImageCleanup.kt` | 16–54；exclusive resource reference cleanup | `26abf3acb48887a836ca55ae59ea11d10ed59b8c` |
| C16 | `D/DesktopPresetBootstrap.kt` | 50–95,130–216；initialize / bind / repair docs | `ca2f54ea6c9c3cc61c448119acf84e63af2d833a` |
| C17 | `D/DesktopPresetSuiteRestoreService.kt` | 60–198,200–345；restore / durable confirmation | `f3a591e171ad057e0836996124f2a400fb373ad9` |
| C18 | `D/DesktopDocumentRagIndexPreparation.kt` | 62–178；prepare / snapshot | `77056884ba1b718f233d3bb022a9c0b718d7ea71` |
| C19 | `D/DesktopDocumentRagReader.kt` | 17–21,54–140；embedding key / retrieve / inspect | `bab47f7276074239e9b0a9cafae8595c6dac2d42` |
| C20 | `D/DesktopDocumentRagSource.kt` | 14–122；source and plan budget | `0666805738cc3a7c811e6274d5d13ad2a2b05b77` |
| C21 | `D/DesktopCoordinatedSnapshotService.kt` | 35–78；snapshot and restore gates | `d0ae62396eccb97e6d58c642793a34bec7a7c991` |
| C22 | `D/DesktopAutomaticBackupRuntime.kt` | 105–130,188–215,268–312；scheduler lifecycle | `a8d7ac29609ea236647ade1e3f44aeb7a6f73a67` |
| C23 | `D/DesktopDataRootMigrationService.kt` | 91–131,155–218,259–282；migration ownership and authority | `3349f75dac9df4767913f46e1a0341c8c0ca5af6` |
| C24 | `D/DesktopDataRootMigrationMaterializer.kt` | 283–371；copyActivePayload | `5517906e6ad126926b8f470a76663a3a2b7e05e1` |
| C25 | `S/data/snapshot/AppDataSnapshotService.kt` | 122–127,382–460,524–561；snapshot scope / restore | `454bc1ab4556c26f8e41e80e1a4892cab3c18bc4` |
| C26 | `D/DesktopFormatRepairDerivedSafety.kt` | 20–108,178–205,248–结束；check / submit / strict namespace | `a43dc0f531ce654f8fc2b58f60782a4f6369f029` |
| C27 | `S/data/local/JsonFileStorage.kt` | 42–60,98–223,257–321,609–667,679–697,783–803；gate / raw writes / scan / cache | `494c2067012ece17e04b3e36d5788276b0b9f833` |
| C28 | `S/data/local/entity/ModelConfig.kt` | 97–119；EmbeddingConfig | `d77bd4bda03e816bf2fe21df51e6adae8b4cd5d4` |
| C29 | `S/data/repository/ModelRepository.kt` | 49–109,181–193,245–276,293–353；embedding write paths | `7344d2385aaee21e43226af3a65f128802d7ff01` |
| C30 | `D/DesktopModelSettingsController.kt` | 155–174；restoreBundledModels | `85fbaafb20571a4a15079eb8b33439dcaa8924cf` |
| C31 | `D/security/DesktopRepositoryCredentialPolicies.kt` | 107–190；embedding credentials / transaction | `ad2323a1997ba835e6b3f215fb51700566f12a33` |
| C32 | `A/domain/model/ModelConfigurationMigration.kt` | 16–94；migration / preset adoption | `c65254e0f2213a7fa4450c09517009e986c94592` |
| C33 | `A/ui/manage/ManageViewModel.kt` | 532–545,599–610；Android embedding editor | `101166d6a00fef2b1e437c11c55184ad1b2395ac` |
| C34 | `D/DesktopWorldBookEditorController.kt` | 68–85,309–327；confirmClearSourceCharacters | `570e48db5d97fd37d2d10847b2eb6a437a8e75b1` |
| C35 | `S/data/root/AppDataRootInfrastructure.kt` | 5–25；reserved paths | `e7a75617803f144faee973f4c5ce0f837d601f9a` |
| C36 | `S/data/local/entity/CharacterCard.kt` | 17–51,134–155；card / DocumentInfo | `1c30a23cd165ac8ae7aa8c240d8f447ee7eaab3c` |
| C37 | `S/data/local/entity/VectorChunk.kt` | 10–52；vector source / storage contract | `24cc66091fb03a41e19831e357bdc5c3aea67ec1` |

### Authority document identity ledger

| docs/desktop 文件 | 相关范围 | Git blob |
| --- | --- | --- |
| `00_PROJECT_SOURCE_MAP.md` | CURRENT入口 | `0a80af24de7f6eef040930db9bc1775fb4fdd4a9` |
| `10_UPSTREAM_BASELINE.json` | 合同/限定验收全文 | `ea8d53aac709179c33b78bb6b28c2fd58c1c904b` |
| `21_CURRENT_STATE.md` | 当前阶段顶部 | `4436cfe3207d6b1a38e62bfac0a087b7f0ae9685` |
| `13_FEATURE_PARITY.md` | parity / 兼容边界 | `6c74428c68d74298412fe29112706118cf775b72` |
| `14_UPSTREAM_COMPAT.md` | parity / 兼容边界 | `85483574cc3afd2f5a128414daddeabb63aac7b3` |
| `120_P9_S2_INDEX_WRITE_SOURCE_AUDIT.md` | 合同/限定验收全文 | `364f53865b93dcacbe366ffabfea758cd398e971` |
| `125_P9_S2B_TRANSACTION_DESIGN.md` | 合同/限定验收全文 | `392d9c6060bd68b9b3cd0845b84ac0fdbfc820c0` |
| `126_P9_S2B_PROJECT_EXPERIMENT_ACCEPTANCE.md` | 合同/限定验收全文 | `766cb5a1de7107a07a266a9070b87c47b138108e` |
| `127_P9_S2B_PRODUCTION_INTEGRATION_CONTRACT.md` | 合同/限定验收全文 | `aa53d29e6537ebf8851ef987af5aa08d8f1e0c7c` |
| `128_P9_S2B_STARTUP_RECOVERY_GATE_DESIGN.md` | 合同/限定验收全文 | `c7734ba53d4a5a9df5046bbeeb2a249a14bfa1e8` |
| `130_P9_S2B_G0_PROJECT_ACCEPTANCE.md` | 合同/限定验收全文 | `667b3e49dfb359049bc06eb0c536de74a5db535d` |
| `131_P9_S2B_G1A_PROJECT_ACCEPTANCE.md` | 合同/限定验收全文 | `44b8232b832cac4d03429900397f3b0957bcf7df` |
| `132_P9_S2B_G1B_PROJECT_ACCEPTANCE.md` | 合同/限定验收全文 | `06a47e9d8df74fa143a2a92d18d461c0654f84fe` |
| `18_DECISIONS.md` | D-038:470–482 | `9565612f67d91eb2a32fc411c385dcfd8af378fc` |

### Official exact-SHA comparison ledger

正式1.4.1 Skill通过本地Git对象读取：

| 路径 | 行号 | Git blob |
| --- | --- | --- |
| `.agents/skills/chatbar-feature-map/SKILL.md` | 1–62，尤其41、52 | `d9e39a1227e05a76b853290c39bd1410e0904f7e` |
| `.agents/skills/chatbar-long-term-memory/SKILL.md` | 1–79，尤其20、54、68–70 | `467fafa1db574d99ab5f677411e353a3743928a0` |

1.4.5/1.4.6通过只读GitHub文件接口获取完整文件及其Git blob，无ref移动。除Skill外下列短路径统一为 `app/app/src/main/java/com/example/chatbar/domain/backup/`；固定源码链接可直接定位，不引用浮动master。

| 版本 / exact commit | 文件及固定链接 | 核对行号 | Git blob |
| --- | --- | --- | --- |
| 1.4.5 `1198a13cf3008de1358b8a69aa5e40b64797c8d9` | [SKILL.md](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/.agents/skills/chatbar-app-backup/SKILL.md) | 1–37 | `dfaeff183ae30ac2e9c54e77b9fef6bd8ab835d8` |
| 1.4.5 `1198a13cf3008de1358b8a69aa5e40b64797c8d9` | [BackupDataRegistry.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/BackupDataRegistry.kt) | 7–64 | `cacf48ea9fe10cf407b34122080258f1cfbda98e` |
| 1.4.5 `1198a13cf3008de1358b8a69aa5e40b64797c8d9` | [BackupEntityValidator.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/BackupEntityValidator.kt) | 16–61 | `a5ad030837411ccaf43ad3b5b486fb10d3dc220b` |
| 1.4.5 `1198a13cf3008de1358b8a69aa5e40b64797c8d9` | [BackupRestoreTransaction.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/BackupRestoreTransaction.kt) | 21–43,46–135,175–217 | `9312e75b4b08df7762eb0ad8fdf6d989316dcebb` |
| 1.4.6 `9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d` | [SKILL.md](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/.agents/skills/chatbar-app-backup/SKILL.md) | Owners / Data semantics全文 | `4fa4d5e8e6a73d53a026476305d65f2ae35b67f5` |
| 1.4.6 `9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d` | [BackupDataRegistry.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/backup/BackupDataRegistry.kt) | 18–44,55–151 | `5f6e68c0909d5bed7a730050300d6b81a67f44a2` |
| 1.4.6 `9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d` | [BackupEntityValidator.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/backup/BackupEntityValidator.kt) | 14–44 | `3f6040786df00ec4695ce5f8f1cd8567f842b1ca` |
| 1.4.6 `9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d` | [BackupRestoreTransaction.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/backup/BackupRestoreTransaction.kt) | 30–50,106–126,181–223 | `25af28339d688d4e18c4a728c171c2502f75b6e1` |
| 1.4.6 `9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d` | [AppBackupService.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupService.kt) | 145–150,233–295 | `f69e8de8bc6c6446fd43293f7c09f26906cf7649` |

本地read-only工具有一次PowerShell花括号路径展开失败、若干定向路径查找未命中，以及sandbox内GitHub连接失败；后续以实际路径和获准只读网络完成，不涉及构建或测试RED，不删除历史证据。133仅为源码审计及候选合同，不能把这些静态检查当工程验证。

**G2-S0 CONTRACT CANDIDATE / PROJECT REVIEW PENDING**
