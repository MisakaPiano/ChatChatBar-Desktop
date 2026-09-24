# Codex session bootstrap instruction

This repository is the long-term Windows downstream port of `SaltyFishOTL/ChatChatBar`.

This file is a reusable Codex control-point bootstrap. It is not a Phase 0 first-pass audit and must not hard-code an old upstream release as current truth.

## Git rules

- `origin = MisakaPiano/ChatChatBar-Desktop`
- `upstream = SaltyFishOTL/ChatChatBar`
- `master` mirrors upstream only
- `desktop` is the Desktop integration branch
- `feature/*` is for Desktop feature work
- `sync/*` is for upstream absorption
- never write to the official upstream repository

Unless a task explicitly says otherwise, implementation work starts from the verified `desktop` HEAD on a dedicated `feature/*` branch. Do not merge a feature branch into `desktop` before Project review.

## Read first

1. root `AGENTS.md`
2. `docs/desktop/00_PROJECT_SOURCE_MAP.md`
3. `docs/desktop/10_UPSTREAM_BASELINE.json`
4. `docs/desktop/21_CURRENT_STATE.md`
5. the relevant upstream `.agents/skills/*/SKILL.md`
6. `docs/desktop/13_FEATURE_PARITY.md`
7. `docs/desktop/14_UPSTREAM_COMPAT.md`
8. `docs/desktop/18_DECISIONS.md`
9. the phase-specific audit/contract document
10. `docs/desktop/23_CODEX_BUDGET.md` when the task has a quota estimate

For Phase 3, the controlling contract audit is `docs/desktop/22_PHASE3_CONTRACT_AUDIT.md`. Do not scan the entire repository indiscriminately.

## Control-point verification

Before modifying production files:

1. fetch `origin` and `upstream`;
2. report current branch, HEAD and working-tree status;
3. read the formal validated baseline from `10_UPSTREAM_BASELINE.json`;
4. observe `upstream/master` separately;
5. classify drift using D-022: `LOW`, `NORMAL`, `HIGH` or `BLOCKING`.

Do not promote the formal baseline merely because upstream advanced.

- `LOW` / `NORMAL`: continue the current milestone.
- `HIGH`: report it and schedule a sync window unless it invalidates the current slice.
- `BLOCKING`: stop implementation and return to Project review.

If network/fetch is unavailable, state exactly what was verified locally and what remains unverified.

## Long-term invariants

- Desktop is a downstream port, not a second CCB implementation.
- Transfer Package, persisted Entity and final Prompt/API request are separate layers.
- Final serialized logical messages / transport request are Prompt truth.
- Prefer shared JVM extraction over duplicate domain logic.
- Keep platform differences behind narrow adapters.
- Do not force the whole project into KMP.
- Do not rewrite upstream Prompt text as part of a platform port.
- Do not silently change Package schemas or legacy compatibility.
- Do not weaken data safety, recovery or validation to save quota.
- Do not commit credentials, API keys, tokens, OAuth secrets or private keys.
- Do not hide primary-path failures with silent fallback.

## Task contract

The concrete task must come from a Project task specification using `04_CODEX_TASK_TEMPLATE.md` or an equivalent explicit contract. Every implementation task should state baseline, recommended model/thinking, expected weekly quota, goal, allowed and forbidden scope, required sources, acceptance tests and handoff information.

Quota is a scheduling constraint, not an architecture constraint (D-019).

## Current milestone

Read `21_CURRENT_STATE.md` for authoritative current state.

At this checkpoint:
- Phase 0, 1 and 2 are complete;
- Phase 3A contract audit is complete;
- Phase 3 production implementation has not yet started;
- first production slice: 3B1 — Shared Entity / Package Contract Core;
- Phase 3 decisions: D-027, D-028 and D-029.

## Required handoff

Return observed upstream SHA/drift, starting Desktop SHA, branch, final commit SHA, changed files by responsibility, exact tests/compiles, unverified items, known risks, scope deviations and clean working-tree status.

Do not claim Project review PASS yourself.
