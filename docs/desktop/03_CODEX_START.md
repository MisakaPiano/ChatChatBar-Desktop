# Codex first-pass instruction

This repository is the long-term Windows downstream port of `SaltyFishOTL/ChatChatBar`.

## Git rules

- `origin = MisakaPiano/ChatChatBar-Desktop`
- `upstream = SaltyFishOTL/ChatChatBar`
- `master` mirrors upstream only
- `desktop` is the Desktop integration branch
- `feature/*` for Desktop features
- `sync/*` for upstream sync
- never write to the official upstream repository

## Current control point

GitHub `desktop` is the CURRENT documentation/source-of-truth branch.
The controlling ChatGPT Project has validated the declared Desktop baseline and separately observes current upstream state. An observed upstream commit never becomes the Desktop baseline until a sync audit and compatibility validation explicitly promote it.

Recorded upstream baseline:

- repo: `SaltyFishOTL/ChatChatBar`
- branch: `master`
- version: `1.3.49`
- commit: `6b1817cd2dc65e6509e6ae350bef1a8e1a1250de`

Currently observed upstream:

- version: `1.3.49`
- commit: `6b1817cd2dc65e6509e6ae350bef1a8e1a1250de`
- drift from declared baseline: none
- Desktop compatibility: validated

Your job is to independently verify these facts from the local clone/remotes and report discrepancies.
Always report the declared validated baseline, observed `upstream/master`, drift status, and compatibility status separately. A future observed upstream advance must not automatically update the validated baseline.

## Read first

1. root `AGENTS.md`
2. `.agents/skills/chatbar-feature-map/SKILL.md`
3. `docs/desktop/00_PROJECT_SOURCE_MAP.md`
4. `docs/desktop/10_UPSTREAM_BASELINE.json`
5. `docs/desktop/21_CURRENT_STATE.md`
6. `docs/desktop/13_FEATURE_PARITY.md`
7. `docs/desktop/14_UPSTREAM_COMPAT.md`
8. `docs/desktop/17_ROADMAP.md`
9. `docs/desktop/11_DESKTOP_PORT_AUDIT.md`
10. `docs/desktop/12_DESKTOP_ARCHITECTURE.md`
11. `docs/desktop/16_TEST_MATRIX.md`
12. `docs/desktop/19_DATA_COMPAT.md`

For each specific feature you inspect, read the matching upstream `.agents/skills/*/SKILL.md` before widening the search. Do not scan the entire repository indiscriminately.

## FIRST PASS IS WORKING-TREE / SOURCE READ-ONLY

Git metadata-only operations are allowed, including `git fetch`, reading remote refs, `git ls-remote`, and other operations that do not create, modify, or delete tracked or untracked working-tree files.

Do not edit, format, create, or delete tracked or untracked working-tree files. Do not stage, commit, push, merge, or rebase. Do not run destructive Git commands.
Do not create `:desktopApp`.
Do not start Phase 1.
Do not modify Android business source.
Do not change prompt text.

## Required audit

### 1. Git / remotes / branch state

Report:
- current branch
- HEAD SHA
- working-tree status
- `origin` and `upstream` remotes
- `master` SHA
- `desktop` SHA
- declared validated baseline version and SHA
- observed `upstream/master` version and SHA
- whether `master` equals the declared validated baseline
- whether observed `upstream/master` has drifted from that baseline
- whether compatibility with the observed upstream has been validated
- ahead/behind relationship of `desktop` versus `master`

Do not update the declared baseline merely because observed upstream has advanced. Report declared baseline, observed upstream, drift status, and compatibility status separately.

If network/fetch is unavailable, say exactly what was verified locally and what remains unverified.

### 2. Baseline/build facts

Verify from source, not docs alone:
- Gradle root is `app/`
- current modules
- Kotlin version
- AGP version
- JDK/JVM target
- compileSdk / targetSdk / minSdk
- current versionName
- whether primary business Entity persistence still uses `JsonFileStorage`
- whether any active Room/ObjectBox business persistence path exists
- whether auxiliary SQLite/catalog/dictionary/index storage exists and who owns it

### 3. Transfer/package schemas

Verify actual source constants/validation for:
- `CharacterCardPackage` current schema and accepted range
- `FormatCardPackage` current schema and accepted range
- `WorldBookPackage` current schema
- SaveSlot package current schema and legacy compatibility
- Character v9 embedded `defaultFormatCard` behavior

Keep Package / Entity / Prompt-runtime facts separate.

### 4. High-risk runtime map

Read only the focused entry points/skills needed to confirm the docs are still directionally correct for:
- Character/package transfer + CCB PNG + ST compatibility
- JsonFileStorage
- PromptTemplates / PromptAssembler / ContextWindow / final ChatViewModel message ordering
- WorldBook runtime/timed effects
- Model/provider/SSE/thinking/local HTTP
- RAG
- long-term Memory
- SaveSlot
- NovelAI/image runtime
- Fish Audio
- Moments
- Community
- Shared Import
- app update
- QQ voice platform dependency

Do not propose reimplementations in this pass. Only identify factual mismatch, missing ownership, or platform boundary risk.

### 5. Official Skill inventory

Enumerate `.agents/skills/*/SKILL.md` from the checked baseline and confirm whether the documented inventory of 20 Skills in `14_UPSTREAM_COMPAT.md` is exact.
Report added/removed/renamed/mismatched skills if any.

### 6. Documentation consistency

Check at least:
- `10_UPSTREAM_BASELINE.json`
- `11_DESKTOP_PORT_AUDIT.md`
- `12_DESKTOP_ARCHITECTURE.md`
- `13_FEATURE_PARITY.md`
- `14_UPSTREAM_COMPAT.md`
- `16_TEST_MATRIX.md`
- `17_ROADMAP.md`
- `18_DECISIONS.md`
- `19_DATA_COMPAT.md`
- `21_CURRENT_STATE.md`

Report stale, contradictory, unsupported, or missing facts.
Do not edit them.

### 7. Smallest Phase 1 proposal

Only if the audit finds no blocking architecture error, propose the smallest Phase 1 bootstrap task consistent with the docs.
It should remain limited to an empty Compose Desktop application/module, Desktop entry point/window, minimal data-root/environment skeleton, build/run instructions, and Android compile regression protection.
Do not implement it.

## Required output format

Return one report with:

1. `Git state`
2. `Baseline verdict` — PASS / DRIFT / UNVERIFIED
3. `Schema verdict` — PASS / DRIFT
4. `Architecture facts verdict` — PASS / DRIFT
5. `Skill inventory verdict` — PASS / DRIFT
6. `Docs findings` — each finding with file + exact fact
7. `Risks / blockers`
8. `Smallest Phase 1 task`
9. `Files modified` — must say `none`

Do not make a commit. Return the report to the user so the ChatGPT Project can review it first.
