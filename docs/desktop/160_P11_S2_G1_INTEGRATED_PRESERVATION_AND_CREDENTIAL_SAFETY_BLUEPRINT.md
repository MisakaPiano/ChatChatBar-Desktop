**G1A EVIDENCE / 2026-10-23:** [162](162_P11_S2_G1A_CREDENTIAL_TRANSACTION_PROJECT_ACCEPTANCE.md) closes 12-suite/112-test synthetic boundary review with second-key failure A_NEW/B_OLD, root journal separate SecretStore writes, credential-bearing formal restore rejection, and tested identity gaps. [163](163_P11_S2_G1B_UNIFIED_DATA_SECRET_TRANSACTION_DESIGN_GATE.md) extends this blueprint with a candidate recoverable multi-participant intent and cross-root secret lock/generation semantics; neither is yet an implemented or approved persistent contract. [164](164_P11_S2_G2A_BOUNDED_IMPLEMENTATION_CODEX_CONTRACT_DRAFT_NOT_AUTHORIZED.md) remains HOLD.
# 160 — P11-S2-G1 Integrated Preservation, Secrets and Recovery: Project Safety Blueprint

**CURRENT DESIGN SYNTHESIS — 2026-10-23 JST — USER PRODUCT PRINCIPLES APPROVED / PRODUCTION CODE CONTRACT NOT AUTHORIZED.**

依据：[157 R01/R02/R03 用户方向](157_P11_S2_G1_USER_CONFIRMED_PARTIAL_POLICY_DIRECTIONS.md)、[158 R07 用户方向与口令源码核查](158_P11_S2_G1_R07_DIRECT_IMPORT_PASSWORD_AND_CREDENTIAL_POLICY.md)、[151 S1 只读图](151_P11_S1_MOMENTS_READONLY_GRAPH_PROJECT_ACCEPTANCE.md)、[154 G0 生命周期](154_P11_S2_G0_ROOT_BACKUP_RECOVERY_PROJECT_EVIDENCE_ACCEPTANCE.md)、[159 G0R 删除/媒体](159_P11_S2_G0R_OWNED_MEDIA_PROJECT_EVIDENCE_ACCEPTANCE.md)、D-037/D-038/95。优先级上正式 upstream 所选版本代码仍高于此设计；没有改变 CCB Package、Entity、最终 Prompt/API message 三层语义。

## 1. 对最终用户承诺的目标与当前实际差距

| 能力 | 用户批准的产品目标 | 当前支持/阻断 |
|---|---|---|
| Android → Desktop `.cbbackup` | 用户选择自己导出的文件，密码可解密时全量验证；可正确整合的数据和用户自有 API Key 应安全**直接导入**，不能仅因凭据存在拒绝 | GUI `DesktopFullBackupPanel` 已有密码框，legacy v1 Archive 仍拒凭据，S0/S1 只读支持限定 v1/v2；DPAPI 存储有能力，但跨域事务未连接 |
| Moments Post/Task | **C-A 原样持久封存，原 JSON bytes、所有三路径+独立媒体副本，不调度 Task** | S1 只有内存来源图，未注册 Desktop durable seal；完整 Android 运行回导仍需另立能力 |
| 数据删除/空间 | **没有用户主动删除就不删封存；空间不足不牺牲已有内容** | 无批准的容量配额/峰值/故障恢复合同；普通 live GC 不懂 sealed manifest |
| 数据根/降级 | **旧版不能不安全写新版数据根**，保留迁移前旧根/可恢复快照 | 当前 Startup/Portable 允许 unknown payload；旧 binary 直接写新根没有防护实测 |
| 全应用备份/恢复 | Seal 纳入正式、有明确范围的 whole-app backup 与 crash-safe recovery | 现有 Registry 未认识新域；未知 top-level 被拒，hidden media 可能静默漏；现有 Journal 不处理新域/SecretStore 的共同事务 |
| 密钥输出 | 默认可分享 Desktop 备份继续**不带 SecretStore** | 如要显式密码加密且携钥的可选导出，必须另签内容、密码/加密/身份和导出策略；用户本轮未直接批准该输出模式 |

## 2. 暂定实现结构（蓝图，非 schema 批准）

**Source**：`SourceEnvelope` 是由用户自己保管的原 `.cbbackup`；只在当前明确选择的有界操作中读取。可以有属于本次操作生命周期的加密输入暂存及内存解密，但不能**自动永久托管**来源包、不能将凭据跟包写入普通可分享备份/日志。输入密码只授予解密验证，不自动授予覆盖目标账号的权力。

**Read-only verified graph**：复用 S0 `ReadOnlyInspection` 与 S1 `MomentArchiveReadOnlyValidator`，保留来源 archive digest、每条原记录 bytes 的 digest/length、版本、scope 投影、未知家族/资源/缺头像限制；校验失败不能进入准备提交。S0 64 MiB/ZIP32 上限仅是既有 inspect 范围，不能直接作为生产容量政策。

**Seal candidate**：建议独立**非隐藏、正式注册且可枚举**的版本化 archive-seal 根；内部是不可变 raw JSON、独立物理 copied media 与可验证来源/拥有权 manifest。源 `(archiveDigest,type,sourceId)` 命名空间与引用闭包必须一致；资源完整性以 hash+length 证明。**确切目录名、schemaVersion、是否纯文件或 JVM-neutral shared-core、容量上限、保留数均未批准**；不能借用旧历史版本常量。Sealed Post/Task 不被作为 live MomentRepository/Task Queue 提供者。

