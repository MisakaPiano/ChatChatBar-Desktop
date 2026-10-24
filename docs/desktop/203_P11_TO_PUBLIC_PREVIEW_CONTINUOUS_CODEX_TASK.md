# 203 — One Continuous Codex Implementation: Finish P11, Close Public Preview A–G, Ship Candidate

**EXECUTABLE CURRENT USER DIRECTIVE / 2026-10-24 / D-046 — NOT ANOTHER AUDIT GATE.**

**Objective:** Complete real CCB Android→Windows Desktop data functionality, P11 portability and all seven Public Preview A–G requirements in **one multi-phase, continuous Codex engineering task**. Build, run, repair and commit real production code; deliver a usable, installable Preview candidate and real manual acceptance steps directly to the user. **Do not pause between phases waiting for Project review, new approval docs or a new task contract.** Project independently audits the implemented code after handoff and checks real release evidence before public publication.

## Start and authority

Repository `H:\ChatChatBar-Desktop`, remote `MisakaPiano/ChatChatBar-Desktop`, original writable `feature/phase8-fish-audio`. Use **LIVE latest feature HEAD** after D-046 and this task are committed. Inspect Git status; preserve all existing uncommitted/ignored/private artifacts; fetch and FF-only align; never reset/rewrite, merge protected `desktop` or write upstream.

Read the controlling `docs/desktop/01_PROJECT_INSTRUCTIONS.md`, `03_CODEX_START.md`, `18_DECISIONS.md` D-046, `21_CURRENT_STATE.md`, `10_UPSTREAM_BASELINE.json`, related actual Android `.agents/skills`, exact source files, and `93_PUBLIC_PREVIEW_POLICY.md` / `95_PREVIEW_FULL_APP_BACKUP_PORTABILITY_GATE.md`. Historical LR1–LR7 are **reference test evidence, not mandatory new approvals**. Formal validated upstream remains **1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8**; selected official1.4.5 whole-backup **1198a13cf3008de1358b8a69aa5e40b64797c8d9**; actual Android export code86/v2 is separate, with source producer not independently attested. Do not change baseline by observation alone.

## Continuous Phase 1 — inspect the actual user's mismatches NOW

Use the **already approved** original Android export directory `H:\手机CCB导出` as **read-only input**. Keep original files untouched; use already available authenticated bounded backup decoder and ask for password only if actually needed. Create a new Git-ignored **private** report directory, no Git/cloud upload of raw contents:

- List all 11 unsupported Entity **records**, exact family and raw JSON, failed field path, original type and expected serializer/official-version field contract; don't reduce errors to one counter. Record actual original JSON/bytes for user inspection.
- Group all 108 historical voice/version cases across 5 nested SaveSlots; show source voice fields, owner message/current text, all surviving version IDs/text, exact original version ID, audio existence/length. Distinguish unknown nonblank from nullable/blank legacy. Test the hypothesis of 5-version pruning against **actual** message data; do not conclude corruption without evidence.
- Show user `ENTITY_REVIEW.md`, `VOICE_REVIEW.md`, source raw files and a brief Chinese explanation, with exact private local path. Report serious actual mismatches immediately **but continue independent production code work**.
- No staged secret plaintext/public log; redact any credentials in summaries. Do not use raw content in Git.

**Success:** user can directly inspect records. If data is normal official history, implement correct compatibility; if genuinely unsupported, preserve all original bytes/media or refuse whole restore before commit.

## Continuous Phase 2 — REAL production backup, voice and credential functionality

Fix actual `app/desktopApp/src/main` code, not another `tools/interop/lr*` tester:
- Make official1.4.5 `.cbbackup` v1/code84 full-app import/inspect/restore/reopen usable, and implement supported code85/86-v2 resource/metadata paths needed for user's actual archive. Reuse shared/official codec/Entity/SaveSlot/Provider structure; preserve exact message versions, voices and media. Keep all 11 source-incompatible entries accounted for without silent drop or default rewriting; honor actual official semantics, not a strict scanner false positive.
- Implement secure import of user-owned password-encrypted backup with API/NovelAI/Fish/model credentials into Windows protected storage with correct account/endpoint mapping and **explicit UI confirmation**; ordinary shareable Desktop exports continue excluding secrets. Use existing Windows SecretStore and native data-root/snapshot/rollback mechanisms where sufficient. Add complexity ONLY to solve a witnessed failure, never automatically require Broker or another Windows principal.
- Integrate Moments inert original Post/Task+3 media edge custody and later backup/snapshot/GC/Portable participation as necessary to prevent loss; do not activate unsupported Tasks. Provide genuine rollback/cancel/disk-full/corrupt/error handling and visible status.
- Implement usable Desktop `.cbbackup` export→isolated Desktop root restore/reopen, and supported transfer to official Android format; source archive and current user root remain intact. If a domain is truly unsupported, fail closed with specific explanation rather than claiming success after omission.
- Use task-owned isolated disposable roots for repeated destructive **test fixture** rebuilds; do not clean or reset unmarked user roots/real source archives.

