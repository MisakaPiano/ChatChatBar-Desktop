# 158 — P11-S2-G1 R07: User-Approved Direct Archive Import, Credential Policy and Password UI Audit

**G0R EVIDENCE ADDENDUM / 2026-10-23:** [159](159_P11_S2_G0R_OWNED_MEDIA_PROJECT_EVIDENCE_ACCEPTANCE.md) has qualified acceptance for synthetic media-deletion/backup observations only; it does not alter the user's approved R07 **normal protected direct import** goal or authorize removing current `CREDENTIAL_APPROVAL_REQUIRED`. Project [160](160_P11_S2_G1_INTEGRATED_PRESERVATION_AND_CREDENTIAL_SAFETY_BLUEPRINT.md) sets the proposed cross-domain safety architecture; test-only [161](161_P11_S2_G1A_SECRETSTORE_TRANSACTION_PROBE_CODEX_CONTRACT.md) examines current per-key vs cross-data restore guarantees before any production change.

**CURRENT 2026-10-23 JST — USER-APPROVED PRODUCT DIRECTION / PRODUCTION IMPLEMENTATION NOT AUTHORIZED.**

## 1. 用户最新明确决定（取代 157 的 R07 未决项）

本次用户在 S2-G1 R07 讨论中明确提出：

- **R07 来源档案托管：选择 A。** Desktop 不自动复制/托管用户选择的整份原始 `.cbbackup`；原始文件归用户保管，读取/验证/安全导入不得修改原始源文件。此项不同于 [157](157_P11_S2_G1_USER_CONFIRMED_PARTIAL_POLICY_DIRECTIONS.md) 中已经批准的 **S2 封存 Post/Task 原 JSON + 独立媒体副本**；不能以“不托管来源 ZIP”为理由丢失已接受的封存数据。
- **R07 直接导入标准：** 用户认为备份中的 API Key/Token 是用户自己主动导出的数据；**不能仅因为档案包含外来已知凭据而整包拒绝**。目标是让用户主动选择的官方 `.cbbackup` 完成正常数据和凭据的安全直接导入。整包拒绝应依据实际无法安全整合、认证/格式/完整性错误、资源/事务/容量等具体不兼容事实，而非“含凭据”这个事实本身。
- **R07 C2 方向：** 用户选择 C2 作为后续完整凭据迁移/账号映射方案；**当前优先目标仍是直接导入**，不能将“以后开发完整 C2”当成长期阻止普通官方备份迁入的理由。必要的初版 Windows 受保护凭据写入与数据/密钥一致性必须作为直接导入的安全实现工作单独设计、验证，而非 C1 删除凭据或无保护明文落盘。
- **R07 C1 定位：** C1 主动排除凭据可作为将来用户自主选择的有限变体，但**不是**正常全包迁移的默认前置条件；不得为了让测试通过自动删除凭据或将“排除后有限迁移”表述成原样完整备份导入。
- **外发备份：** 用户额外指出官方含 API 的 `.cbbackup` 可以设置密码。当前 Desktop 已存在加密导出与密码输入；是否在普通 Desktop 导出中加入“**显式选择携带受保护凭据的加密档案**”需在独立设计中明确授权与验收。**此前 D-037/D-038 的 ordinary shareable Desktop export 默认排除 SecretStore 继续有效**，不能把对“导入”的批准暗中扩展为“默认明文导出含密钥”。

## 2. CURRENT 实际源码核查（产品目标与实现不可混淆）

