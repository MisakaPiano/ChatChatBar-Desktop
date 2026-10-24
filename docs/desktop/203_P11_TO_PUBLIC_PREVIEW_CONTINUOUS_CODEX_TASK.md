**用户权限边界已同步（2026-10-25 JST）。** [权限边界](USER_AUTHORITY_AND_PROJECT_REVIEW_BOUNDARY.md)与[开发原则](01_PROJECT_INSTRUCTIONS.md)记录用户指令：Project 仅做后置阶段性审查；未经用户采纳的新限制仅为建议，不撤销、收窄或暂停已有授权。只暂停真实阻断所影响的动作，其他已授权工作连续推进到 Preview。诊断与修复不自动扩张为真实数据/凭据写入、合并或发布权限；具体动作按已有用户授权执行，不重复索取。真实数据、存档和凭据不上 GitHub，包含 Actions Secrets、日志与产物。

# 203 — One Continuous Codex Implementation: Finish P11, Close Public Preview A–G, Ship Candidate

**EXECUTABLE CURRENT USER DIRECTIVE / 2026-10-24 / D-046 — NOT ANOTHER AUDIT GATE.**

**Objective:** Complete real CCB Android→Windows Desktop data functionality, P11 portability and all seven Public Preview A–G requirements in **one multi-phase, continuous Codex engineering task**. Build, run, repair and commit real production code; deliver a usable, installable Preview candidate and real manual acceptance steps directly to the user. **Do not pause between phases waiting for Project review, new approval docs or a new task contract.** Project audits implemented code after delivery. Its signature is not an additional prerequisite for already authorized implementation, Fork integration or Preview release; actual validation and necessary manual steps must still be recorded honestly.

## Start and authority

Repository `H:\ChatChatBar-Desktop`, remote `MisakaPiano/ChatChatBar-Desktop`, original writable `feature/phase8-fish-audio`. Use **LIVE latest feature HEAD** after D-046 and this task are committed. Inspect Git status; preserve all existing uncommitted/ignored/private artifacts; fetch and FF-only align; never reset/rewrite unknown work or write upstream; use appropriate checks and honor actual repository protections during already authorized Fork integration.

Read the controlling `docs/desktop/01_PROJECT_INSTRUCTIONS.md`, `03_CODEX_START.md`, `18_DECISIONS.md` D-046, `21_CURRENT_STATE.md`, `10_UPSTREAM_BASELINE.json`, related actual Android `.agents/skills`, exact source files, and `93_PUBLIC_PREVIEW_POLICY.md` / `95_PREVIEW_FULL_APP_BACKUP_PORTABILITY_GATE.md`. Historical LR1–LR7 are **reference test evidence, not mandatory new approvals**. Formal validated upstream remains **1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8**; selected official1.4.5 whole-backup **1198a13cf3008de1358b8a69aa5e40b64797c8d9**; actual Android export code86/v2 is separate, with source producer not independently attested. Do not change baseline by observation alone.

## Continuous Phase 1 — inspect the actual user's mismatches NOW

Use the **already approved** original Android export directory `H:\手机CCB导出` as **read-only input**. Keep original files untouched; use already available authenticated bounded backup decoder and ask for password only if actually needed. Create a new Git-ignored **private** report directory, no Git/cloud upload of raw contents:

- List all 11 unsupported Entity **records**, exact family and raw JSON, failed field path, original type and expected serializer/official-version field contract; don't reduce errors to one counter. Record actual original JSON/bytes for user inspection.
- Group all 108 historical voice/version cases across 5 nested SaveSlots; show source voice fields, owner message/current text, all surviving version IDs/text, exact original version ID, audio existence/length. Distinguish unknown nonblank from nullable/blank legacy. Test the hypothesis of 5-version pruning against **actual** message data; do not conclude corruption without evidence.
- Show user `ENTITY_REVIEW.md`, `VOICE_REVIEW.md`, source raw files and a brief Chinese explanation, with exact private local path. Report serious actual mismatches immediately **but continue independent production code work**.
- No staged secret plaintext/public log; redact any credentials in summaries. Do not use raw content in Git.

