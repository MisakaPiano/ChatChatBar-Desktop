**用户权限边界已同步（2026-10-25 JST）。** [权限边界](USER_AUTHORITY_AND_PROJECT_REVIEW_BOUNDARY.md)与[开发原则](01_PROJECT_INSTRUCTIONS.md)记录用户指令：Project 仅做后置阶段性审查；未经用户采纳的新限制仅为建议，不撤销、收窄或暂停已有授权。只暂停真实阻断所影响的动作，其他已授权工作连续推进到 Preview。诊断与修复不自动扩张为真实数据/凭据写入、合并或发布权限；具体动作按已有用户授权执行，不重复索取。真实数据、存档和凭据不上 GitHub，包含 Actions Secrets、日志与产物。

# 95 — Public Preview Full-App Backup & Migration Compatibility Gate

**CURRENT R07 USER DIRECT-IMPORT ADDENDUM / 2026-10-23:** [158](158_P11_S2_G1_R07_DIRECT_IMPORT_PASSWORD_AND_CREDENTIAL_POLICY.md) supersedes the previous *product target* of denying an entire user-selected `.cbbackup` only because it contains self-exported API keys. Normal goal is authenticated direct import of safe data and keys into Windows protected storage with correct account bindings and crash-safe coordinated transaction; full advanced C2 can come later. Existing production rejection must remain until a verified secure adapter replaces it; no license to silently drop safety checks. Default ordinary shareable Desktop exports still omit keys. D-038/Preview remains BLOCKED.

**APPROVED PROGRAM CONTROL — 2026-10-12 JST / D-038 / P11 OWNER / PUBLIC PREVIEW BLOCKER / NOT IMPLEMENTED**

## 本轮 P11：合并式迁移

用户已于 2026-10-25 明确把**合并式迁移纳入当前 P11→Preview 任务**：保留电脑已有数据，将手机数据合并进来，冲突由用户选择。现有执行者在本轮连续实现、验证并推进到 Preview；不另开审计、合同或 Project 审批门槛。详见 [203](203_P11_TO_PUBLIC_PREVIEW_CONTINUOUS_CODEX_TASK.md)。

默认迁移流程为“读取电脑现有状态 + 手机档案 → 合并预览/冲突选择 → 可恢复提交 → 重启验证”。仅保留旧目录、把手机数据单独装入新根，不等于合并完成。新根可以作为事务准备区，但最终可用结果必须包含两边应保留的数据。整包替换如继续保留，应作为清楚区分的恢复模式，不能冒充默认迁移。

同 ID 内容不同、同名不同对象、聊天分支、资源和模型/账户绑定冲突都要明确呈现；按实际语义提供保留电脑、采用手机、两份都保留或跳过选项，由用户选择，不能默默按时间或名称覆盖。两份都保留时，统一重映射 ID、媒体路径及其依赖引用，保持会话/消息/版本/录音、卡片/世界书、Memory/RAG 等关系；不能按时间拼接分叉聊天或改写 Prompt 原文。凭据冲突展示账户/Provider/endpoint 等非密信息，选定后按既有授权写入 Windows 受保护存储，不展示密钥原文。

保留手机原件；迁移前保全电脑状态，取消或失败不改变当前有效状态。提交前若电脑数据已变化，重新核对受影响合并项；不能用过期预览覆盖新数据。验证电脑和手机各自独有内容、重复导入、冲突各选项、资源/引用、凭据绑定、取消/失败/重启与导出再导入。该一次性、用户驱动的合并迁移属于当前 P11；自动双向同步/multi-master/live watcher 仍是后续独立功能。

## 1. Authority and rationale

The user explicitly approved strengthening Public Preview readiness to include the official **ChatBar 1.4.5 full-app migration archive** rather than requiring only Desktop-local backups and one-conversation SaveSlots. This is an additive amendment to D-037 and D-035; neither the phase numbering nor P17/P18 responsibilities change.

