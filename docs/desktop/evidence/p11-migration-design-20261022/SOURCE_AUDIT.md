# P11 迁移设计源码审计

状态：DRAFT / DESIGN + PREPARATION ONLY。日期：2026-10-22 JST。

## 身份与方法

- Desktop 控制提交：`8c378e8cf487f6dbcb0df3f3a6f1408028185091`，`feature/phase8-fish-audio`。
- 官方备份研究目标：`SaltyFishOTL/ChatChatBar` 1.4.5 @ `1198a13cf3008de1358b8a69aa5e40b64797c8d9`。
- 正式 upstream baseline 仍为 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`。未同步或升级 baseline。
- 以下源码直接读取本地精确 Git object；SHA-256 对 Git blob 原始字节计算，不做换行归一化。链接锚点用于定位入口，不代表只审计这一行。O 为官方，D 为 Desktop。没有读取任何运行中凭据、正式用户根或物理设备。
- 本地 Moments 技能与官方技能不完全相同；行为事实以 O01–O23 为准。遵循本地 backup、save-slot、emulator-test 技能定位入口，但本任务明确禁止执行构建、安装及设备诊断。

## 审计事实与推论边界

| 依据 | 核实事实 | 本草案的推论/约束 |
|---|---|---|
| O01–O04/O08 | Post 保存历史 sender 快照、图片和生成元数据；删除角色保留历史 post，只删 pending task；session 删除另有媒体清理 | 封存必须独立保全所有权，不能复用 live 清理器并假定安全；缺 card 不自动等于损坏，但需要明确历史准入规则 |
| O02/O03 | Post/Task 无单独 schemaVersion；Task 可处于 pending/running 且 postId 可空 | 新 sidecar 需要自己的格式/验证器版本；封存状态不得变成任务执行权限 |
| O05/O07 | registered resource fields + 特定资源 map keys 才映射；metadata/extensions 有排除；duplicate key 拒绝 | 任意字符串全文替换会改变内容，任意未知字段直通又不能证明资源闭包 |
| O06 | 官方实体解码 ignoreUnknownKeys=true，有明确类型白名单和 ID/资源检查 | 同名实体可解码不是未知字段无损证明；不能依赖宽松 decode/encode 做保全 |
| O09 | 归档 inventory 记录路径/长度/SHA；导出将登记字段路径 token 化，可物化外部资源；恢复将 token 映射到目标路径 | “原始 bytes”应区分原档案和导出前本地 Entity。未物化外部路径不能让 Desktop 探测本机文件补齐 |
| O10–O12 | prepare→seal→schedule→bootstrap；偏好恢复先于依赖初始化，初始化失败回滚；官方导入重置 momentsAutoStartConfirmed | 无法据此证明封存 task 回注后永不调度；不能把现成 Android 事务当成 Desktop 新域事务 |
| O10/O13–O16 | 正式导出 capture 解密 NovelAI/Fish，并捕获 community session；只配置密码，没有源端排除开关；null restore 会清空对应 Android secret | 无凭据导出不是当前官方功能；Desktop “排除”必须定义成不写、不删目标，而不是套用 null restore |
| O18–O20 | 社区 access/refresh token、Model/Embedding apiKey、AppSettings siliconFlowApiKey | 凭据分类须覆盖 preferences 和 Entity，不能只去掉两个音图 token |
| O17/O09 | 官方可选择明文或加密容器；官方 extract 会落盘 payload.zip/preferences | 加密输入不等于无明文暂存，Desktop 新秘密路径需要独立安全设计 |
| D01–D03 | 严格类型/字段拒绝；已知秘密导出排除、外来非空拒绝；Desktop 偏好 carrier 导出为正式 preferences；retrieval/background 专有 | 当前完整 Android 回传仍有 blocker；两实体子集不代表全包 |
| D04/D05 | root 锁下安排恢复，下一启动 repository 前恢复；old/new 证据、未知外部内容拒绝 | 封存或秘密新域需要事务扩展，不得假定现有 journal 已涵盖 |
| D06–D09 | SecretStore 逐 key 接口，DPAPI、独立 LOCALAPPDATA root；没有社区会话目标类型 | C2 是新的跨域/多 key 协调合同；C1 也须防止新模型 ID 意外绑定旧 secret |
| D10/D11 | root switch 有快照、复制核验、权威切换与回滚机制 | 新保全容器必须进入这些生命周期验收，不能当缓存忽略；本轮未证明未来 sidecar 可直接使用现有实现 |
| O21/D15–D17 | 官方 applicationId 为 com.example.chatbar；此前是官方源码 debug + AVD，只有最小 cbbackup 子集证明 | 本地 debug 不等于官方发行签名，更不等于物理设备包安全；不能不核查就安装 |
| D12–D14 | D-038 增加全应用迁移要求，秘密迁移需另批，未知数据不得静默丢弃 | C1/子集只能有限兼容；条件性技术可行不等于 P11 或 Gate 通过 |

### 未解决的审计/执行条件

- 固定 Moments checkpoint/嵌套负载的全部资源语义、远程资源可用性、合法孤儿集合、目标任务防自动激活仍需独立实现前证明；本轮不据粗略字段列表放行。
- 官方发行 APK 签名链、物理设备隔离及所用 Android 用户的包共享行为尚未核验；不从源码推断真机执行条件已满足。
- 未来 schema、P13 消费合同、新 sidecar 的全量导出表示及跨 SecretStore 事务均未实现。本草案的条件性方案不新增生产保证。
- 只核实当前官方正式导出流程没有凭据排除开关，不声称所有未来官方版本均无此能力。

### 工作范围与文档验证

本轮仅读取固定源码和既有合成证据，新增本目录七份 Markdown。没有运行 Gradle/ADB/模拟器/应用或自动化功能测试；没有改 Kotlin、Package/Entity/schema、Prompt、SecretStore、backup 行为或历史报告。文档交付检查限源码 blob 身份、链接/锚点、必要章节和 Git 路径范围，不作为运行时测试 PASS。

技能相关性已复核：现行 Moments/backup/save-slot/emulator-test 技能描述仍适用于当前实现；设计没有获批或落地，不将草案写进技能作为现行行为。四份既有用户文件继续 KEEP / UNREAD / UNHASHED / UNMODIFIED / UNSTAGED。

## 固定源码索引

| ID | 固定提交源码 | 入口行 | Git blob SHA-256 |
|---|---|---:|---|
| O01 | [.agents/skills/chatbar-moments/SKILL.md](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/.agents/skills/chatbar-moments/SKILL.md#L97) | 97 | `c00b3b9d851c61b00f0271caa0c44dfaf0ef9eca89e979b2552e9e1734a770d7` |
| O02 | [app/app/src/main/java/com/example/chatbar/data/local/entity/MomentEntities.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/data/local/entity/MomentEntities.kt#L8) | 8 | `3280ba03073e9f20f86cf730dc08074f695d66a13369718382c0fb6d047adfbb` |
| O03 | [app/app/src/main/java/com/example/chatbar/data/repository/MomentRepository.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/data/repository/MomentRepository.kt#L12) | 12 | `d5002cfb52894b818b8d2c19ab6037d91813b095e5924cc1bd27f2cd9ebd0a2b` |
| O04 | [app/app/src/main/java/com/example/chatbar/data/local/entity/ChatMessage.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/data/local/entity/ChatMessage.kt#L47) | 47 | `15e677dcecf3205170b1dd3d5c8d1da018e427c77a031bbe1121949e0fe9126d` |
| O05 | [app/app/src/main/java/com/example/chatbar/domain/backup/BackupDataRegistry.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/BackupDataRegistry.kt#L6) | 6 | `470603ac9ca78d5d91fb2f5fee6a33f726c52f49a9310542def83f3a63b206a2` |
| O06 | [app/app/src/main/java/com/example/chatbar/domain/backup/BackupEntityValidator.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/BackupEntityValidator.kt#L17) | 17 | `3a8673d8e2531945c57adbe48b103b706f754e8a4ce42af956a08d32c63c72e0` |
| O07 | [app/app/src/main/java/com/example/chatbar/domain/backup/BackupJsonStream.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/BackupJsonStream.kt#L11) | 11 | `ce61c9767c56945baf5a25c68faae8f1ffc935c5f04ee2a93f9423129d4d5e8d` |
| O08 | [app/app/src/main/java/com/example/chatbar/domain/deletion/DeletionCoordinator.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/deletion/DeletionCoordinator.kt#L91) | 91 | `d60fac3c68b93f6c7d2dd6aba29d2815888d7b35cc51e576ef6bb195a9770d3e` |
| O09 | [app/app/src/main/java/com/example/chatbar/domain/backup/BackupArchive.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/BackupArchive.kt#L26) | 26 | `1fe39d8597e1b5773625c3cc05e99dae544b32cae0e3554c3aa3a37b9a545f7b` |
| O10 | [app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupService.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupService.kt#L114) | 114 | `1a2721a862cf47ae09952bf545ce34a2a310a7fe11ac223119673693a054cd7b` |
| O11 | [app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupBootstrap.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupBootstrap.kt#L24) | 24 | `67c30148d65ea999192989e0048cd69bdc19b6d946c6877fbc1f052e118bcff3` |
| O12 | [app/app/src/main/java/com/example/chatbar/domain/backup/BackupRestoreTransaction.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/BackupRestoreTransaction.kt#L46) | 46 | `2043df77459663a60b061faf5241ace9ba6d5c5377fb438b4efe80be44f0880f` |
| O13 | [app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupModels.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupModels.kt#L35) | 35 | `143aad7e31a86d3034ecdf10acb5db2bf925a755e361d60c890848d4914ec9de` |
| O14 | [app/app/src/main/java/com/example/chatbar/domain/backup/BackupPreferenceTransfer.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/BackupPreferenceTransfer.kt#L15) | 15 | `db2ac24d4b511a31deb5eb45a594b5c3abc83c9de11f2d234a41edd223836a51` |
| O15 | [app/app/src/main/java/com/example/chatbar/data/security/NovelAiCredentialStore.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/data/security/NovelAiCredentialStore.kt#L23) | 23 | `5a66293510edfd8f124298b97d6c17aed60c7ae7fbab5ffd2a377c8554dd213d` |
| O16 | [app/app/src/main/java/com/example/chatbar/data/security/FishAudioCredentialStore.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/data/security/FishAudioCredentialStore.kt#L23) | 23 | `162ad27784bd0d7191b159ad4f16cd96a6c24fdc16b1b969f744e8d895a5c17e` |
| O17 | [app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupCodec.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupCodec.kt#L27) | 27 | `216940b7d2b080ec1ffccd1e7792f90edc90f7cdc8195056050f5fade08ae8fa` |
| O18 | [app/app/src/main/java/com/example/chatbar/domain/community/CommunityModels.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/domain/community/CommunityModels.kt#L130) | 130 | `7d1aa02684494c9c8790d1cf0fe787aeeb777199d510da566bd480656d66a3be` |
| O19 | [app/app/src/main/java/com/example/chatbar/data/local/entity/ModelConfig.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/data/local/entity/ModelConfig.kt#L66) | 66 | `47d5b404212da2a64dadf4bda683b0ac04345bbb768a5dd7781b48a79d63429c` |
| O20 | [app/app/src/main/java/com/example/chatbar/data/local/entity/AppSettings.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/data/local/entity/AppSettings.kt#L18) | 18 | `2825f2b758e1f72c8757e89794a80c1e0e672f7ac059d32289623a59930993b4` |
| O21 | [app/app/build.gradle.kts](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/build.gradle.kts#L23) | 23 | `a0a31a8e693f156462bf0b90d51c6ac9e40cdc5a7b71a264b9d2cc59c7f81929` |
| O22 | [app/app/src/main/java/com/example/chatbar/data/local/entity/SaveSlot.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/data/local/entity/SaveSlot.kt#L12) | 12 | `ddefb887e5aa50b6d8a9bf76c73f1d65bc25193b20b046f8566119fbd2699c3d` |
| O23 | [app/app/src/main/java/com/example/chatbar/ui/chat/ChatViewModel.kt](https://github.com/SaltyFishOTL/ChatChatBar/blob/1198a13cf3008de1358b8a69aa5e40b64797c8d9/app/app/src/main/java/com/example/chatbar/ui/chat/ChatViewModel.kt#L4340) | 4340 | `75b0fd576c091394f2676a35296b335f3126f911568ac3fc1119238660572eb4` |
| D01 | [app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/DesktopBackupPolicy.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/8c378e8cf487f6dbcb0df3f3a6f1408028185091/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/DesktopBackupPolicy.kt#L25) | 25 | `786b1e62d86dc0086e5a4ad91d84102aab304053cb3bb765ac3ee068b841e603` |
| D02 | [app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/DesktopFullBackupMapping.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/8c378e8cf487f6dbcb0df3f3a6f1408028185091/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/DesktopFullBackupMapping.kt#L111) | 111 | `80e50ae1340f22c556aecdb51374607aaf4eccd1bd6802dc0cfc3229bc220104` |
| D03 | [app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/DesktopFullBackupArchive.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/8c378e8cf487f6dbcb0df3f3a6f1408028185091/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/DesktopFullBackupArchive.kt#L14) | 14 | `8f69c76d0bf5647422f2523b684a994ef83545d2bc6d1ae5ce7429f28d0a4524` |
| D04 | [app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopFullBackupCommitJournal.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/8c378e8cf487f6dbcb0df3f3a6f1408028185091/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopFullBackupCommitJournal.kt#L20) | 20 | `3c6809a0f0ba98b0a7c2d5f5ab0843163190f0aeb4e64685131f490d028ed73b` |
| D05 | [app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopStartupGate.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/8c378e8cf487f6dbcb0df3f3a6f1408028185091/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopStartupGate.kt#L44) | 44 | `0a601d98931b7fffa90c9acbdd7e83f7868e6217a1fe10b0ed0396b23c4ab072` |
| D06 | [app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/security/DesktopSecretStore.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/8c378e8cf487f6dbcb0df3f3a6f1408028185091/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/security/DesktopSecretStore.kt#L3) | 3 | `76b5ebae7f79648c1dd37a0fbb1d2d92b9a17d1f97b76861f4b2905a4028dde2` |
| D07 | [app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/security/WindowsSecretStore.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/8c378e8cf487f6dbcb0df3f3a6f1408028185091/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/security/WindowsSecretStore.kt#L16) | 16 | `68f83df55fa3280ab258c2587f922c4ff3286d3b313d16d013c1243c8b100fa8` |
| D08 | [app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/security/WindowsDpapiSecretProtector.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/8c378e8cf487f6dbcb0df3f3a6f1408028185091/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/security/WindowsDpapiSecretProtector.kt#L11) | 11 | `8986343c9193acb570f5adae4ce58572b14b6a8d16671504a858dab241c1c0a4` |
| D09 | [app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/security/WindowsSecretRootResolver.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/8c378e8cf487f6dbcb0df3f3a6f1408028185091/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/security/WindowsSecretRootResolver.kt#L6) | 6 | `6c406060d8579a615d0509b5a77f852744fc3cde6028709b4a3213301a21bb63` |
| D10 | [app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopDataRootMigrationMaterializer.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/8c378e8cf487f6dbcb0df3f3a6f1408028185091/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopDataRootMigrationMaterializer.kt#L122) | 122 | `4f3dba5744e6c64a3410e6f902542e74cc3b7eeded89217c8323da48e4d5d229` |
| D11 | [app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopDataRootMigrationService.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/8c378e8cf487f6dbcb0df3f3a6f1408028185091/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopDataRootMigrationService.kt#L104) | 104 | `980bb5e70c048954226b08e2c70a5c090ffcf6d9b959a00ecd5b6b0947ede8d9` |
| D12 | [docs/desktop/95_PREVIEW_FULL_APP_BACKUP_PORTABILITY_GATE.md](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/8c378e8cf487f6dbcb0df3f3a6f1408028185091/docs/desktop/95_PREVIEW_FULL_APP_BACKUP_PORTABILITY_GATE.md#L34) | 34 | `84a6c7fe04fa3c33c836b8952a109ee4c8df7d229a24f0ea97fd41abbef04fa7` |
| D13 | [docs/desktop/18_DECISIONS.md](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/8c378e8cf487f6dbcb0df3f3a6f1408028185091/docs/desktop/18_DECISIONS.md#L470) | 470 | `0df6b39eb78b54705e15eb31bc004d576e550d5c159410cb4bff0291cd35e2e9` |
| D14 | [docs/desktop/93_PUBLIC_PREVIEW_POLICY.md](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/8c378e8cf487f6dbcb0df3f3a6f1408028185091/docs/desktop/93_PUBLIC_PREVIEW_POLICY.md#L1) | 1 | `06be525431f766d916243937fec78185a1d2af50e535584618a0858bba8b65b0` |
| D15 | [docs/desktop/evidence/p11-interop-20261022/PROJECT_REVIEW.md](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/8c378e8cf487f6dbcb0df3f3a6f1408028185091/docs/desktop/evidence/p11-interop-20261022/PROJECT_REVIEW.md#L1) | 1 | `0471e2889c83b71087c3cc12da576f2ed58f0764e5924649c23c2d0f9a74e9af` |
| D16 | [tools/desktop/interop/Official145InteropTest.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/8c378e8cf487f6dbcb0df3f3a6f1408028185091/tools/desktop/interop/Official145InteropTest.kt#L1) | 1 | `ae4190f653fe25c100d58850bbb542d144a71f0b0cb9bdfbc4f510759ab7d7d7` |
| D17 | [tools/desktop/interop/verify_roundtrip.py](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/8c378e8cf487f6dbcb0df3f3a6f1408028185091/tools/desktop/interop/verify_roundtrip.py#L1) | 1 | `f1465e0af78a0559f0b5d151b2c8c265aafbd5916f5dc3ed442d5451042f5ee0` |
