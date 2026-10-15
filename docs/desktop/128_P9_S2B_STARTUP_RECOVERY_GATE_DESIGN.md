# P9-S2B Minimal Startup Recovery Gate

日期：2026-10-16。**STARTUP GATE DESIGN CANDIDATE / NOT IMPLEMENTED。** 与127生产合同一并交Project复审；126只接受隔离实验。无Main、schema、数据根、业务运行或UI实现授权。

## C1. Exact placement and ownership

当前调用链（D=`app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/`）：

1. D/Main.kt:29 `runDesktopMain` 调用 `DesktopDataDirectory.resolveRoot()`；该阶段解析CLI/environment/bootstrap/Portable根，不是事务恢复。
2. Main.kt:35进入 `runDesktopApplicationWithDataRootOwnership`；136–175的wrapper执行 `DesktopDataRootOwnership.acquire()`，未取得锁不会调用applicationBody。
3. 当前applicationBody内38行构造 `DesktopAppContainer`，其constructor已组装storage、snapshot、settings／backup等对象，不能把constructor视为已通过恢复检查的纯占位。
4. Main.kt:39调用 `runDesktopApplicationLifecycle`；其200行附近先 `runBlocking { initialize() }`，才进入Compose application的try/finally。
5. D/DesktopAppContainer.kt:170–172 `initializePersistentState()`先初始化automaticBackupRuntime，再presetBootstrap；之后主业务UI、controller load和查询才运行。

**候选插入点：ownership成功后、进入现有applicationBody时，早于DialogOwner/pickers和业务AppContainer构造。** 不能把恢复放在initializePersistentState末尾或已有Reader首次查询时。恢复未可信前禁止bootstrap、preset修复、自动备份、聊天初始化、索引读取、外部import路由和任何Provider请求。

候选组件（均未实现）：`DesktopIndexStartupPreflight`只持root Path／ownership证明及有界文件读能力；`DesktopIndexStartupRecovery`是显式写入阶段；`StartupRecoverySession`拥有结构化Job、同一coordinator和root lifetime。preflight本身不需要业务Repository、SecretStore、SettingsRepository、TaskRuntime或AppContainer。

未来若恢复需要exclusive，应先创建纯内存coordinator并在无normal的顶层使用，恢复成功后将**同一实例和已验证readiness**交给AppContainer；当前constructor自行创建coordinator（AppContainer.kt:42–43），故这项依赖注入需要后续明确授权，不复制第二个并发运行的root协调器。不得调用业务TaskRuntime来启动preboot恢复，避免先构造其业务依赖。

生命周期：acquire root → 只读分类 → 如授权则受控恢复／重新分类 → 确认business-ready → 构造container → 初始化 → UI → 既有安全drain → 释放ownership。取消／失败也必须先join真实恢复IO，不能让detached coroutine继续持写盘引用。无法证明IO退出时进入既有unsafe-shutdown ownership保留路径；未来异常类型必须被Main.kt:189–193的retention识别或先完成drain，不能随便抛一个新异常使wrapper提前释放锁。

另须验证初始化失败时的清理：当前lifecycle的initialize位于application try/finally之前，不能假定未来preboot失败会自动触发appContainer.close。本设计让未构造container的StartupRecoverySession自行收尾；业务初始化失败的已有边界不得被新恢复Job绕过。真实阻塞IO无物理绝对取消／超时保证，不能通过超时返回冒充已经释放。

## C2. Evidence-based startup classification

域名、协议、head／交接及budgets沿用127候选，尚未注册。先分类再决定是否可能恢复；分类不是写入许可。

