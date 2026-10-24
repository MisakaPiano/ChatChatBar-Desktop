# Project -> Codex task template (continuous implementation)

**Current user directive / 2026-10-24:** A single P11 + Public Preview A–G assignment may contain multiple self-managed engineering phases. Codex implements/tests/commits/continues after each checkpoint without another Project authorization or interim review. This template is an optional handoff aid, not a stop gate. See `01_PROJECT_INSTRUCTIONS.md` and D-046.

## Task
<name>

## Recommended Codex profile (planning once per umbrella task; no per-phase stop)
- model:
- thinking:
- Program Budget envelope:
- empirical expected runtime:
- empirical expected 5-hour burn:
- empirical expected weekly burn:
- interruption risk:
- prevention / checkpoint measures:
- reason for this profile:

Use `24_CODEX_USAGE_EMPIRICAL_BASELINE.md` for empirical ranges and `23_CODEX_BUDGET.md` for allocation/envelope. Keep them distinct.

Quota is a scheduling constraint only. Do not reduce required safety, compatibility, recovery or validation to fit the estimate.

## Baseline
- upstream version:
- upstream commit:
- desktop base commit:
- current continuous objective / last accepted evidence:

## Control-point check
Before edits:
- fetch `origin` and `upstream`
- verify current branch / HEAD / clean working tree
- compare formal validated baseline with observed `upstream/master`
- classify drift using D-022
- stop only for `BLOCKING` drift; report `HIGH` drift for the next sync window unless it invalidates this task
- never advance `10_UPSTREAM_BASELINE.json` without required sync validation

## Goal
Concrete user-visible/runtime outcomes.

## Upstream sources to read
- relevant Skill(s)
- relevant source files
- Desktop compatibility docs
- relevant phase contract/audit

## Allowed modification scope
List concrete outcome and affected domains for the **overall task**, not artificial one-file per-phase restrictions. Codex can edit all relevant production owners to complete the approved outcome.

## Explicitly forbidden
Exclude unrelated future 1.0/P12–P17 scope, but **do not** exclude changes required to finish P11 and Preview A–G merely because they belong to another internal phase.

## Must not change
List Package/Prompt/WorldBook/storage/model/migration/Android invariants.

## Implementation rules
- smallest sufficient architecture for the complete user-visible outcome; bundle dependent fixes into a coherent implementation
- prefer shared extraction over duplicate domain logic
- keep platform differences behind adapters
- use minimum sufficient architecture
- no unrelated refactors
- no real credentials/passwords or user .cbsave/.cbbackup/.cbback archives/unpacked data anywhere in GitHub, including history, logs, attachments, Actions cache/artifacts and Releases; use ignored local files and check staged/package contents
- do not hide primary-path failures
- do not copy upstream Prompt text into a second Desktop-owned implementation
- do not treat a Package DTO as a persisted Entity

## Acceptance
- focused tests
- shared tests when shared production changes
- Android regression when Android/shared behavior is affected
- Desktop compile/tests
- parity/schema fixtures where relevant
- packaged/manual smoke test when required
- `git diff --check`

## Checkpoint and final review
- finish real code + relevant tests for the phase, commit and push the same feature branch
- record only SHA, affected capabilities, pass/fail evidence and next work; **continue the next independent phase immediately**
- execute already authorized Fork integration with appropriate checks and actual repository branch protections; no additional Project preapproval
- Project reviews delivered implementation after the fact; unresolved issues are Codex fix-list items, not automatic freezes on unrelated implementation
- present runnable Windows/Android manual validation directly to the user, without requiring Project to relay it
- integrated Public Preview candidate is signed only after actually satisfying A–G and release authorization, never by labeling tests GREEN

## Return (brief checkpoint, detailed only for final candidate)
- observed upstream SHA + drift classification
- starting Desktop SHA
- branch
- commit SHA
- changed files grouped by responsibility
- tests run/results
- parity/schema fixture results
- behavior intentionally unchanged
- unresolved dependencies
- unverified items
- known risks
- working-tree status

## Quota telemetry
Codex must not guess account quota. The user/Project records actual runtime, 5-hour allowance before/after and weekly allowance before/after separately, then recalibrates future estimates.
