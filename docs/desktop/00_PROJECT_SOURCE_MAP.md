# CCB Desktop Project Source Map

## UPSTREAM

Current `SaltyFishOTL/ChatChatBar` source. Highest authority for CCB schemas and runtime semantics.

## CURRENT

The current fork `desktop` branch and this directory.

Primary CURRENT control documents:

- `71_PHASE7_SLICE_B1_ACCEPTANCE.md` — P7 Slice B1 chat-image display/actions **ACCEPTED / FROZEN / NOT MERGED** at production `8cd718e`; separates Project code review, user Windows acceptance and Codex local verification. Other Phase-7 acceptance remains open.
- `70_PHASE7_OUTSTANDING_UX_ISSUES.md` — P7-WIN-01/02/03 image auxiliary-window maximize, CCB icon and initial-size issues are OPEN pending window-specific manual reproduction; unchecked acceptance is in `66`.
- `69_PHASE7_SLICE_A_ACCEPTANCE.md` — P7 Slice A Default Model Visibility **ACCEPTED / FROZEN** at `06aaecb`; separates Project code review, user manual acceptance and Codex local validation. Phase 7 remains not accepted or merged.
- `60_PHASE7_UPSTREAM_IMAGE_DELTA_AUDIT.md` / `61_PHASE7_SELECTIVE_FORWARD_PORT_MATRIX.md` — complete observed image delta classification and individually authorized selective official ports; not full 1.4.4 sync or baseline promotion.
- `62_PHASE7_IMAGE_PRODUCT_CLOSURE.md` / `63_PHASE7_TAG_CARET_CONTRACT.md` / `65_PHASE7_CODEX_START.md` — current Program Control authorized image product closure.
- `64_PHASE7_SCOPE_OWNER_UPDATE.md` — current amendment to `55_PHASE7_DEFERRED_OWNER_MAP.md`; window-local image conveniences moved into P7, shell/general preset/global IA owners remain deferred.
- `66_PHASE7_FINAL_MANUAL_ACCEPTANCE_CN.md` / `68_PHASE7_CURRENT_UPSTREAM_IMAGE_REVIEW.md` — current pending manual checklist and final-source regression/package/smoke evidence. `67_SCREENSHOT_INDEX.md` indexes REF inputs and separately labelled local fixture validation images.
- `54_PHASE7_FINAL_PRODUCT_CLOSURE.md` / `56_PHASE7_FINAL_PRODUCT_CODEX_START.md`, `57` / `59` — historical final-product instructions/checklist/evidence; reviewed runtime retained and current image changes follow `60`–`65`.

- `48_PHASE7_UX_PRESENTATION_CLOSURE.md` / `50_PHASE7_UX_PRESENTATION_CODEX_START.md` — historical presentation closure after the second manual UX HOLD; existing R2/R1 runtime remains authoritative.
- `49_NOVELAI_PROVIDER_CAPABILITY_DRIFT_AUDIT.md` — provider capability boundary record; no general model/sampler expansion. Only individually authorized current image request changes in `60` are adopted.
- `51_PHASE7_UX_PRESENTATION_MANUAL_ACCEPTANCE_CN.md` / `53_PHASE7_UX_PRESENTATION_REVIEW.md` — historical presentation acceptance checklist/evidence, superseded by `57`/`59`. `52_SCREENSHOT_REFERENCE_INDEX.md` and `refs/phase7-ux-presentation/` are REF only.

- `10_UPSTREAM_BASELINE.json` — validated upstream baseline plus latest observation metadata. Observation never promotes compatibility by itself.
- `13_FEATURE_PARITY.md` — formal Desktop parity state.
- `14_UPSTREAM_COMPAT.md` — upstream compatibility and domain-impact map.
- `15_SYNC_PLAYBOOK.md` — controlled upstream sync procedure.
- `16_TEST_MATRIX.md` — validation evidence and outstanding test ownership.
- `17_ROADMAP.md` — Phase ownership and future work.
- `18_DECISIONS.md` — accepted architecture/product decisions.
- `21_CURRENT_STATE.md` — current project state, distinguishing integrated acceptance from pending feature review.
- `38_PHASE7_FINAL_REVIEW.md` — historical pre-R2 implementation and automated/package evidence; consolidated user UX acceptance failed.
- `39_PHASE7_R2_CCB_STRUCTURE_MAP.md` / `40_PHASE7_R2_UX_CONTRACT.md` — user-authorized R2 workflow mapping and UX repair contract after failed manual acceptance; screenshot evidence remains REF.
- `43_PHASE7_R2_MANUAL_ACCEPTANCE.md` / `44_PHASE7_R2_REVIEW.md` — R2 acceptance checklist and current repair evidence.
- `45_PHASE7_IMAGE_SYSTEM_FUNCTION_MAP.md` / `46_PHASE7_IMAGE_SYSTEM_REAUDIT_MATRIX.md` / `47_PHASE7_R2_REAUDIT_AMENDMENT.md` — additive user-authorized vertical re-audit; all GAP rows must close within the current R2 task before final review. The map is traceability, not a replacement semantic authority.
- `31_PHASE6_COMPLETION_HANDOFF.md` — Phase 6 closure record.
- `32_PHASE7_ENTRY_HANDOFF.md` — CURRENT Project handoff for direct Phase 7 entry.
- `33_PHASE7_CODEX_MEGA_TASK.md` — CURRENT large-scope Codex execution brief for completing Phase 7 without micro-task churn.