| 项目 | 现有生产事实，固定 feature `eca86192c8b5ab9df8ac7779fc0217f2ddde252a` | 当前不具备的能力 |
|---|---|---|
| 密码输入 UI | [DesktopFullBackupPanel.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/eca86192c8b5ab9df8ac7779fc0217f2ddde252a/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopFullBackupPanel.kt)：Compose `BasicTextField` + `PasswordVisualTransformation`；同框支持加密导出、选择备份检查，密码最长 1024 字符，导出非空时至少 8 字符。 | 未提供按 envelope 加密状态分支的单独恢复口令向导/错误重试 UX；GUI 仍连接现有 `Prepared` 而非 S0/S1 的 v1/v2 bounded read-only 分类。 |
| 密码流 / 加密 | [AppBackupCodec.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/eca86192c8b5ab9df8ac7779fc0217f2ddde252a/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/AppBackupCodec.kt)：官方兼容 PBKDF2-HMAC-SHA256/600000、AES-GCM + Tink Streaming AEAD；`decrypting` 当前正式恢复只接受 v1，独立 `decryptingForInspection` 可读 v1/v2。 | v2 可解密/inspect **不等于** v2 可恢复；目前 S0/S1 新检查器尚未连 GUI；无需重写算法来完成凭据迁入。 |
| 正式恢复密码 | [DesktopFullBackupController.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/eca86192c8b5ab9df8ac7779fc0217f2ddde252a/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopFullBackupController.kt)：`inspect(password:CharArray?)` 传给 Service/Archive，操作后清零该 `CharArray`；这是受保护**口令缓冲的一项处理**，不等于可确保 Compose `String` UI 堆内存已安全抹除。 | 不等于外来 SecretStore 迁移、凭据冲突处理或跨域原子提交；密码正确只解决解密认证。 |
| 当前整包拒绝来源 | [DesktopFullBackupArchive.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/eca86192c8b5ab9df8ac7779fc0217f2ddde252a/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/DesktopFullBackupArchive.kt) 对非空 `preferences.novelAiToken/fishAudioKey/communitySession` 抛出 `CREDENTIAL_APPROVAL_REQUIRED`；[DesktopBackupPolicy.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/eca86192c8b5ab9df8ac7779fc0217f2ddde252a/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/backup/DesktopBackupPolicy.kt) 对已知 Model/Embedding 等 `apiKey` 类字段也拒绝非空输入。 | 这两条是**当前实施门槛**，新用户批准了“将来安全直接迁入”的目标，**不是本次 docs 批准立即删除安全检查**。必须先实现有证据的安全 sink/回滚，再改变准入。 |
| OS 保护存储 | [DesktopSecretStore.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/eca86192c8b5ab9df8ac7779fc0217f2ddde252a/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/security/DesktopSecretStore.kt) 定义 NovelAI、Fish、SiliconFlow、Model、Embedding 等逻辑 key；[WindowsSecretStore.kt](https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/eca86192c8b5ab9df8ac7779fc0217f2ddde252a/app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/security/WindowsSecretStore.kt) 使用 Windows DPAPI 与原子写 blob。 | `DesktopFullBackupService` 未连接外来 archive 凭据完整映射/事务；既有 DPAPI storage 不自动证明了跨根迁入/源账号与模型 ID 绑定/多密钥回滚。 |

## 3. 直接导入需要兑现的精确行为（候选工程合同）

1. 用户明确选定来源档案（明文或密码保护）；需要密码时通过现有或改良的密码输入 UI 解密并完整认证。密码只作为解密输入，**不视为可忽略原档资源/schema/身份/秘密一致性检查的万能许可**。
2. 导入预览识别实际将导入的设置、模型、Embedding、NovelAI/Fish 和其他已知凭据**类别/数量/目标绑定关系**，默认不显示 Token 值；**备份含凭据本身不构成错误**。能够验证无冲突时按用户发起的整体迁入执行；源目标账号不匹配、已有凭据冲突、未知 Secret 意义需要明确决策或阻断，不可偷偷绑定其他现有账户。
3. 导入的密钥必须写入适当 Windows OS 保护存储，不得作为长期普通明文 JSON、日志、诊断或可分享自动导出；必须处理源档存在但目标 Windows 密钥 root 不可用、DPAPI failure、恢复中断和不同 Windows 用户/Portable 环境。
4. 数据变更、被映射密钥写入、模型引用和会话配置必须有**可恢复的一致性事务**：只有整个已声明导入范围及凭据归属完整才可显示成功；失败/取消/中途重启不能留下仅数据换了或仅密钥覆盖了的状态。原目标有效数据/Key、原来源档与恢复证据应保留。G0 未证明该跨域性质。
5. 如果只因格式、未知来源结构、无法表示的 Entity/依赖、空间不足、安全持久化失败或无法解决的实际账号冲突而不能保证**整合完成**，可以明确解释具体阻断并整包安全拒绝；不能发 `CREDENTIAL_APPROVAL_REQUIRED` 仅因为输入包含用户自己的 Key。
6. 完整 C2 逐账号交互、导入与现有密钥冲突的高级迁移 UI、可撤销激活/多账户 profile 等继续是**后续独立能力**。当前的“标准直接导入”必须先交付一个已证实的安全默认映射与所有权，不得借“未来 C2”降低初版保全和 SecretStore 安全标准。

## 4. 现行 HOLD 与下一步

- 此份登记是**USER-APPROVED R07 PRODUCT DIRECTION**，不是批准立即修改 `AppBackupCodec`、`DesktopFullBackupArchive`、`DesktopBackupPolicy`、`DesktopSecretStore` 或 UI/transaction；生产前先独立审计当前凭据引用归属、旧数据 root / DPAPI / backup/restore transaction 与各官方版本 secrets 兼容，再冻结允许的最小代码切片与测试。
- [157](157_P11_S2_G1_USER_CONFIRMED_PARTIAL_POLICY_DIRECTIONS.md) 中 R01/R02/R03 用户同意保持；**R07 不再标为待用户选择**。G0R [156](156_P11_S2_G0R_OWNED_MEDIA_AND_CLEANUP_AUDIT_CODEX_CONTRACT.md) 仍为 test-only，不自动扩大到凭据写入。
- S2-G1 架构/实现细节尚未完成，S2-G2 生产持久域与 SecretStore 跨域事务仍未批准；官方完整 D-038 全应用迁移及 P11 总体验收、Post-P8 HOLD、Public Preview A–G、formal upstream baseline1.4.1/未合并 `desktop` 均不变。

**历史 R07“含凭据整包拒绝”为当前旧代码事实及原历史安全控制，不再是用户批准的最终产品目标；不得未经安全适配就直接移除检查。**
