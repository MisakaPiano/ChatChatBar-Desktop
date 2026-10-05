# CCB Desktop Project Source Map

## UPSTREAM

Current `SaltyFishOTL/ChatChatBar` source. Highest authority for CCB schemas and runtime semantics.

## CURRENT

The current fork `desktop` branch and this directory.

Primary CURRENT control documents:

- `10_UPSTREAM_BASELINE.json` — validated upstream baseline plus latest observation metadata. Observation never promotes compatibility by itself.
- `13_FEATURE_PARITY.md` — formal Desktop parity state.
- `14_UPSTREAM_COMPAT.md` — upstream compatibility and domain-impact map.
- `15_SYNC_PLAYBOOK.md` — controlled upstream sync procedure.
- `16_TEST_MATRIX.md` — validation evidence and outstanding test ownership.
- `17_ROADMAP.md` — Phase ownership and future work.
- `18_DECISIONS.md` — accepted architecture/product decisions.
- `21_CURRENT_STATE.md` — current integrated project state.
- `31_PHASE6_COMPLETION_HANDOFF.md` — current conversation/project handoff after Phase 6 closure and before Phase 7 entry.

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

7. `31_PHASE6_COMPLETION_HANDOFF.md` when starting a new Project conversation or entering the Phase-7 boundary.
8. `15_SYNC_PLAYBOOK.md` for upstream observation/sync work.
9. `16_TEST_MATRIX.md` for validation planning/review.
10. `17_ROADMAP.md`
11. `18_DECISIONS.md`
12. the relevant historical phase contract only when needed.
13. `23_CODEX_BUDGET.md` / `24_CODEX_USAGE_EMPIRICAL_BASELINE.md` for Codex planning.
14. `27_EDITOR_REFERENCE_ADOPTION.md` for editor/image UX and Phase-7 image-workspace planning.

Do not indiscriminately read the entire repository.

## Current phase boundary

Phase 0–6 are complete/accepted.

Before any Phase-7 production implementation:

1. refresh/verify the Project handoff and live Git control point;
2. complete post-Phase-6 safe local cleanup with rebuild/test/package verification;
3. perform a fresh upstream image/NovelAI impact review;
4. freeze the Phase-7 contract against the selected formal authority.

Phase 7 is not started merely because upstream 1.4.4 has been observed.

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