**Success:** user can directly inspect records. If data is normal official history, implement correct compatibility; if genuinely unsupported, preserve all original bytes/media or refuse whole restore before commit.

用户已于 2026-10-25 明确把**合并式迁移纳入当前 P11→Preview 任务**：保留电脑已有数据，将手机数据合并进来，冲突由用户选择。现有执行者在本轮连续实现、验证并推进到 Preview；不另开审计、合同或 Project 审批门槛。详见 [203](203_P11_TO_PUBLIC_PREVIEW_CONTINUOUS_CODEX_TASK.md)。

## Continuous Phase 2 — REAL production backup, voice and credential functionality

Fix actual `app/desktopApp/src/main` code, not another `tools/interop/lr*` tester:
- Make official1.4.5 `.cbbackup` v1/code84 full-app import/inspect/restore/reopen usable, and implement supported code85/86-v2 resource/metadata paths needed for user's actual archive. Reuse shared/official codec/Entity/SaveSlot/Provider structure; preserve exact message versions, voices and media. Keep all 11 source-incompatible entries accounted for without silent drop or default rewriting; honor actual official semantics, not a strict scanner false positive.
- Implement secure import of user-owned password-encrypted backup with API/NovelAI/Fish/model credentials into Windows protected storage with correct account/endpoint mapping and **explicit UI confirmation**; ordinary shareable Desktop exports continue excluding secrets. Use existing Windows SecretStore and native data-root/snapshot/rollback mechanisms where sufficient. Add complexity ONLY to solve a witnessed failure, never automatically require Broker or another Windows principal.
- Integrate Moments inert original Post/Task+3 media edge custody and later backup/snapshot/GC/Portable participation as necessary to prevent loss; do not activate unsupported Tasks. Provide genuine rollback/cancel/disk-full/corrupt/error handling and visible status.
- Implement usable Desktop `.cbbackup` export→isolated Desktop root restore/reopen, and supported transfer to official Android format; source archive and current user root remain intact. If a domain is truly unsupported, fail closed with specific explanation rather than claiming success after omission.
- Use task-owned isolated disposable roots for repeated destructive **test fixture** rebuilds; do not clean or reset unmarked user roots/real source archives.

### Phase 2 mandatory deliverable — merge migration in this P11

用户已于 2026-10-25 明确把**合并式迁移纳入当前 P11→Preview 任务**：保留电脑已有数据，将手机数据合并进来，冲突由用户选择。现有执行者在本轮连续实现、验证并推进到 Preview；不另开审计、合同或 Project 审批门槛。详见 [203](203_P11_TO_PUBLIC_PREVIEW_CONTINUOUS_CODEX_TASK.md)。

默认迁移流程为“读取电脑现有状态 + 手机档案 → 合并预览/冲突选择 → 可恢复提交 → 重启验证”。仅保留旧目录、把手机数据单独装入新根，不等于合并完成。新根可以作为事务准备区，但最终可用结果必须包含两边应保留的数据。整包替换如继续保留，应作为清楚区分的恢复模式，不能冒充默认迁移。

同 ID 内容不同、同名不同对象、聊天分支、资源和模型/账户绑定冲突都要明确呈现；按实际语义提供保留电脑、采用手机、两份都保留或跳过选项，由用户选择，不能默默按时间或名称覆盖。两份都保留时，统一重映射 ID、媒体路径及其依赖引用，保持会话/消息/版本/录音、卡片/世界书、Memory/RAG 等关系；不能按时间拼接分叉聊天或改写 Prompt 原文。凭据冲突展示账户/Provider/endpoint 等非密信息，选定后按既有授权写入 Windows 受保护存储，不展示密钥原文。

保留手机原件；迁移前保全电脑状态，取消或失败不改变当前有效状态。提交前若电脑数据已变化，重新核对受影响合并项；不能用过期预览覆盖新数据。验证电脑和手机各自独有内容、重复导入、冲突各选项、资源/引用、凭据绑定、取消/失败/重启与导出再导入。该一次性、用户驱动的合并迁移属于当前 P11；自动双向同步/multi-master/live watcher 仍是后续独立功能。