**Success:** open and use imported cards/chats/versions/voice/memory/settings (and accurately retained dormant data) in running Desktop; no reliance on a read-only scanning report as the feature.

## Continuous Phase 3 — P11 SaveSlot actual cross-platform use

Implement/fix `.cbsave` Android↔Desktop supported v8 and legacy handling with images/audio/RAG/Memory/SaveSlot/session lifecycle. Test real export→import→open→continue→export→re-import/reopen; run focused JVM Android/shared/Desktop tests. For actual phone GUI/device steps, Codex supplies the user a **short exact checklist, build paths and expected results directly**; do not route through Project. One authorized device only, data-preserving install, no uninstall/clear/signature switch without separate warning and express consent. If real device isn't present, preserve build/test work and proceed with other independent phases.

## Continuous Phase 4 — Windows installer and all Preview A–G in product

Implement and test practical **A** backup/recovery/source retention; **B** installable Windows build, clean install and N→N+1 data-preserving manual update plus D-041 in-app trustworthy update discovery; **C** cross-platform SaveSlot; **D** credential/privacy/ordinary backup exclusions and user-approved direct-import protected flow; **E** user-visible data path/backup and minimal sanitized diagnostics; **F** exact baseline/current upstream release impact, D-016 direct-author permission proof and release notes; **G** actual official145 whole-app migration plus newer source archive where supported. Prefer real user-visible/testable functionality; P12–P17 optional features do not delay Preview.

Do not fabricate 7 PASS badges: for each Gate record **IMPLEMENTED, TESTED, HUMAN_ACTION_REQUIRED or REAL_EXTERNAL_BLOCKER**, with genuine evidence. Fix directly when possible, then continue. Neither a missing synthetic VM poweroff test nor an unselected broker is automatically a reason to freeze coding.

## Continuous Phase 5 — integrated Preview candidate and delivery

Build a usable Windows package/installer from the feature branch, run relevant smoke/corrupt/cancel/restart/upgrade tests and compare actual Android semantics with the pinned selected source; avoid repeated unrelated historical tests. Summarize one compact `PREVIEW_IMPLEMENTATION_PROGRESS.md` (updated across checkpoints, not dozens of new approvals) and the final integrated A–G matrix. Keep raw sensitive reports local and Git evidence limited to code/test identity, concise logs and failures. Each code phase should produce a logical commit + push and continue immediately; Codex may revisit prior phases autonomously to fix real issues.

Codex contacts the user DIRECTLY when a human Windows/Android operation or missing actual password/API key is necessary; say what to click, expected result, and where the output file is. The user has requested **first Public Preview publication as the outcome**. After complete actual A–G evidence and final Project **post-implementation** review confirming no real data-loss, security, legal-authority or compatibility blocker, prepare/publish the authorized Preview release using the allowed fork workflow; protected `desktop` integration still needs its own safe branch decision, never force it. If a true external blocker remains, provide one consolidated actionable user request; do not present it as another theory-only LR phase.

## Mandatory non-negotiable boundaries

No official upstream write, no invented compatibility/version promotions, no existing upstream Prompt literal change without exact diff + user approval, no secrets in Git/public CI/logs, no silent full-backup/SaveSlot data loss or current user-root clearing. Git can revert code, not erased personal data. User's experimental disposable test **copies** may be rebuilt; normal/public Preview data remains production-valued. Unauthorized paid Provider calls are not implied by code permission; use only provided credentials under their actual testing scope. Good engineering and necessary release tests remain mandatory, but **routine project preapproval is NOT**.

**STOP only for:** truly irreversible live-data destruction not covered by disposable-root authorization, existing Prompt literal change, unavailable necessary credential/device requiring user interaction, actual legal/distribution entitlement failure, or genuine unfixable release-safety issue. Even then continue other independent implementation work. Do not stop for "Project has not reviewed checkpoint", "next LR task is not authorized" or "A–G hasn't been signed while still coding".
