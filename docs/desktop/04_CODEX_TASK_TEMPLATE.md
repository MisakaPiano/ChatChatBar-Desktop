# Project -> Codex task template

## Task
<name>

## Recommended Codex profile
- model:
- thinking:
- expected weekly quota:
- reason for this profile:

Quota is a scheduling constraint only. Do not reduce required safety, compatibility, recovery or validation to fit the estimate.

## Baseline
- upstream version:
- upstream commit:
- desktop base commit:
- phase / contract document:

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
List directories/files/classes.

## Explicitly forbidden
List work that belongs to later slices or would broaden the task unnecessarily.

## Must not change
List Package/Prompt/WorldBook/storage/model/migration/Android invariants.

## Implementation rules
- smallest scoped change
- prefer shared extraction over duplicate domain logic
- keep platform differences behind adapters
- use minimum sufficient architecture
- no unrelated refactors
- no credentials in Git
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

## Review gate
- commit and push the feature branch
- do not merge into `desktop`
- wait for Project review

## Return
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