Official reference: `SaltyFishOTL/ChatChatBar v1.4.5 @ 1198a13cf3008de1358b8a69aa5e40b64797c8d9`, particularly `.agents/skills/chatbar-app-backup/SKILL.md`, `domain/backup/*`, `data/security/*CredentialStore.kt`, `domain/voice/FishAudioStorage.kt`, `domain/chat/SaveSlotPackageStorage.kt`, and tests. **This is a selected post-baseline migration target, not a validated upstream baseline promotion.** CCB Desktop's formal validated baseline remains **1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8** until an independently authorized/validated sync.

## 2. Distinguish four products

| Product | Responsibility and authority |
| --- | --- |
| Character/FormatCard/WorldBook **transfer Package** | Existing domain-specific Package, unrelated to full-app backup |
| Single-conversation **.cbsave SaveSlot** | P11 native v8 + legacy 1–7, media/RAG/memory/session transfer; D-037's original separate gate remains mandatory |
| Whole-app **.cbbackup** | **NEW P11 REQUIRED**: official 1.4.5-compatible export, offline validate, safe import/restore and data-root-independent cross-device portability of applicable committed application state |
| Live BYOC/cloud/data-root synchronization | D-035 future owner, **not** a Preview gate; no multi-master merge, remote sync service or automatic watcher is required |

The archive is **not** a raw Android root tree copied onto Windows. Shared logical schema, typed resource mapping, portable ownership and transactional publication are required. No browser/WebView runtime.

## 3. Required P11 implementation contract

Implement and verify within the current continuous P11→Preview task; Project review is post-implementation:

1. Audit **exact official 1.4.5** envelope, bounded v1 format, encryption/authentication, ZIP metadata/inventory, length/hash/CRC, duplicate/path traversal/symlink controls, resource token mapping, destination-space checks, preference/setting and entity registries, prepare/commit/restart/rollback states. Do not invent a Desktop-private format when the official protocol is reusable.
2. Provide user-driven **full-app archive export**, **offline archive inspection**, explicit confirmation and **transactional import/restore**, including crash/restart/cancel/failure rollback. No silent destructive overwrite or default reset of existing data, corrupt bytes or unknown stores. Preserve source archive/root and make recovery steps available.
3. Support **official Android v1.4.5 .cbbackup → Desktop** on genuine files and **Desktop → Desktop** full restore at the Preview gate. Validate **Desktop-exported archive → official Android v1.4.5 import** for supported portable data; if some state is platform-only or not yet implemented in P12–P17, disclose its exact handling, preserve unsupported bytes losslessly or reject transaction before commit; **never claim a complete round-trip after discarding data**.
4. Minimum portable inventory includes supported committed settings, models, characters/cards, chats and versions, worldbooks, memories, RAG documents/index-owned resources, images, voices and media/anchors, drafts, SaveSlot packages and current supported references. Newly implemented P8–P10 owners must hand off known persisted resource paths/serializers/references to P11. Platform-specific session/permission state must be explicitly classified; no fabricated desktop equivalents or silent loss.
5. Full backup must cooperate with `DesktopDataOperationCoordinator`, atomic JsonFileStorage persistence, selected root, portable mode, snapshot/backup, isolated SecretStore, foreground task drain and no-write-during-restore boundary. No raw Android absolute paths left as broken Windows references.
6. P11 owns initial usable UI for export/inspect/import/recovery. Full P17 Backup-Recovery Center and broad OS hardening remain P17; full installer/updater and OS shell integration remain their previous owners except the narrow Public Preview installer/manual-upgrade data-retention gate.
7. During P11, assert official format version/field authority from selected source and tests; do not treat this document as a license to hardcode stale versions indefinitely.

## 4. Strict credential-transfer policy

Official 1.4.5 .cbbackup can include encrypted or non-encrypted credential payloads. That is **not** blanket permission for Desktop to expose secrets:

