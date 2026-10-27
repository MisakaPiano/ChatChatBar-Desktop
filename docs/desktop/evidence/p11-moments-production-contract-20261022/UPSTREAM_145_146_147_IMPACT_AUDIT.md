# 官方 1.4.5 → 1.4.6 → 1.4.7 备份影响审计

2026-10-22 JST。READ-ONLY SOURCE AUDIT / DOCS-ONLY；不是生产实现授权。Desktop 起点 `bd951d4b5ad4c68908f2cdc842b2238ba0e38fa5`，分支 `feature/phase8-fish-audio`。本目录八份文档是本轮全部改动。

## 权威与证据等级

| 角色 | 精确提交 | 本轮用途 |
|---|---|---|
| validated baseline 1.4.1 | `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8` | 不变，不同步 |
| D-038 目标 1.4.5 | `1198a13cf3008de1358b8a69aa5e40b64797c8d9` | 既有合同、POC 运行依据 |
| 官方 1.4.6 | `9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d` | 新版格式、选择与合并源码审计 |
| 官方 1.4.7 | `b4fa75d296680f777be095f3121ad0c4db979f17` | 固定修复后的格式来源；未验证发行 APK |
| 路径修复 | `81f5449803698a179549530c404e11018f9a62f8` | 声明路径与 canonical source 分离 |

缺失的三个 Git 对象通过固定 SHA 获取，使用 `fetch --no-tags --no-write-fetch-head`，没有更新分支、远端跟踪引用、工作树或正式 baseline。读取 CURRENT 00/10/21/13/14/18(D-037/038/041)/93/95、旧七份设计和 POC；CURRENT 的旧“未批准原型”按历史时点理解，本轮用户已接受限定 POC，但没有批准生产实施。历史文件不改。

状态仅表示：`CURRENT_SUPPORTED`＝当前 Desktop 已有明确限定能力；`SOURCE_PROVEN_ONLY`＝固定源码事实，未运行新版；`REQUIRES_ADAPTER`＝现有实现无法满足，需另批适配；`PENDING_TEST`＝具体运行证据欠缺；`BLOCKED`＝缺合同/安全或授权门槛。本轮没有新增功能测试、GUI、模拟器、物理设备或用户数据操作。

下文 O 编号定位末尾固定源码索引；D 编号定位 Desktop。**事实**与**风险推论/建议**分开陈述。官方测试只阅读，不宣称执行通过。

## 逐项源码事实

| 项目 | 1.4.5 | 1.4.6 | 1.4.7 / Desktop 影响 |
|---|---|---|---|
| envelope/manifest | v1；无 scope | codec 接受 1..2；有 selection 时 create 输出 v2+scope；底层无 selection 仍输出 v1 | 1.4.7 相同；Desktop codec 只收 v1，manifest 严格无 scope，versionCode 上限84（O01/02/04、D01–03） |
| 应用版本 | code84 | base code85 | base code86；即使人为生成 v1/85、86，Desktop 也拒绝，不仅是 envelope 门槛 |
| 导出入口 | 全量 | Service 总是构造 plan（默认 allSelection），因此普通正式导出走 v2 | “底层仍能写 v1”不等于有旧格式 UI 导出选项 |
| 选择范围 | 无 | 全局字段组、卡/会话、Studio、Moments；每会话图/音/存档独立；scope 计数+默认 tuple+稀疏 overrides | v2 缺 scope 拒绝；早期 scope 缺 momentsImages 默认 true；缺详细会话 choice 采用兼容推断，不能自行当 false |
| 导入路径 | 完整替换 | 旧无 scope REPLACE_ALL 直接 incoming；有 scope REPLACE_ALL 和两种增量需 preview，准备完整 prepared 树 | 同义词“替换”不再总是全根覆盖；不能忽略 scope 后用旧恢复 |
| 模式 | 仅原替换 | REPLACE_ALL / UPDATE_EXISTING / ADD_COPIES，详见矩阵与 add-only 合同 | ADD_COPIES 不是严格目标不变模式 |
| 类型登记 | validator 内类型表 | 移入 Registry，40 类含 legacy alias；新增登记 retrieval_model_config | 旧草案对 1.4.5 的未注册判断保留；1.4.6/7 已注册不等于 Desktop 全包已兼容 |
| 资源收集 | 注册根遍历 | selection 的实体和额外文件+实际引用；不扫未选 runtime siblings | 1.4.7 修复 selected v8 包 alias 路径误判；校验 containment 未取消 |
| 历史头像 | 资源缺失较严格 | 仅顶层 moment_posts.senderAvatar 可已缺失，保留缺失引用并警告；其他原图严格 | 不是一般“缺资源可跳过”；封存要新增显式 missing-state 合同 |
| Moments 删除/改卡 | 删除卡有保留逻辑 | 共用 MomentMediaRetention，规范 file URI/canonical alias，改卡亦保留历史媒体 | helper 仅列 senderAvatar/imagePath，未枚举独立 metadata.imagePath；新封存须覆盖三者 |
| 事务 | prepared 固定 | stageDirectory=prepared/incoming 写入 journal，旧 journal 默认 prepared，启动按选择恢复 | Android 事务变化不自动覆盖 Desktop 新封存域 |
| 调度 | runOnce 开关与 due 处理 | 本次范围中 Scheduler 无差异 | 1.4.7 仍无导入任务持久隔离，POC 风险不消失 |