Historical phase control documents remain evidence, not CURRENT status:

- `22_PHASE3_CONTRACT_AUDIT.md`
- `28_PHASE4_CONTRACT_AUDIT.md`
- `29_PHASE4_COMPLETION_HANDOFF.md`
- `30_PHASE5_CONTRACT_AUDIT.md`

Planning/reference documents:

- `23_CODEX_BUDGET.md` — planning envelope and task allocation.
- `24_CODEX_USAGE_EMPIRICAL_BASELINE.md` — historical Codex runtime/quota telemetry.
- `27_EDITOR_REFERENCE_ADOPTION.md` — adopted editor/image UX and regression reference rules.

## REF

Historical editors, schema templates and design references. They must not override current upstream behavior.

- `CCB_EDITOR_REFERENCE_PACK.zip` — **REF — Mature User-Validated Editor UX / Workflow / Regression Evidence**. It is not upstream authority, Desktop specification, Package/schema authority, Prompt authority, or a Web implementation to transplant.

## FIXTURE

Cross-platform compatibility samples used for validation.

## ARCHIVE

Superseded plans and handoffs.

`11_DESKTOP_PORT_AUDIT.md` remains the historical Phase 0 audit. Its old source facts must never override the current baseline, current source or CURRENT docs.

## Authority order

1. Current upstream source at the recorded/inspected commit.
2. Current fork `desktop` branch.
3. CURRENT `docs/desktop/*`.
4. Project Instructions.
5. Project Sources / REF / historical snapshots.
6. Old chats and old handoffs.

Never permanently hard-code a historical schemaVersion.

## Read order

For a normal task:

1. `00_PROJECT_SOURCE_MAP.md`
2. `10_UPSTREAM_BASELINE.json`
3. `21_CURRENT_STATE.md`
4. relevant upstream `.agents/skills/*/SKILL.md`
5. `13_FEATURE_PARITY.md`
6. `14_UPSTREAM_COMPAT.md`

Then read only what the task requires:

7. `32_PHASE7_ENTRY_HANDOFF.md` when starting a new Project conversation or entering Phase 7.
8. `33_PHASE7_CODEX_MEGA_TASK.md` for Phase-7 original scope; current continuation uses `60`–`65`, evidence `68` and manual checklist `66`.
9. `15_SYNC_PLAYBOOK.md` only when an actual sync window is opened.
10. `16_TEST_MATRIX.md` for validation planning/review.
11. `17_ROADMAP.md`
12. `18_DECISIONS.md`
13. the relevant historical phase contract only when needed.
14. `23_CODEX_BUDGET.md` / `24_CODEX_USAGE_EMPIRICAL_BASELINE.md` only when quota planning is actually needed.
15. `27_EDITOR_REFERENCE_ADOPTION.md` for editor/image UX and Phase-7 image-workspace planning.

Do not indiscriminately read the entire repository.

## Current phase boundary

Phase 0–6 are complete/accepted.

Phase 6 close, post-close cleanup and reproducibility verification are complete.

Phase 7 remains on the existing feature branch, not accepted or merged. Follow current selective image closure `60`–`65`, final evidence/package `68` and pending manual checklist `66`; `64` amends deferred owner map `55`. Prior R2/presentation/final-product handoffs are historical. Do not reopen Phase 6 or parked sync, promote baseline, create a new phase, or restart micro-task reviews. Real NovelAI generation remains frozen at 1/8; zero additional generation/Enhance/Upscale requests are authorized.

P7 Slice A Default Model Visibility is separately **ACCEPTED / FROZEN** at `06aaecb`; see `69`. Its acceptance does not close the Phase-7-wide manual checklist.

P7 Slice B1 chat-image display/actions is separately **ACCEPTED / FROZEN / NOT MERGED** at production `8cd718e`; see `71`. Slice C and remaining Phase-7 manual checks are not closed by this record.

## High-value upstream entry points

- `AGENTS.md`
- `.agents/skills/chatbar-feature-map/SKILL.md`
- relevant current Skill files
- `app/app/src/main/java/com/example/chatbar/ChatBarApp.kt`
- `data/local/JsonFileStorage.kt`
- `domain/card/CardTransferModels.kt`
- `domain/card/CharacterCardTransferService.kt`
- `domain/worldbook/WorldBookEngine.kt`
- `domain/prompt/PromptTemplates.kt`
- `domain/chat/PromptAssembler.kt`
- `domain/chat/ContextWindowManager.kt`
- `ui/chat/ChatViewModel.kt`
- `domain/chat/StreamingChatService.kt`
- `domain/chat/SaveSlotPackageStorage.kt`
- `domain/rag/`
- `domain/memory/`
- `domain/image/`
- `domain/voice/`
- `domain/moment/`
- `domain/community/`
- `domain/update/`
