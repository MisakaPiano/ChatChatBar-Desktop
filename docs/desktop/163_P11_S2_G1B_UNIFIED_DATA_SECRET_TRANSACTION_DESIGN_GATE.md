# 163 — P11-S2-G1B Unified Data + Credentials: Recovery Design Gate (Proposal)

**CURRENT PROJECT DESIGN — 2026-10-23 JST — ENGINEERING DESIGN NOT YET ACCEPTED / PRODUCTION IMPLEMENTATION NOT AUTHORIZED.**

本文件是根据 [162 G1A 实测](162_P11_S2_G1A_CREDENTIAL_TRANSACTION_PROJECT_ACCEPTANCE.md)、[159 G0R](159_P11_S2_G0R_OWNED_MEDIA_PROJECT_EVIDENCE_ACCEPTANCE.md)、[160 综合蓝图](160_P11_S2_G1_INTEGRATED_PRESERVATION_AND_CREDENTIAL_SAFETY_BLUEPRINT.md)、用户已批准 [157]/[158] 而制定的 **G1B 设计方案，不是已生效持久 schema/实施授权**。必须以固定官方 1.4.5 备份数据与当前 Desktop 源码为真值；不改变 Prompt，不混淆 Package/Entity/最终 Request。

## 1. 必须满足的安全不变量

| ID | 必须成立的行为 | 不成立时 |
|---|---|---|
| INV-01 SOURCE | 原始 `.cbbackup` 由用户持有，不自动托管/更改；正确密码、认证全部 ZIP/inventory/Entity 后方可做持久计划；未知 schema、资源图未闭合不能宣称全量成功。 | 明确分类拒绝，源保持不变。 |
| INV-02 SECRET | 用户自己导出的合法 API 凭据不是拒绝理由；已冻结的每项凭据类型、来源 ID、目标逻辑 Key/账号/provider endpoint 映射**有证据**且不冲突；目标用 OS 保护，不作为普通明文 JSON、诊断或普通可分享备份。 | 未映射/冲突进入明确保护性阻断或用户选择，不能默认剔除/错误绑定。 |
| INV-03 ATOMIC | 允许设备在任何单文件/多密钥/数据/封存提交阶段崩溃，业务加载前必须能**完整恢复到 ALL_OLD 或 ALL_NEW**；无法证明恢复则 startup fail-closed 且保留旧/新密文和受控 journal，不让半完成状态进入 Provider/Repository。 | RELEASE BLOCKER；不能把 try/catch 或单 Key 原子写当整包事务。 |
| INV-04 ROOT | 数据根所有权与 SecretStore 根是不同位置；同 Windows 账户下多个 Desktop 根**可能共享 SecretStore**。迁入过程必须有跨根凭据 CAS/Generation 与锁纪律，不得只靠选中 data-root 的锁。 | 同 key 并发写、丢失原配置，拒绝事务。 |
| INV-05 MOMENTS | C-A MomentPost/Task 保留源原始 JSON 和三路径独立媒体副本，不注入 live Task/Scheduler；封存文件必须是正式可枚举、非隐藏、GC 独立拥有且随快照/备份/迁移/Portable/restart 一致保全。 | 不得向真实 root 发布半完整 Seal。 |
| INV-06 DOWNGRADE | 旧 Desktop 不允许向新格式根不安全写入；仅新程序检查 marker、隐藏目录、Launcher 提示均不足以约束旧 binary，旧版直开/Portable 须另经实际安全设计/测试。 | 不对 Preview 释放新域。 |
| INV-07 CAPACITY | 新导入空间不足时拒绝新提交，不删已有效数据，不自动 GC 封存；capacity 峰值/保护快照/回滚空间及磁盘故障必须单独测试，数字上限尚未批准。 | 不得以无法预留空间为理由清档。 |

## 2. 精确凭据来源→目标身份候选（需按官方源码再次审计）

| 官方 1.4.5 来源 | 现有 Windows SecretStore target | G1A 已知缺口 / 设计必须规定 |
|---|---|---|
| Preferences `novelAiToken` | `NovelAiToken` | 全球单例目标已有值不同不得静默覆盖；选择新旧内容后冻结计划。 |
| Preferences `fishAudioKey` | `FishAudioApiKey` | 同上，来源目标服务身份核对。 |
| Preferences `communitySession` | **当前没有等位** | 必须先提供官方语义可支持的 Windows 会话凭据/过期/刷新生命周期 adapter，或明确报告具体不兼容；不能错误映射到任意 API Key，也不应笼统标记“含凭据拒绝”。 |
| `AppSettings.siliconFlowApiKey` | `SiliconFlowApiKey` | 单例与持久设置分离，未完成映射不得持久化明文。 |
| `ModelConfig.apiKey` | `ModelApiKey(modelId)` | 不可以模型名称代替 ID，也不能忽略同 ID 不同 provider/Endpoint 的危险冲突；绑定模型配置与对应受保护 Key。 |
| legacy `EmbeddingConfig.apiKey` | `LegacyEmbeddingApiKey(embeddingId)` | 源 legacy ID 与目标旧模型的作用域明确。 |
| singleton `embedding_model_config` | `SingletonEmbeddingApiKey` | 即使对象 ID 相同也不能与 legacy secret 混用。 |