- **Ordinary shareable Desktop backup/export excludes API keys, Fish/NovelAI tokens, OAuth secrets, service-role keys and protected SecretStore material by default**, as D-037 already requires.
- Recognize and validate incoming archives containing credential fields; **no silent credential installation**. The user has already authorized protected import and real DPAPI verification in isolated roots as recorded in PREVIEW_IMPLEMENTATION_PROGRESS.md; implement that scope continuously without another Project design approval. The merge UI lets the user choose credential/account conflicts using non-secret metadata. Preserve original archives and never log extracted credentials.
- Password-based encrypted container support may be necessary to read official .cbbackup while *not* authorizing credential import/export. Decryption/authentication, secure temporary handling, no plaintext backups/logs and memory lifetime must be tested independently.
- Treat export/import failure, an unavailable decryption key, invalid credential envelope or unrecognized store as **failure, not success with missing data**. If secure full fidelity cannot be offered, Preview must not claim full portability.

This decision strengthens D-035's ordinary-secret-exclusion rule and does not silently modify the official upstream implementation.

## 5. Blocking Public Preview Data-Safety Gate evidence

Collect actual evidence during the same P11→Preview implementation; no separate Project acceptance or review phase is required:

- **Format/golden tests:** Android 1.4.5 exact-source archive compatibility, encrypted/plain envelopes with no secret leakage, selective resource mapping and no excessive-memory copying; malformed/unsupported/truncated/duplicate/path-escape/corrupt/auth-failure cases.
- **Real cross-device migration:** Android v1.4.5 whole-app .cbbackup → clean/snapshotted Desktop → reopen/continue real chat, inspect settings/card/chat/version/memory/image/voice/draft and owned media; record unsupported states precisely. Test Desktop-generated official-compatible .cbbackup in Android for supported portable data, or mark explicit blocker if that claim cannot be made.
- **Merge migration:** populated Desktop + phone archive → preview → user conflict decisions → transactional merged result → restart/use/export/re-import. Verify local-only/incoming-only/identical and conflicting records, keep-local/use-incoming/keep-both/skip where valid, typed ID/resource/reference mapping, repeated import, intervening Desktop edits, cancellation/failure and credential-account bindings. Use synthetic fixtures in Git; authorized real archives and reports stay local. Fresh-root publication must contain the merged state.
- **Desktop round-trip:** old Desktop persisted root → whole-app export → fresh isolated Desktop root → restore → restart/use, including data root/portable mode where supported.
- **Fail-safe tests:** existing Desktop data left valid and recoverable on wrong password, out-of-space, canceled copy, crash/restart midway, invalid media refs, unauthorized credential handling and incomplete restore; snapshot/source retention and rollback evidence.
- **Release honesty:** exact upstream/desktop commit and tested format versions, migration instructions and confidentiality warning, unsupported domains and known limits, tester support/recovery steps and D-016 direct upstream-author authorization.

**Any material data-loss, silent partial restore, unhandled official 1.4.5 archive, insecure ordinary backup, or inability to safely handle imported unsupported bytes = Public Preview RELEASE BLOCKER.** No bypass by saying “only Preview”.

## 6. Boundaries and exit

- This is **P11 + Public Preview Gate**, not P8 implementation. P8 only preserves Fish voice entities/files/owned references and defines P11 contract handoff; P9/P10 do the same for their persisted domains.
- **Execution authority follows 01 / D-046 and the user's current delivery goal.** Work already authorized by the user, including necessary migration implementation/verification/packaging, proceeds continuously; diagnostic work alone does not authorize live root/credential writes, merging or public release. Baseline/parity and Prompt ownership still require their actual technical/user basis.
- P12–P17 parity PENDING rows remain PENDING; P17 user-experience and feedback hardening remain distinct; P18 is still the full 1.0 gate.
- P11 cannot close until its `.cbsave` and `.cbbackup` planned owners meet the scoped implementation/test conditions. Codex supplies actual Windows/Android evidence or explicitly identifies missing manual steps and gives them directly to the user. Project signoff is not a prerequisite for implementation, integration or an already authorized release. Apply 01 / D-046 to known issues and required validation; do not fabricate unperformed tests.

**CURRENT: D-038 APPROVED / FULL-APP MIGRATION BLOCKER ADDED / IMPLEMENTATION PENDING.**

