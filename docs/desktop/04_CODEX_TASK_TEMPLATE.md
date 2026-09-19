# Project -> Codex task template

## Task
<name>

## Baseline
- upstream version:
- upstream commit:
- desktop base commit:

## Goal
Concrete user-visible/runtime outcomes.

## Upstream sources to read
- relevant Skill(s)
- relevant source files
- Desktop compatibility docs

## Allowed modification scope
List directories/files/classes.

## Must not change
List Package/Prompt/WorldBook/storage/model/migration/Android invariants.

## Implementation rules
- smallest scoped change
- prefer shared extraction over duplicate domain logic
- keep platform differences behind adapters
- no unrelated refactors
- no credentials in Git
- do not hide primary-path failures

## Acceptance
- compile/tests
- parity tests
- Android regression if affected
- Desktop smoke test

## Return
- branch
- commit SHA
- changed files
- tests run/results
- unverified items
- known risks
