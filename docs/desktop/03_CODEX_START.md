**ACTIVE SINGLE EXECUTION TASK:** [203_P11_TO_PUBLIC_PREVIEW_CONTINUOUS_CODEX_TASK.md](203_P11_TO_PUBLIC_PREVIEW_CONTINUOUS_CODEX_TASK.md). Execute all phases in one feature-branch Codex run. No interim Project approval/wait.

# Codex session bootstrap instruction

This repository is the long-term Windows downstream port of `SaltyFishOTL/ChatChatBar`.

This file is a reusable Codex control-point bootstrap. **CURRENT USER DIRECTIVE: one uninterrupted implementation program for P11 plus Public Preview A–G, under `01_PROJECT_INSTRUCTIONS.md` and D-046.** Previous phase-by-phase stop/approval requirements are historical. Codex owns consecutive implementation, testing and commits without waiting for Project review of every phase. Project reviews after implementation.

## Git rules

- `origin = MisakaPiano/ChatChatBar-Desktop`
- `upstream = SaltyFishOTL/ChatChatBar`
- `master` mirrors upstream only
- `desktop` is the Desktop integration branch
- `feature/*` is for Desktop feature work
- `sync/*` is for upstream absorption
- never write to the official upstream repository

**Current work must continue from verified `feature/phase8-fish-audio` HEAD**, not restart on `desktop` or create another feature for every phase. Commit/push checkpoints there and continue automatically; do not merge into protected `desktop` before final integration approval.

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
11. `docs/desktop/24_CODEX_USAGE_EMPIRICAL_BASELINE.md` when estimating runtime/quota/interruption risk

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
- `BLOCKING`: pause only directly affected work, report the source incompatibility and fix it; continue all independent implementation phases. Do not publish Preview with an unaddressed release-impact BLOCKING issue.

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

The current user has authorized a **single continuous P11 + Preview A–G umbrella implementation**. Codex can split the engineering into internal phases, self-plan the next phase and continue after each tested checkpoint without a new Project task, authorization receipt or interim PASS. Use `04_CODEX_TASK_TEMPLATE.md` only for actual required handoffs, not as a reason to defer coding. Quota estimates are optional planning metadata; they cannot stall production fixes. If one phase is blocked by a real device/manual step, request the exact user action and continue unrelated phases.

Quota is a scheduling constraint, not an architecture constraint (D-019).

## Current milestone

Read `21_CURRENT_STATE.md` for authoritative current state.

At the current checkpoint, Phase 0–10 have substantial implementation history. **Immediate goal: implement P11 `.cbbackup` and `.cbsave` real Android↔Desktop portability, then complete Preview A–G, installable Windows build and real smoke tests in the same continuous program.** First inspect actual 11 unsupported Entity records and 108 historic voice references privately rather than creating more abstract speculation. See `21_CURRENT_STATE.md` for provenance, not for a new planning freeze.

## Required handoff

Return observed upstream SHA/drift, starting Desktop SHA, branch, final commit SHA, changed files by responsibility, exact tests/compiles, unverified items, known risks, scope deviations and clean working-tree status.

Do not claim Project review PASS yourself. **Do not stop development awaiting interim Project review.** Deliver working code/tested SHA per phase and continue to other requirements. Route specific manual GUI/Android steps and findings directly to the user; provide one concise integrated final Preview candidate for Project's post-implementation release review.
