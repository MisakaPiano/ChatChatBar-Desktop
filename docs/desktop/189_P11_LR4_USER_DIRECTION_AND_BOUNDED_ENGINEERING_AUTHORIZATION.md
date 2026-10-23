# 189 — P11-LR4 User Decision: B + Fresh Root, Lossless Voice, C-A Sealing

**CURRENT USER DECISION AND BOUNDED ENGINEERING AUTHORIZATION — 2026-10-24 JST.**
**188'S THREE PRODUCT DIRECTIONS ARE USER APPROVED. LR4 UNWIRED/SYNTHETIC ENGINEERING MAY PROCEED. PRODUCTION G1B/G2C/RESTORE AND RELEASE ARE NOT AUTHORIZED.**

## Explicit authority

Following Project [187 LR3 qualified acceptance](187_P11_LR3_BOUNDED_PROJECT_ACCEPTANCE.md), the user was asked explicitly whether to approve **all three** options in [188](188_P11_PRODUCTION_DIRECTION_USER_DECISION_CANDIDATE.md) **and the specifically limited LR4 START work**. User replied: **「同意，给我codex指令吧。我们目前还差哪几个没完成？」**. This decision is scoped to precisely the three listed product directions and bounded LR4 development; it is not a blanket approval to write existing user data/credentials or publish a release.

This record supersedes the earlier **USER CHOICE REQUIRED / DRAFT** label of 188 *only with respect to the product directions below and LR4's bounded start authority*. All unresolved implementation choices and blockers in 188 remain unresolved until separate design/source/platform acceptance.

## Three agreed product directions

**R-LR4-ROOT — B + inactive fresh data root as the TARGET direction.** Prepare imported complete data and isolated protected credential participants **in an inactive new root**, then only after full verification, durable recovery authority and user consent consider a restart-gated cutover. Retain original data root and original/legacy profile-shared protected key bytes. Old unknown/offline shared-key consumers remain potential hazards. A single profile-owned discoverable authority is an **architectural candidate**, not approval of its physical path, encoding, persisted version, Windows protector account identity or write operation. Do not enable old-key fallback or automatically delete/overwrite any Key.

**R-LR4-VOICE — lossless dormant historic recordings.** Retain **exact original** voice record/version identity/audio bytes for orphaned *version references* (e.g. LR3's unknown nonblank explicit IDs), with independently verifiable safe ownership/re-export once implemented. They must remain **non-runnable/non-autoplay**, never silently rebind to currently active version or get dropped. If complete and secure preservation cannot be demonstrated, **refuse the entire archive before commit**; never show success after partial recovery. The LR3 private result of 199 known +36 unknown nonblank versions is scoped only to the first failing nested package, not complete provenance.

**R-LR4-CA — C-A inert MomentPost/Task raw archival.** Preserve exact MomentPost/Task source JSON bytes, separately owned exact media for `senderAvatar`, `imagePath`, `generatedImageMetadata.imagePath`, all typed references/inventory, and source status. No scheduler/Task execution, AI/NovelAI/network activity, silent automatic cleanup or media aliasing. Fail on insufficient space/overflow/unknown source schema, with old source unchanged; protected snapshot/full-backup/GC/root switch/Portable participation and P13 takeover require separate engineering proof before production publication.

These choices do **not** change official transport Package/Entity fields, WorldBook rules, Prompt ownership, session version semantics, serialized Provider requests or validated upstream baseline.

## LR4 automatically permitted under this one approval

Execute [190 LR4 Codex engineering contract](190_P11_LR4_AUTHORIZED_UNWIRED_ENGINEERING_CODEX_TASK.md) on the existing feature branch, only for:
- Test-only read-only continuation of strict large full-backup inspection **past semantically unsupported historic voice references** to independently verify later outer ZIP central/inventory/authentication, without turning semantic BLOCKED into RESTORE PASS.
- New **pure internal, UNWIRED** Kotlin typed plan/preparer model(s), using synthetic root/protector/credential identities, sealed raw record/media candidate witnesses, and safety barriers. New code must have **no existing production callers**, no real SecretStore/DPAPI, no selected durable path/schema or root authority commit.
- Synthetic, task-owned **new** isolated roots and fake credentials for bounded count/capacity/duplicate/foreign-change/cancel/failure/crash-before-publication tests, with genuine RED→GREEN and exact original evidence.
- Bounded use of **previously authorized `H:\手机CCB导出` as READ-ONLY source**, one explicitly selected file via native locked handle, no source modifications, private local secrets/password and no upload of personal data. An authenticated read cannot authorize restoration.
- Verified small commits/push on `feature/phase8-fish-audio` only, including redacted raw evidence and final Project-review packet.

## Explicitly withheld / next decision gate

No actual `WindowsSecretStore` load/save/delete, real DPAPI/profile/SID, user data-root mutation, production persisted namespace/identity/journal, StartupGate/backup/GC/restore wiring, real credential migration, G2C or removal of `CREDENTIAL_APPROVAL_REQUIRED`. No live task scheduling, real API/Provider spending, real archive rewriting, old EXE execution, installer/Android device/ADB/OS destructive testing, upstream sync, Prompt edits, `desktop` merge, release/tag or Public Preview PASS. Signed G1B/C-A topology *direction* does not imply selection of a deployable state-machine design, old-binary fencing, profile anchor location, retention expiry or adequate Community protected sink.

`LR3 CLOSED / QUALIFIED`; `LR4 AUTHORIZED / NOT YET OBSERVED RUNNING`; `G1B production OPEN`; `G2C NOT AUTHORIZED`; `P11 PENDING`; `D-038 BLOCKED`; `Post-P8 HOLD`; `Public Preview HOLD`; **A–G 0/7 formally signed**. Validated upstream remains 1.4.1. Codex's subsequent completion is a **PROJECT CODE REVIEW CANDIDATE**, not milestone acceptance.

## Release separation and remaining packages

From [182 remaining-work scorecard](182_PUBLIC_PREVIEW_REMAINING_WORK_AND_P11_LR2_CODEX_TASK.md), outstanding **unequal engineering packages** remain: (1) entire official145 `.cbbackup` complete semantic+transactional restore, (2) protected data-root/SecretStore G1B/G2C coordinated recovery and Community, (3) C-A Moments durable sealed bytes/media/backup/GC, (4) full SaveSlot Android cross-device/user/OS-process and session/RAG/Memory/voice acceptance, (5) D-041 real installer/update/support/release truth. Three external verification categories: real Android approved direction tests; disposable Windows installer/DPAPI/old binary/crash-restart; D-016 direct-author permission receipt and user's explicit release approval. **0/7 A–G is a release-signature count, NOT a percent completion.** P12–P17 most feature implementation can follow Public Preview, while P18 full parity/1.0 remains.
