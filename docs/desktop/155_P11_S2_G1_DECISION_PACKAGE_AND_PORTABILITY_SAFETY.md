# 155 — P11-S2-G1 Persistent Preservation: Product Decision Package and Safety Architecture

**LATEST G0R ACCEPTANCE / 2026-10-23:** [159](159_P11_S2_G0R_OWNED_MEDIA_PROJECT_EVIDENCE_ACCEPTANCE.md) confirms separate physical copy bytes survive synthetic live cleanup, but shows non-atomic multi-candidate unlink and independently copied media not covered by FullBackup unless formally enumerated. Android pending_deletions is NOT_TESTED; old executable downgrade remains a blocker. User-approved R01/R02/R03/R07 policy directions (current [158](158_P11_S2_G1_R07_DIRECT_IMPORT_PASSWORD_AND_CREDENTIAL_POLICY.md)) are synthesized in [160](160_P11_S2_G1_INTEGRATED_PRESERVATION_AND_CREDENTIAL_SAFETY_BLUEPRINT.md); [161](161_P11_S2_G1A_SECRETSTORE_TRANSACTION_PROBE_CODEX_CONTRACT.md) permits only headless test-only SecretStore transaction gap evidence. No persisted production schema/credential write is authorized.

**LATER R07 USER POLICY (2026-10-23):** [158](158_P11_S2_G1_R07_DIRECT_IMPORT_PASSWORD_AND_CREDENTIAL_POLICY.md) is the controlling user direction: original archive custody A, secure direct import of compatible user-owned API credentials, never blanket denial solely because they are present. Advanced C2 account-transfer is future, default C1 stripping is not standard. Historical 'R07 OPEN / whole-archive credential rejection' below describes prior proposal/current code only. Current rejection stays in production until verified Windows SecretStore identity mapping and crash-safe data+secret restoration are ready; do not merely remove safeguards. Ordinary shareable Desktop export excludes credentials by default. S2 production not authorized.

**CURRENT 2026-10-23 JST — PARTIAL USER-CONFIRMED POLICY DIRECTION (R01/R02/R03); R07 OPEN; S2 PRODUCTION NOT AUTHORIZED.**

依据 [151 S1 read-only acceptance](151_P11_S1_MOMENTS_READONLY_GRAPH_PROJECT_ACCEPTANCE.md)、[154 G0 evidence](154_P11_S2_G0_ROOT_BACKUP_RECOVERY_PROJECT_EVIDENCE_ACCEPTANCE.md)、[152 S2 readiness](152_P11_S2_PERSISTENT_PRESERVATION_READINESS_AND_DECISIONS.md) 与用户既有 D-037/D-038/Public Preview 价值承诺。此文件明确建议及必须批准的选择；不能以 docs 提交自动授权新生产域或变更正式 Android Prompt/Entity/版本 baseline。

**USER DECISION ADDENDUM (later on 2026-10-23 JST):** [157](157_P11_S2_G1_USER_CONFIRMED_PARTIAL_POLICY_DIRECTIONS.md) records the user's explicit support for first-stage C-A non-running Moment Post/Task preservation; exact original JSON plus physically independent media copies, no automatic deletion, insufficient-space fail-closed without deleting existing data; and no unsafe older binary writes to the upgraded root. These are approved *product principles*, **not implementation permission or accepted schema, quota, old-binary enforcement**. R07 (source `.cbbackup` custody and credential behavior) is **still under discussion**; current rejection remains the operational rule. The candidate language below preserves the pre-decision design rationale; its R01/R02/R03 “not approved” wording is historical where superseded by 157. **G1 as a whole remains PARTIAL/PENDING**.

## 1. 已确认的系统事实（不是政策批准）

| 域 | 当前实测 | S2 设计含义 |
|---|---|---|
| 手动/自动快照、root 迁移 | 普通 sentinel bytes PRESERVED | 通用备份可以复用，但包含字节 ≠ schema/密封/秘密/跨版本可恢复 |
| FullBackup 未知顶层域 | EXPLICITLY_REJECTED | 正式新域未注册时不得自称全量已导出；不能偷偷放到 unknown root |
| FullBackup 隐藏路径 | SILENTLY_SKIPPED 与导出成功并存 | 不能通过隐藏文件夹将 committed 数据绕过 registry；每个新域文件/资源必须清单覆盖，且导出成功需要已知覆盖证明 |
| live image GC | 仅 sentinel manifest 引用的 shared `images/` 文件被删除 | 推荐封存使用**独立复制的 owned blobs**，不共享可变 live 路径；或另建统一拥有权并证明所有删除链，不能只存 manifest |
| Journal、Startup、Portable | old admitted/new outside journal | 需要正式跨域原子恢复、预启动拒写、旧 binary 根隔离/迁移政策 |
| Symlink 与旧 exe | 未测试或不确定 | 仍为数据安全 release blocker；不得默认为 PASS |

