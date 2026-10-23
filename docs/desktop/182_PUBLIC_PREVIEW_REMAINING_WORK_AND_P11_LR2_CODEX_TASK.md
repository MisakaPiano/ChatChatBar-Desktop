# 182 — Public Preview Remaining Work Scorecard + P11 Critical Path Codex Long-Run

**PROJECT CONTROL / PREPARED EXECUTABLE CODEX HANDOFF — 2026-10-24 JST. NO ENGINEERING EXECUTION, NO NEW PRODUCTION AUTHORIZATION FOR G1B/G2C, NO PREVIEW RELEASE.**

This record is a **current-state assessment and prepared Codex task** after [181 Project bounded acceptance](181_PREPREVIEW_AUTONOMOUS_BOUNDED_PROJECT_ACCEPTANCE.md), not a claim of release readiness. It does **not** supersede the product/safety policies in [93](93_PUBLIC_PREVIEW_POLICY.md), [95](95_PREVIEW_FULL_APP_BACKUP_PORTABILITY_GATE.md), [158](158_P11_S2_G1_R07_DIRECT_IMPORT_PASSWORD_AND_CREDENTIAL_POLICY.md) or the existing `179` unapproved topology candidate. The first Preview requires **P11 CLOSED/ACCEPTED before its independent A–G release gate can begin**.

## I. Exact authority and objective

- Fork `MisakaPiano/ChatChatBar-Desktop`; Codex local `H:\ChatChatBar-Desktop`; **feature only** `feature/phase8-fish-audio`.
- Expected start before this docs control update: `c46b75d2d357c2a62cef82b67811e234cb14911f`. When this document is committed, **take the resulting feature branch HEAD as the authorized task starting ref only after user hands this task to Codex and fetch/FF ancestry has been verified**. The source code is unchanged by this docs control.
- Protected `desktop @ 5ef95428421f7c693b010ac88f51d470804ab07a`; no merge.
- Formal validated upstream: official ChatBar 1.4.1 @ `5e76a9cb841736bbbf3499a2e35e5789af4c5ca8`.
- Pinned D-038 full-backup transport authority: official ChatBar 1.4.5 @ `1198a13cf3008de1358b8a69aa5e40b64797c8d9`. The actual user full backup declared **code86/v2**, a distinct newer input; do not mislabel as official 1.4.5 or silently sync/bump the baseline.
- Goal: **reduce real Preview blocking work** while leaving all unsupported operations fail-closed; stop at `P11 CRITICAL-PATH PROJECT CODE REVIEW CANDIDATE` or an evidenced blocking condition. No P11 acceptance, Preview Gate PASS or release claim by Codex.

## II. Release gates: hard count, not percentage

The required Public Preview Data-Safety Gate has **seven sections A–G, and currently 0/7 are formally signed PASS**. This is a release **signoff count**, **not** development completion percentage or forecast of remaining duration.

| Gate | Evidence already available | Unresolved before signoff |
|---|---|---|
| A — persistence, recovery | Existing atomic persistence/journals/snapshots; qualified hidden-byte fail-closed correction 181; local isolated SaveSlot import | Full data+credential and Moments recovery; crash/process restart, failure/cancel, capacity and realistic data retention |
| B — upgrade/install D-041 | Scoped source tests and update checker; app-image `0.1.0-preview.903` built; historical partial native checks | Real packaged launch/rendered update flow, install N→N+1/uninstall, valid DPAPI token retention, supported data root continuity |
| C — .cbsave portability | Private v8 Android export (~94MiB, 281 entries) imported into isolated root; Codex reports 59 messages/33 images/244 audio preserved, same-JVM reopening | Physical Android↔Desktop round trip, open/continue, voice/RAG/Memory/session semantics, OS-process restart evidence |
| D — secrets/privacy | Normal shareable export excludes credentials; bounded G2A/G2B/T1 and synthetic security tests | Safe account-target binding, protected credential import/cross-root all-or-none transaction, Community lifecycle and platform proof |
| E — supportability | Version/commit identity and redacted source/test evidence | Tested user recovery/diagnostic process and effective installed-app support information |
| F — release truth | Baseline documented; upstream master observed through `58023edf`, separate selected145 reference | Latest release-impact audit, verified retained D-016 author permission evidence, truthful scope/known issues and user release decision |
| G — D-038 whole app .cbbackup | Real encrypted ~1.34GiB code86/v2 stream authenticated, 5,625-file **supplementary local** CRC/inventory; bounded S0/S1/G2A/G2B synthetic owners | Complete central/Entity/scope/graph admission, full transactional restore including moments/credentials/unknown data, genuine **official 1.4.5** Android→Desktop, Desktop→Desktop, supported Desktop→Android compatibility |