其中 `communitySession` 没有当前受保护 sink 是**真实兼容工作项**；解决这个缺口可能涉及用户登录安全及官方代码/生命周期审计，而非简单新增一个 Key 常量。标准直接导入的“完整”主张必须考虑此类现行不可映射来源。

## 3. 统一提交状态机候选（尚未实施）

**核心问题：** 现有 Root journal 只包含允许的 root-relative data rows；`WindowsSecretStore` 位于 per-Windows-profile LOCALAPPDATA 下且每 key `save/delete` 自己原子写。不能将 SecretStore 的绝对路径塞进 `DesktopFullBackupCommitJournal.schedule`，更不能先 save 几把 Key 再写数据然后靠 finally 回滚。不同卷/不同 owner 的原子文件重命名不能天然形成一个物理原子事务。

建议采用带显式阶段与启动恢复闸门的 **RECOVERABLE MULTI-PARTICIPANT INTENT**，以测试证明逻辑可用性原子，而不虚构硬件级原子：

1. `SOURCE_VERIFIED`：来源原包身份/密码/资源/类型凭据计数与目标现状校验；构造**不含明文秘密的 Plan 摘要**。无差异/冲突/未映射问题没处理前不能进入下一阶段。
2. `PREPARED`：获取 data-root 所有权与跨根 SecretStore 逻辑 key 锁/版本证据；建立独立受限 staging，**旧已保护 blob** 和 **新目标账号保护 blob** 的可逆恢复快照与 hashes 在适当受保护存储中准备齐全；检查可用空间、签名/文件身份、DPAPI 目标上下文和事务所有权。任何参与者不可恢复即止步，不写 live。
3. `INTENT_DURABLE`：在可靠位置写入不携明文凭据的单一可验证 intent，包括 operation ID、来源 hash、目标 root/generation、允许的根相对 rows、受保护 Key identity 与 old/new blob hash、阶段版本、各自 owned staging/rollback 证据索引；确切目录/API/schema 与跨卷 fsync/rename 协议须单独验证，不得直接搬用旧 Journal。
4. `APPLYING`：逐项安装数据/Seal 与受保护密钥；每步在异常/取消/硬退出后仍有可恢复证据。测试需覆盖 KEY_FIRST、DATA_FIRST、MULTI_KEY_SECOND_FAIL、POST_PUBLISH_AMBIGUOUS、DATA_ROW_FAIL、RESTART、ROOT_SWITCH、SECRET_ROOT_UNAVAILABLE。
5. `RECOVERING`：StartupGate 在任何 Repository/Provider/Model hydration/自动备份 Scheduler 启动前独占恢复：对已验证 intent，优先按规定选择全 OLD 或全 NEW 并核对 exact states/hash。部分写入**不能直接开放业务**；如来源密钥账户上下文失效、受保护 blob 不可还原、unknown protected target，则维持 blocked/保留现场，绝不静默清除用户原数据或已有密钥。
6. `COMPLETE`：仅在 root files/Seal/secret values 及必要的身份映射同时核验通过后发布 success receipt；清理只属于此事务的无用临时文件，保留用户批准的恢复/快照证据。两种状态完成不能混合。未明确关闭的写入冲突属于 blocked，不是 silent replace。

上述是**候选协议**而非已验证源码。设计前须回答：intent 权威放在哪个 root/secret-root、共享 SecretStore 多数据根并发如何串行化、备份/恢复如何保证共享 root 不串扰、Windows DPAPI profile/Portable 切换如何原地恢复、原子 move fallback 和 durable fsync 能保证什么、SecretStore 路径与身份脱离 data-root 后如何在迁移/回退时恢复、旧 binary 绕过新 admission 的释放策略。

## 4. 允许做与仍 BLOCKED 的下一轮

- **Project 可以继续 docs-only 源码审查、方案比较、精确 G2 切片划分与风险测试矩阵**，无需修改生产源码或用户数据。本 [163] 用来冻结方案候选和未闭合 blocker，不声明设计已通过正式安全 acceptance。
- **候选最小生产切片** [164](164_P11_S2_G2A_BOUNDED_IMPLEMENTATION_CODEX_CONTRACT_DRAFT_NOT_AUTHORIZED.md)：先封装 typed identity/transaction adapters，在默认不可用、无业务接线的情况下测试候选 Intent + 失败恢复，并保留全量旧 restore rejection；**必须在用户另行明确批准这笔 `src/main` 工程工作之后才能交 Codex 执行**。G2A 即使完成也不得带入完整 GUI、Task 激活、S2 媒体 Seal 发布或 Preview。
- **Release blocking evidence**：真实 DPAPI user/profile/Portable、旧 Windows 版本直开新根防写、child-JVM 真实硬退出和 restart、真实官方 v1.4.5 Android/Windows roundtrip/credential categories、symlink/reparse、whole-app D-038 A–G、容量与统一 GC。不得用 G1A 112 headless tests 补签这些项。

**CURRENT G1A LIMITED EVIDENCE ACCEPTED；S2-G1B DESIGN OPEN、G2A/G2B PRODUCTION NOT AUTHORIZED，P11/D-038/Post-P8/Public Preview HOLD 不变，validated baseline 1.4.1、不合并 desktop。**