## 2. 候选政策包：G1-C-A（建议，但未经批准）

**R01/R04 — 内容与 Task：** 仅第一阶段离线 raw bytes 封存 Post、Task、三图片引用及实际媒体，不发布 MomentRepository/live Entity、不唤醒 Scheduler、不回注可运行 Task。不声称官方 Android 1.4.5 可运行全量回导已完成；后续要么协作实现任务持久隔离，要么经用户明确批准标记为有限迁移。任何提案不得改变现有 Prompt。

**R02 — 数据域、资源与容量：** 候选将 `desktop.moments.preservation` 定义为**独立版本化的持久数据域**，仅当拥有权、目录策略、快照/自动备份/full `.cbbackup`、root migration/Portable、pre-start journal、rollback/GC 与降级安全证据全部闭合时才能上线。存储采用 copy-on-seal + content SHA+长度、独立媒体物理副本；来源 `(archiveSha,type,id)` 防跨包同 ID 自动合并。外来原始 ZIP 不自动托管在用户可分享数据备份。容量配额应先基于真实峰值/故障证据提出具体数值、升级策略与 fail-closed 提示，**本文件不伪造一个产品批准的容量数字**。非显式删除时不自动 GC 已封存原 bytes；删除政策、备份保留、root switch 处理需要用户批准。

**R03 — 旧版防写：** 优先研究 fresh/isolated root generation / side-by-side、升级迁移前完整 snapshot、保留旧 root 可恢复、旧 executable 默认路径与新 root 分离。**仅隔离路径或标记不足以保证用户手动把旧版指向新 root 时不会写入**；对这种场景必须设计 OS 级实际隔离、旧程序测试或明确可实施的拒写机制，并在发布前证明。禁止用前端提示代替已发布旧二进制的保护。G0 没运行旧版，因此产品 R03 无法由 G0 决定为已解决。

**R07 — 来源档案与 Secrets：** 当前非空外来已知凭据整包拒绝；普通 Desktop 可分享备份仍排除 SecretStore。建议初版保留原 `.cbbackup` 由用户自行存储，程序只在当前显式解密会话中验证，绝不自动将可能含 token 的原包放入普通 root/snapshot/诊断；若今后批准来源包托管，需要专门访问保护、加密、保留期限/导出策略。C1 有限主动排除与 C2 真实凭据迁移需另批，不通过普通 S2 间接放行。

## 3. 必须先达成的共同验收合同

1. **数据安全：** schema+raw hash、独立资源 graph 与媒体拥有权、原档不修改、错误不携数据、配额预估及写时二次限额；无内容未知时的成功封存。
2. **覆盖：** 普通自动备份、手动快照、`DesktopFullBackupArchive`、root switch/Portable、GC 与预启动 recovery 的**同一可枚举完整域清单**，即使没有单条聊天消息仍不得漏来源 bytes。
3. **事务：** pre-snapshot → own staging → verified seal → explicit confirm → durable journal → restart apply/rollback → committed root 成功，不允许新旧域各自一半成功。失败/取消只删除本事务拥有的临时物；源包与旧有效状态始终保留。
4. **降级：** 老 binary 不认识新 schema 的事实单独验证；需要可复现的旧版 direct-root / Portable / 旧安装器回退实验、或同等具有强制性的 root 隔离方案。证据未完成前不得给外部玩家开放该域。
5. **Task/Secrets：** 保存原 Task 不等于授权执行；保留安全 metadata 不等于搬运 Secret；暂不变更 C-A/外来 token 的拒绝规则。

## 4. 任务顺序与明确的决策请求

**独立无须用户生产批准的工程探针：** 可执行 [156 G0R test-only media/deletion audit](156_P11_S2_G0R_OWNED_MEDIA_AND_CLEANUP_AUDIT_CODEX_CONTRACT.md)，补齐 GC/pending_deletions 的覆盖边界。它不产生数据政策、不改变 `src/main`。

**需要用户授权的下一关：** 在 G0R 限定证据完成后，以一份明确的 G1 决策确认 **C-A 内容边界、独立 copy-on-seal/持久 schema、容量/自动备份与保留删除规则、side-by-side/downgrade 强制保护策略，以及不托管含秘密原包**；必须列出未解阻断项并允许明确 NOT APPROVED。未签前不能发布生产 `desktop.moments.preservation` 目录，更不能允许 S2-G2 正式写数据。

**状态：G0 证据限定接受；G0R test-only 允许作为独立任务；G1 产品合同 PENDING / S2 production BLOCKED；P11/D-038/Post-P8 HOLD/Preview A–G 未签不变。**
