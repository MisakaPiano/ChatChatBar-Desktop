# CCB Desktop Project Source Map

## UPSTREAM
Current `SaltyFishOTL/ChatChatBar` source. Highest authority for CCB schemas and runtime semantics.

## CURRENT
The current `desktop` branch and this directory.

Phase-specific CURRENT control documents:
- `22_PHASE3_CONTRACT_AUDIT.md` — Phase 3 Package / Entity / import-export contract audit and implementation slicing.
- `23_CODEX_BUDGET.md` — Codex planning envelope and Phase/slice allocation.
- `24_CODEX_USAGE_EMPIRICAL_BASELINE.md` — observed Codex burn-rate telemetry, runtime ranges, model/Thinking evidence, and recalibration baseline.
- `28_PHASE4_CONTRACT_AUDIT.md` — Phase 4 Core Chat / Prompt / WorldBook contract audit, slicing and control point.

## REF
Historical editors, schema templates and design references. They must not override current upstream behavior.

- `CCB_EDITOR_REFERENCE_PACK.zip` — **REF — Mature User-Validated Editor UX / Workflow / Regression Evidence**. It is not upstream authority, Desktop specification, Package/schema authority, Prompt authority, or a Web implementation to transplant. Formal adoption decisions are recorded in `27_EDITOR_REFERENCE_ADOPTION.md`.

## FIXTURE
Cross-platform compatibility samples used for manual validation.

## ARCHIVE
Superseded plans.

`11_DESKTOP_PORT_AUDIT.md` is retained as the historical Phase 0 audit. Its 1.3.48-era source facts must never override the current baseline, current source or Phase 3 contract audit.

## Authority order
1. Current upstream source at the recorded baseline commit.
2. Current fork `desktop` branch.
3. `10_UPSTREAM_BASELINE.json` and `21_CURRENT_STATE.md`.
4. Other CURRENT Desktop docs.
5. Project instructions.
6. Old Project Source snapshots / REF / old chats.

Never permanently hard-code a historical schemaVersion.

## Read order
1. `00_PROJECT_SOURCE_MAP.md`
2. `10_UPSTREAM_BASELINE.json`
3. `21_CURRENT_STATE.md`
4. relevant upstream `.agents/skills/*/SKILL.md`
5. `13_FEATURE_PARITY.md`
6. `14_UPSTREAM_COMPAT.md`
7. `18_DECISIONS.md`
8. the relevant phase contract/audit document; for Phase 3 read `22_PHASE3_CONTRACT_AUDIT.md`, for Phase 4 read `28_PHASE4_CONTRACT_AUDIT.md`
9. `23_CODEX_BUDGET.md` when planning or reviewing Codex work
10. `24_CODEX_USAGE_EMPIRICAL_BASELINE.md` when estimating runtime/quota/interruption risk
11. `27_EDITOR_REFERENCE_ADOPTION.md` when planning Desktop editor/image UX, batch authoring workflows, or related regression tests

Do not indiscriminately read the entire repository. Widen source reading only from the relevant Skill / contract entry points.

## High-value upstream entry points
- AGENTS.md
- .agents/skills/chatbar-feature-map/SKILL.md
- app/app/src/main/java/com/example/chatbar/ChatBarApp.kt
- data/local/JsonFileStorage.kt
- domain/card/CardTransferModels.kt
- domain/card/CharacterCardTransferService.kt
- domain/worldbook/WorldBookEngine.kt
- domain/prompt/PromptTemplates.kt
- domain/chat/PromptAssembler.kt
- domain/chat/ContextWindowManager.kt
- ui/chat/ChatViewModel.kt
- domain/chat/StreamingChatService.kt
- domain/chat/SaveSlotPackageStorage.kt
- domain/rag/
- domain/memory/
- domain/image/
- domain/voice/
- domain/moment/
- domain/community/
- domain/update/
