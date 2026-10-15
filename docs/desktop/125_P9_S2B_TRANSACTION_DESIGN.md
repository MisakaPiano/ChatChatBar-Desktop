# P9-S2B Transaction Design Candidate

日期：2026-10-16。**DESIGN CANDIDATE / PROJECT REVIEW PENDING；PRODUCTION IMPLEMENTATION BLOCKED。** 本轮只选择隔离实验候选并实现test-only原型，不新增生产schema、journal类型、启动恢复或索引替换。正式baseline1.4.1与D-036不变。前置authority：120写入审计、124准备层验收、118 B5B与当前真实源码。

## J versus G

| | J: journal/staging/state-last | G: immutable generations/pointer |
| --- | --- | --- |
| Layout | 保留flat vector_chunks和官方Entity，旁路独立意图/备份/receipt | 不可变generation加validated pointer |
| Commit identity | journal hash+transaction ID+新card hash+新vector集合hash，不以INDEXED布尔状态判断 | pointer绑定generation/hash，与card状态仍须协调 |
| Reading | 逐文件安装时不得开放任何业务读取，重启先恢复 | 所有Reader必须经pointer，旧flat reader不能继续直读 |
| Failure cost | 必须验证完整旧备份、处理中断rollback/cleanup并同步缓存 | 需迁移/双读边界、孤儿generation保全与回收 |
| Compatibility | 官方存储形状侵入较小；新增事务域仍须备份/恢复/Android审计 | Reader/storage大范围变化，Android/.cbbackup/旧数据迁移均未审 |
| Evidence today | 现有replace/rollback不提供crash-safe证明 | 当前没有generation-aware Reader或生产pointer协议 |

**选择J为本轮测试候选，不等于设计已验证或允许生产发布。** 它可用严格停止开放换取保全；若证据不全则COMMIT_UNCERTAIN，绝不猜测回滚。G保留备选，当前缺少采用它所需的跨端/读取合同。两者的断电保证均UNKNOWN；本轮只允许独立进程中断，不允许宣称power-loss安全。若原型无法恢复/保全，相关场景BLOCKED，不能扩大生产范围获取绿色。

## Frozen candidate contract

