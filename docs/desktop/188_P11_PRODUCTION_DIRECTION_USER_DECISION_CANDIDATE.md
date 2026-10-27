# 188 — P11 Production Direction: Consolidated User Decision Candidate

**PROJECT RECOMMENDATION DRAFT / USER CHOICE REQUIRED / NOT AN APPROVED PERSISTENT SCHEMA OR G2C EXECUTION CONTRACT — 2026-10-24 JST.**

Authority: [187 LR3 acceptance](187_P11_LR3_BOUNDED_PROJECT_ACCEPTANCE.md); user-approved D-037/D-038 in `18_DECISIONS.md`, [93](93_PUBLIC_PREVIEW_POLICY.md), [95](95_PREVIEW_FULL_APP_BACKUP_PORTABILITY_GATE.md), [157](157_P11_S2_G1_USER_CONFIRMED_PARTIAL_POLICY_DIRECTIONS.md), [158](158_P11_S2_G1_R07_DIRECT_IMPORT_PASSWORD_AND_CREDENTIAL_POLICY.md), and [LR3 G1B/C-A decision packet](evidence/p11-lr3-20261024/P11_LR3_PROJECT_REVIEW_PACKET/G1B_CA_USER_DECISION_PACKET.md). This proposal is intentionally **not** a retroactive declaration of user consent. Formal upstream baseline ChatBar1.4.1 and selected official1.4.5 backup authority remain separate.

## Recommended three linked product directions for one user decision

**Choice 1 — Creds and root replacement: conditionally select B + inactive fresh destination root as the TARGET architecture, not a production commit authorization.**

New imported/restore datasets should be prepared in a fresh inactive root, with **root-scoped, protected credential participants** instead of mutating a profile-shared Key in place. Reuse existing migration's copy/retain-old/snapshot/authority-switch/restart safety principles, **not** its old copy operation as a completed full-app restore transaction. Use one profile-owned discoverable recovery coordinator/authority *conceptually separate from any chosen data root*; exact path, schema, stable root/protector/account binding, monotonic generation, locks, flush and crash-consistent persistence remain independent design gates. Existing legacy profile-shared ciphertext/OLD roots remain protected and retained; no silent fallback, overwrite, key erasure or expiry. Same-name/ID different account/provider/endpoint requires user-visible conflict explanation and explicit authorization or refusal; CommunitySession requires a bounded protected lifecycle/sink before complete import.

**Choice 2 — Historic voice versions: prefer LOSSLESS, NON-RUNNABLE ARCHIVAL PRESERVATION instead of silently dropping/rebinding.**

For real Android whole-backup/nested v8 items with a nonnull explicit voice version absent from the currently available message version set, retain original exact voice/entity bytes and owned audio/media, category + provenance and re-export integrity in an independently protected dormant domain. Do **not** map that unknown ID to current version, delete the voice or automatically play it as a current-version clip. A prior original may be preserved even though immediate Desktop active playback is unsupported; the UI/recovery receipt must clearly disclose dormant status and unsupported playback. If a complete byte/reference/ownership/transactionally recoverable representation **cannot** be proven, refuse the **whole** import before commit, with source unchanged. The LR3 observation 36 unknown-vs199 known is **only the first failing nested package**; not all-content validation or proof of why versions became historic.

**Choice 3 — C-A MomentPost/Task: sealed, inert raw-record + owned-media preservation before P13 implementation.**

Keep original Android source user-owned and unchanged. Future P11 sealed target holds exact MomentPost/Task JSON bytes, individually proven independent copies of senderAvatar, imagePath and generatedImageMetadata.imagePath, and verified graph/resource identity. No live Task activation, Scheduler, AI/NovelAI/network use, automatic deletion or silent unknown-store drop. Capacity includes old/new copies, staging/rollback and an explicitly approved reserve; insufficient capacity rejects without deleting old data. Snapshot, Desktop full backup, GC/pending-deletions, root migration/Portable and downgrade guards must protect the seal. P13 takeover will require separately versioned staged/rollback migration preserving original seal until separately authorized retirement.

These are **product-direction recommendations**, not permission to ship a partial migration or redefine official Package/Entity/Prompt/WorldBook/SaveSlot semantics.