| State | Required evidence | Allowed / refused |
| --- | --- | --- |
| NO_DOMAIN | root／祖先安全且ownership有效；候选域确实不存在，非权限／路径错误 | preflight零副作用地开放原有初始化；不创建空域或伪receipt。业务初始化自身行为另记，不伪称整个App永不写入 |
| COMPLETED_APPLICABLE | 已知版本、sealed marker/receipt、current head与合法writer交接可追溯；当前适用scope live严格一致 | 可开放；retired history只检完整性／归属，不将过去card hash永久套在合法新card上。当前head或交接未知则BLOCKED |
| SEALED_PENDING | 完整intent/seal，stage与必要old副本、current允许域及来源全部证明 | 不开放业务；进入另行批准的显式恢复。无marker可证明旧态时NOT_COMMITTED；可信marker与完整new一致才COMMITTED |
| UNSEALED_STAGING | 有stage／tmp但无可信intent或seal | COMMIT_UNCERTAIN，保全，不猜测owner、不删目录、不默认“未写入” |
| DAMAGED_EVIDENCE | intent／old backup／marker／current payload缺失或hash不匹配 | COMMIT_UNCERTAIN；已完成事务不自动回滚；不改坏文件或原receipt |
| UNKNOWN_PROTOCOL | 未知schema、版本、domain条目、writer、多个无序active或无法解释的head链 | BLOCKED；不ignoreUnknownKeys、不按历史completed数量猜测后代顺序 |
| READ_ERROR / UNSAFE | 权限／IO失败、无效UTF-8、超预算、link/reparse或身份漂移 | 保全并阻断；不能返回NO_DOMAIN或“存档不存在” |
| RECOVERY_INTERRUPTED | 发现合法已执行前缀，剩余步骤和幂等证据可证明 | 重启再分类，只执行已批准恢复；第二次恢复不得重复收费、覆盖新writer或静默丢证据；否则uncertain |

完整sealed不保证一定可恢复；只有来源／完整文件集／版本适用性均证实后才有写入资格。异常／unknown不能用“启动时清空索引”处理。配置或新writer交接尚未批准时，即使payload看似完整也不可硬编码business-ready。

## C3. Strict read-only preflight contract

- 不mkdir、写tmp、回放journal、bootstrap、更新Entity／缓存、创建历史收据或清理stage；只使用NIO的NOFOLLOW属性、DirectoryStream、受限InputStream、strict UTF-8和已批准协议parser。只有证实根仍存在且候选域确实缺失才NO_DOMAIN；扫描中途消失或拒绝访问属于错误。
- 不调用JsonFileStorage.loadEntity/loadAll/query/exists作存在性探针：S/data/local/JsonFileStorage.kt:114–127的entityDir可mkdir，328–373的宽松读还会把坏记录当null／跳过并改cache。`visitEntitiesStrict:261–324`提供有界严格思想，但只识别flat已知Entity且依赖storage operation gate，不是现成的通用preboot恢复API。
- 不调用MemoryRepository.getState（会recoverJournals），不构造会初始化Settings／preset／备份的业务服务。只读探针不得借“恢复检查”执行任何数据迁移。
- 不打开SecretStore、DPAPI、credentials或解密数据；不读取／记录API Key、Token或模型请求。EmbeddingConfig的apiKey与非秘密字段同文件：**禁止照搬合成原型的完整config读取／raw config hash作为preboot无Secrets证明**。需Project批准非秘密配置身份及writer交接方案；未解决时依赖它的pending／completed验证BLOCKED，而非略过配置校验。域不存在的旧数据不为此扫描用户配置。
- 候选预算与127一致：header64KiB／单JSON8MiB／depth64、ID256、一个active、受控stage/old/new集合、全扫描100,000项／4GiB及compact元数据64MiB。目录遍历计数、bytes、算术均checked Long，stream逐文件hash，不把整个embedding库保存在内存；总预算超限返回BUDGET/BLOCKED，不截断或抽样后宣称已验证。
- 对每个祖先、子目录和文件验证NOFOLLOW/reparse属性、真实句柄／文件身份、root containment和同卷要求；拒绝不安全硬链接／路径别名／ADS／case collision与未知旁文件。平台无法取得必要证明时UNSAFE/UNKNOWN；不得用普通Path.normalize代替Windows路径安全证据。探针不跟随指向其他盘或用户目录的链接。
- 启动检查结果仅对持有ownership且writer尚未开放的这一readiness generation有效，不作永久缓存。开放前如root／文件身份漂移重新分类，不能复用旧VERIFIED_SAFE。
- 时间预算只约束可取消挂起等待与逻辑工作；慢盘／真正阻塞文件IO不保证物理绝对超时。取消后等待实际IO退出再释放锁，持续阻塞进入可见阻断／安全关闭机制，不提前造假完成。

## C4. Minimal blocked/recovery UX — design only

独立最小界面不依赖业务container、主题／用户设置或聊天初始化。仅展示：分类码、发现的active数量及脱敏阶段、card/vector/source/交接哪些尚未验证、文件保持原样、当前禁止普通业务。COMMIT_UNCERTAIN明确表示“提交结果未确认，尚未自动重试或回滚”。不要把historical receipt损坏说成普通“无索引”。