**Success:** merged Desktop state retains the existing computer data and selected incoming phone data with the user's conflict decisions and coherent references; reopen and use both. Then open and use imported cards/chats/versions/voice/memory/settings (and accurately retained dormant data) in running Desktop; no reliance on a read-only scanning report as the feature.

## Continuous Phase 3 — P11 SaveSlot actual cross-platform use

Implement/fix `.cbsave` Android↔Desktop supported v8 and legacy handling with images/audio/RAG/Memory/SaveSlot/session lifecycle. Test real export→import→open→continue→export→re-import/reopen; run focused JVM Android/shared/Desktop tests. For actual phone GUI/device steps, Codex supplies the user a **short exact checklist, build paths and expected results directly**; do not route through Project. One authorized device only, data-preserving install, no uninstall/clear/signature switch without separate warning and express consent. If real device isn't present, preserve build/test work and proceed with other independent phases.

## Continuous Phase 4 — Windows installer and all Preview A–G in product

Implement and test practical **A** backup/recovery/source retention; **B** installable Windows build, clean install and N→N+1 data-preserving manual update plus D-041 in-app trustworthy update discovery; **C** cross-platform SaveSlot; **D** credential/privacy/ordinary backup exclusions and user-approved direct-import protected flow; **E** user-visible data path/backup and minimal sanitized diagnostics; **F** exact baseline/current upstream release impact, D-016 direct-author permission proof and release notes; **G** actual official145 whole-app merge migration, retaining computer data and user-chosen conflicts, plus newer source archive where supported. Prefer real user-visible/testable functionality; P12–P17 optional features do not delay Preview.

Do not fabricate 7 PASS badges: for each Gate record **IMPLEMENTED, TESTED, HUMAN_ACTION_REQUIRED or REAL_EXTERNAL_BLOCKER**, with genuine evidence. Fix directly when possible, then continue. Neither a missing synthetic VM poweroff test nor an unselected broker is automatically a reason to freeze coding.

## Continuous Phase 5 — integrated Preview candidate and delivery

Build a usable Windows package/installer from the feature branch, run relevant smoke/corrupt/cancel/restart/upgrade tests and compare actual Android semantics with the pinned selected source; avoid repeated unrelated historical tests. Summarize one compact `PREVIEW_IMPLEMENTATION_PROGRESS.md` (updated across checkpoints, not dozens of new approvals) and the final integrated A–G matrix. Keep raw sensitive reports local and Git evidence limited to code/test identity, concise logs and failures. Each code phase should produce a logical commit + push and continue immediately; Codex may revisit prior phases autonomously to fix real issues.

Codex contacts the user DIRECTLY when a human Windows/Android operation or missing actual password/API key is necessary; say what to click, expected result, and where the output file is. The user has requested **first Public Preview publication as the outcome**. Prepare/publish and integrate only under the user's actual existing authorization and matching real evidence; diagnosis/repair alone does not create those permissions. Project reviews completed work and identifies concrete defects, without creating an additional signature gate. Honor actual repository protections and never force unknown work. If a true external blocker remains, provide one consolidated actionable user request; do not present it as another theory-only LR phase.

## Mandatory non-negotiable boundaries

No official upstream write, no invented compatibility/version promotions, no existing upstream Prompt literal change without exact diff + user approval, no secrets in Git/public CI/logs, no silent full-backup/SaveSlot data loss or current user-root clearing. Git can revert code, not erased personal data. User's experimental disposable test **copies** may be rebuilt; normal/public Preview data remains production-valued. Unauthorized paid Provider calls are not implied by code permission; use only provided credentials under their actual testing scope. Good engineering and necessary release tests remain mandatory, but **routine project preapproval is NOT**.

**STOP only for:** truly irreversible live-data destruction not covered by disposable-root authorization, existing Prompt literal change, unavailable necessary credential/device requiring user interaction, actual legal/distribution entitlement failure, or genuine unfixable release-safety issue. Even then continue other independent implementation work. Do not stop for "Project has not reviewed checkpoint", "next LR task is not authorized" or "A–G hasn't been signed while still coding".