`Post-P8 HOLD / G1B OPEN / G2C NOT AUTHORIZED / P11 PENDING / D-038 BLOCKED / Public Preview HOLD` all remain. P8–P10 code is substantially present but their formal parity rows remain PENDING; do not conflate implementation presence with parity acceptance or redo already accepted tests by default. P12–P17 may remain PENDING during Preview **only** for capabilities not violating the data safety/portability gates.

## III. Estimate in deliverable blocks (not invented duration)

Five **engineering work packages** remain materially open, with overlap and unequal scope:

1. **P11-FORMAT** — strict bounded large v1/v2 full-backup entity/scope/resource graph authentication and official145 golden compatibility; verified unsupported-data policy; no premature semantic admission.
2. **P11-PRESERVATION** — C-A raw MomentPost/Task JSON + physically independent owned media, snapshot/backup/GC/Portable/restart and no auto scheduler/no auto deletion; requires safe persisted-domain design gate.
3. **P11-CREDENTIALS** — G1B per-root/legacy key, identity, Community, consent and all-or-none data+SecretStore recoverable authority; user **has not** selected/approved actual persistent B topology or G2C.
4. **P11-SESSION** — complete current/legacy SaveSlot ownership and cross-platform/OS-process restart proof, not just category-count comparison.
5. **PREVIEW-PACKAGE** — D-041 installed experience and N→N+1 safe update/rollback, supportability/upstream drift and truth-in-release record.

Three **external evidence categories** cannot be replaced by JVM green: (a) Android actual direction/continue on a controlled Android device (b) authorized isolated Windows profile/VM installer+DPAPI+old-binary/physical-restart tests (c) release-time D-016 retained permission evidence and user's final explicit approval. These are categories, not exactly three test cases; additional checks may be needed.

## IV. Next Codex task — P11-LR2 focused autonomous critical-path engineering

**TASK READY FOR USER HANDOFF; this task does not itself authorize risky persistent credential changes.** Optimize for long-running independent work with self-reviewed checkpoints; do not stop for every minor repair. Use the original feature branch and commit/push clean, scoped engineering checkpoints with raw evidence.

### Stage 0 — authoritative preflight, no broad rescan

Verify live `feature/phase8-fish-audio` HEAD and ancestry, clean tracked worktree and all untracked/unknown user files protected, `desktop` still unchanged. Read `00`, `10`, `21`, relevant upstream Skills, `13`, `14`, then `17`, `18`, `20`, `93`, `95`, `151`, `158`, `163`, `169`, `172`, `176`–`182`, and focused source. Treat earlier NEXT as dated; do not redo closed T0/T1, G2A/G2B or 181 proof without regression impact.

Keep local JVM/Gradle bounded, no global clean, no unknown artifact deletion. Preserve source/release versions exactly. Never modify official Prompt text; if needed produce original/diff/behavior and BLOCK only that dependent subtask. Model/API request parity is assessed from serialized requests, not Preview.

### Stage 1 — P11-FORMAT: largest remaining independent engineering value

Use only existing typed official transport and selected original source, never invent a private replacement wire. Implement **bounded, fail-closed, opt-in semantic read-only** validation suitable for large encrypted v1/v2 sources: strict ZIP local-vs-central names/offsets/duplicates, authenticating actual EOF, manifest/inventory and nested SaveSlot claims, rigorous typed Entity/scope selection and known/unknown resource graph, no plaintext archive copies/secret DTO logs. A supplemental `ZipInputStream` local-header census is **not** enough to publish restore admission. Original S0 64MiB budget is not raised without explicit split budget/streamed architecture + abuse tests. Include official code84/v1 independent source fixtures and real code86/v2 distinction.

User **already authorized** read-only local source use only under `H:\手机CCB导出`; never modify originals or follow reparse/links. It also allows isolated restoration **only** in explicitly task-owned new private roots. A private, password-protected real archive can be consumed via interactive masked console only, never command line/env; no real Key content/IDs/chat/card/Prompt/media or archive digest in public Git, logs, stdout or receipts. Safe real-secret observation does **not** grant real SecretStore write, credential transfer or network use. If password no longer supplied to the process, mark `PASSWORD_REQUIRED` and continue synthetic independent tasks; don't request or store one in Git.