安全快照必须区分“发现候选”和“已独立验证可恢复”。不能调用可能mkdir／prune的完整snapshot服务来填这行，也不能凭backups目录存在就显示安全。无无副作用验证证据时显示UNKNOWN；不自动恢复某个旧快照。

允许的未来动作：重新执行只读检查、保留脱敏诊断、正常退出；写入恢复按钮仅在另行批准的分类和显式确认下开放。诊断只含版本、相对逻辑scope代号、计数、hash／错误类别，排除真实聊天／Prompt／文档正文、API Key、完整路径及未脱敏异常文本。导出诊断不能复制raw journal/payload冒充安全日志；源证据保留原地。

退出必须正常停止预检／恢复Job并确认IO退出；不可关闭root锁后留下后台写入者，不使用强杀／删锁／重建空Entity来启动。任何恢复尝试失败保持同一root受保护；仅窗口关闭不代表进程和IO已结束。

## C5. Frozen implementation test gates — all NOT RUN this round

| Required future test | Evidence / pass condition |
| --- | --- |
| 无域旧Desktop根 | gate前后所有既有Entity／媒体／设置hash不变、无新目录／tmp；仅ownership锁的运行态另记；后续bootstrap合法写入独立记录，不能混成gate零写入 |
| 根缺失／权限错误／扫描途中删除 | 精确READ_ERROR/UNSAFE，不能误分类NO_DOMAIN；文件不被创建或覆盖 |
| sealed/unsealed/pending/marker/finish各组合 | 分类、允许写入阶段及禁止业务一致，未知／坏JSON不跳过，无自动Embedding |
| 完成态vector/card/source/config异常 | 对当前适用head重新证明，uncertain不回滚或清理；对需要Secrets的配置证明保持BLOCKED直到独立合同通过 |
| 多次同文档／第二文档／合法角色编辑／文档删替 | 真实writer交接、current head和retired history各有正确身份；不是永久拒绝第二笔或永久匹配最老receipt |
| 同根双实例／锁取得失败 | 第二实例不构造container、不执行初始化；自己的进程退出后真实锁重获，不能删除锁制造成功 |
| 启动恢复多点JVM异常／被杀 | 独立新进程重读真实文件，old/new/uncertain分类、幂等、完整hash保全；只终止自己持有并验证的Process |
| 恢复自身再次崩溃／取消 | 每个关键move/card/marker/head/receipt/cache边界可重启；不丢备份、不重复写收费请求 |
| 真实Reader／Repository cache／Flow/UI barrier | 合成观察者及挂起点证明无混合card/vector／旧epoch结果被视为可用；包括直接Repository访问，不能只测一个busy按钮 |
| backup/migration/Portable／D-038 | 自动备份和所有restore/switch入口不能越过gate；源目标域和receipt均保全，未知schema或不兼容导出拒绝 |
| F4-B5B及未来P10 | 活跃或未知事务不能VERIFIED_SAFE；writer／历史来源任务都被准入覆盖；DOCUMENT事务不修改Memory/CHAT_MEMORY |
| bounded inventory／恶意路径／未知schema | 数量、字节、深度、溢出、reparse/junction/alias不越界、不无界分配；所有失败文件保全 |
| initialization失败与shutdown | preboot/container各自所有权可证明；没有Job因initialize位于try外而泄漏；TaskRuntime/coordinator/真实IO未drain则root锁继续保留 |
| Windows launcher独立进程 | 合成根正常启动／阻断／退出及root重获；GUI证据独立于纯JVM PASS，不操作历史或用户进程 |
| 文件系统失败与实际断电 | 注入异常、进程终止、JVM crash、真实power-loss分别记录；目录fsync／Windows电源持久性未证实仍UNKNOWN |

所有测试只用合成隔离数据根与离线／localhost服务，先预算和身份门禁，再允许真实Windows自己启动的进程测试；无全量测试或packaging暗授权。

## Unresolved approvals / stop conditions

需要Project裁定：127的多writer transition／current-head协议与正式域注册；所有入口的lease和view epoch；preboot非秘密配置身份；可分享备份投影及跨端接收行为；pre-container同coordinator注入与unsafe退出分类。未裁定时，最小实现只可做严格只读分类／阻断，不能先生产发布再补恢复。

126接受实验；127/128只是前置候选合同，不替代新的实现授权。正式baseline仍1.4.1，D-036/D-038/P11边界不变；完整P9、P10、D-040及Public Preview状态不提升。**本轮编译／自动化测试／GUI／打包均NOT RUN；完成后STOP，等待Project独立审查。**