## Known STOP conditions the recommendation cannot waive

- An old/unregistered/offline third consumer may still write the original shared credential Key. A cooperative lock or new marker does not fence an old executable running with the same Windows user. **Old-binary downgrade isolation remains a Preview release blocker until actual enforceable design + disposable Windows countertests.**
- DPAPI/SID/account/protector identity, clone and Portable rules, profile-move/key re-entry, old shared-secret migration and consent, Community login/session expiry/refresh/logout and rollback are unproved.
- Whole-data + SecretStore cannot become all-or-none through multiple ordinary file atomic moves. Need durable discoverable authority, old/new participant readback, receipt-loss recovery, ALL_OLD/ALL_NEW and ambiguous-publication shutdown/restart proof before runtime activation.
- A raw Moments/voice record and media copy alone is **not** a full-backup/GC-participating persistent domain; unknown schema/ownership/capacity fail closed.
- Real official1.4.5 Android→Desktop→Android, Desktop→Desktop complete restore, and physical Android/`Windows installer+DPAPI` acceptance are independent evidence requirements. Source code86/v2 is not equivalent to 1.4.5 and not proof of full archive integrity in LR3.
- All existing Prompt texts are owned by upstream; no incidental edits. Do not change validated upstream baseline or merge `desktop` to make a gate appear green.

## If the user approves these directions: allowable LR4 START scope

Approval of all three directions can authorize one **bounded preparatory Codex LR4** on the existing feature branch, **NOT** live G2C/persisted production cutover:
1. **Test-only read-only full-archive diagnostic continuation:** after classifying unresolved nested voice relationships, retain an always-`SEMANTICALLY_BLOCKED` result yet continue validation of later entries/outer authenticated EOF + central/inventory where safely supported. Never redefine semantics as pass, drop records, log private content or serialize original as transformed source. Preserve separately scoped gate result for every domain. Perform source-backed synthetic RED→GREEN and only user-authorized one-file read-only real test.
2. **Unwired internal typed preparation contracts:** root/protector/account/endpoint/Key-purpose/old-new participant identity and inert source graph/sealed-media candidate **only in new clearly nonwired Kotlin components**, with no production parser/Startup/SecretStore/Journal/GC/UI callers. No selected persisted schemaVersion, physical profile anchor path or default key migration; use synthetic fake credentials and task-owned disposable roots only. All fail-closed while unknown identity/global consumer/Community/old-writer closure remains.
3. **C-A and historic voice inert preservation synthetic feasibility:** prove exact record bytes, independent owned physical media, storage budget/overflow, duplicate owner/ID/reference failures, crash/cancel before any publication, no scheduler/AI side effects. These are *not* production data safety proof or authorized real restore.
4. **Independent evidence and Project review:** unique source SHA, actual XML/exit and fail/green, known gaps, no private bytes in Git. Stop at `P11-LR4 PROJECT CODE REVIEW CANDIDATE`. No auto Project acceptance/parity promotion. Prepare next exact persistent/writer contract separately.

**LR4 still prohibited even after direction approval:** actual real SecretStore load/save/delete, real DPAPI testing in current profile, existing user data roots, persisted namespace/schema publication, application Startup/restore/GC and P13 task activation, G2C, old EXE/install/Android/ADB experiments without separately approved disposable host, real archive rewriting, Prompt changes, release, protected-branch merge/upstream sync.

## Explicit approval question

**Recommended combined user action:** “同意 188 的三项产品方向，并批准仅按以上 LR4 START 范围进行新组件和合成测试。G2C、持久化真实迁移、真实 SecretStore/DPAPI、旧版防写实测及 Public Preview 发布仍需单独批准。”

If approved, register an exact user-authorization receipt and prepare the LR4 Codex execution contract against the then-current feature HEAD. If not approved, retain this DRAFT; continue only source-based analysis or narrow unaffected safety work. Do not infer approval from silence or from 187's engineering PASS.

**Until explicit decision:** G1B OPEN / G2C NOT AUTHORIZED / P11 PENDING / D-038 BLOCKED / Post-P8 HOLD / Public Preview HOLD / Preview A–G 0/7 signed; formal1.4.1 and `desktop` unchanged.
