## CI后续当前事实（2026-10-28）

最新已完成的 a41a0728 / run40581546331：Android/sharedCore/仪器编译/APK检查成功；云端Windows全组2114项、291失败、14跳过，不能继续记运行中或当作通过。已下载报告ZIP并核对服务端SHA256 `6ff606a2a7d826a90cb33de237a2f75d6efefce61393624943de4ec824b4fd57`。本机旧配置全组2114项中17失败/16跳过，均为启动输出污染或缺少子进程classpath；修正后所有失败所在7 suites共40项通过，配置缓存存储通过。云端额外失败大量在UNSAFE_PATH前置检查，另三项MCI277；当前修订在临时NTFS系统盘工作树/规范临时目录验证，并先上传原始/规范路径、文件系统及真实音频设备数。仅实际waveOut设备数为0的物理音频用例标NOT_TESTED；有设备继续全部原断言。未放宽生产路径保护或改动1207公开资产。新结果以本PR实际head检查为准。

run40576758285 对应 aa4928bb，已 completed/failure：`:desktopApp:test` 实际2114项、254失败、17跳过，运行平台Ubuntu。先前Android工厂门面编译与构建身份缓存两错未再出现；剩余失败包含Windows DPAPI/JNA原生句柄、winmm、java.exe、Chrome和Windows路径语义，不能一概称为Windows产品失败，也不能未补验就一概称无影响。8bc08731没有对应PR工作流运行，不把旧head结果冒充最新head。