### 依赖、选择与身份

**SOURCE_PROVEN_ONLY：** O07 对所选会话要求其卡；Memory/读位置/输入草稿随 session；图、生成语音+anchor、SaveSlots 独立。所选 SaveSlot 保留其自身 inline/v8 原媒体，不受当前聊天图/音关闭影响。相关 Model/Embedding/FormatCard/WorldBook 自动带入；全局默认未选时，为导出 session 物化显式 modelId/imageModelId/formatCardId。RAG 按 session/card/document 来源选择，Memory 历史属于全备而非 `.cbsave` 当前快照。

聊天图关闭将 images/generatedImageMetadata 投影为 []；Moments 正文图关闭将 imagePath/generatedImageMetadata 投影为 null，仍保留头像、checkpoint、Task。Studio 图关闭使用独立 omitted token 保留 seed/recipe。它们是主动选择的派生输出，不是完整原样档案。仅选择 Moments 并不自动纳入每个 card/session；历史允许缺失与可运行依赖完整必须分别判断。

O08–11：UPDATE_EXISTING 先同 namespace ID，再 trim/NFKC/Locale.ROOT 唯一名称；会话名字匹配限定映射后的卡。双方重名、多候选或重复占用目标转独立副本并提示。Moments/history/tasks 无 ID 匹配时先元数据指纹桶，再完整媒体 SHA 指纹；忽略 root id/updatedAt、格式空白和机器路径，不等于严格全字段同一。ADD_COPIES 为导入主要实体新建 ID/名称；子角色、文档、世界书 entry、消息、RAG、Memory、voice、包内引用一起映射。source-turn、alternative-version、voice-anchor 保持其新会话命名空间内身份，不能全文替换字符串。

Memory 重算父节点 sourceHash/coverageHash 及编码子证明的 coverage-unit；自然语言不改，叶 source-turn 保留。SaveSlot 独立新 ID/包名，流式改 manifest/消息/RAG/voice 身份与证明，媒体 ZIP bytes 不重新编码，更新 byteLength。没有因此证明 Desktop 自有逻辑/物理 ID、语音 receipt 与官方实现等价。

### 凭据与偏好：选择性不等于无凭据

**SOURCE_PROVEN_ONLY：** O14.capture 仅在选 novelAiCredentials/fishAudioCredentials/community 时读取对应 Keystore/会话；imagePreferences 未选仍取得 SharedPreferences handle，但不读取其字段值，使用默认刷子值。restore 对内部 retainedPreferences 对应 XML 跳过，不解密未选秘密；外部归档中非空 retainedPreferences 被拒绝。该集合是内部事务指令，不能相信外来输入。

| 已知秘密 | 新版源端携带条件 | Desktop 决策 |
|---|---|---|
| NovelAI / Fish / community access+refresh | 对应 globalOptions 被选；allSelection 默认全部，lightSelection 也不排除它们 | 未另批 C1/C2 仍整包拒绝非空值 |
| ModelConfig.apiKey（含 retrieval alias） | 模型整条被选；也可能因 session/卡/Studio 依赖自动纳入，即使未选全部 models | 仅取消三个偏好开关不够；需逐实体已知秘密审计 |
| EmbeddingConfig.apiKey | embedding 被选，或 cards/chats 非空时选入相关配置集合 | 同上；不因依赖导出而允许自动绑定目标秘密 |
| AppSettings.siliconFlowApiKey | 所属字段选中时保留，否则投影默认值 | 仍需检查实际输入，不靠选择 UI 证明无秘密 |
| 未知扩展/用户文本 | 选择器不是任意文本秘密检测器 | 不自动重写文本；未知结构影响保证则拒绝 |