**Credential import candidate**：来源 `preferences.json`、AppSettings、ModelConfig、EmbeddingConfig 等经固定官方模型/契约识别用户 Key，仅报告 category/目标配置/是否冲突，不回显 secret 值。Windows 目标使用已有 `DesktopSecretStore`/`WindowsSecretStore` DPAPI 持久保护，模型/embedding ID 和 provider endpoint 映射要在操作确认前冻结；全局/逐模型/单例 key 不可互相覆盖或按名称猜测。**正常直接导入默认保留可安全映射的凭据，不自动剔除**；如出现不可证冲突/密钥持久化不可用，按具体不兼容拒绝整个尚未提交操作，不以“含 Key”泛化拒绝。

**Transaction candidate**：先完整认证、读取/裁剪被允许的作用域、验证容量、分配仅本事务 owned scratch/原始字节/密钥映射，再由 RootOwnership 与 OperationCoordinator 取得独占、完成迁移前 snapshot；冻结并独立验证 `old-target-data`、`old-encrypted-secret-blobs` 的恢复凭据/身份及新目标的 staging，形成可验证同一 commit intent。持久 journal 和 startup pre-business recovery 必须对**数据文件/Seal/受保护 SecretStore 修改**形成统一状态机，`ALL_OLD` 或 `ALL_NEW` 两种可用态；包括 crash-after-first-secret、crash-after-data-commit、cancel、DPAPI failure、target secret conflict、占用和 root switch。不能仅调用 N 次 `SecretStore.save()` 然后调用现有 `DesktopFullBackupCommitJournal.schedule()`，因为分别原子并不等于整体原子。

**GC and deletion**：封存媒体建议物理独立于 live `images/` 和 `documents/` 资产，即使 `DesktopCharacterResourceStore.deleteOwned` 被合法触发也不删 seal 媒体。PendingDeletion Android queue 真链在 G0R 没有运行证据；必须以正确版本的来源/所有权/调度合同补足，或让未识别队列无法写 seal。目录名藏到 `images/.hidden/` 会导致完整 `.cbbackup` 导出静默遗漏，严禁作为正式解决方案。Seal 删除（若未来提供）需要用户明确操作、可恢复凭据、共享资源引用、snapshot/rollback policy 和事务。

**Downgrade/root**：建议 side-by-side root generation / 可还原旧 snapshot；然而新版本能力标记、改路径、新 Launcher 弹窗都**不能**约束用户手动启动旧 exe 打开新根。投产前必须提出并验证强制隔离/拒写手段、旧程序直开/Portable 的实际适用范围与无法覆盖时的 release block；G0 未执行旧程序，不能签此项。

## 3. 尚未冻结/必须在真实实现前解决

1. **持久 schema/API**：封存的字节格式、身份/资源引用、Unknown 分类、资源所有权、已完成事务 manifest、任何可逆迁移的 version negotiation。
2. **容量/保留**：可用空间、峰值、多份快照/owned bytes 的界限；容量不足 fail-closed、已有数据从不自动清除；普通快照保留与 Seal 用户数据保留的区别。
3. **后端一致性**：SecretStore 当前逐 key `save/delete` 与 journal current root-path row 的原子性不等于跨域恢复；需要独立可注入失效验证和实现设计。
4. **原包/Secrets**：用户保管原 ZIP；本次 input 经密码验证并不是可以将 API Key 普通明文落盘的许可。普通 Desktop 公开/分享导出仍排除 SecretStore。可选 password-protected credential-bearing **export** 是额外产品功能，没有因此一并批准。
5. **D-038 parity**：官方 v1.4.5 full-app migration/Android 接收和 Desktop → Desktop full restore 仍必须单独验证；S2 C-A Moment nonrunnable 不能用措辞掩盖完整官方 Tasks 功能尚未等价。1.4.6/7 v2 读取不等于可恢复，正式 baseline1.4.1 不变。

## 4. 实施顺序（不启动未批准生产写）

- **G1A（可先行，test-only）**：[161](161_P11_S2_G1A_SECRETSTORE_TRANSACTION_PROBE_CODEX_CONTRACT.md) 用现有 secret fake protector、isolated roots/known-good journal 验证当前逐 key 存储与跨域原子性之间的缺口，记录精确故障边界；不改 `src/main`，不触碰实际 Token/DPAPI 用户目录。
- **G1B（Project docs/audit）**：以 G1A 的事实和已经批准的产品原则，冻结实际 schema/事务/备份/GC/降级设计、无权覆盖的真实阻断及精确 Codex S2-G2 工程切片；任何正式数据写入需获得独立实施授权与测试范围。
- **G2/G3（未来）**：经过批准再实现生产 adapted core + Windows adapters；完整安全/功能/用户验收必须单独签。未完成 S2 全部能力前不允许把半成品发布给 Preview 玩家。

**当前只批准产品方向与 test-only 探针，S2 生产域、跨 SecretStore 写入、FullBackup 的 credential-inclusive 默认输出、legacy executable、Android/GUI/真人根及 Public Preview 均未获本文件授权。P11=PENDING、D-038=BLOCKED、Post-P8 HOLD、A–G 未签、desktop 未合并。**