Tests: independent golden+adversarial synthetics (truncation, wrong password, central-local mismatch, ZIP64/limits, duplicate/path/reparse, unknown entity, missing/malformed dependency, malicious expansion, timeout/cancel/close/failure, plaintext leakage); raw RED then GREEN with real execution/JUnit/exit. Real code86/v2 may remain `SEMANTICALLY_BLOCKED` for genuine unsupported domains; never silently drop data or claim successful full restore.

### Stage 2 — P11-SESSION: cross-process isolated SaveSlot evidence

Use the existing DesktopSaveSlotService, same official identity/streaming code and the **already proven** local `.cbsave`; do not reimplement the decoder. Add controlled OS-process separate restart/reopen checks and complete comparison of what is actually supported: versions/source turns, owned images/audio, session properties, RAG/Memory evidence and duplicate/rebind semantics. Real private source remains read-only; all isolated committed data stays in ignored task-owned workspace, never real app profile/root. If physical Android device execution isn't available, produce a precise minimal physical test script/evidence checklist, keep Android direction `NOT_TESTED`.

### Stage 3 — G1B-D0 design and C-A preservation preparation, parallel-safe

Review actual WindowsSecretStore callers, bootstrap/root migration/restart authority and exact official community/session/backup ownership. Produce a falsifiable signed-off/not-signed-off design matrix for candidate A, B, and B+fresh-root. Distinguish tested independent isolation from durable authority, old writer fencing and Windows protector context. **NO runtime SecretStore load/save/delete, root-canonicalization changes, DPAPI migration, persisted namespace/intent schema, G2C or removal of `CREDENTIAL_APPROVAL_REQUIRED` without separate Project/user approval.**

For C-A, already approved **product policy** is original MomentPost/Task JSON bytes + independently owned copied media, no Task activation, no automatic deletion and fail on insufficient capacity. Build **unwired test-only** source/ownership/resource-graph/GC preflight and exact persisted schema/cutover decision spec. Do not install a real persistent sealed domain, rewrite GC or change StartupGate until the implementation contract is explicitly approved. Continue unrelated independent Stage 1/2 if one design question is blocked.

### Stage 4 — independent D-041/support partials where environment permits

Exercise/update existing code against test release metadata and package build; verify newer Desktop channels, offline/invalid provenance, installed support/recovery metadata. If graphical computer-control or isolated Windows account/VM is unavailable, **record NOT_TESTED**, prepare reproducible non-destructive isolated host script and final user test steps. No automatic installer/uninstall on user's daily Windows environment, no real profile/DPAPI, no old binary run without separate scoped authorization.

### Stage 5 — evidence/stop/commit

Commit on **same** `feature/phase8-fish-audio` in logically small clean checkpoints and push only verified code. For each checkpoint capture exact original/final source SHAs, changed paths, provenance, separately counted functional RED/final GREEN, exact JUnit XML, command arguments, process exit and SHA/byte index. Collect **only redacted synthetic/public** evidence in Git. Private real test archives/hashes/logs/staged roots stay ignored; report only explicitly scoped category/count/boolean and that tool-response transcriptions are **not original raw private stdout**. If a Gradle task executes zero tests or XML timestamps are stale, disqualify those files rather than counting them.

Final deliverable `P11_LR2_PROJECT_REVIEW_PACKET` should include: CHANGE_LEDGER, CODE_SOURCES, FINITE_TEST_MATRIX, REAL_ARCHIVE_PRIVATE_READ_RECEIPT, PROTOCOL_PARITY, C_A_AND_G1B_DESIGN_GATE, PREVIEW_A_G_UPDATED_MATRIX, DECISIONS_REQUIRED, ORIGINAL_EVIDENCE_INDEX and PROJECT_HANDOFF. Reflect honest provisional status in CURRENT; **do not assign Project ACCEPTED, update parity to EXACT/EQUIVALENT, close P11/Preview or promote baseline**.

Stop at `P11-LR2 PROJECT CODE REVIEW CANDIDATE` or a proven safety/tool/quota blocker; submit all safe independent work completed, no promise of continuous autonomous background execution. No merge, release, upstream sync, credentials export or prompt edit.

## V. Project follow-up after Codex completion

Project will compare real GitHub diff and raw evidence and accept/reject individual scopes; freeze/ask for explicit G1B topology/persistent transaction and S2 seal authorizations only when exact decisions are available. Actual Android physical directions, disposable Windows/DPAPI/installer/old-binary and D-016 final packaging confirmation remain separately gated. **0/7 release Gate PASS** remains until independently evidenced and signed after P11 close.