C1/C2 沿用旧设计：C1 是主动排除明确类别的有限迁移，保留目标 SecretStore；C2 是另批的加密、多类别、跨数据/秘密域事务。官方 Keystore 的 null 替换清空语义不可套用 Desktop。不能因官方允许明文或加密导出而改变普通 Desktop SecretStore 排除原则。

### 密码与完整性

O02 的 1.4.5→1.4.6 改动只扩展版本参数/检查；PBKDF2-HMAC-SHA256 600000、32-byte salt、AES-GCM 包裹 keyset、Tink AES256_GCM_HKDF_1MB、密码 8..1024、magic+header AAD 保持。版本在加密 header AAD 内，不能编辑 v2 数字假装降为 v1。完整认证/CRC/inventory/重复路径/数量/hash/资源校验仍需完整消费流。源码没有证明 envelope 与 manifest 版本必须相等的显式跨层检查；未来 adapter 应定义并测试一致性拒绝，而非推断已有保障。

### 1.4.7 路径修复

O04 的 1.4.6→1.4.7 生产 diff 仅 4 加/4 删：selectedFiles 从 File 改为 `(File, declaredRelative)`；records 使用 `.relative`，additionalFiles 原二元组保留；非 selection 分支仍从 filesRoot 推导。物理 source 可由 Registry.target canonical 化，原 filesDir 可是 alias，用二者 relativeTo 会产生 `../` 误判。修复没有放宽 archive path 或 target containment。O21 的新 Android symlink fixture 覆盖明文/加密 v8 包字节；本轮未运行该测试。此修复与旧 POC 比较器的 `/data/user/0`/`/data/data` 假设错误相关但不是同一条失败路径，不能声称旧 POC 已验证 1.4.7 修复。

## 新增勘误与风险推论（不改旧证据）

1. 旧 POC 概要没有列明启用后的新未来任务。重读已提交 scheduler-after：原 5 Task 仍有对应记录，另有 **19 个新 PENDING**，总24；旧 pending 转 FAILED 并关联新失败 Post。应表述为“处理旧任务且产生新调度”，不能仅描述状态改变。测试 runOnce 使用合成 now=10000，19 项 scheduledAt 均大于该合成时刻；不是本轮新执行。
2. 旧设计“无源端无凭据开关”和“retrieval 未注册”明确绑定 1.4.5；新版出现按类省略偏好及注册 alias，只新增版本结论，不改旧事实。由于依赖模型密钥可随实体带入，仍没有证明新版提供覆盖所有秘密的统一无凭据档案。
3. O08 把同 namespace/id 的 model 与 legacy alias 排序取 first，并未先证明两份配置全字段一致。**风险推论：** 同 ID 不同端点/密钥的输入可能在官方归一化时丢掉一份；Desktop 严格合并必须冲突预览或拒绝，不能复制该“优先一份”规则当无损依据。尚未运行新版反例。
4. 新版 `MomentMediaRetention` 不单独计入不同的 generatedImageMetadata.imagePath；POC 恰有两处路径不同的合成 Post。不能据 helper 存在声称三处资源所有权已完整解决。
5. 历史 POC 仅闭合参考夹具，不能升级为生产未知结构验证器。新增封存 capability 标记不能使已发布旧二进制自动认识新格式；降级防写需专门方案及旧版本实测。

## 固定源码索引

下表链接绑定 commit，不使用浮动 master。`—` 表示该版本没有此文件。版本之间相同 blob 仍分别给链接，以便复核差异。后续 D 表固定本轮 Desktop 起点；行号以具体源码中的函数定位为准。