[PR2](https://github.com/MisakaPiano/ChatChatBar-Desktop/pull/2)将Linux任务限定为完整Android测试/仪器编译/APK及sharedCore测试，并在Windows运行完整Desktop测试和NSIS合成helper。首轮8e263558 / run40579518673 的Windows job在checkout因历史证据长路径失败，尚未执行测试；当前修订在临时Windows runner启用Git长路径，并按PR取消旧版本重复运行。后续以实际head检查为准，不用本地16项代替完整云CI。

公开1207原资产和标签未改。公开下载现有证据是服务端digest、公开校验文本和HEAD大小，加本机原包安装全链；没有从公开下载重新执行整条安装全链。安装目录改名/移动后继续更新、现代全备Android完整恢复/物理设备往返、完整断电恢复/历史大快照、跨Windows账户及全部媒体/凭据语义仍未测或按既有证据限定。用户最短验收不提升为全部业务验收。

## 最新交付截点：1207 已正式安装，限定人工验收通过

2026-10-28 JST：普通 NSIS `0.1.0-preview.1207` / Windows `0.1.1207`，构建源码 `bd385a995fbb506e16607dc92403755951a591a4`，155,270,013 B，SHA256 `7bf2961f44b66971a9c2ed7da0559e38e128e1177c22407c56fe1b38405b4735`。已安装到用户授权长期根 `H:\ChatChatBar-Desktop-App`；稳定入口为 `ChatChatBar.exe`，桌面/开始菜单快捷方式与当前用户卸载登记均指向此根。实际程序218个清单文件逐项核验、窗口可见、原生进程cwd在稳定根、bootstrap选择元数据未变；只留当前版本和管理文件，程序根无测试资料/日志。

最终19个唯一Windows helper测试全部通过。普通1206→1207升级、全新1207安装、运行中安装拒绝、正常关闭/全Job归零、两次卸载与独立隔离资料/媒体/bootstrap/三项假DPAPI回读通过。新程序根的受保护ACL实测通过；不改父盘或资料权限。故障1204首次自动退出失败仍保留，用户此前点错误弹窗不算本次验收。

用户本轮明确选择“已按步骤检查，全部通过”，对应1207版本/数据页原有内容/离线草稿正常退出后从稳定入口重开保持；用户截图同时显示版本及重开后的会话/媒体/草稿。此为限定使用验收，不扩张为全部功能、真实Provider或现代Android整应用恢复通过。已按该真实验收公开发布[Desktop Preview 1207](https://github.com/MisakaPiano/ChatChatBar-Desktop/releases/tag/desktop-v0.1.0-preview.1207)；公开API/列表/页面/下载头和校验文本通过，发行包更新选择器读取公开API通过。源码PR已正常合并，Android全组CI另记，不将本地结果冒称云端绿。

实际停电重启后，源码与远端一致、8份总账快照摘要一致、1206包及手机原始档案摘要保持；协调端Git fsck和会话库检查通过，保留3259个Entity JSON可解析。没有发现大量进度丢失；这不证明未保存内存内容或完整断电恢复、媒体图和凭据语义。仅接续在途1207构建和受影响检查，未重演旧故障。

|项|当前交付证据|明确未覆盖或后续|
|---|---|---|
|A 数据保全/恢复|既有真实大包合并/独立重开、流式导出/再发布和小型快照恢复按原范围复用；本次原始档案摘要保持|特定历史迁移前大快照实际恢复、跨账户和完整断电恢复未测|
|B 安装/更新|普通NSIS生命周期与1207长期根正式安装；用户版本/重开/草稿验收通过；手动更新发现实现|完整自动更新未提供；历史MSI故障不改PASS|
|C SaveSlot|既有正式Android模拟器两端续聊/往返及Desktop再导入证据|物理设备、超256MiB安全存档及Android147原恢复重绑限制仍按旧记录|
|D 凭据隐私|独立Windows保护存储、普通分享备份排密、三项假DPAPI在安装卸载后保持；最终发行物显式文件审计|不凭密钥证明服务端账户；跨账户/断电与未调用Provider范围保留|
|E 支持诊断|数据/版本页和基本诊断既有限定人工证据；当前用户截图确认1207显示|不上传私有日志/备份；不宣称全部布局或功能真人验收|
|F 发布真实性|包/源码/摘要绑定、说明、现有D016作者授权记录、PR和草稿发行|公开发行API/资产/标签/发行页读回通过；原始授权截图/消息未独立定位，不伪造|
|G 全应用迁移|既有真实现代大包Desktop认证/合并/冷开/离线续聊/再导出复用；合成冲突证据另列|现代Android完整整应用恢复和物理设备往返未测，Desktop专有域仅承诺Desktop恢复|

完整已知限制和操作步骤见[候选说明](PREVIEW_CANDIDATE_USER_NOTES_DRAFT.md)，精确包与安装证据见[1207收尾](evidence/preview-nsis-1207-20261028/README.md)。下面保留历史检查点，不以旧“当前”描述覆盖本截点。

---

## 2026-10-28 当前路线（用户最新直接指令）

以 [204 路线切换](204_STUDIO_NSIS_ROUTE_SWITCH_20261028.md) 为当前安装与引继入口。用户目标优先于审查助手建议：Studio式 NSIS 为 Preview 主线；MSI 无限期搁置，仅保留最小有价值档案并优先实际回收空间。**虚拟机若不再需要则彻底退役，不默认保留任何一台。** A 接管源码，B 回收废弃大产物，原执行者交接后停止。旧“继续MSI/完整失败VM保留/保持原执行者”安排已被本轮明确调整；其他 P11、数据与 Prompt 边界继续。新 NSIS 尚未验收，旧失败不改为 PASS。

执行分别见 [A 主线](205_NSIS_PRIMARY_HANDOFF_20261028.md)、[B 回收](206_MSI_RECLAIM_HANDOFF_20261028.md)。这次不增加 Project 审批，不重开无关审计；已有有效证据复用。云端 Project 文件同步须取得真实回执，不能用本地副本冒充。

---

# P11 → Public Preview 连续实施进度

2026-10-26组件合同修复及两机接续：DD1已补component1，普通组件GUID/文件/位数/条件不变，检测地址改为每组件唯一且跨兼容版本稳定，版本化ProductCode/目录拥有记录分离；编辑后的MSI分配新PackageCode。旧目录2不能代替组件准入，原生guard读取实际缓存表并保守处理当前用户/机器组件客户端，未知/异类/部分新装登记拒绝，不宣称全用户闭合。真实旧包准入RED为1项FAIL；中间33项中2项失败源于测试快照路径的builder根推断，未放宽生产路径；最终真实PS5.1为7 suites/33 PASS，另资源选项3 PASS独立计数。新Synthetic1102/1103均实际生成exit0：各218个唯一检测地址，217个共享GUID的KeyPath变化为0，对应相对文件目标集合相同，DLL/CAB/独立ProductCode及PackageCode核对通过。仅STATIC READY，未执行安装/升级/回滚，1307/Return:5未关闭，证据见[本轮记录](evidence/preview-component-contract-20261026/README.md)。

用户要求DD1开发打包、DL1固定哈希包隔离验证并行；没有新包不重复旧测试。用户实际截图确认关机的CCB-MSI-Lab保有Clean-Win10-19045-Before-CCB，旧失败现场须保留，后续由用户在DL1本地建立独立测试副本，停止远程桌面浏览器控制。用户明确接受内存压力后，仅合成工厂新增显式选项记录分页/变慢取舍，Java256MiB/单处理器、磁盘/源码/完整性门不变，原内存/后续磁盘拒绝日志均保留。工作区只清理已通知且有逐字节保留副本的程序镜像重复文件，原MSI/日志/真实数据不删；详细清单留本地忽略目录。本轮不合并desktop、不发布、不提升P11/Preview或正式baseline。

2026-10-26目录恢复代码修复（本地，未推送）：DD1已有日志证实旧A读到正确RM_RF目录却没有接回INSTALLDIR，FileRemove脚本指向默认目录；不是仅凭DL1转述。新增打包原生目录合同2，保存/维护/卸载恢复、目录安全与冲突拒绝、成本计算前及提交前校验、可发现旧合同拒绝已接入；没有触碰旧包或现有安装。真实PS5.1终轮3 suites /24 PASS、零失败/错误/跳过、exit0；原生策略使用假API，表写入用内存SQLite，未执行MSI或真实登记适配层。旧verify误收safety1的真实RED、编译失败及中间结果保留。1010和既有MSI未重建，DD1的1307、DL1的Return:5对象与物理删除恢复证据仍未关闭。见本轮[限定证据](evidence/preview-directory-guard-20261026/README.md)；没有解除P11/Preview限制，没有外发。

更新：2026-10-25 JST。执行依据为用户最新直接授权、D-046 与 203；检查点用于代码恢复和结果复核，不等待逐阶段 Project 批准。

2026-10-26发行准备收尾：候选说明已分别绑定1010/946c3791运行包、同版本未执行/未签名MSI和12e221d5本地诊断辅助器，明确后续工具不在1010包内；限定人工结果引用后续真实记录。D-016仍为作者直接授权依据，但仓库受控材料中尚未定位可独立对照的原授权回执，未扫描私人消息或未知附件。未作新上游观察、公开发行或安装验收；MSI回滚失败与正常升级/占用/降级/卸载未测继续如实保留。见[候选说明](PREVIEW_CANDIDATE_USER_NOTES_DRAFT.md)。后续小型合成快照恢复演练不等于历史真实大包快照已恢复。

小型合成快照恢复演练已实际完成：复用1010冻结63个生产JAR，直接编译test-only伴随探针，最终1个Python驱动集成suite /2 PASS、零失败/错误/跳过、exit0，六个独立headless JVM覆盖快照→新状态→恢复/重启准入→两次仓库/图片重开及损坏拒绝不改现用数据。环境缺编译依赖0项执行、探针锁文件读取2 FAIL和两次GREEN均保留，不累计历史测试数。生产源码、正式Gradle、GUI、SecretStore和安装器均未改；没有大包复制、清理、CI或平台实验。原快照与恢复前新状态安全快照均逐文件保全。见[独立原始证据](evidence/preview-continuous-20261026/snapshot-recovery/README.md)；历史真实大包快照恢复仍未测试，MSI及其他既有未关闭项不变。

## 授权及私有数据边界

- 用户已明确批准：本任务新建隔离数据根与独立凭据目录；当前 Windows 账户的真实 DPAPI；将已授权备份及其中凭据导入这些隔离目录做验证。保留手机导出原件，不覆盖日常根或现有凭据。
- 用户已明确批准：工程及必要验收完成后，按正常 PR／分支保护流程合并并发布首个 Preview。不 force，不绕过保护，不写官方 upstream。
- 真实档案、原文、解包内容、API/Token/密码只留本地忽略目录，不进入 Git、Actions、支持包或发布资产。CI 仅使用人工合成材料。

## 本轮范围与进度（用户已授权，连续实施）

用户已于 2026-10-25 明确把**合并式迁移纳入当前 P11→Preview 任务**：保留电脑已有数据，将手机数据合并进来，冲突由用户选择。现有执行者在本轮连续实现、验证并推进到 Preview；不另开审计、合同或 Project 审批门槛。详见 [203](203_P11_TO_PUBLIC_PREVIEW_CONTINUOUS_CODEX_TASK.md)。

合并预览、冲突选择、依赖重映射及合并结果验证现已属于当前任务；此记录不表示功能已经完成。新根应准备电脑+手机的合并结果，不能以替换到仅含手机数据的新根交付。

## 已完成

- 一次性原始数据报告已交用户，原件不变；私有目录不进入本文件。
- `cddb5945f76ea40c0d82a482a4bbf09c4c9c5123` 已推送：官方 code86 字段严格只读校验修复，8 项真实 RED 中 5 FAIL，最终 11 suites / 108 PASS。真实原始 Entity 的本地复核另存私有，不计入合成测试数。
- [CCB 1.4.7 录音问题反馈](CCB_OFFICIAL_147_HISTORICAL_VOICE_VERSION_REPORT.md)已提交：共享恢复路径会将未知非空旧版本改绑到当前版本。共享 JVM 逻辑已合成复现，未运行 Android APK，不推定每份真实档案的旧版本成因。

- Desktop 严格仓库的历史录音身份保留已修复并完成 4 项 RED（2 FAIL）→5 suites /32 PASS；Android 默认行为不变。正式档案的历史准入和完整保全路径仍继续实现。

- 新根独立受保护凭据命名空间、激活凭证及正式 Startup/SecretStore 解析接线已完成首轮 5 suites /44 PASS；包括明确启用的真实 DPAPI 假密钥往返。只有任务新建目录；不是 Windows 重启、断电、跨账号或旧 EXE 证据。合并与导入 UI 继续实现。

- 流式认证后的正式准备区已实现：已知凭据在 Entity 落盘前分离，四种字段状态和精确账户身份保留，Community 仅有受保护的休眠保全 Key、无网络/运行消费者。补测实际发现并修复新根未申报文件/凭据可混入的漏洞：35 tests /3 FAIL →9 suites /78 PASS，真实 DPAPI 仍只用假值和任务目录。没有把该准备区宣称为合并或正式导入已经完成。

- 历史录音正式 SaveSlot 路径已接通：保留已验证原包，对明确旧 ID 仅保全不重绑，音频仍经 MP3 校验与所有权收据。独立 JVM 的实际导入/恢复→退出→重开→导出通过，当前版本播放列表不出现旧 ID；原件不变。最终 8 suites /66 PASS（含9项合并预览规则），各编译/夹具失败及真正 REFERENCE 准入 RED 均保留。合并预览已有完整会话分组、名称/账户冲突、适用选择和 v2 未选设置保留；依赖重映射、最终提交及 UI 仍在实现。

- 独立 Moments 原字节保全适配器已实现，并加入 Desktop 全备清单与图验证：post/task 原始 JSON、来源版本/scope、头像/正文/生成元数据三条独立媒体边均保留；不注册 live Task。实际 live GC 删除原图后副本仍完整，snapshot 与 Desktop 全备导出/检查/映射往返通过。首轮5 suites /46 PASS，补齐 v2 scope 后89项通过，再发现省略媒体可掩盖错误路径类型：1项真实 RED →最终8 suites /90 PASS；取消/发布失败留下不可复用现场，未知或改动内容拒绝。真实手机数据尚未通过此适配器，合并导入发布/UI 继续接线；不宣称 Android 支持 Desktop 封存域。

- 独立凭据根迁移已补实际安全门：两个真实合成 RED 均证明绑定会被普通目录复制遗漏；修复后在复制/快照/切换前明确拒绝，四 suites /69 tests，68 PASS、1 native-opt-in SKIPPED、exit0。原根和目标 lock-only 状态不变；受控重新绑定仍需实现，不宣称迁移支持已完成。

- 全备录音元数据不再被 MP3 重解析掩盖：2项 RED 均失败，修复后3 suites /26 PASS；实际音频持久化 suite 另跑5 PASS。先核对原始时长及音频长度，再生成 Desktop 所有权副本；不改消息、版本或源文本。

- 新增明确 code86/v2 全备写入组件（尚未接线）：固定官方 DTO/加密器独立解码、范围缺省/选中为空、凭据拒绝、取消、无覆盖发布及来源复核已测。补测发现并修正本机 retainedPreferences 误入归档；原 S0 拒绝保持，流式 Source 同样拒绝。最终6 suites /70 PASS；各45 PASS、47/1 FAIL、1/1 FAIL、70/1 FAIL中间记录保留且不累计。正式上游 baseline 不变，合并/恢复/UI继续实现。

- 新根凭据完成检查改为本次实际写入的密文清单，拒绝外部新增/替换的合法文件：2项 RED 均失败，最终4 suites /50 PASS、零跳过。包含真实当前账户 DPAPI 的三项虚构值往返，以及命名空间替换后旧准备句柄不能读/写/删的反例；不触碰日常根或真实Key，不增加持久schema。

- 合并所需 JSON 流处理复用固定官方147源码（仅改包名/来源注释），避免另写不明语义的解析器；6项新增定向测试及受影响读取/凭据/封存回归，最终11 suites /135 PASS、零跳过。尚未据此宣称合并落盘或界面完成。

- 合并依赖重映射已有纯组件：按固定官方147的类型命名空间处理 ID，保留正文、版本、source-turn 和扩展字段；共享 MemoryHashes 重算祖先证明。实际7项 RED中1项失败，发现间接子证明变化未向上层传播，已修复；最终结果见 merge-graph-remapping 原始 XML/RESULT.json。尚未把纯映射宣称为完整落盘、内嵌 v8 包重写或用户迁移。

- 内嵌 v8 存档重映射准备已实现：保留独立原包，逐条处理消息/RAG/录音，媒体原字节复制，重写后重新严格检查；分叉前按共享策略固定旧的隐式版本 ID，历史录音不重绑。首轮4 suites /19 PASS，包含已有实际服务导入/恢复→独立进程重开→导出回归；没有制造 RED。完整冲突绑定、原包保全发布和合并根/UI继续实现。

- 冲突选择已连到目标清单及内嵌声明扫描：先统一分配紧凑、有序消息 ID，再处理实体 Windows 文件名和独立媒体路径。实际27项 RED中2项失败，修复跨会话 Memory 等拥有记录冲突漏报、空计划不响应取消；最终受影响套件结果见 merge-destination-planning。初始夹具编译失败保留。计划仍非可运行根，继续接入原包保全、资源验证、凭据和提交/UI。

- 新增独立原始存档保全域：v8 原包、原始外层 JSON、来源版本/scope、长度/SHA完整保留；不参与播放/Task。快照与 Desktop 全备往返原字节一致。实际23项 RED中1项失败，修复整根检查仍用旧的历史录音拒绝模式；中间编译失败保留，最终结果见 slot-source-custody。新合并映射入口与旧 v1 Prepared 分离，正式合并发布/UI继续接线。

- 合并准备已实际落盘到 PREPARING 新根：双方独有内容/两条聊天分支、图片、原始和分叉历史录音包均保留；独立 JVM 冷启动与续聊、现代全备再导出/认证读取通过。真实1项 RED证明同名同长度不同包曾被误判重复，现签名绑定包SHA；最终测试逐轮见 merge-root-materialization，不累计。凭据冲突选择、实际导入及 bootstrap/UI发布继续接线，未宣称完整产品完成。

- 凭据合并准备已实现：模型配置/Key冲突进入 KEEP/TAKE/BOTH，全球凭据显式选择，空字段不清电脑Key；仅向确切新根的独立保护目录写入并回读，旧Store只读。实际7项RED中1项失败，补上部分回读失败/取消后本次Pending永久作废，阻止误完成；原始轮次和最终数量见 merge-credential-binding。Community仅休眠保全；模型目标身份不代表已验证服务端账号。服务协调冻结、最终提交/UI仍在推进，未用合成结果宣称真实数据迁移完成。

- 生产容器实际复现并修复快照竞争：独占读取时曾出现新Key+旧Endpoint。1项真实RED后，将Desktop配置/密钥事务及Fish/NAI凭据编辑纳入同一资料协调器；最终7 suites /74 PASS。扩展测试中一次singleton文件路径夹具错误保留。仅证明本进程协作写入边界，不冒称跨进程/断电原子事务；旧普通事务语义不变。

- 合并后配置角色实际读取修复：legacy Embedding 的 default-ID 分叉不能影响 active singleton；retired planning 保留共享迁移能发现的物理位置。真实1项/1FAIL、3项/3FAIL均保留，最终8 suites /50 PASS；实际 Windows 模型存储策略下读取与独立凭据绑定通过。见 merge-native-configuration-roles；正式导入服务/UI继续接线。

- 合并提交前的任务维护准入已补：停止并等待已有生成任务的最终落盘，维护期间拒绝新任务和消息修改，失败可恢复正常准入，关闭优先且不可被维护结束重新打开。定向测试实际数量见 merge-runtime-maintenance/RESULT.json，无虚构RED；继续正式导入服务/UI。

- 默认合并服务和数据页已接线：双方数据预览、显式冲突/凭据选择、新根准备、源状态复核、快照、受保护完成及bootstrap切换。实际容器测试覆盖生成任务最后落盘导致旧预览拒绝、重新预览成功、正常关闭后才释放目标锁；源数据/原Key保留。首轮61项中5个新增夹具字段错误保留，修正后5项通过，最终6 suites /63 PASS；不累计历史数。真实档案/原生验证、新版导出及候选界面检查继续。

- 数据页现使用现代 code86/v2 导出，实际Service明文/加密导出、现代角色字段保留、已配置密钥排除及旧Prepared拒绝已测；模型冲突显示Provider/Endpoint/远端模型与ID，不展示密钥。旧版直接替换按钮移除，恢复统一走合并核对。5 suites /42 PASS后修正展示默认Provider，最终相关2 suites /18 PASS；其余24项属于前轮不冒称重跑。下一步本地真实来源及原生隔离验证、可运行候选。

- 真实code86全备完整认证/预览后，实际顶层1790条录音另有36条未知非空版本；音频存在且长度匹配，与三份内嵌存档108次出现的是同一组36ID。旧整根版本检查拒绝，未切换目录；原始数据/诊断/真实Key均仅本机。已补顶层原记录+原消息+独立MP3保全和受凭证约束的准入，合成RED/GREEN、改动/缺失/未知字段拒绝、快照/导出往返及live删除后保全见 historical-live-recording-custody。真实重试及冷进程验证继续，不把适配完成写成真实导入成功。

## 当前与下一步

Studio 固定报告 `134dd47` / 实现 `2484858` 已按来源身份只读核对，Desktop 独立对照与安装验证方案见 [适配说明](STUDIO_UPDATE_REFERENCE_ADAPTATION_20261025.md)。实际发现并修复关闭时全局任务准入过晚：1项真实 RED →4 suites /34 PASS；没有套用 Studio 的测试数字或验收结论。Preview 安装版本现要求显式数字版本、升级递增检查及固定产品身份，合成包使用独立身份：6项 Python 集成测试实际调用12次 Gradle 验证任务均符合预期；不是安装升级 PASS。P11 合并提交与 UI、原生包验证继续推进。

实际 Windows 合成 MSI 首装、A→B、正常关闭与独立 JVM 重开已运行：资料/图片/bootstrap/三项虚构 DPAPI 密钥保持，Job 跟踪启动器/JVM 两进程均退出。另发现安装目录未知测试文件被删除，MSI 原始日志明确有递归清理与 FileRemove 记录；Gate B 不能写 PASS。已实现生成 MSI 的只读产品/模板核对及有界修正，6项实际 MSI 元数据/CAB/拒绝测试通过，原生修复后双版本升级与故障回滚继续执行。修正还隔离默认程序/资料路径、将旧版本移除放入事务并阻止降级；首个 Preview 使用 MSI，EXE bootstrap 暂不生成。真实资料根与实际 API 未进入这些测试。 修正版 0.1.1002/1003 已独立生成；A 实际启动、根锁排他、Job 全退出及数据/图片/三项虚构 DPAPI 密钥重开通过。故障注入后 Windows Installer 回写 HKLM 产品登记返回访问拒绝，旧程序可运行但登记未恢复；两种同包修复返回1602。原生回滚明确 FAIL，保留隔离现场与原包，不改 ACL/安全设置、不据此归因360；修正版正常升级/降级/卸载尚未继续。原始证据见 windows-corrected-native；其他 P11 工作继续。

正式 code85/86 角色卡生图默认值、Studio varietyPlus 已进入共享类型及 Desktop 保存/重开/图像启动路径；旧 code84 仍拒绝新字段。生产流式全备输入已验证完整认证后交接，尚待合并准备区、资源/图验证及 UI 接线。详见 evidence/preview-continuous-20261025/official-entity-production/RESULT.md。首轮夹具错误保留为 111 tests /1 FAIL；最终 Desktop 实际重跑 7 suites /92 PASS，共享 4 suites /27 PASS 复用紧邻上一轮的相同源码结果，不累计冒充本轮重跑数。

1. 修复 Desktop 严格恢复对明确历史版本身份的保留；历史录音不得成为当前版本的声音。补齐正式 SaveSlot/全备保全路径，再调整相应准入。
2. 将当前全备新根工程接入合并式迁移：保留电脑现有数据，读取手机档案、给出合并预览，冲突由用户选择；新根准备合并结果及受保护凭据，正确重映射依赖和资源，取消/失败保留当前状态，重启验证两边内容。Moments/历史封存不自动激活。详见 203 Phase 2。
3. 正式路径的隔离合成验证及用户授权的本地真实来源验证；相关 SaveSlot 读写/重开回归。
4. 打包 Windows 候选、安装升级与退出测试、更新/诊断/导出检查，形成实际 A–G 矩阵并直接向用户交必要人工步骤。

## A–G 当前事实

授权真实来源的隔离 SaveSlot 恢复另遇容量边界：原会话恢复前的完整安全存档实际337,270,924字节，超过现有256MiB便携存档上限，尚未替换当前会话。已补明确容量拒绝和界面提示，不跳过保全、不缩减音频或放宽上限。继续在新建隔离会话验证原有较小存档；该操作不等同于过大原会话的原地恢复已通过。

新建隔离会话的真实尝试还复现32条录音锚点路径与原会话冲突，事务在授权写入前拒绝，旧数据未覆盖。独立合成反例确认跨会话恢复沿用消息ID是原因；现复用共享SessionCopyPolicy分配消息/锚点命名空间，同时保留版本/source-turn及原包。最终受影响6 suites /45 PASS；含legacy1–7已改过库封套sessionId的情况。真实目标重试继续执行，不把此次安全拒绝写成真实恢复成功。

后续真实隔离重试已通过：恢复27消息/235录音/36明确历史版本；独立JVM重开并导出原存档23,177,034字节。再保存两条虚构离线续聊消息，使用共享Memory快照创建新存档并导出23,265,639字节；另一个JVM导入到独立会话后，29消息/235录音/36历史版本、音频收据、电脑见证和完整根图验证全部通过，正常退出0。未调用Provider；不替代Android物理设备验收或超容量原会话的原地恢复。

现代全备实际再导出2,492,481,217字节，code86/v2、10,185清单文件，完整认证且已知凭据字段均无NONEMPTY；独立离线恢复映射14,236文件及全图检查通过。该轮没有发布映射根；快照早于上述两条续聊消息。首轮容量拒绝保留，后续在不同任务磁盘分别满足原有最坏容量预算，没有降低上限或删减媒体。正式提交及独立冷启动继续验证。

内部app-image1005精确绑定`f0da765`：217文件，两次默认启动均证明根锁、正常关闭和Job进程归零，合成资料及三项虚构DPAPI密钥重开保留。附加软件渲染尝试出现启动阻断，原因未确定，原失败保留；无数据页/更新页视觉通过声明。MSI回滚登记失败仍开放，管理员单次修复结果尚待用户执行。详见 evidence/preview-continuous-20261025/windows-app-image-1005/。

现代全备正式再次发布实际拒绝于`MERGE_PACKAGE_OWNER_IDENTITY`：认证通过，但已导入存档的库封套ID/会话与不变原包不同。原根、档案及独立旧Key保留，新目标未激活；正在按共享`SaveSlotPackageStorage.importPackage`的真实封套合同修复，不跳过包/图验证。另一次运行因未完全冻结共享JAR而缺类，属于本地验证工具失败，原记录保留。

封套合同修复已完成9 suites /72 PASS、零跳过：只允许共享导入明确变更的6个封套字段，其他内容/计数必须一致；包内owner采用局部别名，再做全局分叉，原包/原始外记录独立保留。新根使用验证后的重写清单作为库记录；伪造内容、长度、计数、文件名及同ID换会话仍拒绝。真实最小RED、中间夹具失败与最终XML均保留于library-package-envelope；真实大包发布/独立冷启动继续，不把合成通过写成实测通过。

整合无界面回归真实执行247 suites /2150 tests：2120 PASS、27 FAIL、3 SKIPPED、exit1。14项原生窗口/输入案例不适用于所选headless环境，其余失败仍逐项调查。已修正3处明确过期的测试断言（安全剪贴板注入/新标题组件/跨会话ID探针），定向3 suites /23 PASS、零跳过；不宣称整体回归已绿。原始XML及说明见 integrated-oracle-correction。

其余无界面失败已按独立JVM复核：发现并修复较早回复重新生成时，整轮扩展会重新加入已排除目标的实际缺陷；同轮其他回复、最新用户和持久原件保持。其余更新为已接受界面的可访问操作/范围断言，滚动夹具推进动画时钟，旧Phase5假服务器明确限定主聊天而不混入Memory后台请求；独立Memory安全/生命周期同时复跑。真实轮次59/9FAIL→60/6FAIL→60/2FAIL→最终9 suites /67 PASS、零跳过，见regeneration-and-ui-integration。不把这些定向结果累加成2150全绿，14项原生窗口headless失败仍保留。

整合无界面最终执行247 suites /2156 tests：2139 PASS、17 SKIPPED、零失败/错误、exit0。14项实际OS窗口/输入改为明确图形前提，另外3项为已有native opt-in；这些跳过不计原生通过。精确源码/命令/XML见integrated-headless-final。期间另查明332字符有效路径被普通Win32元数据调用误拒：真实RED后使用原有有界extended-path，同一长隔离profile下11 suites /107 PASS，链接/容量拒绝不变。后续合并阶段提示改动另跑受影响回归，不冒称包含在这次整合执行中。

大包合并进度已接入数据页：固定阶段与实际复制校验文件数，不显示私密值、百分比或提前成功；通知止于不可取消提交段之前。首轮夹具编译失败0项执行保留，修正后4 suites /43 PASS、零跳过；实际损坏源/取消/旧bootstrap/双方资料与假密钥/原签名兼容已测，见merge-progress。

内部app-image1006绑定`848abaa2`，217文件、真实版本0.1.0-preview.1006/Windows0.1.1006；两次默认原生启动、根锁、正常关闭及全Job进程退出、合成资料/媒体/bootstrap/三项虚构DPAPI密钥独立重开通过。旧版本号夹具在启动前拒绝、审计后缀误判等工具失败保留；最终21,197类条目包审计通过。不计JUnit，不代替MSI回滚或数据/更新页视觉通过。当前ADB连接核对0设备，未安装/读取Android。详见windows-app-image-1006。

现代全备再次正式发布及独立默认冷启动均已通过：输入2,492,481,217字节、10185清单文件，目标10会话、5项受保护值回读一致，输入不变、正常关闭exit0。另两个JVM在恢复副本保存两条虚构离线消息/草稿后独立重开，原消息/PC见证/完整图保持；均exit0，无Provider操作。发布采用冻结5b8+Planner修复，冷开/续聊采用1006包的848abaa2冻结生产JAR；不混称同一源码运行，真实原文/私有日志不上传。见real-modern-republication-aggregate。下一步继续处理绑定根的目录迁移及必要人工平台验收，不等待Project前置签字。

绑定凭据根的受控目录迁移已完成首轮：保留原字节复制拒绝，新增无凭据自归档→严格读取/源与wire独立证明→新根发布/凭据重新绑定，录音/图片根路径重映射；旧目录/旧Key保留。真实准入RED后13 suites /168 PASS，另显式当前用户真实DPAPI的虚构值迁移1 PASS、零跳过。外部改动、目标保护失败、取消、最后任务写入及全局重启准入均已测。历史快照/恢复现场只留原目录、未复制到新根，界面明确提示。见protected-root-relocation；继续处理旧未绑定根的媒体迁移及大包新迁移验证，不停在检查点。

旧未绑定目录的录音迁移已补实际反例：字节复制后记录仍指向旧根，目标AudioStore明确拒绝。修复后含images/audio的目录复用受控发布/路径重映射，原全局凭据只读保留、已登记消费者进入独立新命名空间。最终13 suites /170 PASS、零跳过（显式真实DPAPI虚构值用例已执行），见legacy-owned-media-relocation。当前源码继续打包并准备大容量目录迁移实测。

内部app-image1007已绑定`85d16a67`，217文件、版本0.1.0-preview.1007/Windows0.1.1007。类条目审计计数后来纠正为21,158（原报告21,216保留为错误原记录）；两次默认原生启动/正常关闭/根锁排他/Job全退出，以及合成资料/媒体/bootstrap/三项受保护假密钥独立重开通过。使用完全冻结的生产JAR和外部测试伴随类，未把私有伴随类放入包。MSI/人工视觉不冒称通过。见windows-app-image-1007；实际大容量恢复副本的受控目录迁移/冷开继续，原目录和旧凭据保留。

|项|已有证据与本轮状态|尚需完成|
|---|---|---|
|A 数据保全/恢复|实际合并服务、合成冲突/取消/故障及新根发布已测；真实隔离合并、标准独立 JVM 重开/离线续聊、存档再导入及整根检查通过；Windows长路径真实反例修复后11 suites /107 PASS；大容量绑定根迁移及独立重开均exit0|平台断电/跨账号未测；本轮未另行恢复该迁移前快照，不把其目录保留当恢复测试|
|B 安装/更新|D-041 发现证据；1008 的 G0 误判已修复，1009 在原失败样本正常启动。内部普通标识1010绑定`946c3791`，217文件/63 JAR核验、两次隔离原生启动/正常退出/根锁与三项受保护假密钥保全通过；MSI构建及静态安全修正exit0，未安装；约定1010人工界面检查PASS_WITH_SCOPE，来源为用户诊断/关闭陈述及原协调像素观察|原 MSI 故障回滚仍 FAIL；管理员修复exit0但UpgradeCode关联仍缺失。后续正常升级/占用/降级/卸载未测；人工界面通过不覆盖这些动作，更新检查仍NOT_CHECKED|
|C SaveSlot|模拟器互通旧证据；Desktop 历史版本不重绑及全备来源保全补齐；真实隔离恢复→重开→离线续聊→新存档→独立JVM再导入通过|原大会话安全快照超256MiB仍拒绝；Android147 原恢复重绑问题及物理设备仍未关闭|
|D 凭据|真实来源4项凭据在任务独立目录经 Windows 保护后回读一致，标准冷启动解析及电脑假密钥保留通过；未调用 Provider|跨 Windows 账号/断电、服务端账号身份不作声明；分享导出排密与保护边界按限定证据|
|E 诊断|现有路径/备份 UI、固定阶段进度与基本诊断复制；2 suites /15 PASS。用户已粘贴1010/946c3791的CCB_DESKTOP_SUPPORT_V1，确认复制取得结果；约定数据页显示限定通过|用户正常关闭为USER_CONFIRMED，不冒称独立进程核验；不覆盖全部尺寸、真实导入导出或网络更新；完整私有请求证据不是可公开支持包|
|F 发布依据|D-016 记录作者直接授权；正式 baseline 仍 1.4.1；内部1010运行包/MSI精确身份及使用/恢复/范围说明已保留，未发布|原授权记录核对及发行时实际范围；安装失败未解决，不把候选打包当发布通过，不虚构授权原文|
|G 全应用迁移|正式服务/冲突UI、合成各选择/依赖/资源/密钥/封存；真实 code86/v2 首次隔离合并9会话/5存档包、认证5625文件、标准冷启动通过；现代导出10,185文件认证/映射通过；恢复副本迁移/独立重开通过，10会话与5项保护值一致；约定1010数据页人工显示限定通过|首次真实来源0冲突，不能代替合成冲突证据；原生安装条件仍待完成；人工显示不替代新真实导入导出，不认证原Android生产应用身份或完整双向互通|

没有把实现授权或已有局部测试写成完整 P11 / Preview PASS。需要人工完成的具体条件在可运行产物准备后直接交用户；其他工作继续。

基本诊断复制已加入版本与更新页，仅收集合法构建身份及固定系统/JVM/检查状态，不读取资料、路径、配置或请求原文；浏览器打开失败与检查失败分开。首轮夹具关闭写法编译失败0项执行，中间及最终分别2 suites /15 PASS、零跳过，原始XML/源码见support-summary。候选使用与恢复说明见[未发布草案](PREVIEW_CANDIDATE_USER_NOTES_DRAFT.md)。当前实际大容量根迁移仍运行，原生MSI回滚登记FAIL及人工/Android条件不变。

内部1008包绑定2f75a4d3，217文件，离线构建exit0；一次默认原生启动/根锁/Job全退出及独立合成资料/媒体/bootstrap/三项原生保护假密钥重开均exit0，前后程序文件不变。真实类条目数21,161；重数旧1007为21,158，纠正此前21,216的报告计数，217项原清单仍匹配，不改旧原始证据。1008人工启动脚本仅预检exit0，未据此宣称真人视觉PASS。详见windows-app-image-1008；旧1006人工入口保留，大容量迁移及安装器外部条件继续。

用户在 Windows PowerShell 5.1 实际执行1006入口遇到字符串解析失败；已独立复现，并发现 JSON 数组包装及哈希命令可用性差异。三个已知人工入口均保留完整清单校验，改为ASCII状态、直接数组读取和框架SHA-256；最终原生5.1语法/安全预检各exit0，90份原始失败/修复文件见manual-helper-winps51，不冒充JUnit或GUI通过。随后用户实际启动1008又遇到 `G0_CHANGED_DURING_CHECK`，登记为新的真实启动失败，继续在同一合成根定位，不清资料或关闭检查；人工视觉尚未开始。管理员修复仍待用户实际结果。大容量受控目录迁移独立继续。

1008启动阻断已独立复现并定位：同一合成 `entities` 目录身份、创建/修改时间不变，JDK属性大小从0变4096，被G0误认为变化。已只修目录大小比较，保留身份/时间/普通文件长度与其他安全检查。真实反例21项中19 PASS/1 FAIL/1 SKIP；修复后3 suites /83项，81 PASS/2个既有真实符号链接前提SKIP，零失败、exit0。诊断编译的两个工具失败和原始冷启动失败保留，见startup-directory-size-fix；继续打新包并验证同一原样本的冷启动，不把JVM通过写成人工界面通过。

1009修正版绑定`7b3e1534`，217文件离线构建exit0。直接使用1008原失败假资料，两次默认原生冷启动/正常关闭/根锁排他/Job全退出通过；三次独立资料/图片/bootstrap/三项原生保护假密钥核对exit0，未重建样本。Windows PowerShell 5.1新人工入口预检exit0，实际入口也打开正常界面，用户截图已看到；专用测试驱动误把PowerShell父进程提前退出当作子程序结束的FAIL保留，后续按确切原生父子链正常关闭并复核数据，未把null退出码说成0。见windows-app-image-1009。人工数据/更新/诊断复制仍待查看。

大容量受控目录迁移现已返回真实 `MATERIALIZATION` 拒绝：映射后文件总量超过既有4GiB展开上限，未达到发布成功或独立冷开步骤；原始失败仅本机保留。继续调查重复资源/映射增长并构造合成反例，不直接放宽上限或清理原始媒体。前文“仍运行”是历史进度，不代表当前状态。管理员MSI修复仍待实际结果。

资源增长的两个机制已用2 suites /12项实际跑出2 FAIL：相同自源在无新记录时仍复制两边媒体；已带有效Desktop归属凭证的MP3每次恢复仍重新派生。现仅对无选中手机记录、同逻辑路径且摘要一致的媒体共用现存文件；合法既有录音复核归属/摘要/MP3后复用，损坏凭证拒绝，Android无凭证原件仍保全并派生。最终实际12 suites /99 PASS、零跳过，含显式原生DPAPI假值及独立历史存档进程。中间55项的新负例断言曾能捕获自身异常，已修正并保留原源码/XML；只以99项为最终负例证据。见retained-media-growth-fix。真实大包重试另建目标，旧失败现场和原目录保留，不提高容量上限。

## 当前人工记录（2026-10-26；来源与未覆盖范围分别保留）

此前用户回复将稍后执行，脚本只读预检通过；随后已出现本次管理员执行的真实结果与 MSI 日志。新结果按下文登记，旧预检不冒充执行证据。

1. 管理员单次修复**已经执行，不要再次运行**。本次实际执行的命令为：

   ```powershell
   & 'H:\ChatChatBar-Desktop\app\desktopApp\build\preview-continuous-private-20261025\Run-AdminSyntheticRepair-Once.ps1' -ConfirmSyntheticProductOnly
   ```

   已核对本机结果：`result.returncode == 0` 为真；`related() == [expected ProductCode]` 为假（实际空集合）；`before == after` 为真。因此最后的组合断言失败，不能把安装器exit0等同修复完成。只读独立 API 核对显示，同一 Synthetic A 产品当前在 `USER_UNMANAGED` 上下文为状态5、版本0.1.1002，其他两种上下文未查到；HKCU 对应 UpgradeCode 键存在，但缺少该 ProductCode 值，关联枚举仍为空。这表明本次有登记状态变化，而完整升级关联没有恢复；不能解释为“什么也没做”，也不能仅凭 AssertionError 归因杀毒软件或权限。程序219个现存文件已另存本地摘要清单；没有重新执行安装器、修改注册表或读取凭据。详见[脱敏观察](evidence/preview-continuous-20261025/admin-repair-readonly-observation/OBSERVATION.json)。后续安装器序列暂不执行，避免在不完整登记上继续升级；其他独立工程继续。

2. 约定的1010隔离界面检查已限定完成，不用为补证重复打开。以下保留原入口定位：

   ```powershell
   & 'H:\ChatChatBar-Desktop\app\desktopApp\build\preview-continuous-private-20261025\windows-runtime-candidate-1010\Open-Isolated-Preview-1010.ps1'
   ```

   用户已粘贴版本 `0.1.0-preview.1010` / 提交 `946c3791` 的基本诊断；原协调已实际查看截图，确认顶部手机合并说明/按钮及备份、版本更新区域可读，无明显重叠截断；用户明确确认正常关闭。登记PASS_WITH_SCOPE：像素结论来自原协调，关闭来自USER_CONFIRMED，不冒称本线程独立看图或进程核验。updateCheck仍NOT_CHECKED，不覆盖真实导入导出、安装升级、网络更新或全部尺寸。1009的旧人工观察按历史证据保留，1006/1008不再作为本次入口。

大容量目录迁移的第二次实际运行使用冻结的`98746a73455ce161b70032b57c834e3ea26ceadb`生产 JAR与未改变的1009依赖，外置私有伴随类；不是新安装包。它在另一个任务目标内执行，原目录、原始手机档案和第一轮失败现场保留。截至18:46 JST，仍在运行，已观察到导出后逐文件摘要核对；尚无迁移成功或独立重开结论。进程与源资料路径、原始日志和凭据见证仅留本地忽略目录。该运行不阻止其他独立工程，也不把管理员待办变成全局停工。

管理员修复后的追加只读定位：最初安装A的exit0结果含预期关联，随后升级故障exit1603时关联已空，管理员修复exit0后仍空；当前进程用户与该MSI日志用户匹配，未公开SID。对应同一份原始/修正MSI的登记动作、条件、顺序与Registry表逐项相同；首次日志为`ProductPublish`，此次修复为`ProductPublishUpdate`。这些事实缩小了差异范围，但尚未证明Windows没有重建关联的内部原因，不通过改注册表、降低断言或另跑安装器制造通过。

低负载独立工作补齐可重复的运行包只读核验工具：最终17项Python合成测试通过，真实原始ZIP名的反斜杠/NUL反例及两个夹具失败均保留；不计入JVM数、不重跑Gradle。现存1009包实际核验exit0，217文件/63 JAR/21,161类条目与原冻结清单完整匹配；不重新构建、启动或安装。已知私有文件/伴随类拒绝不等于通用秘密扫描；MSI失败和真人检查状态不变。源码/原始轮次见[app-image-auditor](evidence/preview-continuous-20261025/app-image-auditor/README.md)。

MSI原失败进一步定位到升级关联键的创建：MSI消息1401携带的是系统错误1307（`ERROR_INVALID_OWNER`），另有其他登记操作返回系统错误5。两类编号不能混同为Win32错误1401。含SID的原行与四个确切Synthetic相关键的当前安全描述仅留本机；当前均可读取，不倒推原回滚时的服务令牌/所有者分配原因，也不修改ACL。编号含义依据[Microsoft Installer消息](https://learn.microsoft.com/en-us/windows/win32/msi/windows-installer-error-messages)与[系统错误码](https://learn.microsoft.com/en-us/windows/win32/debug/system-error-codes--1300-1699-)。

19:42 JST 的轻量线程观察显示大包已越过安全快照，进入`DesktopMergeRootPreparation`的摘要复核；新根仍处于未激活准备阶段。尚未产生`move`退出结果，未据此宣布发布或冷开通过。原有进程持续运行，不启动重复导入，也不增加重负载构建。

独立小修复补上原生安装探针的优化解释器准入：原入口依赖assert，却允许`-O`/`-OO`帮助及优化import进入无断言模块；只用help/import的合成反例实际失败。现模块开头显式拒绝优化解释器，正常帮助不变，最终3项Python测试全部通过。初轮3个失败事件来自CLI方法的两个子情况和一个import方法，不冒称新增3个失败测试方法。没有重新执行MSI、启动界面、访问凭据或改动JVM；旧管理员单次脚本已执行且继续锁定旧源码，不修改它或重新使用。见[native-probe-validation-guard](evidence/preview-continuous-20261025/native-probe-validation-guard/README.md)。

大包目录迁移重试现已完成：`CONTROLLED_ROOT_RELOCATION_PASS / exit0`，随后独立JVM `STANDARD_RELOCATED_ROOT_REOPEN_PASS / exit0`，两次均正常关闭。原注册live清单的文件集/摘要未变，原根及迁移前快照目录保留；新旧5项已知受保护值一致，重开核对10会话、既存离线续聊/草稿和PC见证及整根引用图。运行后63个冻结生产JAR和私有伴随二进制身份仍匹配；生产源码为`98746a73`，不是后续工具/文档提交的新运行包。真实原始见证仅本机，公开[限定脱敏结果](evidence/preview-continuous-20261025/real-root-relocation-reviewed/README.md)；没有放宽4GiB限制或删除原媒体。旧“仍运行”段落是当时记录。本项通过不关闭MSI登记失败或真人检查，也不是P11/Preview整体通过。

最新普通标识内部候选1010绑定`946c3791`，已包含上述生产修复：运行包离线构建/只读核验exit0，217文件/63 JAR/21,161类条目；新建假资料下两次默认原生冷启动、根锁排他、正常关闭、Job进程归零和独立资料/图片/bootstrap/三项假DPAPI值保全均通过，程序文件前后相同。PowerShell 5.1人工入口预检exit0，真人数据/更新/诊断复制仍PENDING。首次MSI在外部I盘构建后被既有安全工具的仓库路径准入拒绝，真实exit1和原始unsigned文件保留；未放宽检查，另建仓库内目录重建MSI exit0，166,498,304字节，普通身份/安全修正/CAB及主JAR与已原生测试运行包一致性已核对。未执行MSI，没有重跑JUnit、安装/修复/注册表操作、合并或发布。原始结果见[windows-candidate-1010](evidence/preview-continuous-20261025/windows-candidate-1010/README.md)，不关闭此前真实MSI登记FAIL。

2026-10-26新增限定结论：约定1010人工界面检查已PASS_WITH_SCOPE，来源与范围见上表及[人工记录](evidence/preview-continuous-20261026/registration-diagnosis-fix/HUMAN_SCOPE_RECORD.json)；旧PENDING段保留为当时记录，不再催用户重开。只读A/B和六份既有日志分析明确：B匹配A正常，故障在B自己的组件应用前触发；回滚重建旧关联时1307并跳过，另有回滚登记错误。管理员实际已提升权限，但Advertise/REINSTALL现场走ProductPublishUpdate，关联仍缺失，底层owner/有效令牌原因未证实。

同日按用户新增授权修正诊断辅助器：repair-registration保留源/程序身份检查，区分部分注册、不可用、未知及观察变化，写新标签诊断并非零拒绝，不再执行原REINSTALL尝试；退出码/精确关联/文件图不变的强成功条件保留为未接线oracle，未新增恢复策略。两个实际安装器边界合成反例失败后，最终2个Python套件/28 PASS、零跳过、exit0；原始失败、快照及XML见[registration-diagnosis-fix](evidence/preview-continuous-20261026/registration-diagnosis-fix/README.md)。仅辅助Python及相关记录变化，没有查询真实注册表、构建/重启/安装/修复/卸载或改生产Kotlin，1010不重建。原MSI故障及未覆盖的原生验收继续保留，不升格整个Preview。

# 2026-10-26 — fixed component1 independent guest preparation

User-provided DL1 output confirms the new independent VM copy inactive/Saved and original failure VM Off, with old checkpoint list/disk length/mtime unchanged. Exact IDs remain local. No guest installer has executed yet; original disk content hashing was not performed. No saved state was discarded.

The fixed1102/1103 packages remain unchanged. A standalone PS5.1 first-install entry now pins both hashes and the existing verifier/identity metadata, refuses existing registration/output and installs1102 once in the explicitly selected isolated guest. Orchestration tests: actual rounds14/16 exit1,14/16 exit1, then16/16 exit0. Failures and final XML retained in `evidence/preview-component-guest-entry-20261026/`. Actual DD1 read-only native registration and actual host refusal checked separately. Fake-boundary tests do not prove native installation/upgrade/rollback. Next user action is opening only the new guest, copying the fixed pair plus helper and running the one first-install command; old failure VM stays Off. DD1 continues independent engineering; no repeat of old DL1 tests.

2026-10-27更新：用户实际执行1102首装及随后仅保护器诊断均被拒绝；新诊断将具体分支定位为首个组件 index0 的 `MsiEnumClientsExW` 返回2。重复粘贴按一份独立结果计，不重复安装原包。候选保护器保留拒绝条件，仅在非维护安装、index0返回2且独立的完整当前用户/机器组件清单证明目标未注册时，允许该空客户端情形；清单失败/存在目标/超限、维护安装、枚举中途失败和升级客户端身份冲突仍拒绝。已有目录/旧包归属校验保持。原生诊断增加阶段、状态、组件序号，不再只输出合并码。

本轮实际PS5.1为7 suites/38 PASS、零失败/跳过、exit0；另有DD1真实组件清单配合注入码2的C适配验证及仅保护器的服务端微型候选 exit0。DD1同为19045，不能归因Win10/Win11差异；DL1候选仍NOT TESTED。证据见[Win10客户端枚举修正](evidence/preview-component-client-win10-20261027/README.md)。两份固定MSI未改；用户下一步只需执行新的小型修复候选入口，无须重跑旧1102。未进行应用安装验收、升级、回滚、清理或发布。
# 2026-10-28 — NSIS 主线首个实现检查点

已接手 `e368552b`，原执行者确认停止写入；A 独占源码。`tools/desktop/nsis` 新增真实 NSIS 构建入口、当前用户稳定 launcher、独立版本暂存及清单归属、窗口启动 ACK、快捷方式、所属退役与卸载。`Main.kt` 接入程序清单租约和初始化/显示后的身份回执，关闭准入提前到确认退出时；保持原排空/持久化语义。`packageNsis` 从普通 app-image 构建，不调用 MSI。三份路线原附件已读取，云端 Project 同步仍无真实回执，非开发阻断。

首轮 Kotlin 编译实际通过；Windows helper 的 7 项聚焦生命周期用例通过后，新增暂存重试/无归属拒绝用例继续验证。测试揭示 ready 文件创建/读取竞态，生产 ACK 改为完整写入后原子改名。小型假 payload 已实际编译成 NSIS EXE；不计为 Desktop 安装或 GUI 通过。真实 app-image、安装升级/卸载、资料与假凭据保全继续验证，尚未发布 Preview。

回收端报告本轮 H/I 已删除约 20.37 GB 逻辑字节，C 盘另一路去重约 1.37 GB；这些是协调端/B 的回执，不冒称 A 独立测量的物理净空间。保留独立资料根、手机原件、当前可复用 app-image、共享依赖及本轮 NSIS 输出；VM 在其他 PC，不声称本机已删除。

1201 普通生产 NSIS（`501a56a1`，155,257,272 B，SHA256 `200a68118dcdd3cb0dbf8820e13a32dd3f7648ac078a94042f48d0fb8df24928`）已实际首装、从稳定入口重开及卸载。两轮窗口正常显示、根锁排他、WM_CLOSE 正常关窗及完整 Job 归零均通过；首装原生父进程/JVM cwd 均为稳定根，不占版本目录。独立 JVM 在每轮及卸载后复核合成会话、草稿、图片、bootstrap 和三项 DPAPI 假值通过；卸载移除注册/程序/所属链接，未知 root 文件保留。没有 Provider 调用。该包不是新修订 manager 的验收证据，尚未发布。

新修订已完成 13 项实际 Windows helper 测试：包括稳定入口按清单验证/委派到每版本 manager、junction 拒绝、真实磁盘可用量门槛、修改快捷方式保留、失败启动/暂存重试/归属及卸载保护。普通新候选及 A→B 升级继续，测试次数不累加成独立方法数。持续起点与未交付事项统一见 [207](207_CONTINUOUS_START_AND_DELIVERY_LEDGER.md)。

修订收尾最终为 15 个唯一 Windows helper 用例全部通过：同包重装只核验并确认、不覆盖已安装版本；NSIS 提取期间稳定入口拒绝新启动，避免启动清理与在途提取竞争。旧程序退役移到卸载登记成功之后，跨 helper 进程保留快捷方式延期状态。之前半写 ACK 和延期状态丢失的实际失败保留本机，不将中间失败轮次计为额外测试。准备冻结新源码并构建普通 1202/1203 升级对。

普通 1202（155,270,753 B / `9d981f4490a054b029cea3d6d9bc03520412d7e290227ccc0ecb736d51d33c7e`）及 1203（155,263,829 B / `9271fabd394f60856764b236e83d25989198b2b8b64a933b570c13735b528ab3`）绑定 `589c6b6f`。真实首装/同包重装/稳定委派/1202→1203 升级、空白默认资料启动、运行中安装拒绝、降级拒绝、正常卸载和精确退役均通过；资料、媒体、bootstrap 和三项 DPAPI 假值独立复核保持。原始代码保护、版本、registry、cwd 和 Job 结果留本机。

故障注入 1204 使 JVM 未就绪，安装器返回2且保留1203指针。首轮自动 Job 清空断言失败，不能计自动退出通过；用户点击原生“Failed to launch JVM”窗口确认后，故障进程已不存在，1203正常重开/资料与三假值回读及1204所属清理通过。没有强制结束任何应用进程；崩溃注入计划被身份守卫拒绝、未执行。用户确认错误窗口不是最终产品验收。

收尾增加自选程序目录与默认/已选资料、凭据及bootstrap根排斥，管理日志迁出程序根到独立 LocalAppData install-state；18 个唯一 Windows helper 用例全过。一个直接负例未继承测试 LocalAppData，仅留下84B合成控制元数据，已核对归属后精确移除；测试夹具修正后2项隔离复核通过、实际用户配置中无该合成控制根。业务数据/凭据未受影响。下一候选冻结后补其真实升级/首装验证，再依 T20 正式安装长期 H 根；不在 H 根试验，最终公开发布仍等真实用户验收。


PR #1 已通过普通merge集成到desktop，提交 `b58485b115b0d1b25740da2803d385e9e37bdcf1`；其文件树与定向验证的aa4928bb一致。无强制/绕过选项。云端Android全组检查[40576758285](https://github.com/MisakaPiano/ChatChatBar-Desktop/actions/runs/40576758285)仍在运行，单独跟踪；本地16项相关测试和配置缓存存储/复用已通过，不能冒称云端全组通过。