1. **Source snapshot.** 完整card/document身份与hash、owned path/真实正文hash、persisted EmbeddingConfig身份及raw文件hash绑定。复用DesktopDocumentRagSource和共享ChunkingEngine。生成不占exclusive，最终在同一root顶层exclusive内重读全部来源与旧集合；变动不安装，保留可诊断stage。禁止模型恢复重试。
2. **Old set proof.** visitEntitiesStrict逐文件验证storageId/Entity ID、DOCUMENT+cardId+originalDocId、已知metadata/有限向量、完整旧目标集合hash与其他记录hash。手动、孤儿、未知metadata或损坏JSON拒绝，不通过宽松query删除。其他明确角色/CHAT_MEMORY只保全，不借本轮重建。
3. **New staging.** 新vector按现有Entity完整序列化、强制刷新文件、逐文件hash校验；ID必须与旧/其他集合不冲突。旧card与所有旧target bytes先完整复制为备份。旧数据在标记证实提交前始终有可校验副本，不提前删除唯一旧索引。
4. **Journal authority.** immutable intent只含协议/事务ID、资源身份、相对owned引用和hash清单，不含正文/Prompt/API key。独立intent seal检测损坏；提交标记绑定同一intent hash/txId/new-card/new-set。测试协议不是生产Entity schema。Hash是完整性证据，不声称对恶意同时改写intent和seal提供认证。
5. **Install.** sealed intent后才逐项移动旧live vectors到moved（完整backup仍保留）、安装新vectors；全部新集合验证后替换card。两次INDEXED也必须通过完整card bytes/hash、txId与新集合区分。未完成阶段只能处于closed read gate，不把混合集合开放为有效索引。
6. **Commit point.** 新card/集合与其他记录核对后，原子替换写入commit marker。错误/取消的即时结果仍需保留attempted stage/cause，不能由异常文字断定回滚。恢复读到可信marker且完整新状态匹配才COMMITTED；无marker、intent/完整旧副本可信且当前文件仅属已知old/new域时可恢复旧态为NOT_COMMITTED；其余COMMIT_UNCERTAIN，保留证据且不开放。
7. **Idempotent recovery.** 所有破坏性恢复动作前先完整验证intent、来源、current old/new允许域、staged新hash和所需old备份。无marker删除的只能是hash精确匹配本事务的新ID，再幂等安装旧副本/card；有marker只验证新状态并清理经过hash确认的旧备份。恢复自身可再次崩溃；receipt完成后原子移动至completed域，二次恢复不重复改写payload。未知条目、损坏hash、来源矛盾均拒绝，不自动清理。
8. **Admission.** 生产实现必须使用同一ownership/coordinator/TaskRuntime的专用writer任务、旧来源持有者登记和关闭drain。当前P9-S2A只是准备任务，不能冒充writer准入。原型只在独立合成root的结构化测试调用/自己启动的子JVM中运行，不能证明生产任务准入已接入。
9. **Startup gate.** Main.kt:29–40先resolve root，136–175 acquisition保护applicationBody；应在ownership成功后、构造业务AppContainer之前设置最小恢复gate，且早于initializePersistentState和任何Reader/Writer。当前Main未改；原型child模拟该顺序，不能替代真实应用启动验收。未确认恢复时禁止bootstrap、自动备份、模型请求和业务开放。
10. **Cache barrier.** JsonFileStorage单文件写会更新cache flow，CharacterRepository另有列表缓存；exclusive只挡合作IO，不会自动冻结Flow订阅。正式方案需暂停业务视图/读取并避免pre-marker新cache发布。原型用test-only原子文件安装，验证后严格检查，再显式loadAll刷新vector cache与CharacterRepository.refreshFromStorage；新进程使用全新storage/repository。不能把宽松loadAll当安全证明（它会跳过坏JSON），只可在同一exclusive的strict proof后重建缓存。
11. **Backup/Portable/migration.** AppDataSnapshotService.copySourceFiles:524起递归复制非AppDataRootInfrastructure保留路径；仅backups/与root lock获保留，不存在任意事务缓存豁免。正式pending/completed域必须纳入一致快照和D-038数据域注册/恢复策略，迁移前暂停任务并先消解pending；未能证明时拒绝备份/恢复/迁移，不漏掉日志或单独恢复半份vector/card。Portable只改变根解析，不消除事务证据。Android旧版未知域的保留和读取行为必须审查。本轮不注册生产域，均为后续blocker。
12. **F4/P10.** 生产writer活动及未知journal必须阻止B5B VERIFIED_SAFE，不能只靠短期IO exclusive。原型仅在合成root的entities/prototype_index_transactions测试域保存证据，当前B5B未知namespace规则应拒绝；这不是正式生产域或白名单。真实任务活动到持久receipt之间、completed域生命周期及后续Memory所有权仍须独立集成。P10不归本事务删除/重建。
13. **Durability and failures.** 文件force(true)+同卷ATOMIC_MOVE为实验策略；不支持时拒绝，不静默fallback。Windows directory fsync/掉电顺序、磁盘损坏、硬件缓存与真实launcher启动未经证明，保持UNKNOWN。COMMIT_UNCERTAIN不自动retry/rollback或删除异常证据。未确认真实IO完成时不提前释放ownership。
14. **Memory is not a template.** MemoryRepository.commitStateLast:53–78先journal再apply；applyJournal:94起写nodes/revisions/transactions/state后删除旧项，getState:43会先recoverJournals。它的revision/state pointer专用，不能复制其宽松query、revision不匹配后的删除或状态末写逻辑来证明card/vector通用事务。

## Test-only protocol and bounded experiment

原型只接受createTempDirectory产生的p9-s2b-synthetic-*根及固定synthetic marker；全部输入为合成文档/Entity，限小集合（<=64vector records/小JSON与journal），不模拟任意生产规模。使用真实JsonFileStorage严格读取、CharacterCard/VectorChunk serializer、root ownership和同一coordinator。原型不调用Embedding，仅用确定性假向量；新card只改变索引元数据/正常更新时间，不清空资源。

Crash cuts覆盖stage前后、intent前后/seal缺口、移动首个old、安装部分/全部new、card前后、marker前后、old备份cleanup及恢复中断。每次记录即时异常与恢复后的明确结果，不把阶段数冒充JUnit测试数。无journal但有孤立stage时无法证明身份，必须UNCERTAIN保留；stage前无任何事务副作用可NOT_COMMITTED。

关键cut以单个-Xmx96m子JVM实际操作，stdout只报告阶段/PID/结果；parent只终止自己保有Process对象且身份仍匹配的子进程，再启动新的recovery进程验证old/new/blocked与幂等。进程中断不是断电。若内存/退出门禁不允许，crash gate标BLOCKED/NOT RUN，不伪造PASS。故障注入/取消也使用实际临时文件与可控挂起点，不以Mock文件表代替磁盘。

## Production blockers after this experiment

正式schema/事务域与D-038注册、业务Reader/Flow暂停与启动gate、专用TaskRuntime writer准入、跨Platform兼容、completed receipt保留/清理政策、真实Main/Windows shutdown、磁盘满/权限与电源故障保证仍未批准或未证明。J仅为DESIGN CANDIDATE；本轮即便合成process实验全绿，也不等于生产P9-S2B、完整P9/P11或Public Preview验收。