| 编号 / 文件 | 1.4.5 | 1.4.6 | 1.4.7 |
|---|---|---|---|
| O01 / app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupModels.kt | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupModels.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupModels.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupModels.kt) |
| O02 / app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupCodec.kt | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupCodec.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupCodec.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupCodec.kt) |
| O03 / app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupService.kt | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupService.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupService.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupService.kt) |
| O04 / app/app/src/main/java/com/example/chatbar/domain/backup/BackupArchive.kt | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/BackupArchive.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/backup/BackupArchive.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/BackupArchive.kt) |
| O05 / app/app/src/main/java/com/example/chatbar/domain/backup/BackupDataRegistry.kt | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/BackupDataRegistry.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/backup/BackupDataRegistry.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/BackupDataRegistry.kt) |
| O06 / app/app/src/main/java/com/example/chatbar/domain/backup/BackupEntityValidator.kt | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/BackupEntityValidator.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/backup/BackupEntityValidator.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/BackupEntityValidator.kt) |
| O07 / app/app/src/main/java/com/example/chatbar/domain/backup/BackupExportSelection.kt | — | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/backup/BackupExportSelection.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/BackupExportSelection.kt) |
| O08 / app/app/src/main/java/com/example/chatbar/domain/backup/BackupMergeEngine.kt | — | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/backup/BackupMergeEngine.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/BackupMergeEngine.kt) |
| O09 / app/app/src/main/java/com/example/chatbar/domain/backup/BackupMergeCatalog.kt | — | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/backup/BackupMergeCatalog.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/BackupMergeCatalog.kt) |
| O10 / app/app/src/main/java/com/example/chatbar/domain/backup/BackupMergeIdentity.kt | — | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/backup/BackupMergeIdentity.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/BackupMergeIdentity.kt) |
| O11 / app/app/src/main/java/com/example/chatbar/domain/backup/BackupMergeMemory.kt | — | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/backup/BackupMergeMemory.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/BackupMergeMemory.kt) |
| O12 / app/app/src/main/java/com/example/chatbar/domain/backup/BackupRetainedMedia.kt | — | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/backup/BackupRetainedMedia.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/BackupRetainedMedia.kt) |
| O13 / app/app/src/main/java/com/example/chatbar/domain/backup/BackupRestoreTransaction.kt | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/BackupRestoreTransaction.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/backup/BackupRestoreTransaction.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/BackupRestoreTransaction.kt) |
| O14 / app/app/src/main/java/com/example/chatbar/domain/backup/BackupPreferenceTransfer.kt | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/BackupPreferenceTransfer.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/backup/BackupPreferenceTransfer.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/BackupPreferenceTransfer.kt) |
| O15 / app/app/src/main/java/com/example/chatbar/domain/moment/MomentMediaRetention.kt | — | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/moment/MomentMediaRetention.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/moment/MomentMediaRetention.kt) |
| O16 / app/app/src/main/java/com/example/chatbar/domain/moment/MomentScheduler.kt | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/moment/MomentScheduler.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/moment/MomentScheduler.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/moment/MomentScheduler.kt) |
| O17 / app/app/src/main/java/com/example/chatbar/domain/backup/BackupHistoricalResourcePolicy.kt | — | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/backup/BackupHistoricalResourcePolicy.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/BackupHistoricalResourcePolicy.kt) |
| O18 / app/app/src/main/java/com/example/chatbar/domain/backup/BackupJsonStream.kt | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/BackupJsonStream.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/backup/BackupJsonStream.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/BackupJsonStream.kt) |
| O19 / app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupBootstrap.kt | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupBootstrap.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupBootstrap.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupBootstrap.kt) |
| O20 / app/app/src/test/java/com/example/chatbar/domain/backup/BackupMergeEngineTest.kt | — | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/test/java/com/example/chatbar/domain/backup/BackupMergeEngineTest.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/test/java/com/example/chatbar/domain/backup/BackupMergeEngineTest.kt) |
| O21 / app/app/src/androidTest/java/com/example/chatbar/domain/backup/BackupSaveSlotCompatibilityTest.kt | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/androidTest/java/com/example/chatbar/domain/backup/BackupSaveSlotCompatibilityTest.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/androidTest/java/com/example/chatbar/domain/backup/BackupSaveSlotCompatibilityTest.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/androidTest/java/com/example/chatbar/domain/backup/BackupSaveSlotCompatibilityTest.kt) |
| O22 / app/app/src/test/java/com/example/chatbar/domain/backup/BackupExportSelectionTest.kt | — | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/test/java/com/example/chatbar/domain/backup/BackupExportSelectionTest.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/test/java/com/example/chatbar/domain/backup/BackupExportSelectionTest.kt) |
| O23 / app/app/src/androidTest/java/com/example/chatbar/domain/backup/BackupDataSelectionTest.kt | — | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/androidTest/java/com/example/chatbar/domain/backup/BackupDataSelectionTest.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/androidTest/java/com/example/chatbar/domain/backup/BackupDataSelectionTest.kt) |
| O24 / app/app/src/androidTest/java/com/example/chatbar/domain/backup/BackupCredentialTransferTest.kt | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/androidTest/java/com/example/chatbar/domain/backup/BackupCredentialTransferTest.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/androidTest/java/com/example/chatbar/domain/backup/BackupCredentialTransferTest.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/androidTest/java/com/example/chatbar/domain/backup/BackupCredentialTransferTest.kt) |
| O25 / app/app/src/test/java/com/example/chatbar/domain/backup/BackupRestoreTransactionTest.kt | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/test/java/com/example/chatbar/domain/backup/BackupRestoreTransactionTest.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/app/app/src/test/java/com/example/chatbar/domain/backup/BackupRestoreTransactionTest.kt) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/app/app/src/test/java/com/example/chatbar/domain/backup/BackupRestoreTransactionTest.kt) |
| O26 / .agents/skills/chatbar-app-backup/SKILL.md | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/.agents/skills/chatbar-app-backup/SKILL.md) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/.agents/skills/chatbar-app-backup/SKILL.md) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/.agents/skills/chatbar-app-backup/SKILL.md) |
| O27 / .agents/skills/chatbar-app-backup/references/incremental-restore.md | — | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/.agents/skills/chatbar-app-backup/references/incremental-restore.md) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/.agents/skills/chatbar-app-backup/references/incremental-restore.md) |
| O28 / .agents/skills/chatbar-save-slot/SKILL.md | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/.agents/skills/chatbar-save-slot/SKILL.md) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/.agents/skills/chatbar-save-slot/SKILL.md) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/.agents/skills/chatbar-save-slot/SKILL.md) |
| O29 / .agents/skills/chatbar-moments/SKILL.md | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/.agents/skills/chatbar-moments/SKILL.md) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/9841e2f216ec6de5f19ecbcdd53e9a0c0ee3ad3d/.agents/skills/chatbar-moments/SKILL.md) | [固定源码](https://github.com/SaltyFishOTL/ChatChatBar/blob/b4fa75d296680f777be095f3121ad0c4db979f17/.agents/skills/chatbar-moments/SKILL.md) |

| 编号 | 当前 Desktop 固定源码 |
|---|---|
| D01 | [backup/AppBackupCodec.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/bd951d4b5ad4c68908f2cdc842b2238ba0e38fa5/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/AppBackupCodec.kt) |
| D02 | [backup/AppBackupModels.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/bd951d4b5ad4c68908f2cdc842b2238ba0e38fa5/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/AppBackupModels.kt) |
| D03 | [backup/DesktopFullBackupArchive.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/bd951d4b5ad4c68908f2cdc842b2238ba0e38fa5/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/DesktopFullBackupArchive.kt) |
| D04 | [backup/DesktopBackupPolicy.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/bd951d4b5ad4c68908f2cdc842b2238ba0e38fa5/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/DesktopBackupPolicy.kt) |
| D05 | [backup/DesktopFullBackupMapping.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/bd951d4b5ad4c68908f2cdc842b2238ba0e38fa5/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/DesktopFullBackupMapping.kt) |
| D06 | [DesktopFullBackupCommitJournal.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/bd951d4b5ad4c68908f2cdc842b2238ba0e38fa5/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopFullBackupCommitJournal.kt) |
| D07 | [DesktopStartupGate.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/bd951d4b5ad4c68908f2cdc842b2238ba0e38fa5/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopStartupGate.kt) |
| D08 | [DesktopDataOperationCoordinator.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/bd951d4b5ad4c68908f2cdc842b2238ba0e38fa5/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopDataOperationCoordinator.kt) |
| D09 | [DesktopDataRootOwnership.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/bd951d4b5ad4c68908f2cdc842b2238ba0e38fa5/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopDataRootOwnership.kt) |
| D10 | [DesktopDataRootMigrationMaterializer.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/bd951d4b5ad4c68908f2cdc842b2238ba0e38fa5/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopDataRootMigrationMaterializer.kt) |
| D11 | [DesktopDataRootMigrationService.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/bd951d4b5ad4c68908f2cdc842b2238ba0e38fa5/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopDataRootMigrationService.kt) |
| D12 | [security/DesktopSecretStore.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/bd951d4b5ad4c68908f2cdc842b2238ba0e38fa5/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/security/DesktopSecretStore.kt) |
| D13 | [security/WindowsSecretStore.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/bd951d4b5ad4c68908f2cdc842b2238ba0e38fa5/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/security/WindowsSecretStore.kt) |
| D14 | [DesktopAutomaticBackupRuntime.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/bd951d4b5ad4c68908f2cdc842b2238ba0e38fa5/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopAutomaticBackupRuntime.kt) |

POC 原件：[PROJECT_HANDOFF](../p11-moments-poc-20261022/PROJECT_HANDOFF.md)。新增Task补充依据：该目录 `raw/20261022-180236-184-pull/artifacts/scheduler-after/entities/moment_tasks/`，只重新读取合成历史证据。
路径修复：[81f5449803698a179549530c404e11018f9a62f8](https://github.com/SaltyFishOTL/ChatChatBar/commit/81f5449803698a179549530c404e11018f9a62f8)。测试定位 O21 `selectedV8PackageExportsThroughPrivateDirectoryAliasWithoutChangingBytes`；O20/O22–25 为相关合并、选择、凭据和事务源码测试，本轮仅阅读。
